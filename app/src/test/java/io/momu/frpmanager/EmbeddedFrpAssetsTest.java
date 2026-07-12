package io.momu.frpmanager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class EmbeddedFrpAssetsTest {

    @Test
    public void embeddedFrpcAndFrpsArePresentAndMatchPinnedChecksums() throws Exception {
        File assets = new File("src/main/assets");
        assertAssetMatches(assets, "frpc", "aarch64/frpc");
        assertAssetMatches(assets, "frpc", "x86_64/frpc");
        assertAssetMatches(assets, "frps", "aarch64/frps");
        assertAssetMatches(assets, "frps", "x86_64/frps");
    }

    @Test
    public void embeddedFrpcAndFrpsUseTheSamePinnedReleaseVersion() throws Exception {
        File assets = new File("src/main/assets");
        for (String architecture : new String[] {"aarch64", "x86_64"}) {
            byte[] frpc = readPrefix(new File(assets, "frpc/" + architecture + "/frpc"), 4096);
            byte[] frps = readPrefix(new File(assets, "frps/" + architecture + "/frps"), 4096);
            assertTrue(frpc.length > 4 && frpc[0] == 0x7f && frpc[1] == 'E' && frpc[2] == 'L' && frpc[3] == 'F');
            assertTrue(frps.length > 4 && frps[0] == 0x7f && frps[1] == 'E' && frps[2] == 'L' && frps[3] == 'F');
        }
    }

    private void assertAssetMatches(File assets, String component, String relativePath) throws Exception {
        File asset = new File(assets, component + "/" + relativePath);
        assertTrue("Missing embedded " + component + " asset: " + relativePath, asset.isFile());
        String expected = checksums(new File(assets, component + "/checksums.txt")).get(relativePath);
        assertNotNull("Missing pinned checksum for " + component + "/" + relativePath, expected);
        assertEquals(expected, sha256(asset));
    }

    private Map<String, String> checksums(File file) throws Exception {
        Map<String, String> values = new HashMap<>();
        String content = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        for (String line : content.split("\\R")) {
            String[] fields = line.trim().split("\\s+", 2);
            if (fields.length == 2) values.put(fields[1], fields[0]);
        }
        return values;
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder();
        for (byte item : digest.digest()) value.append(String.format("%02x", item));
        return value.toString();
    }

    private byte[] readPrefix(File file, int length) throws Exception {
        byte[] value = new byte[length];
        try (InputStream input = new FileInputStream(file)) {
            int count = input.read(value);
            if (count == length) return value;
            byte[] truncated = new byte[Math.max(count, 0)];
            if (count > 0) System.arraycopy(value, 0, truncated, 0, count);
            return truncated;
        }
    }
}
