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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View.OnClickListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.ToggleButton;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;


import android.media.audiofx.Equalizer;
import android.media.audiofx.AudioEffect;

public class EqualizerTest extends Activity implements OnCheckedChangeListener {

    private final static String TAG = "EqualizerTest";

    private static int NUM_BANDS = 5;
    private static int NUM_PARAMS = NUM_BANDS + 1;

    private EffectParameter[] mParameters = new EffectParameter[NUM_PARAMS];
    private Equalizer mEqualizer;
    ToggleButton mOnOffButton;
    ToggleButton mReleaseButton;
    EditText mSessionText;
    static int sSession = 0;
    EffectListner mEffectListener = new EffectListner();
    private static HashMap<Integer, Equalizer> sInstances = new HashMap<Integer, Equalizer>(10);
    String mSettings = "";

    public EqualizerTest() {
        Log.d(TAG, "contructor");
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        SeekBar seekBar;
        TextView textView;

        setContentView(R.layout.equalizertest);

        mSessionText = findViewById(R.id.sessionEdit);
        mSessionText.setOnKeyListener(mSessionKeyListener);

        mSessionText.setText(Integer.toString(sSession));

        mReleaseButton = (ToggleButton)findViewById(R.id.eqReleaseButton);
        mOnOffButton = (ToggleButton)findViewById(R.id.equalizerOnOff);

        getEffect(sSession);

        if (mEqualizer != null) {
            mReleaseButton.setOnCheckedChangeListener(this);
            mOnOffButton.setOnCheckedChangeListener(this);

            short[] bandLevelRange = mEqualizer.getBandLevelRange();
            int centerFreq;
            int []freqRange;

            // Band 1 level
            centerFreq = mEqualizer.getCenterFreq((short)0);
            freqRange = mEqualizer.getBandFreqRange((short)0);
            displayFreq(R.id.eqParam1Center, centerFreq);
            displayFreq(R.id.eqParam1Min, freqRange[0]);
            displayFreq(R.id.eqParam1Max, freqRange[1]);
            seekBar = (SeekBar)findViewById(R.id.eqParam1SeekBar);
            textView = (TextView)findViewById(R.id.eqParam1Value);
            mParameters[0] = new BandLevelParam(mEqualizer, 0, bandLevelRange[0], bandLevelRange[1], seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[0]);

            // Band 2 level
            centerFreq = mEqualizer.getCenterFreq((short)1);
            freqRange = mEqualizer.getBandFreqRange((short)1);
            displayFreq(R.id.eqParam2Center, centerFreq);
            displayFreq(R.id.eqParam2Min, freqRange[0]);
            displayFreq(R.id.eqParam2Max, freqRange[1]);
            seekBar = (SeekBar)findViewById(R.id.eqParam2SeekBar);
            textView = (TextView)findViewById(R.id.eqParam2Value);
            mParameters[1] = new BandLevelParam(mEqualizer, 1, bandLevelRange[0], bandLevelRange[1], seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[1]);

            // Band 3 level
            centerFreq = mEqualizer.getCenterFreq((short)2);
            freqRange = mEqualizer.getBandFreqRange((short)2);
            displayFreq(R.id.eqParam3Center, centerFreq);
            displayFreq(R.id.eqParam3Min, freqRange[0]);
            displayFreq(R.id.eqParam3Max, freqRange[1]);
            seekBar = (SeekBar)findViewById(R.id.eqParam3SeekBar);
            textView = (TextView)findViewById(R.id.eqParam3Value);
            mParameters[2] = new BandLevelParam(mEqualizer, 2, bandLevelRange[0], bandLevelRange[1], seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[2]);

            // Band 4 level
            centerFreq = mEqualizer.getCenterFreq((short)3);
            freqRange = mEqualizer.getBandFreqRange((short)3);
            displayFreq(R.id.eqParam4Center, centerFreq);
            displayFreq(R.id.eqParam4Min, freqRange[0]);
            displayFreq(R.id.eqParam4Max, freqRange[1]);
            seekBar = (SeekBar)findViewById(R.id.eqParam4SeekBar);
            textView = (TextView)findViewById(R.id.eqParam4Value);
            mParameters[3] = new BandLevelParam(mEqualizer, 3, bandLevelRange[0], bandLevelRange[1], seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[3]);

            // Band 5 level
            centerFreq = mEqualizer.getCenterFreq((short)4);
            freqRange = mEqualizer.getBandFreqRange((short)4);
            displayFreq(R.id.eqParam5Center, centerFreq);
            displayFreq(R.id.eqParam5Min, freqRange[0]);
            displayFreq(R.id.eqParam5Max, freqRange[1]);
            seekBar = (SeekBar)findViewById(R.id.eqParam5SeekBar);
            textView = (TextView)findViewById(R.id.eqParam5Value);
            mParameters[4] = new BandLevelParam(mEqualizer, 4, bandLevelRange[0], bandLevelRange[1], seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[4]);

            // Presets
            short numPresets = mEqualizer.getNumberOfPresets();
            seekBar = (SeekBar)findViewById(R.id.eqParam6SeekBar);
            textView = (TextView)findViewById(R.id.eqParam6Value);
            mParameters[5] = new PresetParam(mEqualizer, (short)0, (short)(numPresets-1), seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[5]);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    private View.OnKeyListener mSessionKeyListener
    = new View.OnKeyListener() {
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        try {
                            sSession = Integer.parseInt(mSessionText.getText().toString());
                            getEffect(sSession);
                            if (mEqualizer != null) {
                                for (int i = 0 ; i < mParameters.length; i++) {
                                    mParameters[i].setEffect(mEqualizer);
                                    mParameters[i].setEnabled(true);
                                }
                            }
                        } catch (NumberFormatException e) {
                            Log.d(TAG, "Invalid session #: "+mSessionText.getText().toString());
                        }

                        return true;
                }
            }
            return false;
        }
    };

    // OnCheckedChangeListener
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView.getId() == R.id.equalizerOnOff) {
            if (mEqualizer != null) {
                mEqualizer.setEnabled(isChecked);
                updateBands();
            }
        }
        if (buttonView.getId() == R.id.eqReleaseButton) {
            if (isChecked) {
                if (mEqualizer == null) {
                    getEffect(sSession);
                    if (mEqualizer != null) {
                        for (int i = 0 ; i < mParameters.length; i++) {
                            mParameters[i].setEffect(mEqualizer);
                            mParameters[i].setEnabled(true);
                        }
                    }
                }
            } else {
                if (mEqualizer != null) {
                    for (int i = 0 ; i < mParameters.length; i++) {
                        mParameters[i].setEnabled(false);
                    }
                    putEffect(sSession);
                }
            }
        }
    }

    protected void updateBands() {
        for (int i = 0 ; i < NUM_BANDS; i++) {
            mParameters[i].updateDisplay();
        }
    }

    private void displayFreq(int viewId, int freq) {
        TextView textView = (TextView)findViewById(viewId);
        String text = Integer.toString(freq/1000)+" Hz";
        textView.setText(text);
    }

    private class EqualizerParam extends EffectParameter {
        private Equalizer mEqualizer;

        public EqualizerParam(Equalizer equalizer, int min, int max, SeekBar seekBar, TextView textView, String unit) {
            super (min, max, seekBar, textView, unit);

            mEqualizer = equalizer;
            updateDisplay();
        }

        @Override
        public void setParameter(Integer value) {
        }

        @Override
        public Integer getParameter() {
            return new Integer(0);
        }

        @Override
        public void setEffect(Object eq) {
            mEqualizer = (Equalizer)eq;
        }
    }

    private class BandLevelParam extends EqualizerParam {
        private int mBand;

        public BandLevelParam(Equalizer equalizer, int band, short min, short max, SeekBar seekBar, TextView textView) {
            super (equalizer, min, max, seekBar, textView, "mB");

            mBand = band;
            mEqualizer = equalizer;
            updateDisplay();
        }

        @Override
        public void setParameter(Integer value) {
            if (mEqualizer != null) {
                mEqualizer.setBandLevel((short)mBand, value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mEqualizer != null) {
                return new Integer(mEqualizer.getBandLevel((short)mBand));
            }
            return new Integer(0);
        }
    }

    private class PresetParam extends EqualizerParam {

        public PresetParam(Equalizer equalizer, short min, short max, SeekBar seekBar, TextView textView) {
            super (equalizer, min, max, seekBar, textView, "");

            mEqualizer = equalizer;
            updateDisplay();
        }

        @Override
        public void setParameter(Integer value) {
            if (mEqualizer != null) {
                mEqualizer.usePreset(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mEqualizer != null) {
                return new Integer(mEqualizer.getCurrentPreset());
            }
            return new Integer(0);
        }

        @Override
        public void displayValue(int value, boolean fromTouch) {
            String text = mEqualizer.getPresetName((short)value);
            mValueText.setText(text);
            if (!fromTouch) {
                mSeekBar.setProgress(value - mMin);
            } else {
                updateBands();
            }
        }
    }

    public class EffectListner implements AudioEffect.OnEnableStatusChangeListener,
    AudioEffect.OnControlStatusChangeListener,
    Equalizer.OnParameterChangeListener
   {
        public EffectListner() {
        }
        public void onEnableStatusChange(AudioEffect effect, boolean enabled) {
            Log.d(TAG,"onEnableStatusChange: "+ enabled);
        }
        public void onControlStatusChange(AudioEffect effect, boolean controlGranted) {
            Log.d(TAG,"onControlStatusChange: "+ controlGranted);
        }

        public void onParameterChange(Equalizer effect, int status, int param1, int param2, int value) {
            Log.d(TAG,"onParameterChange EQ, status: "+status+" p1: "+param1+" p2: "+param2+" v: "+value);
        }

        private int byteArrayToInt(byte[] valueBuf, int offset) {
            ByteBuffer converter = ByteBuffer.wrap(valueBuf);
            converter.order(ByteOrder.nativeOrder());
            return converter.getInt(offset);

        }
        private short byteArrayToShort(byte[] valueBuf, int offset) {
            ByteBuffer converter = ByteBuffer.wrap(valueBuf);
            converter.order(ByteOrder.nativeOrder());
            return converter.getShort(offset);

        }
    }

    private void getEffect(int session) {
        synchronized (sInstances) {
            if (sInstances.containsKey(session)) {
                mEqualizer = sInstances.get(session);
            } else {
                try{
                    mEqualizer = new Equalizer(0, session);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG,"Equalizer effect not supported");
                } catch (UnsupportedOperationException e) {
                    Log.e(TAG,"Equalizer library not loaded");
                } catch (IllegalStateException e) {
                    Log.e(TAG,"Equalizer cannot get presets");
                } catch (RuntimeException e) {
                    Log.e(TAG,"Equalizer effect not found");
                }
                sInstances.put(session, mEqualizer);
            }
        }
        mReleaseButton.setEnabled(false);
        mOnOffButton.setEnabled(false);
        if (mEqualizer != null) {
            if (mSettings != "") {
                Log.d(TAG,"Equalizer settings: "+mSettings);
                mEqualizer.setProperties(new Equalizer.Settings(mSettings));
            }

            mEqualizer.setEnableStatusListener(mEffectListener);
            mEqualizer.setControlStatusListener(mEffectListener);
            mEqualizer.setParameterListener(mEffectListener);

            mReleaseButton.setChecked(true);
            mReleaseButton.setEnabled(true);

            mOnOffButton.setChecked(mEqualizer.getEnabled());
            mOnOffButton.setEnabled(true);
        }
    }

    private void putEffect(int session) {
//        mOnOffButton.setChecked(false);
        mOnOffButton.setEnabled(false);
        synchronized (sInstances) {
            if (mEqualizer != null) {
                mSettings = mEqualizer.getProperties().toString();
                mEqualizer.release();
                Log.d(TAG,"Equalizer released, settings: "+mSettings);
                mEqualizer = null;
                sInstances.remove(session);
            }
        }
    }
}
