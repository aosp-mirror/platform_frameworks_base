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
import android.widget.ListView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.media.audiofx.AudioEffect;

import java.util.UUID;

public class EffectsTest extends Activity {

    private final static String TAG = "EffectsTest";


    public EffectsTest() {
        Log.d(TAG, "contructor");
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.effectstest);

        Button button = (Button) findViewById(R.id.env_reverb_actvity);
        button.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(EffectsTest.this, EnvReverbTest.class));
            }
        });

        button = (Button) findViewById(R.id.preset_reverb_actvity);
        button.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(EffectsTest.this, PresetReverbTest.class));
            }
        });

        button = (Button) findViewById(R.id.equalizer_actvity);
        button.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(EffectsTest.this, EqualizerTest.class));
            }
        });

        button = (Button) findViewById(R.id.virtualizer_actvity);
        button.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(EffectsTest.this, VirtualizerTest.class));
            }
        });

        button = (Button) findViewById(R.id.bassboost_actvity);
        button.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(EffectsTest.this, BassBoostTest.class));
            }
        });

        button = (Button) findViewById(R.id.visualizer_actvity);
        button.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                startActivity(new Intent(EffectsTest.this, VisualizerTest.class));
            }
        });

        AudioEffect.Descriptor[] descriptors = AudioEffect.queryEffects();

        ListView list = (ListView) findViewById(R.id.effect_list);
        list.setAdapter(new EffectListAdapter(this, descriptors));

    }

    private class EffectListAdapter extends BaseAdapter {

        private Context mContext;

        AudioEffect.Descriptor[] mDescriptors;

        public EffectListAdapter(Context context, AudioEffect.Descriptor[] descriptors) {
            Log.d(TAG, "EffectListAdapter contructor");
            mContext = context;
            mDescriptors = descriptors;
            for (int i = 0; i < mDescriptors.length; i++) {
                Log.d(TAG, "Effect: "+i+" name: "+ mDescriptors[i].name);
            }
        }

         public int getCount() {
            Log.d(TAG, "EffectListAdapter getCount(): "+mDescriptors.length);
            return mDescriptors.length;
        }

        public Object getItem(int position) {
            Log.d(TAG, "EffectListAdapter getItem() at: "+position+" name: "
                    +mDescriptors[position].name);
            return mDescriptors[position];
        }

        public long getItemId(int position) {
            return position;
        }

        public View getView(int position, View convertView, ViewGroup parent) {
            EffectView ev;
            if (convertView == null) {
                Log.d(TAG, "getView() new EffectView position: " + position);
                ev = new EffectView(mContext, mDescriptors);
            } else {
                Log.d(TAG, "getView() convertView position: " + position);
                ev = new EffectView(mContext, mDescriptors);
                //ev = (EffectView) convertView;
            }
            ev.set(position);
            return ev;
        }
    }

    private class EffectView extends LinearLayout {
        private Context mContext;
        AudioEffect.Descriptor[] mDescriptors;

        public EffectView(Context context, AudioEffect.Descriptor[] descriptors) {
            super(context);

            mContext = context;
            mDescriptors = descriptors;
            this.setOrientation(VERTICAL);
        }

        public String effectUuidToString(UUID effectType) {
            if (effectType.equals(AudioEffect.EFFECT_TYPE_VIRTUALIZER)) {
                return "Virtualizer";
            } else if (effectType.equals(AudioEffect.EFFECT_TYPE_ENV_REVERB)){
                return "Reverb";
            } else if (effectType.equals(AudioEffect.EFFECT_TYPE_PRESET_REVERB)){
                return "Preset Reverb";
            } else if (effectType.equals(AudioEffect.EFFECT_TYPE_EQUALIZER)){
                return "Equalizer";
            } else if (effectType.equals(AudioEffect.EFFECT_TYPE_BASS_BOOST)){
                return "Bass Boost";
            } else if (effectType.equals(AudioEffect.EFFECT_TYPE_AGC)){
                return "Automatic Gain Control";
            } else if (effectType.equals(AudioEffect.EFFECT_TYPE_AEC)){
                return "Acoustic Echo Canceler";
            } else if (effectType.equals(AudioEffect.EFFECT_TYPE_NS)){
                return "Noise Suppressor";
            }

            return effectType.toString();
        }

        public void set(int position) {
            TextView tv = new TextView(mContext);
            tv.setText("Effect "+ position);
            addView(tv, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            tv = new TextView(mContext);
            tv.setText(" type: "+ effectUuidToString(mDescriptors[position].type));
            addView(tv, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            tv = new TextView(mContext);
            tv.setText(" uuid: "+ mDescriptors[position].uuid.toString());
            addView(tv, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            tv = new TextView(mContext);
            tv.setText(" name: "+ mDescriptors[position].name);
            addView(tv, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            tv = new TextView(mContext);
            tv.setText(" vendor: "+ mDescriptors[position].implementor);
            addView(tv, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            tv = new TextView(mContext);
            tv.setText(" mode: "+ mDescriptors[position].connectMode);
            addView(tv, new LinearLayout.LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        }
    }

}
