package android.net.ipmemorystore;
interface IOnBlobRetrievedListener {
  oneway void onBlobRetrieved(in android.net.ipmemorystore.StatusParcelable status, in String l2Key, in String name, in android.net.ipmemorystore.Blob data);
}
