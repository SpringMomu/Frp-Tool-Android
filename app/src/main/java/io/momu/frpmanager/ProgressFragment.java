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

        SshSettingsManager settingsManager = new SshSettingsManager(requireContext());
        sshManager = new SshManager(
            settingsManager.getHost(),
            settingsManager.getPort(),
            settingsManager.getUsername(),
            settingsManager.getPassword()
        );

        startSetupProcess();
    }

    private void startSetupProcess() {
        executor.execute(() -> {
            try {
                updateUi("正在检测服务器架构...", 5);
                String arch = sshManager.executeCommand("uname -m", 10).trim();
                String frpcAssetPath;
                if (arch.equals("aarch64")) {
                    frpcAssetPath = "frpc/aarch64/frpc";
                } else {
                    frpcAssetPath = "frpc/x86_64/frpc";
                }
                updateUi("检测到架构: " + arch + ", 准备上传frpc...", 10);
                File frpcFile = copyAssetToCache(frpcAssetPath, "frpc");
                try (SFTPClient sftp = sshManager.getSftpClient()) {
                    sftp.put(frpcFile.getAbsolutePath(), "/root/frpc_temp_upload");
                }
                updateUi("frpc 上传成功, 准备执行配置脚本...", 20);
                String setupScript = readAssetFileAsString("scripts/setup_env.sh");
                try (SshManager.CommandStreamer streamer = sshManager.executeCommandAndStreamOutput(setupScript, 120)) {
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
                            updateUi("配置完成！", 100);
                            Thread.sleep(1000);
                            if (getActivity() instanceof SetupWizardActivity) {
                                requireActivity().runOnUiThread(((SetupWizardActivity) getActivity())::navigateToNextStep);
                            }
                            break;
                        } else if (finalLine.startsWith("ERROR:")) {
                            throw new IOException("服务器脚本执行出错: " + finalLine);
                        }
                    }
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
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
        return cacheFile;
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
