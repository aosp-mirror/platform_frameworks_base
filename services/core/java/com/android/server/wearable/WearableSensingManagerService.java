/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wearable;

import static android.provider.DeviceConfig.NAMESPACE_WEARABLE_SENSING;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityOptions;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.ambientcontext.AmbientContextEvent;
import android.app.wearable.IWearableSensingCallback;
import android.app.wearable.IWearableSensingManager;
import android.app.wearable.WearableSensingDataRequest;
import android.app.wearable.WearableSensingManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteCallback;
import android.os.ResultReceiver;
import android.os.SharedMemory;
import android.os.ShellCallback;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.service.wearable.WearableSensingDataRequester;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;
import com.android.server.pm.KnownPackages;
import com.android.server.utils.quota.MultiRateLimiter;

import java.io.FileDescriptor;
import java.time.Duration;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * System service for managing sensing {@link AmbientContextEvent}s on Wearables.
 *
 * <p>The use of "Wearable" here is not the same as the Android Wear platform and should be treated
 * separately. </p>
 */
public class WearableSensingManagerService extends
        AbstractMasterSystemService<WearableSensingManagerService,
                WearableSensingManagerPerUserService> {
    private static final String TAG = WearableSensingManagerService.class.getSimpleName();
    private static final String KEY_SERVICE_ENABLED = "service_enabled";

    /** Default value in absence of {@link DeviceConfig} override. */
    private static final boolean DEFAULT_SERVICE_ENABLED = true;

    public static final int MAX_TEMPORARY_SERVICE_DURATION_MS = 30000;

    private static final String RATE_LIMITER_PACKAGE_NAME = "android";
    private static final String RATE_LIMITER_TAG =
            WearableSensingManagerService.class.getSimpleName();

    private static final class DataRequestObserverContext {
        final int mDataType;
        final int mUserId;
        final int mDataRequestObserverId;
        @NonNull final PendingIntent mDataRequestPendingIntent;
        @NonNull final RemoteCallback mDataRequestRemoteCallback;

        DataRequestObserverContext(
                int dataType,
                int userId,
                int dataRequestObserverId,
                PendingIntent dataRequestPendingIntent,
                RemoteCallback dataRequestRemoteCallback) {
            mDataType = dataType;
            mUserId = userId;
            mDataRequestObserverId = dataRequestObserverId;
            mDataRequestPendingIntent = dataRequestPendingIntent;
            mDataRequestRemoteCallback = dataRequestRemoteCallback;
        }
    }

    private final Context mContext;
    private final AtomicInteger mNextDataRequestObserverId = new AtomicInteger(1);
    private final Set<DataRequestObserverContext> mDataRequestObserverContexts = new HashSet<>();
    @NonNull private volatile MultiRateLimiter mDataRequestRateLimiter;
    volatile boolean mIsServiceEnabled;

    public WearableSensingManagerService(Context context) {
        super(context,
                new FrameworkResourcesServiceNameResolver(
                        context,
                        R.string.config_defaultWearableSensingService),
                /*disallowProperty=*/null,
                PACKAGE_UPDATE_POLICY_REFRESH_EAGER
                        | /*To avoid high latency*/ PACKAGE_RESTART_POLICY_REFRESH_EAGER);
        mContext = context;
        mDataRequestRateLimiter =
                new MultiRateLimiter.Builder(context)
                        .addRateLimit(
                                WearableSensingDataRequest.getRateLimit(),
                                WearableSensingDataRequest.getRateLimitWindowSize())
                        .build();
    }

    @Override
    public void onStart() {
        publishBinderService(
                Context.WEARABLE_SENSING_SERVICE, new WearableSensingManagerInternal());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            DeviceConfig.addOnPropertiesChangedListener(
                    NAMESPACE_WEARABLE_SENSING,
                    getContext().getMainExecutor(),
                    (properties) -> onDeviceConfigChange(properties.getKeyset()));

            mIsServiceEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_WEARABLE_SENSING,
                    KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
        }
    }


    private void onDeviceConfigChange(@NonNull Set<String> keys) {
        if (keys.contains(KEY_SERVICE_ENABLED)) {
            mIsServiceEnabled = DeviceConfig.getBoolean(
                    NAMESPACE_WEARABLE_SENSING,
                    KEY_SERVICE_ENABLED, DEFAULT_SERVICE_ENABLED);
        }
    }

    @Override
    protected WearableSensingManagerPerUserService newServiceLocked(int resolvedUserId,
            boolean disabled) {
        return new WearableSensingManagerPerUserService(this, mLock, resolvedUserId);
    }

    @Override
    protected void onServiceRemoved(
            WearableSensingManagerPerUserService service, @UserIdInt int userId) {
        Slog.d(TAG, "onServiceRemoved");
        service.destroyLocked();
    }

    @Override
    protected void onServicePackageRestartedLocked(@UserIdInt int userId) {
        Slog.d(TAG, "onServicePackageRestartedLocked.");
    }

    @Override
    protected void onServicePackageUpdatedLocked(@UserIdInt int userId) {
        Slog.d(TAG, "onServicePackageUpdatedLocked.");
    }

    @Override
    protected void enforceCallingPermissionForManagement() {
        getContext().enforceCallingPermission(
                Manifest.permission.ACCESS_AMBIENT_CONTEXT_EVENT, TAG);
    }

    @Override
    protected int getMaximumTemporaryServiceDurationMs() {
        if (Build.isDebuggable()) {
            return Integer.MAX_VALUE;
        }
        return MAX_TEMPORARY_SERVICE_DURATION_MS;
    }

    /** Returns {@code true} if the detection service is configured on this device. */
    public static boolean isDetectionServiceConfigured() {
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        final String[] packageNames = pmi.getKnownPackageNames(
                KnownPackages.PACKAGE_WEARABLE_SENSING, UserHandle.USER_SYSTEM);
        boolean isServiceConfigured = (packageNames.length != 0);
        Slog.i(TAG, "Wearable sensing service configured: " + isServiceConfigured);
        return isServiceConfigured;
    }

    /**
     * Returns the AmbientContextManagerPerUserService component for this user.
     */
    public ComponentName getComponentName(@UserIdInt int userId) {
        synchronized (mLock) {
            final WearableSensingManagerPerUserService service = getServiceForUserLocked(userId);
            if (service != null) {
                return service.getComponentName();
            }
        }
        return null;
    }

    /**
     * Provides a data stream to the WearableSensingService.
     *
     * <p>This method is only called from adb command via {@link WearableSensingShellCommand}.
     */
    @VisibleForTesting
    void provideDataStream(
            @UserIdInt int userId,
            ParcelFileDescriptor parcelFileDescriptor,
            RemoteCallback callback) {
        synchronized (mLock) {
            final WearableSensingManagerPerUserService mService = getServiceForUserLocked(userId);
            if (mService != null) {
                mService.onProvideDataStream(
                        parcelFileDescriptor, /* wearableSensingCallback= */ null, callback);
            } else {
                Slog.w(TAG, "Service not available.");
            }
        }
    }

    /**
     * Provides data to the WearableSensingService.
     *
     * <p>This method is only called from adb command via {@link WearableSensingShellCommand}.
     */
    @VisibleForTesting
    void provideData(
            @UserIdInt int userId,
            PersistableBundle data,
            SharedMemory sharedMemory,
            RemoteCallback callback) {
        synchronized (mLock) {
            final WearableSensingManagerPerUserService mService = getServiceForUserLocked(userId);
            if (mService != null) {
                mService.onProvidedData(data, sharedMemory, callback);
            } else {
                Slog.w(TAG, "Service not available.");
            }
        }
    }

    /**
     * Sets the window size used in data request rate limiting.
     *
     * <p>The new value will not be reflected in {@link
     * WearableSensingDataRequest#getRateLimitWindowSize()}.
     *
     * <p>{@code windowSize} will be automatically capped between
     * com.android.server.utils.quota.QuotaTracker#MIN_WINDOW_SIZE_MS and
     * com.android.server.utils.quota.QuotaTracker#MAX_WINDOW_SIZE_MS
     *
     * <p>The current rate limit will also be reset.
     *
     * <p>This method is only used for testing and must not be called in production code because
     * it effectively bypasses the rate limiting introduced to enhance privacy protection.
     */
    @VisibleForTesting
    void setDataRequestRateLimitWindowSize(@NonNull Duration windowSize) {
        Slog.w(
                TAG,
                TextUtils.formatSimple(
                        "Setting the data request rate limit window size to %s. This also resets"
                            + " the current limit and should only be callable from a test.",
                        windowSize));
        mDataRequestRateLimiter =
                new MultiRateLimiter.Builder(mContext)
                        .addRateLimit(WearableSensingDataRequest.getRateLimit(), windowSize)
                        .build();
    }

    /**
     * Resets the window size used in data request rate limiting back to the default value.
     *
     * <p>The current rate limit will also be reset.
     *
     * <p>This method is only used for testing and must not be called in production code because
     * it effectively bypasses the rate limiting introduced to enhance privacy protection.
     */
    @VisibleForTesting
    void resetDataRequestRateLimitWindowSize() {
        Slog.w(
                TAG,
                "Resetting the data request rate limit window size back to the default value. This"
                    + " also resets the current limit and should only be callable from a test.");
        mDataRequestRateLimiter =
                new MultiRateLimiter.Builder(mContext)
                        .addRateLimit(
                                WearableSensingDataRequest.getRateLimit(),
                                WearableSensingDataRequest.getRateLimitWindowSize())
                        .build();
    }

    private DataRequestObserverContext getDataRequestObserverContext(
            int dataType, int userId, PendingIntent dataRequestPendingIntent) {
        synchronized (mDataRequestObserverContexts) {
            for (DataRequestObserverContext observerContext : mDataRequestObserverContexts) {
                if (observerContext.mDataType == dataType
                        && observerContext.mUserId == userId
                        && observerContext.mDataRequestPendingIntent.equals(
                                dataRequestPendingIntent)) {
                    return observerContext;
                }
            }
        }
        return null;
    }

    @NonNull
    private RemoteCallback createDataRequestRemoteCallback(
            PendingIntent dataRequestPendingIntent, int userId) {
        return new RemoteCallback(
                bundle -> {
                    WearableSensingDataRequest dataRequest =
                            bundle.getParcelable(
                                    WearableSensingDataRequest.REQUEST_BUNDLE_KEY,
                                    WearableSensingDataRequest.class);
                    if (dataRequest == null) {
                        Slog.e(TAG, "Received data request callback without a request.");
                        return;
                    }
                    RemoteCallback dataRequestStatusCallback =
                            bundle.getParcelable(
                                    WearableSensingDataRequest.REQUEST_STATUS_CALLBACK_BUNDLE_KEY,
                                    RemoteCallback.class);
                    if (dataRequestStatusCallback == null) {
                        Slog.e(TAG, "Received data request callback without a status callback.");
                        return;
                    }
                    if (dataRequest.getDataSize()
                            > WearableSensingDataRequest.getMaxRequestSize()) {
                        Slog.w(
                                TAG,
                                TextUtils.formatSimple(
                                        "WearableSensingDataRequest size exceeds the maximum"
                                            + " allowed size of %s bytes. Dropping the request.",
                                        WearableSensingDataRequest.getMaxRequestSize()));
                        WearableSensingManagerPerUserService.notifyStatusCallback(
                                dataRequestStatusCallback,
                                WearableSensingDataRequester.STATUS_TOO_LARGE);
                        return;
                    }
                    if (!mDataRequestRateLimiter.isWithinQuota(
                            userId, RATE_LIMITER_PACKAGE_NAME, RATE_LIMITER_TAG)) {
                        Slog.w(TAG, "Data request exceeded rate limit. Dropping the request.");
                        WearableSensingManagerPerUserService.notifyStatusCallback(
                                dataRequestStatusCallback,
                                WearableSensingDataRequester.STATUS_TOO_FREQUENT);
                        return;
                    }
                    Intent intent = new Intent();
                    intent.putExtra(
                            WearableSensingManager.EXTRA_WEARABLE_SENSING_DATA_REQUEST,
                            dataRequest);
                    BroadcastOptions options = BroadcastOptions.makeBasic();
                    options.setPendingIntentBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_DENIED);
                    mDataRequestRateLimiter.noteEvent(
                            userId, RATE_LIMITER_PACKAGE_NAME, RATE_LIMITER_TAG);
                    final long previousCallingIdentity = Binder.clearCallingIdentity();
                    try {
                        dataRequestPendingIntent.send(
                                getContext(), 0, intent, null, null, null, options.toBundle());
                        WearableSensingManagerPerUserService.notifyStatusCallback(
                                dataRequestStatusCallback,
                                WearableSensingDataRequester.STATUS_SUCCESS);
                        Slog.i(
                                TAG,
                                TextUtils.formatSimple(
                                        "Sending data request to %s: %s",
                                        dataRequestPendingIntent.getCreatorPackage(),
                                        dataRequest.toExpandedString()));
                    } catch (PendingIntent.CanceledException e) {
                        Slog.w(TAG, "Could not deliver pendingIntent: " + dataRequestPendingIntent);
                        WearableSensingManagerPerUserService.notifyStatusCallback(
                                dataRequestStatusCallback,
                                WearableSensingDataRequester.STATUS_OBSERVER_CANCELLED);
                    } finally {
                        Binder.restoreCallingIdentity(previousCallingIdentity);
                    }
                });
    }

    private void callPerUserServiceIfExist(
            Consumer<WearableSensingManagerPerUserService> serviceConsumer,
            RemoteCallback statusCallback) {
        int userId = UserHandle.getCallingUserId();
        synchronized (mLock) {
            WearableSensingManagerPerUserService service = getServiceForUserLocked(userId);
            if (service == null) {
                Slog.w(TAG, "Service not available for userId " + userId);
                WearableSensingManagerPerUserService.notifyStatusCallback(statusCallback,
                        WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            serviceConsumer.accept(service);
        }
    }

    private final class WearableSensingManagerInternal extends IWearableSensingManager.Stub {

        @Override
        public void provideConnection(
                ParcelFileDescriptor wearableConnection,
                IWearableSensingCallback wearableSensingCallback,
                RemoteCallback statusCallback) {
            Slog.i(TAG, "WearableSensingManagerInternal provideConnection.");
            Objects.requireNonNull(wearableConnection);
            Objects.requireNonNull(statusCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available.");
                WearableSensingManagerPerUserService.notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            callPerUserServiceIfExist(
                    service ->
                            service.onProvideConnection(
                                    wearableConnection, wearableSensingCallback, statusCallback),
                    statusCallback);
        }

        @Override
        public void provideDataStream(
                ParcelFileDescriptor parcelFileDescriptor,
                @Nullable IWearableSensingCallback wearableSensingCallback,
                RemoteCallback statusCallback) {
            Slog.i(TAG, "WearableSensingManagerInternal provideDataStream.");
            Objects.requireNonNull(parcelFileDescriptor);
            Objects.requireNonNull(statusCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available.");
                WearableSensingManagerPerUserService.notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            callPerUserServiceIfExist(
                    service ->
                            service.onProvideDataStream(
                                    parcelFileDescriptor, wearableSensingCallback, statusCallback),
                    statusCallback);
        }

        @Override
        public void provideData(
                PersistableBundle data,
                SharedMemory sharedMemory,
                RemoteCallback callback) {
            Slog.d(TAG, "WearableSensingManagerInternal provideData.");
            Objects.requireNonNull(data);
            Objects.requireNonNull(callback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available.");
                WearableSensingManagerPerUserService.notifyStatusCallback(
                        callback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            callPerUserServiceIfExist(
                    service -> service.onProvidedData(data, sharedMemory, callback),
                    callback);
        }

        @Override
        public void registerDataRequestObserver(
                int dataType,
                PendingIntent dataRequestPendingIntent,
                RemoteCallback statusCallback) {
            Slog.i(TAG, "WearableSensingManagerInternal registerDataRequestObserver.");
            Objects.requireNonNull(dataRequestPendingIntent);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available.");
                WearableSensingManagerPerUserService.notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            int userId = UserHandle.getCallingUserId();
            RemoteCallback dataRequestCallback;
            int dataRequestObserverId;
            synchronized (mDataRequestObserverContexts) {
                DataRequestObserverContext previousObserverContext =
                        getDataRequestObserverContext(dataType, userId, dataRequestPendingIntent);
                if (previousObserverContext != null) {
                    Slog.i(TAG, "Received duplicate data request observer.");
                    dataRequestCallback = previousObserverContext.mDataRequestRemoteCallback;
                    dataRequestObserverId = previousObserverContext.mDataRequestObserverId;
                } else {
                    dataRequestCallback =
                            createDataRequestRemoteCallback(dataRequestPendingIntent, userId);
                    dataRequestObserverId = mNextDataRequestObserverId.getAndIncrement();
                    mDataRequestObserverContexts.add(
                            new DataRequestObserverContext(
                                    dataType,
                                    userId,
                                    dataRequestObserverId,
                                    dataRequestPendingIntent,
                                    dataRequestCallback));
                }
            }
            callPerUserServiceIfExist(
                    service ->
                            service.onRegisterDataRequestObserver(
                                    dataType,
                                    dataRequestCallback,
                                    dataRequestObserverId,
                                    dataRequestPendingIntent.getCreatorPackage(),
                                    statusCallback),
                    statusCallback);
        }

        @Override
        public void unregisterDataRequestObserver(
                int dataType,
                PendingIntent dataRequestPendingIntent,
                RemoteCallback statusCallback) {
            Slog.i(TAG, "WearableSensingManagerInternal unregisterDataRequestObserver.");
            Objects.requireNonNull(dataRequestPendingIntent);
            Objects.requireNonNull(statusCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available.");
                WearableSensingManagerPerUserService.notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            int userId = UserHandle.getCallingUserId();
            int previousDataRequestObserverId;
            String pendingIntentCreatorPackage;
            synchronized (mDataRequestObserverContexts) {
                DataRequestObserverContext previousObserverContext =
                        getDataRequestObserverContext(dataType, userId, dataRequestPendingIntent);
                if (previousObserverContext == null) {
                    Slog.w(TAG, "Previous observer not found, cannot unregister.");
                    return;
                }
                mDataRequestObserverContexts.remove(previousObserverContext);
                previousDataRequestObserverId = previousObserverContext.mDataRequestObserverId;
                pendingIntentCreatorPackage =
                        previousObserverContext.mDataRequestPendingIntent.getCreatorPackage();
            }
            callPerUserServiceIfExist(
                    service ->
                            service.onUnregisterDataRequestObserver(
                                    dataType,
                                    previousDataRequestObserverId,
                                    pendingIntentCreatorPackage,
                                    statusCallback),
                    statusCallback);
        }

        @Override
        public void startHotwordRecognition(
                ComponentName targetVisComponentName, RemoteCallback statusCallback) {
            Slog.i(TAG, "WearableSensingManagerInternal startHotwordRecognition.");
            Objects.requireNonNull(statusCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available.");
                WearableSensingManagerPerUserService.notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            callPerUserServiceIfExist(
                    service ->
                            service.onStartHotwordRecognition(
                                    targetVisComponentName, statusCallback),
                    statusCallback);
        }

        @Override
        public void stopHotwordRecognition(RemoteCallback statusCallback) {
            Slog.i(TAG, "WearableSensingManagerInternal stopHotwordRecognition.");
            Objects.requireNonNull(statusCallback);
            mContext.enforceCallingOrSelfPermission(
                    Manifest.permission.MANAGE_WEARABLE_SENSING_SERVICE, TAG);
            if (!mIsServiceEnabled) {
                Slog.w(TAG, "Service not available.");
                WearableSensingManagerPerUserService.notifyStatusCallback(
                        statusCallback, WearableSensingManager.STATUS_SERVICE_UNAVAILABLE);
                return;
            }
            callPerUserServiceIfExist(
                    service -> service.onStopHotwordRecognition(statusCallback), statusCallback);
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
                String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
            new WearableSensingShellCommand(WearableSensingManagerService.this).exec(
                    this, in, out, err, args, callback, resultReceiver);
        }
    }
}
