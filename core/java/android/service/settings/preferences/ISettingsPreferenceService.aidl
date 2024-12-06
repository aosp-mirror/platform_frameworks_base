package android.service.settings.preferences;

import android.service.settings.preferences.GetValueRequest;
import android.service.settings.preferences.IGetValueCallback;
import android.service.settings.preferences.IMetadataCallback;
import android.service.settings.preferences.ISetValueCallback;
import android.service.settings.preferences.MetadataRequest;
import android.service.settings.preferences.SetValueRequest;

/** @hide */
oneway interface ISettingsPreferenceService {
    @EnforcePermission("READ_SYSTEM_PREFERENCES")
    void getAllPreferenceMetadata(in MetadataRequest request, IMetadataCallback callback) = 1;
    @EnforcePermission("READ_SYSTEM_PREFERENCES")
    void getPreferenceValue(in GetValueRequest request, IGetValueCallback callback) = 2;
    @EnforcePermission(allOf = {"READ_SYSTEM_PREFERENCES", "WRITE_SYSTEM_PREFERENCES"})
    void setPreferenceValue(in SetValueRequest request, ISetValueCallback callback) = 3;
}
