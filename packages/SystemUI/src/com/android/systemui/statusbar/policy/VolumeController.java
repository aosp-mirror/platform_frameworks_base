/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.ContentResolver;
import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Vibrator;
import android.media.AudioManager;
import android.provider.Settings;
import android.util.Slog;
import android.view.IWindowManager;
import android.widget.CompoundButton;

public class VolumeController implements ToggleSlider.Listener {
    private static final String TAG = "StatusBar.VolumeController";
    private static final int STREAM = AudioManager.STREAM_NOTIFICATION;

    private Context mContext;
    private ToggleSlider mControl;
    private AudioManager mAudioManager;

    private boolean mMute;
    private int mVolume;
    // Is there a vibrator
    private final boolean mHasVibrator;

    public VolumeController(Context context, ToggleSlider control) {
        mContext = context;
        mControl = control;

        Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mHasVibrator = vibrator == null ? false : vibrator.hasVibrator();

        mAudioManager = (AudioManager)context.getSystemService(Context.AUDIO_SERVICE);

        mMute = mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
        mVolume = mAudioManager.getStreamVolume(STREAM);
        control.setMax(mAudioManager.getStreamMaxVolume(STREAM));
        control.setValue(mVolume);
        control.setChecked(mMute);

        control.setOnChangedListener(this);
    }

    public void onChanged(ToggleSlider view, boolean tracking, boolean mute, int level) {
        if (!tracking) {
            if (mute) {
                mAudioManager.setRingerMode(
                        mHasVibrator ? AudioManager.RINGER_MODE_VIBRATE
                                     : AudioManager.RINGER_MODE_SILENT);
            } else {
                mAudioManager.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
                mAudioManager.setStreamVolume(STREAM, level, AudioManager.FLAG_PLAY_SOUND);
            }
        }
    }
}
