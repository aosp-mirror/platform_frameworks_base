package android.net.dhcp;
interface IDhcpServer {
  oneway void start(in android.net.INetworkStackStatusCallback cb);
  oneway void updateParams(in android.net.dhcp.DhcpServingParamsParcel params, in android.net.INetworkStackStatusCallback cb);
  oneway void stop(in android.net.INetworkStackStatusCallback cb);
  const int STATUS_UNKNOWN = 0;
  const int STATUS_SUCCESS = 1;
  const int STATUS_INVALID_ARGUMENT = 2;
  const int STATUS_UNKNOWN_ERROR = 3;
}
