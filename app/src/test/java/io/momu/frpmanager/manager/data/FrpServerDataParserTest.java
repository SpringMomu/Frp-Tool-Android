package io.momu.frpmanager.manager.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.manager.model.PortExecutionStatus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public class FrpServerDataParserTest {

    @Test
    public void keepsTcpAndUdpAsIndependentProfilesAndOnlyReportsOwnedFirewallRules() throws Exception {
        FrpServerDataParser parser = new FrpServerDataParser();
        String result = file("/etc/frp/conf.d/port_6000_tcp.ini", config(6000, "tcp")) + "\n"
            + file("/etc/frp/conf.d/port_6000_udp.ini", config(6000, "udp")) + "\n"
            + "FRP_RUNNING\tfrpc@6000_tcp.service\n"
            + "FRP_UNIT\tfrpc@6000_udp.service\tenabled\n";
        List<FrpProfile> profiles = parser.parseLoadedProfiles(result, new HashMap<>(), Collections.emptyList(),
            Collections.emptyList(), new HashSet<>());

        assertEquals(2, profiles.size());
        assertEquals("运行中 (注册未验证)", profiles.get(0).getStatus());
        assertEquals("已停止", profiles.get(1).getStatus());
        parser.applyFirewallRuleStatuses(profiles,
            "-A FRP_TOOL -s 10.0.0.0/8 -p tcp --dport 6000 -m comment --comment frp-tool:6000/tcp -j ACCEPT");
        assertEquals(FrpProfile.FIREWALL_ALLOWED, profiles.get(0).getFirewallStatus());
        assertEquals(FrpProfile.FIREWALL_NOT_ALLOWED, profiles.get(1).getFirewallStatus());
    }

    @Test
    public void marksFilenameContentMismatchAndDuplicateAsUnsafe() throws Exception {
        FrpServerDataParser parser = new FrpServerDataParser();
        String result = file("/etc/frp/conf.d/port_7000_tcp.ini", config(7001, "tcp")) + "\n"
            + file("/etc/frp/conf.d/port_7001_tcp.ini", config(7001, "tcp"));
        List<FrpProfile> profiles = parser.parseLoadedProfiles(result, new HashMap<>(), Collections.emptyList(),
            Collections.emptyList(), new HashSet<>());

        assertEquals(2, profiles.size());
        for (FrpProfile profile : profiles) {
            assertEquals("配置冲突", profile.getStatus());
            assertTrue(profile.getLastError().contains("不一致") || profile.getLastError().contains("多个文件"));
        }
    }

    @Test
    public void marksOnlyManagedLoopbackSamePortProfilesAsUnsafe() throws Exception {
        FrpServerDataParser parser = new FrpServerDataParser();
        String managedLoop = "[common]\nserver_addr = 127.0.0.1\nserver_port = 7000\ntoken = secret\n\n"
            + "[proxy]\ntype = tcp\nlocal_ip = 127.0.0.1\nlocal_port = 7200\nremote_port = 7200\n";
        String externalSamePort = config(7201, "tcp");
        String result = file("/etc/frp/conf.d/port_7200_tcp.ini", managedLoop) + "\n"
            + file("/etc/frp/conf.d/port_7201_tcp.ini", externalSamePort);

        List<FrpProfile> profiles = parser.parseLoadedProfiles(result, new HashMap<>(), Collections.emptyList(),
            Collections.emptyList(), new HashSet<>());

        assertEquals("受管映射冲突", profiles.get(0).getStatus());
        assertTrue(profiles.get(0).getLastError().contains("转发回环"));
        assertEquals("已停止", profiles.get(1).getStatus());
    }

    @Test
    public void keepsASafeDisabledProfileAvailableForExplicitReenable() throws Exception {
        FrpServerDataParser parser = new FrpServerDataParser();
        String safeDisabled = "[common]\nserver_addr = 127.0.0.1\nserver_port = 7000\ntoken = secret\n\n"
            + "[proxy]\ntype = tcp\nlocal_ip = 127.0.0.1\nlocal_port = 38080\nremote_port = 38081\n";
        String result = file("/etc/frp/conf.d/port_38081_tcp.ini.disabled", safeDisabled);

        List<FrpProfile> profiles = parser.parseLoadedProfiles(result, new HashMap<>(), Collections.emptyList(),
            Collections.emptyList(), new HashSet<>());

        assertEquals("已禁用（配置文件已禁用）", profiles.get(0).getStatus());
        assertEquals(null, profiles.get(0).getLastError());
    }

    @Test
    public void parsesEveryBatchOperationUsingMachineReadableKey() {
        FrpServerDataParser parser = new FrpServerDataParser();
        String output = "======== PROCESSING PORT 8000/TCP (stop) ========\n"
            + "FRP_MANAGER_STATUS:SUCCESS\nFRP_MANAGER_KEY:8000/tcp\n"
            + "======== PROCESSING PORT 8001/UDP (delete) ========\n"
            + "FRP_MANAGER_STATUS:ERROR\nFRP_MANAGER_REASON:FRPS_UNREACHABLE\nFRP_MANAGER_KEY:8001/udp\n";
        FrpServerDataParser.BatchOutputParseResult parsed = parser.parseBatchOperationOutput(output);

        assertEquals(2, parsed.getBlocks().size());
        assertEquals(PortExecutionStatus.SUCCESS, parsed.getStatusByProfileKey().get("8000/tcp"));
        assertEquals(PortExecutionStatus.ERROR, parsed.getStatusByProfileKey().get("8001/udp"));
        assertEquals("FRPS_UNREACHABLE", parsed.getBlocks().get(1).getReason());
    }

    @Test
    public void recognizesPartialAtomicApplyResults() {
        FrpServerDataParser parser = new FrpServerDataParser();
        assertEquals(Collections.singletonList("9001/tcp"), parser.parseSuccessfulProfileKeys("APPLY_OK:9001/tcp\n"));
        assertEquals(Collections.singletonList("9002/udp"), parser.parseFailedProfileKeys("APPLY_FAIL:9002/udp\n"));
    }

    @Test
    public void pendingRemotePortRenameReplacesOldServerRowWithOneNewRow() throws Exception {
        FrpProfile renamed = new FrpProfile();
        renamed.setServerAddr("frps.example.test");
        renamed.setServerPort(7000);
        renamed.setRemotePort(33335);
        renamed.setLocalIp("127.0.0.1");
        renamed.setLocalPort(38080);
        renamed.setProtocol("tcp");
        renamed.setSourcePath("/etc/frp/conf.d/port_33334_tcp.ini");
        renamed.setUnitName("frpc@33334_tcp.service");
        renamed.setPersistedRemotePort(33334);
        renamed.setPersistedProtocol("tcp");

        List<FrpProfile> profiles = new FrpServerDataParser().parseLoadedProfiles(
            file("/etc/frp/conf.d/port_33334_tcp.ini", config(33334, "tcp")),
            new HashMap<>(), Collections.emptyList(), Collections.singletonList(renamed), new HashSet<>());

        assertEquals(1, profiles.size());
        assertEquals(33335, profiles.get(0).getRemotePort());
        assertEquals(33334, profiles.get(0).getPersistedRemotePort());
        assertTrue(profiles.get(0).isModified());
    }

    private String file(String path, String content) {
        return "FRP_FILE\t" + encode(path) + "\t" + encode(content);
    }

    private String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String config(int remotePort, String protocol) {
        return "[common]\nserver_addr = frps.example.test\nserver_port = 7000\ntoken = secret\n\n"
            + "[proxy]\ntype = " + protocol + "\nlocal_ip = 127.0.0.1\nlocal_port = " + remotePort
            + "\nremote_port = " + remotePort + "\n";
    }
}
