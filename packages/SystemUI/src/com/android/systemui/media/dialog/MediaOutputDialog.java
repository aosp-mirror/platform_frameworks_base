/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.dialog;

import android.content.Context;
import android.os.Bundle;
import android.util.FeatureFlagUtils;
import android.view.View;
import android.view.WindowManager;

import androidx.core.graphics.drawable.IconCompat;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.animation.DialogTransitionAnimator;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.res.R;

/**
 * Dialog for media output transferring.
 */
@SysUISingleton
public class MediaOutputDialog extends MediaOutputBaseDialog {
    private final DialogTransitionAnimator mDialogTransitionAnimator;
    private final UiEventLogger mUiEventLogger;

    MediaOutputDialog(
            Context context,
            boolean aboveStatusbar,
            BroadcastSender broadcastSender,
            MediaOutputController mediaOutputController,
            DialogTransitionAnimator dialogTransitionAnimator,
            UiEventLogger uiEventLogger,
            boolean includePlaybackAndAppMetadata) {
        super(context, broadcastSender, mediaOutputController, includePlaybackAndAppMetadata);
        mDialogTransitionAnimator = dialogTransitionAnimator;
        mUiEventLogger = uiEventLogger;
        mAdapter = new MediaOutputAdapter(mMediaOutputController);
        if (!aboveStatusbar) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUiEventLogger.log(MediaOutputEvent.MEDIA_OUTPUT_DIALOG_SHOW);
    }

    @Override
    int getHeaderIconRes() {
        return 0;
    }

    @Override
    IconCompat getHeaderIcon() {
        return mMediaOutputController.getHeaderIcon();
    }

    @Override
    int getHeaderIconSize() {
        return mContext.getResources().getDimensionPixelSize(
                R.dimen.media_output_dialog_header_album_icon_size);
    }

    @Override
    CharSequence getHeaderText() {
        return mMediaOutputController.getHeaderTitle();
    }

    @Override
    CharSequence getHeaderSubtitle() {
        return mMediaOutputController.getHeaderSubTitle();
    }

    @Override
    IconCompat getAppSourceIcon() {
        return mMediaOutputController.getNotificationSmallIcon();
    }

    @Override
    int getStopButtonVisibility() {
        boolean isActiveRemoteDevice = false;
        if (mMediaOutputController.getCurrentConnectedMediaDevice() != null) {
            isActiveRemoteDevice = mMediaOutputController.isActiveRemoteDevice(
                    mMediaOutputController.getCurrentConnectedMediaDevice());
        }
        boolean showBroadcastButton = isBroadcastSupported() && mMediaOutputController.isPlaying();

        return (isActiveRemoteDevice || showBroadcastButton) ? View.VISIBLE : View.GONE;
    }

    @Override
    public boolean isBroadcastSupported() {
        boolean isBluetoothLeDevice = false;
        boolean isBroadcastEnabled = false;
        if (FeatureFlagUtils.isEnabled(mContext,
                FeatureFlagUtils.SETTINGS_NEED_CONNECTED_BLE_DEVICE_FOR_BROADCAST)) {
            if (mMediaOutputController.getCurrentConnectedMediaDevice() != null) {
                isBluetoothLeDevice = mMediaOutputController.isBluetoothLeDevice(
                    mMediaOutputController.getCurrentConnectedMediaDevice());
                // if broadcast is active, broadcast should be considered as supported
                // there could be a valid case that broadcast is ongoing
                // without active LEA device connected
                isBroadcastEnabled = mMediaOutputController.isBluetoothLeBroadcastEnabled();
            }
        } else {
            // To decouple LE Audio Broadcast and Unicast, it always displays the button when there
            // is no LE Audio device connected to the phone
            isBluetoothLeDevice = true;
        }

        return mMediaOutputController.isBroadcastSupported()
                && (isBluetoothLeDevice || isBroadcastEnabled);
    }

    @Override
    public CharSequence getStopButtonText() {
        int resId = R.string.media_output_dialog_button_stop_casting;
        if (isBroadcastSupported() && mMediaOutputController.isPlaying()
                && !mMediaOutputController.isBluetoothLeBroadcastEnabled()) {
            resId = R.string.media_output_broadcast;
        }
        return mContext.getText(resId);
    }

    @Override
    public void onStopButtonClick() {
        if (isBroadcastSupported() && mMediaOutputController.isPlaying()) {
            if (!mMediaOutputController.isBluetoothLeBroadcastEnabled()) {
                if (startLeBroadcastDialogForFirstTime()) {
                    return;
                }
                startLeBroadcast();
            } else {
                stopLeBroadcast();
            }
        } else {
            mMediaOutputController.releaseSession();
            mDialogTransitionAnimator.disableAllCurrentDialogsExitAnimations();
            dismiss();
        }
    }

    @Override
    public int getBroadcastIconVisibility() {
        return (isBroadcastSupported() && mMediaOutputController.isBluetoothLeBroadcastEnabled())
                ? View.VISIBLE : View.GONE;
    }

    @Override
    public void onBroadcastIconClick() {
        startLeBroadcastDialog();
    }

    @VisibleForTesting
    public enum MediaOutputEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The MediaOutput dialog became visible on the screen.")
        MEDIA_OUTPUT_DIALOG_SHOW(655);

        private final int mId;

        MediaOutputEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }
}
