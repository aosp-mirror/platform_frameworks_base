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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.opengl.Matrix;
import android.provider.Settings.Secure;
import android.util.MathUtils;
import com.android.systemui.tuner.TunerService;

import java.util.ArrayList;

/**
 * Listens for changes to twilight from the TwilightService.
 *
 * Also pushes the current matrix to accessibility based on the current twilight
 * and various tuner settings.
 */
public class NightModeController implements TunerService.Tunable {

    public static final String NIGHT_MODE_ADJUST_TINT = "tuner_night_mode_adjust_tint";
    private static final String COLOR_MATRIX_CUSTOM_VALUES = "tuner_color_custom_values";

    private static final String ACTION_TWILIGHT_CHANGED = "android.intent.action.TWILIGHT_CHANGED";

    private static final String EXTRA_IS_NIGHT = "isNight";
    private static final String EXTRA_AMOUNT = "amount";

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

    // This is whether or not this is the main NightMode controller in SysUI that should be
    // updating relevant color matrixes or if its in the tuner process getting current state
    // for UI.
    private final boolean mUpdateMatrix;

    private float[] mCustomMatrix;
    private boolean mListening;
    private boolean mAdjustTint;

    private boolean mIsNight;
    private float mAmount;
    private boolean mIsAuto;

    public NightModeController(Context context) {
        this(context, false);
    }

    public NightModeController(Context context, boolean updateMatrix) {
        mContext = context;
        mUpdateMatrix = updateMatrix;
        TunerService.get(mContext).addTunable(this, NIGHT_MODE_ADJUST_TINT,
                COLOR_MATRIX_CUSTOM_VALUES, Secure.TWILIGHT_MODE);
    }

    public void setNightMode(boolean isNight) {
        if (mIsAuto) {
            if (mIsNight != isNight) {
                TunerService.get(mContext).setValue(Secure.TWILIGHT_MODE, isNight
                        ? Secure.TWILIGHT_MODE_AUTO_OVERRIDE_ON
                        : Secure.TWILIGHT_MODE_AUTO_OVERRIDE_OFF);
            } else {
                TunerService.get(mContext).setValue(Secure.TWILIGHT_MODE,
                        Secure.TWILIGHT_MODE_AUTO);
            }
        } else {
            TunerService.get(mContext).setValue(Secure.TWILIGHT_MODE, isNight
                    ? Secure.TWILIGHT_MODE_LOCKED_ON : Secure.TWILIGHT_MODE_LOCKED_OFF);
        }
    }

    public void setAuto(boolean auto) {
        mIsAuto = auto;
        if (auto) {
            TunerService.get(mContext).setValue(Secure.TWILIGHT_MODE, Secure.TWILIGHT_MODE_AUTO);
        } else {
            // Lock into the current state
            TunerService.get(mContext).setValue(Secure.TWILIGHT_MODE, mIsNight
                    ? Secure.TWILIGHT_MODE_LOCKED_ON : Secure.TWILIGHT_MODE_LOCKED_OFF);
        }
    }

    public boolean isAuto() {
        return mIsAuto;
    }

    public void setAdjustTint(Boolean newValue) {
        TunerService.get(mContext).setValue(NIGHT_MODE_ADJUST_TINT, ((Boolean) newValue) ? 1 : 0);
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
        listener.onNightModeChanged();
        updateListening();
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
        updateListening();
    }

    private void updateListening() {
        boolean shouldListen = mListeners.size() != 0 || (mUpdateMatrix && mAdjustTint);
        if (shouldListen == mListening) return;
        mListening = shouldListen;
        if (mListening) {
            mContext.registerReceiver(mReceiver, new IntentFilter(ACTION_TWILIGHT_CHANGED));
        } else {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    public boolean isEnabled() {
        if (!mListening) {
            updateNightMode(mContext.registerReceiver(null,
                    new IntentFilter(ACTION_TWILIGHT_CHANGED)));
        }
        return mIsNight;
    }

    public String getCustomValues() {
        return TunerService.get(mContext).getValue(COLOR_MATRIX_CUSTOM_VALUES);
    }

    public void setCustomValues(String values) {
        TunerService.get(mContext).setValue(COLOR_MATRIX_CUSTOM_VALUES, values);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (COLOR_MATRIX_CUSTOM_VALUES.equals(key)) {
            mCustomMatrix = newValue != null ? toValues(newValue) : null;
            updateCurrentMatrix();
        } else if (NIGHT_MODE_ADJUST_TINT.equals(key)) {
            mAdjustTint = newValue == null || Integer.parseInt(newValue) != 0;
            updateListening();
            updateCurrentMatrix();
        } else if (Secure.TWILIGHT_MODE.equals(key)) {
            mIsAuto = newValue != null && Integer.parseInt(newValue) >= Secure.TWILIGHT_MODE_AUTO;
        }
    }

    private void updateCurrentMatrix() {
        if (!mUpdateMatrix) return;
        if ((!mAdjustTint || mAmount == 0) && mCustomMatrix == null) {
            TunerService.get(mContext).setValue(Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX, null);
            return;
        }
        float[] values = scaleValues(IDENTITY_MATRIX, NIGHT_VALUES, mAdjustTint ? mAmount : 0);
        if (mCustomMatrix != null) {
            values = multiply(values, mCustomMatrix);
        }
        TunerService.get(mContext).setValue(Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX,
                toString(values));
    }

    private void updateNightMode(Intent intent) {
        mIsNight = intent != null && intent.getBooleanExtra(EXTRA_IS_NIGHT, false);
        mAmount = intent != null ? intent.getFloatExtra(EXTRA_AMOUNT, 0) : 0;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_TWILIGHT_CHANGED.equals(intent.getAction())) {
                updateNightMode(intent);
                updateCurrentMatrix();
                for (int i = 0; i < mListeners.size(); i++) {
                    mListeners.get(i).onNightModeChanged();
                }
            }
        }
    };

    public interface Listener {
        void onNightModeChanged();
        void onTwilightAutoChanged();
    }

    private static float[] multiply(float[] matrix, float[] other) {
        if (matrix == null) {
            return other;
        }
        float[] result = new float[16];
        Matrix.multiplyMM(result, 0, matrix, 0, other, 0);
        return result;
    }

    private float[] scaleValues(float[] identityMatrix, float[] nightValues, float amount) {
        float[] values = new float[identityMatrix.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = MathUtils.lerp(identityMatrix[i], nightValues[i], amount);
        }
        return values;
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

    public static float[] toValues(String customValues) {
        String[] strValues = customValues.split(",");
        float[] values = new float[strValues.length];
        for (int i = 0; i < values.length; i++) {
            values[i] = Float.parseFloat(strValues[i]);
        }
        return values;
    }
}
