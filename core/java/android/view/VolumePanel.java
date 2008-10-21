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

package android.view;

import android.media.ToneGenerator;
import android.media.AudioManager;
import android.media.AudioService;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.Vibrator;
import android.util.Config;
import android.util.Log;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Handle the volume up and down keys.
 *
 * This code really should be moved elsewhere.
 * 
 * @hide
 */
public class VolumePanel extends Handler
{
    private static final String TAG = "VolumePanel";
    private static boolean LOGD = false || Config.LOGD;

    /**
     * The delay before playing a sound. This small period exists so the user
     * can press another key (non-volume keys, too) to have it NOT be audible.
     * <p>
     * PhoneWindow will implement this part.
     */
    public static final int PLAY_SOUND_DELAY = 300;
    
    /**
     * The delay before vibrating. This small period exists so if the user is
     * moving to silent mode, it will not emit a short vibrate (it normally
     * would since vibrate is between normal mode and silent mode using hardware
     * keys).
     */
    public static final int VIBRATE_DELAY = 300;

    private static final int VIBRATE_DURATION = 300;
    private static final int BEEP_DURATION = 150; 
    private static final int MAX_VOLUME = 100;
    private static final int FREE_DELAY = 10000;
    
    private static final int MSG_VOLUME_CHANGED = 0;
    private static final int MSG_FREE_RESOURCES = 1;
    private static final int MSG_PLAY_SOUND = 2;
    private static final int MSG_STOP_SOUNDS = 3;
    private static final int MSG_VIBRATE = 4;
    
    private final String RINGTONE_VOLUME_TEXT;
    private final String MUSIC_VOLUME_TEXT;
    private final String INCALL_VOLUME_TEXT;
    private final String ALARM_VOLUME_TEXT;
    private final String UNKNOWN_VOLUME_TEXT;
    
    protected Context mContext;
    protected AudioService mAudioService;
    
    private Toast mToast;
    private View mView;
    private TextView mMessage;
    private ImageView mOtherStreamIcon;
    private ImageView mRingerStreamIcon;
    private ProgressBar mLevel;

    // Synchronize when accessing this
    private ToneGenerator mToneGenerators[];
    private Vibrator mVibrator;
    
    public VolumePanel(Context context, AudioService volumeService) {
        mContext = context;
        mAudioService = volumeService;
        mToast = new Toast(context);
    
        RINGTONE_VOLUME_TEXT = context.getString(com.android.internal.R.string.volume_ringtone);
        MUSIC_VOLUME_TEXT = context.getString(com.android.internal.R.string.volume_music);
        INCALL_VOLUME_TEXT = context.getString(com.android.internal.R.string.volume_call);
        ALARM_VOLUME_TEXT = context.getString(com.android.internal.R.string.volume_alarm);
        UNKNOWN_VOLUME_TEXT = context.getString(com.android.internal.R.string.volume_unknown);
        
        LayoutInflater inflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View view = mView = inflater.inflate(com.android.internal.R.layout.volume_adjust, null);
        mMessage = (TextView) view.findViewById(com.android.internal.R.id.message);
        mOtherStreamIcon = (ImageView) view.findViewById(com.android.internal.R.id.other_stream_icon);
        mRingerStreamIcon = (ImageView) view.findViewById(com.android.internal.R.id.ringer_stream_icon);
        mLevel = (ProgressBar) view.findViewById(com.android.internal.R.id.level);

        mToneGenerators = new ToneGenerator[AudioManager.NUM_STREAMS];
        mVibrator = new Vibrator();
    }
    
    public void postVolumeChanged(int streamType, int flags) {
        if (hasMessages(MSG_VOLUME_CHANGED)) return;
        removeMessages(MSG_FREE_RESOURCES);
        obtainMessage(MSG_VOLUME_CHANGED, streamType, flags).sendToTarget();
    }
    
    /**
     * Override this if you have other work to do when the volume changes (for
     * example, vibrating, playing a sound, etc.). Make sure to call through to
     * the superclass implementation.
     */
    protected void onVolumeChanged(int streamType, int flags) {

        if (LOGD) Log.d(TAG, "onVolumeChanged(streamType: " + streamType + ", flags: " + flags + ")");
        
        if ((flags & AudioManager.FLAG_SHOW_UI) != 0) {
            onShowVolumeChanged(streamType, flags);
        }
        
        if ((flags & AudioManager.FLAG_PLAY_SOUND) != 0) {
            removeMessages(MSG_PLAY_SOUND);
            sendMessageDelayed(obtainMessage(MSG_PLAY_SOUND, streamType, flags), PLAY_SOUND_DELAY);
        }
        
        if ((flags & AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE) != 0) {
            removeMessages(MSG_PLAY_SOUND);
            removeMessages(MSG_VIBRATE);
            onStopSounds();
        }
        
        removeMessages(MSG_FREE_RESOURCES);
        sendMessageDelayed(obtainMessage(MSG_FREE_RESOURCES), FREE_DELAY); 
    }

    protected void onShowVolumeChanged(int streamType, int flags) {
        int index = mAudioService.getStreamVolume(streamType);
        String message = UNKNOWN_VOLUME_TEXT;
        
        if (LOGD) {
            Log.d(TAG, "onShowVolumeChanged(streamType: " + streamType
                    + ", flags: " + flags + "), index: " + index);
        }

        switch (streamType) {
            
            case AudioManager.STREAM_RING: {
                message = RINGTONE_VOLUME_TEXT;
                setRingerIcon(index);
                break;
            }
                
            case AudioManager.STREAM_MUSIC: {
                message = MUSIC_VOLUME_TEXT;
                setOtherIcon(index);
                break;
            }
                
            case AudioManager.STREAM_VOICE_CALL: {
                message = INCALL_VOLUME_TEXT;
                /*
                 * For in-call voice call volume, there is no inaudible volume
                 * level, so never show the mute icon
                 */
                setOtherIcon(index == 0 ? 1 : index);
                break;
            }

            case AudioManager.STREAM_ALARM: {
                message = ALARM_VOLUME_TEXT;
                setOtherIcon(index);
                break;
            }
        }

        if (!mMessage.getText().equals(message)) {
            mMessage.setText(message);
        }

        int max = mAudioService.getStreamMaxVolume(streamType);
        if (max != mLevel.getMax()) {
            mLevel.setMax(max);
        }
        mLevel.setProgress(index);
        
        mToast.setView(mView);
        mToast.setDuration(Toast.LENGTH_SHORT);
        mToast.setGravity(Gravity.TOP, 0, 0);
        mToast.show();
        
        // Do a little vibrate if applicable (only when going into vibrate mode)
        if ((flags & AudioManager.FLAG_VIBRATE) != 0 && 
                mAudioService.isStreamAffectedByRingerMode(streamType) &&
                mAudioService.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE &&
                mAudioService.shouldVibrate(AudioManager.VIBRATE_TYPE_RINGER)) {
            sendMessageDelayed(obtainMessage(MSG_VIBRATE), VIBRATE_DELAY);
        }
        
    }

    protected void onPlaySound(int streamType, int flags) {

        if (hasMessages(MSG_STOP_SOUNDS)) {
            removeMessages(MSG_STOP_SOUNDS);
            // Force stop right now
            onStopSounds();
        }
        
        synchronized (this) {
            ToneGenerator toneGen = getOrCreateToneGenerator(streamType);
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP);
        }

        sendMessageDelayed(obtainMessage(MSG_STOP_SOUNDS), BEEP_DURATION);
    }

    protected void onStopSounds() {
        
        synchronized (this) {
            for (int i = AudioManager.NUM_STREAMS - 1; i >= 0; i--) {
                ToneGenerator toneGen = mToneGenerators[i];
                if (toneGen != null) {
                    toneGen.stopTone();
                }
            }
        }
    }
    
    protected void onVibrate() {
        
        // Make sure we ended up in vibrate ringer mode
        if (mAudioService.getRingerMode() != AudioManager.RINGER_MODE_VIBRATE) {
            return;
        }
        
        mVibrator.vibrate(VIBRATE_DURATION);
    }
    
    /**
     * Lock on this VolumePanel instance as long as you use the returned ToneGenerator.
     */
    private ToneGenerator getOrCreateToneGenerator(int streamType) {
        synchronized (this) {
            if (mToneGenerators[streamType] == null) {
                return mToneGenerators[streamType] = new ToneGenerator(streamType, MAX_VOLUME);
            } else {
                return mToneGenerators[streamType];
            }
        }
    }
    
    private void setOtherIcon(int index) {
        mRingerStreamIcon.setVisibility(View.GONE);
        mOtherStreamIcon.setVisibility(View.VISIBLE);
        
        mOtherStreamIcon.setImageResource(index == 0
                ? com.android.internal.R.drawable.ic_volume_off_small
                : com.android.internal.R.drawable.ic_volume_small);
    }

    private void setRingerIcon(int index) {
        mOtherStreamIcon.setVisibility(View.GONE);
        mRingerStreamIcon.setVisibility(View.VISIBLE);

        int ringerMode = mAudioService.getRingerMode();
        int icon;

        if (LOGD) Log.d(TAG, "setRingerIcon(index: " + index+ "), ringerMode: " + ringerMode);

        if (ringerMode == AudioManager.RINGER_MODE_SILENT) {
            icon = com.android.internal.R.drawable.ic_volume_off;
        } else if (ringerMode == AudioManager.RINGER_MODE_VIBRATE) {
            icon = com.android.internal.R.drawable.ic_vibrate;
        } else {
            icon = com.android.internal.R.drawable.ic_volume;
        }
        mRingerStreamIcon.setImageResource(icon);
    }
    
    protected void onFreeResources() {
        // We'll keep the views, just ditch the cached drawable and hence
        // bitmaps
        mOtherStreamIcon.setImageDrawable(null);
        mRingerStreamIcon.setImageDrawable(null);
        
        synchronized (this) {
            for (int i = mToneGenerators.length - 1; i >= 0; i--) {
                if (mToneGenerators[i] != null) {
                    mToneGenerators[i].release();
                }
                mToneGenerators[i] = null;
            }
        }
    }
    
    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            
            case MSG_VOLUME_CHANGED: {
                onVolumeChanged(msg.arg1, msg.arg2);
                break;
            }
            
            case MSG_FREE_RESOURCES: {
                onFreeResources();
                break;
            }
            
            case MSG_STOP_SOUNDS: {
                onStopSounds();
                break;
            }
            
            case MSG_PLAY_SOUND: {
                onPlaySound(msg.arg1, msg.arg2);
                break;
            }

            case MSG_VIBRATE: {
                onVibrate();
                break;
            }
            
        }
    }
    
}
