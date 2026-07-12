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
	private final String expectedHostKeyFingerprint;

	private volatile SSHClient client;

	private final Object lock = new Object();

	public SshManager(String host, int port, String username, String password) {
		this(host, port, username, password, null, null, null);
	}

	public SshManager(String host, int port, String username, String privateKey, String passphrase) {
		this(host, port, username, null, privateKey, passphrase, null);
	}


	public static SshManager password(String host, int port, String username, String password,
		String expectedHostKeyFingerprint) {
		return new SshManager(host, port, username, password, null, null, expectedHostKeyFingerprint);
	}

	public SshManager(String host, int port, String username, String privateKey, String passphrase,
		String expectedHostKeyFingerprint) {
		this(host, port, username, null, privateKey, passphrase, expectedHostKeyFingerprint);
	}

	private SshManager(String host, int port, String username, String password, String privateKey, String passphrase,
		String expectedHostKeyFingerprint) {
		this.host = host;
		this.port = port;
		this.username = username;
		this.password = password;
		this.privateKey = privateKey;
		this.passphrase = passphrase;
		this.expectedHostKeyFingerprint = expectedHostKeyFingerprint;
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
				final String[] offeredFingerprint = new String[1];
				client.addHostKeyVerifier(new net.schmizz.sshj.transport.verification.HostKeyVerifier() {
					@Override public boolean verify(String hostname, int remotePort, PublicKey key) {
						offeredFingerprint[0] = fingerprint(key);
						return expectedHostKeyFingerprint != null && expectedHostKeyFingerprint.equals(offeredFingerprint[0]);
					}
					@Override public List<String> findExistingAlgorithms(String hostname, int remotePort) {
						return Collections.emptyList();
					}
				});
				try {
					client.connect(host, port);
				} catch (IOException exception) {
					closeClientQuietly();
					if (offeredFingerprint[0] != null) {
						throw new HostKeyVerificationException(host, port, offeredFingerprint[0],
							expectedHostKeyFingerprint, exception);
					}
					throw exception;
				}
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
		SshCommandResult result = executeCommandResult(command, timeoutSeconds);
		if (!result.isSuccessful()) {
			throw commandFailure(result);
		}
		return result.getStdout();
	}

	public SshCommandResult executeCommandResult(String command, long timeoutSeconds) throws IOException {
		if (host == null || host.isEmpty() || username == null || username.isEmpty()
				|| ((password == null || password.isEmpty()) && (privateKey == null || privateKey.isEmpty()))) {
			throw new IOException("SSH 连接信息不完整，请先进行配置。");
		}

		ensureConnected();

		synchronized (lock) {
			if (client == null || !client.isConnected()) {
				throw new IOException("SSH client is not connected.");
			}

				long started = System.nanoTime();
				ExecutorService readers = Executors.newFixedThreadPool(2);
				try (Session session = client.startSession()) {
					final Session.Command cmd = session.exec(command);
					Future<String> stdout = readers.submit(() -> readStream(cmd.getInputStream()));
					Future<String> stderr = readers.submit(() -> readStream(cmd.getErrorStream()));
					cmd.join(timeoutSeconds, TimeUnit.SECONDS);
					Integer exitCode = cmd.getExitStatus();
					String exitSignal = cmd.getExitSignal() == null ? null : cmd.getExitSignal().name();
					boolean timedOut = cmd.isOpen();
					if (timedOut) {
						session.close();
					}
					String stdoutText = getFuture(stdout, timedOut ? 2 : 10);
					String stderrText = getFuture(stderr, timedOut ? 2 : 10);
					return new SshCommandResult(stdoutText, stderrText, exitCode, exitSignal, timedOut,
						TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
				} finally {
					readers.shutdownNow();
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

	private String getFuture(Future<String> future, long timeoutSeconds) throws IOException {
		try {
			return future.get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IOException("SSH command output collection interrupted", exception);
		} catch (ExecutionException | TimeoutException exception) {
			throw new IOException("无法读取 SSH 命令输出", exception);
		}
	}

	private IOException commandFailure(SshCommandResult result) {
		String reason = result.isTimedOut()
			? "SSH 命令执行超时并已取消"
			: result.getExitSignal() != null ? "SSH 命令被信号终止：" + result.getExitSignal()
			: "SSH 命令执行失败，退出码=" + result.getExitCode();
		String stderr = SensitiveDataRedactor.redact(result.getStderr()).trim();
		if (!stderr.isEmpty()) {
			reason += ": " + stderr;
		}
		return new IOException(reason);
	}

	private String fingerprint(PublicKey key) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
			return "SHA256:" + java.util.Base64.getEncoder().withoutPadding().encodeToString(digest);
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Unable to calculate SSH host key fingerprint", exception);
		}
	}

	private void closeClientQuietly() {
		try {
			if (client != null) {
				client.disconnect();
			}
		} catch (IOException ignored) {
		} finally {
			client = null;
		}
	}

	public static final class HostKeyVerificationException extends IOException {
		private final String fingerprint;
		private final String expectedFingerprint;

		HostKeyVerificationException(String host, int port, String fingerprint, String expectedFingerprint,
			IOException cause) {
			super((expectedFingerprint == null ? "未信任" : "已变更") + "的 SSH 主机密钥 "
				+ host + ":" + port + "，指纹：" + fingerprint, cause);
			this.fingerprint = fingerprint;
			this.expectedFingerprint = expectedFingerprint;
		}

		public String getFingerprint() { return fingerprint; }
		public String getExpectedFingerprint() { return expectedFingerprint; }
	}

	public static class CommandStreamer implements Closeable {
		private final Session session;
		private final Session.Command command;
		private final ScheduledExecutorService timeoutExecutor;
		private final ScheduledFuture<?> timeoutTask;
		private volatile boolean timedOut;

		public CommandStreamer(Session session, Session.Command command, long timeoutSeconds) {
			this.session = session;
			this.command = command;
			this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor();
			this.timeoutTask = timeoutExecutor.schedule(() -> {
				timedOut = true;
				try {
					session.close();
				} catch (IOException ignored) {
				}
			}, timeoutSeconds, TimeUnit.SECONDS);
		}

		public InputStream getInputStream() {
			return command.getInputStream();
		}

		public boolean isTimedOut() { return timedOut; }

		public Integer getExitStatus() { return command.getExitStatus(); }

		@Override
		public void close() throws IOException {
			timeoutTask.cancel(true);
			timeoutExecutor.shutdownNow();
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

			return new CommandStreamer(session, cmd, timeoutSeconds);
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
