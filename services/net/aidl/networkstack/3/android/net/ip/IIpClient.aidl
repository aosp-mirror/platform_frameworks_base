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
interface IIpClient {
  oneway void completedPreDhcpAction();
  oneway void confirmConfiguration();
  oneway void readPacketFilterComplete(in byte[] data);
  oneway void shutdown();
  oneway void startProvisioning(in android.net.ProvisioningConfigurationParcelable req);
  oneway void stop();
  oneway void setTcpBufferSizes(in String tcpBufferSizes);
  oneway void setHttpProxy(in android.net.ProxyInfo proxyInfo);
  oneway void setMulticastFilter(boolean enabled);
  oneway void addKeepalivePacketFilter(int slot, in android.net.TcpKeepalivePacketDataParcelable pkt);
  oneway void removeKeepalivePacketFilter(int slot);
  oneway void setL2KeyAndGroupHint(in String l2Key, in String groupHint);
  oneway void addNattKeepalivePacketFilter(int slot, in android.net.NattKeepalivePacketDataParcelable pkt);
}
