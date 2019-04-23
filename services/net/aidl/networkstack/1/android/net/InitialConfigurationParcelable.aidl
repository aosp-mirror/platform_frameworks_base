package android.net;
parcelable InitialConfigurationParcelable {
  android.net.LinkAddress[] ipAddresses;
  android.net.IpPrefix[] directlyConnectedRoutes;
  String[] dnsServers;
  String gateway;
}
