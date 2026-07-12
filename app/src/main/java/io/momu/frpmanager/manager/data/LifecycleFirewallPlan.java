package io.momu.frpmanager.manager.data;

import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.manager.model.PortExecutionStatus;
import io.momu.frpmanager.manager.util.FrpProfileKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Selects only firewall changes justified by a proven FRP lifecycle result. */
public final class LifecycleFirewallPlan {
    private final List<FrpProfile> profilesToAllow;
    private final List<FrpProfile> profilesToBlock;

    private LifecycleFirewallPlan(List<FrpProfile> profilesToAllow, List<FrpProfile> profilesToBlock) {
        this.profilesToAllow = Collections.unmodifiableList(new ArrayList<>(profilesToAllow));
        this.profilesToBlock = Collections.unmodifiableList(new ArrayList<>(profilesToBlock));
    }

    public static LifecycleFirewallPlan from(String operationType,
        Map<String, PortExecutionStatus> statuses, List<FrpProfile> profiles) {
        List<FrpProfile> allow = new ArrayList<>();
        List<FrpProfile> block = new ArrayList<>();
        boolean starting = "start".equals(operationType) || "restart".equals(operationType);
        boolean stopping = "stop".equals(operationType) || "delete".equals(operationType);
        if (!starting && !stopping) return new LifecycleFirewallPlan(allow, block);

        for (FrpProfile profile : profiles) {
            PortExecutionStatus status = statuses.get(FrpProfileKey.from(profile));
            if (starting) {
                if (status == PortExecutionStatus.SUCCESS) allow.add(profile);
                else block.add(profile);
            } else if (status == PortExecutionStatus.SUCCESS) {
                block.add(profile);
            }
        }
        return new LifecycleFirewallPlan(allow, block);
    }

    public List<FrpProfile> getProfilesToAllow() {
        return profilesToAllow;
    }

    public List<FrpProfile> getProfilesToBlock() {
        return profilesToBlock;
    }
}
