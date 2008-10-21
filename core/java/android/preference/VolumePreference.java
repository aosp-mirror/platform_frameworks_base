/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.preference;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.AudioManager;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.provider.Settings.System;
import android.util.AttributeSet;
import android.view.View;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

/**
 * @hide
 */
public class VolumePreference extends SeekBarPreference implements OnSeekBarChangeListener,
        Runnable, PreferenceManager.OnActivityStopListener {

    private static final String TAG = "VolumePreference";
    
    private ContentResolver mContentResolver;
    private Handler mHandler = new Handler();

    private AudioManager mVolume;
    private int mStreamType;
    private int mOriginalStreamVolume; 
    private Ringtone mRingtone;

    private int mLastProgress;
    private SeekBar mSeekBar;
    
    private ContentObserver mVolumeObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);

            if (mSeekBar != null) {
                mSeekBar.setProgress(System.getInt(mContentResolver,
                        System.VOLUME_SETTINGS[mStreamType], 0));
            }
        }
    };
    
    public VolumePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.VolumePreference, 0, 0);
        mStreamType = a.getInt(android.R.styleable.VolumePreference_streamType, 0);
        a.recycle();        
    
        mContentResolver = context.getContentResolver();
        
        mVolume = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }
    
    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
    
        final SeekBar seekBar = mSeekBar = (SeekBar) view.findViewById(com.android.internal.R.id.seekbar);
        seekBar.setMax(mVolume.getStreamMaxVolume(mStreamType));
        mOriginalStreamVolume = mVolume.getStreamVolume(mStreamType);
        seekBar.setProgress(mOriginalStreamVolume);
        seekBar.setOnSeekBarChangeListener(this);
        
        mContentResolver.registerContentObserver(System.getUriFor(System.VOLUME_SETTINGS[mStreamType]), false, mVolumeObserver);

        getPreferenceManager().registerOnActivityStopListener(this);
        mRingtone = RingtoneManager.getRingtone(getContext(), Settings.System.DEFAULT_RINGTONE_URI);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        
        if (!positiveResult) {
            mVolume.setStreamVolume(mStreamType, mOriginalStreamVolume, 0);
        }
        
        cleanup();
    }
    

    public void onActivityStop() {
        cleanup();
    }

    /**
     * Do clean up.  This can be called multiple times!
     */
    private void cleanup() {
        stopSample();
        if (mVolumeObserver != null) {
            mContentResolver.unregisterContentObserver(mVolumeObserver);
        }
        getPreferenceManager().unregisterOnActivityStopListener(this);
        mSeekBar = null;
    }

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {
        if (!fromTouch) {
            return;
        }

        postSetVolume(progress);
    }

    private void postSetVolume(int progress) {
        // Do the volume changing separately to give responsive UI
        mLastProgress = progress;
        mHandler.removeCallbacks(this);
        mHandler.post(this);
    }
    
    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
        if (mRingtone != null && !mRingtone.isPlaying()) {
            sample();
        }
    }

    public void run() {
        mVolume.setStreamVolume(mStreamType, mLastProgress, 0);
    }
    
    private void sample() {
        mRingtone.play();
    }

    private void stopSample() {
        if (mRingtone != null) {
            mRingtone.stop();
        }
    }
    
}
