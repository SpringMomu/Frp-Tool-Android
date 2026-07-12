package io.momu.frpmanager.manager.data;

import static org.junit.Assert.assertEquals;

import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.manager.model.PortExecutionStatus;
import io.momu.frpmanager.manager.util.FrpProfileKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LifecycleFirewallPlanTest {

    @Test
    public void onlySuccessfulStartsAreAllowedAndEveryUnprovenStartIsCleanedUp() {
        List<FrpProfile> profiles = Arrays.asList(
            profile(6100), profile(6101), profile(6102), profile(6103));
        Map<String, PortExecutionStatus> statuses = new HashMap<>();
        statuses.put(FrpProfileKey.from(profiles.get(0)), PortExecutionStatus.SUCCESS);
        statuses.put(FrpProfileKey.from(profiles.get(1)), PortExecutionStatus.ERROR);
        statuses.put(FrpProfileKey.from(profiles.get(2)), PortExecutionStatus.WARNING);

        LifecycleFirewallPlan plan = LifecycleFirewallPlan.from("start", statuses, profiles);

        assertEquals(Arrays.asList(6100), ports(plan.getProfilesToAllow()));
        assertEquals(Arrays.asList(6101, 6102, 6103), ports(plan.getProfilesToBlock()));
    }

    @Test
    public void stopOnlyClosesRulesForServicesConfirmedStopped() {
        List<FrpProfile> profiles = Arrays.asList(profile(6200), profile(6201));
        Map<String, PortExecutionStatus> statuses = new HashMap<>();
        statuses.put(FrpProfileKey.from(profiles.get(0)), PortExecutionStatus.SUCCESS);
        statuses.put(FrpProfileKey.from(profiles.get(1)), PortExecutionStatus.ERROR);

        LifecycleFirewallPlan plan = LifecycleFirewallPlan.from("stop", statuses, profiles);

        assertEquals(Arrays.asList(6200), ports(plan.getProfilesToBlock()));
        assertEquals(0, plan.getProfilesToAllow().size());
    }

    private List<Integer> ports(List<FrpProfile> profiles) {
        java.util.ArrayList<Integer> ports = new java.util.ArrayList<>();
        for (FrpProfile profile : profiles) ports.add(profile.getRemotePort());
        return ports;
    }

    private FrpProfile profile(int port) {
        FrpProfile profile = new FrpProfile();
        profile.setRemotePort(port);
        profile.setProtocol("tcp");
        return profile;
    }
}
