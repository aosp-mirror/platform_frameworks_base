/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.preference.VolumePreference.VolumeStore;
import android.provider.Settings;
import android.provider.Settings.System;
import android.util.Log;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

/**
 * Turns a {@link SeekBar} into a volume control.
 * @hide
 */
public class SeekBarVolumizer implements OnSeekBarChangeListener, Handler.Callback {

    public interface Callback {
        void onSampleStarting(SeekBarVolumizer sbv);
    }

    private Context mContext;
    private Handler mHandler;
    private final Callback mCallback;

    private AudioManager mAudioManager;
    private int mStreamType;
    private int mOriginalStreamVolume;
    private Ringtone mRingtone;

    private int mLastProgress = -1;
    private SeekBar mSeekBar;
    private int mVolumeBeforeMute = -1;

    private static final int MSG_SET_STREAM_VOLUME = 0;
    private static final int MSG_START_SAMPLE = 1;
    private static final int MSG_STOP_SAMPLE = 2;
    private static final int CHECK_RINGTONE_PLAYBACK_DELAY_MS = 1000;

    private ContentObserver mVolumeObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (mSeekBar != null && mAudioManager != null) {
                int volume = mAudioManager.getStreamVolume(mStreamType);
                mSeekBar.setProgress(volume);
            }
        }
    };

    public SeekBarVolumizer(Context context, SeekBar seekBar, int streamType, Uri defaultUri,
            Callback callback) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mStreamType = streamType;
        mSeekBar = seekBar;

        HandlerThread thread = new HandlerThread(VolumePreference.TAG + ".CallbackHandler");
        thread.start();
        mHandler = new Handler(thread.getLooper(), this);
        mCallback = callback;

        initSeekBar(seekBar, defaultUri);
    }

    private void initSeekBar(SeekBar seekBar, Uri defaultUri) {
        seekBar.setMax(mAudioManager.getStreamMaxVolume(mStreamType));
        mOriginalStreamVolume = mAudioManager.getStreamVolume(mStreamType);
        seekBar.setProgress(mOriginalStreamVolume);
        seekBar.setOnSeekBarChangeListener(this);

        mContext.getContentResolver().registerContentObserver(
                System.getUriFor(System.VOLUME_SETTINGS[mStreamType]),
                false, mVolumeObserver);

        if (defaultUri == null) {
            if (mStreamType == AudioManager.STREAM_RING) {
                defaultUri = Settings.System.DEFAULT_RINGTONE_URI;
            } else if (mStreamType == AudioManager.STREAM_NOTIFICATION) {
                defaultUri = Settings.System.DEFAULT_NOTIFICATION_URI;
            } else {
                defaultUri = Settings.System.DEFAULT_ALARM_ALERT_URI;
            }
        }

        mRingtone = RingtoneManager.getRingtone(mContext, defaultUri);

        if (mRingtone != null) {
            mRingtone.setStreamType(mStreamType);
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_SET_STREAM_VOLUME:
                mAudioManager.setStreamVolume(mStreamType, mLastProgress, 0);
                break;
            case MSG_START_SAMPLE:
                onStartSample();
                break;
            case MSG_STOP_SAMPLE:
                onStopSample();
                break;
            default:
                Log.e(VolumePreference.TAG, "invalid SeekBarVolumizer message: "+msg.what);
        }
        return true;
    }

    private void postStartSample() {
        mHandler.removeMessages(MSG_START_SAMPLE);
        mHandler.sendMessageDelayed(mHandler.obtainMessage(MSG_START_SAMPLE),
                isSamplePlaying() ? CHECK_RINGTONE_PLAYBACK_DELAY_MS : 0);
    }

    private void onStartSample() {
        if (!isSamplePlaying()) {
            if (mCallback != null) {
                mCallback.onSampleStarting(this);
            }
            if (mRingtone != null) {
                mRingtone.play();
            }
        }
    }

    void postStopSample() {
        // remove pending delayed start messages
        mHandler.removeMessages(MSG_START_SAMPLE);
        mHandler.removeMessages(MSG_STOP_SAMPLE);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_STOP_SAMPLE));
    }

    private void onStopSample() {
        if (mRingtone != null) {
            mRingtone.stop();
        }
    }

    public void stop() {
        postStopSample();
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

    void postSetVolume(int progress) {
        // Do the volume changing separately to give responsive UI
        mLastProgress = progress;
        mHandler.removeMessages(MSG_SET_STREAM_VOLUME);
        mHandler.sendMessage(mHandler.obtainMessage(MSG_SET_STREAM_VOLUME));
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
        postStartSample();
    }

    public boolean isSamplePlaying() {
        return mRingtone != null && mRingtone.isPlaying();
    }

    public void startSample() {
        postStartSample();
    }

    public void stopSample() {
        postStopSample();
    }

    public SeekBar getSeekBar() {
        return mSeekBar;
    }

    public void changeVolumeBy(int amount) {
        mSeekBar.incrementProgressBy(amount);
        postSetVolume(mSeekBar.getProgress());
        postStartSample();
        mVolumeBeforeMute = -1;
    }

    public void muteVolume() {
        if (mVolumeBeforeMute != -1) {
            mSeekBar.setProgress(mVolumeBeforeMute);
            postSetVolume(mVolumeBeforeMute);
            postStartSample();
            mVolumeBeforeMute = -1;
        } else {
            mVolumeBeforeMute = mSeekBar.getProgress();
            mSeekBar.setProgress(0);
            postStopSample();
            postSetVolume(0);
        }
    }

    public void onSaveInstanceState(VolumeStore volumeStore) {
        if (mLastProgress >= 0) {
            volumeStore.volume = mLastProgress;
            volumeStore.originalVolume = mOriginalStreamVolume;
        }
    }

    public void onRestoreInstanceState(VolumeStore volumeStore) {
        if (volumeStore.volume != -1) {
            mOriginalStreamVolume = volumeStore.originalVolume;
            mLastProgress = volumeStore.volume;
            postSetVolume(mLastProgress);
        }
    }
}