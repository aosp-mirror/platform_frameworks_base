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

package com.android.systemui.qs.carrier;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.settingslib.Utils;
import com.android.settingslib.graph.SignalDrawable;
import com.android.systemui.R;

import java.util.Objects;

public class QSCarrier extends LinearLayout {

    private View mMobileGroup;
    private TextView mCarrierText;
    private ImageView mMobileSignal;
    private ImageView mMobileRoaming;
    private CellSignalState mLastSignalState;
    private boolean mProviderModelInitialized = false;

    public QSCarrier(Context context) {
        super(context);
    }

    public QSCarrier(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public QSCarrier(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public QSCarrier(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMobileGroup = findViewById(R.id.mobile_combo);
        mMobileRoaming = findViewById(R.id.mobile_roaming);
        mMobileSignal = findViewById(R.id.mobile_signal);
        mCarrierText = findViewById(R.id.qs_carrier_text);
    }

    /**
     * Update the state of this view
     * @param state the current state of the signal for this view
     * @return true if the state was actually changed
     */
    public boolean updateState(CellSignalState state) {
        if (Objects.equals(state, mLastSignalState)) return false;
        mLastSignalState = state;
        mMobileGroup.setVisibility(state.visible ? View.VISIBLE : View.GONE);
        if (state.visible) {
            mMobileRoaming.setVisibility(state.roaming ? View.VISIBLE : View.GONE);
            ColorStateList colorStateList = Utils.getColorAttr(mContext,
                    android.R.attr.textColorPrimary);
            mMobileRoaming.setImageTintList(colorStateList);
            mMobileSignal.setImageTintList(colorStateList);

            if (state.providerModelBehavior) {
                if (!mProviderModelInitialized) {
                    mProviderModelInitialized = true;
                    mMobileSignal.setImageDrawable(
                            mContext.getDrawable(R.drawable.ic_qs_no_calling_sms));
                }
                mMobileSignal.setImageDrawable(mContext.getDrawable(state.mobileSignalIconId));
                mMobileSignal.setContentDescription(state.contentDescription);
            } else {
                if (!mProviderModelInitialized) {
                    mProviderModelInitialized = true;
                    mMobileSignal.setImageDrawable(new SignalDrawable(mContext));
                }
                mMobileSignal.setImageLevel(state.mobileSignalIconId);
                StringBuilder contentDescription = new StringBuilder();
                if (state.contentDescription != null) {
                    contentDescription.append(state.contentDescription).append(", ");
                }
                if (state.roaming) {
                    contentDescription
                            .append(mContext.getString(R.string.data_connection_roaming))
                            .append(", ");
                }
                // TODO: show mobile data off/no internet text for 5 seconds before carrier text
                if (hasValidTypeContentDescription(state.typeContentDescription)) {
                    contentDescription.append(state.typeContentDescription);
                }
                mMobileSignal.setContentDescription(contentDescription);
            }
        }
        return true;
    }

    private boolean hasValidTypeContentDescription(String typeContentDescription) {
        return TextUtils.equals(typeContentDescription,
                mContext.getString(R.string.data_connection_no_internet))
                || TextUtils.equals(typeContentDescription,
                mContext.getString(
                        com.android.settingslib.R.string.cell_data_off_content_description))
                || TextUtils.equals(typeContentDescription,
                mContext.getString(
                        com.android.settingslib.R.string.not_default_data_content_description));
    }

    public void setCarrierText(CharSequence text) {
        mCarrierText.setText(text);
    }
}
