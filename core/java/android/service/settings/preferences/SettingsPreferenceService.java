/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.settings.preferences;

import android.Manifest;
import android.annotation.EnforcePermission;
import android.annotation.FlaggedApi;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.PermissionEnforcer;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.flags.Flags;

/**
 * Base class for a service that exposes its settings preferences to external access.
 * <p>This class is to be implemented by apps that contribute to the Android Settings surface.
 * Access to this service is permission guarded by
 * {@link android.permission.READ_SYSTEM_PREFERENCES} for binding and reading, and guarded by both
 * {@link android.permission.READ_SYSTEM_PREFERENCES} and
 * {@link android.permission.WRITE_SYSTEM_PREFERENCES} for writing. An additional checks for access
 * control are the responsibility of the implementing class.
 *
 * <p>This implementation must correspond to an exported service declaration in the host app
 * AndroidManifest.xml as follows
 * <pre class="prettyprint">
 * {@literal
 * <service
 *     android:permission="android.permission.READ_SYSTEM_PREFERENCES"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.service.settings.preferences.action.PREFERENCE_SERVICE" />
 *     </intent-filter>
 * </service>}
 * </pre>
 *
 * <ul>
 *   <li>It is recommended to expose the metadata for most, if not all, preferences within a
 *   settings app, thus implementing {@link #onGetAllPreferenceMetadata}.
 *   <li>Exposing preferences for read access of their values is up to the implementer, but any
 *   exposed must be a subset of the preferences exposed in {@link #onGetAllPreferenceMetadata}.
 *   To expose a preference for read access, the implementation will contain
 *   {@link #onGetPreferenceValue}.
 *   <li>Exposing a preference for write access of their values is up to the implementer, but should
 *   be done so with extra care and consideration, both for security and privacy. These must also
 *   be a subset of those exposed in {@link #onGetAllPreferenceMetadata}. To expose a preference for
 *   write access, the implementation will contain {@link #onSetPreferenceValue}.
 * </ul>
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public abstract class SettingsPreferenceService extends Service {

    /**
     * Intent Action corresponding to a {@link SettingsPreferenceService}. Note that any checks for
     * such services must be accompanied by a check to ensure the host is a system application.
     * Given an {@link android.content.pm.ApplicationInfo} you can check for
     * {@link android.content.pm.ApplicationInfo#FLAG_SYSTEM}, or when querying
     * {@link PackageManager#queryIntentServices} you can provide the flag
     * {@link PackageManager#MATCH_SYSTEM_ONLY}.
     */
    public static final String ACTION_PREFERENCE_SERVICE =
            "android.service.settings.preferences.action.PREFERENCE_SERVICE";

    /** @hide */
    @NonNull
    @Override
    public final IBinder onBind(@Nullable Intent intent) {
        return new ISettingsPreferenceService.Stub(
                PermissionEnforcer.fromContext(getApplicationContext())) {
            @EnforcePermission(Manifest.permission.READ_SYSTEM_PREFERENCES)
            @Override
            public void getAllPreferenceMetadata(MetadataRequest request,
                                                 IMetadataCallback callback) {
                getAllPreferenceMetadata_enforcePermission();
                onGetAllPreferenceMetadata(request, new OutcomeReceiver<>() {
                    @Override
                    public void onResult(MetadataResult result) {
                        try {
                            callback.onSuccess(result);
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        try {
                            callback.onFailure();
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                    }
                });
            }

            @EnforcePermission(Manifest.permission.READ_SYSTEM_PREFERENCES)
            @Override
            public void getPreferenceValue(GetValueRequest request, IGetValueCallback callback) {
                getPreferenceValue_enforcePermission();
                onGetPreferenceValue(request, new OutcomeReceiver<>() {
                    @Override
                    public void onResult(GetValueResult result) {
                        try {
                            callback.onSuccess(result);
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        try {
                            callback.onFailure();
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                    }
                });
            }

            @EnforcePermission(allOf = {
                    Manifest.permission.READ_SYSTEM_PREFERENCES,
                    Manifest.permission.WRITE_SYSTEM_PREFERENCES
            })
            @Override
            public void setPreferenceValue(SetValueRequest request, ISetValueCallback callback) {
                setPreferenceValue_enforcePermission();
                onSetPreferenceValue(request, new OutcomeReceiver<>() {
                    @Override
                    public void onResult(SetValueResult result) {
                        try {
                            callback.onSuccess(result);
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        try {
                            callback.onFailure();
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                    }
                });
            }
        };
    }

    /**
     * Retrieve the metadata for all exposed settings preferences within this application. This
     * data should be a snapshot of their state at the time of this method being called.
     * @param request object to specify request parameters
     * @param callback object to receive result or failure of request
     */
    public abstract void onGetAllPreferenceMetadata(
            @NonNull MetadataRequest request,
            @NonNull OutcomeReceiver<MetadataResult, Exception> callback);

    /**
     * Retrieve the current value of the requested settings preference. If this value is not exposed
     * or cannot be obtained for some reason, the corresponding result code will be set on the
     * result object.
     * @param request object to specify request parameters
     * @param callback object to receive result or failure of request
     */
    public abstract void onGetPreferenceValue(
            @NonNull GetValueRequest request,
            @NonNull OutcomeReceiver<GetValueResult, Exception> callback);

    /**
     * Set the value within the request to the target settings preference. If this value cannot
     * be written for some reason, the corresponding result code will be set on the result object.
     * @param request object to specify request parameters
     * @param callback object to receive result or failure of request
     */
    public abstract void onSetPreferenceValue(
            @NonNull SetValueRequest request,
            @NonNull OutcomeReceiver<SetValueResult, Exception> callback);
}
