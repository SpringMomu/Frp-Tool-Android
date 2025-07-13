package io.momu.frpmanager;

import android.util.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import net.schmizz.sshj.*;
import net.schmizz.sshj.connection.channel.direct.*;
import net.schmizz.sshj.sftp.*;
import net.schmizz.sshj.userauth.keyprovider.*;
import org.bouncycastle.jce.provider.*;
import org.bouncycastle.openssl.*;
import org.bouncycastle.openssl.jcajce.*;

public class SshManager {

	private final String host;
	private final int port;
	private final String username;
	private final String password;
	private final String privateKey;
	private final String passphrase;

	private volatile SSHClient client;

	private final Object lock = new Object();

	public SshManager(String host, int port, String username, String password) {
		this(host, port, username, password, null, null);
	}

	public SshManager(String host, int port, String username, String privateKey, String passphrase) {
		this(host, port, username, null, privateKey, passphrase);
	}

	private SshManager(String host, int port, String username, String password, String privateKey, String passphrase) {
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		this.privateKey = privateKey;
		this.passphrase = passphrase;
	}

	private void authenticate() throws IOException {
		if (privateKey != null && !privateKey.isEmpty()) {
			try {
				PEMParser pemParser = new PEMParser(new StringReader(privateKey));
				Object pemObject = pemParser.readObject();
				pemParser.close();

				KeyPair keyPair; 
				JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

				if (pemObject instanceof PEMEncryptedKeyPair) {
					if (passphrase == null || passphrase.isEmpty()) {
						throw new IOException("Private key is encrypted, but no passphrase was provided.");
					}
					JcePEMDecryptorProviderBuilder decryptorBuilder = new JcePEMDecryptorProviderBuilder();
					PEMKeyPair decryptedKeyPair = ((PEMEncryptedKeyPair) pemObject).decryptKeyPair(decryptorBuilder.build(passphrase.toCharArray()));
					keyPair = converter.getKeyPair(decryptedKeyPair);

				} else if (pemObject instanceof PEMKeyPair) {
					keyPair = converter.getKeyPair((PEMKeyPair) pemObject);

				} else {
					throw new IOException("Unsupported private key format: " + pemObject.getClass().getName());
				}

				client.authPublickey(username, new KeyPairWrapper(keyPair));

			} catch (Exception e) {
				throw new IOException("Failed to parse or use the private key. Incorrect passphrase or corrupted key.", e);
			}
		} else if (password != null && !password.isEmpty()) {
			client.authPassword(username, password);
		} else {
			throw new IOException("No authentication method available.");
		}
	}

	private void ensureConnected() throws IOException {
		synchronized (lock) {
			if (client == null || !client.isConnected()) {
				client = new SSHClient();
				client.addHostKeyVerifier(new net.schmizz.sshj.transport.verification.PromiscuousVerifier());
				client.connect(host, port);
				authenticate();
			} else if (!client.isAuthenticated()) {
				authenticate();
			}
		}
	}

	public void forceDisconnect() throws IOException {
		synchronized (lock) {
			if (client != null && client.isConnected()) {
				client.disconnect();
			}
			client = null;
		}
	}

	public String executeCommand(String command, long timeoutSeconds) throws IOException {
		if (host == null || host.isEmpty() || username == null || username.isEmpty()
				|| ((password == null || password.isEmpty()) && (privateKey == null || privateKey.isEmpty()))) {
			throw new IOException("SSH 连接信息不完整，请先进行配置。");
		}

		ensureConnected();

		synchronized (lock) {
			if (client == null || !client.isConnected()) {
				throw new IOException("SSH client is not connected.");
			}

			try (Session session = client.startSession()) {
				final Session.Command cmd = session.exec(command);
				cmd.join(timeoutSeconds, TimeUnit.SECONDS);

				String stderr = readStream(cmd.getErrorStream());
				if (stderr != null && !stderr.isEmpty()) {
					Log.e("SshManager_stderr", "Command: " + command + "\nError: " + stderr);
				}
				return readStream(cmd.getInputStream());
			}
		}
	}

	public String executeCommand(String command) throws IOException {
		return executeCommand(command, 15);
	}

	public void disconnect() throws IOException {
		forceDisconnect();
	}

	private String readStream(InputStream stream) {
		try (Scanner s = new Scanner(stream).useDelimiter("\\A")) {
			return s.hasNext() ? s.next() : "";
		}
	}

	public static class CommandStreamer implements Closeable {
		private final Session session;
		private final Session.Command command;

		public CommandStreamer(Session session, Session.Command command) {
			this.session = session;
			this.command = command;
		}

		public InputStream getInputStream() {
			return command.getInputStream();
		}

		@Override
		public void close() throws IOException {
			if (session != null && session.isOpen()) {
				session.close();
			}
		}
	}

	public CommandStreamer executeCommandAndStreamOutput(String command, long timeoutSeconds) throws IOException {
		if (host == null || host.isEmpty() || username == null || username.isEmpty()
				|| ((password == null || password.isEmpty()) && (privateKey == null || privateKey.isEmpty()))) {
			throw new IOException("SSH 连接信息不完整，请先进行配置。");
		}

		ensureConnected();

		synchronized (lock) {
			if (client == null || !client.isConnected()) {
				throw new IOException("SSH client is not connected.");
			}

			Session session = client.startSession();
			session.allocateDefaultPTY();
			final Session.Command cmd = session.exec(command);

			return new CommandStreamer(session, cmd);
		}
	}

	public SFTPClient getSftpClient() throws IOException {
		if (host == null || host.isEmpty() || username == null || username.isEmpty()
				|| ((password == null || password.isEmpty()) && (privateKey == null || privateKey.isEmpty()))) {
			throw new IOException("SSH 连接信息不完整，请先进行配置。");
		}

		ensureConnected();

		synchronized (lock) {
			if (client == null || !client.isConnected()) {
				throw new IOException("SSH client is not connected.");
			}
			return client.newSFTPClient();
		}
	}
}
