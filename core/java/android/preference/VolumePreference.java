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
public class VolumePreference extends SeekBarPreference implements 
        PreferenceManager.OnActivityStopListener {

    private static final String TAG = "VolumePreference";
    
    private int mStreamType;
    
    /** May be null if the dialog isn't visible. */
    private SeekBarVolumizer mSeekBarVolumizer;
    
    public VolumePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        TypedArray a = context.obtainStyledAttributes(attrs,
                com.android.internal.R.styleable.VolumePreference, 0, 0);
        mStreamType = a.getInt(android.R.styleable.VolumePreference_streamType, 0);
        a.recycle();        
    }
    
    public void setStreamType(int streamType) {
        mStreamType = streamType;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);
    
        final SeekBar seekBar = (SeekBar) view.findViewById(com.android.internal.R.id.seekbar);
        mSeekBarVolumizer = new SeekBarVolumizer(getContext(), seekBar, mStreamType);
        
        getPreferenceManager().registerOnActivityStopListener(this);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        super.onDialogClosed(positiveResult);
        
        if (!positiveResult && mSeekBarVolumizer != null) {
            mSeekBarVolumizer.revertVolume();
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
       getPreferenceManager().unregisterOnActivityStopListener(this);
       
       if (mSeekBarVolumizer != null) {
           mSeekBarVolumizer.stop();
           mSeekBarVolumizer = null;
       }
    }
   
    protected void onSampleStarting(SeekBarVolumizer volumizer) {
        if (mSeekBarVolumizer != null && volumizer != mSeekBarVolumizer) {
            mSeekBarVolumizer.stopSample();
        }
    }
    
    /**
     * Turns a {@link SeekBar} into a volume control.
     */
    public class SeekBarVolumizer implements OnSeekBarChangeListener, Runnable {

        private Context mContext;
        private Handler mHandler = new Handler();
    
        private AudioManager mAudioManager;
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
                    mSeekBar.setProgress(System.getInt(mContext.getContentResolver(),
                            System.VOLUME_SETTINGS[mStreamType], 0));
                }
            }
        };
    
        public SeekBarVolumizer(Context context, SeekBar seekBar, int streamType) {
            mContext = context;
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            mStreamType = streamType;
            mSeekBar = seekBar;
            
            initSeekBar(seekBar);
        }

        private void initSeekBar(SeekBar seekBar) {
            seekBar.setMax(mAudioManager.getStreamMaxVolume(mStreamType));
            mOriginalStreamVolume = mAudioManager.getStreamVolume(mStreamType);
            seekBar.setProgress(mOriginalStreamVolume);
            seekBar.setOnSeekBarChangeListener(this);
            
            mContext.getContentResolver().registerContentObserver(
                    System.getUriFor(System.VOLUME_SETTINGS[mStreamType]),
                    false, mVolumeObserver);
    
            mRingtone = RingtoneManager.getRingtone(mContext,
                    mStreamType == AudioManager.STREAM_NOTIFICATION
                            ? Settings.System.DEFAULT_NOTIFICATION_URI
                            : Settings.System.DEFAULT_RINGTONE_URI);
            mRingtone.setStreamType(mStreamType);
        }
        
        public void stop() {
            stopSample();
            mContext.getContentResolver().unregisterContentObserver(mVolumeObserver);
            mSeekBar.setOnSeekBarChangeListener(null);
        }
        
        public void revertVolume() {
            mAudioManager.setStreamVolume(mStreamType, mOriginalStreamVolume, 0);
        }
        
        public void onProgressChanged(SeekBar seekBar, int progress,
                boolean fromTouch) {
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
            mAudioManager.setStreamVolume(mStreamType, mLastProgress, 0);
        }
        
        private void sample() {
    
            // Only play a preview sample when controlling the ringer stream
            if (mStreamType != AudioManager.STREAM_RING
                    && mStreamType != AudioManager.STREAM_NOTIFICATION) {
                return;
            }
    
            onSampleStarting(this);
            mRingtone.play();
        }
    
        public void stopSample() {
            if (mRingtone != null) {
                mRingtone.stop();
            }
        }

        public SeekBar getSeekBar() {
            return mSeekBar;
        }
        
    }
}
