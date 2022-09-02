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

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.CLIPBOARD_OVERLAY_ENABLED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_ENTERED;
import static com.android.systemui.clipboardoverlay.ClipboardOverlayEvent.CLIPBOARD_OVERLAY_UPDATED;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.util.DeviceConfigProxy;

import javax.inject.Inject;

/**
 * ClipboardListener brings up a clipboard overlay when something is copied to the clipboard.
 */
@SysUISingleton
public class ClipboardListener extends CoreStartable
        implements ClipboardManager.OnPrimaryClipChangedListener {
    private static final String TAG = "ClipboardListener";

    @VisibleForTesting
    static final String SHELL_PACKAGE = "com.android.shell";
    @VisibleForTesting
    static final String EXTRA_SUPPRESS_OVERLAY =
            "com.android.systemui.SUPPRESS_CLIPBOARD_OVERLAY";

    private final DeviceConfigProxy mDeviceConfig;
    private final ClipboardOverlayControllerFactory mOverlayFactory;
    private final ClipboardManager mClipboardManager;
    private final UiEventLogger mUiEventLogger;
    private ClipboardOverlayController mClipboardOverlayController;

    @Inject
    public ClipboardListener(Context context, DeviceConfigProxy deviceConfigProxy,
            ClipboardOverlayControllerFactory overlayFactory, ClipboardManager clipboardManager,
            UiEventLogger uiEventLogger) {
        super(context);
        mDeviceConfig = deviceConfigProxy;
        mOverlayFactory = overlayFactory;
        mClipboardManager = clipboardManager;
        mUiEventLogger = uiEventLogger;
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

        if (mClipboardOverlayController == null) {
            mClipboardOverlayController = mOverlayFactory.create(mContext);
            mUiEventLogger.log(CLIPBOARD_OVERLAY_ENTERED, 0, clipSource);
        } else {
            mUiEventLogger.log(CLIPBOARD_OVERLAY_UPDATED, 0, clipSource);
        }
        mClipboardOverlayController.setClipData(clipData, clipSource);
        mClipboardOverlayController.setOnSessionCompleteListener(() -> {
            // Session is complete, free memory until it's needed again.
            mClipboardOverlayController = null;
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

    private static boolean isEmulator() {
        return SystemProperties.getBoolean("ro.boot.qemu", false);
    }
}
