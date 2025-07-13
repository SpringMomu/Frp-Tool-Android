package io.momu.frpmanager;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.text.method.*;
import android.util.*;
import android.view.*;
import android.view.animation.*;
import android.view.inputmethod.*;
import android.widget.*;
import androidx.activity.*;
import androidx.annotation.*;
import androidx.appcompat.app.*;
import androidx.appcompat.widget.*;
import androidx.core.content.*;
import androidx.recyclerview.widget.*;
import com.google.android.material.chip.*;
import com.google.android.material.dialog.*;
import com.google.android.material.floatingactionbutton.*;
import com.google.android.material.materialswitch.*;
import com.google.android.material.textfield.*;
import io.momu.frpmanager.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;

import android.content.ClipboardManager;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import io.momu.frpmanager.R;

public class FrpManagerActivity extends AppCompatActivity implements FrpProfileAdapter.OnProfileClickListener {

	private static final String TAG = "FrpManagerActivity";

	private RecyclerView recyclerView;
	private FrpProfileAdapter adapter;
	private FloatingActionButton fabAddPort;
	private LinearLayout batchActionsLayout;
	private Button btnCancelSelection, btnConfirmDelete;
	private TextView emptyViewText;

	private Chip chipFilterStatus;
	private ChipGroup chipGroupStatusFilter;
	private ChipGroup chipGroupProtocolFilter;
	private ChipGroup chipGroupFirewallFilter;

	private RelativeLayout filterHeaderLayout;
	private LinearLayout filtersContainer;
	private ImageView ivToggleFilters;
	private boolean isFiltersVisible = false;

	private Button btnBatchStart, btnBatchStop;
	private Button btnBatchFirewallAllow, btnBatchFirewallBlock;

	private boolean isInSelectionMode = false;

	private SshManager sshManager;
	private SshSettingsManager settingsManager;
	private FirewallManager firewallManager;
	private ExecutorService executor = Executors.newSingleThreadExecutor();

	private List<FrpProfile> allProfiles = new ArrayList<>();
	private List<FrpProfile> pendingChangesProfiles = new ArrayList<>();
	private MenuItem applyChangesMenuItem;

	private Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
	private Runnable autoRefreshRunnable;
	private Dialog loadingDialog;

	private final Set<String> lockedProfiles = Collections.synchronizedSet(new HashSet<>());

	private String getProfileLockKey(FrpProfile profile) {
		if (profile == null || profile.getProtocol() == null)
			return "";
		return profile.getRemotePort() + "/" + profile.getProtocol().toLowerCase();
	}

	private String getProfileLockKey(int port, String protocol) {
		if (protocol == null)
			return "";
		return port + "/" + protocol.toLowerCase();
	}
	
	private String currentSearchQuery = "";
	private String currentStatusFilter = "all";
	private String currentProtocolFilter = "all";
	private String currentFirewallFilter = "all";

	private MenuItem searchMenuItem;
	private MenuItem refreshMenuItem;
	private MenuItem batchCreateMenuItem;
	private MenuItem selectAllMenuItem;
	private MenuItem invertSelectionMenuItem;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_frp_manager);

		settingsManager = new SshSettingsManager(this);
		if (settingsManager.isConfigured()) {
			if (settingsManager.getUseKeyAuth()) {
				sshManager = new SshManager(
					settingsManager.getHost(),
					settingsManager.getPort(),
					settingsManager.getUsername(),
					settingsManager.getPrivateKey(),
					settingsManager.getPassphrase()
				);
			} else {
				sshManager = new SshManager(
					settingsManager.getHost(),
					settingsManager.getPort(),
					settingsManager.getUsername(),
					settingsManager.getPassword()
				);
			}
		} else {
			Toast.makeText(this, "错误：SSH 未配置。请返回主页进行设置。", Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		initViews();
		setupRecyclerView();
		showLoadingDialog();
		detectFirewallAndLoadProfiles();

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
				@Override
				public void handleOnBackPressed() {
					if (!currentSearchQuery.isEmpty()) {
						filterByText("");
						Toast.makeText(FrpManagerActivity.this, "已清空搜索筛选", Toast.LENGTH_SHORT).show();
					} else if (isInSelectionMode) {
						exitSelectionMode();
					} else {
						if (!pendingChangesProfiles.isEmpty()) {
							showExitConfirmationDialog(new ArrayList<>(pendingChangesProfiles));
						} else {
							finish();
						}
					}
				}
			});

		autoRefreshRunnable = new Runnable() {
			@Override
			public void run() {
				if (!isInSelectionMode && lockedProfiles.isEmpty()) {
					loadFrpProfilesFromServer();
				}
				autoRefreshHandler.postDelayed(this, 5000);
			}
		};
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		autoRefreshHandler.removeCallbacks(autoRefreshRunnable);
		executor.execute(() -> {
			try {
				if (sshManager != null) {
					sshManager.disconnect();
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		});
	}

	private void showLoadingDialog() {
		if (loadingDialog == null) {
			MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
			LayoutInflater inflater = LayoutInflater.from(this);
			View loadingView = inflater.inflate(R.layout.dialog_loading, null);
			builder.setView(loadingView);
			builder.setCancelable(false);
			loadingDialog = builder.create();
		}

		if (!isFinishing() && !loadingDialog.isShowing()) {
			loadingDialog.show();
		}
	}

	private void hideLoadingDialog() {
		if (loadingDialog != null && loadingDialog.isShowing()) {
			loadingDialog.dismiss();
		}
	}

	private void detectFirewallAndLoadProfiles() {
		executor.execute(() -> {
			try {
				String detectCommand = "if systemctl is-active --quiet firewalld; then echo \"firewalld\"; "
					+ "elif command -v ufw >/dev/null && ufw status | grep -q \"Status: active\"; then echo \"ufw\"; "
					+ "elif command -v iptables >/dev/null; then echo \"iptables\"; " + "else echo \"none\"; fi";

				String firewallType = sshManager.executeCommand(detectCommand, 20).trim();
				this.firewallManager = FirewallManagerFactory.getManager(firewallType);

				runOnUiThread(() -> {
					if (this.firewallManager != null) {
						Toast.makeText(this, "已获取防火墙类型: " + this.firewallManager.getType(), Toast.LENGTH_SHORT).show();
					} else {
						Toast.makeText(this, "警告: 未在服务器上检测到支持的防火墙", Toast.LENGTH_LONG).show();
					}
					loadFrpProfilesFromServer();
				});

			} catch (IOException e) {
				runOnUiThread(() -> {
					Toast.makeText(this, "防火墙检索失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
					loadFrpProfilesFromServer();
				});
			}
		});
	}

	private void initViews() {
		Toolbar toolbar = findViewById(R.id.toolbar_manager);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setDisplayShowHomeEnabled(true);
		}

		recyclerView = findViewById(R.id.recycler_view_ports);
		fabAddPort = findViewById(R.id.fab_add_port);
		batchActionsLayout = findViewById(R.id.batch_actions_layout);
		btnCancelSelection = findViewById(R.id.btn_cancel_selection);
		btnConfirmDelete = findViewById(R.id.btn_confirm_delete);
		btnBatchStart = findViewById(R.id.btn_batch_start);
		btnBatchStop = findViewById(R.id.btn_batch_stop);
		emptyViewText = findViewById(R.id.empty_view_text);
		btnBatchFirewallAllow = findViewById(R.id.btn_batch_firewall_allow);
		btnBatchFirewallBlock = findViewById(R.id.btn_batch_firewall_block);

		chipFilterStatus = findViewById(R.id.chip_filter_status);
		chipFilterStatus.setOnCloseIconClickListener(v -> {
			filterByText("");
		});

		filterHeaderLayout = findViewById(R.id.filter_header_layout);
		filtersContainer = findViewById(R.id.filters_container);
		ivToggleFilters = findViewById(R.id.iv_toggle_filters);

		filterHeaderLayout.setOnClickListener(v -> {
			isFiltersVisible = !isFiltersVisible;
			toggleFilterVisibility(isFiltersVisible);
		});

		chipGroupStatusFilter = findViewById(R.id.chip_group_status_filter);
		chipGroupStatusFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
			if (checkedIds.isEmpty()) {
				group.check(R.id.chip_filter_all);
				return;
			}
			int checkedId = checkedIds.get(0);
			if (checkedId == R.id.chip_filter_running) {
				currentStatusFilter = "running";
			} else if (checkedId == R.id.chip_filter_stopped) {
				currentStatusFilter = "stopped";
			} else {
				currentStatusFilter = "all";
			}
			applyFilters();
		});

		chipGroupProtocolFilter = findViewById(R.id.chip_group_protocol_filter);
		if (chipGroupProtocolFilter != null) {
			chipGroupProtocolFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
				if (checkedIds.isEmpty()) {
					group.check(R.id.chip_filter_protocol_all);
					return;
				}
				int checkedId = checkedIds.get(0);
				if (checkedId == R.id.chip_filter_protocol_tcp) {
					currentProtocolFilter = "tcp";
				} else if (checkedId == R.id.chip_filter_protocol_udp) {
					currentProtocolFilter = "udp";
				} else {
					currentProtocolFilter = "all";
				}
				applyFilters();
			});
		}

		chipGroupFirewallFilter = findViewById(R.id.chip_group_firewall_filter);
		if (chipGroupFirewallFilter != null) {
			chipGroupFirewallFilter.setOnCheckedStateChangeListener((group, checkedIds) -> {
				if (checkedIds.isEmpty()) {
					group.check(R.id.chip_filter_firewall_all);
					return;
				}
				int checkedId = checkedIds.get(0);
				if (checkedId == R.id.chip_filter_firewall_allowed) {
					currentFirewallFilter = "allowed";
				} else if (checkedId == R.id.chip_filter_firewall_blocked) {
					currentFirewallFilter = "blocked";
				} else {
					currentFirewallFilter = "all";
				}
				applyFilters();
			});
		}

		fabAddPort.setOnClickListener(view -> showAddOrEditDialog(null));
		btnCancelSelection.setOnClickListener(v -> exitSelectionMode());

		btnConfirmDelete.setOnClickListener(v -> {
			List<FrpProfile> selectedItems = adapter.getSelectedItems();
			confirmAndExecuteBatchOperation("delete", selectedItems);
		});

		btnBatchStart.setOnClickListener(v -> {
			List<FrpProfile> selectedItems = adapter.getSelectedItems();
			List<FrpProfile> itemsToStart = selectedItems.stream()
				.filter(p -> !"运行中".equals(p.getStatus()) && !p.isModified()).collect(Collectors.toList());
			if (!itemsToStart.isEmpty()) {
				confirmAndExecuteBatchOperation("start", itemsToStart);
			} else {
				Toast.makeText(FrpManagerActivity.this, "没有可启动的选中项（请先应用更改）", Toast.LENGTH_SHORT).show();
			}
		});

		btnBatchStop.setOnClickListener(v -> {
			List<FrpProfile> selectedItems = adapter.getSelectedItems();
			List<FrpProfile> itemsToStop = selectedItems.stream()
				.filter(p -> "运行中".equals(p.getStatus()) && !p.isModified()).collect(Collectors.toList());
			if (!itemsToStop.isEmpty()) {
				confirmAndExecuteBatchOperation("stop", itemsToStop);
			} else {
				Toast.makeText(FrpManagerActivity.this, "没有可暂停的选中项", Toast.LENGTH_SHORT).show();
			}
		});

		if (btnBatchFirewallAllow != null) {
			btnBatchFirewallAllow.setOnClickListener(v -> {
				List<FrpProfile> selectedItems = adapter.getSelectedItems();
				if (firewallManager != null && settingsManager.isFirewallManaged()) {
					confirmAndExecuteBatchFirewallOperation("allow", selectedItems);
				} else {
					Toast.makeText(this, "防火墙管理未启用或不支持", Toast.LENGTH_SHORT).show();
				}
			});
		}

		if (btnBatchFirewallBlock != null) {
			btnBatchFirewallBlock.setOnClickListener(v -> {
				List<FrpProfile> selectedItems = adapter.getSelectedItems();
				if (firewallManager != null && settingsManager.isFirewallManaged()) {
					confirmAndExecuteBatchFirewallOperation("block", selectedItems);
				} else {
					Toast.makeText(this, "防火墙管理未启用或不支持", Toast.LENGTH_SHORT).show();
				}
			});
		}
	}

	private void toggleFilterVisibility(final boolean show) {
		float startRotation = ivToggleFilters.getRotation();
		float endRotation = show ? 180f : 0f;
		ivToggleFilters.animate().rotation(endRotation).setDuration(300).start();

		if (show) {
			filtersContainer.setVisibility(View.VISIBLE);
			filtersContainer.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			int targetHeight = filtersContainer.getMeasuredHeight();

			filtersContainer.getLayoutParams().height = 0;
			ValueAnimator animator = ValueAnimator.ofInt(0, targetHeight);
			animator.addUpdateListener(animation -> {
				filtersContainer.getLayoutParams().height = (int) animation.getAnimatedValue();
				filtersContainer.requestLayout();
			});
			animator.setInterpolator(new DecelerateInterpolator());
			animator.setDuration(300);
			animator.start();
		} else {
			int initialHeight = filtersContainer.getHeight();
			ValueAnimator animator = ValueAnimator.ofInt(initialHeight, 0);
			animator.addUpdateListener(animation -> {
				filtersContainer.getLayoutParams().height = (int) animation.getAnimatedValue();
				filtersContainer.requestLayout();
				if ((int) animation.getAnimatedValue() == 0) {
					filtersContainer.setVisibility(View.GONE);
				}
			});
			animator.setInterpolator(new DecelerateInterpolator());
			animator.setDuration(300);
			animator.start();
		}
	}

	private void setupRecyclerView() {
		adapter = new FrpProfileAdapter(this);
		recyclerView.setLayoutManager(new LinearLayoutManager(this));
		recyclerView.setAdapter(adapter);

		recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
				@Override
				public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
					super.onScrolled(recyclerView, dx, dy);
					if (dy > 0 && fabAddPort.isShown()) {
						fabAddPort.hide();
					} else if (dy < 0 && !fabAddPort.isShown()) {
						fabAddPort.show();
					}
				}
			});
	}

	private void checkEmptyState() {
		if (adapter.getItemCount() == 0) {
			recyclerView.setVisibility(View.GONE);
			emptyViewText.setVisibility(View.VISIBLE);
			boolean isAnyFilterActive = (currentSearchQuery != null && !currentSearchQuery.isEmpty())
				|| !currentStatusFilter.equals("all") || !currentProtocolFilter.equals("all")
				|| !currentFirewallFilter.equals("all");

			if (isAnyFilterActive) {
				emptyViewText.setText("无匹配结果");
			} else {
				emptyViewText.setText("列表为空，请添加端口映射");
			}
		} else {
			recyclerView.setVisibility(View.VISIBLE);
			emptyViewText.setVisibility(View.GONE);
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.manager_menu, menu);

		applyChangesMenuItem = menu.findItem(R.id.action_apply_changes);
		searchMenuItem = menu.findItem(R.id.action_search);
		refreshMenuItem = menu.findItem(R.id.action_refresh);
		batchCreateMenuItem = menu.findItem(R.id.action_batch_create);
		selectAllMenuItem = menu.findItem(R.id.action_select_all);
		invertSelectionMenuItem = menu.findItem(R.id.action_invert_selection);

		updatePendingChangesMenu();

		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		boolean isVisible = !isInSelectionMode;
		boolean isFirewallReady = firewallManager != null && settingsManager.isFirewallManaged();

		applyChangesMenuItem.setVisible(isVisible && !pendingChangesProfiles.isEmpty());
		searchMenuItem.setVisible(true);
		refreshMenuItem.setVisible(isVisible);
		batchCreateMenuItem.setVisible(isVisible);
		menu.findItem(R.id.action_range_delete).setVisible(isVisible);
		menu.findItem(R.id.action_batch_delete).setVisible(isVisible);
		menu.findItem(R.id.action_batch_start).setVisible(isVisible);
		menu.findItem(R.id.action_batch_stop).setVisible(isVisible);

		MenuItem batchFirewallAllow = menu.findItem(R.id.action_batch_firewall_allow);
		if (batchFirewallAllow != null)
			batchFirewallAllow.setVisible(isVisible && isFirewallReady);
		MenuItem batchFirewallBlock = menu.findItem(R.id.action_batch_firewall_block);
		if (batchFirewallBlock != null)
			batchFirewallBlock.setVisible(isVisible && isFirewallReady);

		selectAllMenuItem.setVisible(!isVisible);
		invertSelectionMenuItem.setVisible(!isVisible);
		return super.onPrepareOptionsMenu(menu);
	}

	private void updatePendingChangesMenu() {
		if (applyChangesMenuItem == null)
			return;
		int count = pendingChangesProfiles.size();
		if (count > 0) {
			applyChangesMenuItem.setTitle("应用更改 (" + count + ")");
			applyChangesMenuItem.setVisible(!isInSelectionMode);
		} else {
			applyChangesMenuItem.setTitle("应用更改");
			applyChangesMenuItem.setVisible(false);
		}
		invalidateOptionsMenu();
	}

	@Override
	public boolean onOptionsItemSelected(@NonNull MenuItem item) {
		int itemId = item.getItemId();

		if (itemId == R.id.action_search) {
			showSearchDialog();
			return true;
		}

		if (itemId == R.id.action_select_all) {
			if (adapter != null) {
				adapter.selectAll();
				onSelectionChanged(adapter.getSelectedItemsCount());
			}
			return true;
		} else if (itemId == R.id.action_invert_selection) {
			if (adapter != null) {
				adapter.invertSelection();
				onSelectionChanged(adapter.getSelectedItemsCount());
			}
			return true;
		} else if (itemId == android.R.id.home) {
			getOnBackPressedDispatcher().onBackPressed();
			return true;
		} else if (itemId == R.id.action_refresh) {
			showLoadingDialog();
			loadFrpProfilesFromServer();
			return true;
		} else if (itemId == R.id.action_batch_create) {
			showBatchCreateDialog();
			return true;
		} else if (itemId == R.id.action_range_delete) {
			showBatchOperationDialog("delete");
			return true;
		} else if (itemId == R.id.action_batch_delete) {
			if (!isInSelectionMode)
				enterSelectionMode();
			return true;
		} else if (itemId == R.id.action_apply_changes) {
			applyAllPendingChanges();
			return true;
		} else if (itemId == R.id.action_batch_start) {
			showBatchOperationDialog("start");
			return true;
		} else if (itemId == R.id.action_batch_stop) {
			showBatchOperationDialog("stop");
			return true;
		} else if (itemId == R.id.action_batch_firewall_allow) {
			showBatchFirewallOperationDialog("allow");
			return true;
		} else if (itemId == R.id.action_batch_firewall_block) {
			showBatchFirewallOperationDialog("block");
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	private void showSearchDialog() {
		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
		builder.setTitle("搜索");

		LayoutInflater inflater = this.getLayoutInflater();
		View dialogView = inflater.inflate(R.layout.dialog_material_input, null);
		final TextInputEditText input = dialogView.findViewById(R.id.text_input_edit_text);
		input.setHint("输入端口、IP或标签");
		input.setText(currentSearchQuery);
		input.requestFocus();

		builder.setView(dialogView);

		builder.setPositiveButton("搜索", (dialog, which) -> {
			String query = input.getText().toString();
			filterByText(query);
			InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
		});

		builder.setNegativeButton("取消", (dialog, which) -> {
			InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
			dialog.cancel();
		});

		builder.setNeutralButton("清空文本", (dialog, which) -> {
			filterByText("");
			InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
		});

		AlertDialog dialog = builder.create();

		if (dialog.getWindow() != null) {
			dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
		}
		dialog.show();
	}

	private void updateFilterChip() {
		if (currentSearchQuery != null && !currentSearchQuery.isEmpty()) {
			chipFilterStatus.setText("文本筛选: \"" + currentSearchQuery + "\"");
			chipFilterStatus.setVisibility(View.VISIBLE);
		} else {
			chipFilterStatus.setVisibility(View.GONE);
		}
	}

	@Override
	public void onProfileClick(FrpProfile profile) {
		if (lockedProfiles.contains(getProfileLockKey(profile))) {
			Toast.makeText(this, "请等待当前操作完成...", Toast.LENGTH_SHORT).show();
			return;
		}

		if (profile.isModified()) {
			new MaterialAlertDialogBuilder(this).setTitle("配置已修改 (待应用)")
				.setMessage("端口 " + profile.getRemotePort() + " 的配置已在本地修改，需要先 '应用更改' 才能执行其他操作。")
				.setPositiveButton("编辑", (d, w) -> showAddOrEditDialog(profile)).setNegativeButton("取消", null)
				.show();
			return;
		}

		List<String> itemsList = new ArrayList<>();
		switch (profile.getStatus()) {
			case "运行中" :
				itemsList.add("暂停此项映射");
				itemsList.add("重启此项服务");
				break;
			case "已停止" :
			case "启动失败" :
				itemsList.add("启动此项映射");
				break;
			case "已禁用" :
				itemsList.add("启用并启动");
				break;
			default :
				Toast.makeText(this, "状态未知或正在操作中", Toast.LENGTH_SHORT).show();
				return;
		}
		itemsList.add("编辑配置");
		itemsList.add("查看原配置文件");
		itemsList.add("删除此项");
		itemsList.add("防火墙操作");
		itemsList.add("取消");

		final CharSequence[] items = itemsList.toArray(new CharSequence[0]);
		new MaterialAlertDialogBuilder(this)
			.setTitle("操作端口: " + profile.getRemotePort() + "/" + profile.getProtocol().toUpperCase())
			.setItems(items, (dialog, itemIndex) -> {
			String selectedAction = items[itemIndex].toString();
			switch (selectedAction) {
				case "暂停此项映射" :
					executeBatchOperationWithLogDialog("stop", Collections.singletonList(profile));
					break;
				case "重启此项服务" :
					executeBatchOperationWithLogDialog("restart", Collections.singletonList(profile));
					break;
				case "启动此项映射" :
				case "启用并启动" :
					executeBatchOperationWithLogDialog("start", Collections.singletonList(profile));
					break;
				case "编辑配置" :
					showAddOrEditDialog(profile);
					break;
				case "查看原配置文件" :
					showConfigFileDialog(profile);
					break;
				case "删除此项" :
					confirmAndExecuteBatchOperation("delete", Collections.singletonList(profile));
					break;
				case "防火墙操作" :
					showFirewallOperationDialog(profile);
					break;
			}
		}).show();
	}

	@Override
	public void onProfileLongClick(FrpProfile profile) {
		if (!isInSelectionMode) {
			enterSelectionMode();
		}
	}

	@Override
	public void onSelectionChanged(int selectedCount) {
		if (isInSelectionMode) {
			if (getSupportActionBar() != null) {
				getSupportActionBar().setTitle("已选择 " + selectedCount + " 项");
			}
			btnConfirmDelete.setText("删除已选 (" + selectedCount + ")");
			btnConfirmDelete.setEnabled(selectedCount > 0);

			List<FrpProfile> selectedItems = adapter.getSelectedItems();
			long startableCount = selectedItems.stream().filter(p -> !"运行中".equals(p.getStatus()) && !p.isModified())
			.count();
			long stoppableCount = selectedItems.stream().filter(p -> "运行中".equals(p.getStatus()) && !p.isModified())
			.count();

			btnBatchStart.setText("启动已选 (" + startableCount + ")");
			btnBatchStart.setEnabled(startableCount > 0);

			btnBatchStop.setText("暂停已选 (" + stoppableCount + ")");
			btnBatchStop.setEnabled(stoppableCount > 0);

			boolean isFirewallReady = firewallManager != null && settingsManager.isFirewallManaged()
				&& selectedCount > 0;
			if (btnBatchFirewallAllow != null) {
				btnBatchFirewallAllow.setText("放行已选 (" + selectedCount + ")");
				btnBatchFirewallAllow.setEnabled(isFirewallReady);
			}
			if (btnBatchFirewallBlock != null) {
				btnBatchFirewallBlock.setText("阻止已选 (" + selectedCount + ")");
				btnBatchFirewallBlock.setEnabled(isFirewallReady);
			}
		}
	}

	private void updateProfileStatusInUi(FrpProfile profile, String status) {
		for (FrpProfile p : allProfiles) {
			if (p.getRemotePort() == profile.getRemotePort()
				&& p.getProtocol().equalsIgnoreCase(profile.getProtocol())) {
				p.setStatus(status);
				break;
			}
		}
		applyFilters();
	}

	private void showErrorDialog(String title, String content) {
		new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(content).setPositiveButton("好的", null).show();
	}

	private void showAddOrEditDialog(final FrpProfile existingProfile) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View dialogView = inflater.inflate(R.layout.dialog_add_port, null);

		final TextView dialogTitle = dialogView.findViewById(R.id.dialog_title);
		final EditText serverAddrInput = dialogView.findViewById(R.id.edit_text_server_addr);
		final EditText serverPortInput = dialogView.findViewById(R.id.edit_text_server_port);
		final EditText tokenInput = dialogView.findViewById(R.id.edit_text_token);
		final EditText remotePortInput = dialogView.findViewById(R.id.edit_text_remote_port);
		final TextInputLayout remotePortLayout = dialogView.findViewById(R.id.layout_remote_port);
		final EditText localIpInput = dialogView.findViewById(R.id.edit_text_local_ip);
		final EditText localPortInput = dialogView.findViewById(R.id.edit_text_local_port);
		final EditText tagInput = dialogView.findViewById(R.id.edit_text_tag);
		final CheckBox tcpCheckbox = dialogView.findViewById(R.id.checkbox_tcp);
		final CheckBox udpCheckbox = dialogView.findViewById(R.id.checkbox_udp);
		final MaterialSwitch proxySwitch = dialogView.findViewById(R.id.switch_proxy_protocol);
		final Spinner proxyVersionSpinner = dialogView.findViewById(R.id.spinner_proxy_version);

		ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
																 new String[]{"v1", "v2"});
		spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		proxyVersionSpinner.setAdapter(spinnerAdapter);
		proxySwitch.setOnCheckedChangeListener(
			(buttonView, isChecked) -> proxyVersionSpinner.setVisibility(isChecked ? View.VISIBLE : View.GONE));

		if (existingProfile != null) {
			dialogTitle.setText("编辑端口映射");
			serverAddrInput.setText(existingProfile.getServerAddr());
			serverPortInput.setText(
				existingProfile.getServerPort() > 0 ? String.valueOf(existingProfile.getServerPort()) : "");
			tokenInput.setText(existingProfile.getToken());

			remotePortInput.setText(String.valueOf(existingProfile.getRemotePort()));
			remotePortInput.setEnabled(false);
			remotePortLayout.setHelperText("编辑模式下远程端口不可更改");
			localIpInput.setText(existingProfile.getLocalIp());
			localPortInput.setText(String.valueOf(existingProfile.getLocalPort()));
			tagInput.setText(existingProfile.getTag());

			if ("udp".equalsIgnoreCase(existingProfile.getProtocol())) {
				udpCheckbox.setChecked(true);
				tcpCheckbox.setChecked(false);
			} else {
				tcpCheckbox.setChecked(true);
				udpCheckbox.setChecked(false);
			}
			tcpCheckbox.setEnabled(false);
			udpCheckbox.setEnabled(false);

			String proxyVersion = existingProfile.getProxyProtocolVersion();
			if (proxyVersion != null && !proxyVersion.isEmpty()) {
				proxySwitch.setChecked(true);
				proxyVersionSpinner.setVisibility(View.VISIBLE);
				if ("v1".equals(proxyVersion)) {
					proxyVersionSpinner.setSelection(0);
				} else if ("v2".equals(proxyVersion)) {
					proxyVersionSpinner.setSelection(1);
				}
			} else {
				proxySwitch.setChecked(false);
				proxyVersionSpinner.setVisibility(View.GONE);
			}
		} else {
			dialogTitle.setText("添加新端口映射");
			serverAddrInput.setText(settingsManager.getFrpServerAddr());
			serverPortInput.setText(String.valueOf(settingsManager.getFrpServerPort()));
			tokenInput.setText(settingsManager.getFrpToken());

			tcpCheckbox.setChecked(true);
			udpCheckbox.setChecked(false);
			proxySwitch.setChecked(false);
			proxyVersionSpinner.setVisibility(View.GONE);
		}

		new MaterialAlertDialogBuilder(this).setView(dialogView).setPositiveButton("保存", (dialog, which) -> {
			String serverAddr = serverAddrInput.getText().toString().trim();
			if (serverAddr.isEmpty() || (!isValidIpAddress(serverAddr) && !isValidDomain(serverAddr))) {
				Toast.makeText(FrpManagerActivity.this, "FRP 服务器地址不能为空或格式不正确", Toast.LENGTH_SHORT).show();
				return;
			}
			int serverPort;
			try {
				serverPort = Integer.parseInt(serverPortInput.getText().toString());
				if (serverPort <= 0 || serverPort > 65535)
					throw new NumberFormatException();
			} catch (NumberFormatException e) {
				Toast.makeText(FrpManagerActivity.this, "FRP 服务器端口无效", Toast.LENGTH_SHORT).show();
				return;
			}
			String token = tokenInput.getText().toString().trim();

			String remotePortStr = remotePortInput.getText().toString();
			if (TextUtils.isEmpty(remotePortStr)) {
				Toast.makeText(FrpManagerActivity.this, "远程端口不能为空", Toast.LENGTH_SHORT).show();
				return;
			}
			int remotePort;
			try {
				remotePort = Integer.parseInt(remotePortStr);
				if (remotePort <= 0 || remotePort > 65535) {
					Toast.makeText(FrpManagerActivity.this, "远程端口号必须在 1-65535 之间", Toast.LENGTH_SHORT).show();
					return;
				}
			} catch (NumberFormatException e) {
				Toast.makeText(FrpManagerActivity.this, "远程端口号无效", Toast.LENGTH_SHORT).show();
				return;
			}
			String localIp = localIpInput.getText().toString().trim();
			if (TextUtils.isEmpty(localIp)) {
				Toast.makeText(FrpManagerActivity.this, "本地 IP 不能为空", Toast.LENGTH_SHORT).show();
				return;
			}
			if (!isValidIpAddress(localIp)) {
				Toast.makeText(FrpManagerActivity.this, "本地 IP 格式不正确", Toast.LENGTH_SHORT).show();
				return;
			}
			int localPort;
			try {
				localPort = Integer.parseInt(localPortInput.getText().toString());
				if (localPort <= 0 || localPort > 65535) {
					Toast.makeText(FrpManagerActivity.this, "本地端口号必须在 1-65535 之间", Toast.LENGTH_SHORT).show();
					return;
				}
			} catch (NumberFormatException e) {
				Toast.makeText(FrpManagerActivity.this, "本地端口号无效", Toast.LENGTH_SHORT).show();
				return;
			}
			boolean isTcpChecked = tcpCheckbox.isChecked();
			boolean isUdpChecked = udpCheckbox.isChecked();

			if (!isTcpChecked && !isUdpChecked) {
				Toast.makeText(FrpManagerActivity.this, "请至少选择一个协议 (TCP 或 UDP)", Toast.LENGTH_SHORT).show();
				return;
			}

			if (existingProfile == null) {
				Set<String> existingProfileKeys = allProfiles.stream().map(this::getProfileLockKey)
				.collect(Collectors.toSet());

				boolean tcpExists = existingProfileKeys.contains(getProfileLockKey(remotePort, "tcp"));
				boolean udpExists = existingProfileKeys.contains(getProfileLockKey(remotePort, "udp"));
				final String tag = tagInput.getText().toString();
				final String proxyVersion = proxySwitch.isChecked()
					? (String) proxyVersionSpinner.getSelectedItem()
					: null;

				if (isTcpChecked && isUdpChecked) {
					if (tcpExists && udpExists) {
						Toast.makeText(FrpManagerActivity.this, "错误：远程端口 " + remotePort + " 的 TCP 和 UDP 协议均已存在。",
									 Toast.LENGTH_LONG).show();
						return;
					} else if (tcpExists) {
						new MaterialAlertDialogBuilder(FrpManagerActivity.this).setTitle("端口部分已存在")
							.setMessage("远程端口 " + remotePort + "/TCP 已存在。\n是否要继续只创建 " + remotePort + "/UDP 端口？")
							.setPositiveButton("仅创建UDP", (d, w) -> {
							FrpProfile udpProfile = createProfileFromInputs(serverAddr, serverPort, token,
																			remotePort, localIp, localPort, tag, "udp", proxyVersion);
							addProfileToPendingList(udpProfile, true);
						}).setNegativeButton("取消", null).show();
					} else if (udpExists) {
						new MaterialAlertDialogBuilder(FrpManagerActivity.this).setTitle("端口部分已存在")
							.setMessage("远程端口 " + remotePort + "/UDP 已存在。\n是否要继续只创建 " + remotePort + "/TCP 端口？")
							.setPositiveButton("仅创建TCP", (d, w) -> {
							FrpProfile tcpProfile = createProfileFromInputs(serverAddr, serverPort, token,
																			remotePort, localIp, localPort, tag, "tcp", proxyVersion);
							addProfileToPendingList(tcpProfile, true);
						}).setNegativeButton("取消", null).show();
					} else {
						FrpProfile tcpProfile = createProfileFromInputs(serverAddr, serverPort, token, remotePort,
																		localIp, localPort, tag, "tcp", proxyVersion);
						FrpProfile udpProfile = createProfileFromInputs(serverAddr, serverPort, token, remotePort,
																		localIp, localPort, tag, "udp", proxyVersion);
						addProfileToPendingList(tcpProfile, true);
						addProfileToPendingList(udpProfile, true);
					}
				} else if (isTcpChecked) {
					if (tcpExists) {
						Toast.makeText(FrpManagerActivity.this, "错误：远程端口 " + remotePort + "/TCP 已存在。",
									 Toast.LENGTH_SHORT).show();
						return;
					}
					FrpProfile tcpProfile = createProfileFromInputs(serverAddr, serverPort, token, remotePort, localIp,
																	localPort, tag, "tcp", proxyVersion);
					addProfileToPendingList(tcpProfile, true);
				} else {
					if (udpExists) {
						Toast.makeText(FrpManagerActivity.this, "错误：远程端口 " + remotePort + "/UDP 已存在。",
									 Toast.LENGTH_SHORT).show();
						return;
					}
					FrpProfile udpProfile = createProfileFromInputs(serverAddr, serverPort, token, remotePort, localIp,
																	localPort, tag, "udp", proxyVersion);
					addProfileToPendingList(udpProfile, true);
				}
			} else {
				FrpProfile newProfile = createProfileFromInputs(serverAddr, serverPort, token,
																existingProfile.getRemotePort(), localIp, localPort, tagInput.getText().toString(),
																existingProfile.getProtocol(),
																proxySwitch.isChecked() ? (String) proxyVersionSpinner.getSelectedItem() : null);
				newProfile.setStatus(existingProfile.getStatus());
				newProfile.setModified(true);

				if (newProfile.hasFunctionalChanges(existingProfile)) {
					showSaveAndRestartDialog(newProfile, existingProfile.getStatus());
				} else {
					applySingleProfileChange(newProfile, false);
					Toast.makeText(FrpManagerActivity.this, "备注已更新", Toast.LENGTH_SHORT).show();
				}
			}
		}).setNegativeButton("取消", null).show();
	}

	private FrpProfile createProfileFromInputs(String serverAddr, int serverPort, String token, int remotePort,
											 String localIp, int localPort, String tag, String protocol, String proxyVersion) {
		FrpProfile newProfile = new FrpProfile();
		newProfile.setServerAddr(serverAddr);
		newProfile.setServerPort(serverPort);
		if (!token.isEmpty()) {
			newProfile.setToken(token);
		}
		newProfile.setRemotePort(remotePort);
		newProfile.setLocalIp(localIp);
		newProfile.setLocalPort(localPort);
		newProfile.setTag(tag);
		newProfile.setProtocol(protocol);
		newProfile.setProxyProtocolVersion(proxyVersion);
		newProfile.setStatus("已停止 (待应用)");
		newProfile.setModified(true);
		return newProfile;
	}

	private boolean isValidIpAddress(String ip) {
		if (ip == null || ip.isEmpty()) {
			return false;
		}
		Pattern pattern = Pattern.compile("^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\."
										 + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." + "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$");
		return pattern.matcher(ip).matches();
	}

	private boolean isValidDomain(String domain) {
		if (domain == null || domain.isEmpty()) {
			return false;
		}
		Pattern pattern = Pattern.compile("^[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$");
		return pattern.matcher(domain).matches();
	}

	private void showSaveAndRestartDialog(FrpProfile profile, String currentStatus) {
		String message = "配置已修改。是否立即将新配置应用到服务器？\n\n注意：应用后需要您手动重启或启动此项服务才能使新配置生效。";
		new MaterialAlertDialogBuilder(this).setTitle("应用配置").setMessage(message).setPositiveButton("立即应用", (d, w) -> {
			applySingleProfileChange(profile, false);
		}).setNeutralButton("暂存更改", (d, w) -> addProfileToPendingList(profile, false)).setNegativeButton("取消", null)
			.show();
	}

	private void addProfileToPendingList(FrpProfile profileToAdd, boolean isNew) {
		profileToAdd.setModified(true);

		pendingChangesProfiles.removeIf(p -> p.getRemotePort() == profileToAdd.getRemotePort()
			&& p.getProtocol().equalsIgnoreCase(profileToAdd.getProtocol()));
		pendingChangesProfiles.add(profileToAdd);

		int existingIndex = -1;
		for (int i = 0; i < allProfiles.size(); i++) {
			if (allProfiles.get(i).getRemotePort() == profileToAdd.getRemotePort()
				&& allProfiles.get(i).getProtocol().equalsIgnoreCase(profileToAdd.getProtocol())) {
				existingIndex = i;
				break;
			}
		}

		if (existingIndex != -1) {
			allProfiles.set(existingIndex, profileToAdd);
		} else {
			allProfiles.add(profileToAdd);
		}

		Collections.sort(allProfiles,
						 Comparator.comparingInt(FrpProfile::getRemotePort).thenComparing(FrpProfile::getProtocol));
		applyFilters();
		updatePendingChangesMenu();
	}

	private void applySingleProfileChange(final FrpProfile profile, final boolean restart) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View logView = inflater.inflate(R.layout.dialog_log_viewer, null);
		final TextView logTextView = logView.findViewById(R.id.log_text_view);
		final ScrollView logScrollView = logView.findViewById(R.id.log_scroll_view);
		final Button copyButton = logView.findViewById(R.id.btn_copy_log);
		final StringBuilder logBuilder = new StringBuilder();

		String title = "应用端口 " + profile.getRemotePort() + "/" + profile.getProtocol().toUpperCase() + " 的更改...";
		AlertDialog logDialog = new MaterialAlertDialogBuilder(this).setView(logView).setTitle(title)
			.setCancelable(false).setPositiveButton("关闭", null).create();

		logDialog.show();
		final Button closeButton = logDialog.getButton(AlertDialog.BUTTON_POSITIVE);
		closeButton.setEnabled(false);

		copyButton.setOnClickListener(v -> {
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText("FRP Manager Log", logBuilder.toString());
			clipboard.setPrimaryClip(clip);
			Toast.makeText(FrpManagerActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
		});

		lockedProfiles.add(getProfileLockKey(profile));

		executor.execute(() -> {
			boolean hasError = false;
			try {
				appendLog(logTextView, logScrollView, logBuilder,
						 "准备为端口 " + profile.getRemotePort() + "/" + profile.getProtocol().toUpperCase() + " 应用更改...",
						 "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

				final String fileContent = buildFileContent(profile);
				StringBuilder commandBuilder = new StringBuilder("set -e; ");
				boolean manageFirewall = settingsManager.isFirewallManaged() && firewallManager != null;
				String protocol = profile.getProtocol().toLowerCase();

				if (manageFirewall) {
					appendLog(logTextView, logScrollView, logBuilder,
							 "[防火墙日志] 正在为端口 " + profile.getRemotePort() + " 准备更新规则...", "info");
					String removeTcpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "tcp")
						.replace(" || true", "") + " || true";
					String removeUdpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "udp")
						.replace(" || true", "") + " || true";
					String addCmd = firewallManager.addPortRuleCommand(profile.getRemotePort(), protocol);
					commandBuilder.append(removeTcpCmd).append("; ").append(removeUdpCmd).append("; ").append(addCmd)
						.append("; ");
				}

				commandBuilder.append(String.format(Locale.US,
													"sudo rm -f /etc/frp/conf.d/port_%d_%s.ini.disabled; "
													+ "printf '%%s\\n' '%s' | sudo tee /etc/frp/conf.d/port_%d_%s.ini > /dev/null",
													profile.getRemotePort(), protocol, fileContent, profile.getRemotePort(), protocol));
				if (restart) {
					appendLog(logTextView, logScrollView, logBuilder, "正在添加服务重启命令...", "info");
					String serviceNew = String.format("frpc@%d_%s.service", profile.getRemotePort(), profile.getProtocol().toLowerCase());
					String serviceOld = String.format("frpc@%d.service", profile.getRemotePort());

					String restartBlock = String.format(Locale.US,
														"; SERVICE_NAME_TO_USE=''; " +
														"if [ -f '/etc/frp/conf.d/port_%d_%s.ini' ]; then SERVICE_NAME_TO_USE='%s'; " +
														"elif [ -f '/etc/frp/conf.d/port_%d.ini' ]; then SERVICE_NAME_TO_USE='%s'; fi; " +
														"if [ -n \"$SERVICE_NAME_TO_USE\" ]; then sudo systemctl restart \"$SERVICE_NAME_TO_USE\"; " +
														"else echo 'RESTART FAILED: config file not found.'; fi",
														profile.getRemotePort(), profile.getProtocol().toLowerCase(), serviceNew,
														profile.getRemotePort(), serviceOld
														);
					commandBuilder.append(restartBlock);
				}

				if (manageFirewall) {
					appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 正在添加防火墙重载命令...", "info");
					commandBuilder.append("; ").append(firewallManager.reloadFirewallCommand()).append(";");
				}

				final String finalCommand = commandBuilder.toString();
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");
				appendLog(logTextView, logScrollView, logBuilder, "最终执行命令:\n" + finalCommand, "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

				String result = sshManager.executeCommand(finalCommand, 30);
				appendLog(logTextView, logScrollView, logBuilder, "\n服务器返回信息:", "success");
				appendLog(logTextView, logScrollView, logBuilder, result.isEmpty() ? "(无输出)" : result, "success");

				pendingChangesProfiles.removeIf(p -> p.getRemotePort() == profile.getRemotePort()
					&& p.getProtocol().equalsIgnoreCase(protocol));
				appendLog(logTextView, logScrollView, logBuilder, "\n更改已应用成功！", "success");
				if (!restart) {
					appendLog(logTextView, logScrollView, logBuilder, "请手动重启服务以使新配置生效。", "success");
				}

			} catch (IOException e) {
				hasError = true;
				appendLog(logTextView, logScrollView, logBuilder, "\n\n###### 操作失败! ######", "error");
				appendLog(logTextView, logScrollView, logBuilder, "错误详情: " + e.getMessage(), "error");
			} finally {
				lockedProfiles.remove(getProfileLockKey(profile));
				boolean finalHasError = hasError;
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					closeButton.setEnabled(true);
					updatePendingChangesMenu();
					loadFrpProfilesFromServer();
					if (!finalHasError) {
						new Handler(Looper.getMainLooper()).postDelayed(logDialog::dismiss, 2500);
					}
				});
			}
		});
	}

	private String buildFileContent(FrpProfile profile) {
		StringBuilder commonBuilder = new StringBuilder();
		commonBuilder.append("[common]\n");

		String serverAddr = profile.getServerAddr() != null
			? profile.getServerAddr()
			: settingsManager.getFrpServerAddr();
		int serverPort = profile.getServerPort() > 0 ? profile.getServerPort() : settingsManager.getFrpServerPort();
		String token = profile.getToken() != null ? profile.getToken() : settingsManager.getFrpToken();

		commonBuilder.append("server_addr = ").append(serverAddr).append("\n");
		commonBuilder.append("server_port = ").append(serverPort).append("\n");
		if (token != null && !token.isEmpty()) {
			commonBuilder.append("token = ").append(token).append("\n");
		}

		StringBuilder fileContentBuilder = new StringBuilder();
		fileContentBuilder.append(commonBuilder.toString()).append("\n\n");

		fileContentBuilder.append(String.format("# tag = %s\n", profile.getTag() != null ? profile.getTag() : ""));
		String sectionName = String.format("%s_port_%d", profile.getProtocol(), profile.getRemotePort());
		fileContentBuilder.append(String.format("[%s]\n", sectionName));
		fileContentBuilder.append(String.format("type = %s\n", profile.getProtocol()));
		fileContentBuilder.append(String.format("local_ip = %s\n", profile.getLocalIp()));
		fileContentBuilder.append(String.format("local_port = %d\n", profile.getLocalPort()));
		fileContentBuilder.append(String.format("remote_port = %d", profile.getRemotePort()));
		if (profile.getProxyProtocolVersion() != null && !profile.getProxyProtocolVersion().isEmpty()) {
			fileContentBuilder
				.append(String.format("\nproxy_protocol_version = %s", profile.getProxyProtocolVersion()));
		}
		return fileContentBuilder.toString().replace("'", "'\\''");
	}

	private void showBatchCreateDialog() {
		LayoutInflater inflater = LayoutInflater.from(this);
		View dialogView = inflater.inflate(R.layout.dialog_batch_create, null);

		final EditText serverAddrInput = dialogView.findViewById(R.id.edit_text_server_addr);
		final EditText serverPortInput = dialogView.findViewById(R.id.edit_text_server_port);
		final EditText tokenInput = dialogView.findViewById(R.id.edit_text_token);
		final EditText remoteRangeInput = dialogView.findViewById(R.id.edit_text_remote_range);
		final EditText localIpInput = dialogView.findViewById(R.id.edit_text_local_ip);
		final EditText tagInput = dialogView.findViewById(R.id.edit_text_tag);
		final CheckBox tcpCheckbox = dialogView.findViewById(R.id.checkbox_tcp_batch);
		final CheckBox udpCheckbox = dialogView.findViewById(R.id.checkbox_udp_batch);

		serverAddrInput.setText(settingsManager.getFrpServerAddr());
		serverPortInput.setText(String.valueOf(settingsManager.getFrpServerPort()));
		tokenInput.setText(settingsManager.getFrpToken());

		tcpCheckbox.setChecked(true);
		udpCheckbox.setChecked(false);

		new MaterialAlertDialogBuilder(this).setView(dialogView).setTitle("批量创建端口").setNegativeButton("取消", null)
			.setPositiveButton("创建", (dialog, which) -> {
			try {
				String serverAddr = serverAddrInput.getText().toString().trim();
				if (!isValidIpAddress(serverAddr) && !isValidDomain(serverAddr)) {
					throw new IllegalArgumentException("FRP 服务器地址格式不正确。");
				}

				int serverPort;
				try {
					serverPort = Integer.parseInt(serverPortInput.getText().toString());
					if (serverPort <= 0 || serverPort > 65535)
						throw new NumberFormatException();
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("FRP 服务器端口无效。");
				}

				String token = tokenInput.getText().toString().trim();
				String remoteRange = remoteRangeInput.getText().toString();
				String localIp = localIpInput.getText().toString();
				final String tagTemplate = tagInput.getText().toString().trim();

				boolean isTcpChecked = tcpCheckbox.isChecked();
				boolean isUdpChecked = udpCheckbox.isChecked();

				if (!isTcpChecked && !isUdpChecked) {
					throw new IllegalArgumentException("请至少选择一个协议 (TCP 或 UDP)。");
				}

				String[] parts = remoteRange.split("-");
				if (parts.length != 2) {
					throw new IllegalArgumentException("范围格式错误，应为 '开始-结束'");
				}

				int remoteStart = Integer.parseInt(parts[0].trim());
				int remoteEnd = Integer.parseInt(parts[1].trim());

				if (remoteStart <= 0 || remoteEnd > 65535 || remoteStart > remoteEnd) {
					throw new IllegalArgumentException("端口范围无效，必须在 1-65535 之间，且开始端口需小于等于结束端口。");
				}
				if (remoteEnd - remoteStart + 1 > 1001) {
					throw new IllegalArgumentException("单次创建范围不能超过1001个端口。");
				}

				if (!isValidIpAddress(localIp)) {
					throw new IllegalArgumentException("本地 IP 格式不正确。");
				}

				List<FrpProfile> newProfiles = new ArrayList<>();
				Set<String> existingProfileKeys = allProfiles.stream().map(this::getProfileLockKey)
				.collect(Collectors.toSet());

				for (int port = remoteStart; port <= remoteEnd; port++) {
					if (isTcpChecked && !existingProfileKeys.contains(getProfileLockKey(port, "tcp"))) {
						newProfiles.add(createBatchProfile(serverAddr, serverPort, token, port, localIp,
														 tagTemplate, "tcp"));
					}
					if (isUdpChecked && !existingProfileKeys.contains(getProfileLockKey(port, "udp"))) {
						newProfiles.add(createBatchProfile(serverAddr, serverPort, token, port, localIp,
														 tagTemplate, "udp"));
					}
				}

				if (newProfiles.isEmpty()) {
					Toast.makeText(FrpManagerActivity.this, "指定范围内所有端口均已存在，无需创建。", Toast.LENGTH_SHORT).show();
					return;
				}

				for (FrpProfile p : newProfiles) {
					addProfileToPendingList(p, true);
				}

				new MaterialAlertDialogBuilder(this).setTitle("创建成功")
					.setMessage(newProfiles.size() + " 个新端口配置已暂存。请点击菜单中的 '应用更改' 以将它们写入服务器。")
					.setPositiveButton("好的", null).show();

			} catch (Exception e) {
				showErrorDialog("输入错误", e.getMessage());
			}
		}).show();
	}

	private FrpProfile createBatchProfile(String serverAddr, int serverPort, String token, int port, String localIp,
										 String tagTemplate, String protocol) {
		FrpProfile p = new FrpProfile();
		p.setServerAddr(serverAddr);
		p.setServerPort(serverPort);
		if (!token.isEmpty())
			p.setToken(token);

		p.setRemotePort(port);
		p.setLocalIp(localIp);
		p.setLocalPort(port);
		p.setProtocol(protocol);

		if (tagTemplate.contains("[P]")) {
			p.setTag(tagTemplate.replace("[P]", String.valueOf(port)));
		} else if (!tagTemplate.isEmpty()) {
			p.setTag(tagTemplate);
		} else {
			p.setTag("批量创建");
		}

		p.setStatus("已停止 (待应用)");
		p.setModified(true);
		return p;
	}

	private void showBatchOperationDialog(final String operationType) {
		String title;
		switch (operationType) {
			case "start" :
				title = "批量启动端口";
				break;
			case "stop" :
				title = "批量暂停端口";
				break;
			case "delete" :
			default :
				title = "范围删除端口";
				break;
		}

		LayoutInflater inflater = this.getLayoutInflater();
		View dialogView = inflater.inflate(R.layout.dialog_material_input, null);
		final TextInputEditText input = dialogView.findViewById(R.id.text_input_edit_text);

		new MaterialAlertDialogBuilder(this).setTitle(title).setView(dialogView)
			.setPositiveButton(operationType.equals("delete") ? "删除" : "执行", (dialog, which) -> {
			String range = input.getText().toString().trim();
			if (range.isEmpty()) {
				Toast.makeText(FrpManagerActivity.this, "请输入端口范围或 'all'", Toast.LENGTH_SHORT).show();
				return;
			}

			List<FrpProfile> targetProfiles = parseRangeToProfiles(range, operationType);
			if (targetProfiles != null && !targetProfiles.isEmpty()) {
				confirmAndExecuteBatchOperation(operationType, targetProfiles);
			} else if (targetProfiles != null) {
				Toast.makeText(this, "在指定范围内未找到可操作的端口", Toast.LENGTH_SHORT).show();
			}
		}).setNegativeButton("取消", null).show();
	}

	private List<FrpProfile> parseRangeToProfiles(String range, String operationType) {
		List<FrpProfile> targets = new ArrayList<>();
		Predicate<FrpProfile> filter;
		switch (operationType) {
			case "start" :
			case "restart" :
				filter = p -> !"运行中".equals(p.getStatus()) && !p.isModified();
				break;
			case "stop" :
				filter = p -> "运行中".equals(p.getStatus()) && !p.isModified();
				break;
			case "delete" :
			case "firewall-allow" :
			case "firewall-block" :
			default :
				filter = p -> true;
				break;
		}

		try {
			if ("all".equalsIgnoreCase(range)) {
				targets.addAll(allProfiles.stream().filter(filter).collect(Collectors.toList()));
			} else {
				String[] parts = range.split("-");
				if (parts.length != 2)
					throw new NumberFormatException("范围格式错误");
				int startPort = Integer.parseInt(parts[0].trim());
				int endPort = Integer.parseInt(parts[1].trim());
				if (startPort > endPort || startPort <= 0 || endPort > 65535)
					throw new IllegalArgumentException("端口范围无效");

				targets.addAll(
					allProfiles.stream().filter(p -> p.getRemotePort() >= startPort && p.getRemotePort() <= endPort)
					.filter(filter).collect(Collectors.toList()));
			}
		} catch (Exception e) {
			Toast.makeText(this, "范围格式错误，应为 '开始-结束'(1-65535) 或 'all'", Toast.LENGTH_SHORT).show();
			return null;
		}

		return targets;
	}

	private void confirmAndExecuteBatchOperation(String operationType, List<FrpProfile> targets) {
		if (targets == null || targets.isEmpty()) {
			Toast.makeText(this, "没有需要操作的项", Toast.LENGTH_SHORT).show();
			return;
		}

		String title, positiveButtonText;
		switch (operationType) {
			case "start" :
				title = "确认启动以下 " + targets.size() + " 个端口？";
				positiveButtonText = "全部启动";
				break;
			case "stop" :
				title = "确认暂停以下 " + targets.size() + " 个端口？";
				positiveButtonText = "全部暂停";
				break;
			case "restart" :
				title = "确认重启以下 " + targets.size() + " 个端口？";
				positiveButtonText = "全部重启";
				break;
			case "delete" :
			default :
				title = "确认永久删除以下 " + targets.size() + " 个端口？";
				positiveButtonText = "永久删除";
				break;
		}

		ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
															 targets.stream()
															 .map(p -> "端口: " + p.getRemotePort() + "/" + p.getProtocol().toUpperCase()
															 + (p.getTag() != null && !p.getTag().isEmpty() ? " (" + p.getTag() + ")" : ""))
			.collect(Collectors.toList()));

		new MaterialAlertDialogBuilder(this).setTitle(title).setAdapter(arrayAdapter, null)
			.setNegativeButton("取消", null).setPositiveButton(positiveButtonText, (d, w) -> {
			executeBatchOperationWithLogDialog(operationType, targets);
		}).show();
	}

	private void executeBatchOperationWithLogDialog(final String operationType, final List<FrpProfile> targets) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View logView = inflater.inflate(R.layout.dialog_log_viewer, null);
		final TextView logTextView = logView.findViewById(R.id.log_text_view);
		final ScrollView logScrollView = logView.findViewById(R.id.log_scroll_view);
		final Button copyButton = logView.findViewById(R.id.btn_copy_log);
		final StringBuilder logBuilder = new StringBuilder();

		logTextView.setMovementMethod(new ScrollingMovementMethod());
		AlertDialog logDialog = new MaterialAlertDialogBuilder(this).setView(logView)
			.setTitle(getOperationName(operationType) + "命令执行中...").setCancelable(false)
			.setPositiveButton("关闭", null).create();
		logDialog.show();

		final Button closeButton = logDialog.getButton(AlertDialog.BUTTON_POSITIVE);
		closeButton.setEnabled(false);
		copyButton.setOnClickListener(v -> {
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText("FRP Manager Log", logBuilder.toString());
			clipboard.setPrimaryClip(clip);
			Toast.makeText(FrpManagerActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
		});

		executor.execute(() -> {
			List<FrpProfile> finalTargets = new ArrayList<>(targets);
			if (finalTargets.isEmpty()) {
				appendLog(logTextView, logScrollView, logBuilder, "没有可执行操作的目标。", "info");
				runOnUiThread(() -> { logDialog.setTitle("操作完成"); closeButton.setEnabled(true); exitSelectionMode(); });
				return;
			}

			StringBuilder commandBuilder = new StringBuilder();
			StringBuilder summaryBuilder = new StringBuilder();
			summaryBuilder.append("--- 命令执行计划 ---\n");

			boolean manageFirewall = settingsManager.isFirewallManaged() && firewallManager != null;
			boolean firewallActionTaken = false;

			switch(operationType){
				case "start":
				case "restart":
					String mode = "start".equals(operationType) ? "启动" : "重启";
					summaryBuilder.append("执行模式：").append(mode).append("\n");

					for (FrpProfile p : finalTargets) {
						int port = p.getRemotePort();
						String protocol = p.getProtocol().toLowerCase();
						summaryBuilder.append(String.format(Locale.US, "\n● 端口 %d/%S (%s):\n", port, protocol.toUpperCase(), mode));

						if (manageFirewall && "start".equals(operationType)) {
							summaryBuilder.append(" - 防火墙: 添加端口规则\n");
							commandBuilder.append(firewallManager.addPortRuleCommand(port, protocol)).append("; ");
							firewallActionTaken = true;
						}
						summaryBuilder.append(" - 操 作: 添加新规则\n");
						summaryBuilder.append(" - 验 证: 检查服务状态->诊断日志\n");

						String serviceNew = String.format("frpc@%d_%s.service", port, protocol);
						String serviceOld = String.format("frpc@%d.service", port);
						String configFileNew = String.format("/etc/frp/conf.d/port_%d_%s.ini", port, protocol);
						String configFileOld = String.format("/etc/frp/conf.d/port_%d.ini", port);
						String actionVerb = "start".equals(operationType) ? "enable --now" : "restart";
						String fileContent = buildFileContent(p);

						String scriptBlock = String.format(Locale.US,
														 "echo; echo '======== PROCESSING PORT %d/%S (%s) ========';\n" +
														 "echo '--> Step 1: Decommissioning legacy rule (if it exists)...';\n" +
														 "sudo systemctl disable --now %s >/dev/null 2>&1 || true;\n" +
														 "if [ -f \"%s\" ]; then sudo mv -f \"%s\" \"%s.bak\"; echo ' - Legacy config %s renamed to .bak'; fi;\n" +
														 "echo '--> Step 2: Writing new configuration to %s...';\n" +
														 "printf '%%s' '%s' | sudo tee \"%s\" > /dev/null;\n" +
														 "echo '--> Step 3: Performing action: %s';\n" +
														 "sudo systemctl %s %s; sleep 1.5;\n" +
														 "echo '--> Step 4: Verification & Diagnosis:';\n" +
														 "if sudo systemctl is-active --quiet \"%s\"; then\n" +
														 "  LOG_OUTPUT=$(sudo journalctl -u \"%s\" --no-pager -n 10);\n" +
														 "  if echo \"$LOG_OUTPUT\" | grep -q -E \"listener failed|start error|bind: address already in use\"; then echo 'FRP_MANAGER_STATUS:WARNING'; else echo 'FRP_MANAGER_STATUS:SUCCESS'; fi;\n" +
														 "  echo \"$LOG_OUTPUT\";\n" +
														 "else\n" +
														 "  echo 'FRP_MANAGER_STATUS:ERROR'; echo 'FRP_MANAGER_MESSAGE:服务启动失败！';\n" +
														 "  sudo journalctl -u \"%s\" --no-pager -n 20 || echo 'Could not retrieve logs.';\n" +
														 "fi\n",
														 port, protocol.toUpperCase(), operationType,
														 serviceOld,
														 configFileOld, configFileOld, configFileOld, configFileOld,
														 configFileNew,
														 fileContent, configFileNew,
														 actionVerb,
														 actionVerb, serviceNew,
														 serviceNew,
														 serviceNew,
														 serviceNew
														 );
						commandBuilder.append(scriptBlock);
					}
					break;

				case "stop":
					summaryBuilder.append("执行模式：暂停\n");
					for (FrpProfile p : finalTargets) {
						int port = p.getRemotePort(); String protocol = p.getProtocol().toLowerCase();
						summaryBuilder.append(String.format(Locale.US, "\n● 端口 %d/%S (暂停):\n", port, protocol.toUpperCase()));
						if(manageFirewall) {
							commandBuilder.append(firewallManager.removePortRuleCommand(port, protocol)).append("; ");
							firewallActionTaken = true;
						}
						summaryBuilder.append(" - 操 作: 停止服务\n");
						String serviceNew = String.format("frpc@%d_%s.service", port, protocol);
						String serviceOld = String.format("frpc@%d.service", port);
						String baseCmd = String.format(Locale.US, "echo; echo '--- Processing Port %d/%S ---'; sudo systemctl disable --now %s >/dev/null 2>&1||true; sudo systemctl disable --now %s >/dev/null 2>&1||true;", port, protocol.toUpperCase(), serviceNew, serviceOld);
						commandBuilder.append(baseCmd);
					}
					break;

				case "delete":
					summaryBuilder.append("执行模式：删除\n");
					for (FrpProfile p : finalTargets) {
						int port = p.getRemotePort(); String protocol = p.getProtocol().toLowerCase();
						summaryBuilder.append(String.format(Locale.US, "\n● 端口 %d/%S (删除):\n", port, protocol.toUpperCase()));
						if(manageFirewall) {
							commandBuilder.append(firewallManager.removePortRuleCommand(port, protocol)).append("; ");
							firewallActionTaken = true;
						}
						summaryBuilder.append(" - 操 作: 精确清理服务和配置文件\n");

						String serviceToDelete = String.format("frpc@%d_%s.service", port, protocol);
						String configToDelete = String.format("/etc/frp/conf.d/port_%d_%s.ini", port, protocol);

						String cleanupCmd = String.format(Locale.US,
														 "echo; echo '--- Processing Port %d/%s for precise deletion ---';\n" +
														 "sudo systemctl disable --now %s >/dev/null 2>&1||true;\n" +
														 "sudo rm -f /etc/systemd/system/%s;\n" +
														 "sudo rm -f %s;\n" +
														 "sudo rm -f %s.bak;\n" +
														 "echo 'Precise cleanup for port %d/%s completed.';\n",
														 port, protocol.toUpperCase(),
														 serviceToDelete,
														 serviceToDelete,
														 configToDelete,
														 configToDelete,
														 port, protocol.toUpperCase()
														 );
						commandBuilder.append(cleanupCmd);
					}
					commandBuilder.append("echo 'Reloading systemd...';\n").append("sudo systemctl daemon-reload;\n");
					break;
			}


			if (manageFirewall && firewallActionTaken) {
				summaryBuilder.append("\n● 防火墙:\n - 重载防火墙\n");
				commandBuilder.append("echo 'Reloading firewall rules...';\n").append(firewallManager.reloadFirewallCommand()).append(";");
			}

			final String finalCommand = commandBuilder.toString();
			final String summary = summaryBuilder.toString();

			finalTargets.forEach(p -> {
				lockedProfiles.add(getProfileLockKey(p));
				String pendingStatus;
				switch (operationType) { case "start": case "restart": pendingStatus = "正在启动..."; break; case "stop": pendingStatus = "正在暂停..."; break; case "delete": pendingStatus = "正在删除..."; break; default: pendingStatus = "操作中..."; break; }
				runOnUiThread(() -> updateProfileStatusInUi(p, pendingStatus));
			});

			try {
				appendLog(logTextView, logScrollView, logBuilder, summary, "info");
				appendLog(logTextView, logScrollView, logBuilder, "\n--- 开始执行 ---", "info");
				String result = sshManager.executeCommand(finalCommand, 180);

				appendLog(logTextView, logScrollView, logBuilder, "\n--- 执行日志 ---", "info");
				String[] blocks = result.split("======== PROCESSING PORT");
				for (String block : blocks) {
					if (block.trim().isEmpty()) continue;
					String fullBlock = "======== PROCESSING PORT" + block;
					String logType = "success";
					if (block.contains("FRP_MANAGER_STATUS:ERROR")) logType = "error";
					else if (block.contains("FRP_MANAGER_STATUS:WARNING")) logType = "warning";
					appendLog(logTextView, logScrollView, logBuilder, fullBlock, logType);
				}
				appendLog(logTextView, logScrollView, logBuilder, "\n--- 执行完毕 ---", "success");

				if ("start".equals(operationType) || "restart".equals(operationType)) {
					for (String block : blocks) {
						if (block.trim().isEmpty()) continue;
						String status = "UNKNOWN";
						if (block.contains("FRP_MANAGER_STATUS:SUCCESS")) status = "SUCCESS";
						else if (block.contains("FRP_MANAGER_STATUS:WARNING")) status = "WARNING";
						else if (block.contains("FRP_MANAGER_STATUS:ERROR")) status = "ERROR";
						Pattern p = Pattern.compile("^\\s*(\\d+)/([A-Z]+)"); Matcher m = p.matcher(block);
						if (m.find()) {
							int port = Integer.parseInt(m.group(1)); String protocol = m.group(2).toLowerCase();
							String profileKey = getProfileLockKey(port, protocol);
							String finalStatus = status;
							runOnUiThread(() -> {
								FrpProfile profileToUpdate = allProfiles.stream().filter(prof -> getProfileLockKey(prof).equals(profileKey)).findFirst().orElse(null);
								if (profileToUpdate != null) {
									switch(finalStatus) {
										case "SUCCESS": profileToUpdate.setStatus("运行中"); break;
										case "WARNING": profileToUpdate.setStatus("运行中 (有警告)"); break;
										case "ERROR":
											profileToUpdate.setStatus("启动失败");
											new MaterialAlertDialogBuilder(FrpManagerActivity.this).setTitle("进程启动失败或崩溃").setMessage("端口 " + port + "/" + protocol.toUpperCase() + " 的frpc服务未能成功启动，请检查日志获取详细错误信息。").setPositiveButton("好的", null).show();
											break;
									}
								}
							});
						}
					}
				}
			} catch (IOException e) {
				appendLog(logTextView, logScrollView, logBuilder, "\n\n###### 操作失败! ######", "error");
				appendLog(logTextView, logScrollView, logBuilder, "错误详情: " + e.getMessage(), "error");
				e.printStackTrace();
			} finally {
				finalTargets.forEach(p -> lockedProfiles.remove(getProfileLockKey(p)));
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					closeButton.setEnabled(true);
					loadFrpProfilesFromServer();
					exitSelectionMode();
				});
			}
		});
	}

	private void appendLog(TextView textView, ScrollView scrollView, StringBuilder logBuilder, String message,
						 String type) {
		runOnUiThread(() -> {
			String formattedMessage;
			int color;
			switch (type) {
				case "success" :
					formattedMessage = "<font color='#4CAF50'>" + TextUtils.htmlEncode(message).replace("\n", "<br>")
						+ "</font><br>";
					break;
				case "error" :
					formattedMessage = "<font color='#F44336'>" + TextUtils.htmlEncode(message).replace("\n", "<br>")
						+ "</font><br>";
					break;
				case "warning" :
					formattedMessage = "<font color='#FF9800'>" + TextUtils.htmlEncode(message).replace("\n", "<br>")
						+ "</font><br>";
					break;
				case "info" :
				default :
					formattedMessage = TextUtils.htmlEncode(message).replace("\n", "<br>") + "<br>";
					break;
			}
			logBuilder.append(message).append("\n");
			textView.append(Html.fromHtml(formattedMessage, Html.FROM_HTML_MODE_LEGACY));

			scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
		});
	}

	private String getOperationName(String type) {
		switch (type) {
			case "start" :
				return "启动";
			case "stop" :
				return "暂停";
			case "restart" :
				return "重启";
			case "delete" :
				return "删除";
			case "apply" :
				return "应用更改";
			case "allow" :
				return "防火墙放行";
			case "block" :
				return "防火墙阻止";
			default :
				return "操作";
		}
	}

	private void applyChangesOnServer(final List<FrpProfile> profilesToApply) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View logView = inflater.inflate(R.layout.dialog_log_viewer, null);
		final TextView logTextView = logView.findViewById(R.id.log_text_view);
		final ScrollView logScrollView = logView.findViewById(R.id.log_scroll_view);
		final Button copyButton = logView.findViewById(R.id.btn_copy_log);
		final StringBuilder logBuilder = new StringBuilder();

		AlertDialog logDialog = new MaterialAlertDialogBuilder(this).setView(logView).setTitle("应用更改中...")
			.setCancelable(false).setPositiveButton("关闭", null).create();

		logDialog.show();
		final Button closeButton = logDialog.getButton(AlertDialog.BUTTON_POSITIVE);
		closeButton.setEnabled(false);

		copyButton.setOnClickListener(v -> {
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText("FRP Manager Log", logBuilder.toString());
			clipboard.setPrimaryClip(clip);
			Toast.makeText(FrpManagerActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
		});

		profilesToApply.forEach(p -> lockedProfiles.add(getProfileLockKey(p)));

		executor.execute(() -> {
			boolean hasError = false;
			StringBuilder commandBuilder = new StringBuilder("set -e; ");
			boolean manageFirewall = settingsManager.isFirewallManaged() && firewallManager != null;
			boolean firewallActionTaken = false;

			appendLog(logTextView, logScrollView, logBuilder, "准备将 " + profilesToApply.size() + " 项配置写入服务器...", "info");
			appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

			if (manageFirewall) {
				appendLog(logTextView, logScrollView, logBuilder,
						 "[防火墙日志] 防火墙管理已启用，检测到类型: " + firewallManager.getType(), "info");
			} else if (settingsManager.isFirewallManaged()) {
				appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 防火墙管理已启用，但未在服务器上检测到支持的类型。", "error");
			}

			for (FrpProfile profile : profilesToApply) {
				String fileContent = buildFileContent(profile);
				String protocol = profile.getProtocol().toLowerCase();

				if (manageFirewall) {
					String removeTcpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "tcp")
						.replace(" || true", "") + " || true";
					String removeUdpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "udp")
						.replace(" || true", "") + " || true";
					String addCmd = firewallManager.addPortRuleCommand(profile.getRemotePort(), protocol);

					appendLog(logTextView, logScrollView, logBuilder,
							 String.format("[防火墙日志] 为端口 %d 准备更新规则...", profile.getRemotePort()), "info");
					commandBuilder.append(removeTcpCmd).append("; ").append(removeUdpCmd).append("; ").append(addCmd)
						.append("; ");
					firewallActionTaken = true;
				}

				String command = String.format(Locale.US,
											 "sudo rm -f /etc/frp/conf.d/port_%d_%s.ini.disabled; "
											 + "printf '%%s\\n' '%s' | sudo tee /etc/frp/conf.d/port_%d_%s.ini > /dev/null; ",
											 profile.getRemotePort(), protocol, fileContent, profile.getRemotePort(), protocol);
				commandBuilder.append(command);
			}

			if (firewallActionTaken) {
				String reloadCommand = firewallManager.reloadFirewallCommand();
				appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 添加防火墙重载命令...", "info");
				commandBuilder.append(reloadCommand).append(";");
			}

			final String finalCommand = commandBuilder.toString();

			try {
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");
				appendLog(logTextView, logScrollView, logBuilder, "执行命令:\n" + finalCommand, "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

				String result = "";
				if (commandBuilder.length() > 5) {
					appendLog(logTextView, logScrollView, logBuilder, "正在发送命令至服务器...", "info");
					result = sshManager.executeCommand(finalCommand, 60);
				}

				appendLog(logTextView, logScrollView, logBuilder, "\n服务器返回信息:", "success");
				appendLog(logTextView, logScrollView, logBuilder, result.isEmpty() ? "(无输出)" : result, "success");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------",
						 "success");

				appendLog(logTextView, logScrollView, logBuilder, "\n正在验证配置文件是否已创建...", "info");
				StringBuilder verifyCmdBuilder = new StringBuilder();
				for (FrpProfile p : profilesToApply) {
					String protocol = p.getProtocol().toLowerCase();
					verifyCmdBuilder.append(String.format(
												"if [ -f /etc/frp/conf.d/port_%d_%s.ini ]; then echo 'OK:%s'; else echo 'FAIL:%s'; fi; ",
												p.getRemotePort(), protocol, getProfileLockKey(p), getProfileLockKey(p)));
				}
				String verifyResult = sshManager.executeCommand(verifyCmdBuilder.toString(), 30);

				boolean allVerified = true;
				for (String line : verifyResult.split("\n")) {
					if (line.startsWith("FAIL:")) {
						allVerified = false;
						String failedProfileKey = line.split(":")[1];
						appendLog(logTextView, logScrollView, logBuilder,
								 "创建失败: 未在服务器上找到 port_" + failedProfileKey.replace("/", "_") + ".ini 文件。", "error");
						hasError = true;
					}
				}

				if (allVerified) {
					appendLog(logTextView, logScrollView, logBuilder, "验证成功！所有配置文件均已写入服务器。", "success");
					appendLog(logTextView, logScrollView, logBuilder, "\n所有更改已成功应用！请手动启动或重启相关服务以使配置生效。", "success");
				} else {
					appendLog(logTextView, logScrollView, logBuilder, "\n部分或全部配置应用失败！请检查以上日志。", "error");
				}

			} catch (IOException e) {
				hasError = true;
				appendLog(logTextView, logScrollView, logBuilder, "\n\n###### 应用更改失败! ######", "error");
				appendLog(logTextView, logScrollView, logBuilder, "错误详情: " + e.getMessage(), "error");
				e.printStackTrace();
			} finally {
				profilesToApply.forEach(p -> lockedProfiles.remove(getProfileLockKey(p)));

				boolean finalHasError = hasError;
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					closeButton.setEnabled(true);
					if (!finalHasError) {
						pendingChangesProfiles.clear();
						updatePendingChangesMenu();
						loadFrpProfilesFromServer();
						new Handler(Looper.getMainLooper()).postDelayed(logDialog::dismiss, 2500);
					}
				});
			}
		});
	}

	private void showFirewallOperationDialog(final FrpProfile profile) {
		if (firewallManager == null) {
			Toast.makeText(this, "未检测到支持的防火墙，无法操作。", Toast.LENGTH_SHORT).show();
			return;
		}
		if (!settingsManager.isFirewallManaged()) {
			Toast.makeText(this, "防火墙管理未在设置中启用。", Toast.LENGTH_LONG).show();
			return;
		}

		LayoutInflater inflater = LayoutInflater.from(this);
		View dialogView = inflater.inflate(R.layout.dialog_firewall_operation, null);

		TextView title = dialogView.findViewById(R.id.dialog_title);
		TextView tvType = dialogView.findViewById(R.id.tv_firewall_type);
		TextView tvStatus = dialogView.findViewById(R.id.tv_firewall_port_status);

		title.setText("操作端口 " + profile.getRemotePort() + "/" + profile.getProtocol().toUpperCase() + " 防火墙");
		tvType.setText("防火墙类型: " + firewallManager.getType());
		tvStatus.setText("当前状态: " + profile.getFirewallStatus());

		if ("已放行".equals(profile.getFirewallStatus())) {
			tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running));
		} else {
			tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped));
		}

		new MaterialAlertDialogBuilder(this).setView(dialogView)
			.setPositiveButton("放行端口", (d, w) -> executeSingleFirewallOperation(profile, "allow"))
		.setNegativeButton("阻止端口", (d, w) -> executeSingleFirewallOperation(profile, "block"))
		.setNeutralButton("取消", null).show();
	}

	private void executeSingleFirewallOperation(final FrpProfile profile, final String operation) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View logView = inflater.inflate(R.layout.dialog_log_viewer, null);
		final TextView logTextView = logView.findViewById(R.id.log_text_view);
		final ScrollView logScrollView = logView.findViewById(R.id.log_scroll_view);
		final Button copyButton = logView.findViewById(R.id.btn_copy_log);
		final StringBuilder logBuilder = new StringBuilder();

		String opName = "allow".equals(operation) ? "放行" : "阻止";
		AlertDialog logDialog = new MaterialAlertDialogBuilder(this).setView(logView)
			.setTitle(opName + "端口 " + profile.getRemotePort() + "/" + profile.getProtocol().toUpperCase() + "...")
			.setCancelable(false).setPositiveButton("关闭", null).create();

		logDialog.show();
		final Button closeButton = logDialog.getButton(AlertDialog.BUTTON_POSITIVE);
		closeButton.setEnabled(false);

		copyButton.setOnClickListener(v -> {
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText("FRP Manager Log", logBuilder.toString());
			clipboard.setPrimaryClip(clip);
			Toast.makeText(FrpManagerActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
		});

		executor.execute(() -> {
			StringBuilder commandBuilder = new StringBuilder("set -e; ");
			String actionCommand;
			if ("allow".equals(operation)) {
				actionCommand = firewallManager.addPortRuleCommand(profile.getRemotePort(), profile.getProtocol());
			} else {
				actionCommand = firewallManager.removePortRuleCommand(profile.getRemotePort(), profile.getProtocol());
			}
			commandBuilder.append(actionCommand).append("; ");
			commandBuilder.append(firewallManager.reloadFirewallCommand()).append(";");

			String finalCommand = commandBuilder.toString();

			try {
				appendLog(logTextView, logScrollView, logBuilder, "防火墙类型: " + firewallManager.getType(), "info");
				appendLog(logTextView, logScrollView, logBuilder,
						 "目标端口: " + profile.getRemotePort() + "/" + profile.getProtocol(), "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");
				appendLog(logTextView, logScrollView, logBuilder, "组合的服务器命令:\n" + finalCommand, "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

				String result = sshManager.executeCommand(finalCommand, 60);

				appendLog(logTextView, logScrollView, logBuilder, "\n服务器返回信息:", "success");
				appendLog(logTextView, logScrollView, logBuilder, result.isEmpty() ? "(无输出)" : result, "success");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------",
						 "success");
				appendLog(logTextView, logScrollView, logBuilder, "\n操作完成!", "success");
			} catch (IOException e) {
				appendLog(logTextView, logScrollView, logBuilder, "\n\n###### 操作失败! ######", "error");
				appendLog(logTextView, logScrollView, logBuilder, "错误详情: " + e.getMessage(), "error");
			} finally {
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					closeButton.setEnabled(true);
					loadFrpProfilesFromServer();
				});
			}
		});
	}

	private void showBatchFirewallOperationDialog(final String operationType) {
		String title = "allow".equals(operationType) ? "批量放行防火墙端口" : "批量阻止防火墙端口";
		String buttonText = "allow".equals(operationType) ? "全部放行" : "全部阻止";

		LayoutInflater inflater = this.getLayoutInflater();
		View dialogView = inflater.inflate(R.layout.dialog_material_input, null);
		final TextInputEditText input = dialogView.findViewById(R.id.text_input_edit_text);
		input.setHint("输入端口范围 (如 1000-2000) 或 all");

		new MaterialAlertDialogBuilder(this).setTitle(title).setView(dialogView)
			.setPositiveButton(buttonText, (dialog, which) -> {
			String range = input.getText().toString().trim();
			if (range.isEmpty()) {
				Toast.makeText(FrpManagerActivity.this, "请输入端口范围或 'all'", Toast.LENGTH_SHORT).show();
				return;
			}
			String op = "allow".equals(operationType) ? "firewall-allow" : "firewall-block";
			List<FrpProfile> targetProfiles = parseRangeToProfiles(range, op);
			if (targetProfiles != null && !targetProfiles.isEmpty()) {
				confirmAndExecuteBatchFirewallOperation(operationType, targetProfiles);
			} else if (targetProfiles != null) {
				Toast.makeText(this, "在指定范围内未找到可操作的端口", Toast.LENGTH_SHORT).show();
			}
		}).setNegativeButton("取消", null).show();
	}

	private void confirmAndExecuteBatchFirewallOperation(String operationType, List<FrpProfile> targets) {
		if (targets == null || targets.isEmpty()) {
			Toast.makeText(this, "没有需要操作的项", Toast.LENGTH_SHORT).show();
			return;
		}

		String title = "allow".equals(operationType)
			? "确认放行以下 " + targets.size() + " 个端口？"
			: "确认阻止以下 " + targets.size() + " 个端口？";
		String positiveButtonText = "allow".equals(operationType) ? "全部放行" : "全部阻止";

		ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
															 targets.stream()
															 .map(p -> String.format("端口: %d/%s%s", p.getRemotePort(), p.getProtocol().toUpperCase(),
																					 (p.getTag() != null && !p.getTag().isEmpty() ? " (" + p.getTag() + ")" : "")))
			.collect(Collectors.toList()));

		new MaterialAlertDialogBuilder(this).setTitle(title).setAdapter(arrayAdapter, null)
			.setNegativeButton("取消", null).setPositiveButton(positiveButtonText, (d, w) -> {
			executeBatchFirewallOperation(operationType, targets);
		}).show();
	}

	private void executeBatchFirewallOperation(final String operationType, final List<FrpProfile> targets) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View logView = inflater.inflate(R.layout.dialog_log_viewer, null);
		final TextView logTextView = logView.findViewById(R.id.log_text_view);
		final ScrollView logScrollView = logView.findViewById(R.id.log_scroll_view);
		final Button copyButton = logView.findViewById(R.id.btn_copy_log);
		final StringBuilder logBuilder = new StringBuilder();

		AlertDialog logDialog = new MaterialAlertDialogBuilder(this).setView(logView)
			.setTitle(getOperationName(operationType) + "执行中...").setCancelable(false).setPositiveButton("关闭", null)
			.create();

		logDialog.show();
		final Button closeButton = logDialog.getButton(AlertDialog.BUTTON_POSITIVE);
		closeButton.setEnabled(false);

		copyButton.setOnClickListener(v -> {
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText("FRP Manager Log", logBuilder.toString());
			clipboard.setPrimaryClip(clip);
			Toast.makeText(FrpManagerActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
		});

		executor.execute(() -> {
			StringBuilder commandBuilder = new StringBuilder("set -e; ");
			appendLog(logTextView, logScrollView, logBuilder,
					 "开始执行 " + targets.size() + " 个批量防火墙操作 [" + getOperationName(operationType) + "]...", "info");
			appendLog(logTextView, logScrollView, logBuilder, "防火墙类型: " + firewallManager.getType(), "info");
			appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

			for (FrpProfile p : targets) {
				String firewallCommand;
				if ("allow".equals(operationType)) {
					firewallCommand = firewallManager.addPortRuleCommand(p.getRemotePort(), p.getProtocol());
				} else {
					firewallCommand = firewallManager.removePortRuleCommand(p.getRemotePort(), p.getProtocol());
				}
				appendLog(logTextView, logScrollView, logBuilder, "-> " + firewallCommand, "info");
				commandBuilder.append(firewallCommand).append("; ");
			}

			String reloadCommand = firewallManager.reloadFirewallCommand();
			appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 添加防火墙重载命令...", "info");
			appendLog(logTextView, logScrollView, logBuilder, "-> " + reloadCommand, "info");
			commandBuilder.append(reloadCommand).append(";");

			final String finalCommand = commandBuilder.toString();

			try {
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");
				appendLog(logTextView, logScrollView, logBuilder, "最终组合的服务器命令:\n" + finalCommand, "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

				appendLog(logTextView, logScrollView, logBuilder, "正在发送命令至服务器...", "info");
				String result = sshManager.executeCommand(finalCommand, 180);

				appendLog(logTextView, logScrollView, logBuilder, "\n服务器返回信息:", "success");
				appendLog(logTextView, logScrollView, logBuilder, result.isEmpty() ? "(无输出)" : result, "success");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------",
						 "success");
				appendLog(logTextView, logScrollView, logBuilder, "\n" + getOperationName(operationType) + " 命令已完成！",
						 "success");

			} catch (IOException e) {
				appendLog(logTextView, logScrollView, logBuilder, "\n\n###### 操作失败! ######", "error");
				appendLog(logTextView, logScrollView, logBuilder, "错误详情: " + e.getMessage(), "error");
				e.printStackTrace();
			} finally {
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					closeButton.setEnabled(true);
					loadFrpProfilesFromServer();
					exitSelectionMode();
				});
			}
		});
	}

	private void filterByText(String query) {
		this.currentSearchQuery = query;
		applyFilters();
	}

	private void applyFilters() {
		List<FrpProfile> filteredList = new ArrayList<>(allProfiles);

		if (!currentStatusFilter.equals("all")) {
			if (currentStatusFilter.equals("running")) {
				filteredList.removeIf(p -> !"运行中".equals(p.getStatus()));
			} else {
				filteredList.removeIf(p -> "运行中".equals(p.getStatus()));
			}
		}

		if (!currentProtocolFilter.equals("all")) {
			filteredList.removeIf(p -> !currentProtocolFilter.equalsIgnoreCase(p.getProtocol()));
		}

		if (!currentFirewallFilter.equals("all")) {
			if (currentFirewallFilter.equals("allowed")) {
				filteredList.removeIf(p -> !"已放行".equals(p.getFirewallStatus()));
			} else {
				filteredList.removeIf(p -> "已放行".equals(p.getFirewallStatus()));
			}
		}

		String lowerCaseQuery = currentSearchQuery.toLowerCase().trim();
		if (!lowerCaseQuery.isEmpty()) {
			filteredList.removeIf(profile -> !(String.valueOf(profile.getRemotePort()).contains(lowerCaseQuery)
				|| (profile.getTag() != null && profile.getTag().toLowerCase().contains(lowerCaseQuery))
				|| (profile.getLocalIp() != null && profile.getLocalIp().toLowerCase().contains(lowerCaseQuery))
				|| String.valueOf(profile.getLocalPort()).contains(lowerCaseQuery)));
		}

		Log.d(TAG, "Filtering complete. Final list size: " + filteredList.size());
		adapter.updateData(filteredList);
		updateFilterChip();
		checkEmptyState();
	}

	private void enterSelectionMode() {
		isInSelectionMode = true;
		adapter.enterSelectionMode();
		fabAddPort.hide();
		batchActionsLayout.setVisibility(View.VISIBLE);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setTitle("已选择 0 项");
			getSupportActionBar().setDisplayHomeAsUpEnabled(false);
		}
		invalidateOptionsMenu();
		onSelectionChanged(0);
	}

	private void exitSelectionMode() {
		isInSelectionMode = false;
		adapter.exitSelectionMode();
		fabAddPort.show();
		batchActionsLayout.setVisibility(View.GONE);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
			getSupportActionBar().setTitle("FRP 端口管理");
		}
		invalidateOptionsMenu();
	}

	private ArrayAdapter<String> createDisplayListAdapter(List<FrpProfile> profiles) {
		List<String> displayItems = new ArrayList<>();
		for (FrpProfile p : profiles) {
			displayItems.add("端口: " + p.getRemotePort() + "/" + p.getProtocol().toUpperCase()
							 + (p.getTag() != null && !p.getTag().isEmpty() ? " (" + p.getTag() + ")" : ""));
		}
		return new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, displayItems);
	}

	private void applyAllPendingChanges() {
		List<FrpProfile> modifiedProfiles = new ArrayList<>(pendingChangesProfiles);
		if (modifiedProfiles.isEmpty()) {
			Toast.makeText(this, "没有待应用的更改。", Toast.LENGTH_SHORT).show();
			return;
		}
		ArrayAdapter<String> arrayAdapter = createDisplayListAdapter(modifiedProfiles);
		new MaterialAlertDialogBuilder(this).setTitle("应用以下 " + modifiedProfiles.size() + " 个更改？")
			.setAdapter(arrayAdapter, null).setPositiveButton("应用", (dialog, which) -> {
			String message = String.format("此操作将应用 %d 个配置更改到服务器。\n\n更改不会自动生效，您需要稍后手动启动或重启相关服务。\n\n您确定要继续吗？",
										 modifiedProfiles.size());
			new MaterialAlertDialogBuilder(FrpManagerActivity.this).setTitle("确认应用更改").setMessage(message)
				.setPositiveButton("确定应用", (d, w) -> applyChangesOnServer(modifiedProfiles))
			.setNegativeButton("取消", null).show();
		}).setNegativeButton("取消", null).show();
	}

	private void loadFrpProfilesFromServer() {
		if (adapter == null) {
			Log.e(TAG, "loadFrpProfilesFromServer called but adapter is null. Aborting.");
			return;
		}
		final Map<String, Boolean> selectionState = adapter.getSelectionStateByProfileKey();
		final List<FrpProfile> currentProfiles = new ArrayList<>(this.allProfiles);

		executor.execute(() -> {
			final String SEPARATOR = "---FRP_MANAGER_SEPARATOR---";
			StringBuilder commandBuilder = new StringBuilder();

			commandBuilder.append("echo '---CONFIGS---' && ").append(
				"find /etc/frp/conf.d/ -name 'port_*.ini' -type f -exec sh -c 'echo \"{}\"; sudo cat \"{}\"; echo \"---FILE_END---\"' \\;");

			commandBuilder.append(" && echo '").append(SEPARATOR).append("' && echo '---RUNNING_SERVICES---' && ")
				.append("systemctl list-units --type=service --state=running 'frpc@*.service' --no-pager | awk '{print $1}'; ");
			commandBuilder.append("echo '").append(SEPARATOR).append("' && echo '---ENABLED_STATUS---' && ")
				.append("systemctl list-unit-files 'frpc@*.service' --no-pager | awk '{print $1,$2}';");
			commandBuilder.append("echo '").append(SEPARATOR).append("' && echo '---FIREWALL_RULES---' && ");
			commandBuilder.append("FW_TYPE=$(if systemctl is-active --quiet firewalld; then echo 'firewalld'; ").append(
				"elif command -v ufw >/dev/null && ufw status | grep -q 'Status: active'; then echo 'ufw'; ")
				.append("elif command -v iptables >/dev/null; then echo 'iptables'; else echo 'none'; fi); ")
				.append("echo \"$FW_TYPE\"; echo '---FW_SEP---'; ").append("case $FW_TYPE in ")
				.append("'firewalld') firewall-cmd --list-ports ;; ")
				.append("'ufw') ufw status verbose | grep -w 'ALLOW' ;; ")
				.append("'iptables') iptables -S INPUT | grep -- '-j ACCEPT' ;; ").append("esac;");

			String command = commandBuilder.toString();
			Log.d(TAG, "Executing SSH command: " + command);

			try {
				String result = sshManager.executeCommand(command, 45);
				Log.d(TAG, "========= RAW SERVER OUTPUT START =========\n" + result
					  + "\n========= RAW SERVER OUTPUT END =========");
				final List<FrpProfile> profiles = parseAndProcessServerResult(result, selectionState,
																			  currentProfiles);
				Log.d(TAG, "Parsing complete. Number of profiles loaded: " + profiles.size());
				runOnUiThread(() -> {
					allProfiles.clear();
					allProfiles.addAll(profiles);
					Collections.sort(allProfiles,
									 Comparator.comparingInt(FrpProfile::getRemotePort).thenComparing(FrpProfile::getProtocol));

					Log.d(TAG, "Updating UI with " + allProfiles.size() + " profiles.");
					applyFilters(); 
					hideLoadingDialog();
					updatePendingChangesMenu();
				});

			} catch (final Exception e) {
				e.printStackTrace();
				Log.e(TAG, "Failed to load profiles from server.", e);
				runOnUiThread(() -> {
					hideLoadingDialog();
					showErrorDialog("加载失败", "无法连接到服务器或连接已断开。\n\n错误: " + e.getMessage());
					checkEmptyState();
				});
			}
		});
	}

	private List<FrpProfile> parseAndProcessServerResult(String result, Map<String, Boolean> selectionState,
														 List<FrpProfile> oldProfiles) throws IOException {
		final String SEPARATOR = "---FRP_MANAGER_SEPARATOR---";
		String[] sections = result.split(SEPARATOR);
		if (sections.length < 3) {
			Log.e(TAG, "Incomplete server data. Only " + sections.length + " sections found. Raw output: " + result);
			throw new IOException("从服务器返回的数据格式不完整");
		}

		List<FrpProfile> profilesFromConfig = parseProfilesFromConfigSection(sections[0]);
		Log.d(TAG, "Parsed " + profilesFromConfig.size() + " profiles from config section.");
		String runningServices = sections[1];
		String enabledStatuses = sections[2];

		Map<String, FrpProfile> masterProfileMap = new HashMap<>();
		for (FrpProfile serverProfile : profilesFromConfig) {
			masterProfileMap.put(getProfileLockKey(serverProfile), serverProfile);
		}

		Set<String> openFirewallPorts = new HashSet<>();
		if (sections.length > 3) {
			String firewallSection = sections[3].replace("---FIREWALL_RULES---", "").trim();
			openFirewallPorts = parseFirewallRules(firewallSection);
		}

		for (FrpProfile profile : masterProfileMap.values()) {
			int port = profile.getRemotePort();
			String protocol = profile.getProtocol().toLowerCase();
			String serviceName = String.format("frpc@%d_%s.service", port, protocol);
			String legacyServiceName = String.format("frpc@%d.service", port);

			if (runningServices.contains(serviceName) || runningServices.contains(legacyServiceName)) {
				profile.setStatus("运行中");
			} else if (enabledStatuses.contains(serviceName + "\tenabled")
					 || enabledStatuses.contains(legacyServiceName + "\tenabled")) {
				profile.setStatus("已停止");
			} else if (enabledStatuses.contains(serviceName + "\tdisabled")
					 || enabledStatuses.contains(legacyServiceName + "\tdisabled")) {
				profile.setStatus("已禁用");
			} else {
				profile.setStatus("已停止");
			}

			profile.setSelected(selectionState.getOrDefault(getProfileLockKey(profile), false));
			profile.setModified(false);

			String portRule = String.format("%d/%s", profile.getRemotePort(), protocol);
			if (openFirewallPorts.contains(portRule)) {
				profile.setFirewallStatus("已放行");
			} else {
				profile.setFirewallStatus("未放行");
			}
		}

		for (FrpProfile pending : pendingChangesProfiles) {
			pending.setModified(true);
			masterProfileMap.put(getProfileLockKey(pending), pending);
		}

		Map<String, FrpProfile> oldProfilesMap = oldProfiles.stream()
			.collect(Collectors.toMap(this::getProfileLockKey, p -> p, (a, b) -> b));

		for (String lockedKey : lockedProfiles) {
			if (masterProfileMap.containsKey(lockedKey) && oldProfilesMap.containsKey(lockedKey)) {
				masterProfileMap.get(lockedKey).setStatus(oldProfilesMap.get(lockedKey).getStatus());
			}
		}

		return new ArrayList<>(masterProfileMap.values());
	}

	private Set<String> parseFirewallRules(String firewallOutput) {
		Set<String> openPorts = new HashSet<>();
		String[] parts = firewallOutput.split("---FW_SEP---");
		if (parts.length < 2) {
			Log.e(TAG, "Firewall output missing FW_SEP.");
			return openPorts;
		}

		String firewallType = parts[0].trim();
		String rulesContent = parts[1].trim();

		Pattern portPattern;

		switch (firewallType) {
			case "firewalld" :
				portPattern = Pattern.compile("(\\d+/(?:tcp|udp))");
				Matcher firewalldMatcher = portPattern.matcher(rulesContent);
				while (firewalldMatcher.find()) {
					openPorts.add(firewalldMatcher.group(1));
				}
				break;
			case "ufw" :
				portPattern = Pattern.compile("(\\d+)(?:,\\d+)*\\/(tcp|udp)");
				for (String line : rulesContent.split("\n")) {
					Matcher ufwMatcher = portPattern.matcher(line);
					while (ufwMatcher.find()) {
						String portStr = ufwMatcher.group(1);
						String protocol = ufwMatcher.group(2);
						if (portStr.contains(",")) {
							for (String p : portStr.split(",")) {
								openPorts.add(p.trim() + "/" + protocol);
							}
						} else {
							openPorts.add(portStr + "/" + protocol);
						}
					}
				}
				break;
			case "iptables" :
				portPattern = Pattern.compile("--dport (\\d+)");
				Pattern protocolPattern = Pattern.compile("-p (tcp|udp)");
				for (String line : rulesContent.split("\n")) {
					if (!line.contains("-j ACCEPT"))
						continue;
					Matcher iptablesPortMatcher = portPattern.matcher(line);
					if (iptablesPortMatcher.find()) {
						String port = iptablesPortMatcher.group(1);
						Matcher iptablesProtocolMatcher = protocolPattern.matcher(line);
						String protocol = "tcp";
						if (iptablesProtocolMatcher.find()) {
							protocol = iptablesProtocolMatcher.group(1);
						}
						openPorts.add(port + "/" + protocol);
					}
				}
				break;
			case "none" :
			default :
				break;
		}
		return openPorts;
	}

	private List<FrpProfile> parseProfilesFromConfigSection(String configSection) {
		List<FrpProfile> profiles = new ArrayList<>();

		String cleanConfigSection = configSection.replace("---CONFIGS---", "").trim();
		String[] fileParts = cleanConfigSection.split("---FILE_END---");

		Pattern fileNamePattern = Pattern.compile("port_(\\d+)(?:_(\\w+))?\\.ini$");

		for (String part : fileParts) {
			String trimmedPart = part.trim();

			if (trimmedPart.isEmpty()) {
				continue;
			}

			String[] lines = trimmedPart.split("\n", 2);
			if (lines.length < 2) {
				Log.w(TAG, "Skipping invalid config part (not enough lines): " + trimmedPart);
				continue;
			}

			String filePath = lines[0].trim();
			if (filePath.endsWith(".ini.disabled")) {
				Log.d(TAG, "Skipping disabled config file: " + filePath);
				continue;
			}

			Matcher matcher = fileNamePattern.matcher(filePath);
			if (matcher.find()) {
				String content = lines[1];
				if (content.trim().isEmpty()) {
					Log.w(TAG, "Skipping empty config file: " + filePath);
					continue;
				}
				FrpProfile profile = parseFrpProfileFromContent(content);
				if (profile != null) {
					profile.setRemotePort(Integer.parseInt(matcher.group(1)));

					String protocolFromFile = matcher.group(2);

					if (protocolFromFile != null && !protocolFromFile.isEmpty()) {
						profile.setProtocol(protocolFromFile.toLowerCase());
					} else {
						String protocolFromContent = parseValue(content, "type");
						if (!protocolFromContent.isEmpty()) {
							profile.setProtocol(protocolFromContent.toLowerCase());
						} else {
							profile.setProtocol("tcp");
						}
					}
					Log.d(TAG, "Successfully parsed profile: " + getProfileLockKey(profile));
					profiles.add(profile);
				} else {
					Log.e(TAG, "Failed to parse content for file: " + filePath);
				}
			} else {
				Log.w(TAG, "Filename does not match pattern: " + filePath);
			}
		}
		return profiles;
	}

	private FrpProfile parseFrpProfileFromContent(String content) {
		try {
			FrpProfile profile = new FrpProfile();

			String serverAddr = parseValue(content, "server_addr");
			if (!serverAddr.isEmpty())
				profile.setServerAddr(serverAddr);

			String serverPortStr = parseValue(content, "server_port");
			if (!serverPortStr.isEmpty())
				profile.setServerPort(Integer.parseInt(serverPortStr));

			String token = parseValue(content, "token");
			if (!token.isEmpty())
				profile.setToken(token);

			String remotePortContent = parseValue(content, "remote_port");
			if (!remotePortContent.isEmpty())
				profile.setRemotePort(Integer.parseInt(remotePortContent));

			profile.setLocalIp(parseValue(content, "local_ip"));

			String localPortContent = parseValue(content, "local_port");
			if (!localPortContent.isEmpty())
				profile.setLocalPort(Integer.parseInt(localPortContent));

			String protocolContent = parseValue(content, "type");
			if (!protocolContent.isEmpty())
				profile.setProtocol(protocolContent);

			String tag = parseValue(content, "#\\s*tag");
			if (!tag.isEmpty()) {
				tag = tag.replaceFirst("=", "").trim();
			}
			profile.setTag(tag);

			String proxyVersion = parseValue(content, "proxy_protocol_version");
			if (!proxyVersion.isEmpty()) {
				profile.setProxyProtocolVersion(proxyVersion);
			}
			return profile;
		} catch (Exception e) {
			Log.e(TAG, "Exception while parsing profile content: " + content, e);
			return null;
		}
	}

	private String parseValue(String content, String key) {
		Pattern pattern = Pattern.compile("^\\s*" + key + "\\s*=\\s*(.*)", Pattern.MULTILINE);
		Matcher matcher = pattern.matcher(content);
		if (matcher.find()) {
			return matcher.group(1).trim();
		}
		return "";
	}

	private void showConfigFileDialog(final FrpProfile profile) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View dialogView = inflater.inflate(R.layout.dialog_log_viewer, null);
		final TextView contentTextView = dialogView.findViewById(R.id.log_text_view);
		final ScrollView scrollView = dialogView.findViewById(R.id.log_scroll_view);
		final Button copyButton = dialogView.findViewById(R.id.btn_copy_log);

		contentTextView.setTextIsSelectable(true);
		contentTextView.setMovementMethod(ScrollingMovementMethod.getInstance());
		contentTextView.setText("正在从服务器加载配置文件...");

		final AlertDialog configDialog = new MaterialAlertDialogBuilder(this)
			.setTitle("配置文件: port_" + profile.getRemotePort() + "_" + profile.getProtocol()).setView(dialogView)
			.setPositiveButton("关闭", null).create();

		configDialog.show();
		copyButton.setEnabled(false);

		copyButton.setOnClickListener(v -> {
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText(
				"FRP Config port_" + profile.getRemotePort() + "_" + profile.getProtocol(),
				contentTextView.getText());
			clipboard.setPrimaryClip(clip);
			Toast.makeText(FrpManagerActivity.this, "配置内容已复制到剪贴板", Toast.LENGTH_SHORT).show();
		});

		executor.execute(() -> {
			try {
				String protocol = profile.getProtocol().toLowerCase();
				String command = String.format(Locale.US,
											 "sudo cat /etc/frp/conf.d/port_%d_%s.ini 2>/dev/null || "
											 + "sudo cat /etc/frp/conf.d/port_%d.ini 2>/dev/null || "
											 + "sudo cat /etc/frp/conf.d/port_%d_%s.ini.disabled 2>/dev/null || "
											 + "sudo cat /etc/frp/conf.d/port_%d.ini.disabled 2>/dev/null || "
											 + "echo '错误：在服务器上未找到对应的配置文件。'",
											 profile.getRemotePort(), protocol, profile.getRemotePort(), profile.getRemotePort(), protocol,
											 profile.getRemotePort());

				final String fileContent = sshManager.executeCommand(command, 15);

				runOnUiThread(() -> {
					contentTextView.setText(fileContent.trim().isEmpty() ? "配置文件为空或读取失败。" : fileContent);
					copyButton.setEnabled(!fileContent.trim().isEmpty());
					scrollView.post(() -> scrollView.fullScroll(View.FOCUS_UP));
				});
			} catch (IOException e) {
				runOnUiThread(() -> {
					contentTextView.setText("加载配置文件失败:\n" + e.getMessage());
					copyButton.setEnabled(false);
				});
			}
		});
	}

	private void showExitConfirmationDialog(List<FrpProfile> modifiedProfiles) {
		ArrayAdapter<String> arrayAdapter = createDisplayListAdapter(modifiedProfiles);

		new MaterialAlertDialogBuilder(this).setTitle("您有 " + modifiedProfiles.size() + " 个未应用的更改")
			.setAdapter(arrayAdapter, null).setPositiveButton("立即应用", (dialog, which) -> applyAllPendingChanges())
		.setNegativeButton("放弃并退出", (dialog, which) -> {
			pendingChangesProfiles.clear();
			updatePendingChangesMenu();
			finish();
		}).setNeutralButton("取消", null).show();
	}
}
