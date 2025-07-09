package io.momu.frpmanager;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
        // Ensure the correct layout file name is used.
        View view = LayoutInflater.from(context).inflate(R.layout.list_item_frp_profile, parent, false);
        return new ProfileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ProfileViewHolder holder, int position) {
        final FrpProfile profile = profiles.get(position);

        holder.tvRemotePort.setText(String.valueOf(profile.getRemotePort()));
        holder.tvProtocol.setText(profile.getProtocol() != null ? profile.getProtocol().toUpperCase() : "N/A");
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

        String statusText = profile.getStatus();
        int statusColor;
        switch (statusText != null ? statusText : "") {
            case "运行中":
                statusColor = ContextCompat.getColor(context, R.color.status_running);
                break;
            case "已停止":
            case "启动失败":
                statusColor = ContextCompat.getColor(context, R.color.status_stopped);
                break;
            case "已禁用":
                statusColor = ContextCompat.getColor(context, R.color.status_disabled);
                break;
            case "正在启动...":
            case "正在暂停...":
            case "正在重启...":
            case "操作中...":
                statusColor = ContextCompat.getColor(context, R.color.status_pending);
                break;
            default:
                statusColor = ContextCompat.getColor(context, R.color.status_disabled);
                break;
        }

        if (profile.isModified()) {
            statusText += " / 待应用";
            statusColor = ContextCompat.getColor(context, R.color.status_pending);
            holder.itemView.setBackgroundColor(ContextCompat.getColor(context, R.color.pending_changes_background));
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT);
        }
        holder.tvStatus.setText(statusText);
        holder.tvStatus.setTextColor(statusColor);

        // Bind firewall status.
        String firewallStatus = profile.getFirewallStatus();
        if (firewallStatus != null && !firewallStatus.isEmpty()) {
            holder.tvFirewallStatus.setVisibility(View.VISIBLE);
            holder.tvFirewallStatus.setText("防火墙: " + firewallStatus);
            if ("已放行".equals(firewallStatus)) {
                holder.tvFirewallStatus.setTextColor(ContextCompat.getColor(context, R.color.status_running));
            } else {
                holder.tvFirewallStatus.setTextColor(ContextCompat.getColor(context, R.color.status_stopped));
            }
        } else {
            holder.tvFirewallStatus.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> listener.onProfileClick(profile));
        holder.itemView.setOnLongClickListener(v -> {
            listener.onProfileLongClick(profile);
            return true;
        });

        if (isSelectionMode) {
            holder.checkBox.setVisibility(View.VISIBLE);
            holder.checkBox.setChecked(profile.isSelected());
            holder.checkBox.setOnClickListener(v -> listener.onProfileClick(profile));
        } else {
            holder.checkBox.setVisibility(View.GONE);
            holder.checkBox.setOnClickListener(null);
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

    public void updateSingleItem(FrpProfile profile) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).equals(profile)) {
                profiles.set(i, profile);
                notifyItemChanged(i);
                return;
            }
        }
    }

    public void enterSelectionMode() {
        if (!isSelectionMode) {
            isSelectionMode = true;
            clearSelection();
            notifyDataSetChanged();
        }
    }

    /**
     * Exits selection mode and clears all selections.
     */
    public void exitSelectionMode() {
        if (isSelectionMode) {
            isSelectionMode = false;
            clearSelection();
            notifyDataSetChanged();
            listener.onSelectionChanged(0);
        }
    }

    public void clearSelection() {
        for (FrpProfile profile : profiles) {
            profile.setSelected(false);
        }
    }

    public void toggleSelection(FrpProfile profile) {
        profile.setSelected(!profile.isSelected());
        int position = profiles.indexOf(profile);
        if (position != -1) {
            notifyItemChanged(position);
            listener.onSelectionChanged(getSelectedItemsCount());
        }
    }

    public List<FrpProfile> getSelectedItems() {
        return profiles.stream()
            .filter(FrpProfile::isSelected)
            .collect(Collectors.toList());
    }

    public int getSelectedItemsCount() {
        return (int) profiles.stream().filter(FrpProfile::isSelected).count();
    }

    public void selectAll() {
        if (!isSelectionMode) return;
        profiles.forEach(profile -> profile.setSelected(true));
        notifyDataSetChanged();
    }

    public void invertSelection() {
        if (!isSelectionMode) return;
        profiles.forEach(profile -> profile.setSelected(!profile.isSelected()));
        notifyDataSetChanged();
    }

    /**
     * Retrieves the selection state of all profiles in the adapter.
     * Used to preserve selection across data refreshes.
     * @return A Map where the key is the remote port and the value is its selection status.
     */
    public Map<Integer, Boolean> getSelectionState() {
        Map<Integer, Boolean> selectionState = new HashMap<>();
        for (FrpProfile profile : profiles) {
            selectionState.put(profile.getRemotePort(), profile.isSelected());
        }
        return selectionState;
    }

    static class ProfileViewHolder extends RecyclerView.ViewHolder {
        CheckBox checkBox;
        TextView tvRemotePort, tvProtocol, tvStatus, tvMapping, tvTag, tvProxyProtocol, tvFirewallStatus;

        public ProfileViewHolder(@NonNull View itemView) {
            super(itemView);
            checkBox = itemView.findViewById(R.id.checkbox_select);
            tvRemotePort = itemView.findViewById(R.id.tv_remote_port);
            tvProtocol = itemView.findViewById(R.id.tv_protocol);
            tvStatus = itemView.findViewById(R.id.tv_status);
            tvMapping = itemView.findViewById(R.id.tv_mapping);
            tvTag = itemView.findViewById(R.id.tv_tag);
            tvProxyProtocol = itemView.findViewById(R.id.tv_proxy_protocol);
            tvFirewallStatus = itemView.findViewById(R.id.tv_firewall_status);
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
        public int getOldListSize() {
            return oldList.size();
        }

        @Override
        public int getNewListSize() {
            return newList.size();
        }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).getRemotePort() == newList.get(newItemPosition).getRemotePort();
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            FrpProfile oldProfile = oldList.get(oldItemPosition);
            FrpProfile newProfile = newList.get(newItemPosition);

            // Compare all fields that affect UI display and logic.
            return oldProfile.getRemotePort() == newProfile.getRemotePort() &&
                Objects.equals(oldProfile.getLocalIp(), newProfile.getLocalIp()) &&
                oldProfile.getLocalPort() == newProfile.getLocalPort() &&
                Objects.equals(oldProfile.getProtocol(), newProfile.getProtocol()) &&
                Objects.equals(oldProfile.getTag(), newProfile.getTag()) &&
                Objects.equals(oldProfile.getStatus(), newProfile.getStatus()) &&
                Objects.equals(oldProfile.getProxyProtocolVersion(), newProfile.getProxyProtocolVersion()) &&
                oldProfile.isModified() == newProfile.isModified() &&
                oldProfile.isSelected() == newProfile.isSelected() &&
                Objects.equals(oldProfile.getFirewallStatus(), newProfile.getFirewallStatus());
        }
    }
}