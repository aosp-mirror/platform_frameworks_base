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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private static final String TAG = "SeekBarVolumizer";

    public interface Callback {
        void onSampleStarting(SeekBarVolumizer sbv);
        void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch);
        void onMuted(boolean muted);
    }

    private final Context mContext;
    private final H mUiHandler = new H();
    private final Callback mCallback;
    private final Uri mDefaultUri;
    private final AudioManager mAudioManager;
    private final int mStreamType;
    private final int mMaxStreamVolume;
    private boolean mAffectedByRingerMode;
    private boolean mNotificationOrRing;
    private final Receiver mReceiver = new Receiver();

    private Handler mHandler;
    private Observer mVolumeObserver;
    private int mOriginalStreamVolume;
    private Ringtone mRingtone;
    private int mLastProgress = -1;
    private boolean mMuted;
    private SeekBar mSeekBar;
    private int mVolumeBeforeMute = -1;
    private int mRingerMode;

    private static final int MSG_SET_STREAM_VOLUME = 0;
    private static final int MSG_START_SAMPLE = 1;
    private static final int MSG_STOP_SAMPLE = 2;
    private static final int MSG_INIT_SAMPLE = 3;
    private static final int CHECK_RINGTONE_PLAYBACK_DELAY_MS = 1000;

    public SeekBarVolumizer(Context context, int streamType, Uri defaultUri, Callback callback) {
        mContext = context;
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mStreamType = streamType;
        mAffectedByRingerMode = mAudioManager.isStreamAffectedByRingerMode(mStreamType);
        mNotificationOrRing = isNotificationOrRing(mStreamType);
        if (mNotificationOrRing) {
            mRingerMode = mAudioManager.getRingerModeInternal();
        }
        mMaxStreamVolume = mAudioManager.getStreamMaxVolume(mStreamType);
        mCallback = callback;
        mOriginalStreamVolume = mAudioManager.getStreamVolume(mStreamType);
        mMuted = mAudioManager.isStreamMute(mStreamType);
        if (mCallback != null) {
            mCallback.onMuted(mMuted);
        }
        if (defaultUri == null) {
            if (mStreamType == AudioManager.STREAM_RING) {
                defaultUri = Settings.System.DEFAULT_RINGTONE_URI;
            } else if (mStreamType == AudioManager.STREAM_NOTIFICATION) {
                defaultUri = Settings.System.DEFAULT_NOTIFICATION_URI;
            } else {
                defaultUri = Settings.System.DEFAULT_ALARM_ALERT_URI;
            }
        }
        mDefaultUri = defaultUri;
    }

    private static boolean isNotificationOrRing(int stream) {
        return stream == AudioManager.STREAM_RING || stream == AudioManager.STREAM_NOTIFICATION;
    }

    public void setSeekBar(SeekBar seekBar) {
        if (mSeekBar != null) {
            mSeekBar.setOnSeekBarChangeListener(null);
        }
        mSeekBar = seekBar;
        mSeekBar.setOnSeekBarChangeListener(null);
        mSeekBar.setMax(mMaxStreamVolume);
        updateSeekBar();
        mSeekBar.setOnSeekBarChangeListener(this);
    }

    protected void updateSeekBar() {
        if (mNotificationOrRing && mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
            mSeekBar.setEnabled(true);
            mSeekBar.setProgress(0);
        } else if (mMuted) {
            mSeekBar.setEnabled(false);
            mSeekBar.setProgress(0);
        } else {
            mSeekBar.setEnabled(true);
            mSeekBar.setProgress(mLastProgress > -1 ? mLastProgress : mOriginalStreamVolume);
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_SET_STREAM_VOLUME:
                mAudioManager.setStreamVolume(mStreamType, mLastProgress,
                        AudioManager.FLAG_SHOW_UI_WARNINGS);
                break;
            case MSG_START_SAMPLE:
                onStartSample();
                break;
            case MSG_STOP_SAMPLE:
                onStopSample();
                break;
            case MSG_INIT_SAMPLE:
                onInitSample();
                break;
            default:
                Log.e(TAG, "invalid SeekBarVolumizer message: "+msg.what);
        }
        return true;
    }

    private void onInitSample() {
        mRingtone = RingtoneManager.getRingtone(mContext, mDefaultUri);
        if (mRingtone != null) {
            mRingtone.setStreamType(mStreamType);
        }
    }

    private void postStartSample() {
        if (mHandler == null) return;
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
                try {
                    mRingtone.play();
                } catch (Throwable e) {
                    Log.w(TAG, "Error playing ringtone, stream " + mStreamType, e);
                }
            }
        }
    }

    private void postStopSample() {
        if (mHandler == null) return;
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
        if (mHandler == null) return;  // already stopped
        postStopSample();
        mContext.getContentResolver().unregisterContentObserver(mVolumeObserver);
        mReceiver.setListening(false);
        mSeekBar.setOnSeekBarChangeListener(null);
        mHandler.getLooper().quitSafely();
        mHandler = null;
        mVolumeObserver = null;
    }

    public void start() {
        if (mHandler != null) return;  // already started
        HandlerThread thread = new HandlerThread(TAG + ".CallbackHandler");
        thread.start();
        mHandler = new Handler(thread.getLooper(), this);
        mHandler.sendEmptyMessage(MSG_INIT_SAMPLE);
        mVolumeObserver = new Observer(mHandler);
        mContext.getContentResolver().registerContentObserver(
                System.getUriFor(System.VOLUME_SETTINGS[mStreamType]),
                false, mVolumeObserver);
        mReceiver.setListening(true);
    }

    public void revertVolume() {
        mAudioManager.setStreamVolume(mStreamType, mOriginalStreamVolume, 0);
    }

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {
        if (fromTouch) {
            postSetVolume(progress);
        }
        if (mCallback != null) {
            mCallback.onProgressChanged(seekBar, progress, fromTouch);
        }
    }

    private void postSetVolume(int progress) {
        if (mHandler == null) return;
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

    private final class H extends Handler {
        private static final int UPDATE_SLIDER = 1;

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == UPDATE_SLIDER) {
                if (mSeekBar != null) {
                    mLastProgress = msg.arg1;
                    final boolean muted = msg.arg2 != 0;
                    if (muted != mMuted) {
                        mMuted = muted;
                        if (mCallback != null) {
                            mCallback.onMuted(mMuted);
                        }
                    }
                    updateSeekBar();
                }
            }
        }

        public void postUpdateSlider(int volume, boolean mute) {
            obtainMessage(UPDATE_SLIDER, volume, mute ? 1 : 0).sendToTarget();
        }
    }

    private void updateSlider() {
        if (mSeekBar != null && mAudioManager != null) {
            final int volume = mAudioManager.getStreamVolume(mStreamType);
            final boolean mute = mAudioManager.isStreamMute(mStreamType);
            mUiHandler.postUpdateSlider(volume, mute);
        }
    }

    private final class Observer extends ContentObserver {
        public Observer(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            updateSlider();
        }
    }

    private final class Receiver extends BroadcastReceiver {
        private boolean mListening;

        public void setListening(boolean listening) {
            if (mListening == listening) return;
            mListening = listening;
            if (listening) {
                final IntentFilter filter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
                filter.addAction(AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION);
                mContext.registerReceiver(this, filter);
            } else {
                mContext.unregisterReceiver(this);
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (AudioManager.VOLUME_CHANGED_ACTION.equals(action)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                int streamValue = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                final boolean streamMatch = mNotificationOrRing ? isNotificationOrRing(streamType)
                        : (streamType == mStreamType);
                if (mSeekBar != null && streamMatch && streamValue != -1) {
                    final boolean muted = mAudioManager.isStreamMute(mStreamType);
                    mUiHandler.postUpdateSlider(streamValue, muted);
                }
            } else if (AudioManager.INTERNAL_RINGER_MODE_CHANGED_ACTION.equals(action)) {
                if (mNotificationOrRing) {
                    mRingerMode = mAudioManager.getRingerModeInternal();
                }
                if (mAffectedByRingerMode) {
                    updateSlider();
                }
            }
        }
    }
}
