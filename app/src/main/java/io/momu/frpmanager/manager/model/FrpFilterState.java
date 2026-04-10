package io.momu.frpmanager.manager.model;

public class FrpFilterState {

    private String searchQuery = "";
    private String statusFilter = "all";
    private String protocolFilter = "all";
    private String firewallFilter = "all";

    public String getSearchQuery() {
        return searchQuery;
    }

    public void setSearchQuery(String searchQuery) {
        this.searchQuery = searchQuery == null ? "" : searchQuery;
    }

    public String getStatusFilter() {
        return statusFilter;
    }

    public void setStatusFilter(String statusFilter) {
        this.statusFilter = statusFilter == null ? "all" : statusFilter;
    }

    public String getProtocolFilter() {
        return protocolFilter;
    }

    public void setProtocolFilter(String protocolFilter) {
        this.protocolFilter = protocolFilter == null ? "all" : protocolFilter;
    }

    public String getFirewallFilter() {
        return firewallFilter;
    }

    public void setFirewallFilter(String firewallFilter) {
        this.firewallFilter = firewallFilter == null ? "all" : firewallFilter;
    }

    public boolean hasActiveFilters() {
        return !searchQuery.isEmpty()
            || !"all".equals(statusFilter)
            || !"all".equals(protocolFilter)
            || !"all".equals(firewallFilter);
    }

    public boolean hasSearchQuery() {
        return !searchQuery.isEmpty();
    }

    public String buildSearchChipText() {
        return "文本筛选: \"" + searchQuery + "\"";
    }
}
