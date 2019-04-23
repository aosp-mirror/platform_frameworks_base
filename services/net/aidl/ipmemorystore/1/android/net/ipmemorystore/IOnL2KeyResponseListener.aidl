package android.net.ipmemorystore;
interface IOnL2KeyResponseListener {
  oneway void onL2KeyResponse(in android.net.ipmemorystore.StatusParcelable status, in String l2Key);
}
