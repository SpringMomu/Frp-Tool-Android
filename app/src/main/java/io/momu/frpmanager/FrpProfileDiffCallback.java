package io.momu.frpmanager;

import androidx.recyclerview.widget.DiffUtil;
import java.util.List;
import java.util.Objects;

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

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
        return Objects.equals(oldList.get(oldItemPosition).getName(), newList.get(newItemPosition).getName());
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
        return oldList.get(oldItemPosition).equals(newList.get(newItemPosition));
    }

    @Override
    public Object getChangePayload(int oldItemPosition, int newItemPosition) {
        return super.getChangePayload(oldItemPosition, newItemPosition);
    }
}