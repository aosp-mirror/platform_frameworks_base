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

import android.app.Dialog;
import android.content.Context;
import android.content.res.TypedArray;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Settings;
import android.provider.Settings.System;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.widget.SeekBar;
import android.widget.SeekBar.OnSeekBarChangeListener;

/**
 * @hide
 */
public class VolumePreference extends SeekBarDialogPreference implements
        PreferenceManager.OnActivityStopListener, View.OnKeyListener {

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

        // grab focus and key events so that pressing the volume buttons in the
        // dialog doesn't also show the normal volume adjust toast.
        view.setOnKeyListener(this);
        view.setFocusableInTouchMode(true);
        view.requestFocus();
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        // If key arrives immediately after the activity has been cleaned up.
        if (mSeekBarVolumizer == null) return true;
        boolean isdown = (event.getAction() == KeyEvent.ACTION_DOWN);
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                if (isdown) {
                    mSeekBarVolumizer.changeVolumeBy(-1);
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
                if (isdown) {
                    mSeekBarVolumizer.changeVolumeBy(1);
                }
                return true;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
                if (isdown) {
                    mSeekBarVolumizer.muteVolume();
                }
                return true;
            default:
                return false;
        }
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
        if (mSeekBarVolumizer != null) {
            mSeekBarVolumizer.stopSample();
        }
    }

    /**
     * Do clean up.  This can be called multiple times!
     */
    private void cleanup() {
       getPreferenceManager().unregisterOnActivityStopListener(this);

       if (mSeekBarVolumizer != null) {
           Dialog dialog = getDialog();
           if (dialog != null && dialog.isShowing()) {
               View view = dialog.getWindow().getDecorView()
                       .findViewById(com.android.internal.R.id.seekbar);
               if (view != null) view.setOnKeyListener(null);
               // Stopped while dialog was showing, revert changes
               mSeekBarVolumizer.revertVolume();
           }
           mSeekBarVolumizer.stop();
           mSeekBarVolumizer = null;
       }

    }

    protected void onSampleStarting(SeekBarVolumizer volumizer) {
        if (mSeekBarVolumizer != null && volumizer != mSeekBarVolumizer) {
            mSeekBarVolumizer.stopSample();
        }
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        final Parcelable superState = super.onSaveInstanceState();
        if (isPersistent()) {
            // No need to save instance state since it's persistent
            return superState;
        }

        final SavedState myState = new SavedState(superState);
        if (mSeekBarVolumizer != null) {
            mSeekBarVolumizer.onSaveInstanceState(myState.getVolumeStore());
        }
        return myState;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (state == null || !state.getClass().equals(SavedState.class)) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        if (mSeekBarVolumizer != null) {
            mSeekBarVolumizer.onRestoreInstanceState(myState.getVolumeStore());
        }
    }

    public static class VolumeStore {
        public int volume = -1;
        public int originalVolume = -1;
    }

    private static class SavedState extends BaseSavedState {
        VolumeStore mVolumeStore = new VolumeStore();

        public SavedState(Parcel source) {
            super(source);
            mVolumeStore.volume = source.readInt();
            mVolumeStore.originalVolume = source.readInt();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(mVolumeStore.volume);
            dest.writeInt(mVolumeStore.originalVolume);
        }

        VolumeStore getVolumeStore() {
            return mVolumeStore;
        }

        public SavedState(Parcelable superState) {
            super(superState);
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
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

        private int mLastProgress = -1;
        private SeekBar mSeekBar;
        private int mVolumeBeforeMute = -1;

        private ContentObserver mVolumeObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                if (mSeekBar != null && mAudioManager != null) {
                    int volume = mAudioManager.isStreamMute(mStreamType) ?
                            mAudioManager.getLastAudibleStreamVolume(mStreamType)
                            : mAudioManager.getStreamVolume(mStreamType);
                    mSeekBar.setProgress(volume);
                }
            }
        };

        public SeekBarVolumizer(Context context, SeekBar seekBar, int streamType) {
            this(context, seekBar, streamType, null);
        }

        public SeekBarVolumizer(Context context, SeekBar seekBar, int streamType, Uri defaultUri) {
            mContext = context;
            mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            mStreamType = streamType;
            mSeekBar = seekBar;

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

        void postSetVolume(int progress) {
            // Do the volume changing separately to give responsive UI
            mLastProgress = progress;
            mHandler.removeCallbacks(this);
            mHandler.post(this);
        }

        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        public void onStopTrackingTouch(SeekBar seekBar) {
            if (!isSamplePlaying()) {
                startSample();
            }
        }

        public void run() {
            mAudioManager.setStreamVolume(mStreamType, mLastProgress, 0);
        }

        public boolean isSamplePlaying() {
            return mRingtone != null && mRingtone.isPlaying();
        }

        public void startSample() {
            onSampleStarting(this);
            if (mRingtone != null) {
                mRingtone.play();
            }
        }

        public void stopSample() {
            if (mRingtone != null) {
                mRingtone.stop();
            }
        }

        public SeekBar getSeekBar() {
            return mSeekBar;
        }

        public void changeVolumeBy(int amount) {
            mSeekBar.incrementProgressBy(amount);
            if (!isSamplePlaying()) {
                startSample();
            }
            postSetVolume(mSeekBar.getProgress());
            mVolumeBeforeMute = -1;
        }

        public void muteVolume() {
            if (mVolumeBeforeMute != -1) {
                mSeekBar.setProgress(mVolumeBeforeMute);
                startSample();
                postSetVolume(mVolumeBeforeMute);
                mVolumeBeforeMute = -1;
            } else {
                mVolumeBeforeMute = mSeekBar.getProgress();
                mSeekBar.setProgress(0);
                stopSample();
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
}
