package io.momu.frpmanager;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SshSettingsManagerDefaultsTest {

    @Test
    public void sshHostIsNeverInventedAsTheFrpsAddress() {
        assertEquals("", SshSettingsManager.defaultFrpServerAddress("103.39.64.145"));
    }
}
