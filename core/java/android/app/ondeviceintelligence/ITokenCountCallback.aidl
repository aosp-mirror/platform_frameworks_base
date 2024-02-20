package android.app.ondeviceintelligence;

import android.os.PersistableBundle;

/**
  * Interface for receiving the token count of a request for a given features.
  *
  * @hide
  */
interface ITokenCountCallback {
    void onSuccess(long tokenCount) = 1;
    void onFailure(int errorCode, in String errorMessage, in PersistableBundle errorParams) = 2;
}
