package io.momu.frpmanager;

import android.util.Log;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;

public class SshManager {

    private final String host;
    private final int port;
    private final String username;
    private final String password;

    private SSHClient client;

    public SshManager(String host, int port, String username, String password) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.client = new SSHClient();
    }

    private void connect() throws IOException {
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
        client = new SSHClient();
        client.addHostKeyVerifier(new PromiscuousVerifier());
        client.connect(host, port);
        client.authPassword(username, password);
    }

    public void forceDisconnect() throws IOException {
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }

    public String executeCommand(String command, long timeoutSeconds) throws IOException {
        if (host == null || username == null || password == null || host.isEmpty() || username.isEmpty()) {
            throw new IOException("SSH 连接信息不完整，请先进行配置。");
        }

        if (client == null || !client.isAuthenticated()) {
            Log.d("SshManager", "Client is not authenticated. Reconnecting...");
            connect();
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

    public String executeCommand(String command) throws IOException {
        return executeCommand(command, 15);
    }

    public void disconnect() throws IOException {
        if (client != null && client.isConnected()) {
            client.disconnect();
        }
    }

    private String readStream(java.io.InputStream stream) {
        java.util.Scanner s = new java.util.Scanner(stream).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    /**
     * Inner class to wrap session and input stream resources for streaming commands.
     * Implements Closeable to ensure proper resource management with try-with-resources.
     */
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
            // Closing the session will automatically handle its associated streams and resources.
            if (session != null && session.isOpen()) {
                session.close();
            }
        }
    }

    /**
     * Executes a command and returns its output stream for real-time processing.
     * This method does not wait for the command to complete.
     *
     * @param command The command to execute.
     * @param timeoutSeconds Command timeout (used internally by sshj).
     * @return A CommandStreamer object providing access to the command's input stream.
     * @throws IOException
     */
    public CommandStreamer executeCommandAndStreamOutput(String command, long timeoutSeconds) throws IOException {
        if (host == null || username == null || password == null || host.isEmpty() || username.isEmpty()) {
            throw new IOException("SSH 连接信息不完整，请先进行配置。");
        }

        if (client == null || !client.isAuthenticated()) {
            Log.d("SshManager", "Client is not authenticated. Reconnecting...");
            connect();
        }

        // Session must remain open after this method returns, so try-with-resources is not used here.
        // CommandStreamer's close method is responsible for closing the session.
        Session session = client.startSession();
        session.allocateDefaultPTY();
        final Session.Command cmd = session.exec(command);

        return new CommandStreamer(session, cmd);
    }

    /**
     * Retrieves an SFTP client instance. Essential for file transfers in the wizard.
     *
     * @return An SFTPClient instance.
     * @throws IOException
     */
    public SFTPClient getSftpClient() throws IOException {
        if (host == null || username == null || password == null || host.isEmpty() || username.isEmpty()) {
            throw new IOException("SSH 连接信息不完整，请先进行配置。");
        }

        if (client == null || !client.isAuthenticated()) {
            Log.d("SshManager", "Client is not authenticated. Reconnecting...");
            connect();
        }

        return client.newSFTPClient();
    }
}