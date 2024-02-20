package android.app.ondeviceintelligence;

import android.app.ondeviceintelligence.Content;
import android.app.ondeviceintelligence.IResponseCallback;
import android.app.ondeviceintelligence.IProcessingSignal;
import android.os.PersistableBundle;


/**
  * This callback is a streaming variant of {@link IResponseCallback}.
  *
  * @hide
  */
interface IStreamingResponseCallback {
    void onNewContent(in Content result) = 1;
    void onSuccess(in Content result) = 2;
    void onFailure(int errorCode, in String errorMessage, in PersistableBundle errorParams) = 3;
}
