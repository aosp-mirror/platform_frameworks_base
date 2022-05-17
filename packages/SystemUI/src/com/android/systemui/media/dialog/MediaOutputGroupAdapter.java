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

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.android.settingslib.media.MediaDevice;
import com.android.systemui.R;

import java.util.List;

/**
 * Adapter for media output dynamic group dialog.
 */
//TODO: clear this class after new UI updated
public class MediaOutputGroupAdapter extends MediaOutputBaseAdapter {

    private static final String TAG = "MediaOutputGroupAdapter";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final List<MediaDevice> mGroupMediaDevices;

    public MediaOutputGroupAdapter(MediaOutputController controller) {
        super(controller);
        mGroupMediaDevices = controller.getGroupMediaDevices();
    }

    @Override
    public MediaDeviceBaseViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup,
            int viewType) {
        super.onCreateViewHolder(viewGroup, viewType);

        return new GroupViewHolder(mHolderView);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaDeviceBaseViewHolder viewHolder, int position) {
        // Add "Group"
        if (position == 0) {
            viewHolder.onBind(CUSTOMIZED_ITEM_GROUP, true /* topMargin */,
                    false /* bottomMargin */);
            return;
        }
        // Add available devices
        final int newPosition = position - 1;
        final int size = mGroupMediaDevices.size();
        if (newPosition < size) {
            viewHolder.onBind(mGroupMediaDevices.get(newPosition), false /* topMargin */,
                    newPosition == (size - 1) /* bottomMargin */, position);
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "Incorrect position: " + position);
        }
    }

    @Override
    public int getItemCount() {
        // Require extra item for group volume operation
        return mGroupMediaDevices.size() + 1;
    }

    @Override
    CharSequence getItemTitle(MediaDevice device) {
        return super.getItemTitle(device);
    }

    class GroupViewHolder extends MediaDeviceBaseViewHolder {

        GroupViewHolder(View view) {
            super(view);
        }

        @Override
        void onBind(MediaDevice device, boolean topMargin, boolean bottomMargin, int position) {
            super.onBind(device, topMargin, bottomMargin, position);
            mCheckBox.setVisibility(View.VISIBLE);
            mCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                onCheckBoxClicked(isChecked, device);
            });
            boolean isCurrentSeekbarInvisible = mSeekBar.getVisibility() == View.GONE;
            setTwoLineLayout(device, false /* bFocused */, true /* showSeekBar */,
                    false /* showProgressBar */, false /* showSubtitle*/);
            initSeekbar(device, isCurrentSeekbarInvisible);
            final List<MediaDevice> selectedDevices = mController.getSelectedMediaDevice();
            if (isDeviceIncluded(mController.getSelectableMediaDevice(), device)) {
                mCheckBox.setButtonDrawable(R.drawable.ic_check_box);
                mCheckBox.setChecked(false);
                mCheckBox.setEnabled(true);
            } else if (isDeviceIncluded(selectedDevices, device)) {
                if (selectedDevices.size() == 1 || !isDeviceIncluded(
                        mController.getDeselectableMediaDevice(), device)) {
                    mCheckBox.setButtonDrawable(getDisabledCheckboxDrawable());
                    mCheckBox.setChecked(true);
                    mCheckBox.setEnabled(false);
                } else {
                    mCheckBox.setButtonDrawable(R.drawable.ic_check_box);
                    mCheckBox.setChecked(true);
                    mCheckBox.setEnabled(true);
                }
            }
        }

        @Override
        void onBind(int customizedItem, boolean topMargin, boolean bottomMargin) {
            if (customizedItem == CUSTOMIZED_ITEM_GROUP) {
                setTwoLineLayout(mContext.getText(R.string.media_output_dialog_group),
                        true /* bFocused */, true /* showSeekBar */, false /* showProgressBar */,
                        false /* showSubtitle*/);
                mTitleIcon.setImageDrawable(getSpeakerDrawable());
                mCheckBox.setVisibility(View.GONE);
                initSessionSeekbar();
            }
        }

        private void onCheckBoxClicked(boolean isChecked, MediaDevice device) {
            if (isChecked && isDeviceIncluded(mController.getSelectableMediaDevice(), device)) {
                mController.addDeviceToPlayMedia(device);
            } else if (!isChecked && isDeviceIncluded(mController.getDeselectableMediaDevice(),
                    device)) {
                mController.removeDeviceFromPlayMedia(device);
            }
        }

        private Drawable getDisabledCheckboxDrawable() {
            final Drawable drawable = mContext.getDrawable(R.drawable.ic_check_box_blue_24dp)
                    .mutate();
            final Bitmap checkbox = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(checkbox);
            TypedValue value = new TypedValue();
            mContext.getTheme().resolveAttribute(android.R.attr.disabledAlpha, value, true);
            drawable.setAlpha((int) (value.getFloat() * 255));
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);

            return drawable;
        }

        private boolean isDeviceIncluded(List<MediaDevice> deviceList, MediaDevice targetDevice) {
            for (MediaDevice device : deviceList) {
                if (TextUtils.equals(device.getId(), targetDevice.getId())) {
                    return true;
                }
            }
            return false;
        }
    }
}
