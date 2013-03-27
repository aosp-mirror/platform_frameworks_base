/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.effectstest;

import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.SeekBar;


abstract class EffectParameter implements SeekBar.OnSeekBarChangeListener {

    private final static String TAG = "EffectParameter";

    protected int mMin;
    protected int mMax;
    protected String mUnit;
    protected SeekBar mSeekBar;
    protected TextView mValueText;

    public EffectParameter (int min, int max, SeekBar seekBar, TextView textView, String unit) {
        mMin = min;
        mMax = max;
        mSeekBar = seekBar;
        mValueText = textView;
        mUnit = unit;
        byte[] paramBuf = new byte[4];

        mSeekBar.setMax(max-min);
    }

    public void displayValue(int value, boolean fromTouch) {
        String text = Integer.toString(value)+" "+mUnit;
        mValueText.setText(text);
        if (!fromTouch) {
            mSeekBar.setProgress(value - mMin);
        }
    }

    public void updateDisplay() {
        displayValue(getParameter(), false);
    }

    public abstract void setParameter(Integer value);

    public abstract Integer getParameter();

    public abstract void setEffect(Object effect);

    // SeekBar.OnSeekBarChangeListener
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {

        if (seekBar != mSeekBar) {
            Log.e(TAG, "onProgressChanged called with wrong seekBar");
            return;
        }

        int value = progress + mMin;
        if (fromTouch) {
            setParameter(value);
        }

        displayValue(getParameter(), fromTouch);
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
    }

    public void setEnabled(boolean e) {
        mSeekBar.setEnabled(e);
    }
}
