package io.momu.frpmanager;

import androidx.recyclerview.widget.DiffUtil;
import java.util.List;
import java.util.Objects;

/**
 * An implementation of DiffUtil.Callback for FrpProfile lists.
 * This helps RecyclerView efficiently update its data.
 */
public class FrpProfileDiffCallback extends DiffUtil.Callback {

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

    /**
     * Determines if two objects represent the same item.
     * This method is called by DiffUtil to check if the two items are the same item,
     * e.g., by comparing a unique ID or name.
     *
     * @param oldItemPosition The position of the item in the old list.
     * @param newItemPosition The position of the item in the new list.
     * @return True if the two items represent the same object, false otherwise.
     */
    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        // Assuming 'getRemotePort()' is a unique identifier for FrpProfile.
        // It's crucial for this to return true only if it's genuinely the same underlying profile.
        return oldList.get(oldItemPosition).getRemotePort() == newList.get(newItemPosition).getRemotePort();
    }

    /**
     * Determines if the content of two items is the same.
     * This method is called by DiffUtil only if {@link #areItemsTheSame(int, int)} returns true.
     * It compares the actual data content to detect changes that should trigger a UI update.
     *
     * @param oldItemPosition The position of the item in the old list.
     * @param newItemPosition The position of the item in the new list.
     * @return True if the contents of the items are the same, false otherwise.
     */
    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        // Directly uses FrpProfile's equals() method for content comparison.
        // Ensure FrpProfile correctly implements equals() and hashCode() to compare
        // all fields relevant to UI representation (status, isModified, firewallStatus, etc.).
        return oldList.get(oldItemPosition).equals(newList.get(newItemPosition));
    }

    /**
     * (Optional) Called when {@link #areItemsTheSame(int, int)} returns true and
     * {@link #areContentsTheSame(int, int)} returns false.
     * This method can return a "payload" object that describes the change.
     * This allows for more granular, partial updates in {@code onBindViewHolder}.
     *
     * @param oldItemPosition The position of the item in the old list.
     * @param newItemPosition The position of the item in the new list.
     * @return A payload object representing the change, or null if a full rebind is desired.
     */
    @Override
    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
        // Current implementation does not use payloads, triggering a full rebind in onBindViewHolder.
        // Return a Bundle with specific change keys if partial updates are needed.
        return super.getChangePayload(oldItemPosition, newItemPosition);
    }
}