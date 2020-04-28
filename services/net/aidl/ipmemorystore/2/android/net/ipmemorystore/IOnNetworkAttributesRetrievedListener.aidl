package android.net.ipmemorystore;
interface IOnNetworkAttributesRetrievedListener {
  oneway void onNetworkAttributesRetrieved(in android.net.ipmemorystore.StatusParcelable status, in String l2Key, in android.net.ipmemorystore.NetworkAttributesParcelable attributes);
}
