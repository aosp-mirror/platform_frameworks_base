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

import android.media.audiofx.Virtualizer;
import android.media.audiofx.AudioEffect;

public class VirtualizerTest extends Activity implements OnCheckedChangeListener {

    private final static String TAG = "VirtualizerTest";

    private static int NUM_PARAMS = 1;

    private EffectParameter mStrength;
    private Virtualizer mVirtualizer;
    ToggleButton mOnOffButton;
    ToggleButton mReleaseButton;
    EditText mSessionText;
    static int sSession = 0;
    EffectListner mEffectListener = new EffectListner();
    private static HashMap<Integer, Virtualizer> sInstances = new HashMap<Integer, Virtualizer>(10);
    String mSettings = "";

    public VirtualizerTest() {
        Log.d(TAG, "contructor");
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        SeekBar seekBar;
        TextView textView;

        setContentView(R.layout.virtualizertest);

        mSessionText = (EditText) findViewById(R.id.sessionEdit);
        mSessionText.setOnKeyListener(mSessionKeyListener);
        mSessionText.setText(Integer.toString(sSession));

        mReleaseButton = (ToggleButton)findViewById(R.id.virtReleaseButton);
        mOnOffButton = (ToggleButton)findViewById(R.id.virtualizerOnOff);

        getEffect(sSession);

        if (mVirtualizer != null) {
            mReleaseButton.setOnCheckedChangeListener(this);
            mOnOffButton.setOnCheckedChangeListener(this);
            textView = (TextView)findViewById(R.id.virtStrengthMin);
            textView.setText("0");
            textView = (TextView)findViewById(R.id.virtStrengthMax);
            textView.setText("1000");
            seekBar = (SeekBar)findViewById(R.id.virtStrengthSeekBar);
            textView = (TextView)findViewById(R.id.virtStrengthValue);
            mStrength = new VirtualizerParam(mVirtualizer, 0, 1000, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mStrength);
            mStrength.setEnabled(mVirtualizer.getStrengthSupported());
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
                            if (mVirtualizer != null) {
                                mStrength.setEffect(mVirtualizer);
                                mStrength.setEnabled(mVirtualizer.getStrengthSupported());
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
        if (buttonView.getId() == R.id.virtualizerOnOff) {
            if (mVirtualizer != null) {
                mVirtualizer.setEnabled(isChecked);
                mStrength.updateDisplay();
            }
        }
        if (buttonView.getId() == R.id.virtReleaseButton) {
            if (isChecked) {
                if (mVirtualizer == null) {
                    getEffect(sSession);
                    if (mVirtualizer != null) {
                        mStrength.setEffect(mVirtualizer);
                        mStrength.setEnabled(mVirtualizer.getStrengthSupported());
                    }
                }
            } else {
                if (mVirtualizer != null) {
                    mStrength.setEnabled(false);
                    putEffect(sSession);
                }
            }
        }
    }

    private class VirtualizerParam extends EffectParameter {
        private Virtualizer mVirtualizer;

        public VirtualizerParam(Virtualizer virtualizer, int min, int max, SeekBar seekBar, TextView textView) {
            super (min, max, seekBar, textView, "o/oo");

            mVirtualizer = virtualizer;
            updateDisplay();
        }

        @Override
        public void setParameter(Integer value) {
            if (mVirtualizer != null) {
                mVirtualizer.setStrength(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mVirtualizer != null) {
                return new Integer(mVirtualizer.getRoundedStrength());
            }
            return new Integer(0);
        }

        @Override
        public void setEffect(Object effect) {
            mVirtualizer = (Virtualizer)effect;
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
                mVirtualizer = sInstances.get(session);
            } else {
                try{
                    mVirtualizer = new Virtualizer(0, session);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG,"Virtualizer effect not supported");
                } catch (IllegalStateException e) {
                    Log.e(TAG,"Virtualizer cannot get strength supported");
                } catch (UnsupportedOperationException e) {
                    Log.e(TAG,"Virtualizer library not loaded");
                } catch (RuntimeException e) {
                    Log.e(TAG,"Virtualizer effect not found");
                }
                sInstances.put(session, mVirtualizer);
            }
        }
        mReleaseButton.setEnabled(false);
        mOnOffButton.setEnabled(false);

        if (mVirtualizer != null) {
            if (mSettings != "") {
                mVirtualizer.setProperties(new Virtualizer.Settings(mSettings));
            }
            mVirtualizer.setEnableStatusListener(mEffectListener);
            mVirtualizer.setControlStatusListener(mEffectListener);
            mVirtualizer.setParameterListener(mEffectListener);

            mReleaseButton.setChecked(true);
            mReleaseButton.setEnabled(true);

            mOnOffButton.setChecked(mVirtualizer.getEnabled());
            mOnOffButton.setEnabled(true);
        }
    }

    private void putEffect(int session) {
        mOnOffButton.setChecked(false);
        mOnOffButton.setEnabled(false);
        synchronized (sInstances) {
            if (mVirtualizer != null) {
                mSettings = mVirtualizer.getProperties().toString();
                mVirtualizer.release();
                Log.d(TAG,"Virtualizer released");
                mVirtualizer = null;
                sInstances.remove(session);
            }
        }
    }
}
