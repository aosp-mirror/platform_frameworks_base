package android.net;
parcelable ProvisioningConfigurationParcelable {
  boolean enableIPv4;
  boolean enableIPv6;
  boolean usingMultinetworkPolicyTracker;
  boolean usingIpReachabilityMonitor;
  int requestedPreDhcpActionMs;
  android.net.InitialConfigurationParcelable initialConfig;
  android.net.StaticIpConfiguration staticIpConfig;
  android.net.apf.ApfCapabilities apfCapabilities;
  int provisioningTimeoutMs;
  int ipv6AddrGenMode;
  android.net.Network network;
  String displayName;
}
