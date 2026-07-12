package io.momu.frpmanager.manager.data;

import static org.junit.Assert.assertEquals;

import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.FirewallDManager;
import io.momu.frpmanager.IptablesManager;
import io.momu.frpmanager.UfwManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.Test;

public class FrpServerCommandFactoryShellSyntaxTest {

    @Test
    public void loadProfilesCommandParsesInZsh() throws Exception {
        String command = new FrpServerCommandFactory(null).buildLoadProfilesCommand();
		assertParsesInZsh(command);
    }

    @Test
    public void lifecycleAndFirewallCommandsParseInZsh() throws Exception {
        FrpServerCommandFactory factory = new FrpServerCommandFactory(null);
        FrpProfile profile = profile();
        assertParsesInZsh(factory.buildBatchOperationCommand(
            "start", Collections.singletonList(profile), false, null).getCommand());
        assertParsesInZsh(factory.buildBatchOperationCommand(
            "restart", Collections.singletonList(profile), false, null).getCommand());
        assertParsesInZsh(factory.buildBatchOperationCommand(
            "delete", Collections.singletonList(profile), false, null).getCommand());
        assertParsesInZsh(factory.buildSingleFirewallCommand(profile, "allow", new IptablesManager()));
        assertParsesInZsh(factory.buildBatchFirewallCommand(
            "block", Collections.singletonList(profile), new IptablesManager()));
        assertParsesInZsh(factory.buildSingleFirewallCommand(profile, "block", new UfwManager()));
        assertParsesInZsh(factory.buildBatchFirewallCommand(
            "allow", Collections.singletonList(profile), new UfwManager()));
        assertParsesInZsh(factory.buildSingleFirewallCommand(profile, "allow", new FirewallDManager("public")));

        FrpProfile staleManagedProfile = profile();
        staleManagedProfile.setLocalPort(6099);
        staleManagedProfile.setSourcePath("/etc/frp/conf.d/port_6100_tcp.ini.disabled");
        FrpServerCommandFactory managedFactory = new FrpServerCommandFactory(null, true);
        assertParsesInZsh(managedFactory.buildBatchOperationCommand(
            "start", Collections.singletonList(staleManagedProfile), false, null).getCommand());

        FrpProfile renamedProfile = profile();
        renamedProfile.setLocalPort(6099);
        renamedProfile.setRemotePort(6101);
        renamedProfile.setSourcePath("/etc/frp/conf.d/port_6100_tcp.ini");
        renamedProfile.setUnitName("frpc@6100_tcp.service");
        renamedProfile.setPersistedRemotePort(6100);
        renamedProfile.setPersistedProtocol("tcp");
        assertParsesInZsh(factory.buildApplySingleProfileChangeCommand(
            renamedProfile, false, false, null));
    }

    private void assertParsesInZsh(String command) throws Exception {
        Process process = new ProcessBuilder("zsh", "-n", "-c", command).start();
        String stderr = readFully(process.getErrorStream());

        assertEquals("Generated command must be valid zsh syntax: " + stderr, 0, process.waitFor());
    }

    private FrpProfile profile() {
        FrpProfile profile = new FrpProfile();
        profile.setServerAddr("frps.example.test");
        profile.setServerPort(7000);
        profile.setRemotePort(6100);
        profile.setLocalIp("127.0.0.1");
        profile.setLocalPort(6100);
        profile.setProtocol("tcp");
        profile.setToken("test-token");
        return profile;
    }

    private String readFully(InputStream input) throws Exception {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
