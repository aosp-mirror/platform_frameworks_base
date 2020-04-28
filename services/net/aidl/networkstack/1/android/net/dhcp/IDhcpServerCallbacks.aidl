package android.net.dhcp;
interface IDhcpServerCallbacks {
  oneway void onDhcpServerCreated(int statusCode, in android.net.dhcp.IDhcpServer server);
}
