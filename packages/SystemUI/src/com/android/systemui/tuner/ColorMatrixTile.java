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
import com.android.systemui.statusbar.policy.DisplayController;

import java.util.Objects;


public class ColorMatrixTile extends QSTile<QSTile.State> implements DisplayController.Listener {

    public static final String COLOR_MATRIX_SPEC = "colors";

    private final DisplayController mDisplayController;

    private int mIndex;
    private String mCurrentValue;

    private boolean mCustomEnabled;
    private String[] mValues;
    private CharSequence[] mValueTitles;

    public ColorMatrixTile(Host host) {
        super(host);
        mDisplayController = host.getDisplayController();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mValues = DisplayController.getColorTransforms(mContext);
            mValueTitles = DisplayController.getColorTitles(mContext);
            mDisplayController.addListener(this);
        } else {
            mDisplayController.removeListener(this);
        }
    }

    @Override
    protected State newTileState() {
        return new State();
    }

    @Override
    protected void handleClick() {
        mIndex++;
        if (mIndex == DisplayController.AUTO_INDEX) {
            mDisplayController.setAuto(true);
        } else {
            mDisplayController.setAuto(false);
            if (!mDisplayController.isCustomEnabled()
                    && (mIndex == DisplayController.CUSTOM_INDEX)) {
                mIndex++;
            }
            if (mIndex == mValues.length - 1) {
                mIndex = 0;
            }
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX, mValues[mIndex],
                    ActivityManager.getCurrentUser());
        }
        refreshState();
    }

    @Override
    protected void handleUpdateState(State state, Object arg) {
        if (mDisplayController.isAuto()) {
            mIndex = DisplayController.AUTO_INDEX;
        } else if (mDisplayController.isCustomSet()) {
            mIndex = DisplayController.CUSTOM_INDEX;
        } else {
            mIndex = Objects.equals(mDisplayController.getCurrentMatrix(), mValues[1]) ? 1 : 0;
        }
        state.icon = ResourceIcon.get(R.drawable.ic_colorize);
        state.label = mValueTitles[mIndex];
        state.contentDescription = mValueTitles[mIndex];
    }

    @Override
    public void onCurrentMatrixChanged() {
        refreshState();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_COLOR_MATRIX;
    }
}
