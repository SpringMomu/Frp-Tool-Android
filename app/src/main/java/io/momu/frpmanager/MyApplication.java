package io.momu.frpmanager;

import android.app.Application;
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public class MyApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        setupBouncyCastle();
    }

    private void setupBouncyCastle() {
        // Ensure that the bundled Bouncy Castle Provider is used,
        // prioritizing it over any potentially incomplete system-provided versions.
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }
}