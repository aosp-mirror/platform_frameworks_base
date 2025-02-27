package android.service.settings.preferences;

import android.service.settings.preferences.GetValueResult;

/** @hide */
oneway interface IGetValueCallback {
    void onSuccess(in GetValueResult result) = 1;
    void onFailure() = 2;
}
