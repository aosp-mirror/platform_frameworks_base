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

package com.android.systemui.clipboardoverlay;

import static android.content.ClipDescription.CLASSIFICATION_COMPLETE;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.CLIPBOARD_OVERLAY_ENABLED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_UPDATED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_TOAST_SHOWN;

import static com.google.android.setupcompat.util.WizardManagerHelper.SETTINGS_SECURE_USER_SETUP_COMPLETE;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.util.DeviceConfigProxy;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * ClipboardListener brings up a clipboard overlay when something is copied to the clipboard.
 */
@SysUISingleton
public class ClipboardListener implements
        CoreStartable, ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardListener";

    @VisibleForTesting
    static final String SHELL_PACKAGE = "com.android.shell";
    @VisibleForTesting
    static final String EXTRA_SUPPRESS_OVERLAY =
            "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY";

    private final Context mContext;
    private final DeviceConfigProxy mDeviceConfig;
    private final Provider<ClipboardOverlayController> mOverlayProvider;
    private final ClipboardOverlayControllerLegacyFactory mOverlayFactory;
    private final ClipboardToast mClipboardToast;
    private final ClipboardManager mClipboardManager;
    private final UiEventLogger mUiEventLogger;
    private final FeatureFlags mFeatureFlags;
    private boolean mUsingNewOverlay;
    private ClipboardOverlay mClipboardOverlay;

    @Inject
    public ClipboardListener(Context context, DeviceConfigProxy deviceConfigProxy,
            Provider<ClipboardOverlayController> clipboardOverlayControllerProvider,
            ClipboardOverlayControllerLegacyFactory overlayFactory,
            ClipboardToast clipboardToast,
            ClipboardManager clipboardManager,
            UiEventLogger uiEventLogger,
            FeatureFlags featureFlags) {
        mContext = context;
        mDeviceConfig = deviceConfigProxy;
        mOverlayProvider = clipboardOverlayControllerProvider;
        mOverlayFactory = overlayFactory;
        mClipboardToast = clipboardToast;
        mClipboardManager = clipboardManager;
        mUiEventLogger = uiEventLogger;
        mFeatureFlags = featureFlags;

        mUsingNewOverlay = mFeatureFlags.isEnabled(Flags.CLIPBOARD_OVERLAY_REFACTOR);
    }

    @Override
    public void start() {
        if (mDeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_SYSTEMUI, CLIPBOARD_OVERLAY_ENABLED, true)) {
            mClipboardManager.addPrimaryClipChangedListener(this);
        }
    }

    @Override
    public void onPrimaryClipChanged() {
        if (!mClipboardManager.hasPrimaryClip()) {
            return;
        }

        String clipSource = mClipboardManager.getPrimaryClipSource();
        ClipData clipData = mClipboardManager.getPrimaryClip();

        if (shouldSuppressOverlay(clipData, clipSource, isEmulator())) {
            Log.i(TAG, "Clipboard overlay suppressed.");
            return;
        }

        if (!isUserSetupComplete()) {
            // just show a toast, user should not access intents from this state
            if (shouldShowToast(clipData)) {
                mUiEventLogger.log(CLIPBOARD_TOAST_SHOWN, 0, clipSource);
                mClipboardToast.showCopiedToast();
            }
            return;
        }

        boolean enabled = mFeatureFlags.isEnabled(Flags.CLIPBOARD_OVERLAY_REFACTOR);
        if (mClipboardOverlay == null || enabled != mUsingNewOverlay) {
            mUsingNewOverlay = enabled;
            if (enabled) {
                mClipboardOverlay = mOverlayProvider.get();
            } else {
                mClipboardOverlay = mOverlayFactory.create(mContext);
            }
            mUiEventLogger.log(CLIPBOARD_OVERLAY_ENTERED, 0, clipSource);
        } else {
            mUiEventLogger.log(CLIPBOARD_OVERLAY_UPDATED, 0, clipSource);
        }
        mClipboardOverlay.setClipData(clipData, clipSource);
        mClipboardOverlay.setOnSessionCompleteListener(() -> {
            // Session is complete, free memory until it's needed again.
            mClipboardOverlay = null;
        });
    }

    // The overlay is suppressed if EXTRA_SUPPRESS_OVERLAY is true and the device is an emulator or
    // the source package is SHELL_PACKAGE. This is meant to suppress the overlay when the emulator
    // or a mirrored device is syncing the clipboard.
    @VisibleForTesting
    static boolean shouldSuppressOverlay(ClipData clipData, String clipSource,
            boolean isEmulator) {
        if (!(isEmulator || SHELL_PACKAGE.equals(clipSource))) {
            return false;
        }
        if (clipData == null || clipData.getDescription().getExtras() == null) {
            return false;
        }
        return clipData.getDescription().getExtras().getBoolean(EXTRA_SUPPRESS_OVERLAY, false);
    }

    boolean shouldShowToast(ClipData clipData) {
        if (clipData == null) {
            return false;
        } else if (clipData.getDescription().getClassificationStatus() == CLASSIFICATION_COMPLETE) {
            // only show for classification complete if we aren't already showing a toast, to ignore
            // the duplicate ClipData with classification
            return !mClipboardToast.isShowing();
        }
        return true;
    }

    private static boolean isEmulator() {
        return SystemProperties.getBoolean("ro.boot.qemu", false);
    }

    private boolean isUserSetupComplete() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                SETTINGS_SECURE_USER_SETUP_COMPLETE, 0) == 1;
    }

    interface ClipboardOverlay {
        void setClipData(ClipData clipData, String clipSource);

        void setOnSessionCompleteListener(Runnable runnable);
    }
}
