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
import android.widget.SeekBar;
import android.widget.ToggleButton;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ImageButton;
import android.widget.ImageView;
import java.util.HashMap;
import java.util.Map;

import android.media.audiofx.EnvironmentalReverb;
import android.media.audiofx.AudioEffect;
import android.media.AudioManager;

public class EnvReverbTest extends Activity implements OnCheckedChangeListener, SeekBar.OnSeekBarChangeListener {

    private final static String TAG = "EnvReverbTest";

    private static int NUM_PARAMS = 10;

    private EffectParameter[] mParameters = new EffectParameter[NUM_PARAMS];
    private EnvironmentalReverb mReverb;
    ToggleButton mOnOffButton;
    ToggleButton mReleaseButton;
    ToggleButton mAttachButton;
    private static HashMap<Integer, EnvironmentalReverb> sInstances = new HashMap<Integer, EnvironmentalReverb>(10);
    static SimplePlayer sPlayerController = null;
    SeekBar mSendLevelSeekBar;
    TextView mSendLevelDisplay;
    static float sSendLevel = linToExp(50,100);
    static boolean sAttached = false;
    String mSettings = "";

    public EnvReverbTest() {
        Log.d(TAG, "contructor");
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        Log.d(TAG, "onCreate");
        SeekBar seekBar;
        TextView textView;
        ToggleButton button;
        setContentView(R.layout.envreverbtest);

        ImageView playPause = findViewById(R.id.playPause1);
        ImageView stop = findViewById(R.id.stop1);
        textView = findViewById(R.id.sessionText);
        if (sPlayerController == null) {
            sPlayerController = new SimplePlayer(this, R.id.playPause1, playPause,
                    R.id.stop1, stop, textView,
                    R.raw.mp3_sample, AudioManager.STREAM_MUSIC, 0);
        } else {
            sPlayerController.set(this, R.id.playPause1, playPause,
                    R.id.stop1, stop, textView,
                    AudioManager.STREAM_MUSIC, 0);
        }

        // send level
        mSendLevelSeekBar = (SeekBar)findViewById(R.id.sendLevelSeekBar);
        mSendLevelDisplay = (TextView)findViewById(R.id.sendLevelValue);
        mSendLevelSeekBar.setMax(100);
        mSendLevelSeekBar.setOnSeekBarChangeListener(this);
        mSendLevelSeekBar.setProgress(expToLin(sSendLevel,100));
        sPlayerController.setAuxEffectSendLevel(sSendLevel);

        mOnOffButton = (ToggleButton)findViewById(R.id.rvbOnOff);
        mReleaseButton = (ToggleButton)findViewById(R.id.rvbReleaseButton);
        mAttachButton = (ToggleButton)findViewById(R.id.attachButton);

        getEffect(0);

        if (mReverb != null) {
            mOnOffButton.setOnCheckedChangeListener(this);
            mReleaseButton.setOnCheckedChangeListener(this);
            mAttachButton.setOnCheckedChangeListener(this);

//            button = (ToggleButton)findViewById(R.id.rvbBypass);
//            button.setChecked(false);
//            button.setOnCheckedChangeListener(this);

            // Room level
            seekBar = (SeekBar)findViewById(R.id.rvbParam1SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam1Value);
            mParameters[0] = new RoomLevelParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[0]);

            // Room HF level
            seekBar = (SeekBar)findViewById(R.id.rvbParam2SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam2Value);
            mParameters[1] = new RoomHFLevelParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[1]);

            // Decay time
            seekBar = (SeekBar)findViewById(R.id.rvbParam3SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam3Value);
            mParameters[2] = new DecayTimeParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[2]);

            // Decay HF ratio
            seekBar = (SeekBar)findViewById(R.id.rvbParam4SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam4Value);
            mParameters[3] = new DecayHFRatioParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[3]);

            // Reflections level
            seekBar = (SeekBar)findViewById(R.id.rvbParam5SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam5Value);
            mParameters[4] = new ReflectionsLevelParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[4]);

            // Reflections delay
            seekBar = (SeekBar)findViewById(R.id.rvbParam6SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam6Value);
            mParameters[5] = new ReflectionsDelayParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[5]);

            // Reverb level
            seekBar = (SeekBar)findViewById(R.id.rvbParam7SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam7Value);
            mParameters[6] = new ReverbLevelParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[6]);

            // Reverb delay
            seekBar = (SeekBar)findViewById(R.id.rvbParam8SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam8Value);
            mParameters[7] = new ReverbDelayParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[7]);

            // Diffusion
            seekBar = (SeekBar)findViewById(R.id.rvbParam9SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam9Value);
            mParameters[8] = new DiffusionParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[8]);

            // Density
            seekBar = (SeekBar)findViewById(R.id.rvbParam10SeekBar);
            textView = (TextView)findViewById(R.id.rvbParam10Value);
            mParameters[9] = new DensityParam(mReverb, seekBar, textView);
            seekBar.setOnSeekBarChangeListener(mParameters[9]);
        }
    }
    @Override
    public void onResume() {
        super.onResume();
        Log.d(TAG, "onResume");
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    // OnCheckedChangeListener
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView.getId() == R.id.rvbOnOff) {
            if (mReverb != null) {
                mReverb.setEnabled(isChecked);
                Log.d(TAG,"onCheckedChanged: rvbOnOff");
                for (int i = 0 ; i < mParameters.length; i++) {
                    mParameters[i].updateDisplay();
                }
            }
        }
        if (buttonView.getId() == R.id.rvbReleaseButton) {
            if (isChecked) {
                if (mReverb == null) {
                    getEffect(0);
                    for (int i = 0 ; i < mParameters.length; i++) {
                        mParameters[i].setEffect(mReverb);
                        mParameters[i].setEnabled(true);
                    }
                }
            } else {
                if (mReverb != null) {
                    for (int i = 0 ; i < mParameters.length; i++) {
                        mParameters[i].setEnabled(false);
                    }
                    putEffect(0);
                }
            }
        }
//        if (buttonView.getId() == R.id.rvbBypass) {
//            // REVERB_PARAM_BYPASS parametervalue is 11 in EffectEnvironmentalReverApi.h
//            if (mReverb != null) {
//                if (isChecked) {
//                    mReverb.setParameter((int)11, (int)1);
//                } else {
//                    mReverb.setParameter((int)11, (int)0);
//                }
//            }
//        }
        if (buttonView.getId() == R.id.attachButton) {
            if (mReverb != null) {
                if (isChecked) {
                    sPlayerController.attachAuxEffect(mReverb.getId());
                    sAttached = true;
                } else {
                    sPlayerController.attachAuxEffect(0);
                    sAttached = false;
                }
            }
        }
    }

    // SeekBar.OnSeekBarChangeListener
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {

        if (seekBar != mSendLevelSeekBar) {
            Log.e(TAG, "onProgressChanged called with wrong seekBar");
            return;
        }

        sSendLevel = linToExp(progress,100);
        if (fromTouch) {
            sPlayerController.setAuxEffectSendLevel(sSendLevel);
        }
        String text = Float.toString(sSendLevel);
        mSendLevelDisplay.setText(text);
        if (!fromTouch) {
            seekBar.setProgress(progress);
        }
    }

    static float linToExp(int lin, int range) {
        if (lin == 0) return 0;
        return (float)Math.pow((double)10,(double)72*(lin-range)/(20*range));
    }

    static int expToLin(float exp, int range) {
        if (exp == 0) return 0;
        return (int)(20*range*Math.log10((double)exp)/72 + range);
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
    }

    private class EnvReverbParam extends EffectParameter {
        private EnvironmentalReverb mReverb;

        public EnvReverbParam(EnvironmentalReverb reverb, int min, int max, SeekBar seekBar, TextView textView, String unit) {
            super (min, max, seekBar, textView, unit);
            mReverb = reverb;
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
        public void setEffect(Object reverb) {
            mReverb = (EnvironmentalReverb)reverb;
        }
    }

    private class RoomLevelParam extends EnvReverbParam {

        public RoomLevelParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, -9600, 0, seekBar, textView, "mB");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setRoomLevel(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return new Integer(mReverb.getRoomLevel());
            }
            return new Integer(0);
        }
    }

    private class RoomHFLevelParam extends EnvReverbParam {

        public RoomHFLevelParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, -4000, 0, seekBar, textView, "mB");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setRoomHFLevel(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return new Integer(mReverb.getRoomHFLevel());
            }
            return new Integer(0);
        }
    }

    private class DecayTimeParam extends EnvReverbParam {

        public DecayTimeParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, 200, 4000, seekBar, textView, "ms");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setDecayTime(value.intValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return mReverb.getDecayTime();
            }
            return new Integer(0);
        }
    }

    private class DecayHFRatioParam extends EnvReverbParam {

        public DecayHFRatioParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, 100, 1000, seekBar, textView, "permilles");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setDecayHFRatio(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return new Integer(mReverb.getDecayHFRatio());
            }
            return new Integer(0);
        }
    }

    private class ReflectionsLevelParam extends EnvReverbParam {

        public ReflectionsLevelParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, -9600, 0, seekBar, textView, "mB");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setReflectionsLevel(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return new Integer(mReverb.getReflectionsLevel());
            }
            return new Integer(0);
        }
    }

    private class ReflectionsDelayParam extends EnvReverbParam {

        public ReflectionsDelayParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, 0, 65, seekBar, textView, "ms");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setReflectionsDelay(value.intValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return mReverb.getReflectionsDelay();
            }
            return new Integer(0);
        }
    }

    private class ReverbLevelParam extends EnvReverbParam {

        public ReverbLevelParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, -9600, 2000, seekBar, textView, "mB");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setReverbLevel(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return new Integer(mReverb.getReverbLevel());
            }
            return new Integer(0);
        }
    }

    private class ReverbDelayParam extends EnvReverbParam {

        public ReverbDelayParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, 0, 65, seekBar, textView, "ms");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setReverbDelay(value.intValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return mReverb.getReverbDelay();
            }
            return new Integer(0);
        }
    }

    private class DiffusionParam extends EnvReverbParam {

        public DiffusionParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, 0, 1000, seekBar, textView, "permilles");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setDiffusion(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return new Integer(mReverb.getDiffusion());
            }
            return new Integer(0);
        }
    }

    private class DensityParam extends EnvReverbParam {

        public DensityParam(EnvironmentalReverb reverb, SeekBar seekBar, TextView textView) {
            super (reverb, 0, 1000, seekBar, textView, "permilles");
        }

        @Override
        public void setParameter(Integer value) {
            if (mReverb != null) {
                mReverb.setDensity(value.shortValue());
            }
        }

        @Override
        public Integer getParameter() {
            if (mReverb != null) {
                return new Integer(mReverb.getDensity());
            }
            return new Integer(0);
        }
    }

    private void getEffect(int session) {
        synchronized (sInstances) {
            if (sInstances.containsKey(session)) {
                mReverb = sInstances.get(session);
            } else {
                try{
                    mReverb = new EnvironmentalReverb(0, session);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG,"Reverb effect not supported");
                } catch (UnsupportedOperationException e) {
                    Log.e(TAG,"Reverb library not loaded");
                } catch (RuntimeException e) {
                    Log.e(TAG,"Reverb effect not found");
                }
                Log.d(TAG, "new reverb: "+mReverb);
                sInstances.put(session, mReverb);
            }
        }
        mReleaseButton.setEnabled(false);
        mOnOffButton.setEnabled(false);
        mAttachButton.setEnabled(false);
        if (mReverb != null) {
            if (mSettings != "") {
                mReverb.setProperties(new EnvironmentalReverb.Settings(mSettings));
            }
            mReleaseButton.setChecked(true);
            mReleaseButton.setEnabled(true);
            mOnOffButton.setChecked(mReverb.getEnabled());
            mOnOffButton.setEnabled(true);
            mAttachButton.setChecked(false);
            mAttachButton.setEnabled(true);
            if (sAttached) {
                mAttachButton.setChecked(true);
                sPlayerController.attachAuxEffect(mReverb.getId());
            }
        }
    }

    private void putEffect(int session) {
        mOnOffButton.setChecked(false);
        mOnOffButton.setEnabled(false);
        mAttachButton.setChecked(false);
        mAttachButton.setEnabled(false);
        synchronized (sInstances) {
            if (mReverb != null) {
                mSettings = mReverb.getProperties().toString();
                mReverb.release();
                Log.d(TAG,"Reverb released, settings: "+mSettings);
                mReverb = null;
                sInstances.remove(session);
            }
        }
    }
}
