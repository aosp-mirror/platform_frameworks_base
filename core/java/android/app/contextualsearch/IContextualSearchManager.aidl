package android.app.contextualsearch;

import android.app.contextualsearch.IContextualSearchCallback;
/**
 * @hide
 */
oneway interface IContextualSearchManager {
  void startContextualSearch(int entrypoint);
  void getContextualSearchState(in IBinder token, in IContextualSearchCallback callback);
}
