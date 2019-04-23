package android.net.ip;
interface IIpClientCallbacks {
  oneway void onIpClientCreated(in android.net.ip.IIpClient ipClient);
  oneway void onPreDhcpAction();
  oneway void onPostDhcpAction();
  oneway void onNewDhcpResults(in android.net.DhcpResultsParcelable dhcpResults);
  oneway void onProvisioningSuccess(in android.net.LinkProperties newLp);
  oneway void onProvisioningFailure(in android.net.LinkProperties newLp);
  oneway void onLinkPropertiesChange(in android.net.LinkProperties newLp);
  oneway void onReachabilityLost(in String logMsg);
  oneway void onQuit();
  oneway void installPacketFilter(in byte[] filter);
  oneway void startReadPacketFilter();
  oneway void setFallbackMulticastFilter(boolean enabled);
  oneway void setNeighborDiscoveryOffload(boolean enable);
}
