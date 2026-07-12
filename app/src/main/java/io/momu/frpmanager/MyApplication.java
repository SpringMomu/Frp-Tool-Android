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
		// Keep platform crypto providers (especially AndroidKeyStore) ahead of BC.
		// BC is only needed here for SSH PEM parsing and must not claim keystore ciphers.
		Security.addProvider(new BouncyCastleProvider());
	}
}
