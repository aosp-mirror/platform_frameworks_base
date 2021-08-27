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
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.widget.SeekBar;

import com.android.internal.R;

/**
 * @hide
 *
 * @deprecated Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 *      <a href="{@docRoot}reference/androidx/preference/package-summary.html">
 *      Preference Library</a> for consistent behavior across all devices. For more information on
 *      using the AndroidX Preference Library see
 *      <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>.
 */
@Deprecated
public class VolumePreference extends SeekBarDialogPreference implements
        PreferenceManager.OnActivityStopListener, View.OnKeyListener, SeekBarVolumizer.Callback {
    @UnsupportedAppUsage
    private int mStreamType;

    /** May be null if the dialog isn't visible. */
    private SeekBarVolumizer mSeekBarVolumizer;

    public VolumePreference(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.VolumePreference, defStyleAttr, defStyleRes);
        mStreamType = a.getInt(android.R.styleable.VolumePreference_streamType, 0);
        a.recycle();
    }

    public VolumePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    @UnsupportedAppUsage
    public VolumePreference(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.seekBarDialogPreferenceStyle);
    }

    public VolumePreference(Context context) {
        this(context, null);
    }

    public void setStreamType(int streamType) {
        mStreamType = streamType;
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);

        final SeekBar seekBar = (SeekBar) view.findViewById(R.id.seekbar);
        mSeekBarVolumizer = new SeekBarVolumizer(getContext(), mStreamType, null, this);
        mSeekBarVolumizer.start();
        mSeekBarVolumizer.setSeekBar(seekBar);

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
           final Dialog dialog = getDialog();
           if (dialog != null && dialog.isShowing()) {
               final View view = dialog.getWindow().getDecorView().findViewById(R.id.seekbar);
               if (view != null) {
                   view.setOnKeyListener(null);
               }

               // Stopped while dialog was showing, revert changes
               mSeekBarVolumizer.revertVolume();
           }

           mSeekBarVolumizer.stop();
           mSeekBarVolumizer = null;
       }

    }

    @Override
    public void onSampleStarting(SeekBarVolumizer volumizer) {
        if (mSeekBarVolumizer != null && volumizer != mSeekBarVolumizer) {
            mSeekBarVolumizer.stopSample();
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromTouch) {
        // noop
    }

    @Override
    public void onMuted(boolean muted, boolean zenMuted) {
        // noop
    }

    @Override
    public void onStartTrackingTouch(SeekBarVolumizer sbv) {
        // noop
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
        @UnsupportedAppUsage
        public int volume = -1;
        @UnsupportedAppUsage
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

        public static final @android.annotation.NonNull Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
}
