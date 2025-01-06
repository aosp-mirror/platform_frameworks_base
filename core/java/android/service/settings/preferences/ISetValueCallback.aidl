package android.service.settings.preferences;

import android.service.settings.preferences.SetValueResult;

/** @hide */
oneway interface ISetValueCallback {
    void onSuccess(in SetValueResult result);
    void onFailure();
}
