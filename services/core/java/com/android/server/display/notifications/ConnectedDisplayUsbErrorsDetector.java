/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.notifications;

import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_CAPABLE_DISABLED;
import static android.hardware.usb.DisplayPortAltModeInfo.DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE;
import static android.hardware.usb.DisplayPortAltModeInfo.LINK_TRAINING_STATUS_FAILURE;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.usb.DisplayPortAltModeInfo;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbManager.DisplayPortAltModeInfoListener;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.feature.DisplayManagerFlags;

/**
 * Detects usb issues related to an external display connected.
 */
public class ConnectedDisplayUsbErrorsDetector implements DisplayPortAltModeInfoListener {
    private static final String TAG = "ConnectedDisplayUsbErrorsDetector";

    /**
     * Dependency injection for {@link ConnectedDisplayUsbErrorsDetector}.
     */
    public interface Injector {

        /**
         * @return {@link UsbManager} service.
         */
        UsbManager getUsbManager();
    }

    /**
     * USB errors listener
     */
    public interface Listener {

        /**
         * Link training failure callback.
         */
        void onDisplayPortLinkTrainingFailure();

        /**
         * DisplayPort capable device plugged-in, but cable is not supporting DisplayPort.
         */
        void onCableNotCapableDisplayPort();
    }

    private Listener mListener;
    private final Injector mInjector;
    private final Context mContext;
    private final boolean mIsConnectedDisplayErrorHandlingEnabled;

    ConnectedDisplayUsbErrorsDetector(@NonNull final DisplayManagerFlags flags,
            @NonNull final Context context) {
        this(flags, context, () -> context.getSystemService(UsbManager.class));
    }

    @VisibleForTesting
    ConnectedDisplayUsbErrorsDetector(@NonNull final DisplayManagerFlags flags,
            @NonNull final Context context, @NonNull final Injector injector) {
        mContext = context;
        mInjector = injector;
        mIsConnectedDisplayErrorHandlingEnabled =
                flags.isConnectedDisplayErrorHandlingEnabled();
    }

    /** Register listener for usb error events. */
    @SuppressLint("AndroidFrameworkRequiresPermission")
    void registerListener(final Listener listener) {
        if (!mIsConnectedDisplayErrorHandlingEnabled) {
            return;
        }

        final var usbManager = mInjector.getUsbManager();
        if (usbManager == null) {
            Slog.e(TAG, "UsbManager is null");
            return;
        }

        mListener = listener;

        try {
            usbManager.registerDisplayPortAltModeInfoListener(mContext.getMainExecutor(), this);
        } catch (IllegalStateException e) {
            Slog.e(TAG, "Failed to register listener", e);
        }
    }

    /**
     * Callback upon changes in {@link DisplayPortAltModeInfo}.
     * @param portId    String describing the {@link android.hardware.usb.UsbPort} that was changed.
     * @param info      New {@link DisplayPortAltModeInfo} for the corresponding portId.
     */
    @Override
    public void onDisplayPortAltModeInfoChanged(@NonNull String portId,
            @NonNull DisplayPortAltModeInfo info) {
        if (mListener == null) {
            return;
        }

        if (DISPLAYPORT_ALT_MODE_STATUS_CAPABLE_DISABLED == info.getPartnerSinkStatus()
                && DISPLAYPORT_ALT_MODE_STATUS_NOT_CAPABLE == info.getCableStatus()
        ) {
            mListener.onCableNotCapableDisplayPort();
            return;
        }

        if (LINK_TRAINING_STATUS_FAILURE == info.getLinkTrainingStatus()) {
            mListener.onDisplayPortLinkTrainingFailure();
            return;
        }
    }
}
