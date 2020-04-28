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
}
