package android.app.ondeviceintelligence;

import android.app.ondeviceintelligence.Feature;
import android.os.PersistableBundle;

/**
  * Interface for receiving a feature for the given identifier.
  *
  * @hide
  */
oneway interface IFeatureCallback {
    void onSuccess(in Feature result) = 1;
    void onFailure(int errorCode, in String errorMessage, in PersistableBundle errorParams) = 2;
}
