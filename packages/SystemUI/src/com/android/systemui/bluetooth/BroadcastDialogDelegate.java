/**
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

package com.android.systemui.bluetooth;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Dialog;
import android.bluetooth.BluetoothLeBroadcast;
import android.bluetooth.BluetoothLeBroadcastMetadata;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.settingslib.bluetooth.LocalBluetoothLeBroadcast;
import com.android.settingslib.bluetooth.LocalBluetoothManager;
import com.android.settingslib.media.MediaOutputConstants;
import com.android.systemui.broadcast.BroadcastSender;
import com.android.systemui.media.controls.util.MediaDataUtils;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.SystemUIDialog;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Dialog for showing le audio broadcasting dialog.
 */
public class BroadcastDialogDelegate implements SystemUIDialog.Delegate {

    private static final String TAG = "BroadcastDialog";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int HANDLE_BROADCAST_FAILED_DELAY = 3000;
    private static final String CURRENT_BROADCAST_APP = "current_broadcast_app";
    private static final String OUTPUT_PKG_NAME = "output_pkg_name";

    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    private final Context mContext;
    private final UiEventLogger mUiEventLogger;
    private final MediaOutputDialogFactory mMediaOutputDialogFactory;
    private final LocalBluetoothManager mLocalBluetoothManager;
    private final BroadcastSender mBroadcastSender;
    private final SystemUIDialog.Factory mSystemUIDialogFactory;
    private final String mCurrentBroadcastApp;
    private final String mOutputPackageName;
    private final Executor mExecutor;
    private boolean mShouldLaunchLeBroadcastDialog;
    private Button mSwitchBroadcast;

    private final Set<SystemUIDialog> mDialogs = new HashSet<>();

    private final BluetoothLeBroadcast.Callback mBroadcastCallback =
            new BluetoothLeBroadcast.Callback() {
            @Override
            public void onBroadcastStarted(int reason, int broadcastId) {
                if (DEBUG) {
                    Log.d(TAG, "onBroadcastStarted(), reason = " + reason
                            + ", broadcastId = " + broadcastId);
                }
                mMainThreadHandler.post(() -> handleLeBroadcastStarted());
            }

            @Override
            public void onBroadcastStartFailed(int reason) {
                if (DEBUG) {
                    Log.d(TAG, "onBroadcastStartFailed(), reason = " + reason);
                }
                mMainThreadHandler.postDelayed(() -> handleLeBroadcastStartFailed(),
                        HANDLE_BROADCAST_FAILED_DELAY);
            }

            @Override
            public void onBroadcastMetadataChanged(int broadcastId,
                    @NonNull BluetoothLeBroadcastMetadata metadata) {
                if (DEBUG) {
                    Log.d(TAG, "onBroadcastMetadataChanged(), broadcastId = " + broadcastId
                            + ", metadata = " + metadata);
                }
                mMainThreadHandler.post(() -> handleLeBroadcastMetadataChanged());
            }

            @Override
            public void onBroadcastStopped(int reason, int broadcastId) {
                if (DEBUG) {
                    Log.d(TAG, "onBroadcastStopped(), reason = " + reason
                            + ", broadcastId = " + broadcastId);
                }
                mMainThreadHandler.post(() -> handleLeBroadcastStopped());
            }

            @Override
            public void onBroadcastStopFailed(int reason) {
                if (DEBUG) {
                    Log.d(TAG, "onBroadcastStopFailed(), reason = " + reason);
                }
                mMainThreadHandler.postDelayed(() -> handleLeBroadcastStopFailed(),
                        HANDLE_BROADCAST_FAILED_DELAY);
            }

            @Override
            public void onBroadcastUpdated(int reason, int broadcastId) {
            }

            @Override
            public void onBroadcastUpdateFailed(int reason, int broadcastId) {
            }

            @Override
            public void onPlaybackStarted(int reason, int broadcastId) {
            }

            @Override
            public void onPlaybackStopped(int reason, int broadcastId) {
            }
        };

    @AssistedFactory
    public interface Factory {
        BroadcastDialogDelegate create(
                @Assisted(CURRENT_BROADCAST_APP) String currentBroadcastApp,
                @Assisted(OUTPUT_PKG_NAME) String outputPkgName
        );
    }

    @AssistedInject
    BroadcastDialogDelegate(
            Context context,
            MediaOutputDialogFactory mediaOutputDialogFactory,
            @Nullable LocalBluetoothManager localBluetoothManager,
            UiEventLogger uiEventLogger,
            Executor executor,
            BroadcastSender broadcastSender,
            SystemUIDialog.Factory systemUIDialogFactory,
            @Assisted(CURRENT_BROADCAST_APP) String currentBroadcastApp,
            @Assisted(OUTPUT_PKG_NAME) String outputPkgName) {
        mContext = context;
        mMediaOutputDialogFactory = mediaOutputDialogFactory;
        mLocalBluetoothManager = localBluetoothManager;
        mSystemUIDialogFactory = systemUIDialogFactory;
        mCurrentBroadcastApp = currentBroadcastApp;
        mOutputPackageName = outputPkgName;
        mUiEventLogger = uiEventLogger;
        mExecutor = executor;
        mBroadcastSender = broadcastSender;

        if (DEBUG) {
            Log.d(TAG, "Init BroadcastDialog");
        }
    }

    @Override
    public SystemUIDialog createDialog() {
        return mSystemUIDialogFactory.create(this);
    }

    @Override
    public void onStart(SystemUIDialog dialog) {
        mDialogs.add(dialog);
        registerBroadcastCallBack(mExecutor, mBroadcastCallback);
    }

    @Override
    public void onCreate(SystemUIDialog dialog, Bundle savedInstanceState) {
        if (DEBUG) {
            Log.d(TAG, "onCreate");
        }

        mUiEventLogger.log(BroadcastDialogEvent.BROADCAST_DIALOG_SHOW);
        View dialogView = dialog.getLayoutInflater().inflate(R.layout.broadcast_dialog, null);
        final Window window = dialog.getWindow();
        window.setContentView(dialogView);

        TextView title = dialogView.requireViewById(R.id.dialog_title);
        TextView subTitle = dialogView.requireViewById(R.id.dialog_subtitle);
        title.setText(mContext.getString(
                R.string.bt_le_audio_broadcast_dialog_title, mCurrentBroadcastApp));
        String switchBroadcastApp = MediaDataUtils.getAppLabel(mContext, mOutputPackageName,
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_unknown_name));
        subTitle.setText(mContext.getString(
                R.string.bt_le_audio_broadcast_dialog_sub_title, switchBroadcastApp));

        mSwitchBroadcast = dialogView.requireViewById(R.id.switch_broadcast);
        Button changeOutput = dialogView.requireViewById(R.id.change_output);
        Button cancelBtn = dialogView.requireViewById(R.id.cancel);
        mSwitchBroadcast.setText(mContext.getString(
                R.string.bt_le_audio_broadcast_dialog_switch_app, switchBroadcastApp), null);
        mSwitchBroadcast.setOnClickListener((view) -> startSwitchBroadcast());
        changeOutput.setOnClickListener((view) -> {
            mMediaOutputDialogFactory.create(mOutputPackageName, true, null);
            dialog.dismiss();
        });
        cancelBtn.setOnClickListener((view) -> {
            if (DEBUG) {
                Log.d(TAG, "BroadcastDialog dismiss.");
            }
            dialog.dismiss();
        });
    }

    @Override
    public void onStop(SystemUIDialog dialog) {
        unregisterBroadcastCallBack(mBroadcastCallback);
        mDialogs.remove(dialog);
    }

    void refreshSwitchBroadcastButton() {
        String switchBroadcastApp = MediaDataUtils.getAppLabel(mContext, mOutputPackageName,
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_unknown_name));
        mSwitchBroadcast.setText(mContext.getString(
                R.string.bt_le_audio_broadcast_dialog_switch_app, switchBroadcastApp), null);
        mSwitchBroadcast.setEnabled(true);
    }

    private void startSwitchBroadcast() {
        if (DEBUG) {
            Log.d(TAG, "startSwitchBroadcast");
        }
        mSwitchBroadcast.setText(R.string.media_output_broadcast_starting);
        mSwitchBroadcast.setEnabled(false);
        //Stop the current Broadcast
        if (!stopBluetoothLeBroadcast()) {
            handleLeBroadcastStopFailed();
            return;
        }
    }

    private void registerBroadcastCallBack(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull BluetoothLeBroadcast.Callback callback) {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "The broadcast profile is null");
            return;
        }
        broadcast.registerServiceCallBack(executor, callback);
    }

    private void unregisterBroadcastCallBack(@NonNull BluetoothLeBroadcast.Callback callback) {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "The broadcast profile is null");
            return;
        }
        broadcast.unregisterServiceCallBack(callback);
    }

    boolean startBluetoothLeBroadcast() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "The broadcast profile is null");
            return false;
        }
        String switchBroadcastApp = MediaDataUtils.getAppLabel(mContext, mOutputPackageName,
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_unknown_name));
        broadcast.startBroadcast(switchBroadcastApp, /*language*/ null);
        return true;
    }

    boolean stopBluetoothLeBroadcast() {
        LocalBluetoothLeBroadcast broadcast =
                mLocalBluetoothManager.getProfileManager().getLeAudioBroadcastProfile();
        if (broadcast == null) {
            Log.d(TAG, "The broadcast profile is null");
            return false;
        }
        broadcast.stopLatestBroadcast();
        return true;
    }

    @Override
    public void onWindowFocusChanged(SystemUIDialog dialog, boolean hasFocus) {
        if (!hasFocus && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    public enum BroadcastDialogEvent implements UiEventLogger.UiEventEnum {
        @UiEvent(doc = "The Broadcast dialog became visible on the screen.")
        BROADCAST_DIALOG_SHOW(1062);

        private final int mId;

        BroadcastDialogEvent(int id) {
            mId = id;
        }

        @Override
        public int getId() {
            return mId;
        }
    }

    void handleLeBroadcastStarted() {
        // Waiting for the onBroadcastMetadataChanged. The UI launchs the broadcast dialog when
        // the metadata is ready.
        mShouldLaunchLeBroadcastDialog = true;
    }

    private void handleLeBroadcastStartFailed() {
        mSwitchBroadcast.setText(R.string.media_output_broadcast_start_failed);
        mSwitchBroadcast.setEnabled(false);
        refreshSwitchBroadcastButton();
    }

    void handleLeBroadcastMetadataChanged() {
        if (mShouldLaunchLeBroadcastDialog) {
            startLeBroadcastDialog();
            mShouldLaunchLeBroadcastDialog = false;
        }
    }

    @VisibleForTesting
    void handleLeBroadcastStopped() {
        mShouldLaunchLeBroadcastDialog = false;
        if (!startBluetoothLeBroadcast()) {
            handleLeBroadcastStartFailed();
            return;
        }
    }

    private void handleLeBroadcastStopFailed() {
        mSwitchBroadcast.setText(R.string.media_output_broadcast_start_failed);
        mSwitchBroadcast.setEnabled(false);
        refreshSwitchBroadcastButton();
    }

    private void startLeBroadcastDialog() {
        mBroadcastSender.sendBroadcast(new Intent()
                .setPackage(mContext.getPackageName())
                .setAction(MediaOutputConstants.ACTION_LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG)
                .putExtra(MediaOutputConstants.EXTRA_PACKAGE_NAME, mOutputPackageName));
        mDialogs.forEach(Dialog::dismiss);
    }
}
