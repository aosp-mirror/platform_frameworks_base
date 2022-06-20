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
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

import androidx.core.graphics.drawable.IconCompat;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.dagger.SysUISingleton;

/**
 * Dialog for media output transferring.
 */
@SysUISingleton
public class MediaOutputDialog extends MediaOutputBaseDialog {
    final UiEventLogger mUiEventLogger;

    MediaOutputDialog(Context context, boolean aboveStatusbar, BroadcastSender broadcastSender,
            MediaOutputController mediaOutputController, UiEventLogger uiEventLogger) {
        super(context, broadcastSender, mediaOutputController);
        mUiEventLogger = uiEventLogger;
        mAdapter = new MediaOutputAdapter(mMediaOutputController, this);
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
    Drawable getAppSourceIcon() {
        return mMediaOutputController.getAppSourceIcon();
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
        if (mMediaOutputController.getCurrentConnectedMediaDevice() != null) {
            isBluetoothLeDevice = mMediaOutputController.isBluetoothLeDevice(
                    mMediaOutputController.getCurrentConnectedMediaDevice());
        }
        return mMediaOutputController.isBroadcastSupported() && isBluetoothLeDevice;
    }

    @Override
    public CharSequence getStopButtonText() {
        int resId = R.string.keyboard_key_media_stop;
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
            dismiss();
        }
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
