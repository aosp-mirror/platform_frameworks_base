package android.app.ondeviceintelligence;

import java.util.List;
import android.app.ondeviceintelligence.Feature;
import android.os.PersistableBundle;

/**
  * Interface for receiving list of supported features.
  *
  * @hide
  */
oneway interface IListFeaturesCallback {
    void onSuccess(in List<Feature> result) = 1;
    void onFailure(int errorCode, in String errorMessage, in PersistableBundle errorParams) = 2;
}
