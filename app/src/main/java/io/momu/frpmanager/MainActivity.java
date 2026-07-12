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
	private TextView tvDashboardState;
	private LinearProgressIndicator syncProgress;
	private int lastCpuValue = -1, lastMemoryValue = -1, lastTemperatureValue = -1;
	private int lastTotalValue = -1, lastRunningValue = -1, lastPendingValue = -1, lastErrorValue = -1;
	private final Map<TextView, ValueAnimator> metricAnimators = new HashMap<>();

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

	private MaterialButton btnEnterFrpManager, btnViewLogs, btnSettings, btnDebugClear, btnFrpSettings,
		btnRepairFrpDeployment;

	private SshManager sshManager;
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private SshSettingsManager settingsManager;

	private List<String> totalPortList = new ArrayList<>();
	private List<String> runningPortList = new ArrayList<>();
	private List<String> errorPortList = new ArrayList<>();
	private List<String> pendingPortList = new ArrayList<>();
	private Map<String, List<String>> logUnitsByDisplay = new HashMap<>();

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
		for (ValueAnimator animator : new ArrayList<>(metricAnimators.values())) animator.cancel();
		metricAnimators.clear();
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

	private Map<String, List<String>> buildLogUnitMap(String rawFileNames) {
		Map<String, Set<String>> protocolsByPort = new HashMap<>();
		Map<String, List<String>> unitsByPort = new HashMap<>();
		if (rawFileNames != null) {
			Pattern pattern = Pattern.compile("port_(\\d+)(?:_(tcp|udp))?\\.ini");
			for (String fileName : rawFileNames.trim().split("\\s+")) {
				Matcher matcher = pattern.matcher(fileName);
				if (!matcher.matches()) continue;
				String port = matcher.group(1);
				String protocol = matcher.group(2);
				protocolsByPort.computeIfAbsent(port, ignored -> new HashSet<>()).add(protocol == null ? "tcp" : protocol);
				unitsByPort.computeIfAbsent(port, ignored -> new ArrayList<>()).add(protocol == null
					? "frpc@" + port + ".service" : "frpc@" + port + "_" + protocol + ".service");
			}
		}
		Map<String, List<String>> byDisplay = new HashMap<>();
		for (String display : formatPortMapToList(protocolsByPort)) {
			Matcher port = Pattern.compile("^(\\d+) ").matcher(display);
			if (port.find()) byDisplay.put(display, unitsByPort.getOrDefault(port.group(1), Collections.emptyList()));
		}
		return byDisplay;
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
		tvDashboardState = findViewById(R.id.tv_dashboard_state);
		syncProgress = findViewById(R.id.sync_progress);

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
		btnRepairFrpDeployment = findViewById(R.id.btn_repair_frp_deployment);

		tvStatTotalLabel.setText("端口总数");
		tvStatRunningLabel.setText("运行中");
		tvStatPendingLabel.setText("待应用");
		tvStatErrorLabel.setText("错误");

		btnEnterFrpManager.setOnClickListener(v -> {
			if (!settingsManager.isConfigured())
				return;
			openFrpManager();
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

		btnRepairFrpDeployment.setOnClickListener(v -> confirmFrpDeploymentRepair());

		statTotalLayout.setOnClickListener(v -> showPortsDialog("total", "端口总数列表", totalPortList));
		statRunningLayout.setOnClickListener(v -> showPortsDialog("running", "运行中端口列表", runningPortList));
		statErrorLayout.setOnClickListener(v -> showPortsDialog("error", "错误端口列表", errorPortList));
		statPendingLayout.setOnClickListener(v -> showPortsDialog("pending", "待应用更改列表", pendingPortList));

		layoutDiskHeader.setOnClickListener(v -> {
			isDiskCardExpanded = !isDiskCardExpanded;
			toggleDiskCardWithAnimation(isDiskCardExpanded);
		});

		animateDashboardEntrance();
	}

	private void openFrpManager() {
		if (settingsManager.hasFrpCommonSettings()) {
			startActivity(new Intent(MainActivity.this, FrpManagerActivity.class));
			return;
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle("FRP 环境尚未就绪")
			.setMessage("完整的一键部署会自动配置本机受管 frps；当前设置中没有可用的默认连接，"
				+ "可能尚未执行新版环境初始化。\n\n你可以直接修复部署，或改用独立外部 frps。"
				+ "如果服务器上已有单独端口配置，也可以继续进入查看或编辑。")
			.setPositiveButton("修复部署", (dialog, which) -> confirmFrpDeploymentRepair())
			.setNegativeButton("仍然进入", (dialog, which) ->
				startActivity(new Intent(MainActivity.this, FrpManagerActivity.class)))
			.setNeutralButton("外部 frps", (dialog, which) -> showFrpCommonSettingsDialog())
			.show();
	}

	private void confirmFrpDeploymentRepair() {
		if (!settingsManager.isConfigured()) {
			Toast.makeText(this, "请先配置 SSH 连接", Toast.LENGTH_SHORT).show();
			showSshSettingsDialog();
			return;
		}
		new MaterialAlertDialogBuilder(this)
			.setTitle("修复 / 重新部署 FRP")
			.setMessage("软件将重新上传 APK 内置的 frpc 与 frps，校验架构和 SHA-256，修复受管 systemd 服务、鉴权与本机控制端点。\n\n"
				+ "现有由 Frp Tool 管理的端口配置会被保留并迁移；不属于本工具的 frps 服务或配置不会被覆盖。")
			.setNegativeButton("取消", null)
			.setPositiveButton("开始修复", (dialog, which) -> showSetupWizardDialog())
			.show();
	}

	private void animateDashboardEntrance() {
		if (!ValueAnimator.areAnimatorsEnabled()) return;
		int[] cardIds = {R.id.card_server_status, R.id.card_disk_status, R.id.card_frp_overview, R.id.card_actions};
		float offset = 14f * getResources().getDisplayMetrics().density;
		for (int index = 0; index < cardIds.length; index++) {
			View card = findViewById(cardIds[index]);
			card.setAlpha(0f);
			card.setTranslationY(offset);
			card.animate().alpha(1f).translationY(0f).setStartDelay(index * 70L).setDuration(420L)
				.setInterpolator(new DecelerateInterpolator(1.6f)).start();
		}
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
		final TextInputEditText etFrpsFirewallHost = dialogView.findViewById(R.id.et_frps_firewall_host);
		final TextInputEditText etFrpsFirewallPort = dialogView.findViewById(R.id.et_frps_firewall_port);

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
			etFrpsFirewallHost.setText(settingsManager.getFrpsFirewallHost());
			etFrpsFirewallPort.setText(String.valueOf(settingsManager.getFrpsFirewallPort()));

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
						testManager = new SshManager(host, Integer.parseInt(portStr), user, selectedKeyContent, passphrase,
							settingsManager.getHostKeyFingerprint(host, Integer.parseInt(portStr)));
					} else {
						String pass = etPassword.getText().toString().trim();
						testManager = SshManager.password(host, Integer.parseInt(portStr), user, pass,
							settingsManager.getHostKeyFingerprint(host, Integer.parseInt(portStr)));
				}

					boolean manageFirewall = cbManageFirewall.isChecked();
					String firewallHost = etFrpsFirewallHost.getText().toString().trim();
					boolean hasDedicatedFirewallHost = !firewallHost.isEmpty();
					int firewallPort = 22;
					if (manageFirewall) {
						try { firewallPort = Integer.parseInt(etFrpsFirewallPort.getText().toString().trim()); }
						catch (NumberFormatException exception) { tvStatus.setText("frps 防火墙 SSH 端口无效"); return; }
						if (firewallPort < 1 || firewallPort > 65535) { tvStatus.setText("frps 防火墙 SSH 端口无效"); return; }
						if (firewallHost.isEmpty()) firewallHost = settingsManager.getFirewallHost(host);
						if (!hasDedicatedFirewallHost && firewallHost.equalsIgnoreCase(host)) firewallPort = Integer.parseInt(portStr);
					}
					final String testFirewallHost = firewallHost;
					final int testFirewallPort = firewallPort;
					final boolean testManageFirewall = manageFirewall;
					executor.execute(() -> {
						boolean firewallStage = false;
						try {
							testManager.executeCommand("echo 'SSH_TEST_SUCCESS'", 10);
							if (testManageFirewall) {
								firewallStage = true;
								SshManager firewallManager = switchUseKeyAuth.isChecked()
									? new SshManager(testFirewallHost, testFirewallPort, user, selectedKeyContent, etPassphrase.getText().toString(),
										settingsManager.getHostKeyFingerprint(testFirewallHost, testFirewallPort))
									: SshManager.password(testFirewallHost, testFirewallPort, user, etPassword.getText().toString().trim(),
										settingsManager.getHostKeyFingerprint(testFirewallHost, testFirewallPort));
								firewallManager.executeCommand("echo 'FRPS_FIREWALL_SSH_TEST_SUCCESS'", 10);
							}
							runOnUiThread(() -> {
								tvStatus.setText(testManageFirewall ? "SSH 与防火墙通道均已验证。" : "连接成功!");
							tvStatus.setTextColor(Color.parseColor("#4CAF50"));
						});
						} catch (SshManager.HostKeyVerificationException e) {
							boolean isFirewallKey = firewallStage;
							String trustHost = isFirewallKey ? testFirewallHost : host;
							int trustPort = isFirewallKey ? testFirewallPort : Integer.parseInt(portStr);
							runOnUiThread(() -> showTrustHostKeyDialog(trustHost, trustPort, e,
								() -> tvStatus.setText("已保存主机指纹，请再次测试连接。")));
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

					int sshPort;
					try { sshPort = Integer.parseInt(portStr); if (sshPort < 1 || sshPort > 65535) throw new NumberFormatException(); }
					catch (NumberFormatException exception) { tvStatus.setText("SSH 端口无效"); return; }
						boolean manageFirewall = cbManageFirewall.isChecked();
					String frpsFirewallHost = etFrpsFirewallHost.getText().toString().trim();
					int frpsFirewallPort = 22;
					if (manageFirewall) {
						try { frpsFirewallPort = Integer.parseInt(etFrpsFirewallPort.getText().toString().trim()); }
						catch (NumberFormatException exception) { tvStatus.setText("frps 防火墙 SSH 端口无效"); return; }
							if (frpsFirewallPort < 1 || frpsFirewallPort > 65535) {
								tvStatus.setText("frps 防火墙 SSH 端口无效");
								return;
							}
					}
					settingsManager.saveFrpsFirewallSettings(frpsFirewallHost, frpsFirewallPort);
					settingsManager.setManageFirewall(manageFirewall);

					if (switchUseKeyAuth.isChecked()) {
						String passphrase = etPassphrase.getText().toString();
						if (selectedKeyContent == null || selectedKeyContent.isEmpty()) {
							tvStatus.setText("请先选择私钥文件再保存");
							return;
						}
						settingsManager.saveKeyAuthSettings(host, sshPort, user, selectedKeyContent, passphrase);
					} else {
						String pass = etPassword.getText().toString().trim();
						settingsManager.savePasswordAuthSettings(host, sshPort, user, pass);
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
		showSyncIndicator();
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

					String mode = settingsManager.isEmbeddedFrpsManaged() ? "embedded" : "external";
					String scriptOutput = sshManager.executeCommand(
						RemoteScriptCommand.bash("export FRP_TOOL_MODE=" + mode + "\n" + scriptContent), 20).trim();
				runOnUiThread(() -> {
					if (scriptOutput.contains("STATUS:ALL_OK")) {
						isFirstLoad = true;
						refreshHandler.post(refreshRunnable);
					} else if (scriptOutput.contains("STATUS:NEEDS_SETUP")) {
						hideSyncIndicator();
						new MaterialAlertDialogBuilder(this).setTitle("环境未配置")
							.setMessage("检测到服务器缺少必要的组件，是否现在开始自动配置？\n\n" + scriptOutput)
							.setNegativeButton("以后再说", null)
							.setPositiveButton("开始配置", (d, w) -> showSetupWizardDialog()).show();
					} else {
						hideSyncIndicator();
						showErrorUI("环境检查失败");
						new MaterialAlertDialogBuilder(this).setTitle("未知错误")
							.setMessage("无法识别服务器环境，请检查脚本或连接。\n\n--- 检查日志 ---\n" + scriptOutput)
							.setPositiveButton("好的", null).show();
					}
				});

			} catch (SshManager.HostKeyVerificationException e) {
				runOnUiThread(() -> {
					hideSyncIndicator();
					showTrustHostKeyDialog(settingsManager.getHost(), settingsManager.getPort(), e,
						this::checkEnvironmentAndProceed);
				});
			} catch (IOException e) {
				runOnUiThread(() -> {
					hideSyncIndicator();
					showErrorUI("连接或脚本执行失败");
					new MaterialAlertDialogBuilder(this).setTitle("验证失败")
						.setMessage("无法完成服务器验证流程。\n错误详情: " + e.getMessage()).setPositiveButton("好的", null).show();
				});
			}
		});
	}

	private void showUnsupportedSystemDialog(String osInfo) {
		hideSyncIndicator();
		new MaterialAlertDialogBuilder(this).setTitle("操作系统不受支持")
			.setMessage("本工具目前仅支持基于 systemd 的主流 Linux 发行版 (如 Ubuntu, Debian, CentOS 7+)。\n\n检测到您的系统为:\n" + osInfo
						+ "\n\n请更换系统或手动配置后使用。")
			.setCancelable(false).setPositiveButton("返回SSH设置", (d, w) -> showSshSettingsDialog()).show();
	}

	private void showNonRootUserDialog() {
		hideSyncIndicator();
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
			java.security.MessageDigest digest;
			try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
			catch (java.security.NoSuchAlgorithmException exception) { throw new IOException("设备不支持 SHA-256", exception); }
			byte[] buffer = new byte[1024];
			int read;
			while ((read = in.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);
			}
				if (assetPath.startsWith("frpc/") || assetPath.startsWith("frps/")) {
					String actual = bytesToHex(digest.digest());
					String expected = expectedAssetChecksum(assetPath);
					String component = assetPath.substring(0, assetPath.indexOf('/'));
					if (expected == null || !expected.equalsIgnoreCase(actual)) {
						cacheFile.delete();
						throw new IOException("内置 " + component + " SHA-256 校验失败");
					}
					try (FileOutputStream checksum = new FileOutputStream(new File(getCacheDir(), cacheFileName + ".sha256"))) {
						checksum.write((expected + "  " + component + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
					}
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
			getAssets().open(component + "/checksums.txt")))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String[] fields = line.trim().split("\\s+", 2);
				if (fields.length == 2 && relativePath.equals(fields[1])) return fields[0];
			}
		}
		return null;
	}

	private String bytesToHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) builder.append(String.format(Locale.US, "%02x", value));
		return builder.toString();
	}

	private String frpcAssetPathForArchitecture(String architecture) throws IOException {
		if ("aarch64".equals(architecture)) return "frpc/aarch64/frpc";
		if ("x86_64".equals(architecture)) return "frpc/x86_64/frpc";
		throw new IOException("不支持的服务器 CPU 架构: " + architecture + "。不会错误下发 x86_64 二进制。");
	}

	private String frpsAssetPathForArchitecture(String architecture) throws IOException {
		if ("aarch64".equals(architecture)) return "frps/aarch64/frps";
		if ("x86_64".equals(architecture)) return "frps/x86_64/frps";
		throw new IOException("不支持的服务器 CPU 架构: " + architecture + "。不会错误下发 x86_64 二进制。");
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
				String frpcAssetPath = frpcAssetPathForArchitecture(arch);
				String frpsAssetPath = frpsAssetPathForArchitecture(arch);
				final String status1 = "检测到架构: " + arch + "，准备上传 frpc + frps...";
				runOnUiThread(() -> {
					tvStatus.setText(status1);
					progressBar.setProgress(10, true);
				});

				File frpcFile = copyAssetToCache(frpcAssetPath, "frpc");
				File frpsFile = copyAssetToCache(frpsAssetPath, "frps");
				runOnUiThread(() -> {
					tvStatus.setText("正在通过 SFTP 上传匹配架构的 frpc 与 frps...");
					progressBar.setIndeterminate(true);
				});
				try (SFTPClient sftp = sshManager.getSftpClient()) {
					sftp.put(frpcFile.getAbsolutePath(), "/root/frpc_temp_upload");
					sftp.put(new File(getCacheDir(), "frpc.sha256").getAbsolutePath(), "/root/frpc_temp_upload.sha256");
					sftp.put(frpsFile.getAbsolutePath(), "/root/frps_temp_upload");
					sftp.put(new File(getCacheDir(), "frps.sha256").getAbsolutePath(), "/root/frps_temp_upload.sha256");
				}
				final int UPLOAD_COMPLETE_PROGRESS = 25;
				runOnUiThread(() -> {
					progressBar.setIndeterminate(false);
					tvStatus.setText("上传完成，正在配置受管 frps、frpc 和 systemd...");
					progressBar.setProgress(UPLOAD_COMPLETE_PROGRESS, true);
				});
				String setupScript = readAssetFile("scripts/setup_env.sh");
				if (setupScript == null) throw new IOException("无法读取 setup_env.sh");
				boolean setupCompleted = false;
				try (SshManager.CommandStreamer streamer = sshManager.executeCommandAndStreamOutput(
					RemoteScriptCommand.bash(setupScript), 300)) {
					BufferedReader reader = new BufferedReader(new InputStreamReader(streamer.getInputStream()));
					String line;
					while ((line = reader.readLine()) != null) {
						final String finalLine = line;
						if (finalLine.startsWith("PROGRESS:")) {
							String[] parts = finalLine.split(";", 2);
							if (parts.length == 2) {
								try {
									int progress = Integer.parseInt(parts[0].substring("PROGRESS:".length()));
									String status = parts[1].replaceFirst("^STATUS:", "");
									runOnUiThread(() -> {
										progressBar.setIndeterminate(false);
										progressBar.setProgress(progress, true);
										tvStatus.setText(status);
									});
								} catch (NumberFormatException ignored) {
									// Ignore malformed progress only; final status and exit code remain authoritative.
								}
							}
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
				String managedToken = downloadEmbeddedFrpsToken();
				settingsManager.saveFrpCommonSettings("127.0.0.1", 7000, managedToken);
				settingsManager.saveFrpsFirewallSettings(settingsManager.getHost(), settingsManager.getPort());
				settingsManager.setManageFirewall(true);
				settingsManager.setEmbeddedFrpsManaged(true);
				runOnUiThread(() -> showSetupCompletion(dialog, pageCompletion, viewAnimator));
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

	private String downloadEmbeddedFrpsToken() throws IOException {
		File localToken = new File(getCacheDir(), "managed-frps.token");
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

	private void showSetupCompletion(AlertDialog dialog, View pageCompletion, ViewAnimator viewAnimator) {
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
		}, 2200);
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

		final AlertDialog commonSettingsDialog = builder.setView(dialogView).setTitle("外部 frps 连接").setNegativeButton("取消", null)
			.setPositiveButton("保存", null).create();
		commonSettingsDialog.setOnShowListener(ignored -> commonSettingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			String addr = etAddr.getText().toString().trim();
			String portStr = etPort.getText().toString().trim();
			if (!isValidFrpsAddress(addr) || portStr.isEmpty()
				|| etToken.getText().toString().matches(".*[\\r\\n\\u0000].*")) {
				Toast.makeText(this, "外部 frps 地址格式不正确，且地址和端口不能为空", Toast.LENGTH_SHORT).show();
				return;
			}
			int serverPort;
			try { serverPort = Integer.parseInt(portStr); if (serverPort < 1 || serverPort > 65535) throw new NumberFormatException(); }
			catch (NumberFormatException exception) { Toast.makeText(this, "FRP 端口无效", Toast.LENGTH_SHORT).show(); return; }
			String token = etToken.getText().toString().trim();
			String sshHost = settingsManager.getHost();
			if (sshHost != null && addr.equalsIgnoreCase(sshHost.trim())) {
				new MaterialAlertDialogBuilder(this)
					.setTitle("确认同机 frps")
					.setMessage("你填写的 frps 地址与当前 SSH 管理主机相同。新版托管模式默认使用 "
						+ "127.0.0.1 连接受管 frps；只有你明确让 frps 在该 SSH 地址监听控制端口时，"
						+ "才应填写主机公网地址。")
					.setNegativeButton("返回修改", null)
					.setPositiveButton("确认同机运行 frps", (dialog, which) ->
						persistFrpCommonSettings(commonSettingsDialog, addr, serverPort, token))
					.show();
				return;
			}
			persistFrpCommonSettings(commonSettingsDialog, addr, serverPort, token);
		}));
		commonSettingsDialog.show();
	}

	private void persistFrpCommonSettings(AlertDialog dialog, String address, int port, String token) {
		settingsManager.saveFrpCommonSettings(address, port, token);
		settingsManager.setEmbeddedFrpsManaged(
			("127.0.0.1".equals(address) || "localhost".equalsIgnoreCase(address)) && port == 7000);
		Toast.makeText(this, "外部 frps 默认连接已保存", Toast.LENGTH_SHORT).show();
		dialog.dismiss();
	}

	private boolean isValidFrpsAddress(String address) {
		if (address == null || address.isEmpty() || address.indexOf('\n') >= 0
			|| address.indexOf('\r') >= 0 || address.indexOf('\u0000') >= 0) return false;
		return Patterns.IP_ADDRESS.matcher(address).matches() || Patterns.DOMAIN_NAME.matcher(address).matches();
	}

	private void showServerInfoDialog() {
		LayoutInflater inflater = LayoutInflater.from(this);
		View dialogView = inflater.inflate(R.layout.dialog_log_viewer, null);
		final TextView contentTextView = dialogView.findViewById(R.id.log_text_view);
		final ScrollView scrollView = dialogView.findViewById(R.id.log_scroll_view);
		final Button copyButton = dialogView.findViewById(R.id.btn_copy_log);
		final LinearProgressIndicator progressIndicator = dialogView.findViewById(R.id.log_progress_indicator);
		final TextView statusLabel = dialogView.findViewById(R.id.log_status_label);
		final TextView statusDetail = dialogView.findViewById(R.id.log_status_detail);
		final StringBuilder logBuilder = new StringBuilder();

		contentTextView.setMovementMethod(ScrollingMovementMethod.getInstance());
		contentTextView.setText("");
		statusLabel.setText("服务器状态");
		statusDetail.setText("正在读取系统资源与防火墙信息");
		progressIndicator.setVisibility(View.VISIBLE);

		final AlertDialog infoDialog = new MaterialAlertDialogBuilder(this).setTitle("服务器状态总览").setView(dialogView)
			.setPositiveButton("刷新", null).setNegativeButton("关闭", null).create();

		infoDialog.setOnShowListener(dialog -> {
			Button refreshButton = infoDialog.getButton(AlertDialog.BUTTON_POSITIVE);
			refreshButton.setOnClickListener(v -> {
				contentTextView.setText("");
				logBuilder.setLength(0);
				loadServerInfo(contentTextView, scrollView, logBuilder, copyButton, progressIndicator,
					statusLabel, statusDetail);
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

		loadServerInfo(contentTextView, scrollView, logBuilder, copyButton, progressIndicator,
			statusLabel, statusDetail);
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
						summary.append(" - ").append(mountPoint).append(": ").append(used).append(" / ").append(size)
							.append(" (").append(usePercent).append(")\n");
						foundDisks = true;
					}
				}
			}
			if (!foundDisks) {
				summary.append(" - 未能获取到硬盘信息。\n");
			}
		} catch (Exception e) {
			summary.append(" - 解析硬盘信息时出错。\n");
		}

		summary.append("● 检测到防火墙: ").append("none".equals(firewallType) ? "无" : firewallType).append("\n");

		summary.append("======================================\n");
		return summary.toString();
	}

	private void loadServerInfo(TextView textView, ScrollView scrollView, StringBuilder logBuilder, Button copyButton,
		LinearProgressIndicator progressIndicator, TextView statusLabel, TextView statusDetail) {
		progressIndicator.setVisibility(View.VISIBLE);
		statusLabel.setText("正在加载");
		statusLabel.setTextColor(getColor(R.color.brand_primary));
		statusDetail.setText("正在读取系统资源与防火墙信息");
		if (!settingsManager.isConfigured()) {
			runOnUiThread(() -> {
				appendLog(textView, scrollView, logBuilder, "SSH未配置，无法加载信息。", "error");
				progressIndicator.setVisibility(View.GONE);
				statusLabel.setText("无法加载");
				statusLabel.setTextColor(getColor(R.color.status_stopped));
				statusDetail.setText("请先完成 SSH 连接配置");
			});
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
					progressIndicator.setVisibility(View.GONE);
					statusLabel.setText("加载完成");
					statusLabel.setTextColor(getColor(R.color.status_running));
					statusDetail.setText("系统资源与网络状态已更新");
					scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
				});

			} catch (IOException e) {
				runOnUiThread(() -> {
					textView.setText("");
					logBuilder.setLength(0);
					appendLog(textView, scrollView, logBuilder, "加载服务器信息失败:\n" + e.getMessage(), "error");
					copyButton.setEnabled(false);
					progressIndicator.setVisibility(View.GONE);
					statusLabel.setText("加载失败");
					statusLabel.setTextColor(getColor(R.color.status_stopped));
					statusDetail.setText("连接或远程命令执行异常");
				});
			}
		});
	}

	private void appendLog(TextView textView, ScrollView scrollView, StringBuilder logBuilder, String message,
						   String type) {
		runOnUiThread(() -> {
			int color;
			switch (type) {
				case "success" :
					color = getColor(R.color.status_running);
					break;
				case "error" :
					color = getColor(R.color.status_stopped);
					break;
				case "info" :
				default :
					color = getColor(R.color.app_on_surface);
					break;
			}
			SpannableString formattedMessage = new SpannableString(message + "\n");
			formattedMessage.setSpan(new ForegroundColorSpan(color), 0, formattedMessage.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			logBuilder.append(message).append("\n");
			textView.append(formattedMessage);

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
		runOnUiThread(this::showSyncIndicator);
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
					this.logUnitsByDisplay = buildLogUnitMap(rawFileNames);
				Map<String, Set<String>> runningPortMap = parseServiceIdsToPortMap(rawRunningIds);
				this.runningPortList = formatPortMapToList(runningPortMap);
				Set<String> runningIdSet = new HashSet<>(!rawRunningIds.trim().isEmpty() ? Arrays.asList(rawRunningIds.trim().split("\\s+")) : Collections.emptySet());
				Set<String> enabledIdSet = new HashSet<>(!rawEnabledIds.trim().isEmpty() ? Arrays.asList(rawEnabledIds.trim().split("\\s+")) : Collections.emptySet());
				enabledIdSet.removeAll(runningIdSet);
				String errorIds = String.join(" ", enabledIdSet);
				Map<String, Set<String>> errorPortMap = parseServiceIdsToPortMap(errorIds);
				this.errorPortList = formatPortMapToList(errorPortMap);
					this.pendingPortList = PendingChangesStore.getDisplayKeys(this);

				runOnUiThread(() -> {
					hideSyncIndicator();
					isFirstLoad = false;
					updateServerStatusUI(cpuUsage, memUsage, cpuTemp, dfOutput, lsblkOutput, cpuModel, memoryDetails, coreCount);
						updateFrpOverviewUI(totalPortMap.size(), runningPortMap.size(), pendingPortList.size(), errorPortMap.size());
				});

			} catch (Exception e) {
				final String finalCurrentStep = currentStep;
				runOnUiThread(() -> {
					hideSyncIndicator();
					isFirstLoad = false;
					refreshHandler.removeCallbacks(refreshRunnable);
					showErrorUI("刷新失败，请查看诊断报告");
					Log.e("MainActivity", "刷新失败，步骤: " + finalCurrentStep, e);
				});
				e.printStackTrace();
			}
		});
	}

	private void showDebugDialog(String title, String message) {
		if (isFinishing() || isDestroyed()) {
			return;
		}

		View content = LayoutInflater.from(this).inflate(R.layout.dialog_log_viewer, null);
		TextView textView = content.findViewById(R.id.log_text_view);
		TextView statusLabel = content.findViewById(R.id.log_status_label);
		TextView statusDetail = content.findViewById(R.id.log_status_detail);
		Button copyButton = content.findViewById(R.id.btn_copy_log);
		textView.setText(SensitiveDataRedactor.redact(message));
		statusLabel.setText("诊断信息");
		statusLabel.setTextColor(getColor(R.color.status_stopped));
		statusDetail.setText("刷新流程未完成，可复制详情用于排查");
		copyButton.setVisibility(View.GONE);

		new MaterialAlertDialogBuilder(this)
			.setTitle(title)
			.setView(content)
			.setPositiveButton("复制并关闭", (dialog, which) -> {
			      ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			      ClipData clip = ClipData.newPlainText("Server Diag Log", SensitiveDataRedactor.redact(message));
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

		animateMetricText(tvCpuUsage, lastCpuValue, cpu, "%");
		animateMetricText(tvMemoryUsage, lastMemoryValue, mem, "%");
		animateMetricText(tvCpuTemp, lastTemperatureValue, temp, "°C");
		lastCpuValue = cpu;
		lastMemoryValue = mem;
		lastTemperatureValue = temp;
		tvDashboardState.setText("SYNCED");
		tvDashboardState.setTextColor(getColor(R.color.status_running));

		updateDiskStatusCard(dfOutput, lsblkOutput);
	}

	private void animateMetricText(TextView view, int previousValue, int nextValue, String suffix) {
		ValueAnimator priorAnimator = metricAnimators.remove(view);
		if (priorAnimator != null) priorAnimator.cancel();
		if (!ValueAnimator.areAnimatorsEnabled() || previousValue == nextValue) {
			setRetroMetricText(view, nextValue, suffix);
			return;
		}
		int startValue = previousValue < 0 ? 0 : previousValue;
		ValueAnimator animator = ValueAnimator.ofInt(startValue, nextValue);
		animator.setDuration(previousValue < 0 ? 720L : 520L);
		animator.setInterpolator(new DecelerateInterpolator(1.8f));
		animator.addUpdateListener(valueAnimator ->
			setRetroMetricText(view, (int) valueAnimator.getAnimatedValue(), suffix));
		animator.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				metricAnimators.remove(view);
			}
		});
		metricAnimators.put(view, animator);
		animator.start();
	}

	private void setRetroMetricText(TextView view, int value, String suffix) {
		String displayText = value + suffix;
		SpannableString display = new SpannableString(displayText);
		if (!suffix.isEmpty()) {
			int unitStart = displayText.length() - suffix.length();
			display.setSpan(new TypefaceSpan("sans-serif-monospace"), unitStart, displayText.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			display.setSpan(new StyleSpan(Typeface.BOLD), unitStart, displayText.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			display.setSpan(new RelativeSizeSpan(0.62f), unitStart, displayText.length(),
				Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		view.setText(display);
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
			setRetroMetricText(tvUsagePercent, disk.usePercent, "%");
			progressDisk.setProgressCompat(disk.usePercent, true);
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
		animateMetricText(tvStatTotalValue, lastTotalValue, total, "");
		animateMetricText(tvStatRunningValue, lastRunningValue, running, "");
		animateMetricText(tvStatPendingValue, lastPendingValue, pending, "");
		animateMetricText(tvStatErrorValue, lastErrorValue, error, "");
		lastTotalValue = total;
		lastRunningValue = running;
		lastPendingValue = pending;
		lastErrorValue = error;
		tvStatRunningLabel.setText("运行中");
		tvStatErrorLabel.setText("异常");
	}

	private void showErrorUI(String message) {
		progressCpu.setIndeterminate(true);
		progressMemory.setIndeterminate(true);
		progressCpuTemp.setIndeterminate(true);

		tvCpuUsage.setText("--");
		tvMemoryUsage.setText("--");
		tvCpuTemp.setText("--");
		tvCpuLabel.setText(message);
		tvDashboardState.setText("OFFLINE");
		tvDashboardState.setTextColor(getColor(R.color.status_stopped));

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
				openFrpManager();
			});
		}

		builder.create().show();
	}

	private void showLogViewerDialog(String port) {
		List<String> unitNames = unitNamesFromDisplay(port);
		if (unitNames.isEmpty()) {
			Toast.makeText(this, "无效的端口日志目标", Toast.LENGTH_SHORT).show();
			return;
		}
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
		LayoutInflater inflater = this.getLayoutInflater();
		View dialogView = inflater.inflate(R.layout.dialog_log_viewer, null);
		builder.setTitle("运行状态: " + port).setView(dialogView);
		ScrollView scrollView = dialogView.findViewById(R.id.log_scroll_view);
		final TextView logContent = dialogView.findViewById(R.id.log_text_view);
		final TextView statusLabel = dialogView.findViewById(R.id.log_status_label);
		final TextView statusDetail = dialogView.findViewById(R.id.log_status_detail);
		final LinearProgressIndicator progressIndicator = dialogView.findViewById(R.id.log_progress_indicator);
		Button copyButton = dialogView.findViewById(R.id.btn_copy_log);
		statusLabel.setText("正在读取运行状态");
		statusDetail.setText("后续内容会在后台自动更新");
		progressIndicator.setVisibility(View.VISIBLE);
		logContent.setText("正在连接服务器...");
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
						String unitArguments = unitNames.stream().map(unit -> "'" + unit + "'")
							.collect(java.util.stream.Collectors.joining(" "));
						String journalArguments = unitNames.stream().map(unit -> "-u '" + unit + "'")
							.collect(java.util.stream.Collectors.joining(" "));
						String logCommand = "echo '---SYSTEMD STATUS---' && systemctl status " + unitArguments
							+ " --no-pager; echo '\n---JOURNAL LOGS (last 20 lines)---'; journalctl "
							+ journalArguments + " --no-pager -n 20";
					try {
						String rawLogs = sshManager.executeCommand(logCommand);
						SpannableString formattedLogs = formatLogsForDisplay(rawLogs);
						runOnUiThread(() -> {
							logContent.setText(formattedLogs);
							progressIndicator.setVisibility(View.GONE);
							statusLabel.setText("实时状态");
							statusLabel.setTextColor(getColor(R.color.status_running));
							statusDetail.setText("每 3 秒在后台更新一次，不影响其他操作");
							scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
						});
					} catch (IOException e) {
						runOnUiThread(() -> {
							logContent.setText("加载运行信息失败:\n" + e.getMessage());
							progressIndicator.setVisibility(View.GONE);
							statusLabel.setText("更新失败");
							statusLabel.setTextColor(getColor(R.color.status_stopped));
							statusDetail.setText("窗口保持打开，稍后会自动重试");
						});
					}
				});

				logRefreshHandler.postDelayed(this, 3000);
			}
		};

		logDialog.show();
		logRefreshHandler.post(logRefreshRunnable);
	}

	private List<String> unitNamesFromDisplay(String display) {
		List<String> knownUnits = logUnitsByDisplay.get(display);
		if (knownUnits != null && !knownUnits.isEmpty()) return new ArrayList<>(knownUnits);
		Matcher matcher = Pattern.compile("^(\\d+) \\((TCP|UDP|TCP/UDP)\\)$").matcher(display == null ? "" : display);
		if (!matcher.matches()) return Collections.emptyList();
		String port = matcher.group(1);
		String protocols = matcher.group(2);
		List<String> units = new ArrayList<>();
		if (protocols.contains("TCP")) units.add("frpc@" + port + "_tcp.service");
		if (protocols.contains("UDP")) units.add("frpc@" + port + "_udp.service");
		return units;
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

	private void showSyncIndicator() {
		if (syncProgress == null) return;
		syncProgress.animate().cancel();
		syncProgress.setAlpha(1f);
		syncProgress.setVisibility(View.VISIBLE);
		if (tvDashboardState != null) {
			tvDashboardState.setText("SYNCING");
			tvDashboardState.setTextColor(getColor(R.color.status_pending));
		}
	}

	private void hideSyncIndicator() {
		if (syncProgress == null || syncProgress.getVisibility() != View.VISIBLE) return;
		syncProgress.animate().alpha(0f).setDuration(220L).withEndAction(() -> {
			syncProgress.setVisibility(View.GONE);
			syncProgress.setAlpha(1f);
		}).start();
	}

	private void initializeSshManager() {
		if (settingsManager.isConfigured()) {
			if (settingsManager.getUseKeyAuth()) {
				String key = settingsManager.getPrivateKey();
				sshManager = new SshManager(settingsManager.getHost(), settingsManager.getPort(),
											settingsManager.getUsername(), key, settingsManager.getPassphrase(),
											settingsManager.getHostKeyFingerprint(settingsManager.getHost(), settingsManager.getPort()));
			} else {
				sshManager = SshManager.password(settingsManager.getHost(), settingsManager.getPort(),
											settingsManager.getUsername(), settingsManager.getPassword(),
											settingsManager.getHostKeyFingerprint(settingsManager.getHost(), settingsManager.getPort()));
			}
		} else {
			sshManager = new SshManager(null, 0, null, null);
		}
	}

	private void showTrustHostKeyDialog(String host, int port, SshManager.HostKeyVerificationException exception,
		Runnable onTrusted) {
		String prior = exception.getExpectedFingerprint() == null ? "（首次连接）" : exception.getExpectedFingerprint();
		new MaterialAlertDialogBuilder(this)
			.setTitle(exception.getExpectedFingerprint() == null ? "确认 SSH 主机指纹" : "SSH 主机密钥已变更")
			.setMessage("主机: " + host + ":" + port + "\n\n已保存指纹: " + prior
				+ "\n新指纹: " + exception.getFingerprint()
				+ "\n\n请仅在通过独立可信渠道核对该 SHA256 指纹后继续。")
			.setNegativeButton("拒绝", null)
			.setPositiveButton("我已核对并信任", (dialog, which) -> {
				settingsManager.trustHostKeyFingerprint(host, port, exception.getFingerprint());
				initializeSshManager();
				if (onTrusted != null) onTrusted.run();
			})
			.show();
	}

	private void executeCleanup() {
		final AlertDialog dialog = new MaterialAlertDialogBuilder(this).setView(R.layout.dialog_loading)
			.setCancelable(false).show();

		executor.execute(() -> {
			try {
				String scriptContent = readAssetFile("scripts/cleanup.sh");
				if (scriptContent == null)
					throw new IOException("无法读取清理脚本");

					String result = sshManager.executeCommand(RemoteScriptCommand.bash(scriptContent), 90);

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
