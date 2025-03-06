/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility.hearingaid;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;

/**
 * An ArrayAdapter which was used by Spinner in hearing devices dialog.
 */
public class HearingDevicesSpinnerAdapter extends ArrayAdapter<String> {

    private final Context mContext;
    private int mSelectedPosition;

    public HearingDevicesSpinnerAdapter(@NonNull Context context) {
        super(context, R.layout.hearing_devices_spinner_view,
                R.id.hearing_devices_spinner_text);
        setDropDownViewResource(R.layout.hearing_devices_spinner_dropdown_view);
        mContext = context;
    }

    @Override
    public View getDropDownView(int position, @Nullable View convertView,
            @NonNull ViewGroup parent) {

        View view = super.getDropDownView(position, convertView, parent);

        final boolean isSelected = position == mSelectedPosition;
        view.setBackgroundResource(isSelected
                ? R.drawable.hearing_devices_spinner_selected_background
                : R.drawable.bluetooth_tile_dialog_bg_off);

        View checkIcon = view.findViewById(R.id.hearing_devices_spinner_check_icon);
        if (checkIcon != null) {
            checkIcon.setVisibility(isSelected ? VISIBLE : GONE);
        }

        TextView text = view.findViewById(R.id.hearing_devices_spinner_text);
        if (text != null) {
            int tintColor = Utils.getColorAttr(mContext,
                    isSelected ? com.android.internal.R.attr.materialColorOnPrimaryContainer
                            : com.android.internal.R.attr.materialColorOnSurface).getDefaultColor();
            text.setTextColor(tintColor);
        }
        return view;
    }

    /**
     * Sets the selected position into this adapter. The selected item will have different UI in
     * the dropdown view.
     *
     * @param position the selected position
     */
    public void setSelected(int position) {
        mSelectedPosition = position;
        notifyDataSetChanged();
    }
}
