package android.service.ondeviceintelligence;

import android.os.PersistableBundle;

/**
  * Interface for receiving status from a updateProcessingState call from on-device intelligence
  * service.
  *
  * @hide
  */
interface IProcessingUpdateStatusCallback {
    void onSuccess(in PersistableBundle statusParams) = 1;
    void onFailure(int errorCode, in String errorMessage) = 2;
}
