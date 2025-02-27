package android.service.settings.preferences;

import android.service.settings.preferences.MetadataResult;

/** @hide */
oneway interface IMetadataCallback {
    void onSuccess(in MetadataResult result);
    void onFailure();
}
