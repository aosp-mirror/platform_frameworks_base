package android.net;
interface IIpMemoryStore {
  oneway void storeNetworkAttributes(String l2Key, in android.net.ipmemorystore.NetworkAttributesParcelable attributes, android.net.ipmemorystore.IOnStatusListener listener);
  oneway void storeBlob(String l2Key, String clientId, String name, in android.net.ipmemorystore.Blob data, android.net.ipmemorystore.IOnStatusListener listener);
  oneway void findL2Key(in android.net.ipmemorystore.NetworkAttributesParcelable attributes, android.net.ipmemorystore.IOnL2KeyResponseListener listener);
  oneway void isSameNetwork(String l2Key1, String l2Key2, android.net.ipmemorystore.IOnSameL3NetworkResponseListener listener);
  oneway void retrieveNetworkAttributes(String l2Key, android.net.ipmemorystore.IOnNetworkAttributesRetrievedListener listener);
  oneway void retrieveBlob(String l2Key, String clientId, String name, android.net.ipmemorystore.IOnBlobRetrievedListener listener);
}
