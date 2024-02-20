package android.app.ondeviceintelligence;

import android.app.ondeviceintelligence.FeatureDetails;
import android.os.PersistableBundle;

/**
  * Interface for receiving details about a given feature. .
  *
  * @hide
  */
interface IFeatureDetailsCallback {
    void onSuccess(in FeatureDetails result) = 1;
    void onFailure(int errorCode, in String errorMessage, in PersistableBundle errorParams) = 2;
}
