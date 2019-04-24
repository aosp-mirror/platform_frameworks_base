package android.net.ipmemorystore;
parcelable NetworkAttributesParcelable {
  byte[] assignedV4Address;
  long assignedV4AddressExpiry;
  String groupHint;
  android.net.ipmemorystore.Blob[] dnsAddresses;
  int mtu;
}
