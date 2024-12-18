package android.app.ondeviceintelligence;

import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.Bundle;


/**
  * This callback is a streaming variant of {@link IResponseCallback}.
  *
  * @hide
  */
oneway interface IStreamingResponseCallback {
    void onNewContent(in Bundle processedResult) = 1;
    void onSuccess(in Bundle result) = 2;
    void onFailure(int errorCode, in String errorMessage, in PersistableBundle errorParams) = 3;
    void onDataAugmentRequest(in Bundle processedContent, in RemoteCallback responseCallback) = 4;
}
