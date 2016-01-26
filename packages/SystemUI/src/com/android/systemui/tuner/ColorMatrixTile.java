/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.tuner;

import android.app.ActivityManager;
import android.provider.Settings;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import libcore.util.Objects;

public class ColorMatrixTile extends QSTile<QSTile.State> implements TunerService.Tunable {

    public static final String COLOR_MATRIX_CUSTOM_ENABLED = "tuner_color_custom_enabled";
    public static final String COLOR_MATRIX_CUSTOM_VALUES = "tuner_color_custom_values";

    public static final String COLOR_MATRIX_SPEC = "colors";

    private int mIndex;
    private String mCurrentValue;

    private boolean mCustomEnabled;
    private String[] mValues;
    private CharSequence[] mValueTitles;

    public ColorMatrixTile(Host host) {
        super(host);
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mValues = ColorMatrixFragment.getColorTransforms();
            mValueTitles = ColorMatrixFragment.getColorTitles(mContext);
            TunerService.get(mContext).addTunable(this, COLOR_MATRIX_CUSTOM_ENABLED,
                    COLOR_MATRIX_CUSTOM_VALUES,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX);
        } else {
            TunerService.get(mContext).removeTunable(this);
        }
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    protected void handleClick() {
        mIndex++;
        if (!mCustomEnabled && (mIndex == ColorMatrixFragment.CUSTOM_INDEX)) {
            mIndex++;
        }
        if (mIndex == mValues.length - 1) {
            mIndex = 0;
        }
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX, mValues[mIndex],
                ActivityManager.getCurrentUser());
        refreshState();
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (COLOR_MATRIX_CUSTOM_ENABLED.equals(key)) {
            mCustomEnabled = newValue != null && Integer.parseInt(newValue) != 0;
        } else if (COLOR_MATRIX_CUSTOM_VALUES.equals(key)) {
            mValues[ColorMatrixFragment.CUSTOM_INDEX] = newValue;
        } else {
            mCurrentValue = newValue;
        }
        // Last value is unknown, default to that.
        mIndex = mValues.length - 1;
        for (int i = 0; i < mValues.length - 1; i++) {
            if (Objects.equal(mCurrentValue, mValues[i])) {
                mIndex = i;
                break;
            }
        }
        refreshState();
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        state.icon = ResourceIcon.get(R.drawable.ic_colorize);
        state.label = mValueTitles[mIndex];
        state.contentDescription = mValueTitles[mIndex];
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_COLOR_MATRIX;
    }
}
