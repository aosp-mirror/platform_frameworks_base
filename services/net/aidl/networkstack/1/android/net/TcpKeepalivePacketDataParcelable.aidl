package android.net;
parcelable TcpKeepalivePacketDataParcelable {
  byte[] srcAddress;
  int srcPort;
  byte[] dstAddress;
  int dstPort;
  int seq;
  int ack;
  int rcvWnd;
  int rcvWndScale;
  int tos;
  int ttl;
}
