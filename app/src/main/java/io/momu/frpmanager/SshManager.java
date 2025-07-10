package io.momu.frpmanager;

import android.util.Log;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

public class SshManager {

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    private volatile SSHClient client;

    private final Object lock = new Object();

    public SshManager(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    private void ensureConnected() throws IOException {
        synchronized (lock) {
            if (client == null || !client.isConnected()) {
                Log.d("SshManager", "Client is null or not connected. Connecting...");
                client = new SSHClient();
                client.addHostKeyVerifier(new PromiscuousVerifier());
                client.connect(host, port);
                client.authPassword(username, password);
                Log.d("SshManager", "Client connected and authenticated.");
            } else if (!client.isAuthenticated()) {
                Log.d("SshManager", "Client is connected but not authenticated. Authenticating...");
                client.authPassword(username, password);
                Log.d("SshManager", "Client authenticated.");
            }
        }
    }

    public void forceDisconnect() throws IOException {
        synchronized (lock) {
            if (client != null && client.isConnected()) {
                client.disconnect();
                Log.d("SshManager", "Client disconnected by force.");
            }
            client = null;
        }
    }

    public String executeCommand(String command, long timeoutSeconds) throws IOException {
        if (host == null || username == null || password == null || host.isEmpty() || username.isEmpty()) {
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
        if (host == null || username == null || password == null || host.isEmpty() || username.isEmpty()) {
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
        if (host == null || username == null || password == null || host.isEmpty() || username.isEmpty()) {
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