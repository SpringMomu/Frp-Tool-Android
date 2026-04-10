package io.momu.frpmanager.manager.util;

import androidx.annotation.Nullable;
import io.momu.frpmanager.FrpProfile;
import java.util.Locale;

public final class FrpProfileKey {

    private FrpProfileKey() {
    }

    public static String from(@Nullable FrpProfile profile) {
        if (profile == null) {
            return "";
        }
        return from(profile.getRemotePort(), profile.getProtocol());
    }

    public static String from(int port, @Nullable String protocol) {
        if (protocol == null || protocol.trim().isEmpty()) {
            return "";
        }
        return port + "/" + protocol.toLowerCase(Locale.US);
    }
}
