package io.momu.frpmanager;

import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.os.*;
import android.text.*;
import android.text.method.*;
import android.text.style.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.appcompat.widget.*;
import com.google.android.material.button.*;
import com.google.android.material.dialog.*;
import com.google.android.material.progressindicator.*;
import com.google.android.material.switchmaterial.*;
import com.google.android.material.textfield.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;
import net.schmizz.sshj.sftp.*;

import android.content.ClipboardManager;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity {
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;
    private static final int REFRESH_INTERVAL_MS = 5000;

    private Toolbar toolbar;
    private LinearProgressIndicator progressCpu, progressMemory;
    private TextView tvCpuUsage, tvMemoryUsage;
    private TextView tvStatTotalValue, tvStatTotalLabel;
    private TextView tvStatRunningValue, tvStatRunningLabel;
    private TextView tvStatPendingValue, tvStatPendingLabel;
    private TextView tvStatErrorValue, tvStatErrorLabel;
    private LinearLayout statTotalLayout, statRunningLayout, statPendingLayout, statErrorLayout;
    private MaterialButton btnEnterFrpManager, btnViewLogs, btnSettings, btnDebugClear, btnFrpSettings;

    private SshManager sshManager;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private SshSettingsManager settingsManager;

    private List<String> totalPortList = new ArrayList<>();
    private List<String> runningPortList = new ArrayList<>();
    private List<String> errorPortList = new ArrayList<>();
    private List<String> pendingPortList = new ArrayList<>();

    private AlertDialog loadingDialog;
    private boolean isFirstLoad = true;
    private Handler logRefreshHandler;
    private Runnable logRefreshRunnable;
    private boolean isLogViewerActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        settingsManager = new SshSettingsManager(this);

        initViews();
        setSupportActionBar(toolbar);
        initializeSshManager();
        setupRefreshRunnable();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!settingsManager.isConfigured()) {
            showSshSettingsDialog();
            showErrorUI("请先配置SSH连接");
        } else {
            checkEnvironmentAndProceed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        refreshHandler.removeCallbacksAndMessages(null);
        if (logRefreshHandler != null) {
            logRefreshHandler.removeCallbacksAndMessages(null);
        }
        if (sshManager != null) {
            executor.execute(() -> {
                try {
                    sshManager.disconnect();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        }
        executor.shutdown();
    }

    private void initViews() {
        toolbar = findViewById(R.id.toolbar);
        progressCpu = findViewById(R.id.progress_cpu);
        progressMemory = findViewById(R.id.progress_memory);
        tvCpuUsage = findViewById(R.id.tv_cpu_usage);
        tvMemoryUsage = findViewById(R.id.tv_memory_usage);

        statTotalLayout = findViewById(R.id.stat_total);
        tvStatTotalValue = statTotalLayout.findViewById(R.id.tv_stat_value);
        tvStatTotalLabel = statTotalLayout.findViewById(R.id.tv_stat_label);

        statRunningLayout = findViewById(R.id.stat_running);
        tvStatRunningValue = statRunningLayout.findViewById(R.id.tv_stat_value);
        tvStatRunningLabel = statRunningLayout.findViewById(R.id.tv_stat_label);

        statPendingLayout = findViewById(R.id.stat_pending);
        tvStatPendingValue = statPendingLayout.findViewById(R.id.tv_stat_value);
        tvStatPendingLabel = statPendingLayout.findViewById(R.id.tv_stat_label);

        statErrorLayout = findViewById(R.id.stat_error);
        tvStatErrorValue = statErrorLayout.findViewById(R.id.tv_stat_value);
        tvStatErrorLabel = statErrorLayout.findViewById(R.id.tv_stat_label);

        findViewById(R.id.btn_apply_all).setVisibility(View.GONE);

        btnEnterFrpManager = findViewById(R.id.btn_enter_frp_manager);
        btnViewLogs = findViewById(R.id.btn_view_logs);
        btnSettings = findViewById(R.id.btn_settings);
        btnDebugClear = findViewById(R.id.btn_debug_clear);

        btnFrpSettings = findViewById(R.id.btn_frp_settings);

        tvStatTotalLabel.setText("端口总数");
        tvStatRunningLabel.setText("运行中");
        tvStatPendingLabel.setText("待应用");
        tvStatErrorLabel.setText("错误");

        btnEnterFrpManager.setOnClickListener(v -> {
            if (!settingsManager.isConfigured()) return;
            Intent intent = new Intent(MainActivity.this, FrpManagerActivity.class);
            startActivity(intent);
        });

        btnViewLogs.setOnClickListener(v -> {
            if (!settingsManager.isConfigured()) return;
            showServerInfoDialog();
        });

        btnSettings.setOnClickListener(v -> showSshSettingsDialog());

        btnDebugClear.setOnClickListener(v -> {
            if (!settingsManager.isConfigured()) {
                Toast.makeText(this, "请先配置SSH", Toast.LENGTH_SHORT).show();
                return;
            }
            new MaterialAlertDialogBuilder(this)
                .setTitle("确认操作")
                .setMessage("此操作将尝试删除服务器上由本软件创建的所有相关配置和文件，用于重置测试环境。确定要继续吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定清除", (dialog, which) -> executeCleanup())
                .show();
        });

        btnFrpSettings.setOnClickListener(v -> {
            if (!settingsManager.isConfigured()) {
                Toast.makeText(this, "请先配置SSH", Toast.LENGTH_SHORT).show();
                return;
            }
            showFrpCommonSettingsDialog();
        });


        statTotalLayout.setOnClickListener(v -> showPortsDialog("total", "端口总数列表", totalPortList));
        statRunningLayout.setOnClickListener(v -> showPortsDialog("running", "运行中端口列表", runningPortList));
        statErrorLayout.setOnClickListener(v -> showPortsDialog("error", "错误端口列表", errorPortList));
        statPendingLayout.setOnClickListener(v -> showPortsDialog("pending", "待应用更改列表", pendingPortList));
    }

    private void showSshSettingsDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_ssh_settings, null);
        builder.setView(dialogView);
        builder.setCancelable(false);

        final TextInputEditText etHost = dialogView.findViewById(R.id.et_ssh_host);
        final TextInputEditText etPort = dialogView.findViewById(R.id.et_ssh_port);
        final TextInputEditText etUsername = dialogView.findViewById(R.id.et_ssh_username);
        final TextInputEditText etPassword = dialogView.findViewById(R.id.et_ssh_password);
        final TextView tvStatus = dialogView.findViewById(R.id.tv_ssh_status);
        final SwitchMaterial cbManageFirewall = dialogView.findViewById(R.id.switch_manage_firewall);
        final TextInputLayout passwordLayout = dialogView.findViewById(R.id.layout_ssh_password);

        etUsername.setText("root");
        etUsername.setEnabled(false);
        etUsername.setFocusable(false);
        passwordLayout.setHelperText("注意：必须使用 root 用户。如果密码登录失败，请检查服务器 /etc/ssh/sshd_config 文件中是否已设置 'PermitRootLogin yes'。");

        if (settingsManager.isConfigured()) {
            etHost.setText(settingsManager.getHost());
            etPort.setText(String.valueOf(settingsManager.getPort()));
            etPassword.setText(settingsManager.getPassword());
            cbManageFirewall.setChecked(settingsManager.isFirewallManaged());
            tvStatus.setText("已保存的连接信息");
            tvStatus.setTextColor(Color.GRAY);
        } else {
            tvStatus.setText("请填写 root 用户密码以开始使用");
            tvStatus.setTextColor(Color.RED);
        }

        builder.setTitle("SSH 连接配置");
        builder.setPositiveButton("保存并连接", null);
        builder.setNeutralButton("测试连接", null);
        if (settingsManager.isConfigured()) {
            builder.setNegativeButton("取消", (dialog, which) -> dialog.dismiss());
        }

        final AlertDialog dialog = builder.create();

        dialog.setOnShowListener(d -> {
            Button positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Button neutralButton = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);

            neutralButton.setOnClickListener(v -> {
                String host = etHost.getText().toString().trim();
                String portStr = etPort.getText().toString().trim();
                String user = etUsername.getText().toString().trim();
                String pass = etPassword.getText().toString().trim();

                if (host.isEmpty() || portStr.isEmpty() || user.isEmpty()) {
                    tvStatus.setText("主机、端口和用户名不能为空");
                    tvStatus.setTextColor(Color.RED);
                    return;
                }

                tvStatus.setText("正在测试连接...");
                tvStatus.setTextColor(Color.BLUE);
                boolean manageFirewall = cbManageFirewall.isChecked();

                executor.execute(() -> {
                    try {
                        SshManager testManager = new SshManager(host, Integer.parseInt(portStr), user, pass);
                        testManager.executeCommand("echo 'SSH_TEST_SUCCESS'", 10);

                        String firewallType = "none";
                        if (manageFirewall) {
                            runOnUiThread(() -> {
                                tvStatus.setText("连接成功! 正在检测防火墙...");
                                tvStatus.setTextColor(Color.BLUE);
                            });
                            String detectCommand = "if systemctl is-active --quiet firewalld; then echo \"firewalld\"; " +
                                "elif command -v ufw >/dev/null && ufw status | grep -q \"Status: active\"; then echo \"ufw\"; " +
                                "elif command -v iptables >/dev/null; then echo \"iptables\"; " +
                                "else echo \"none\"; fi";
                            firewallType = testManager.executeCommand(detectCommand, 15).trim();
                        }

                        final String finalFirewallType = firewallType;
                        runOnUiThread(() -> {
                            String statusMessage = "连接成功!";
                            if (manageFirewall) {
                                if (!"none".equals(finalFirewallType)) {
                                    statusMessage += " 检测到防火墙: " + finalFirewallType;
                                } else {
                                    statusMessage += " 未检测到支持的防火墙 (firewalld/ufw/iptables)";
                                }
                            }
                            tvStatus.setText(statusMessage);
                            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            tvStatus.setText("测试失败: " + e.getMessage());
                            tvStatus.setTextColor(Color.RED);
                        });
                    }
                });
            });

            positiveButton.setOnClickListener(v -> {
                String host = etHost.getText().toString().trim();
                String portStr = etPort.getText().toString().trim();
                String user = etUsername.getText().toString().trim();
                String pass = etPassword.getText().toString().trim();

                if (host.isEmpty() || portStr.isEmpty() || user.isEmpty()) {
                    tvStatus.setText("主机、端口和用户名不能为空");
                    tvStatus.setTextColor(Color.RED);
                    return;
                }

                boolean manageFirewall = cbManageFirewall.isChecked();
                settingsManager.saveSshSettings(host, Integer.parseInt(portStr), user, pass);
                settingsManager.setManageFirewall(manageFirewall);

                initializeSshManager();

                executor.execute(() -> {
                    try {
                        if (sshManager != null) {
                            sshManager.forceDisconnect();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });

                Toast.makeText(MainActivity.this, "配置已保存，正在检查服务器环境...", Toast.LENGTH_SHORT).show();
                dialog.dismiss();

                checkEnvironmentAndProceed();
            });
        });

        dialog.show();
    }

    private void checkEnvironmentAndProceed() {
        showLoadingDialog();
        executor.execute(() -> {
            try {
                String osRelease = sshManager.executeCommand("cat /etc/os-release", 10);
                if (!(osRelease.contains("ID=ubuntu") || osRelease.contains("ID=debian") || osRelease.contains("ID=centos"))) {
                    runOnUiThread(() -> showUnsupportedSystemDialog(osRelease));
                    return;
                }

                String currentUser = sshManager.executeCommand("whoami", 10).trim();
                if (!"root".equals(currentUser)) {
                    runOnUiThread(this::showNonRootUserDialog);
                    return;
                }

                String scriptContent = readAssetFile("scripts/check_env.sh");
                if (scriptContent == null) throw new IOException("无法读取 check_env.sh 脚本");

                String scriptOutput = sshManager.executeCommand(scriptContent, 15).trim();
                runOnUiThread(() -> {
                    if (loadingDialog != null && loadingDialog.isShowing()) loadingDialog.dismiss();

                    if (scriptOutput.contains("STATUS:ALL_OK")) {
                        isFirstLoad = true;
                        showLoadingDialog();
                        refreshHandler.post(refreshRunnable);
                    } else if (scriptOutput.contains("STATUS:NEEDS_SETUP")) {
                        new MaterialAlertDialogBuilder(this)
                            .setTitle("环境未配置")
                            .setMessage("检测到服务器缺少必要的组件，是否现在开始自动配置？\n\n" + scriptOutput)
                            .setNegativeButton("以后再说", null)
                            .setPositiveButton("开始配置", (d, w) -> showSetupWizardDialog())
                            .show();
                    } else {
                        showErrorUI("环境检查失败");
                        new MaterialAlertDialogBuilder(this)
                            .setTitle("未知错误")
                            .setMessage("无法识别服务器环境，请检查脚本或连接。\n\n--- 检查日志 ---\n" + scriptOutput)
                            .setPositiveButton("好的", null).show();
                    }
                });

            } catch (IOException e) {
                runOnUiThread(() -> {
                    if (loadingDialog != null && loadingDialog.isShowing()) loadingDialog.dismiss();
                    showErrorUI("连接或脚本执行失败");
                    new MaterialAlertDialogBuilder(this)
                        .setTitle("验证失败")
                        .setMessage("无法完成服务器验证流程。\n错误详情: " + e.getMessage())
                        .setPositiveButton("好的", null).show();
                });
            }
        });
    }

    private void showUnsupportedSystemDialog(String osInfo) {
        if (loadingDialog != null && loadingDialog.isShowing()) loadingDialog.dismiss();
        new MaterialAlertDialogBuilder(this)
            .setTitle("操作系统不受支持")
            .setMessage("本工具目前仅支持基于 systemd 的主流 Linux 发行版 (如 Ubuntu, Debian, CentOS 7+)。\n\n检测到您的系统为:\n" + osInfo + "\n\n请更换系统或手动配置后使用。")
            .setCancelable(false)
            .setPositiveButton("返回SSH设置", (d, w) -> showSshSettingsDialog())
            .show();
    }

    private void showNonRootUserDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) loadingDialog.dismiss();
        new MaterialAlertDialogBuilder(this)
            .setTitle("需要 Root 权限")
            .setMessage("为了执行自动化配置和管理，本工具要求必须使用 'root' 用户进行 SSH 连接。\n\n请在下方的设置中使用 root 用户信息登录。")
            .setCancelable(false)
            .setPositiveButton("返回SSH设置", (d, w) -> showSshSettingsDialog())
            .show();
    }

    private void showSetupWizardDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        final View dialogView = inflater.inflate(R.layout.dialog_setup_wizard, null);

        builder.setView(dialogView);
        builder.setCancelable(false);

        final AlertDialog dialog = builder.create();

        final ViewAnimator viewAnimator = dialogView.findViewById(R.id.setup_view_animator);
        final Button btnCancel = dialogView.findViewById(R.id.btn_setup_cancel);
        final Button btnBack = dialogView.findViewById(R.id.btn_setup_back);
        final Button btnNext = dialogView.findViewById(R.id.btn_setup_next);

        // Crash guard: Ensure these buttons exist in the layout.
        if (btnCancel == null || btnBack == null || btnNext == null) {
            Toast.makeText(this, "布局文件错误，请检查 dialog_setup_wizard.xml", Toast.LENGTH_LONG).show();
            return;
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnNext.setOnClickListener(v -> {
            int currentPage = viewAnimator.getDisplayedChild();
            if (currentPage == 0) {
                viewAnimator.setDisplayedChild(1);
                btnBack.setVisibility(View.VISIBLE);
                btnNext.setText("开始配置");
            } else if (currentPage == 1) {
                btnBack.setVisibility(View.GONE);
                btnNext.setVisibility(View.GONE);
                btnCancel.setVisibility(View.GONE);
                viewAnimator.setDisplayedChild(2);

                View pageProgress = viewAnimator.getChildAt(2);
                View pageCompletion = viewAnimator.getChildAt(3);
                TextView tvProgressTitle = pageProgress.findViewById(R.id.tv_progress_title);
                TextView tvStatus = pageProgress.findViewById(R.id.tv_setup_status);
                LinearProgressIndicator progressBar = pageProgress.findViewById(R.id.progress_indicator_setup);

                startSetupTask(dialog, dialogView, pageProgress, pageCompletion, tvProgressTitle, tvStatus, progressBar, viewAnimator);
            }
        });

        btnBack.setOnClickListener(v -> {
            int currentPage = viewAnimator.getDisplayedChild();
            if (currentPage == 1) {
                viewAnimator.setDisplayedChild(0);
                btnBack.setVisibility(View.GONE);
                btnNext.setText("下一步");
            }
        });

        dialog.show();
    }

    private File copyAssetToCache(String assetPath, String cacheFileName) throws IOException {
        AssetManager assetManager = getAssets();
        File cacheFile = new File(getCacheDir(), cacheFileName);
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

    private void startSetupTask(AlertDialog dialog, View dialogView, View pageProgress, View pageCompletion, TextView tvProgressTitle, TextView tvStatus, LinearProgressIndicator progressBar, ViewAnimator viewAnimator) {
        executor.execute(() -> {
            try {
                runOnUiThread(() -> {
                    tvStatus.setText("正在检测服务器架构...");
                    progressBar.setProgress(5, true);
                });
                String arch = sshManager.executeCommand("uname -m", 10).trim();
                String frpcAssetPath = arch.equals("aarch64") ? "frpc/aarch64/frpc" : "frpc/x86_64/frpc";
                final String status1 = "检测到架构: " + arch + ", 准备部署frpc...";
                runOnUiThread(() -> {
                    tvStatus.setText(status1);
                    progressBar.setProgress(10, true);
                });

                File frpcFile = copyAssetToCache(frpcAssetPath, "frpc");
                runOnUiThread(() -> {
                    tvStatus.setText("正在部署 frpc, 请稍候...");
                    progressBar.setIndeterminate(true);
                });
                try (SFTPClient sftp = sshManager.getSftpClient()) {
                    sftp.put(frpcFile.getAbsolutePath(), "/root/frpc_temp_upload");
                }
                final int UPLOAD_COMPLETE_PROGRESS = 25;
                runOnUiThread(() -> {
                    progressBar.setIndeterminate(false);
                    tvStatus.setText("部署完成，准备执行配置脚本...");
                    progressBar.setProgress(UPLOAD_COMPLETE_PROGRESS, true);
                });
                String setupScript = readAssetFile("scripts/setup_env.sh");
                try (SshManager.CommandStreamer streamer = sshManager.executeCommandAndStreamOutput(setupScript, 120)) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(streamer.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        final String finalLine = line;
                        if (finalLine.startsWith("PROGRESS:")) {
                            // Progress update logic.
                        } else if (finalLine.startsWith("FINAL_STATUS:SETUP_COMPLETE")) {
                            runOnUiThread(() -> {
                                viewAnimator.setDisplayedChild(viewAnimator.indexOfChild(pageCompletion));

                                ImageView checkMark = pageCompletion.findViewById(R.id.iv_completion_check);
                                if (checkMark != null) {
                                    checkMark.setAlpha(0f);
                                    checkMark.setScaleX(0.5f);
                                    checkMark.setScaleY(0.5f);

                                    checkMark.animate()
                                        .alpha(1f)
                                        .scaleX(1f)
                                        .scaleY(1f)
                                        .setDuration(800)
                                        .setInterpolator(new OvershootInterpolator())
                                        .start();
                                }
                                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                    dialog.dismiss();
                                    onResume();
                                }, 3000);
                            });
                            break;
                        } else if (finalLine.startsWith("ERROR:")) {
                            throw new IOException("服务器脚本执行出错: " + finalLine);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    tvProgressTitle.setText("配置失败");
                    tvProgressTitle.setTextColor(Color.RED);
                    tvStatus.setText("发生错误: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                    dialog.setCancelable(true);
                    Button btnCancel = dialogView.findViewById(R.id.btn_setup_cancel);
                    if (btnCancel != null) {
                        btnCancel.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void showFrpCommonSettingsDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_frp_common_settings, null);

        final TextInputEditText etAddr = dialogView.findViewById(R.id.et_frp_server_addr);
        final TextInputEditText etPort = dialogView.findViewById(R.id.et_frp_server_port);
        final TextInputEditText etToken = dialogView.findViewById(R.id.et_frp_token);

        etAddr.setText(settingsManager.getFrpServerAddr());
        etPort.setText(String.valueOf(settingsManager.getFrpServerPort()));
        etToken.setText(settingsManager.getFrpToken());

        builder.setView(dialogView)
            .setTitle("FRP 通用配置")
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", (d, w) -> {
                String addr = etAddr.getText().toString().trim();
                String portStr = etPort.getText().toString().trim();
                if(addr.isEmpty() || portStr.isEmpty()){
                    Toast.makeText(this, "地址和端口不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                settingsManager.saveFrpCommonSettings(addr, Integer.parseInt(portStr), etToken.getText().toString().trim());
                Toast.makeText(this, "通用配置已保存", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private void showServerInfoDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_log_viewer, null);
        final TextView contentTextView = dialogView.findViewById(R.id.log_text_view);
        final ScrollView scrollView = dialogView.findViewById(R.id.log_scroll_view);
        final Button copyButton = dialogView.findViewById(R.id.btn_copy_log);
        final StringBuilder logBuilder = new StringBuilder();

        contentTextView.setMovementMethod(ScrollingMovementMethod.getInstance());
        contentTextView.setText("");

        final AlertDialog infoDialog = new MaterialAlertDialogBuilder(this)
            .setTitle("服务器状态总览")
            .setView(dialogView)
            .setPositiveButton("刷新", null)
            .setNegativeButton("关闭", null)
            .create();

        infoDialog.setOnShowListener(dialog -> {
            Button refreshButton = infoDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            refreshButton.setOnClickListener(v -> {
                contentTextView.setText("");
                logBuilder.setLength(0);
                loadServerInfo(contentTextView, scrollView, logBuilder, copyButton);
            });
        });

        infoDialog.show();

        copyButton.setEnabled(false);
        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Server Info Log", logBuilder.toString());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(MainActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });

        loadServerInfo(contentTextView, scrollView, logBuilder, copyButton);
    }

    private String parseAndSummarizeServerInfo(String rawLog, String firewallType) {
        StringBuilder summary = new StringBuilder();
        summary.append("\n=============== 状态摘要 ===============\n");

        try {
            String uptimeLine = "";
            String[] lines = rawLog.split("\n");
            for (String line : lines) {
                if (line.contains("up") && line.contains("load average")) {
                    uptimeLine = line;
                    break;
                }
            }
            if (!uptimeLine.isEmpty()) {
                String uptime = uptimeLine.substring(uptimeLine.indexOf("up") + 3, uptimeLine.indexOf(",")).trim();
                String load = uptimeLine.substring(uptimeLine.indexOf("load average:") + 13).trim();
                summary.append("● 系统已运行: ").append(uptime).append("\n");
                summary.append("● 系统负载 (1/5/15min): ").append(load).append("\n");
            }
        } catch (Exception e) { /* ignore */ }

        try {
            String memLine = "";
            String[] lines = rawLog.split("\n");
            for (String line : lines) {
                if (line.trim().startsWith("Mem:")) {
                    memLine = line;
                    break;
                }
            }
            if (!memLine.isEmpty()) {
                String[] parts = memLine.trim().split("\\s+");
                summary.append("● 内存使用: ").append(parts[2]).append(" / ").append(parts[1]).append("\n");
            }
        } catch (Exception e) { /* ignore */ }

        summary.append("● 硬盘使用:\n");
        try {
            String[] lines = rawLog.split("\n");
            boolean foundDisks = false;
            for (String line : lines) {
                if (line.trim().startsWith("/dev/")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 6) {
                        String mountPoint = parts[5];
                        String used = parts[2];
                        String size = parts[1];
                        String usePercent = parts[4];
                        summary.append("  - ").append(mountPoint).append(": ")
                            .append(used).append(" / ").append(size)
                            .append(" (").append(usePercent).append(")\n");
                        foundDisks = true;
                    }
                }
            }
            if (!foundDisks) {
                summary.append("  - 未能获取到硬盘信息。\n");
            }
        } catch (Exception e) {
            summary.append("  - 解析硬盘信息时出错。\n");
        }

        summary.append("● 检测到防火墙: ").append("none".equals(firewallType) ? "无" : firewallType).append("\n");

        summary.append("======================================\n");
        return summary.toString();
    }

    private void loadServerInfo(TextView textView, ScrollView scrollView, StringBuilder logBuilder, Button copyButton) {
        if (!settingsManager.isConfigured()) {
            runOnUiThread(() -> appendLog(textView, scrollView, logBuilder, "SSH未配置，无法加载信息。", "error"));
            return;
        }

        appendLog(textView, scrollView, logBuilder, "正在从服务器加载信息...", "info");
        copyButton.setEnabled(false);

        executor.execute(() -> {
            try {
                String command = "echo '--- 系统负载与运行时间 ---'; " +
                    "uptime; " +
                    "echo; echo '--- 内存使用情况 ---'; " +
                    "free -h; " +
                    "echo; echo '--- 硬盘使用情况 ---'; " +
                    "df -h | grep -E '^/dev/|Filesystem'; " +
                    "echo; echo '---INTERNAL_IP---'; " +
                    "hostname -I | awk '{print $1}'; " +
                    "echo; echo '---FIREWALL_TYPE---'; " +
                    "if systemctl is-active --quiet firewalld; then echo 'firewalld'; " +
                    "elif command -v ufw >/dev/null && ufw status | grep -q 'Status: active'; then echo 'ufw'; " +
                    "elif command -v iptables >/dev/null; then echo 'iptables'; " +
                    "else echo 'none'; fi";

                final String serverInfoRaw = sshManager.executeCommand(command, 45);

                String serverInfoForDisplay = serverInfoRaw;
                String internalIp = "获取失败";
                String firewallType = "获取失败";

                try {
                    String[] ipParts = serverInfoRaw.split("---INTERNAL_IP---");
                    if (ipParts.length > 1) {
                        serverInfoForDisplay = ipParts[0];
                        String rest = ipParts[1];

                        String[] firewallParts = rest.split("---FIREWALL_TYPE---");
                        if (firewallParts.length > 1) {
                            internalIp = firewallParts[0].trim();
                            firewallType = firewallParts[1].trim();
                        }
                    }
                } catch (Exception e) {
                    // Parsing failed, use raw data
                }

                final String finalServerInfoForDisplay = serverInfoForDisplay;
                final String finalInternalIp = internalIp;
                final String finalFirewallType = firewallType;
                final String summary = parseAndSummarizeServerInfo(finalServerInfoForDisplay, finalFirewallType);

                runOnUiThread(() -> {
                    textView.setText("");
                    logBuilder.setLength(0);

                    appendLog(textView, scrollView, logBuilder, finalServerInfoForDisplay, "info");

                    try {
                        String versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                        String toolInfo = "\n❖ FRP Manager - 版本: " + versionName + " ❖\n\n" +
                            "by - Momu\n";
                        appendLog(textView, scrollView, logBuilder, toolInfo, "info");
                    } catch (Exception e) {
                        // Ignore
                    }

                    appendLog(textView, scrollView, logBuilder, summary, "success");

                    String finalInfo = "服务器公网IP: " + settingsManager.getHost() + "\n" +
                        "服务器内网IP: " + finalInternalIp + "\n";
                    appendLog(textView, scrollView, logBuilder, finalInfo, "success");

                    copyButton.setEnabled(true);
                    scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                });

            } catch (IOException e) {
                runOnUiThread(() -> {
                    textView.setText("");
                    logBuilder.setLength(0);
                    appendLog(textView, scrollView, logBuilder, "加载服务器信息失败:\n" + e.getMessage(), "error");
                    copyButton.setEnabled(false);
                });
            }
        });
    }

    private void appendLog(TextView textView, ScrollView scrollView, StringBuilder logBuilder, String message, String type) {
        runOnUiThread(() -> {
            String color;
            switch (type) {
                case "success": color = "#4CAF50"; break;
                case "error": color = "#F44336"; break;
                case "info": default: color = "#FFFFFF"; break;
            }
            String formattedMessage = "<font color='" + color + "'>" + TextUtils.htmlEncode(message).replace("\n", "<br>") + "</font><br>";
            logBuilder.append(message).append("\n");
            textView.append(Html.fromHtml(formattedMessage, Html.FROM_HTML_MODE_LEGACY));

            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void setupRefreshRunnable() {
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                refreshDashboardData();
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        };
    }

    private void refreshDashboardData() {
        if (!settingsManager.isConfigured()) {
            showErrorUI("SSH未配置或环境不安全");
            return;
        }

        executor.execute(() -> {
            final String SEPARATOR = "---DASHBOARD_SEP---";
            String command = "top -bn1 | grep 'Cpu(s)' | awk '{printf \"%.0f\", $2 + $4}';" +
                "echo " + SEPARATOR + ";" +
                "free -m | grep Mem | awk '{printf \"%.0f\", $3/$2 * 100.0}';" +
                "echo " + SEPARATOR + ";" +
                "ls -1 /etc/frp/conf.d/port_*.ini* 2>/dev/null | sed -n 's/.*port_\\([0-9]*\\).*/\\1/p' | sort -un;" +
                "echo " + SEPARATOR + ";" +
                "systemctl list-units --type=service --state=running 'frpc@*.service' --no-pager | grep -oP 'frpc@\\K[0-9]+';" +
                "echo " + SEPARATOR + ";" +
                "systemctl list-unit-files 'frpc@*.service' --no-pager | grep 'enabled' | grep -oP 'frpc@\\K[0-9]+';";


            try {
                String result = sshManager.executeCommand(command);
                String[] sections = result.split(SEPARATOR);

                if (sections.length < 5) {
                    throw new IOException("从服务器返回的数据格式不完整");
                }

                final int cpuUsage = sections[0].trim().isEmpty() ? 0 : Integer.parseInt(sections[0].trim());
                final int memUsage = sections[1].trim().isEmpty() ? 0 : Integer.parseInt(sections[1].trim());

                List<String> allPorts = new ArrayList<>(Arrays.asList(sections[2].trim().split("\\s+")));
                if (allPorts.size() == 1 && allPorts.get(0).isEmpty()) allPorts.clear();

                List<String> runningPorts = new ArrayList<>(Arrays.asList(sections[3].trim().split("\\s+")));
                if (runningPorts.size() == 1 && runningPorts.get(0).isEmpty()) runningPorts.clear();

                List<String> enabledPorts = new ArrayList<>(Arrays.asList(sections[4].trim().split("\\s+")));
                if (enabledPorts.size() == 1 && enabledPorts.get(0).isEmpty()) enabledPorts.clear();

                Set<String> runningSet = new HashSet<>(runningPorts);
                List<String> errorPorts = enabledPorts.stream()
                    .filter(p -> !runningSet.contains(p))
                    .collect(Collectors.toList());

                this.totalPortList = new ArrayList<>(allPorts);
                this.runningPortList = new ArrayList<>(runningPorts);
                this.errorPortList = new ArrayList<>(errorPorts);
                this.pendingPortList.clear();

                runOnUiThread(() -> {
                    if (isFirstLoad && loadingDialog != null) {
                        loadingDialog.dismiss();
                        isFirstLoad = false;
                    }
                    updateServerStatusUI(cpuUsage, memUsage);
                    updateFrpOverviewUI(totalPortList.size(), runningPortList.size(), pendingPortList.size(), errorPortList.size());
                });

            } catch (IOException | NumberFormatException e) {
                final String errorMessage = e.getMessage();
                runOnUiThread(() -> {
                    if (isFirstLoad && loadingDialog != null) {
                        loadingDialog.dismiss();
                        isFirstLoad = false;
                    }
                    showErrorUI("错误: " + errorMessage);
                });
                refreshHandler.removeCallbacks(refreshRunnable);
                e.printStackTrace();
            }
        });
    }

    private void updateServerStatusUI(int cpu, int mem) {
        progressCpu.setIndeterminate(false);
        progressMemory.setIndeterminate(false);
        progressCpu.setProgress(cpu);
        tvCpuUsage.setText(cpu + "%");
        progressMemory.setProgress(mem);
        tvMemoryUsage.setText(mem + "%");
    }

    private void updateFrpOverviewUI(int total, int running, int pending, int error) {
        tvStatTotalValue.setText(String.valueOf(total));
        tvStatRunningValue.setText(String.valueOf(running));
        tvStatPendingValue.setText(String.valueOf(pending));
        tvStatErrorValue.setText(String.valueOf(error));
    }

    private void showErrorUI(String message) {
        progressCpu.setIndeterminate(true);
        progressMemory.setIndeterminate(true);
        progressCpu.setProgress(0);
        progressMemory.setProgress(0);
        tvCpuUsage.setText(message);
        tvMemoryUsage.setText("错误");
        tvStatTotalValue.setText("-");
        tvStatRunningValue.setText("-");
        tvStatPendingValue.setText("-");
        tvStatErrorValue.setText("-");
    }

    private void showPortsDialog(String type, String title, List<String> ports) {
        if (ports.isEmpty() && !"pending".equals(type)) {
            Toast.makeText(this, "没有" + title, Toast.LENGTH_SHORT).show();
            return;
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle(title);

        if (ports.isEmpty()) {
            builder.setMessage("当前没有待应用的更改。");
        } else {
            ListView listView = new ListView(this);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, ports);
            listView.setAdapter(adapter);
            builder.setView(listView);

            if ("error".equals(type)) {
                listView.setOnItemClickListener((parent, view, position, id) -> {
                    String selectedPort = ports.get(position);
                    showLogViewerDialog(selectedPort);
                });
            }
        }

        builder.setNegativeButton("关闭", null);

        if ("pending".equals(type)) {
            builder.setPositiveButton("前往管理页面应用", (dialog, which) -> {
                Intent intent = new Intent(MainActivity.this, FrpManagerActivity.class);
                startActivity(intent);
            });
        }

        builder.create().show();
    }

    private void showLogViewerDialog(String port) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_log_viewer, null);
        builder.setView(dialogView);

        TextView logTitle = dialogView.findViewById(R.id.log_title);
        ScrollView scrollView = dialogView.findViewById(R.id.log_scroll_view);
        final TextView logContent = dialogView.findViewById(R.id.log_text_view);
        ProgressBar logProgress = dialogView.findViewById(R.id.log_progress);

        Button copyButton = dialogView.findViewById(R.id.btn_copy_log);
        if (copyButton != null) copyButton.setVisibility(View.GONE);

        logTitle.setText("端口 " + port + " 日志 (实时刷新中)");
        isLogViewerActive = true;

        AlertDialog logDialog = builder.create();
        logDialog.setOnDismissListener(dialog -> {
            isLogViewerActive = false;
            if (logRefreshHandler != null && logRefreshRunnable != null) {
                logRefreshHandler.removeCallbacks(logRefreshRunnable);
            }
        });

        logRefreshHandler = new Handler(Looper.getMainLooper());
        logRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isLogViewerActive) return;

                executor.execute(() -> {
                    String logCommand = String.format("echo '---SYSTEMD STATUS---' && " +
                            "systemctl status frpc@%s.service --no-pager && " +
                            "echo '\n---JOURNAL LOGS (last 20 lines)---' && " +
                            "journalctl -u frpc@%s.service --no-pager -n 20",
                            port, port);
                    try {
                        String rawLogs = sshManager.executeCommand(logCommand);
                        SpannableString formattedLogs = formatLogsForDisplay(rawLogs);
                        runOnUiThread(() -> {
                            logProgress.setVisibility(View.GONE);
                            logContent.setText(formattedLogs);
                            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
                        });
                    } catch (IOException e) {
                        runOnUiThread(() -> logContent.setText("加载日志失败:\n" + e.getMessage()));
                    }
                });

                logRefreshHandler.postDelayed(this, 3000);
            }
        };

        logDialog.show();
        logRefreshHandler.post(logRefreshRunnable);
    }

    private SpannableString formatLogsForDisplay(String rawLogs) {
        SpannableString spannable = new SpannableString(rawLogs);

        Pattern errorPattern = Pattern.compile("failed|error|fatal", Pattern.CASE_INSENSITIVE);
        Matcher errorMatcher = errorPattern.matcher(rawLogs);
        while (errorMatcher.find()) {
            spannable.setSpan(new ForegroundColorSpan(Color.RED), errorMatcher.start(), errorMatcher.end(), 0);
        }

        Pattern successPattern = Pattern.compile("active \\(running\\)", Pattern.CASE_INSENSITIVE);
        Matcher successMatcher = successPattern.matcher(rawLogs);
        while (successMatcher.find()) {
            spannable.setSpan(new ForegroundColorSpan(Color.parseColor("#4CAF50")), successMatcher.start(), successMatcher.end(), 0);
        }

        Pattern inactivePattern = Pattern.compile("inactive \\(dead\\)", Pattern.CASE_INSENSITIVE);
        Matcher inactiveMatcher = inactivePattern.matcher(rawLogs);
        while (inactiveMatcher.find()) {
            spannable.setSpan(new ForegroundColorSpan(Color.GRAY), inactiveMatcher.start(), inactiveMatcher.end(), 0);
        }

        return spannable;
    }

    private void showLoadingDialog() {
        if (loadingDialog != null && loadingDialog.isShowing()) {
            loadingDialog.dismiss();
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_loading, null);

        builder.setView(dialogView);
        builder.setCancelable(false);

        loadingDialog = builder.create();
        loadingDialog.show();
    }

    private void initializeSshManager() {
        if (settingsManager.isConfigured()) {
            sshManager = new SshManager(
                settingsManager.getHost(),
                settingsManager.getPort(),
                settingsManager.getUsername(),
                settingsManager.getPassword()
            );
        } else {
            sshManager = new SshManager(null, 0, null, null);
        }
    }

    private void executeCleanup() {
        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
            .setView(R.layout.dialog_loading)
            .setCancelable(false)
            .show();

        executor.execute(() -> {
            try {
                String scriptContent = readAssetFile("scripts/cleanup.sh");
                if (scriptContent == null) throw new IOException("无法读取清理脚本");

                String result = sshManager.executeCommand(scriptContent, 60);

                runOnUiThread(() -> {
                    dialog.dismiss();
                    new MaterialAlertDialogBuilder(this)
                        .setTitle("操作完成")
                        .setMessage("清理脚本已执行完毕。\n\n服务器返回:\n" + result)
                        .setPositiveButton("好的", null)
                        .show();
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, "清理失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String readAssetFile(String fileName) {
        try (InputStream is = getAssets().open(fileName);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}