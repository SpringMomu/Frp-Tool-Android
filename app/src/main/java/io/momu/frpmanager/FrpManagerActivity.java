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
import io.momu.frpmanager.manager.data.FrpManagerRepository;
import io.momu.frpmanager.manager.model.ApplyChangesResult;
import io.momu.frpmanager.manager.model.BatchOperationBlock;
import io.momu.frpmanager.manager.model.BatchOperationResult;
import io.momu.frpmanager.manager.model.CommandExecutionResult;
import io.momu.frpmanager.manager.model.FrpFilterState;
import io.momu.frpmanager.manager.model.PortExecutionStatus;
import io.momu.frpmanager.manager.ui.OperationLogDialog;
import io.momu.frpmanager.manager.util.FrpProfileFilter;
import io.momu.frpmanager.manager.util.FrpProfileKey;
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
	private FrpManagerRepository repository;
	private FirewallManager firewallManager;
	private ExecutorService executor = Executors.newSingleThreadExecutor();

	private List<FrpProfile> allProfiles = new ArrayList<>();
	private List<FrpProfile> pendingChangesProfiles = new ArrayList<>();
	private MenuItem applyChangesMenuItem;

	private Handler autoRefreshHandler = new Handler(Looper.getMainLooper());
	private Runnable autoRefreshRunnable;
	private Dialog loadingDialog;

	private final Set<String> lockedProfiles = Collections.synchronizedSet(new HashSet<>());
	private final FrpFilterState filterState = new FrpFilterState();

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
		repository = new FrpManagerRepository(sshManager, settingsManager);

		initViews();
		setupRecyclerView();
		showLoadingDialog();
		detectFirewallAndLoadProfiles();

		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
				@Override
				public void handleOnBackPressed() {
					if (filterState.hasSearchQuery()) {
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
				String firewallType = repository.detectFirewallType();
				this.firewallManager = FirewallManagerFactory.getManager(firewallType);

				runOnUiThread(() -> {
					if (firewallManager != null) {
						Toast.makeText(this, "已获取防火墙类型: " + firewallManager.getType(), Toast.LENGTH_SHORT).show();
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
				filterState.setStatusFilter("running");
			} else if (checkedId == R.id.chip_filter_stopped) {
				filterState.setStatusFilter("stopped");
			} else {
				filterState.setStatusFilter("all");
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
					filterState.setProtocolFilter("tcp");
				} else if (checkedId == R.id.chip_filter_protocol_udp) {
					filterState.setProtocolFilter("udp");
				} else {
					filterState.setProtocolFilter("all");
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
					filterState.setFirewallFilter("allowed");
				} else if (checkedId == R.id.chip_filter_firewall_blocked) {
					filterState.setFirewallFilter("blocked");
				} else {
					filterState.setFirewallFilter("all");
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
			if (filterState.hasActiveFilters()) {
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
		input.setText(filterState.getSearchQuery());
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
		if (filterState.hasSearchQuery()) {
			chipFilterStatus.setText(filterState.buildSearchChipText());
			chipFilterStatus.setVisibility(View.VISIBLE);
		} else {
			chipFilterStatus.setVisibility(View.GONE);
		}
	}

	@Override
	public void onProfileClick(FrpProfile profile) {
		if (lockedProfiles.contains(FrpProfileKey.from(profile))) {
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
		String targetKey = FrpProfileKey.from(profile);
		for (FrpProfile p : allProfiles) {
			if (FrpProfileKey.from(p).equals(targetKey)) {
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
				Set<String> existingProfileKeys = allProfiles.stream().map(FrpProfileKey::from)
				.collect(Collectors.toSet());

				boolean tcpExists = existingProfileKeys.contains(FrpProfileKey.from(remotePort, "tcp"));
				boolean udpExists = existingProfileKeys.contains(FrpProfileKey.from(remotePort, "udp"));
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
		OperationLogDialog logDialog = new OperationLogDialog(
			this,
			"应用端口 " + getProfileDisplayName(profile) + " 的更改...",
			false
		);

		lockedProfiles.add(FrpProfileKey.from(profile));

		executor.execute(() -> {
			boolean hasError = false;
			try {
				logDialog.append("准备为端口 " + getProfileDisplayName(profile) + " 应用更改...", "info");
				logDialog.append("------------------------------------------", "info");
				if (isFirewallReady()) {
					logDialog.append("[防火墙日志] 检测到已启用防火墙联动。", "info");
				}

				CommandExecutionResult result = repository.applySingleProfileChange(profile, restart, firewallManager);
				logDialog.append("------------------------------------------", "info");
				logDialog.append("最终执行命令:\n" + result.getCommand(), "info");
				logDialog.append("------------------------------------------", "info");
				appendCommandOutput(logDialog, result);

				pendingChangesProfiles.removeIf(p -> FrpProfileKey.from(p).equals(FrpProfileKey.from(profile)));
				logDialog.append("\n更改已应用成功！", "success");
				if (!restart) {
					logDialog.append("请手动重启服务以使新配置生效。", "success");
				}
			} catch (IOException e) {
				hasError = true;
				logDialog.append("\n\n###### 操作失败! ######", "error");
				logDialog.append("错误详情: " + e.getMessage(), "error");
			} finally {
				lockedProfiles.remove(FrpProfileKey.from(profile));
				boolean finalHasError = hasError;
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					logDialog.enableCloseButton(true);
					updatePendingChangesMenu();
					loadFrpProfilesFromServer();
					if (!finalHasError) {
						logDialog.dismissDelayed(2500);
					}
				});
			}
		});
	}

	private void appendCommandOutput(OperationLogDialog logDialog, CommandExecutionResult result) {
		logDialog.append("\n服务器返回信息:", "success");
		logDialog.append(result.getOutput().isEmpty() ? "(无输出)" : result.getOutput(), "success");
	}

	private void appendBatchOperationLogs(OperationLogDialog logDialog, BatchOperationResult result) {
		logDialog.append(result.getSummary(), "info");
		logDialog.append("\n--- 开始执行 ---", "info");
		for (BatchOperationBlock block : result.getBlocks()) {
			logDialog.append(block.getContent(), toLogType(block.getStatus()));
		}
		if (result.getBlocks().isEmpty()) {
			logDialog.append(result.getOutput().isEmpty() ? "(无输出)" : result.getOutput(), "info");
		}
		logDialog.append("\n--- 执行完毕 ---", "success");
	}

	private void applyBatchOperationStatuses(String operationType, BatchOperationResult result) {
		if (!"start".equals(operationType) && !"restart".equals(operationType)) {
			return;
		}

		for (Map.Entry<String, PortExecutionStatus> entry : result.getStatusByProfileKey().entrySet()) {
			String profileKey = entry.getKey();
			PortExecutionStatus status = entry.getValue();
			FrpProfile profileToUpdate = allProfiles.stream()
				.filter(profile -> FrpProfileKey.from(profile).equals(profileKey))
				.findFirst()
				.orElse(null);
			if (profileToUpdate == null) {
				continue;
			}

			switch (status) {
				case SUCCESS:
					profileToUpdate.setStatus("运行中");
					break;
				case WARNING:
					profileToUpdate.setStatus("运行中 (有警告)");
					break;
				case ERROR:
					profileToUpdate.setStatus("启动失败");
					showStartFailureDialog(profileToUpdate);
					break;
				case UNKNOWN:
				default:
					break;
			}
		}
	}

	private void showStartFailureDialog(FrpProfile profile) {
		runOnUiThread(() -> new MaterialAlertDialogBuilder(FrpManagerActivity.this)
			.setTitle("进程启动失败或崩溃")
			.setMessage("端口 " + getProfileDisplayName(profile) + " 的 frpc 服务未能成功启动，请检查日志获取详细错误信息。")
			.setPositiveButton("好的", null)
			.show());
	}

	private String toLogType(PortExecutionStatus status) {
		switch (status) {
			case SUCCESS:
				return "success";
			case WARNING:
				return "warning";
			case ERROR:
				return "error";
			case UNKNOWN:
			default:
				return "info";
		}
	}

	private String getProfileDisplayName(FrpProfile profile) {
		return profile.getRemotePort() + "/" + profile.getProtocol().toUpperCase(Locale.US);
	}

	private boolean isFirewallReady() {
		return firewallManager != null && settingsManager.isFirewallManaged();
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
				Set<String> existingProfileKeys = allProfiles.stream().map(FrpProfileKey::from)
				.collect(Collectors.toSet());

				for (int port = remoteStart; port <= remoteEnd; port++) {
					if (isTcpChecked && !existingProfileKeys.contains(FrpProfileKey.from(port, "tcp"))) {
						newProfiles.add(createBatchProfile(serverAddr, serverPort, token, port, localIp,
														 tagTemplate, "tcp"));
					}
					if (isUdpChecked && !existingProfileKeys.contains(FrpProfileKey.from(port, "udp"))) {
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
		OperationLogDialog logDialog = new OperationLogDialog(this, getOperationName(operationType) + "命令执行中...", false);

		executor.execute(() -> {
			List<FrpProfile> finalTargets = new ArrayList<>(targets);
			if (finalTargets.isEmpty()) {
				logDialog.append("没有可执行操作的目标。", "info");
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					logDialog.enableCloseButton(true);
					exitSelectionMode();
				});
				return;
			}

			finalTargets.forEach(profile -> {
				lockedProfiles.add(FrpProfileKey.from(profile));
				runOnUiThread(() -> updateProfileStatusInUi(profile, getPendingStatus(operationType)));
			});

			try {
				BatchOperationResult result = repository.executeBatchOperation(operationType, finalTargets, firewallManager);
				appendBatchOperationLogs(logDialog, result);
				applyBatchOperationStatuses(operationType, result);
			} catch (IOException e) {
				logDialog.append("\n\n###### 操作失败! ######", "error");
				logDialog.append("错误详情: " + e.getMessage(), "error");
			} finally {
				finalTargets.forEach(profile -> lockedProfiles.remove(FrpProfileKey.from(profile)));
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					logDialog.enableCloseButton(true);
					loadFrpProfilesFromServer();
					exitSelectionMode();
				});
			}
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
		OperationLogDialog logDialog = new OperationLogDialog(this, "应用更改中...", false);
		profilesToApply.forEach(profile -> lockedProfiles.add(FrpProfileKey.from(profile)));

		executor.execute(() -> {
			boolean hasError = false;
			try {
				logDialog.append("准备将 " + profilesToApply.size() + " 项配置写入服务器...", "info");
				logDialog.append("------------------------------------------", "info");
				if (isFirewallReady()) {
					logDialog.append("[防火墙日志] 防火墙管理已启用，检测到类型: " + firewallManager.getType(), "info");
				} else if (settingsManager.isFirewallManaged()) {
					logDialog.append("[防火墙日志] 防火墙管理已启用，但未在服务器上检测到支持的类型。", "error");
				}

				ApplyChangesResult result = repository.applyChanges(profilesToApply, firewallManager);
				logDialog.append("------------------------------------------", "info");
				logDialog.append("执行命令:\n" + result.getCommand(), "info");
				logDialog.append("------------------------------------------", "info");
				appendCommandOutput(logDialog, result);
				logDialog.append("------------------------------------------", "success");
				logDialog.append("\n正在验证配置文件是否已创建...", "info");

				if (result.isSuccessful()) {
					logDialog.append("验证成功！所有配置文件均已写入服务器。", "success");
					logDialog.append("\n所有更改已成功应用！请手动启动或重启相关服务以使配置生效。", "success");
				} else {
					hasError = true;
					for (String failedProfileKey : result.getFailedProfileKeys()) {
						logDialog.append(
							"创建失败: 未在服务器上找到 port_" + failedProfileKey.replace("/", "_") + ".ini 文件。",
							"error"
						);
					}
					logDialog.append("\n部分或全部配置应用失败！请检查以上日志。", "error");
				}
			} catch (IOException e) {
				hasError = true;
				logDialog.append("\n\n###### 应用更改失败! ######", "error");
				logDialog.append("错误详情: " + e.getMessage(), "error");
			} finally {
				profilesToApply.forEach(profile -> lockedProfiles.remove(FrpProfileKey.from(profile)));
				boolean finalHasError = hasError;
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					logDialog.enableCloseButton(true);
					if (!finalHasError) {
						pendingChangesProfiles.clear();
						updatePendingChangesMenu();
						loadFrpProfilesFromServer();
						logDialog.dismissDelayed(2500);
					}
				});
			}
		});
	}

	private String getPendingStatus(String operationType) {
		switch (operationType) {
			case "start":
			case "restart":
				return "正在启动...";
			case "stop":
				return "正在暂停...";
			case "delete":
				return "正在删除...";
			default:
				return "操作中...";
		}
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
		String opName = "allow".equals(operation) ? "放行" : "阻止";
		OperationLogDialog logDialog = new OperationLogDialog(
			this,
			opName + "端口 " + getProfileDisplayName(profile) + "...",
			false
		);

		executor.execute(() -> {
			try {
				logDialog.append("防火墙类型: " + firewallManager.getType(), "info");
				logDialog.append("目标端口: " + getProfileDisplayName(profile), "info");
				CommandExecutionResult result = repository.executeSingleFirewallOperation(profile, operation, firewallManager);
				logDialog.append("------------------------------------------", "info");
				logDialog.append("组合的服务器命令:\n" + result.getCommand(), "info");
				logDialog.append("------------------------------------------", "info");
				appendCommandOutput(logDialog, result);
				logDialog.append("------------------------------------------", "success");
				logDialog.append("\n操作完成!", "success");
			} catch (IOException e) {
				logDialog.append("\n\n###### 操作失败! ######", "error");
				logDialog.append("错误详情: " + e.getMessage(), "error");
			} finally {
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					logDialog.enableCloseButton(true);
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
		OperationLogDialog logDialog = new OperationLogDialog(this, getOperationName(operationType) + "执行中...", false);

		executor.execute(() -> {
			try {
				logDialog.append(
					"开始执行 " + targets.size() + " 个批量防火墙操作 [" + getOperationName(operationType) + "]...",
					"info"
				);
				logDialog.append("防火墙类型: " + firewallManager.getType(), "info");
				CommandExecutionResult result = repository.executeBatchFirewallOperation(operationType, targets, firewallManager);
				logDialog.append("------------------------------------------", "info");
				logDialog.append("最终组合的服务器命令:\n" + result.getCommand(), "info");
				logDialog.append("------------------------------------------", "info");
				appendCommandOutput(logDialog, result);
				logDialog.append("------------------------------------------", "success");
				logDialog.append("\n" + getOperationName(operationType) + " 命令已完成！", "success");
			} catch (IOException e) {
				logDialog.append("\n\n###### 操作失败! ######", "error");
				logDialog.append("错误详情: " + e.getMessage(), "error");
			} finally {
				runOnUiThread(() -> {
					logDialog.setTitle("操作完成");
					logDialog.enableCloseButton(true);
					loadFrpProfilesFromServer();
					exitSelectionMode();
				});
			}
		});
	}

	private void filterByText(String query) {
		filterState.setSearchQuery(query);
		applyFilters();
	}

	private void applyFilters() {
		List<FrpProfile> filteredList = FrpProfileFilter.apply(allProfiles, filterState);
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
			try {
				final List<FrpProfile> profiles = repository.loadProfiles(
					selectionState,
					currentProfiles,
					new ArrayList<>(pendingChangesProfiles),
					new HashSet<>(lockedProfiles)
				);
				runOnUiThread(() -> {
					allProfiles.clear();
					allProfiles.addAll(profiles);
					Collections.sort(
						allProfiles,
						Comparator.comparingInt(FrpProfile::getRemotePort).thenComparing(FrpProfile::getProtocol)
					);
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
				final String fileContent = repository.loadConfigFile(profile);

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
