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

package com.android.settingslib.bluetooth;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.TextView;

import com.android.settingslib.R;

public class BroadcastDialog extends AlertDialog {

    private static final String TAG = "BroadcastDialog";

    private String mCurrentApp;
    private String mSwitchApp;
    private Context mContext;

    public BroadcastDialog(Context context) {
        super(context);
        mContext = context;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {

        View layout = View.inflate(mContext, R.layout.broadcast_dialog, null);
        final Window window = getWindow();
        window.setContentView(layout);
        window.setWindowAnimations(
                com.android.settingslib.widget.theme.R.style.Theme_AlertDialog_SettingsLib);

        TextView title = layout.findViewById(R.id.dialog_title);
        TextView subTitle = layout.findViewById(R.id.dialog_subtitle);
        title.setText(mContext.getString(R.string.bt_le_audio_broadcast_dialog_title, mCurrentApp));
        subTitle.setText(
                mContext.getString(R.string.bt_le_audio_broadcast_dialog_sub_title, mSwitchApp));
        Button positiveBtn = layout.findViewById(R.id.positive_btn);
        Button negativeBtn = layout.findViewById(R.id.negative_btn);
        Button neutralBtn = layout.findViewById(R.id.neutral_btn);
        positiveBtn.setText(mContext.getString(
                R.string.bt_le_audio_broadcast_dialog_switch_app, mSwitchApp), null);
        neutralBtn.setOnClickListener((view) -> {
            Log.d(TAG, "BroadcastDialog dismiss.");
            dismiss();
        });
    }
}
