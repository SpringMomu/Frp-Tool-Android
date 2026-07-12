package io.momu.frpmanager.manager.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.IptablesManager;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class FrpServerCommandFactoryLifecycleTest {

    @Test
    public void preflightsEndpointBeforeEnablingAndClassifiesUnreachableFrps() {
        FrpServerCommandFactory factory = new FrpServerCommandFactory(null);
        String command = factory.buildBatchOperationCommand(
            "start", Collections.singletonList(profile(33334, "tcp")), false, null).getCommand();

        int verify = command.indexOf("frpc verify");
        int preflight = command.indexOf("/dev/tcp/$1/$2");
        int enable = command.indexOf("systemctl enable --now");
        assertTrue(verify >= 0);
        assertTrue(preflight > verify);
        assertTrue(enable > preflight);
        assertTrue(command.contains("FRP_MANAGER_REASON:FRPS_UNREACHABLE"));
        assertTrue(command.contains("目标 frps 端点 103.39.64.145:7000 不可达"));
        assertTrue(command.contains("托管模式请修复 frps.service"));
    }

    @Test
    public void everyGeneratedStartErrorStopsAndDisablesTheUnit() {
        String command = new FrpServerCommandFactory(null).buildBatchOperationCommand(
            "start", Collections.singletonList(profile(33334, "tcp")), false, null).getCommand();

        assertEquals(count(command, "FRP_MANAGER_STATUS:ERROR"),
            count(command, "systemctl disable --now 'frpc@33334_tcp.service'"));
        assertTrue(command.contains("systemctl reset-failed 'frpc@33334_tcp.service'"));
        assertTrue(command.contains("FRP_MANAGER_REASON:CONFIG_INVALID"));
        assertTrue(command.contains("FRP_MANAGER_REASON:PROXY_REGISTRATION_UNCONFIRMED"));
        assertTrue(command.contains("FRP_REASON='REMOTE_PORT_CONFLICT'"));
        assertTrue(command.contains("FRP_REASON='AUTHENTICATION_FAILED'"));
        assertTrue(command.contains("FRP_REASON='PROXY_REGISTRATION_FAILED'"));
    }

    @Test
    public void managedSameHostSamePortProfileIsRejectedBeforeEndpointOrServiceStart() {
        FrpProfile profile = profile(33334, "tcp");
        profile.setServerAddr("127.0.0.1");
        String command = new FrpServerCommandFactory(null).buildBatchOperationCommand(
            "start", Collections.singletonList(profile), false, null).getCommand();

        int rejection = command.indexOf("FRP_MANAGER_REASON:LOCAL_PORT_LOOP");
        int preflight = command.indexOf("/dev/tcp/$1/$2");
        int enable = command.indexOf("systemctl enable --now");
        assertTrue(rejection >= 0);
        assertTrue(preflight > rejection);
        assertTrue(enable > rejection);
        assertTrue(command.contains("systemctl disable --now 'frpc@33334_tcp.service'"));
    }

    @Test
    public void managedModeRepairsAStaleExternalProfileAndStartsLocalFrpsBeforePreflight() {
        FrpProfile profile = profile(33334, "tcp");
        profile.setLocalPort(33333);
        profile.setSourcePath("/etc/frp/conf.d/port_33334_tcp.ini.disabled");
        String command = new FrpServerCommandFactory(null, true).buildBatchOperationCommand(
            "start", Collections.singletonList(profile), false, null).getCommand();
        String content = new FrpServerCommandFactory(null, true).buildFileContent(profile);

        assertTrue(command.contains("FRP_HOST='127.0.0.1'; FRP_PORT=7000"));
        assertTrue(command.contains("systemctl enable --now frps.service"));
        assertTrue(command.indexOf("systemctl enable --now frps.service") < command.indexOf("/dev/tcp/$1/$2"));
        assertTrue(command.contains("CONFIG_REPAIR_FAILED"));
        assertTrue(command.contains("target='/etc/frp/conf.d/port_33334_tcp.ini.disabled'"));
        assertFalse(command.contains("目标 frps 端点 103.39.64.145:7000 不可达"));
        assertTrue(content.contains("server_addr = 127.0.0.1"));
        assertTrue(content.contains("server_port = 7000"));
    }

    @Test
    public void managedModeDoesNotLetAStaleExternalAddressBypassLoopProtection() {
        FrpProfile profile = profile(33334, "tcp");
        String command = new FrpServerCommandFactory(null, true).buildBatchOperationCommand(
            "start", Collections.singletonList(profile), false, null).getCommand();

        assertTrue(command.contains("FRP_MANAGER_REASON:LOCAL_PORT_LOOP"));
        assertFalse(command.contains("systemctl enable --now frps.service"));
        assertFalse(command.contains("目标 frps 端点 103.39.64.145:7000 不可达"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void managedSameHostSamePortProfileCannotBeWritten() {
        FrpProfile profile = profile(33334, "tcp");
        profile.setServerAddr("127.0.0.1");
        new FrpServerCommandFactory(null).buildFileContent(profile);
    }

    @Test(expected = IllegalArgumentException.class)
    public void managedControlPortCannotBeUsedAsAProxyPort() {
        FrpProfile profile = profile(7000, "tcp");
        profile.setServerAddr("127.0.0.1");
        profile.setLocalPort(38080);
        new FrpServerCommandFactory(null).buildFileContent(profile);
    }

    @Test
    public void applyingConfigurationContainsNoFirewallMutation() {
        FrpServerCommandFactory factory = new FrpServerCommandFactory(null);
        FrpProfile first = profile(33334, "tcp");
        FrpProfile second = profile(33335, "udp");
        String single = factory.buildApplySingleProfileChangeCommand(first, false, true, new IptablesManager());
        String batch = factory.buildApplyChangesCommand(Arrays.asList(first, second), true, new IptablesManager());

        for (String command : Arrays.asList(single, batch)) {
            assertFalse(command.contains("frp-tool:"));
            assertFalse(command.contains("iptables"));
            assertFalse(command.contains("firewall-cmd"));
            assertFalse(command.contains("ufw "));
        }
    }

    @Test
    public void remotePortMigrationStopsOldUnitWritesNewFileAndRemovesOldFile() {
        FrpServerCommandFactory factory = new FrpServerCommandFactory(null);
        FrpProfile renamed = persistedProfile(33334, 33335, "tcp");

        String command = factory.buildApplySingleProfileChangeCommand(renamed, false, false, null);

        assertTrue(command.contains("source='/etc/frp/conf.d/port_33334_tcp.ini'"));
        assertTrue(command.contains("target='/etc/frp/conf.d/port_33335_tcp.ini'"));
        assertTrue(command.contains("old_unit='frpc@33334_tcp.service'"));
        assertTrue(command.contains("systemctl disable --now \"$old_unit\""));
        assertTrue(command.contains("/usr/local/bin/frpc verify -c \"$tmp\""));
        assertTrue(command.contains("mv -f \"$tmp\" \"$target\""));
        assertTrue(command.contains("rm -f \"$source\""));
        assertTrue(command.contains("systemctl daemon-reload"));
    }

    @Test
    public void remotePortMigrationRefusesToOverwriteExistingTarget() {
        String command = new FrpServerCommandFactory(null).buildApplySingleProfileChangeCommand(
            persistedProfile(33334, 33335, "tcp"), false, false, null);

        assertTrue(command.contains("[ \"$source\" = \"$target\" ] || [ ! -e \"$target\" ]"));
        assertTrue(command.contains("目标端口配置已存在，拒绝覆盖。"));
        assertTrue(command.indexOf("拒绝覆盖") < command.indexOf("mv -f \"$tmp\" \"$target\""));
    }

    @Test
    public void migratedConfigurationIsVerifiedAtItsNewPath() {
        String command = new FrpServerCommandFactory(null).buildVerifyConfigFilesCommand(
            Collections.singletonList(persistedProfile(33334, 33335, "tcp")));

        assertTrue(command.contains("/etc/frp/conf.d/port_33335_tcp.ini"));
        assertFalse(command.contains("/etc/frp/conf.d/port_33334_tcp.ini"));
    }

    @Test
    public void persistedProfileWithLoadErrorStillGeneratesVerifiedDelete() {
        FrpProfile profile = persistedProfile(33334, 33334, "tcp");
        profile.setLastError("配置冲突");

        String command = new FrpServerCommandFactory(null).buildBatchOperationCommand(
            "delete", Collections.singletonList(profile), false, null).getCommand();

        assertTrue(command.contains("systemctl disable --now 'frpc@33334_tcp.service'"));
        assertTrue(command.contains("rm -f '/etc/frp/conf.d/port_33334_tcp.ini'"));
        assertTrue(command.contains("FRP_MANAGER_REASON:DELETE_VERIFY_FAILED"));
    }

    private int count(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    private FrpProfile profile(int port, String protocol) {
        FrpProfile profile = new FrpProfile();
        profile.setServerAddr("103.39.64.145");
        profile.setServerPort(7000);
        profile.setRemotePort(port);
        profile.setLocalIp("127.0.0.1");
        profile.setLocalPort(port);
        profile.setProtocol(protocol);
        profile.setToken("test-token");
        return profile;
    }

    private FrpProfile persistedProfile(int oldPort, int newPort, String protocol) {
        FrpProfile profile = profile(newPort, protocol);
        profile.setLocalPort(38080);
        profile.setSourcePath("/etc/frp/conf.d/port_" + oldPort + "_" + protocol + ".ini");
        profile.setUnitName("frpc@" + oldPort + "_" + protocol + ".service");
        profile.setPersistedRemotePort(oldPort);
        profile.setPersistedProtocol(protocol);
        profile.setRawConfigContent("[common]\nserver_addr = 103.39.64.145\nserver_port = 7000\n"
            + "token = test-token\n\n[proxy]\ntype = " + protocol + "\nlocal_ip = 127.0.0.1\n"
            + "local_port = 38080\nremote_port = " + oldPort + "\n");
        return profile;
    }
}
