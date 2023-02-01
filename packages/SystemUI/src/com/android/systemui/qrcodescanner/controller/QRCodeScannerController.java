/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.qrcodescanner.controller;

import static android.provider.Settings.Secure.LOCK_SCREEN_SHOW_QR_CODE_SCANNER;

import android.annotation.IntDef;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.util.DeviceConfigProxy;
import com.android.systemui.util.settings.SecureSettings;

import org.jetbrains.annotations.NotNull;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;

/**
 * Controller to handle communication between SystemUI and QR Code Scanner provider.
 * Only listens to the {@link QRCodeScannerChangeEvent} if there is an active observer (i.e.
 * registerQRCodeScannerChangeObservers
 * for the required {@link QRCodeScannerChangeEvent} has been called).
 */
@SysUISingleton
public class QRCodeScannerController implements
        CallbackController<QRCodeScannerController.Callback> {
    /**
     * Event for the change in availability and preference of the QR code scanner.
     */
    public interface Callback {
        /**
         * Listener implementation for {@link QRCodeScannerChangeEvent}
         * DEFAULT_QR_CODE_SCANNER_CHANGE
         */
        default void onQRCodeScannerActivityChanged() {
        }

        /**
         * Listener implementation for {@link QRCodeScannerChangeEvent}
         * QR_CODE_SCANNER_PREFERENCE_CHANGE
         */
        default void onQRCodeScannerPreferenceChanged() {
        }
    }

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {DEFAULT_QR_CODE_SCANNER_CHANGE, QR_CODE_SCANNER_PREFERENCE_CHANGE})
    public @interface QRCodeScannerChangeEvent {
    }

    public static final int DEFAULT_QR_CODE_SCANNER_CHANGE = 0;
    public static final int QR_CODE_SCANNER_PREFERENCE_CHANGE = 1;

    private static final String TAG = "QRCodeScannerController";

    private final Context mContext;
    private final Executor mExecutor;
    private final SecureSettings mSecureSettings;
    private final DeviceConfigProxy mDeviceConfigProxy;
    private final ArrayList<Callback> mCallbacks = new ArrayList<>();
    private final UserTracker mUserTracker;
    private final boolean mConfigEnableLockScreenButton;

    private HashMap<Integer, ContentObserver> mQRCodeScannerPreferenceObserver = new HashMap<>();
    private DeviceConfig.OnPropertiesChangedListener mOnDefaultQRCodeScannerChangedListener = null;
    private UserTracker.Callback mUserChangedListener = null;

    private boolean mQRCodeScannerEnabled;
    private Intent mIntent = null;
    private String mQRCodeScannerActivity = null;
    private ComponentName mComponentName = null;
    private AtomicInteger mQRCodeScannerPreferenceChangeEvents = new AtomicInteger(0);
    private AtomicInteger mDefaultQRCodeScannerChangeEvents = new AtomicInteger(0);
    private Boolean mIsCameraAvailable = null;

    @Inject
    public QRCodeScannerController(
            Context context,
            @Background Executor executor,
            SecureSettings secureSettings,
            DeviceConfigProxy proxy,
            UserTracker userTracker) {
        mContext = context;
        mExecutor = executor;
        mSecureSettings = secureSettings;
        mDeviceConfigProxy = proxy;
        mUserTracker = userTracker;
        mConfigEnableLockScreenButton = mContext.getResources().getBoolean(
            android.R.bool.config_enableQrCodeScannerOnLockScreen);
    }

    /**
     * Add a callback for {@link QRCodeScannerChangeEvent} events
     */
    @Override
    public void addCallback(@NotNull Callback listener) {
        if (!isCameraAvailable()) return;

        synchronized (mCallbacks) {
            mCallbacks.add(listener);
        }
    }

    /**
     * Remove callback for {@link QRCodeScannerChangeEvent} events
     */
    @Override
    public void removeCallback(@NotNull Callback listener) {
        if (!isCameraAvailable()) return;

        synchronized (mCallbacks) {
            mCallbacks.remove(listener);
        }
    }

    /**
     * Returns a verified intent to start the QR code scanner activity.
     * Returns null if the intent is not available
     */
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * Returns true if lock screen entry point for QR Code Scanner is to be enabled.
     */
    public boolean isEnabledForLockScreenButton() {
        return mQRCodeScannerEnabled && mIntent != null && mConfigEnableLockScreenButton
                && isActivityCallable(mIntent);
    }

    /**
     * Returns true if quick settings entry point for QR Code Scanner is to be enabled.
     */
    public boolean isEnabledForQuickSettings() {
        return mIntent != null && isActivityCallable(mIntent);
    }

    /**
     * Register the change observers for {@link QRCodeScannerChangeEvent}
     *
     * @param events {@link QRCodeScannerChangeEvent} events that need to be handled.
     */
    public void registerQRCodeScannerChangeObservers(
            @QRCodeScannerChangeEvent int... events) {
        if (!isCameraAvailable()) return;

        for (int event : events) {
            switch (event) {
                case DEFAULT_QR_CODE_SCANNER_CHANGE:
                    mDefaultQRCodeScannerChangeEvents.incrementAndGet();
                    registerDefaultQRCodeScannerObserver();
                    break;
                case QR_CODE_SCANNER_PREFERENCE_CHANGE:
                    mQRCodeScannerPreferenceChangeEvents.incrementAndGet();
                    registerQRCodePreferenceObserver();
                    registerUserChangeObservers();
                    break;
                default:
                    Log.e(TAG, "Unrecognised event: " + event);
            }
        }
    }

    /**
     * Unregister the change observers for {@link QRCodeScannerChangeEvent}. Make sure only to call
     * this after registerQRCodeScannerChangeObservers
     *
     * @param events {@link QRCodeScannerChangeEvent} events that need to be handled.
     */
    public void unregisterQRCodeScannerChangeObservers(
            @QRCodeScannerChangeEvent int... events) {
        if (!isCameraAvailable()) return;

        for (int event : events) {
            switch (event) {
                case DEFAULT_QR_CODE_SCANNER_CHANGE:
                    if (mOnDefaultQRCodeScannerChangedListener == null) continue;

                    if (mDefaultQRCodeScannerChangeEvents.decrementAndGet() == 0) {
                        unregisterDefaultQRCodeScannerObserver();
                    }
                    break;
                case QR_CODE_SCANNER_PREFERENCE_CHANGE:
                    if (mUserTracker == null) continue;

                    if (mQRCodeScannerPreferenceChangeEvents.decrementAndGet() == 0) {
                        unregisterQRCodePreferenceObserver();
                        unregisterUserChangeObservers();
                    }
                    break;
                default:
                    Log.e(TAG, "Unrecognised event: " + event);
            }
        }
    }

    /** Returns true if camera is available on the device */
    public boolean isCameraAvailable() {
        if (mIsCameraAvailable == null) {
            mIsCameraAvailable = mContext.getPackageManager().hasSystemFeature(
                    PackageManager.FEATURE_CAMERA);
        }
        return mIsCameraAvailable;
    }

    private void updateQRCodeScannerPreferenceDetails(boolean updateSettings) {
        if (!mConfigEnableLockScreenButton) {
            // Settings only apply to lock screen entry point.
            return;
        }

        boolean prevQRCodeScannerEnabled = mQRCodeScannerEnabled;
        mQRCodeScannerEnabled = mSecureSettings.getIntForUser(LOCK_SCREEN_SHOW_QR_CODE_SCANNER, 0,
                mUserTracker.getUserId()) != 0;
        if (updateSettings) {
            mSecureSettings.putStringForUser(Settings.Secure.SHOW_QR_CODE_SCANNER_SETTING,
                    mQRCodeScannerActivity, mUserTracker.getUserId());
        }

        if (!Objects.equals(mQRCodeScannerEnabled, prevQRCodeScannerEnabled)) {
            notifyQRCodeScannerPreferenceChanged();
        }
    }

    private String getDefaultScannerActivity() {
        return mContext.getResources().getString(
            com.android.internal.R.string.config_defaultQrCodeComponent);
    }

    private void updateQRCodeScannerActivityDetails() {
        String qrCodeScannerActivity = mDeviceConfigProxy.getString(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER, "");

        // "" means either the flags is not available or is set to "", and in both the cases we
        // want to use R.string.config_defaultQrCodeComponent
        if (Objects.equals(qrCodeScannerActivity, "")) {
            qrCodeScannerActivity = getDefaultScannerActivity();
        }

        String prevQrCodeScannerActivity = mQRCodeScannerActivity;
        ComponentName componentName = null;
        Intent intent = new Intent();
        if (qrCodeScannerActivity != null) {
            componentName = ComponentName.unflattenFromString(qrCodeScannerActivity);
            intent.setComponent(componentName);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        }

        if (isActivityAvailable(intent)) {
            mQRCodeScannerActivity = qrCodeScannerActivity;
            mComponentName = componentName;
            mIntent = intent;
        } else {
            mQRCodeScannerActivity = null;
            mComponentName = null;
            mIntent = null;
        }

        if (!Objects.equals(mQRCodeScannerActivity, prevQrCodeScannerActivity)) {
            notifyQRCodeScannerActivityChanged();
        }
    }

    private boolean isActivityAvailable(Intent intent) {
        // Our intent should always be explicit and should have a component set
        if (intent.getComponent() == null) return false;

        int flags = PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS
                | PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS;
        return !mContext.getPackageManager().queryIntentActivities(intent,
                flags).isEmpty();
    }

    private boolean isActivityCallable(Intent intent) {
        // Our intent should always be explicit and should have a component set
        if (intent.getComponent() == null) return false;

        int flags = PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        return !mContext.getPackageManager().queryIntentActivities(intent,
                flags).isEmpty();
    }

    private void unregisterUserChangeObservers() {
        mUserTracker.removeCallback(mUserChangedListener);

        // Reset cached values to default as we are no longer listening
        mUserChangedListener = null;
        mQRCodeScannerEnabled = false;
    }

    private void unregisterQRCodePreferenceObserver() {
        if (!mConfigEnableLockScreenButton) {
            // Settings only apply to lock screen entry point.
            return;
        }

        mQRCodeScannerPreferenceObserver.forEach((key, value) -> {
            mSecureSettings.unregisterContentObserver(value);
        });

        // Reset cached values to default as we are no longer listening
        mQRCodeScannerPreferenceObserver = new HashMap<>();
        mSecureSettings.putStringForUser(Settings.Secure.SHOW_QR_CODE_SCANNER_SETTING, null,
                mUserTracker.getUserId());
    }

    private void unregisterDefaultQRCodeScannerObserver() {
        mDeviceConfigProxy.removeOnPropertiesChangedListener(
                mOnDefaultQRCodeScannerChangedListener);

        // Reset cached values to default as we are no longer listening
        mOnDefaultQRCodeScannerChangedListener = null;
        mQRCodeScannerActivity = null;
        mIntent = null;
        mComponentName = null;
    }

    private void notifyQRCodeScannerActivityChanged() {
        // Clone and iterate so that we don't block other threads trying to add to mCallbacks
        ArrayList<Callback> callbacksCopy;
        synchronized (mCallbacks) {
            callbacksCopy = (ArrayList) mCallbacks.clone();
        }

        callbacksCopy.forEach(c -> c.onQRCodeScannerActivityChanged());
    }

    private void notifyQRCodeScannerPreferenceChanged() {
        // Clone and iterate so that we don't block other threads trying to add to mCallbacks
        ArrayList<Callback> callbacksCopy;
        synchronized (mCallbacks) {
            callbacksCopy = (ArrayList) mCallbacks.clone();
        }

        callbacksCopy.forEach(c -> c.onQRCodeScannerPreferenceChanged());
    }

    private void registerDefaultQRCodeScannerObserver() {
        if (mOnDefaultQRCodeScannerChangedListener != null) return;

        // While registering the observers for the first time update the default values in the
        // background
        mExecutor.execute(() -> updateQRCodeScannerActivityDetails());
        mOnDefaultQRCodeScannerChangedListener =
                properties -> {
                    if (DeviceConfig.NAMESPACE_SYSTEMUI.equals(properties.getNamespace())
                            && (properties.getKeyset().contains(
                            SystemUiDeviceConfigFlags.DEFAULT_QR_CODE_SCANNER))) {
                        updateQRCodeScannerActivityDetails();
                        updateQRCodeScannerPreferenceDetails(/* updateSettings = */true);
                    }
                };
        mDeviceConfigProxy.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                mExecutor, mOnDefaultQRCodeScannerChangedListener);
    }

    private void registerQRCodePreferenceObserver() {
        if (!mConfigEnableLockScreenButton) {
            // Settings only apply to lock screen entry point.
            return;
        }

        int userId = mUserTracker.getUserId();
        if (mQRCodeScannerPreferenceObserver.getOrDefault(userId, null) != null) return;

        // While registering the observers for the first time update the default values in the
        // background
        mExecutor.execute(
                () -> updateQRCodeScannerPreferenceDetails(/* updateSettings = */true));
        mQRCodeScannerPreferenceObserver.put(userId, new ContentObserver(null /* handler */) {
            @Override
            public void onChange(boolean selfChange) {
                mExecutor.execute(() -> {
                    updateQRCodeScannerPreferenceDetails(/* updateSettings  = */false);
                });
            }
        });
        mSecureSettings.registerContentObserverForUser(
                mSecureSettings.getUriFor(LOCK_SCREEN_SHOW_QR_CODE_SCANNER), false,
                mQRCodeScannerPreferenceObserver.get(userId), userId);
    }

    private void registerUserChangeObservers() {
        if (mUserChangedListener != null) return;

        mUserChangedListener = new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, Context userContext) {
                // For the new user,
                // 1. Enable setting (if qr code scanner activity is available, and if not already
                // done)
                // 2. Update the lock screen entry point preference as per the user
                registerQRCodePreferenceObserver();
                updateQRCodeScannerPreferenceDetails(/* updateSettings = */true);
            }
        };
        mUserTracker.addCallback(mUserChangedListener, mExecutor);
    }
}
