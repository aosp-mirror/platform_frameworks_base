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

import static android.service.settings.preferences.SettingsPreferenceService.ACTION_PREFERENCE_SERVICE;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.OutcomeReceiver;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.flags.Flags;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Client class responsible for binding to and interacting with an instance of
 * {@link SettingsPreferenceService}.
 * <p>This is a convenience class to handle the lifecycle of the service connection.
 * <p>This client will only interact with one instance at a time,
 * so if the caller requires multiple instances (multiple applications that provide settings), then
 * the caller must create multiple client classes, one for each instance required. To find all
 * available services, a caller may query {@link android.content.pm.PackageManager} for applications
 * that provide the intent action {@link SettingsPreferenceService#ACTION_PREFERENCE_SERVICE} that
 * are also system applications ({@link android.content.pm.ApplicationInfo#FLAG_SYSTEM}).
 */
@FlaggedApi(Flags.FLAG_SETTINGS_CATALYST)
public class SettingsPreferenceServiceClient implements AutoCloseable {

    private final Context mContext;
    private final Intent mServiceIntent;
    private final ServiceConnection mServiceConnection;
    private final boolean mSystemOnly;
    private ISettingsPreferenceService mRemoteService;

    /**
     * Construct a client for binding to a {@link SettingsPreferenceService} provided by the
     * application corresponding to the provided package name.
     * @param packageName - package name for which this client will initiate a service binding
     */
    public SettingsPreferenceServiceClient(@NonNull Context context,
                                           @NonNull String packageName) {
        this(context, packageName, true, null);
    }

    /**
     * @hide Only to be called directly by test
     */
    @TestApi
    public SettingsPreferenceServiceClient(@NonNull Context context,
                                           @NonNull String packageName,
                                           boolean systemOnly,
                                           @Nullable ServiceConnection connectionListener) {
        mContext = context.getApplicationContext();
        mServiceIntent = new Intent(ACTION_PREFERENCE_SERVICE).setPackage(packageName);
        mSystemOnly = systemOnly;
        mServiceConnection = createServiceConnection(connectionListener);
    }

    /**
     * Initiate binding to service.
     * <p>If no service exists for the package provided or the package is not for a system
     * application, no binding will occur.
     */
    public void start() {
        PackageManager pm = mContext.getPackageManager();
        PackageManager.ResolveInfoFlags flags;
        if (mSystemOnly) {
            flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY);
        } else {
            flags = PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL);
        }
        List<ResolveInfo> infos = pm.queryIntentServices(mServiceIntent, flags);
        if (infos.size() == 1) {
            mContext.bindService(mServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        }
    }

    /**
     * If there is an active service binding, unbind from that service.
     */
    public void stop() {
        if (mRemoteService != null) {
            mRemoteService = null;
            mContext.unbindService(mServiceConnection);
        }
    }

    /**
     * Retrieve the metadata for all exposed settings preferences within the application.
     * @param request object to specify request parameters
     * @param executor {@link Executor} on which to invoke the receiver
     * @param receiver callback to receive the result or failure
     */
    public void getAllPreferenceMetadata(
            @NonNull MetadataRequest request,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OutcomeReceiver<MetadataResult, Exception> receiver) {
        if (mRemoteService == null) {
            executor.execute(() ->
                    receiver.onError(new IllegalStateException("Service not ready")));
            return;
        }
        try {
            mRemoteService.getAllPreferenceMetadata(request, new IMetadataCallback.Stub() {
                @Override
                public void onSuccess(MetadataResult result) {
                    executor.execute(() -> receiver.onResult(result));
                }

                @Override
                public void onFailure() {
                    executor.execute(() -> receiver.onError(
                            new IllegalStateException("Service call failure")));
                }
            });
        } catch (RemoteException | RuntimeException e) {
            executor.execute(() -> receiver.onError(e));
        }
    }

    /**
     * Retrieve the current value of the requested settings preference.
     * @param request object to specify request parameters
     * @param executor {@link Executor} on which to invoke the receiver
     * @param receiver callback to receive the result or failure
     */
    public void getPreferenceValue(@NonNull GetValueRequest request,
                                   @CallbackExecutor @NonNull Executor executor,
                                   @NonNull OutcomeReceiver<GetValueResult, Exception> receiver) {
        if (mRemoteService == null) {
            executor.execute(() ->
                    receiver.onError(new IllegalStateException("Service not ready")));
            return;
        }
        try {
            mRemoteService.getPreferenceValue(request, new IGetValueCallback.Stub() {
                @Override
                public void onSuccess(GetValueResult result) {
                    executor.execute(() -> receiver.onResult(result));
                }

                @Override
                public void onFailure() {
                    executor.execute(() -> receiver.onError(
                            new IllegalStateException("Service call failure")));
                }
            });
        } catch (RemoteException | RuntimeException e) {
            executor.execute(() -> receiver.onError(e));
        }
    }

    /**
     * Set the value on the target settings preference.
     * @param request object to specify request parameters
     * @param executor {@link Executor} on which to invoke the receiver
     * @param receiver callback to receive the result or failure
     */
    public void setPreferenceValue(@NonNull SetValueRequest request,
                                   @CallbackExecutor @NonNull Executor executor,
                                   @NonNull OutcomeReceiver<SetValueResult, Exception> receiver) {
        if (mRemoteService == null) {
            executor.execute(() ->
                    receiver.onError(new IllegalStateException("Service not ready")));
            return;
        }
        try {
            mRemoteService.setPreferenceValue(request, new ISetValueCallback.Stub() {
                @Override
                public void onSuccess(SetValueResult result) {
                    executor.execute(() -> receiver.onResult(result));
                }

                @Override
                public void onFailure() {
                    executor.execute(() -> receiver.onError(
                            new IllegalStateException("Service call failure")));
                }
            });
        } catch (RemoteException | RuntimeException e) {
            executor.execute(() -> receiver.onError(e));
        }
    }

    @NonNull
    private ServiceConnection createServiceConnection(@Nullable ServiceConnection listener) {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mRemoteService = getPreferenceServiceInterface(service);
                if (listener != null) {
                    listener.onServiceConnected(name, service);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mRemoteService = null;
                if (listener != null) {
                    listener.onServiceDisconnected(name);
                }
            }
        };
    }

    @NonNull
    private ISettingsPreferenceService getPreferenceServiceInterface(@NonNull IBinder service) {
        return ISettingsPreferenceService.Stub.asInterface(service);
    }

    /**
     * This client handles a resource, thus is it important to appropriately close that resource
     * when it is no longer needed.
     * <p>This method is provided by {@link AutoCloseable} and calling it
     * will unbind any service binding.
     */
    @Override
    public void close() {
        stop();
    }
}
