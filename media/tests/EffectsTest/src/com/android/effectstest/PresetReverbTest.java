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

import android.media.audiofx.PresetReverb;
import android.media.audiofx.AudioEffect;

public class PresetReverbTest extends Activity implements OnCheckedChangeListener {

    private final static String TAG = "PresetReverbTest";

    private static int NUM_PARAMS = 1;

    private EffectParameter[] mParameters = new EffectParameter[NUM_PARAMS];
    private PresetReverb mPresetReverb;
    ToggleButton mOnOffButton;
    ToggleButton mReleaseButton;
    EditText mSessionText;
    static int sSession = 0;
    EffectListner mEffectListener = new EffectListner();
    private static HashMap<Integer, PresetReverb> sInstances = new HashMap<Integer, PresetReverb>(10);
    String mSettings = "";

    public PresetReverbTest() {
        Log.d(TAG, "contructor");
    }

    private static String[] sPresetNames = {
        "NONE",         //PresetReverb.PRESET_NONE
        "SMALLROOM",    //PresetReverb.PRESET_SMALLROOM
        "MEDIUMROOM",   //PresetReverb.PRESET_MEDIUMROOM
        "LARGEROOM",    //PresetReverb.PRESET_LARGEROOM
        "MEDIUMHALL",   //PresetReverb.PRESET_MEDIUMHALL
        "LARGEHALL",    //PresetReverb.PRESET_LARGEHALL
        "PLATE",        //PresetReverb.PRESET_PLATE
    };

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.presetreverbtest);

        mSessionText = (EditText) findViewById(R.id.sessionEdit);
        mSessionText.setOnKeyListener(mSessionKeyListener);

        mSessionText.setText(Integer.toString(sSession));

        mReleaseButton = (ToggleButton)findViewById(R.id.presetrvbReleaseButton);
        mOnOffButton = (ToggleButton)findViewById(R.id.presetrvbOnOff);

        getEffect(sSession);

        if (mPresetReverb != null) {
            mReleaseButton.setOnCheckedChangeListener(this);
            mOnOffButton.setOnCheckedChangeListener(this);
            // Presets
            SeekBar seekBar = (SeekBar)findViewById(R.id.presetrvbParam1SeekBar);
            TextView textView = (TextView)findViewById(R.id.presetrvbParam1Value);
            mParameters[0] = new PresetParam(mPresetReverb, (short)0, (short)(sPresetNames.length - 1), seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[0]);
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
                            if (mPresetReverb != null) {
                                for (int i = 0 ; i < mParameters.length; i++) {
                                    mParameters[i].setEffect(mPresetReverb);
                                    mParameters[i].setEnabled(true);
                                    mParameters[i].updateDisplay();
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
        if (buttonView.getId() == R.id.presetrvbOnOff) {
            if (mPresetReverb != null) {
                mPresetReverb.setEnabled(isChecked);
                updateParams();
            }
        }
        if (buttonView.getId() == R.id.presetrvbReleaseButton) {
            if (isChecked) {
                if (mPresetReverb == null) {
                    getEffect(sSession);
                    if (mPresetReverb != null) {
                        for (int i = 0 ; i < mParameters.length; i++) {
                            mParameters[i].setEffect(mPresetReverb);
                            mParameters[i].setEnabled(true);
                            mParameters[i].updateDisplay();
                        }
                    }
                }
            } else {
                if (mPresetReverb != null) {
                    for (int i = 0 ; i < mParameters.length; i++) {
                        mParameters[i].setEnabled(false);
                    }
                    putEffect(sSession);
                }
            }
        }
    }

    private class PresetParam extends EffectParameter {
        private PresetReverb mPresetReverb;

        public PresetParam(PresetReverb presetrvb, short min, short max, SeekBar seekBar, TextView textView) {
            super (min, max, seekBar, textView, "");

            mPresetReverb = presetrvb;
            updateDisplay();
        }

        @Override
        public void setParameter(Integer value) {
            if (mPresetReverb != null) {
                mPresetReverb.setPreset(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mPresetReverb != null) {
                return new Integer(mPresetReverb.getPreset());
            }
            return new Integer(0);
        }

        @Override
        public void displayValue(int value, boolean fromTouch) {
            mValueText.setText(sPresetNames[value]);
            if (!fromTouch) {
                mSeekBar.setProgress(value - mMin);
            } else {
                updateParams();
            }
        }

        @Override
        public void setEffect(Object presetrvb) {
            mPresetReverb = (PresetReverb)presetrvb;
        }

    }

    protected void updateParams() {
        for (int i = 0 ; i < mParameters.length; i++) {
            mParameters[i].updateDisplay();
        }
    }

    public class EffectListner implements AudioEffect.OnEnableStatusChangeListener,
    AudioEffect.OnControlStatusChangeListener,
    PresetReverb.OnParameterChangeListener
   {
        public EffectListner() {
        }
        public void onEnableStatusChange(AudioEffect effect, boolean enabled) {
            Log.d(TAG,"onEnableStatusChange: "+ enabled);
        }
        public void onControlStatusChange(AudioEffect effect, boolean controlGranted) {
            Log.d(TAG,"onControlStatusChange: "+ controlGranted);
        }

        public void onParameterChange(PresetReverb effect, int status, int param, short value) {
            Log.d(TAG,"onParameterChange, status: "+status+" p: "+param+" v: "+value);
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
                mPresetReverb = sInstances.get(session);
            } else {
                try{
                    mPresetReverb = new PresetReverb(0, session);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG,"PresetReverb effect not supported");
                } catch (UnsupportedOperationException e) {
                    Log.e(TAG,"PresetReverb library not loaded");
                } catch (RuntimeException e) {
                    Log.e(TAG,"PresetReverb effect not found");
                }
                sInstances.put(session, mPresetReverb);
            }
        }
        mReleaseButton.setEnabled(false);
        mOnOffButton.setEnabled(false);

        if (mPresetReverb != null) {
            if (mSettings != "") {
                mPresetReverb.setProperties(new PresetReverb.Settings(mSettings));
            }
            mPresetReverb.setEnableStatusListener(mEffectListener);
            mPresetReverb.setControlStatusListener(mEffectListener);
            mPresetReverb.setParameterListener(mEffectListener);

            mReleaseButton.setChecked(true);
            mReleaseButton.setEnabled(true);

            mOnOffButton.setChecked(mPresetReverb.getEnabled());
            mOnOffButton.setEnabled(true);
        }
    }

    private void putEffect(int session) {
        mOnOffButton.setChecked(false);
        mOnOffButton.setEnabled(false);
        synchronized (sInstances) {
            if (mPresetReverb != null) {
                mSettings = mPresetReverb.getProperties().toString();
                mPresetReverb.release();
                Log.d(TAG,"PresetReverb released");
                mPresetReverb = null;
                sInstances.remove(session);
            }
        }
    }
}
