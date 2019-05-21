package android.net;
interface INetworkMonitorCallbacks {
  oneway void onNetworkMonitorCreated(in android.net.INetworkMonitor networkMonitor);
  oneway void notifyNetworkTested(int testResult, @nullable String redirectUrl);
  oneway void notifyPrivateDnsConfigResolved(in android.net.PrivateDnsConfigParcel config);
  oneway void showProvisioningNotification(String action, String packageName);
  oneway void hideProvisioningNotification();
}
