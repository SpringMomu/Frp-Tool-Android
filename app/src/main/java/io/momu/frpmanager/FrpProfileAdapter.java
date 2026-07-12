package io.momu.frpmanager;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public class FrpProfileAdapter extends RecyclerView.Adapter<FrpProfileAdapter.ProfileViewHolder> {

    public interface OnProfileClickListener {
        void onProfileClick(FrpProfile profile);
        void onProfileLongClick(FrpProfile profile);
        void onSelectionChanged(int selectedCount);
    }

    private List<FrpProfile> profiles = new ArrayList<>();
    private final OnProfileClickListener listener;
    private boolean isSelectionMode = false;
    private final Context context;

    public FrpProfileAdapter(Context context) {
        this.context = context;
        if (!(context instanceof OnProfileClickListener)) {
            throw new RuntimeException(context.toString() + " must implement OnProfileClickListener");
        }
        this.listener = (OnProfileClickListener) context;
    }

    @NonNull
    @Override
    public ProfileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.list_item_frp_profile, parent, false);
        return new ProfileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ProfileViewHolder holder, int position) {
        final FrpProfile profile = profiles.get(position);
        holder.tvRemotePort.setText(String.valueOf(profile.getRemotePort()));
        holder.tvProtocol.setText(profile.getProtocol() != null ? profile.getProtocol().toUpperCase(Locale.US) : "N/A");
        holder.tvMapping.setText("映射到 " + profile.getLocalIp() + ":" + profile.getLocalPort());
        if (profile.getProxyProtocolVersion() != null && !profile.getProxyProtocolVersion().isEmpty()) {
            holder.tvProxyProtocol.setText("Proxy Protocol: " + profile.getProxyProtocolVersion());
            holder.tvProxyProtocol.setVisibility(View.VISIBLE);
        } else {
            holder.tvProxyProtocol.setVisibility(View.GONE);
        }
        if (profile.getTag() != null && !profile.getTag().isEmpty()) {
            holder.tvTag.setText("备注: " + profile.getTag());
            holder.tvTag.setVisibility(View.VISIBLE);
        } else {
            holder.tvTag.setVisibility(View.GONE);
        }
        String statusText = profile.getStatus() == null ? "未知" : profile.getStatus();
        int statusColor;
        if (statusText.startsWith("运行中")) {
            statusColor = ContextCompat.getColor(context, R.color.status_running);
        } else {
            switch (statusText) {
                case "已停止": case "启动失败": statusColor = ContextCompat.getColor(context, R.color.status_stopped); break;
                case "已禁用": statusColor = ContextCompat.getColor(context, R.color.status_disabled); break;
                case "正在启动...": case "正在暂停...": case "正在重启...": case "操作中...": statusColor = ContextCompat.getColor(context, R.color.status_pending); break;
                default: statusColor = ContextCompat.getColor(context, R.color.status_disabled); break;
            }
        }
        if (profile.isModified()) {
            statusText += " / 待应用";
            statusColor = ContextCompat.getColor(context, R.color.status_pending);
        }
        int cardBackground = ContextCompat.getColor(context,
            profile.isModified() ? R.color.pending_changes_background : R.color.default_item_background);
        int strokeColor = ContextCompat.getColor(context, R.color.app_outline_variant);
        if (isSelectionMode && profile.isSelected()) {
            cardBackground = ContextCompat.getColor(context, R.color.selection_background);
            strokeColor = ContextCompat.getColor(context, R.color.brand_primary);
        }
        holder.cardView.setCardBackgroundColor(cardBackground);
        holder.cardView.setStrokeColor(strokeColor);
        holder.cardView.setStrokeWidth((int) (context.getResources().getDisplayMetrics().density
            * (isSelectionMode && profile.isSelected() ? 2f : 1f)));
        holder.statusIndicator.setBackgroundColor(statusColor);
        holder.tvStatus.setText(statusText);
        holder.tvStatus.setTextColor(statusColor);
        String firewallStatus = profile.getFirewallStatus();
        if (firewallStatus == null || firewallStatus.trim().isEmpty()) {
            firewallStatus = FrpProfile.FIREWALL_UNKNOWN;
        }
        int firewallColor;
        if (FrpProfile.FIREWALL_ALLOWED.equals(firewallStatus)) {
            firewallColor = ContextCompat.getColor(context, R.color.status_running);
        } else if (FrpProfile.FIREWALL_NOT_ALLOWED.equals(firewallStatus)) {
            firewallColor = ContextCompat.getColor(context, R.color.status_stopped);
        } else if (FrpProfile.FIREWALL_PENDING.equals(firewallStatus)) {
            firewallColor = ContextCompat.getColor(context, R.color.status_pending);
        } else {
            firewallColor = ContextCompat.getColor(context, R.color.status_disabled);
        }
        holder.tvFirewallStatus.setVisibility(View.VISIBLE);
        holder.tvFirewallStatus.setText("防火墙  " + firewallStatus);
        holder.tvFirewallStatus.setTextColor(firewallColor);
        TextViewCompat.setCompoundDrawableTintList(holder.tvFirewallStatus, ColorStateList.valueOf(firewallColor));

        holder.itemView.setOnClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION) return;
            FrpProfile clickedProfile = profiles.get(currentPosition);
            if (isSelectionMode) {
                clickedProfile.setSelected(!clickedProfile.isSelected());
                notifyItemChanged(currentPosition);
                listener.onSelectionChanged(getSelectedItemsCount());
            } else {
                listener.onProfileClick(clickedProfile);
            }
        });
        holder.itemView.setOnLongClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION) return true;
            FrpProfile longClickedProfile = profiles.get(currentPosition);
            if (!isSelectionMode) {
                listener.onProfileLongClick(longClickedProfile);
            }
            if (!longClickedProfile.isSelected()) {
                longClickedProfile.setSelected(true);
                notifyItemChanged(currentPosition);
                listener.onSelectionChanged(getSelectedItemsCount());
            }
            return true;
        });
        if (isSelectionMode) {
            holder.checkBox.setVisibility(View.VISIBLE);
            holder.checkBox.setChecked(profile.isSelected());
        } else {
            holder.checkBox.setVisibility(View.GONE);
            holder.checkBox.setChecked(false);
        }
    }

    @Override
    public int getItemCount() {
        return profiles.size();
    }

    public void updateData(List<FrpProfile> newProfiles) {
        final FrpProfileDiffCallback diffCallback = new FrpProfileDiffCallback(this.profiles, newProfiles);
        final DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(diffCallback);

        this.profiles.clear();
        this.profiles.addAll(newProfiles);
        diffResult.dispatchUpdatesTo(this);
    }

    public void enterSelectionMode() {
        if (!isSelectionMode) { isSelectionMode = true; clearSelection(); notifyDataSetChanged(); }
    }
    public void exitSelectionMode() {
        if (isSelectionMode) { isSelectionMode = false; clearSelection(); notifyDataSetChanged(); listener.onSelectionChanged(0); }
    }
    private void clearSelection() {
        for (FrpProfile profile : profiles) { profile.setSelected(false); }
    }
    public List<FrpProfile> getSelectedItems() {
        return profiles.stream().filter(FrpProfile::isSelected).collect(Collectors.toList());
    }
    public int getSelectedItemsCount() {
        return (int) profiles.stream().filter(FrpProfile::isSelected).count();
    }
    public void selectAll() {
        if (!isSelectionMode) return;
        profiles.forEach(profile -> profile.setSelected(true));
        notifyDataSetChanged();
        listener.onSelectionChanged(profiles.size());
    }
    public void invertSelection() {
        if (!isSelectionMode) return;
        profiles.forEach(profile -> profile.setSelected(!profile.isSelected()));
        notifyDataSetChanged();
        listener.onSelectionChanged(getSelectedItemsCount());
    }
    public Map<String, Boolean> getSelectionStateByProfileKey() {
        Map<String, Boolean> selectionState = new HashMap<>();
        for (FrpProfile profile : profiles) {
            if (profile.getProtocol() != null) {
                String key = profile.getRemotePort() + "/" + profile.getProtocol().toLowerCase(Locale.US);
                selectionState.put(key, profile.isSelected());
            }
        }
        return selectionState;
    }
    static class ProfileViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        View statusIndicator;
        CheckBox checkBox; TextView tvRemotePort, tvProtocol, tvStatus, tvMapping, tvTag, tvProxyProtocol, tvFirewallStatus;
        public ProfileViewHolder(@NonNull View itemView) {
            super(itemView);
            cardView = (MaterialCardView) itemView;
            statusIndicator = itemView.findViewById(R.id.status_indicator);
            checkBox = itemView.findViewById(R.id.checkbox_select); tvRemotePort = itemView.findViewById(R.id.tv_remote_port); tvProtocol = itemView.findViewById(R.id.tv_protocol); tvStatus = itemView.findViewById(R.id.tv_status); tvMapping = itemView.findViewById(R.id.tv_mapping); tvTag = itemView.findViewById(R.id.tv_tag); tvProxyProtocol = itemView.findViewById(R.id.tv_proxy_protocol); tvFirewallStatus = itemView.findViewById(R.id.tv_firewall_status);
        }
    }

    private static class FrpProfileDiffCallback extends DiffUtil.Callback {
        private final List<FrpProfile> oldList;
        private final List<FrpProfile> newList;

        public FrpProfileDiffCallback(List<FrpProfile> oldList, List<FrpProfile> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }

        @Override
        public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            FrpProfile oldProfile = oldList.get(oldItemPosition);
            FrpProfile newProfile = newList.get(newItemPosition);
            return oldProfile.getRemotePort() == newProfile.getRemotePort() &&
				Objects.equals(oldProfile.getProtocol(), newProfile.getProtocol());
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            FrpProfile oldProfile = oldList.get(oldItemPosition);
            FrpProfile newProfile = newList.get(newItemPosition);
            return oldProfile.getLocalPort() == newProfile.getLocalPort() &&
				Objects.equals(oldProfile.getLocalIp(), newProfile.getLocalIp()) &&
				Objects.equals(oldProfile.getProtocol(), newProfile.getProtocol()) &&
				Objects.equals(oldProfile.getStatus(), newProfile.getStatus()) &&
				Objects.equals(oldProfile.getTag(), newProfile.getTag()) &&
				Objects.equals(oldProfile.getFirewallStatus(), newProfile.getFirewallStatus()) &&
				oldProfile.isModified() == newProfile.isModified() &&
				oldProfile.isSelected() == newProfile.isSelected();
        }
    }
}
