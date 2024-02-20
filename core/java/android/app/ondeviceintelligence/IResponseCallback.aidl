package android.app.ondeviceintelligence;

import android.app.ondeviceintelligence.Content;
import android.app.ondeviceintelligence.IProcessingSignal;
import android.os.PersistableBundle;

/**
  * Interface for a IResponseCallback for receiving response from on-device intelligence service.
  *
  * @hide
  */
interface IResponseCallback {
    void onSuccess(in Content result) = 1;
    void onFailure(int errorCode, in String errorMessage, in PersistableBundle errorParams) = 2;
}
