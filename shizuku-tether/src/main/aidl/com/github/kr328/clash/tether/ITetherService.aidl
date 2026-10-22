package com.github.kr328.clash.tether;

interface ITetherService {
    void init(String parentPid);
    void startTethering(boolean enableLogging, int vpnMode);
    void stopTethering();
    String getStatus();
    String getUpstreamInterface();
    int getConnectedClients();
    boolean isCompatible();
    String getCompatibilityReport();
    boolean isHotspotEnabled();
}
