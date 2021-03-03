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
import android.view.View;
import android.view.WindowManager;

import androidx.core.graphics.drawable.IconCompat;

import com.android.systemui.R;

/**
 * Dialog for media output group.
 */
public class MediaOutputGroupDialog extends MediaOutputBaseDialog {

    MediaOutputGroupDialog(Context context, boolean aboveStatusbar, MediaOutputController
            mediaOutputController) {
        super(context, mediaOutputController);
        mMediaOutputController.resetGroupMediaDevices();
        mAdapter = new MediaOutputGroupAdapter(mMediaOutputController);
        if (!aboveStatusbar) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
        show();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    int getHeaderIconRes() {
        return R.drawable.ic_arrow_back;
    }

    @Override
    IconCompat getHeaderIcon() {
        return null;
    }

    @Override
    int getHeaderIconSize() {
        return mContext.getResources().getDimensionPixelSize(
                    R.dimen.media_output_dialog_header_back_icon_size);
    }

    @Override
    CharSequence getHeaderText() {
        return mContext.getString(R.string.media_output_dialog_add_output);
    }

    @Override
    CharSequence getHeaderSubtitle() {
        final int size = mMediaOutputController.getSelectedMediaDevice().size();
        if (size == 1) {
            return mContext.getText(R.string.media_output_dialog_single_device);
        }
        return mContext.getString(R.string.media_output_dialog_multiple_devices, size);
    }

    @Override
    int getStopButtonVisibility() {
        return View.VISIBLE;
    }

    @Override
    void onHeaderIconClick() {
        mMediaOutputController.launchMediaOutputDialog();
    }
}
