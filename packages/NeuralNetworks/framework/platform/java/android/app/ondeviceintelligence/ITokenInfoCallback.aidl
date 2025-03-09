package android.app.ondeviceintelligence;

import android.os.PersistableBundle;
import android.app.ondeviceintelligence.TokenInfo;

/**
  * Interface for receiving the token info of a request for a given feature.
  *
  * @hide
  */
oneway interface ITokenInfoCallback {
    void onSuccess(in TokenInfo tokenInfo) = 1;
    void onFailure(int errorCode, in String errorMessage, in PersistableBundle errorParams) = 2;
}
