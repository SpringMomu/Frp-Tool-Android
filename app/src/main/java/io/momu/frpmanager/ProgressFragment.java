package io.momu.frpmanager;

import android.content.res.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.*;
import com.google.android.material.progressindicator.*;
import java.io.*;
import java.util.concurrent.*;
import net.schmizz.sshj.sftp.*;

public class ProgressFragment extends Fragment {

    private LinearProgressIndicator progressBar;
	private TextView tvStatus;
	private SshManager sshManager;
	private SshSettingsManager settingsManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wizard_progress, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        progressBar = view.findViewById(R.id.progress_indicator_setup);
        tvStatus = view.findViewById(R.id.tv_setup_status);

		settingsManager = new SshSettingsManager(requireContext());
		sshManager = settingsManager.getUseKeyAuth()
			? new SshManager(settingsManager.getHost(), settingsManager.getPort(), settingsManager.getUsername(),
				settingsManager.getPrivateKey(), settingsManager.getPassphrase(),
				settingsManager.getHostKeyFingerprint(settingsManager.getHost(), settingsManager.getPort()))
			: SshManager.password(settingsManager.getHost(), settingsManager.getPort(), settingsManager.getUsername(),
				settingsManager.getPassword(), settingsManager.getHostKeyFingerprint(settingsManager.getHost(), settingsManager.getPort()));

        startSetupProcess();
    }

    private void startSetupProcess() {
        executor.execute(() -> {
			try {
				updateUi("正在检测服务器架构...", 5);
				String arch = sshManager.executeCommand("uname -m", 10).trim();
				String frpcAssetPath;
				String frpsAssetPath;
				if (arch.equals("aarch64")) {
					frpcAssetPath = "frpc/aarch64/frpc";
					frpsAssetPath = "frps/aarch64/frps";
				} else if (arch.equals("x86_64")) {
					frpcAssetPath = "frpc/x86_64/frpc";
					frpsAssetPath = "frps/x86_64/frps";
				} else {
					throw new IOException("不支持的服务器 CPU 架构: " + arch + "。不会错误下发 x86_64 二进制。");
				}
				updateUi("检测到架构: " + arch + "，准备上传 frpc + frps...", 10);
				File frpcFile = copyAssetToCache(frpcAssetPath, "frpc");
				File frpsFile = copyAssetToCache(frpsAssetPath, "frps");
				try (SFTPClient sftp = sshManager.getSftpClient()) {
					sftp.put(frpcFile.getAbsolutePath(), "/root/frpc_temp_upload");
					sftp.put(new File(requireContext().getCacheDir(), "frpc.sha256").getAbsolutePath(), "/root/frpc_temp_upload.sha256");
					sftp.put(frpsFile.getAbsolutePath(), "/root/frps_temp_upload");
					sftp.put(new File(requireContext().getCacheDir(), "frps.sha256").getAbsolutePath(), "/root/frps_temp_upload.sha256");
				}
				updateUi("frpc 与 frps 上传成功，准备执行一体化配置...", 20);
				String setupScript = readAssetFileAsString("scripts/setup_env.sh");
				boolean setupCompleted = false;
				try (SshManager.CommandStreamer streamer = sshManager.executeCommandAndStreamOutput(
					RemoteScriptCommand.bash(setupScript), 300)) {
					BufferedReader reader = new BufferedReader(new InputStreamReader(streamer.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String finalLine = line;
                        if (finalLine.startsWith("PROGRESS:")) {
                            String[] parts = finalLine.split(";");
                            int progress = Integer.parseInt(parts[0].replace("PROGRESS:", ""));
                            String status = parts[1].replace("STATUS:", "");
                            updateUi(status, progress);
						} else if (finalLine.startsWith("FINAL_STATUS:SETUP_COMPLETE")) {
							setupCompleted = true;
						} else if (finalLine.startsWith("ERROR:")) {
							throw new IOException("服务器脚本执行出错: " + finalLine);
						}
					}
					if (streamer.isTimedOut()) throw new IOException("配置脚本执行超时并已取消");
					if (!setupCompleted || streamer.getExitStatus() == null || streamer.getExitStatus() != 0) {
						throw new IOException("配置脚本未成功完成，退出码=" + streamer.getExitStatus());
					}
				}
				String token = downloadEmbeddedFrpsToken();
				settingsManager.saveFrpCommonSettings("127.0.0.1", 7000, token);
				settingsManager.saveFrpsFirewallSettings(settingsManager.getHost(), settingsManager.getPort());
				settingsManager.setManageFirewall(true);
				settingsManager.setEmbeddedFrpsManaged(true);
				updateUi("frps、frpc 与防火墙联动已配置完成！", 100);
				Thread.sleep(800);
				if (getActivity() instanceof SetupWizardActivity) {
					requireActivity().runOnUiThread(((SetupWizardActivity) getActivity())::navigateToNextStep);
				}
            } catch (Exception e) {
                e.printStackTrace();
                updateUi("发生错误: " + e.getMessage(), -1);
            }
        });
    }

    private void updateUi(final String message, final int progress) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> {
                tvStatus.setText(message);
                if (progress >= 0) {
                    progressBar.setProgress(progress);
                } else {
                }
            });
        }
    }

    private File copyAssetToCache(String assetPath, String cacheFileName) throws IOException {
        AssetManager assetManager = requireContext().getAssets();
        File cacheFile = new File(requireContext().getCacheDir(), cacheFileName);
		try (InputStream in = assetManager.open(assetPath);
			FileOutputStream out = new FileOutputStream(cacheFile)) {
			java.security.MessageDigest digest;
			try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
			catch (java.security.NoSuchAlgorithmException exception) { throw new IOException("设备不支持 SHA-256", exception); }
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);
			}
				String expected = expectedAssetChecksum(assetPath);
				String component = assetPath.substring(0, assetPath.indexOf('/'));
				if (expected == null || !expected.equalsIgnoreCase(bytesToHex(digest.digest()))) {
					cacheFile.delete();
					throw new IOException("内置 " + component + " SHA-256 校验失败");
				}
				try (FileOutputStream checksum = new FileOutputStream(new File(requireContext().getCacheDir(), cacheFileName + ".sha256"))) {
					checksum.write((expected + "  " + component + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
        }
		return cacheFile;
	}

	private String expectedAssetChecksum(String assetPath) throws IOException {
		int separator = assetPath.indexOf('/');
		if (separator <= 0) return null;
		String component = assetPath.substring(0, separator);
		String relativePath = assetPath.substring(separator + 1);
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
			requireContext().getAssets().open(component + "/checksums.txt")))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String[] fields = line.trim().split("\\s+", 2);
				if (fields.length == 2 && relativePath.equals(fields[1])) return fields[0];
			}
		}
		return null;
	}

	private String downloadEmbeddedFrpsToken() throws IOException {
		File localToken = new File(requireContext().getCacheDir(), "managed-frps.token");
		try {
			try (SFTPClient sftp = sshManager.getSftpClient()) {
				sftp.get("/etc/frp/frps.token", localToken.getAbsolutePath());
			}
			String token;
			try (BufferedReader reader = new BufferedReader(new FileReader(localToken))) {
				token = reader.readLine();
			}
			if (token == null || !token.matches("[0-9a-f]{64}")) {
				throw new IOException("服务器返回的受管 frps token 格式无效");
			}
			return token;
		} finally {
			if (localToken.exists() && !localToken.delete()) localToken.deleteOnExit();
		}
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) result.append(String.format(java.util.Locale.US, "%02x", value));
		return result.toString();
	}

    private String readAssetFileAsString(String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = requireContext().getAssets().open(fileName);
		BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append(System.lineSeparator());
            }
        }
        return sb.toString();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
