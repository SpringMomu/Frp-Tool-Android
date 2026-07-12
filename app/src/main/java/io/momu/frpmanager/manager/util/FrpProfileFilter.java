package io.momu.frpmanager.manager.util;

import io.momu.frpmanager.FrpProfile;
import io.momu.frpmanager.manager.model.FrpFilterState;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FrpProfileFilter {

    private FrpProfileFilter() {
    }

    public static List<FrpProfile> apply(List<FrpProfile> source, FrpFilterState state) {
        List<FrpProfile> filtered = new ArrayList<>(source);

        if (!"all".equals(state.getStatusFilter())) {
            if ("running".equals(state.getStatusFilter())) {
                filtered.removeIf(profile -> !"运行中".equals(profile.getStatus()));
            } else {
                filtered.removeIf(profile -> "运行中".equals(profile.getStatus()));
            }
        }

        if (!"all".equals(state.getProtocolFilter())) {
            filtered.removeIf(profile -> !state.getProtocolFilter().equalsIgnoreCase(profile.getProtocol()));
        }

        if (!"all".equals(state.getFirewallFilter())) {
            if ("allowed".equals(state.getFirewallFilter())) {
                filtered.removeIf(profile -> !profile.isFirewallAllowed());
            } else {
                filtered.removeIf(FrpProfile::isFirewallAllowed);
            }
        }

        String lowerCaseQuery = state.getSearchQuery().toLowerCase(Locale.US).trim();
        if (!lowerCaseQuery.isEmpty()) {
            filtered.removeIf(profile -> !(String.valueOf(profile.getRemotePort()).contains(lowerCaseQuery)
                || (profile.getTag() != null && profile.getTag().toLowerCase(Locale.US).contains(lowerCaseQuery))
                || (profile.getLocalIp() != null && profile.getLocalIp().toLowerCase(Locale.US).contains(lowerCaseQuery))
                || String.valueOf(profile.getLocalPort()).contains(lowerCaseQuery)));
        }

        return filtered;
    }
}
