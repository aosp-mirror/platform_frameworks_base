/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.bluetooth;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEvent;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.R;
import com.android.systemui.media.MediaDataUtils;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.statusbar.phone.SystemUIDialog;

/**
 * Dialog for showing le audio broadcasting dialog.
 */
public class BroadcastDialog extends SystemUIDialog {

    private static final String TAG = "BroadcastDialog";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private Context mContext;
    private UiEventLogger mUiEventLogger;
    @VisibleForTesting
    protected View mDialogView;
    private MediaOutputDialogFactory mMediaOutputDialogFactory;
    private String mSwitchBroadcastApp;
    private String mOutputPackageName;

    public BroadcastDialog(Context context, MediaOutputDialogFactory mediaOutputDialogFactory,
            String switchBroadcastApp, String outputPkgName, UiEventLogger uiEventLogger) {
        super(context);
        if (DEBUG) {
            Log.d(TAG, "Init BroadcastDialog");
        }

        mContext = getContext();
        mMediaOutputDialogFactory = mediaOutputDialogFactory;
        mSwitchBroadcastApp = switchBroadcastApp;
        mOutputPackageName = outputPkgName;
        mUiEventLogger = uiEventLogger;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) {
            Log.d(TAG, "onCreate");
        }

        mUiEventLogger.log(BroadcastDialogEvent.BROADCAST_DIALOG_SHOW);
        mDialogView = LayoutInflater.from(mContext).inflate(R.layout.broadcast_dialog, null);
        final Window window = getWindow();
        window.setContentView(mDialogView);

        TextView title = mDialogView.requireViewById(R.id.dialog_title);
        TextView subTitle = mDialogView.requireViewById(R.id.dialog_subtitle);
        title.setText(
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_title,
                        MediaDataUtils.getAppLabel(mContext, mOutputPackageName,
                                mContext.getString(
                                        R.string.bt_le_audio_broadcast_dialog_unknown_name))));
        subTitle.setText(
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_sub_title,
                        mSwitchBroadcastApp));

        Button switchBroadcast = mDialogView.requireViewById(R.id.switch_broadcast);
        Button changeOutput = mDialogView.requireViewById(R.id.change_output);
        Button cancelBtn = mDialogView.requireViewById(R.id.cancel);
        switchBroadcast.setText(mContext.getString(
                R.string.bt_le_audio_broadcast_dialog_switch_app, mSwitchBroadcastApp), null);
        changeOutput.setOnClickListener((view) -> {
            mMediaOutputDialogFactory.create(mOutputPackageName, true, null);
            dismiss();
        });
        cancelBtn.setOnClickListener((view) -> {
            if (DEBUG) {
                Log.d(TAG, "BroadcastDialog dismiss.");
            }
            dismiss();
        });
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && isShowing()) {
            dismiss();
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

}
