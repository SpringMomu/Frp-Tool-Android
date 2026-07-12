package io.momu.frpmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class RemoteScriptCommandTest {

    @Test
    public void bundledBashScriptParsesAndRunsThroughZshLoginShell() throws Exception {
        String command = RemoteScriptCommand.bash("set -euo pipefail\nprintf 'wrapped-bash-ok\\n'");
        Process syntax = new ProcessBuilder("zsh", "-n", "-c", command).start();
        assertEquals(0, syntax.waitFor());

        Process execution = new ProcessBuilder("zsh", "-c", command).start();
        String output = readFully(execution.getInputStream());
        assertEquals(0, execution.waitFor());
        assertTrue(output.contains("wrapped-bash-ok"));
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
