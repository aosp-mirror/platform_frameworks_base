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

package com.android.systemui.screenrecord;

import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.INTERNAL;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC;
import static com.android.systemui.screenrecord.ScreenRecordingAudioSource.MIC_AND_INTERNAL;

import android.content.Context;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;

import java.util.List;

/**
 * Screen recording view adapter
 */
public class ScreenRecordingAdapter extends ArrayAdapter<ScreenRecordingAudioSource> {
    private LinearLayout mSelectedMic;
    private LinearLayout mSelectedInternal;
    private LinearLayout mSelectedMicAndInternal;
    private LinearLayout mMicOption;
    private LinearLayout mMicAndInternalOption;
    private LinearLayout mInternalOption;

    public ScreenRecordingAdapter(Context context, int resource,
            List<ScreenRecordingAudioSource> objects) {
        super(context, resource, objects);
        initViews();
    }

    private void initViews() {
        mSelectedInternal = getSelected(R.string.screenrecord_device_audio_label);
        mSelectedMic = getSelected(R.string.screenrecord_mic_label);
        mSelectedMicAndInternal = getSelected(R.string.screenrecord_device_audio_and_mic_label);

        mMicOption = getOption(R.string.screenrecord_mic_label, Resources.ID_NULL);
        mMicOption.removeViewAt(1);

        mMicAndInternalOption = getOption(
                R.string.screenrecord_device_audio_and_mic_label, Resources.ID_NULL);
        mMicAndInternalOption.removeViewAt(1);

        mInternalOption = getOption(R.string.screenrecord_device_audio_label,
                R.string.screenrecord_device_audio_description);
    }

    private LinearLayout getOption(int label, int description) {
        LayoutInflater inflater = (LayoutInflater) getContext()
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        LinearLayout layout = (LinearLayout) inflater
                .inflate(R.layout.screen_record_dialog_audio_source, null, false);
        ((TextView) layout.findViewById(R.id.screen_recording_dialog_source_text))
                .setText(label);
        if (description != Resources.ID_NULL)
            ((TextView) layout.findViewById(R.id.screen_recording_dialog_source_description))
                    .setText(description);
        return layout;
    }

    private LinearLayout getSelected(int label) {
        LayoutInflater inflater = LayoutInflater.from(getContext());
        LinearLayout layout = (LinearLayout) inflater
                .inflate(R.layout.screen_record_dialog_audio_source_selected, null, false);
        ((TextView) layout.findViewById(R.id.screen_recording_dialog_source_text))
                .setText(label);
        return layout;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        switch (getItem(position)) {
            case INTERNAL:
                return mInternalOption;
            case MIC_AND_INTERNAL:
                return mMicAndInternalOption;
            case MIC:
                return mMicOption;
            default:
                return super.getDropDownView(position, convertView, parent);
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        switch (getItem(position)) {
            case INTERNAL:
                return mSelectedInternal;
            case MIC_AND_INTERNAL:
                return mSelectedMicAndInternal;
            case MIC:
                return mSelectedMic;
            default:
                return super.getView(position, convertView, parent);
        }
    }
}
