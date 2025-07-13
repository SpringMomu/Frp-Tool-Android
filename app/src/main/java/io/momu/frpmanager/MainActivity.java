package io.momu.frpmanager;

import android.animation.*;
import android.content.*;
import android.content.res.*;
import android.graphics.*;
import android.net.*;
import android.os.*;
import android.text.*;
import android.text.method.*;
import android.text.style.*;
import android.util.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;
import androidx.appcompat.app.*;
import androidx.appcompat.widget.*;
import com.google.android.material.button.*;
import com.google.android.material.card.*;
import com.google.android.material.dialog.*;
import com.google.android.material.progressindicator.*;
import com.google.android.material.switchmaterial.*;
import com.google.android.material.textfield.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import net.schmizz.sshj.sftp.*;
import org.json.*;

import android.content.ClipboardManager;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity {
	private final Handler refreshHandler = new Handler(Looper.getMainLooper());
	private Runnable refreshRunnable;
	private static final int REFRESH_INTERVAL_MS = 5000;

	private Toolbar toolbar;

	private LinearProgressIndicator progressCpu, progressMemory, progressCpuTemp;
	private TextView tvCpuUsage, tvMemoryUsage, tvCpuTemp;
	private TextView tvCpuLabel, tvMemoryLabel;

	private MaterialCardView cardDiskStatus;
	private LinearLayout layoutDiskDetails;
	private RelativeLayout layoutDiskHeader;
	private ImageView ivDiskExpand;
	private boolean isDiskCardExpanded = false;

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

	private static final int PICK_KEY_FILE_REQUEST_CODE = 1001;
	private String selectedKeyContent = null;
	private String selectedKeyName = null;

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

	private boolean isOsSupported(String osRelease) {
		if (osRelease == null || osRelease.isEmpty()) {
			return false;
		}

		final Set<String> supportedIds = new HashSet<>(Arrays.asList("ubuntu", "debian", "centos"));

		String[] lines = osRelease.split("\n");
		for (String line : lines) {
			if (line.trim().startsWith("ID=")) {
				String value = line.substring(line.indexOf("=") + 1).trim();

				if (value.startsWith("\"") && value.endsWith("\"")) {
					value = value.substring(1, value.length() - 1);
				}

				return supportedIds.contains(value);
			}
		}

		return false;
	}

	private Map<String, Set<String>> parseFileNamesToPortMap(String rawFileNames) {
		Map<String, Set<String>> portProtocolMap = new HashMap<>();
		if (rawFileNames == null || rawFileNames.trim().isEmpty()) {
			return portProtocolMap;
		}
		Pattern pattern = Pattern.compile("port_(\\d+)(?:_(tcp|udp))?\\.ini");
		for (String fileName : rawFileNames.trim().split("\\s+")) {
			Matcher matcher = pattern.matcher(fileName);
			if (matcher.find()) {
				String port = matcher.group(1);
				String protocol = matcher.group(2) != null ? matcher.group(2) : "tcp";
				portProtocolMap.computeIfAbsent(port, k -> new HashSet<>()).add(protocol);
			}
		}
		return portProtocolMap;
	}

	private Map<String, Set<String>> parseServiceIdsToPortMap(String rawIds) {
		Map<String, Set<String>> portProtocolMap = new HashMap<>();
		if (rawIds == null || rawIds.trim().isEmpty()) {
			return portProtocolMap;
		}
		for (String identifier : rawIds.trim().split("\\s+")) {
			if (identifier.isEmpty())
				continue;
			String[] parts = identifier.split("_");
			if (parts.length > 0 && !parts[0].isEmpty()) {
				String port = parts[0];
				String protocol = (parts.length > 1) ? parts[1] : "tcp";
				portProtocolMap.computeIfAbsent(port, k -> new HashSet<>()).add(protocol);
			}
		}
		return portProtocolMap;
	}

	private List<String> formatPortMapToList(Map<String, Set<String>> portMap) {
		Map<String, Set<String>> sortedMap = new TreeMap<>(Comparator.comparingInt(Integer::parseInt));
		sortedMap.putAll(portMap);

		List<String> formattedList = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : sortedMap.entrySet()) {
			String port = entry.getKey();
			Set<String> protocols = entry.getValue();
			String display;

			if (protocols.contains("tcp") && protocols.contains("udp")) {
				display = port + " (TCP/UDP)";
			} else if (protocols.contains("tcp")) {
				display = port + " (TCP)";
			} else if (protocols.contains("udp")) {
				display = port + " (UDP)";
			} else {
				display = port;
			}
			formattedList.add(display);
		}
		return formattedList;
	}

	private void initViews() {
		toolbar = findViewById(R.id.toolbar);

		progressCpu = findViewById(R.id.progress_cpu);
		progressMemory = findViewById(R.id.progress_memory);
		tvCpuUsage = findViewById(R.id.tv_cpu_usage);
		tvMemoryUsage = findViewById(R.id.tv_memory_usage);
		progressCpuTemp = findViewById(R.id.progress_cpu_temp);
		tvCpuTemp = findViewById(R.id.tv_cpu_temp);
		tvCpuLabel = findViewById(R.id.tv_cpu_label);
		tvMemoryLabel = findViewById(R.id.tv_memory_label);

		cardDiskStatus = findViewById(R.id.card_disk_status);
		layoutDiskHeader = findViewById(R.id.layout_disk_header);
		layoutDiskDetails = findViewById(R.id.layout_disk_details);
		ivDiskExpand = findViewById(R.id.iv_disk_expand);

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
			if (!settingsManager.isConfigured())
				return;
			Intent intent = new Intent(MainActivity.this, FrpManagerActivity.class);
			startActivity(intent);
		});

		btnViewLogs.setOnClickListener(v -> {
			if (!settingsManager.isConfigured())
				return;
			showServerInfoDialog();
		});

		btnSettings.setOnClickListener(v -> showSshSettingsDialog());

		btnDebugClear.setOnClickListener(v -> {
			if (!settingsManager.isConfigured()) {
				Toast.makeText(this, "请先配置SSH", Toast.LENGTH_SHORT).show();
				return;
			}
			new MaterialAlertDialogBuilder(this).setTitle("确认操作")
				.setMessage("此操作将尝试删除服务器上由本软件创建的所有相关配置和文件，用于重置测试环境。确定要继续吗？").setNegativeButton("取消", null)
				.setPositiveButton("确定清除", (dialog, which) -> executeCleanup()).show();
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

		layoutDiskHeader.setOnClickListener(v -> {
			isDiskCardExpanded = !isDiskCardExpanded;
			toggleDiskCardWithAnimation(isDiskCardExpanded);
		});
	}

	private void toggleDiskCardWithAnimation(boolean expand) {
		if (expand) {
			ivDiskExpand.animate().rotation(180).setDuration(300).start();
			layoutDiskDetails.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			int targetHeight = layoutDiskDetails.getMeasuredHeight();

			layoutDiskDetails.getLayoutParams().height = 0;
			layoutDiskDetails.setVisibility(View.VISIBLE);

			ValueAnimator va = ValueAnimator.ofInt(0, targetHeight);
			va.addUpdateListener(animation -> {
				layoutDiskDetails.getLayoutParams().height = (Integer) animation.getAnimatedValue();
				layoutDiskDetails.requestLayout();
			});
			va.setDuration(300);
			va.start();
		} else {
			ivDiskExpand.animate().rotation(0).setDuration(300).start();
			int initialHeight = layoutDiskDetails.getMeasuredHeight();

			ValueAnimator va = ValueAnimator.ofInt(initialHeight, 0);
			va.addUpdateListener(animation -> {
				layoutDiskDetails.getLayoutParams().height = (Integer) animation.getAnimatedValue();
				layoutDiskDetails.requestLayout();
			});
			va.addListener(new AnimatorListenerAdapter() {
					@Override
					public void onAnimationEnd(Animator animation) {
						super.onAnimationEnd(animation);
						layoutDiskDetails.setVisibility(View.GONE);
					}
				});
			va.setDuration(300);
			va.start();
		}
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
		final TextView tvStatus = dialogView.findViewById(R.id.tv_ssh_status);
		final SwitchMaterial cbManageFirewall = dialogView.findViewById(R.id.switch_manage_firewall);

		final SwitchMaterial switchUseKeyAuth = dialogView.findViewById(R.id.switch_use_key_auth);
		final TextInputLayout layoutPassword = dialogView.findViewById(R.id.layout_ssh_password);
		final TextInputEditText etPassword = dialogView.findViewById(R.id.et_ssh_password);
		final LinearLayout layoutKeyAuthGroup = dialogView.findViewById(R.id.layout_key_auth_group);
		final Button btnSelectKey = dialogView.findViewById(R.id.btn_select_key);
		final TextView tvKeyPath = dialogView.findViewById(R.id.tv_key_path);
		final TextInputEditText etPassphrase = dialogView.findViewById(R.id.et_ssh_passphrase);

		etUsername.setText("root");
		etUsername.setEnabled(false);
		etUsername.setFocusable(false);
		layoutPassword
			.setHelperText("注意：必须使用 root 用户。如果密码登录失败，请检查服务器 /etc/ssh/sshd_config 文件中是否已设置 'PermitRootLogin yes'。");

		if (settingsManager.isConfigured()) {
			etHost.setText(settingsManager.getHost());
			etPort.setText(String.valueOf(settingsManager.getPort()));
			cbManageFirewall.setChecked(settingsManager.isFirewallManaged());

			boolean useKey = settingsManager.getUseKeyAuth();
			switchUseKeyAuth.setChecked(useKey);
			if (useKey) {
				layoutPassword.setVisibility(View.GONE);
				layoutKeyAuthGroup.setVisibility(View.VISIBLE);
				selectedKeyContent = settingsManager.getPrivateKey();
				tvKeyPath.setText(selectedKeyContent != null ? "已选择密钥 (保留)" : "尚未选择文件");
				etPassphrase.setText(settingsManager.getPassphrase());
			} else {
				layoutPassword.setVisibility(View.VISIBLE);
				layoutKeyAuthGroup.setVisibility(View.GONE);
				etPassword.setText(settingsManager.getPassword());
			}
			tvStatus.setText("已保存的连接信息");
		} else {
			tvStatus.setText("请填写 root 用户密码以开始使用");
		}

		switchUseKeyAuth.setOnCheckedChangeListener((buttonView, isChecked) -> {
			layoutPassword.setVisibility(isChecked ? View.GONE : View.VISIBLE);
			layoutKeyAuthGroup.setVisibility(isChecked ? View.VISIBLE : View.GONE);
			if (isChecked) {
				tvStatus.setText("请选择私钥文件");
			} else {
				tvStatus.setText("请输入密码");
			}
		});

		btnSelectKey.setOnClickListener(v -> {
			Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
			intent.setType("*/*");
			intent.addCategory(Intent.CATEGORY_OPENABLE);
			startActivityForResult(Intent.createChooser(intent, "选择私钥文件"), PICK_KEY_FILE_REQUEST_CODE);
		});

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

				if (host.isEmpty() || portStr.isEmpty() || user.isEmpty()) {
					tvStatus.setText("主机、端口和用户名不能为空");
					return;
				}

				tvStatus.setText("正在测试连接...");

				SshManager testManager;
				if (switchUseKeyAuth.isChecked()) {
					String passphrase = etPassphrase.getText().toString();
					if (selectedKeyContent == null || selectedKeyContent.isEmpty()) {
						tvStatus.setText("请先选择私钥文件");
						return;
					}
					testManager = new SshManager(host, Integer.parseInt(portStr), user, selectedKeyContent, passphrase);
				} else {
					String pass = etPassword.getText().toString().trim();
					testManager = new SshManager(host, Integer.parseInt(portStr), user, pass);
				}

				boolean manageFirewall = cbManageFirewall.isChecked();
				executor.execute(() -> {
					try {
						testManager.executeCommand("echo 'SSH_TEST_SUCCESS'", 10);
						runOnUiThread(() -> {
							tvStatus.setText("连接成功!");
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

				if (host.isEmpty() || portStr.isEmpty() || user.isEmpty()) {
					tvStatus.setText("主机、端口和用户名不能为空");
					return;
				}

				boolean manageFirewall = cbManageFirewall.isChecked();
				settingsManager.setManageFirewall(manageFirewall);

				if (switchUseKeyAuth.isChecked()) {
					String passphrase = etPassphrase.getText().toString();
					if (selectedKeyContent == null || selectedKeyContent.isEmpty()) {
						tvStatus.setText("请先选择私钥文件再保存");
						return;
					}
					settingsManager.saveKeyAuthSettings(host, Integer.parseInt(portStr), user, selectedKeyContent,
														passphrase);
				} else {
					String pass = etPassword.getText().toString().trim();
					settingsManager.savePasswordAuthSettings(host, Integer.parseInt(portStr), user, pass);
				}

				initializeSshManager();

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

				if (!isOsSupported(osRelease)) {
					runOnUiThread(() -> showUnsupportedSystemDialog(osRelease));
					return;
				}

				String currentUser = sshManager.executeCommand("whoami", 10).trim();
				if (!"root".equals(currentUser)) {
					runOnUiThread(this::showNonRootUserDialog);
					return;
				}

				String scriptContent = readAssetFile("scripts/check_env.sh");
				if (scriptContent == null)
					throw new IOException("无法读取 check_env.sh 脚本");

				String scriptOutput = sshManager.executeCommand(scriptContent, 15).trim();
				runOnUiThread(() -> {
					if (loadingDialog != null && loadingDialog.isShowing())
						loadingDialog.dismiss();

					if (scriptOutput.contains("STATUS:ALL_OK")) {
						isFirstLoad = true;
						showLoadingDialog();
						refreshHandler.post(refreshRunnable);
					} else if (scriptOutput.contains("STATUS:NEEDS_SETUP")) {
						new MaterialAlertDialogBuilder(this).setTitle("环境未配置")
							.setMessage("检测到服务器缺少必要的组件，是否现在开始自动配置？\n\n" + scriptOutput)
							.setNegativeButton("以后再说", null)
							.setPositiveButton("开始配置", (d, w) -> showSetupWizardDialog()).show();
					} else {
						showErrorUI("环境检查失败");
						new MaterialAlertDialogBuilder(this).setTitle("未知错误")
							.setMessage("无法识别服务器环境，请检查脚本或连接。\n\n--- 检查日志 ---\n" + scriptOutput)
							.setPositiveButton("好的", null).show();
					}
				});

			} catch (IOException e) {
				runOnUiThread(() -> {
					if (loadingDialog != null && loadingDialog.isShowing())
						loadingDialog.dismiss();
					showErrorUI("连接或脚本执行失败");
					new MaterialAlertDialogBuilder(this).setTitle("验证失败")
						.setMessage("无法完成服务器验证流程。\n错误详情: " + e.getMessage()).setPositiveButton("好的", null).show();
				});
			}
		});
	}

	private void showUnsupportedSystemDialog(String osInfo) {
		if (loadingDialog != null && loadingDialog.isShowing())
			loadingDialog.dismiss();
		new MaterialAlertDialogBuilder(this).setTitle("操作系统不受支持")
			.setMessage("本工具目前仅支持基于 systemd 的主流 Linux 发行版 (如 Ubuntu, Debian, CentOS 7+)。\n\n检测到您的系统为:\n" + osInfo
						+ "\n\n请更换系统或手动配置后使用。")
			.setCancelable(false).setPositiveButton("返回SSH设置", (d, w) -> showSshSettingsDialog()).show();
	}

	private void showNonRootUserDialog() {
		if (loadingDialog != null && loadingDialog.isShowing())
			loadingDialog.dismiss();
		new MaterialAlertDialogBuilder(this).setTitle("需要 Root 权限")
			.setMessage("为了执行自动化配置和管理，本工具要求必须使用 'root' 用户进行 SSH 连接。\n\n请在下方的设置中使用 root 用户信息登录。")
			.setCancelable(false).setPositiveButton("返回SSH设置", (d, w) -> showSshSettingsDialog()).show();
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
				btnNext.setText("下一步");
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

				startSetupTask(dialog, dialogView, pageProgress, pageCompletion, tvProgressTitle, tvStatus, progressBar,
							   viewAnimator);
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
		try (InputStream in = assetManager.open(assetPath); FileOutputStream out = new FileOutputStream(cacheFile)) {
			byte[] buffer = new byte[1024];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
			}
		}
		return cacheFile;
	}

	private void startSetupTask(AlertDialog dialog, View dialogView, View pageProgress, View pageCompletion,
								TextView tvProgressTitle, TextView tvStatus, LinearProgressIndicator progressBar,
								ViewAnimator viewAnimator) {
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
						} else if (finalLine.startsWith("FINAL_STATUS:SETUP_COMPLETE")) {
							runOnUiThread(() -> {
								viewAnimator.setDisplayedChild(viewAnimator.indexOfChild(pageCompletion));

								ImageView checkMark = pageCompletion.findViewById(R.id.iv_completion_check);
								if (checkMark != null) {
									checkMark.setAlpha(0f);
									checkMark.setScaleX(0.5f);
									checkMark.setScaleY(0.5f);

									checkMark.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(800)
										.setInterpolator(new OvershootInterpolator()).start();
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

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == PICK_KEY_FILE_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
			Uri uri = data.getData();
			if (uri != null) {
				try {
					selectedKeyContent = readTextFromUri(uri);
					selectedKeyName = getFileNameFromUri(uri);
					Toast.makeText(this, "已选择密钥: " + selectedKeyName, Toast.LENGTH_SHORT).show();

				} catch (IOException e) {
					Toast.makeText(this, "读取密钥文件失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
				}
			}
		}
	}

	private String readTextFromUri(Uri uri) throws IOException {
		StringBuilder stringBuilder = new StringBuilder();
		try (InputStream inputStream = getContentResolver().openInputStream(uri);
		BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
			String line;
			while ((line = reader.readLine()) != null) {
				stringBuilder.append(line).append('\n');
			}
		}
		return stringBuilder.toString();
	}

	private String getFileNameFromUri(Uri uri) {
		String result = null;
		if (uri.getScheme().equals("content")) {
			try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
				if (cursor != null && cursor.moveToFirst()) {
					int colIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
					if (colIndex != -1) {
						result = cursor.getString(colIndex);
					}
				}
			}
		}
		if (result == null) {
			result = uri.getPath();
			int cut = result.lastIndexOf('/');
			if (cut != -1) {
				result = result.substring(cut + 1);
			}
		}
		return result;
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

		builder.setView(dialogView).setTitle("FRP 通用配置").setNegativeButton("取消", null)
			.setPositiveButton("保存", (d, w) -> {
			String addr = etAddr.getText().toString().trim();
			String portStr = etPort.getText().toString().trim();
			if (addr.isEmpty() || portStr.isEmpty()) {
				Toast.makeText(this, "地址和端口不能为空", Toast.LENGTH_SHORT).show();
				return;
			}
			settingsManager.saveFrpCommonSettings(addr, Integer.parseInt(portStr),
												  etToken.getText().toString().trim());
			Toast.makeText(this, "通用配置已保存", Toast.LENGTH_SHORT).show();
		}).show();
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

		final AlertDialog infoDialog = new MaterialAlertDialogBuilder(this).setTitle("服务器状态总览").setView(dialogView)
			.setPositiveButton("刷新", null).setNegativeButton("关闭", null).create();

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
		} catch (Exception e) {
		}

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
		} catch (Exception e) {
		}

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
						summary.append("  - ").append(mountPoint).append(": ").append(used).append(" / ").append(size)
							.append(" (").append(usePercent).append(")\n");
						foundDisks = true;
					}
				}
			}
			if (!foundDisks) {
				summary.append("  - 未能获取到硬盘信息。\n");
			}
		} catch (Exception e) {
			summary.append("  - 解析硬盘信息时出错。\n");
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
				String command = "echo '--- 系统负载与运行时间 ---'; " + "uptime; " + "echo; echo '--- 内存使用情况 ---'; "
					+ "free -h; " + "echo; echo '--- 硬盘使用情况 ---'; " + "df -h | grep -E '^/dev/|Filesystem'; "
					+ "echo; echo '---INTERNAL_IP---'; " + "hostname -I | awk '{print $1}'; "
					+ "echo; echo '---FIREWALL_TYPE---'; "
					+ "if systemctl is-active --quiet firewalld; then echo 'firewalld'; "
					+ "elif command -v ufw >/dev/null && ufw status | grep -q 'Status: active'; then echo 'ufw'; "
					+ "elif command -v iptables >/dev/null; then echo 'iptables'; " + "else echo 'none'; fi";

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
						String toolInfo = "\n❖ FRP Manager - 版本: " + versionName + " ❖\n\n" + "by - Momu\n";
						appendLog(textView, scrollView, logBuilder, toolInfo, "info");
					} catch (Exception e) {
					}

					appendLog(textView, scrollView, logBuilder, summary, "success");

					String finalInfo = "服务器公网IP: " + settingsManager.getHost() + "\n" + "服务器内网IP: " + finalInternalIp
						+ "\n";
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

	private void appendLog(TextView textView, ScrollView scrollView, StringBuilder logBuilder, String message,
						   String type) {
		runOnUiThread(() -> {
			String color;
			switch (type) {
				case "success" :
					color = "#4CAF50";
					break;
				case "error" :
					color = "#F44336";
					break;
				case "info" :
				default :
					color = "#FFFFFF";
					break;
			}
			String formattedMessage = "<font color='" + color + "'>"
				+ TextUtils.htmlEncode(message).replace("\n", "<br>") + "</font><br>";
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
		if (!settingsManager.isConfigured() || sshManager == null) {
			showErrorUI("SSH未配置或环境不安全");
			return;
		}

		executor.execute(() -> {
			String currentStep = "初始化刷新任务 (Initializing refresh task)";
			final String remotePath = "/tmp/get_stats.sh";
			File scriptFile = null;

			try {
				currentStep = "从Assets复制脚本到本地缓存 (Copying script from assets to local cache)";
				scriptFile = copyAssetToCache("get_stats.sh", "get_stats.sh");

				currentStep = "通过SFTP上传脚本到 " + remotePath + " (Uploading script via SFTP)";
				try (SFTPClient sftp = sshManager.getSftpClient()) {
					sftp.put(scriptFile.getAbsolutePath(), remotePath);
				}

				currentStep = "在服务器上为脚本添加执行权限 (chmod +x " + remotePath + ")";
				sshManager.executeCommand("chmod +x " + remotePath);

				currentStep = "在服务器上执行脚本 " + remotePath + " (Executing script on server)";
				String jsonResult = sshManager.executeCommand(remotePath);

				currentStep = "在服务器上删除临时脚本 " + remotePath + " (Cleaning up temporary script)";
				sshManager.executeCommand("rm " + remotePath);

				currentStep = "解析服务器返回的JSON数据 (Parsing JSON response)";
				if (jsonResult == null || jsonResult.trim().isEmpty()) {
					throw new IOException("执行远程脚本后返回了空响应。");
				}
				JSONObject json = new JSONObject(jsonResult);

				final int cpuUsage = json.optInt("cpuUsage", 0);
				final int memUsage = json.optInt("memUsage", 0);
				final int cpuTemp = json.optInt("cpuTemp", 0) / 1000;
				final String dfOutput = json.optString("dfOutput", "").replace("\\n", "\n").trim();
				final String lsblkOutput = json.optString("lsblkOutput", "").replace("\\n", "\n").trim();
				final String rawFileNames = json.optString("frpTotalFiles", "").replace("\\n", "\n").trim();
				final String rawRunningIds = json.optString("frpRunningServices", "").replace("\\n", "\n").trim();
				final String rawEnabledIds = json.optString("frpEnabledServices", "").replace("\\n", "\n").trim();
				final String cpuModel = json.optString("cpuModel", "CPU");
				final String memoryDetails = json.optString("memoryDetails", "内存");
				final String coreCount = String.valueOf(json.optInt("coreCount", 0));

				Map<String, Set<String>> totalPortMap = parseFileNamesToPortMap(rawFileNames);
				this.totalPortList = formatPortMapToList(totalPortMap);
				Map<String, Set<String>> runningPortMap = parseServiceIdsToPortMap(rawRunningIds);
				this.runningPortList = formatPortMapToList(runningPortMap);
				Set<String> runningIdSet = new HashSet<>(!rawRunningIds.trim().isEmpty() ? Arrays.asList(rawRunningIds.trim().split("\\s+")) : Collections.emptySet());
				Set<String> enabledIdSet = new HashSet<>(!rawEnabledIds.trim().isEmpty() ? Arrays.asList(rawEnabledIds.trim().split("\\s+")) : Collections.emptySet());
				enabledIdSet.removeAll(runningIdSet);
				String errorIds = String.join(" ", enabledIdSet);
				Map<String, Set<String>> errorPortMap = parseServiceIdsToPortMap(errorIds);
				this.errorPortList = formatPortMapToList(errorPortMap);
				this.pendingPortList.clear();

				runOnUiThread(() -> {
					if (isFirstLoad && loadingDialog != null) {
						loadingDialog.dismiss();
						isFirstLoad = false;
					}
					updateServerStatusUI(cpuUsage, memUsage, cpuTemp, dfOutput, lsblkOutput, cpuModel, memoryDetails, coreCount);
					updateFrpOverviewUI(totalPortMap.size(), runningPortMap.size(), pendingPortList.size(), errorPortMap.size());
				});

			} catch (Exception e) {
				final String finalCurrentStep = currentStep;
				runOnUiThread(() -> {
					if (isFirstLoad && loadingDialog != null) {
						loadingDialog.dismiss();
						isFirstLoad = false;
					}
					refreshHandler.removeCallbacks(refreshRunnable);
					showErrorUI("刷新失败，请查看诊断报告");
					showDebugDialog("刷新操作失败 (诊断信息)",
									"失败步骤 (Failed Step):\n" + finalCurrentStep + "\n\n" +
									"错误类型 (Error Type):\n" + e.getClass().getSimpleName() + "\n\n" +
									"错误信息 (Error Message):\n" + e.getMessage()
									);
				});
				e.printStackTrace();
			}
		});
	}

	private void showDebugDialog(String title, String message) {
		if (isFinishing() || isDestroyed()) {
			return;
		}

		TextView tv = new TextView(this);
		tv.setText(message);
		tv.setTextIsSelectable(true);
		tv.setPadding(40, 20, 40, 20);
		ScrollView scrollView = new ScrollView(this);
		scrollView.addView(tv);

		new MaterialAlertDialogBuilder(this)
			.setTitle(title)
			.setView(scrollView)
			.setCancelable(false)
			.setPositiveButton("复制并关闭", (dialog, which) -> {
			            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			            ClipData clip = ClipData.newPlainText("Server Diag Log", message);
			            clipboard.setPrimaryClip(clip);
			            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show();
			            dialog.dismiss();
		        })
		        .show();
	}

	private void updateServerStatusUI(int cpu, int mem, int temp, String dfOutput, String lsblkOutput, String cpuModel, String memoryDetails, String coreCount) {
		progressCpu.setProgressCompat(cpu, true);
		progressMemory.setProgressCompat(mem, true);
		progressCpuTemp.setProgressCompat(Math.min(temp, 100), true);

		String simplifiedCpuModel = cpuModel.replaceAll("\\(R\\)|\\(TM\\)| CPU |@.*GHz", "").replaceAll("\\s+", " ");
		String cpuLabelText = simplifiedCpuModel.isEmpty() ? "CPU" : simplifiedCpuModel;
		if (!coreCount.isEmpty()) {
			try {
				if (Integer.parseInt(coreCount) > 0) {
					cpuLabelText += " (" + coreCount + " Cores)";
				}
			} catch (NumberFormatException e) {

			}
		}
		tvCpuLabel.setText(cpuLabelText);
		tvMemoryLabel.setText(memoryDetails.isEmpty() ? "内存" : memoryDetails);

		tvCpuUsage.setText(cpu + "%");
		tvMemoryUsage.setText(mem + "%");
		tvCpuTemp.setText(temp + "°C");

		updateDiskStatusCard(dfOutput, lsblkOutput);
	}

	private void updateDiskStatusCard(String dfOutput, String lsblkOutput) {
		boolean wasExpanded = layoutDiskDetails.getVisibility() == View.VISIBLE;
		if(wasExpanded) {
			layoutDiskDetails.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
		}
		layoutDiskDetails.removeAllViews();
		cardDiskStatus.setVisibility(View.VISIBLE);

		if (dfOutput.isEmpty() && lsblkOutput.isEmpty()) {
			TextView errorView = new TextView(this);
			errorView.setText("未能获取到任何硬盘信息");
			errorView.setPadding(0, 16, 0, 16);
			layoutDiskDetails.addView(errorView);
			return;
		}

		Map<String, DiskInfo> mountedDisksMap = new HashMap<>();
		String[] dfLines = dfOutput.split("\n");
		for (String line : dfLines) {
			if (line.toLowerCase().startsWith("filesystem")) continue;
			String[] parts = line.trim().split("\\s+");
			if (parts.length < 7) continue;
			try {
				String fsName = parts[0];
				String size = parts[2];
				String used = parts[3];
				int usePercent = Integer.parseInt(parts[5].replace("%", ""));
				String mountPoint = parts[6];
				mountedDisksMap.put(fsName, new DiskInfo(fsName, size, used, usePercent, mountPoint));
			} catch (Exception e) {
				Log.e("DiskParse", "Could not parse df line: " + line, e);
			}
		}

		Set<String> parentDisksWithMounts = new HashSet<>();
		for (String mountedPartition : mountedDisksMap.keySet()) {
			String parent = mountedPartition.replaceAll("/dev/|p?[0-9]+$", "");
			parentDisksWithMounts.add(parent);
		}

		List<DiskInfo> unmountedDisks = new ArrayList<>();
		String[] lsblkLines = lsblkOutput.split("\n");
		for(String line : lsblkLines) {
			String[] parts = line.trim().split("\\s+");
			if (parts.length < 3) continue;

			String deviceName = parts[0];
			String deviceType = parts[2];

			if("disk".equals(deviceType)){
				if(!parentDisksWithMounts.contains(deviceName)){
					String size = parts[1];
					unmountedDisks.add(new DiskInfo(deviceName, size));
				}
			}
		}

		LayoutInflater inflater = LayoutInflater.from(this);
		List<DiskInfo> sortedMountedDisks = new ArrayList<>(mountedDisksMap.values());
		sortedMountedDisks.sort(Comparator.comparing(d -> d.deviceName));

		for (DiskInfo disk : sortedMountedDisks) {
			View diskItemView = inflater.inflate(R.layout.include_disk_item, layoutDiskDetails, false);
			TextView tvMountPoint = diskItemView.findViewById(R.id.tv_disk_mount_point);
			TextView tvUsageDetails = diskItemView.findViewById(R.id.tv_disk_usage_details);
			TextView tvUsagePercent = diskItemView.findViewById(R.id.tv_disk_usage_percent);
			LinearProgressIndicator progressDisk = diskItemView.findViewById(R.id.progress_disk_item);

			tvMountPoint.setText(disk.deviceName);
			tvUsageDetails.setText(String.format("%s / %s (挂载于 %s)", disk.usedSize, disk.totalSize, disk.mountPoint));
			tvUsagePercent.setText(disk.usePercent + "%");
			progressDisk.setProgress(disk.usePercent);
			layoutDiskDetails.addView(diskItemView);
		}

		if (!unmountedDisks.isEmpty() && !sortedMountedDisks.isEmpty()) {
			View divider = new View(this);
			LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2);
			params.setMargins(0, 24, 0, 24);
			divider.setLayoutParams(params);
			divider.setBackgroundColor(Color.DKGRAY);
			layoutDiskDetails.addView(divider);
		}

		unmountedDisks.sort(Comparator.comparing(d -> d.deviceName));
		for (DiskInfo disk : unmountedDisks) {
			View unmountedView = inflater.inflate(R.layout.include_unmounted_disk_item, layoutDiskDetails, false);
			TextView tvName = unmountedView.findViewById(R.id.tv_unmounted_disk_name);
			TextView tvSize = unmountedView.findViewById(R.id.tv_unmounted_disk_size);
			tvName.setText("/dev/" + disk.deviceName);
			tvSize.setText("总容量: " + disk.totalSize);
			layoutDiskDetails.addView(unmountedView);
		}

		layoutDiskDetails.setVisibility(wasExpanded ? View.VISIBLE : View.GONE);
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
		progressCpuTemp.setIndeterminate(true);

		tvCpuUsage.setText(message);
		tvMemoryUsage.setText("错误");
		tvCpuTemp.setText("...");

		cardDiskStatus.setVisibility(View.GONE);

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
		ScrollView scrollView = dialogView.findViewById(R.id.log_scroll_view);
		final TextView logContent = dialogView.findViewById(R.id.log_text_view);
		Button copyButton = dialogView.findViewById(R.id.btn_copy_log);
		if (copyButton != null)
			copyButton.setVisibility(View.GONE);
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
				if (!isLogViewerActive)
					return;

				executor.execute(() -> {
					String logCommand = String.format(
						"echo '---SYSTEMD STATUS---' && " + "systemctl status frpc@%s.service --no-pager && "
						+ "echo '\n---JOURNAL LOGS (last 20 lines)---' && "
						+ "journalctl -u frpc@%s.service --no-pager -n 20",
						port, port);
					try {
						String rawLogs = sshManager.executeCommand(logCommand);
						SpannableString formattedLogs = formatLogsForDisplay(rawLogs);
						runOnUiThread(() -> {
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
			spannable.setSpan(new ForegroundColorSpan(Color.parseColor("#4CAF50")), successMatcher.start(),
							  successMatcher.end(), 0);
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
			if (settingsManager.getUseKeyAuth()) {
				String key = settingsManager.getPrivateKey();
				Log.d("SSH_DEBUG", "Initializing with KEY auth. Key is null? " + (key == null));
				if (key != null) {
					Log.d("SSH_DEBUG", "Key starts with: " + key.substring(0, Math.min(key.length(), 30)));
				}

				sshManager = new SshManager(settingsManager.getHost(), settingsManager.getPort(),
											settingsManager.getUsername(), key, settingsManager.getPassphrase());
			} else {
				Log.d("SSH_DEBUG", "Initializing with PASSWORD auth.");
				sshManager = new SshManager(settingsManager.getHost(), settingsManager.getPort(),
											settingsManager.getUsername(), settingsManager.getPassword());
			}
		} else {
			Log.d("SSH_DEBUG", "Not configured. Initializing with null settings.");
			sshManager = new SshManager(null, 0, null, null);
		}
	}

	private void executeCleanup() {
		final AlertDialog dialog = new MaterialAlertDialogBuilder(this).setView(R.layout.dialog_loading)
			.setCancelable(false).show();

		executor.execute(() -> {
			try {
				String scriptContent = readAssetFile("scripts/cleanup.sh");
				if (scriptContent == null)
					throw new IOException("无法读取清理脚本");

				String result = sshManager.executeCommand(scriptContent, 60);

				runOnUiThread(() -> {
					dialog.dismiss();
					new MaterialAlertDialogBuilder(this).setTitle("操作完成").setMessage("清理脚本已执行完毕。\n\n服务器返回:\n" + result)
						.setPositiveButton("好的", null).show();
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

	private static class DiskInfo {
		String deviceName;
		String totalSize;
		boolean isMounted;

		String usedSize;
		int usePercent;
		String mountPoint;

		public DiskInfo(String deviceName, String totalSize, String usedSize, int usePercent, String mountPoint) {
			this.isMounted = true;
			this.deviceName = deviceName;
			this.totalSize = totalSize;
			this.usedSize = usedSize;
			this.usePercent = usePercent;
			this.mountPoint = mountPoint;
		}

		public DiskInfo(String deviceName, String totalSize) {
			this.isMounted = false;
			this.deviceName = deviceName;
			this.totalSize = totalSize;
		}
	}
}
