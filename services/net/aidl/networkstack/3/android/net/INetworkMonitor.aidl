package android.net;
interface INetworkMonitor {
  oneway void start();
  oneway void launchCaptivePortalApp();
  oneway void notifyCaptivePortalAppFinished(int response);
  oneway void setAcceptPartialConnectivity();
  oneway void forceReevaluation(int uid);
  oneway void notifyPrivateDnsChanged(in android.net.PrivateDnsConfigParcel config);
  oneway void notifyDnsResponse(int returnCode);
  oneway void notifyNetworkConnected(in android.net.LinkProperties lp, in android.net.NetworkCapabilities nc);
  oneway void notifyNetworkDisconnected();
  oneway void notifyLinkPropertiesChanged(in android.net.LinkProperties lp);
  oneway void notifyNetworkCapabilitiesChanged(in android.net.NetworkCapabilities nc);
  const int NETWORK_TEST_RESULT_VALID = 0;
  const int NETWORK_TEST_RESULT_INVALID = 1;
  const int NETWORK_TEST_RESULT_PARTIAL_CONNECTIVITY = 2;
  const int NETWORK_VALIDATION_RESULT_VALID = 1;
  const int NETWORK_VALIDATION_RESULT_PARTIAL = 2;
  const int NETWORK_VALIDATION_PROBE_DNS = 4;
  const int NETWORK_VALIDATION_PROBE_HTTP = 8;
  const int NETWORK_VALIDATION_PROBE_HTTPS = 16;
  const int NETWORK_VALIDATION_PROBE_FALLBACK = 32;
  const int NETWORK_VALIDATION_PROBE_PRIVDNS = 64;
}
