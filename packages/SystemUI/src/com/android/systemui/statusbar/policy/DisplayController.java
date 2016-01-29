/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import libcore.util.Objects;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.provider.Settings;

import com.android.systemui.R;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;

public class DisplayController implements TunerService.Tunable {

    public static final String COLOR_MATRIX_CUSTOM_ENABLED = "tuner_color_custom_enabled";
    public static final String COLOR_MATRIX_CUSTOM_VALUES = "tuner_color_custom_values";

    public static final String COLOR_STATE = "sysui_color_matrix_state";

    public static final int COLOR_STATE_DISABLED = 0;
    public static final int COLOR_STATE_ENABLED = 1;
    public static final int COLOR_STATE_AUTO = 2;

    public static final String AUTO_STRING = "auto_mode";
    public static final String NONE_STRING = "none";

    public static final int AUTO_INDEX = 2;
    public static final int CUSTOM_INDEX = 3;

    // Night mode ~= 3400 K
    private static final float[] NIGHT_VALUES = new float[] {
        1, 0,     0,     0,
        0, .754f, 0,     0,
        0, 0,     .516f, 0,
        0, 0,     0,     1,
    };
    public static final float[] IDENTITY_MATRIX = new float[] {
        1, 0, 0, 0,
        0, 1, 0, 0,
        0, 0, 1, 0,
        0, 0, 0, 1,
    };

    private final ArrayList<Listener> mListeners = new ArrayList<>();

    private final Context mContext;

    private String mCurrentValue;
    private boolean mListening;

    public DisplayController(Context context) {
        mContext = context;
        TunerService.get(mContext).addTunable(this, COLOR_STATE,
                Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX);
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
        listener.onCurrentMatrixChanged();
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public boolean isEnabled() {
        return TunerService.get(mContext).getValue(COLOR_STATE, COLOR_STATE_DISABLED)
                != COLOR_STATE_DISABLED;
    }

    public boolean isAuto() {
        return mListening;
    }

    public void setAuto(boolean auto) {
        TunerService.get(mContext).setValue(COLOR_STATE, auto ? COLOR_STATE_AUTO
                : COLOR_STATE_DISABLED);
    }

    public boolean isCustomSet() {
        return isCustomEnabled() && Objects.equal(getCurrentMatrix(), getCustomValues());
    }

    public String getCurrentMatrix() {
        return mCurrentValue;
    }

    public String getCustomValues() {
        return TunerService.get(mContext).getValue(COLOR_MATRIX_CUSTOM_VALUES);
    }

    public boolean isCustomEnabled() {
        return TunerService.get(mContext).getValue(COLOR_MATRIX_CUSTOM_ENABLED, 0) != 0;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX.equals(key)) {
            mCurrentValue = newValue;
            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onCurrentMatrixChanged();
            }
        } else if (COLOR_STATE.equals(key)) {
            final boolean listening = newValue != null
                    && Integer.parseInt(newValue) == COLOR_STATE_AUTO;
            if (listening && !mListening) {
                mListening = true;
                mContext.registerReceiver(mReceiver,
                        new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
                updateNightMode();
            } else if (!listening && mListening) {
                mListening = false;
                mContext.unregisterReceiver(mReceiver);
            }
            for (int i = 0; i < mListeners.size(); i++) {
                mListeners.get(i).onCurrentMatrixChanged();
            }
        }
    }

    private void updateNightMode() {
        final int uiMode = mContext.getResources().getConfiguration().uiMode;
        final boolean isNightMode = (uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        String value = null;
        if (isNightMode) {
            value = toString(NIGHT_VALUES);
        }
        TunerService.get(mContext).setValue(Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX,
                value);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
                updateNightMode();
            }
        }
    };

    public interface Listener {
        void onCurrentMatrixChanged();
    }

    public static String[] getColorTransforms(Context context) {
        return new String[] {
                NONE_STRING,
                toString(NIGHT_VALUES),
                AUTO_STRING, // Blank spot for auto values
                null, // Blank spot for custom values
        };
    }

    public static CharSequence[] getColorTitles(Context context) {
        // TODO: Move to string array resource.
        return new CharSequence[]{
                context.getString(R.string.color_matrix_none),
                context.getString(R.string.color_matrix_night),
                context.getString(R.string.color_matrix_auto),
                context.getString(R.string.color_matrix_custom),
        };
    }

    public static String toString(float[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (builder.length() != 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.toString();
    }
}
