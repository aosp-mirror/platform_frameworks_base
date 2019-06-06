///////////////////////////////////////////////////////////////////////////////
// THIS FILE IS IMMUTABLE. DO NOT EDIT IN ANY CASE.                          //
///////////////////////////////////////////////////////////////////////////////

// This file is a frozen snapshot of an AIDL interface (or parcelable). Do not
// try to edit this file. It looks like you are doing that because you have
// modified an AIDL interface in a backward-incompatible way, e.g., deleting a
// function from an interface or a field from a parcelable and it broke the
// build. That breakage is intended.
//
// You must not make a backward incompatible changes to the AIDL files built
// with the aidl_interface module type with versions property set. The module
// type is used to build AIDL files in a way that they can be used across
// independently updatable components of the system. If a device is shipped
// with such a backward incompatible change, it has a high risk of breaking
// later when a module using the interface is updated, e.g., Mainline modules.

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
