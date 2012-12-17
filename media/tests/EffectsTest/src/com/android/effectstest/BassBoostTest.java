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

import android.media.audiofx.BassBoost;
import android.media.audiofx.AudioEffect;

public class BassBoostTest extends Activity implements OnCheckedChangeListener {

    private final static String TAG = "BassBoostTest";

    private static int NUM_PARAMS = 1;

    private EffectParameter mStrength;
    private BassBoost mBassBoost = null;
    ToggleButton mOnOffButton;
    ToggleButton mReleaseButton;
    EditText mSessionText;
    static int sSession = 0;
    EffectListner mEffectListener = new EffectListner();
    private static HashMap<Integer, BassBoost> sInstances = new HashMap<Integer, BassBoost>(10);
    String mSettings = "";

    public BassBoostTest() {
        Log.d(TAG, "contructor");
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        SeekBar seekBar;
        TextView textView;

        setContentView(R.layout.bassboosttest);

        mSessionText = (EditText) findViewById(R.id.sessionEdit);
        mSessionText.setOnKeyListener(mSessionKeyListener);

        mSessionText.setText(Integer.toString(sSession));

        mReleaseButton = (ToggleButton)findViewById(R.id.bbReleaseButton);
        mOnOffButton = (ToggleButton)findViewById(R.id.bassboostOnOff);

        getEffect(sSession);

        if (mBassBoost != null) {
            mReleaseButton.setOnCheckedChangeListener(this);
            mOnOffButton.setOnCheckedChangeListener(this);

            textView = (TextView)findViewById(R.id.bbStrengthMin);
            textView.setText("0");
            textView = (TextView)findViewById(R.id.bbStrengthMax);
            textView.setText("1000");
            seekBar = (SeekBar)findViewById(R.id.bbStrengthSeekBar);
            textView = (TextView)findViewById(R.id.bbStrengthValue);
            mStrength = new BassBoostParam(mBassBoost, 0, 1000, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mStrength);
            mStrength.setEnabled(mBassBoost.getStrengthSupported());
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
            Log.d(TAG, "onKey() keyCode: "+keyCode+" event.getAction(): "+event.getAction());
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                        try {
                            sSession = Integer.parseInt(mSessionText.getText().toString());
                            getEffect(sSession);
                            if (mBassBoost != null) {
                                mStrength.setEffect(mBassBoost);
                                mStrength.setEnabled(mBassBoost.getStrengthSupported());
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
        if (buttonView.getId() == R.id.bassboostOnOff) {
            if (mBassBoost != null) {
                mBassBoost.setEnabled(isChecked);
                mStrength.updateDisplay();
            }
        }
        if (buttonView.getId() == R.id.bbReleaseButton) {
            if (isChecked) {
                if (mBassBoost == null) {
                    getEffect(sSession);
                    if (mBassBoost != null) {
                        mStrength.setEffect(mBassBoost);
                        mStrength.setEnabled(mBassBoost.getStrengthSupported());
                    }
                }
            } else {
                if (mBassBoost != null) {
                    mStrength.setEnabled(false);
                    putEffect(sSession);
                }
            }
        }
    }

    private class BassBoostParam extends EffectParameter {
        private BassBoost mBassBoost;

        public BassBoostParam(BassBoost bassboost, int min, int max, SeekBar seekBar, TextView textView) {
            super (min, max, seekBar, textView, "o/oo");

            mBassBoost = bassboost;
            updateDisplay();
        }

        @Override
        public void setParameter(Integer value) {
            if (mBassBoost != null) {
                mBassBoost.setStrength(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mBassBoost != null) {
                return new Integer(mBassBoost.getRoundedStrength());
            }
            return new Integer(0);
        }

        @Override
        public void setEffect(Object effect) {
            mBassBoost = (BassBoost)effect;
        }
    }

    public class EffectListner implements AudioEffect.OnEnableStatusChangeListener,
        AudioEffect.OnControlStatusChangeListener, AudioEffect.OnParameterChangeListener
   {
        public EffectListner() {
        }
        public void onEnableStatusChange(AudioEffect effect, boolean enabled) {
            Log.d(TAG,"onEnableStatusChange: "+ enabled);
        }
        public void onControlStatusChange(AudioEffect effect, boolean controlGranted) {
            Log.d(TAG,"onControlStatusChange: "+ controlGranted);
        }
        public void onParameterChange(AudioEffect effect, int status, byte[] param, byte[] value) {
            int p = byteArrayToInt(param, 0);
            short v = byteArrayToShort(value, 0);

            Log.d(TAG,"onParameterChange, status: "+status+" p: "+p+" v: "+v);
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
                mBassBoost = sInstances.get(session);
            } else {
                try{
                    mBassBoost = new BassBoost(0, session);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG,"BassBoost effect not supported");
                } catch (IllegalStateException e) {
                    Log.e(TAG,"BassBoost cannot get strength supported");
                } catch (UnsupportedOperationException e) {
                    Log.e(TAG,"BassBoost library not loaded");
                } catch (RuntimeException e) {
                    Log.e(TAG,"BassBoost effect not found");
                }
                sInstances.put(session, mBassBoost);
            }
            mReleaseButton.setEnabled(false);
            mOnOffButton.setEnabled(false);

            if (mBassBoost != null) {
                if (mSettings != "") {
                    mBassBoost.setProperties(new BassBoost.Settings(mSettings));
                }
                mBassBoost.setEnableStatusListener(mEffectListener);
                mBassBoost.setControlStatusListener(mEffectListener);
                mBassBoost.setParameterListener(mEffectListener);

                mReleaseButton.setChecked(true);
                mReleaseButton.setEnabled(true);

                mOnOffButton.setChecked(mBassBoost.getEnabled());
                mOnOffButton.setEnabled(true);
            }
        }
    }

    private void putEffect(int session) {
        mOnOffButton.setChecked(false);
        mOnOffButton.setEnabled(false);
        synchronized (sInstances) {
            if (mBassBoost != null) {
                mSettings = mBassBoost.getProperties().toString();
                mBassBoost.release();
                Log.d(TAG,"BassBoost released");
                mBassBoost = null;
                sInstances.remove(session);
            }
        }
    }

}
