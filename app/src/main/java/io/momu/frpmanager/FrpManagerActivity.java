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
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import android.content.ClipboardManager;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import io.momu.frpmanager.R;

public class FrpManagerActivity extends AppCompatActivity implements FrpProfileAdapter.OnProfileClickListener {

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

	private final Set<Integer> lockedPorts = Collections.synchronizedSet(new HashSet<>());
	private final AtomicBoolean isOperationCancelled = new AtomicBoolean(false);

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
			sshManager = new SshManager(
				settingsManager.getHost(),
				settingsManager.getPort(),
				settingsManager.getUsername(),
				settingsManager.getPassword()
			);
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
				if (!isInSelectionMode && lockedPorts.isEmpty()) {
					loadFrpProfilesFromServer();
				}
				autoRefreshHandler.postDelayed(this, 5000);
			}
		};
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
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

		if (!loadingDialog.isShowing()) {
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
				String detectCommand = "if systemctl is-active --quiet firewalld; then echo \"firewalld\"; " +
					"elif command -v ufw >/dev/null && ufw status | grep -q \"Status: active\"; then echo \"ufw\"; " +
					"elif command -v iptables >/dev/null; then echo \"iptables\"; " +
					"else echo \"none\"; fi";

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
		if(chipGroupProtocolFilter != null) {
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
		if(chipGroupFirewallFilter != null) {
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
		// FIX: For dark mode UI issues with pending cards, ensure your FrpProfileAdapter
		// uses a color selector (e.g., `status_pending_background.xml`) that defines
		// distinct colors for light and dark themes.
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
				|| !currentStatusFilter.equals("all")
				|| !currentProtocolFilter.equals("all")
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
		if (batchFirewallAllow != null) batchFirewallAllow.setVisible(isVisible && isFirewallReady);
		MenuItem batchFirewallBlock = menu.findItem(R.id.action_batch_firewall_block);
		if (batchFirewallBlock != null) batchFirewallBlock.setVisible(isVisible && isFirewallReady);

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
		}
		else if (itemId == R.id.action_batch_firewall_allow) {
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
		if (isInSelectionMode) {
			adapter.toggleSelection(profile);
			onSelectionChanged(adapter.getSelectedItemsCount());
			return;
		}

		if (lockedPorts.contains(profile.getRemotePort())) {
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
		new MaterialAlertDialogBuilder(this).setTitle("操作端口: " + profile.getRemotePort())
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
				case "防火墙操作":
					showFirewallOperationDialog(profile);
					break;
			}
		}).show();
	}

	@Override
	public void onProfileLongClick(FrpProfile profile) {
		if (!isInSelectionMode) {
			enterSelectionMode();
			adapter.toggleSelection(profile);
			onSelectionChanged(adapter.getSelectedItemsCount());
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

			boolean isFirewallReady = firewallManager != null && settingsManager.isFirewallManaged() && selectedCount > 0;
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
		for (int i = 0; i < allProfiles.size(); i++) {
			if (allProfiles.get(i).getRemotePort() == profile.getRemotePort()) {
				allProfiles.get(i).setStatus(status);
				adapter.updateSingleItem(allProfiles.get(i));
				return;
			}
		}
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
		final RadioGroup protocolGroup = dialogView.findViewById(R.id.radiogroup_protocol);
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
			// FIX: Populate with profile's specific values, not global defaults.
			serverAddrInput.setText(existingProfile.getServerAddr());
			serverPortInput.setText(existingProfile.getServerPort() > 0 ? String.valueOf(existingProfile.getServerPort()) : "");
			tokenInput.setText(existingProfile.getToken());

			remotePortInput.setText(String.valueOf(existingProfile.getRemotePort()));
			remotePortInput.setEnabled(false);
			remotePortLayout.setHelperText("编辑模式下远程端口不可更改");
			localIpInput.setText(existingProfile.getLocalIp());
			localPortInput.setText(String.valueOf(existingProfile.getLocalPort()));
			tagInput.setText(existingProfile.getTag());
			if ("udp".equalsIgnoreCase(existingProfile.getProtocol())) {
				protocolGroup.check(R.id.radio_udp);
			} else {
				protocolGroup.check(R.id.radio_tcp);
			}
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

			protocolGroup.check(R.id.radio_tcp);
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
				if (serverPort <= 0 || serverPort > 65535) throw new NumberFormatException();
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

			FrpProfile newProfile = new FrpProfile();
			newProfile.setServerAddr(serverAddr);
			newProfile.setServerPort(serverPort);
			if (!token.isEmpty()) {
				newProfile.setToken(token);
			}

			if (existingProfile == null) {
				for (FrpProfile p : allProfiles) {
					if (p.getRemotePort() == remotePort) {
						Toast.makeText(FrpManagerActivity.this, "远程端口 " + remotePort + " 已存在，请选择其他端口",
									 Toast.LENGTH_SHORT).show();
						return;
					}
				}
				newProfile.setRemotePort(remotePort);
				newProfile.setStatus("已停止 (待应用)");
			} else {
				newProfile.setRemotePort(existingProfile.getRemotePort());
				newProfile.setStatus(existingProfile.getStatus());
			}

			newProfile.setLocalIp(localIp);
			newProfile.setLocalPort(localPort);
			newProfile.setTag(tagInput.getText().toString());
			newProfile.setProtocol(protocolGroup.getCheckedRadioButtonId() == R.id.radio_udp ? "udp" : "tcp");
			if (proxySwitch.isChecked()) {
				newProfile.setProxyProtocolVersion((String) proxyVersionSpinner.getSelectedItem());
			} else {
				newProfile.setProxyProtocolVersion(null);
			}
			newProfile.setModified(true);

			if (existingProfile != null) {
				boolean protocolChanged = !Objects.equals(newProfile.getProtocol(), existingProfile.getProtocol());

				if (newProfile.hasFunctionalChanges(existingProfile) || protocolChanged) {
					showSaveAndRestartDialog(newProfile, existingProfile.getStatus());
				} else {
					applySingleProfileChange(newProfile, false);
					Toast.makeText(FrpManagerActivity.this, "备注已更新", Toast.LENGTH_SHORT).show();
				}
			} else {
				addProfileToPendingList(newProfile, true);
			}
		}).setNegativeButton("取消", null).show();
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
		if (domain == null || domain.isEmpty()){
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

	// FIX: Prevents duplicate cards when adding/modifying profiles.
	private void addProfileToPendingList(FrpProfile profileToAdd, boolean isNew) {
		profileToAdd.setModified(true);

		pendingChangesProfiles.removeIf(p -> p.getRemotePort() == profileToAdd.getRemotePort());
		pendingChangesProfiles.add(profileToAdd);

		int existingIndex = -1;
		for (int i = 0; i < allProfiles.size(); i++) {
			if (allProfiles.get(i).getRemotePort() == profileToAdd.getRemotePort()) {
				existingIndex = i;
				break;
			}
		}

		if (existingIndex != -1) {
			allProfiles.set(existingIndex, profileToAdd);
		} else {
			allProfiles.add(profileToAdd);
		}

		Collections.sort(allProfiles, Comparator.comparingInt(FrpProfile::getRemotePort));
		applyFilters();
		updatePendingChangesMenu();
	}


	/**
	 * Applies changes for a single profile to the server and shows a log dialog for feedback.
	 *
	 * @param profile The profile with modifications to apply.
	 * @param restart Whether to restart the service after applying the changes.
	 */
	private void applySingleProfileChange(final FrpProfile profile, final boolean restart) {
		LayoutInflater inflater = LayoutInflater.from(this);
		View logView = inflater.inflate(R.layout.dialog_log_viewer, null);
		final TextView logTextView = logView.findViewById(R.id.log_text_view);
		final ScrollView logScrollView = logView.findViewById(R.id.log_scroll_view);
		final Button copyButton = logView.findViewById(R.id.btn_copy_log);
		final StringBuilder logBuilder = new StringBuilder();

		String title = "应用端口 " + profile.getRemotePort() + " 的更改...";
		AlertDialog logDialog = new MaterialAlertDialogBuilder(this).setView(logView)
			.setTitle(title)
			.setCancelable(false)
			.setPositiveButton("关闭", null)
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

		final int port = profile.getRemotePort();
		lockedPorts.add(port);

		executor.execute(() -> {
			boolean hasError = false;
			try {
				appendLog(logTextView, logScrollView, logBuilder, "准备为端口 " + port + " 应用更改...", "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

				final String fileContent = buildFileContent(profile);
				StringBuilder commandBuilder = new StringBuilder("set -e; ");
				boolean manageFirewall = settingsManager.isFirewallManaged() && firewallManager != null;

				if (manageFirewall) {
					appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 正在为端口 " + port + " 准备更新规则...", "info");
					String removeTcpCmd = firewallManager.removePortRuleCommand(port, "tcp").replace(" || true", "") + " || true";
					String removeUdpCmd = firewallManager.removePortRuleCommand(port, "udp").replace(" || true", "") + " || true";
					String addCmd = firewallManager.addPortRuleCommand(port, profile.getProtocol());
					commandBuilder.append(removeTcpCmd).append("; ").append(removeUdpCmd).append("; ").append(addCmd).append("; ");
				}

				appendLog(logTextView, logScrollView, logBuilder, "正在生成文件写入命令...", "info");
				commandBuilder.append(String.format(Locale.US,
													"sudo rm -f /etc/frp/conf.d/port_%d.ini.disabled; "
													+ "printf '%%s\\n' '%s' | sudo tee /etc/frp/conf.d/port_%d.ini > /dev/null",
													port, fileContent, port));
				if (restart) {
					appendLog(logTextView, logScrollView, logBuilder, "正在添加服务重启命令...", "info");
					commandBuilder.append(String.format("; sudo systemctl restart frpc@%d.service", port));
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

				pendingChangesProfiles.removeIf(p -> p.getRemotePort() == port);
				appendLog(logTextView, logScrollView, logBuilder, "\n更改已应用成功！", "success");
				if (!restart) {
					appendLog(logTextView, logScrollView, logBuilder, "请手动重启服务以使新配置生效。", "success");
				}

			} catch (IOException e) {
				hasError = true;
				appendLog(logTextView, logScrollView, logBuilder, "\n\n###### 操作失败! ######", "error");
				appendLog(logTextView, logScrollView, logBuilder, "错误详情: " + e.getMessage(), "error");
			} finally {
				lockedPorts.remove(port);
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


	// Generates FRP client configuration file content based on profile.
	private String buildFileContent(FrpProfile profile) {
		StringBuilder commonBuilder = new StringBuilder();
		commonBuilder.append("[common]\n");

		String serverAddr = profile.getServerAddr() != null ? profile.getServerAddr() : settingsManager.getFrpServerAddr();
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

	private void restartFrpcService() {
		Toast.makeText(this, "正在发送重启命令...", Toast.LENGTH_SHORT).show();
		executor.execute(() -> {
			try {
				sshManager.executeCommand("sudo systemctl restart frpc.service", 20);
				Thread.sleep(2000);

				runOnUiThread(() -> {
					Toast.makeText(FrpManagerActivity.this, "重启命令已发送！", Toast.LENGTH_SHORT).show();
					loadFrpProfilesFromServer();
				});
			} catch (IOException | InterruptedException e) {
				runOnUiThread(() -> Toast
					.makeText(FrpManagerActivity.this, "重启失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
			}
		});
	}

	private void showBatchCreateDialog() {
		LayoutInflater inflater = LayoutInflater.from(this);
		View dialogView = inflater.inflate(R.layout.dialog_batch_create, null);

		final EditText serverAddrInput = dialogView.findViewById(R.id.edit_text_server_addr);
		final EditText serverPortInput = dialogView.findViewById(R.id.edit_text_server_port);
		final EditText tokenInput = dialogView.findViewById(R.id.edit_text_token);
		final EditText remoteRangeInput = dialogView.findViewById(R.id.edit_text_remote_range);
		final EditText localIpInput = dialogView.findViewById(R.id.edit_text_local_ip);

		serverAddrInput.setText(settingsManager.getFrpServerAddr());
		serverPortInput.setText(String.valueOf(settingsManager.getFrpServerPort()));
		tokenInput.setText(settingsManager.getFrpToken());

		new MaterialAlertDialogBuilder(this).setView(dialogView).setTitle("批量创建端口")
			.setPositiveButton("创建", (dialog, which) -> {
			try {
				String serverAddr = serverAddrInput.getText().toString().trim();
				if (!isValidIpAddress(serverAddr) && !isValidDomain(serverAddr)) {
					throw new IllegalArgumentException("FRP 服务器地址格式不正确。");
				}
				int serverPort;
				try {
					serverPort = Integer.parseInt(serverPortInput.getText().toString());
					if (serverPort <= 0 || serverPort > 65535) throw new NumberFormatException();
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("FRP 服务器端口无效。");
				}
				String token = tokenInput.getText().toString().trim();

				String remoteRange = remoteRangeInput.getText().toString();
				String localIp = localIpInput.getText().toString();
				String[] parts = remoteRange.split("-");
				if (parts.length != 2)
					throw new IllegalArgumentException("范围格式错误，应为 '开始-结束'");

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
				Set<Integer> existingPorts = allProfiles.stream().map(FrpProfile::getRemotePort)
				.collect(Collectors.toSet());

				for (int port = remoteStart; port <= remoteEnd; port++) {
					if (!existingPorts.contains(port)) {
						FrpProfile p = new FrpProfile();
						p.setServerAddr(serverAddr);
						p.setServerPort(serverPort);
						if (!token.isEmpty()) p.setToken(token);

						p.setRemotePort(port);
						p.setLocalIp(localIp);
						p.setLocalPort(port);
						p.setProtocol("tcp");
						p.setTag("批量创建");
						p.setStatus("已停止 (待应用)");
						p.setModified(true);
						newProfiles.add(p);
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
		}).setNegativeButton("取消", null).show();
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
			case "firewall-allow":
			case "firewall-block":
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
															 .map(p -> "端口: " + p.getRemotePort()
															 + (p.getTag() != null && !p.getTag().isEmpty() ? " (" + p.getTag() + ")" : ""))
			.collect(Collectors.toList()));

		new MaterialAlertDialogBuilder(this).setTitle(title).setAdapter(arrayAdapter, null)
			.setNegativeButton("取消", null).setPositiveButton(positiveButtonText, (d, w) -> {
			executeBatchOperationWithLogDialog(operationType, targets);
		}).show();
	}

	// FIX: Added port conflict check for start/restart operations.
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

			if ("start".equals(operationType) || "restart".equals(operationType)) {
				appendLog(logTextView, logScrollView, logBuilder, "正在检查服务器端口占用情况...", "info");

				StringBuilder checkPortCmdBuilder = new StringBuilder();
				for (FrpProfile p : finalTargets) {
					// Use ss command for more accurate port check across all interfaces.
					String command = String.format(
						Locale.US,
						"if ss -tlpn | grep -qE '(\\*:|0\\.0\\.0\\.0:)%d' ; then echo 'IN_USE:%d'; else echo 'FREE:%d'; fi;",
						p.getRemotePort(), p.getRemotePort(), p.getRemotePort()
					);
					checkPortCmdBuilder.append(command);
				}

				try {
					String checkResult = sshManager.executeCommand(checkPortCmdBuilder.toString(), 30);
					Set<Integer> portsInUse = new HashSet<>();
					for (String line : checkResult.split("\n")) {
						if (line.startsWith("IN_USE:")) {
							portsInUse.add(Integer.parseInt(line.split(":")[1]));
						}
					}

					if (!portsInUse.isEmpty()) {
						appendLog(logTextView, logScrollView, logBuilder, "检测到端口冲突，以下端口将不会被启动：", "error");
						for (Integer port : portsInUse) {
							appendLog(logTextView, logScrollView, logBuilder, "-> 端口 " + port + " 已被占用。", "error");
						}
						finalTargets.removeIf(p -> portsInUse.contains(p.getRemotePort()));
					} else {
						appendLog(logTextView, logScrollView, logBuilder, "端口检查通过，所有目标端口均可用。", "success");
					}

				} catch (IOException e) {
					appendLog(logTextView, logScrollView, logBuilder, "端口检查失败: " + e.getMessage(), "error");
					runOnUiThread(() -> {
						logDialog.setTitle("操作中止");
						closeButton.setEnabled(true);
					});
					return;
				}
			}

			if (finalTargets.isEmpty()) {
				appendLog(logTextView, logScrollView, logBuilder, "没有可执行操作的目标。", "info");
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					closeButton.setEnabled(true);
					exitSelectionMode();
				});
				return;
			}

			boolean manageFirewall = settingsManager.isFirewallManaged() && firewallManager != null;
			StringBuilder commandBuilder = new StringBuilder("set -e; ");
			boolean firewallActionTaken = false;

			appendLog(logTextView, logScrollView, logBuilder,
					 "开始执行 " + finalTargets.size() + " 个批量操作 [" + getOperationName(operationType) + "]...", "info");
			appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

			if (manageFirewall) {
				appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 防火墙管理已启用，检测到类型: " + firewallManager.getType(), "info");
			} else if (settingsManager.isFirewallManaged()) {
				appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 防火墙管理已启用，但未在服务器上检测到支持的类型。", "error");
			}

			for (FrpProfile p : finalTargets) {
				String operationCommand = "";

				if (manageFirewall) {
					String firewallCommand = null;
					String logMessage = null;

					switch (operationType) {
						case "start":
							firewallCommand = firewallManager.addPortRuleCommand(p.getRemotePort(), p.getProtocol());
							logMessage = String.format("[防火墙日志] 为端口 %d/%s 生成添加规则命令...", p.getRemotePort(), p.getProtocol());
							firewallActionTaken = true;
							break;
						case "stop":
						case "delete":
							firewallCommand = firewallManager.removePortRuleCommand(p.getRemotePort(), p.getProtocol());
							logMessage = String.format("[防火墙日志] 为端口 %d/%s 生成删除规则命令...", p.getRemotePort(), p.getProtocol());
							firewallActionTaken = true;
							break;
						case "restart":
							break;
					}

					if (firewallCommand != null) {
						appendLog(logTextView, logScrollView, logBuilder, logMessage, "info");
						appendLog(logTextView, logScrollView, logBuilder, "-> " + firewallCommand, "info");
						commandBuilder.append(firewallCommand).append("; ");
					}
				}

				switch (operationType) {
					case "start":
						operationCommand = String.format(Locale.US, "sudo systemctl enable --now frpc@%d.service; ", p.getRemotePort());
						break;
					case "stop":
						operationCommand = String.format(Locale.US, "sudo systemctl disable --now frpc@%d.service; ", p.getRemotePort());
						break;
					case "restart":
						operationCommand = String.format(Locale.US, "sudo systemctl restart frpc@%d.service; ", p.getRemotePort());
						break;
					case "delete":
						operationCommand = String.format(Locale.US, "sudo systemctl disable --now frpc@%d.service >/dev/null 2>&1 || true; sudo rm -f /etc/frp/conf.d/port_%d.ini /etc/frp/conf.d/port_%d.ini.disabled; ", p.getRemotePort(), p.getRemotePort(), p.getRemotePort());
						break;
					default:
						continue;
				}
				commandBuilder.append(operationCommand);
			}

			if (manageFirewall && firewallActionTaken) {
				String reloadCommand = firewallManager.reloadFirewallCommand();
				appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 添加防火墙重载命令...", "info");
				appendLog(logTextView, logScrollView, logBuilder, "-> " + reloadCommand, "info");
				commandBuilder.append(reloadCommand).append(";");
			}

			final String finalCommand = commandBuilder.toString();

			finalTargets.forEach(p -> {
				lockedPorts.add(p.getRemotePort());
				String pendingStatus;
				switch (operationType) {
					case "start" :
						pendingStatus = "正在启动...";
						break;
					case "stop" :
						pendingStatus = "正在暂停...";
						break;
					case "restart" :
						pendingStatus = "正在重启...";
						break;
					default :
						pendingStatus = "操作中...";
						break;
				}
				final String finalPendingStatus = pendingStatus;
				runOnUiThread(() -> updateProfileStatusInUi(p, finalPendingStatus));
			});

			try {
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");
				appendLog(logTextView, logScrollView, logBuilder, "最终组合的服务器命令:\n" + finalCommand, "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

				String result = "";
				if (!finalTargets.isEmpty()) {
					appendLog(logTextView, logScrollView, logBuilder, "正在发送命令至服务器...", "info");
					result = sshManager.executeCommand(finalCommand, 180);
				}

				appendLog(logTextView, logScrollView, logBuilder, "\n服务器返回信息:", "success");
				appendLog(logTextView, logScrollView, logBuilder, result.isEmpty() ? "(无输出)" : result, "success");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------",
						 "success");

				if (operationType.equals("start") || operationType.equals("stop") || operationType.equals("restart")) {
					appendLog(logTextView, logScrollView, logBuilder, "\n正在获取服务最新日志...", "info");
					Thread.sleep(1500);
					StringBuilder journalCmdBuilder = new StringBuilder("set -e; ");
					for (FrpProfile p : finalTargets) {
						journalCmdBuilder.append(String.format(Locale.US,
															 "echo '---LOG_FOR_PORT_%d---'; journalctl -u frpc@%d.service --no-pager -n 10 || echo 'Failed to get logs for port %d.'; ",
															 p.getRemotePort(), p.getRemotePort(), p.getRemotePort()));
					}
					String journalResult = sshManager.executeCommand(journalCmdBuilder.toString(), 60);
					appendLog(logTextView, logScrollView, logBuilder, journalResult, "info");
					appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------",
							 "info");
				}

				appendLog(logTextView, logScrollView, logBuilder, "\n" + getOperationName(operationType) + "命令已完成！",
						 "success");

			} catch (IOException | InterruptedException e) {
				appendLog(logTextView, logScrollView, logBuilder, "\n\n###### 操作失败! ######", "error");
				appendLog(logTextView, logScrollView, logBuilder, "错误详情: " + e.getMessage(), "error");
				e.printStackTrace();
			} finally {
				finalTargets.forEach(p -> lockedPorts.remove(p.getRemotePort()));

				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					closeButton.setEnabled(true);
					closeButton.setText("关闭");

					if ("delete".equals(operationType)) {
						allProfiles.removeAll(finalTargets);
						pendingChangesProfiles.removeAll(finalTargets);
						updatePendingChangesMenu();
					}
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
			switch (type) {
				case "success" :
					formattedMessage = "<font color='#4CAF50'>" + TextUtils.htmlEncode(message).replace("\n", "<br>") + "</font><br>";
					break;
				case "error" :
					formattedMessage = "<font color='#F44336'>" + TextUtils.htmlEncode(message).replace("\n", "<br>") + "</font><br>";
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

	// FIX: Added post-creation verification step to ensure files are written on server.
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

		profilesToApply.forEach(p -> lockedPorts.add(p.getRemotePort()));

		executor.execute(() -> {
			boolean hasError = false;
			StringBuilder commandBuilder = new StringBuilder("set -e; ");
			boolean manageFirewall = settingsManager.isFirewallManaged() && firewallManager != null;
			boolean firewallActionTaken = false;

			appendLog(logTextView, logScrollView, logBuilder, "准备将 " + profilesToApply.size() + " 项配置写入服务器...", "info");
			appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

			if (manageFirewall) {
				appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 防火墙管理已启用，检测到类型: " + firewallManager.getType(), "info");
			} else if (settingsManager.isFirewallManaged()) {
				appendLog(logTextView, logScrollView, logBuilder, "[防火墙日志] 防火墙管理已启用，但未在服务器上检测到支持的类型。", "error");
			}

			for (FrpProfile profile : profilesToApply) {
				String fileContent = buildFileContent(profile);

				if (manageFirewall) {
					String removeTcpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "tcp").replace(" || true", "") + " || true";
					String removeUdpCmd = firewallManager.removePortRuleCommand(profile.getRemotePort(), "udp").replace(" || true", "") + " || true";
					String addCmd = firewallManager.addPortRuleCommand(profile.getRemotePort(), profile.getProtocol());

					appendLog(logTextView, logScrollView, logBuilder, String.format("[防火墙日志] 为端口 %d 准备更新规则...", profile.getRemotePort()), "info");
					commandBuilder.append(removeTcpCmd).append("; ").append(removeUdpCmd).append("; ").append(addCmd).append("; ");
					firewallActionTaken = true;
				}

				String command = String.format(Locale.US,
											 "sudo rm -f /etc/frp/conf.d/port_%d.ini.disabled; "
											 + "printf '%%s\\n' '%s' | sudo tee /etc/frp/conf.d/port_%d.ini > /dev/null; ",
											 profile.getRemotePort(), fileContent, profile.getRemotePort());
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
					verifyCmdBuilder.append(String.format("if [ -f /etc/frp/conf.d/port_%d.ini ]; then echo 'OK:%d'; else echo 'FAIL:%d'; fi; ", p.getRemotePort(), p.getRemotePort(), p.getRemotePort()));
				}
				String verifyResult = sshManager.executeCommand(verifyCmdBuilder.toString(), 30);

				boolean allVerified = true;
				for (String line : verifyResult.split("\n")) {
					if (line.startsWith("FAIL:")) {
						allVerified = false;
						String failedPort = line.split(":")[1];
						appendLog(logTextView, logScrollView, logBuilder, "创建失败: 未在服务器上找到 port_" + failedPort + ".ini 文件。", "error");
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
				profilesToApply.forEach(p -> lockedPorts.remove(p.getRemotePort()));

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

		title.setText("操作端口 " + profile.getRemotePort() + " 防火墙");
		tvType.setText("防火墙类型: " + firewallManager.getType());
		tvStatus.setText("当前状态: " + profile.getFirewallStatus());

		if ("已放行".equals(profile.getFirewallStatus())) {
			tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_running));
		} else {
			tvStatus.setTextColor(ContextCompat.getColor(this, R.color.status_stopped));
		}

		new MaterialAlertDialogBuilder(this)
			.setView(dialogView)
			.setPositiveButton("放行端口", (d, w) -> executeSingleFirewallOperation(profile, "allow"))
		.setNegativeButton("阻止端口", (d, w) -> executeSingleFirewallOperation(profile, "block"))
		.setNeutralButton("取消", null)
			.show();
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
			.setTitle(opName + "端口 " + profile.getRemotePort() + "...")
			.setCancelable(false)
			.setPositiveButton("关闭", null)
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
				appendLog(logTextView, logScrollView, logBuilder, "目标端口: " + profile.getRemotePort() + "/" + profile.getProtocol(), "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");
				appendLog(logTextView, logScrollView, logBuilder, "组合的服务器命令:\n" + finalCommand, "info");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "info");

				String result = sshManager.executeCommand(finalCommand, 60);

				appendLog(logTextView, logScrollView, logBuilder, "\n服务器返回信息:", "success");
				appendLog(logTextView, logScrollView, logBuilder, result.isEmpty() ? "(无输出)" : result, "success");
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "success");
				appendLog(logTextView, logScrollView, logBuilder, "\n操作完成!", "success");
			} catch (IOException e) {
				appendLog(logTextView, logScrollView, logBuilder, "\n\n###### 操作失败! ######", "error");
				appendLog(logTextView, logScrollView, logBuilder, "错误详情: " + e.getMessage(), "error");
				e.printStackTrace();
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

		String title = "allow".equals(operationType) ? "确认放行以下 " + targets.size() + " 个端口？" : "确认阻止以下 " + targets.size() + " 个端口？";
		String positiveButtonText = "allow".equals(operationType) ? "全部放行" : "全部阻止";

		ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
															 targets.stream()
															 .map(p -> String.format("端口: %d/%s%s",
																					 p.getRemotePort(),
																					 p.getProtocol(),
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
			.setTitle(getOperationName(operationType) + "执行中...").setCancelable(false)
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
				appendLog(logTextView, logScrollView, logBuilder, "------------------------------------------", "success");
				appendLog(logTextView, logScrollView, logBuilder, "\n" + getOperationName(operationType) + " 命令已完成！", "success");

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

		switch (currentStatusFilter) {
			case "running":
				filteredList.removeIf(p -> !"运行中".equals(p.getStatus()));
				break;
			case "stopped":
				filteredList.removeIf(p -> "运行中".equals(p.getStatus()));
				break;
		}

		switch (currentProtocolFilter) {
			case "tcp":
				filteredList.removeIf(p -> !"tcp".equalsIgnoreCase(p.getProtocol()));
				break;
			case "udp":
				filteredList.removeIf(p -> !"udp".equalsIgnoreCase(p.getProtocol()));
				break;
		}

		switch (currentFirewallFilter) {
			case "allowed":
				filteredList.removeIf(p -> !"已放行".equals(p.getFirewallStatus()));
				break;
			case "blocked":
				filteredList.removeIf(p -> "已放行".equals(p.getFirewallStatus()));
				break;
		}

		String lowerCaseQuery = currentSearchQuery.toLowerCase().trim();
		if (!lowerCaseQuery.isEmpty()) {
			filteredList.removeIf(profile ->
				!(String.valueOf(profile.getRemotePort()).contains(lowerCaseQuery) ||
				(profile.getTag() != null && profile.getTag().toLowerCase().contains(lowerCaseQuery)) ||
				(profile.getLocalIp() != null && profile.getLocalIp().toLowerCase().contains(lowerCaseQuery)) ||
				String.valueOf(profile.getLocalPort()).contains(lowerCaseQuery))
			);
		}

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
			displayItems.add("端口: " + p.getRemotePort()
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
		final Map<Integer, Boolean> selectionState = adapter.getSelectionState();

		executor.execute(() -> {
			final String SEPARATOR = "---FRP_MANAGER_SEPARATOR---";
			StringBuilder commandBuilder = new StringBuilder();
			commandBuilder.append("echo '---CONFIGS---' && ")
				.append("for f in /etc/frp/conf.d/port_*.ini; do if [ -f \"$f\" ]; then echo \"$f\"; cat \"$f\"; echo '---FILE_END---'; fi; done; ");
			commandBuilder.append("echo ").append(SEPARATOR).append(" && echo '---RUNNING_SERVICES---' && ")
				.append("systemctl list-units --type=service --state=running 'frpc@*.service' --no-pager | awk '{print $1}'; ");
			commandBuilder.append("echo ").append(SEPARATOR).append(" && echo '---ENABLED_STATUS---' && ")
				.append("systemctl list-unit-files 'frpc@*.service' --no-pager | awk '{print $1,$2}';");
			commandBuilder.append("echo ").append(SEPARATOR).append(" && echo '---FIREWALL_RULES---' && ");
			commandBuilder.append("FW_TYPE=$(if systemctl is-active --quiet firewalld; then echo 'firewalld'; ")
				.append("elif command -v ufw >/dev/null && ufw status | grep -q 'Status: active'; then echo 'ufw'; ")
				.append("elif command -v iptables >/dev/null; then echo 'iptables'; else echo 'none'; fi); ")
				.append("echo \"$FW_TYPE\"; echo '---FW_SEP---'; ")
				.append("case $FW_TYPE in ")
				.append("'firewalld') firewall-cmd --list-ports ;; ")
				.append("'ufw') ufw status verbose | grep -w 'ALLOW' ;; ")
				.append("'iptables') iptables -S INPUT | grep -- '-j ACCEPT' ;; ")
				.append("esac;");

			String command = commandBuilder.toString();

			try {
				String result = sshManager.executeCommand(command, 45);
				final List<FrpProfile> profiles = parseAndProcessServerResult(result, selectionState,
																			 FrpManagerActivity.this.allProfiles);

				allProfiles.clear();
				allProfiles.addAll(profiles);
				Collections.sort(allProfiles, Comparator.comparingInt(FrpProfile::getRemotePort));

				runOnUiThread(() -> {
					applyFilters();
					hideLoadingDialog();
					updatePendingChangesMenu();
				});

			} catch (final Exception e) {
				e.printStackTrace();
				runOnUiThread(() -> {
					hideLoadingDialog();
					showErrorDialog("加载失败", "无法连接到服务器或连接已断开。\n\n错误: " + e.getMessage());
					checkEmptyState();
				});
			}
		});
	}

	private List<FrpProfile> parseAndProcessServerResult(String result, Map<Integer, Boolean> selectionState,
														 List<FrpProfile> oldProfiles) throws IOException {
		final String SEPARATOR = "---FRP_MANAGER_SEPARATOR---";
		String[] sections = result.split(SEPARATOR);
		if (sections.length < 3) {
			Log.e("ParseError", "Incomplete server data. Raw output: " + result);
			throw new IOException("从服务器返回的数据格式不完整");
		}

		List<FrpProfile> profilesFromConfig = parseProfilesFromConfigSection(sections[0]);
		String runningServices = sections[1];
		String enabledStatuses = sections[2];

		Map<Integer, FrpProfile> masterProfileMap = new HashMap<>();

		for (FrpProfile serverProfile : profilesFromConfig) {
			masterProfileMap.put(serverProfile.getRemotePort(), serverProfile);
		}

		Set<String> openFirewallPorts = new HashSet<>();
		if (sections.length > 3) {
			String firewallSection = sections[3].replace("---FIREWALL_RULES---", "").trim();
			openFirewallPorts = parseFirewallRules(firewallSection);
		}

		for (FrpProfile profile : masterProfileMap.values()) {
			int port = profile.getRemotePort();
			String serviceName = "frpc@" + port + ".service";

			if (runningServices.contains(serviceName)) {
				profile.setStatus("运行中");
			} else if (enabledStatuses.contains(serviceName + "\tenabled")) {
				profile.setStatus("已停止");
			} else if (enabledStatuses.contains(serviceName + "\tdisabled")) {
				profile.setStatus("已禁用");
			} else {
				profile.setStatus("已停止");
			}

			profile.setSelected(selectionState.getOrDefault(port, false));
			profile.setModified(false);

			String portRule = String.format("%d/%s", profile.getRemotePort(), profile.getProtocol());
			if (openFirewallPorts.contains(portRule)) {
				profile.setFirewallStatus("已放行");
			} else {
				profile.setFirewallStatus("未放行");
			}
		}

		for (FrpProfile pending : pendingChangesProfiles) {
			pending.setModified(true);
			masterProfileMap.put(pending.getRemotePort(), pending);
		}

		Map<Integer, FrpProfile> oldProfilesMap = oldProfiles.stream()
			.collect(Collectors.toMap(FrpProfile::getRemotePort, p -> p, (a, b) -> b));

		for (int lockedPort : lockedPorts) {
			if (masterProfileMap.containsKey(lockedPort) && oldProfilesMap.containsKey(lockedPort)) {
				masterProfileMap.get(lockedPort).setStatus(oldProfilesMap.get(lockedPort).getStatus());
			}
		}

		return new ArrayList<>(masterProfileMap.values());
	}

	private Set<String> parseFirewallRules(String firewallOutput) {
		Set<String> openPorts = new HashSet<>();
		String[] parts = firewallOutput.split("---FW_SEP---");
		if (parts.length < 2) {
			Log.e("FirewallParse", "Firewall output missing FW_SEP.");
			return openPorts;
		}

		String firewallType = parts[0].trim();
		String rulesContent = parts[1].trim();

		Pattern portPattern;

		switch (firewallType) {
			case "firewalld":
				portPattern = Pattern.compile("(\\d+/(?:tcp|udp))");
				Matcher firewalldMatcher = portPattern.matcher(rulesContent);
				while (firewalldMatcher.find()) {
					openPorts.add(firewalldMatcher.group(1));
				}
				break;
			case "ufw":
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
			case "iptables":
				portPattern = Pattern.compile("--dport (\\d+)");
				Pattern protocolPattern = Pattern.compile("-p (tcp|udp)");
				for (String line : rulesContent.split("\n")) {
					if(!line.contains("-j ACCEPT")) continue;
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
			case "none":
			default:
				break;
		}
		return openPorts;
	}


	private List<FrpProfile> parseProfilesFromConfigSection(String configSection) {
		List<FrpProfile> profiles = new ArrayList<>();
		String[] fileParts = configSection.split("---FILE_END---");
		for (String part : fileParts) {
			if (part.trim().isEmpty() || part.trim().equals("---CONFIGS---"))
				continue;

			String[] lines = part.split("\n", 2);
			if (lines.length < 2)
				continue;

			String filePath = lines[0].trim();
			if (filePath.endsWith(".ini.disabled")) {
				continue;
			}

			String content = lines[1];
			FrpProfile profile = parseFrpProfileFromContent(content);
			if (profile != null) {
				profiles.add(profile);
			}
		}
		return profiles;
	}

	private FrpProfile parseFrpProfileFromContent(String content) {
		try {
			FrpProfile profile = new FrpProfile();

			String serverAddr = parseValue(content, "server_addr");
			if(!serverAddr.isEmpty()) profile.setServerAddr(serverAddr);

			String serverPortStr = parseValue(content, "server_port");
			if(!serverPortStr.isEmpty()) profile.setServerPort(Integer.parseInt(serverPortStr));

			String token = parseValue(content, "token");
			if(!token.isEmpty()) profile.setToken(token);

			profile.setRemotePort(Integer.parseInt(parseValue(content, "remote_port")));
			profile.setLocalIp(parseValue(content, "local_ip"));
			profile.setLocalPort(Integer.parseInt(parseValue(content, "local_port")));
			profile.setProtocol(parseValue(content, "type"));

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
			Log.e("ParseProfileError", "Failed to parse profile content: " + content, e);
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
			.setTitle("配置文件: port_" + profile.getRemotePort()).setView(dialogView).setPositiveButton("关闭", null)
			.create();

		configDialog.show();
		copyButton.setEnabled(false);

		copyButton.setOnClickListener(v -> {
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			ClipData clip = ClipData.newPlainText("FRP Config port_" + profile.getRemotePort(),
												 contentTextView.getText());
			clipboard.setPrimaryClip(clip);
			Toast.makeText(FrpManagerActivity.this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
		});

		executor.execute(() -> {
			try {
				String command = String.format(Locale.US,
											 "sudo cat /etc/frp/conf.d/port_%d.ini 2>/dev/null || "
											 + "sudo cat /etc/frp/conf.d/port_%d.ini.disabled 2>/dev/null || "
											 + "echo '错误：在服务器上未找到对应的配置文件。'",
											 profile.getRemotePort(), profile.getRemotePort());

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
			.setAdapter(arrayAdapter, null)
			.setPositiveButton("立即应用", (dialog, which) -> applyAllPendingChanges())
		.setNegativeButton("放弃并退出", (dialog, which) -> {
			pendingChangesProfiles.clear();
			updatePendingChangesMenu();
			finish();
		}).setNeutralButton("取消", null).show();
	}
}