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
