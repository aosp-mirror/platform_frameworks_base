package android.net;
interface INetworkStackConnector {
  oneway void makeDhcpServer(in String ifName, in android.net.dhcp.DhcpServingParamsParcel params, in android.net.dhcp.IDhcpServerCallbacks cb);
  oneway void makeNetworkMonitor(in android.net.Network network, String name, in android.net.INetworkMonitorCallbacks cb);
  oneway void makeIpClient(in String ifName, in android.net.ip.IIpClientCallbacks callbacks);
  oneway void fetchIpMemoryStore(in android.net.IIpMemoryStoreCallbacks cb);
}
