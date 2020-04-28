package android.net;
interface INetworkStackStatusCallback {
  oneway void onStatusAvailable(int statusCode);
}
