package android.net.dhcp;
parcelable DhcpServingParamsParcel {
  int serverAddr;
  int serverAddrPrefixLength;
  int[] defaultRouters;
  int[] dnsServers;
  int[] excludedAddrs;
  long dhcpLeaseTimeSecs;
  int linkMtu;
  boolean metered;
}
