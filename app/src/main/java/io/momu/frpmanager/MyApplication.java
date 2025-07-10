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
        Security.removeProvider("BC");
        Security.insertProviderAt(new BouncyCastleProvider(), 1);
    }
}