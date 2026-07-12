package io.momu.frpmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class EmbeddedFrpScriptsTest {

    @Test
    public void embeddedSetupUsesUploadedComponentsAndAProtectedLocalControlPort() throws Exception {
        String script = script("setup_env.sh");
        assertTrue(script.contains("FRPC_TEMP_PATH=\"/root/frpc_temp_upload\""));
        assertTrue(script.contains("FRPS_TEMP_PATH=\"/root/frps_temp_upload\""));
        assertTrue(script.contains("install -m 0755 \"$FRPC_TEMP_PATH\" \"$FRPC_PATH\""));
        assertTrue(script.contains("install -m 0755 \"$FRPS_TEMP_PATH\" \"$FRPS_PATH\""));
        assertTrue(script.contains("bindAddr = \"127.0.0.1\""));
        assertTrue(script.contains("bindPort = 7000"));
        assertTrue(script.contains("proxyBindAddr = \"0.0.0.0\""));
        assertTrue(script.contains("回环控制端与对外代理端口"));
        assertTrue(script.contains("auth.method = \"token\""));
        assertTrue(script.contains("od -An -N32 -tx1 /dev/urandom"));
        assertTrue(script.contains("Requires=frps.service"));
        assertTrue(script.contains("拒绝覆盖"));
        assertTrue(script.contains("managed_config_safety_error"));
        assertTrue(script.contains("已停用以避免同机转发冲突"));
        assertTrue(script.contains("127.0.0.1:7000 已被非受管服务占用"));
        assertTrue(script.indexOf("127.0.0.1:7000 已被非受管服务占用")
            < script.indexOf("install -m 0755 \"$FRPC_TEMP_PATH\" \"$FRPC_PATH\""));
        assertTrue(script.indexOf("127.0.0.1:7000 已被非受管服务占用")
            < script.indexOf("install -d -m 0700 \"$FRP_DIR\""));
        assertTrue(script.contains("迁移本工具端口配置到受管 frps"));
        assertTrue(!script.contains("apt-get") && !script.contains("yum install") && !script.contains("curl http"));
    }

    @Test
    public void embeddedScriptsAreValidBash() throws Exception {
        for (String name : new String[] {"setup_env.sh", "check_env.sh", "cleanup.sh"}) {
            Process process = new ProcessBuilder("bash", "-n", scriptFile(name).getAbsolutePath()).start();
            assertEquals("Invalid embedded Bash script: " + name, 0, process.waitFor());
        }
    }

    private String script(String name) throws Exception {
        return new String(Files.readAllBytes(scriptFile(name).toPath()), StandardCharsets.UTF_8);
    }

    private File scriptFile(String name) {
        return new File("src/main/assets/scripts", name);
    }
}
