package io.momu.frpmanager.manager.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.momu.frpmanager.FirewallManager;
import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.IptablesManager;
import io.momu.frpmanager.UfwManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.Test;

public class FrpServerCommandFactoryFirewallTest {

    @Test
    public void keepsTcpAndUdpRulesIndependentForAllowAndClose() {
        FrpServerCommandFactory factory = new FrpServerCommandFactory(null);
        FrpProfile tcp = profile(6100, "tcp");
        FrpProfile udp = profile(6100, "udp");

        String allow = factory.buildBatchFirewallCommand("allow", Arrays.asList(tcp, udp), new IptablesManager());
        assertTrue(allow.contains("frp-tool:6100/tcp"));
        assertTrue(allow.contains("frp-tool:6100/udp"));

        String close = factory.buildBatchFirewallCommand("block", Arrays.asList(tcp, udp), new IptablesManager());
        assertTrue(close.contains("-D FRP_TOOL -p tcp --dport 6100"));
        assertTrue(close.contains("-D FRP_TOOL -p udp --dport 6100"));
    }

    @Test
    public void singlePortOperationDoesNotTouchTheOtherProtocol() {
        FrpServerCommandFactory factory = new FrpServerCommandFactory(null);
        String command = factory.buildSingleFirewallCommand(profile(6200, "tcp"), "allow", new IptablesManager());

        assertTrue(command.contains("frp-tool:6200/tcp"));
        assertFalse(command.contains("frp-tool:6200/udp"));
    }

    @Test
    public void ufwRemovalParsesSpacedAndDoubleDigitRuleNumbers() throws Exception {
        Path deleted = state("");
        String function = "ufw() { if [ \"$1\" = status ]; then printf '%s\\n' "
            + "'[ 7] 33334/tcp ALLOW IN Anywhere # frp-tool:33334/tcp' "
            + "'[42] 33334/tcp (v6) ALLOW IN Anywhere (v6) # frp-tool:33334/tcp'; "
            + "else printf '%s\\n' \"$3\" >> '" + deleted + "'; fi; }; ";
        String command = function + new UfwManager().removePortRuleCommand(33334, "tcp");

        assertEquals(0, run(command));
        assertEquals("42\n7\n", read(deleted));
    }

    @Test
    public void ufwExistenceRequiresAnExactAllowRule() {
        String query = new UfwManager().ruleExistsCommand(33334, "tcp");
        assertTrue(query.contains("33334/tcp"));
        assertTrue(query.contains("ALLOW IN"));
        assertTrue(query.contains("frp-tool:33334/tcp"));
    }

    @Test
    public void existingAllowSurvivesReloadFailure() throws Exception {
        assertReloadFailureRestores("allow", "present", "present");
    }

    @Test
    public void missingBlockDoesNotCreateRuleWhenReloadFails() throws Exception {
        assertReloadFailureRestores("block", "absent", "absent");
    }

    @Test
    public void batchExistingAllowSurvivesReloadFailure() throws Exception {
        assertBatchReloadFailureRestores("allow", "present", "present");
    }

    @Test
    public void batchMissingBlockDoesNotCreateRuleWhenReloadFails() throws Exception {
        assertBatchReloadFailureRestores("block", "absent", "absent");
    }

    @Test
    public void successfulAllowIsVerifiedPresent() throws Exception {
        Path state = state("absent");
        String command = new FrpServerCommandFactory(null).buildSingleFirewallCommand(
            profile(6300, "tcp"), "allow", new FileFirewallManager(state, true, true, true));

        assertEquals(0, run(command));
        assertEquals("present", read(state));
    }

    @Test
    public void successfulBlockIsVerifiedAbsent() throws Exception {
        Path state = state("present");
        String command = new FrpServerCommandFactory(null).buildSingleFirewallCommand(
            profile(6301, "tcp"), "block", new FileFirewallManager(state, true, true, true));

        assertEquals(0, run(command));
        assertEquals("absent", read(state));
    }

    @Test
    public void allowFailsWhenMutationDidNotCreateRule() throws Exception {
        Path state = state("absent");
        String command = new FrpServerCommandFactory(null).buildSingleFirewallCommand(
            profile(6302, "tcp"), "allow", new FileFirewallManager(state, true, false, true));

        assertEquals(1, run(command));
        assertEquals("absent", read(state));
    }

    @Test
    public void blockFailsWhenMutationDidNotRemoveRule() throws Exception {
        Path state = state("present");
        String command = new FrpServerCommandFactory(null).buildSingleFirewallCommand(
            profile(6303, "tcp"), "block", new FileFirewallManager(state, true, true, false));

        assertEquals(1, run(command));
        assertEquals("present", read(state));
    }

    private void assertReloadFailureRestores(String operation, String initial, String expected) throws Exception {
        Path state = state(initial);
        String command = new FrpServerCommandFactory(null).buildSingleFirewallCommand(
            profile(6400, "tcp"), operation, new FileFirewallManager(state, false, true, true));

        assertEquals(1, run(command));
        assertEquals(expected, read(state));
    }

    private void assertBatchReloadFailureRestores(String operation, String initial, String expected) throws Exception {
        Path state = state(initial);
        String command = new FrpServerCommandFactory(null).buildBatchFirewallCommand(
            operation, Arrays.asList(profile(6401, "tcp")), new FileFirewallManager(state, false, true, true));

        assertEquals(1, run(command));
        assertEquals(expected, read(state));
    }

    private Path state(String value) throws Exception {
        Path path = Files.createTempFile("frp-firewall-state-", ".txt");
        Files.write(path, value.getBytes(StandardCharsets.UTF_8));
        path.toFile().deleteOnExit();
        return path;
    }

    private String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private int run(String command) throws Exception {
        Process process = new ProcessBuilder("zsh", "-c", command)
            .redirectOutput(new java.io.File("/dev/null"))
            .redirectError(new java.io.File("/dev/null"))
            .start();
        return process.waitFor();
    }

    private static final class FileFirewallManager implements FirewallManager {
        private final String statePath;
        private final boolean reloadSucceeds;
        private final boolean addChangesState;
        private final boolean removeChangesState;

        FileFirewallManager(Path statePath, boolean reloadSucceeds,
            boolean addChangesState, boolean removeChangesState) {
            this.statePath = "'" + statePath.toString().replace("'", "'\"'\"'") + "'";
            this.reloadSucceeds = reloadSucceeds;
            this.addChangesState = addChangesState;
            this.removeChangesState = removeChangesState;
        }

        @Override
        public String ruleExistsCommand(int port, String protocol) {
            return "[ \"$(cat " + statePath + ")\" = present ]";
        }

        @Override
        public String addPortRuleCommand(int port, String protocol) {
            return addChangesState ? "printf present > " + statePath : "true";
        }

        @Override
        public String removePortRuleCommand(int port, String protocol) {
            return removeChangesState ? "printf absent > " + statePath : "true";
        }

        @Override
        public String reloadFirewallCommand() {
            return reloadSucceeds ? "true" : "false";
        }

        @Override
        public String getType() {
            return "test";
        }
    }

    private FrpProfile profile(int port, String protocol) {
        FrpProfile profile = new FrpProfile();
        profile.setRemotePort(port);
        profile.setProtocol(protocol);
        return profile;
    }
}
