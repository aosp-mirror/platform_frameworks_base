/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.media;

import android.content.Context;
import android.media.SoundPool;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * <p>A class for producing sounds that match those produced by various actions
 * taken by the media and camera APIs. It is recommended to call methods in this class
 * in a background thread since it relies on binder calls.</p>
 *
 * <p>This class is recommended for use with the {@link android.hardware.camera2} API, since the
 * camera2 API does not play any sounds on its own for any capture or video recording actions.</p>
 *
 * <p>With the older {@link android.hardware.Camera} API, use this class to play an appropriate
 * camera operation sound when implementing a custom still or video recording mechanism (through the
 * Camera preview callbacks with
 * {@link android.hardware.Camera#setPreviewCallback Camera.setPreviewCallback}, or through GPU
 * processing with {@link android.hardware.Camera#setPreviewTexture Camera.setPreviewTexture}, for
 * example), or when implementing some other camera-like function in your application.</p>
 *
 * <p>There is no need to play sounds when using
 * {@link android.hardware.Camera#takePicture Camera.takePicture} or
 * {@link android.media.MediaRecorder} for still images or video, respectively,
 * as the Android framework will play the appropriate sounds when needed for
 * these calls.</p>
 *
 */
public class MediaActionSound {
    private static final int NUM_MEDIA_SOUND_STREAMS = 1;

    private SoundPool mSoundPool;
    private SoundState[] mSounds;

    private static final String[] SOUND_DIRS = {
        "/product/media/audio/ui/",
        "/system/media/audio/ui/",
    };

    private static final String[] SOUND_FILES = {
        "camera_click.ogg",
        "camera_focus.ogg",
        "VideoRecord.ogg",
        "VideoStop.ogg"
    };

    private static final String TAG = "MediaActionSound";
    /**
     * The sound used by
     * {@link android.hardware.Camera#takePicture Camera.takePicture} to
     * indicate still image capture.
     * @see #play
     */
    public static final int SHUTTER_CLICK         = 0;

    /**
     * A sound to indicate that focusing has completed. Because deciding
     * when this occurs is application-dependent, this sound is not used by
     * any methods in the media or camera APIs.
     * @see #play
     */
    public static final int FOCUS_COMPLETE        = 1;

    /**
     * The sound used by
     * {@link android.media.MediaRecorder#start MediaRecorder.start()} to
     * indicate the start of video recording.
     * @see #play
     */
    public static final int START_VIDEO_RECORDING = 2;

    /**
     * The sound used by
     * {@link android.media.MediaRecorder#stop MediaRecorder.stop()} to
     * indicate the end of video recording.
     * @see #play
     */
    public static final int STOP_VIDEO_RECORDING  = 3;

    /**
     * States for SoundState.
     * STATE_NOT_LOADED             : sample not loaded
     * STATE_LOADING                : sample being loaded: waiting for load completion callback
     * STATE_LOADING_PLAY_REQUESTED : sample being loaded and playback request received
     * STATE_LOADED                 : sample loaded, ready for playback
     */
    private static final int STATE_NOT_LOADED             = 0;
    private static final int STATE_LOADING                = 1;
    private static final int STATE_LOADING_PLAY_REQUESTED = 2;
    private static final int STATE_LOADED                 = 3;

    /**
     * <p>Returns true if the application must play the shutter sound in accordance
     * to certain regional restrictions.</p>
     *
     * <p>If this method returns true, applications are strongly recommended to use
     * MediaActionSound.play(SHUTTER_CLICK) or START_VIDEO_RECORDING whenever it captures
     * images or video to storage or sends them over the network.</p>
     */
    public static boolean mustPlayShutterSound() {
        boolean result = false;
        IBinder b = ServiceManager.getService(Context.AUDIO_SERVICE);
        IAudioService audioService = IAudioService.Stub.asInterface(b);
        try {
            result = audioService.isCameraSoundForced();
        } catch (RemoteException e) {
            Log.e(TAG, "audio service is unavailable for queries, defaulting to false");
        }
        return result;
    }

    private class SoundState {
        public final int name;
        public int id;
        public int state;

        public SoundState(int name) {
            this.name = name;
            id = 0; // 0 is an invalid sample ID.
            state = STATE_NOT_LOADED;
        }
    }
    /**
     * Construct a new MediaActionSound instance. Only a single instance is
     * needed for playing any platform media action sound; you do not need a
     * separate instance for each sound type.
     */
    public MediaActionSound() {
        mSoundPool = new SoundPool.Builder()
                .setMaxStreams(NUM_MEDIA_SOUND_STREAMS)
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .build();
        mSoundPool.setOnLoadCompleteListener(mLoadCompleteListener);
        mSounds = new SoundState[SOUND_FILES.length];
        for (int i = 0; i < mSounds.length; i++) {
            mSounds[i] = new SoundState(i);
        }
    }

    private int loadSound(SoundState sound) {
        final String soundFileName = SOUND_FILES[sound.name];
        for (String soundDir : SOUND_DIRS) {
            int id = mSoundPool.load(soundDir + soundFileName, 1);
            if (id > 0) {
                sound.state = STATE_LOADING;
                sound.id = id;
                return id;
            }
        }
        return 0;
    }

    /**
     * Preload a predefined platform sound to minimize latency when the sound is
     * played later by {@link #play}.
     * @param soundName The type of sound to preload, selected from
     *         SHUTTER_CLICK, FOCUS_COMPLETE, START_VIDEO_RECORDING, or
     *         STOP_VIDEO_RECORDING.
     * @see #play
     * @see #SHUTTER_CLICK
     * @see #FOCUS_COMPLETE
     * @see #START_VIDEO_RECORDING
     * @see #STOP_VIDEO_RECORDING
     */
    public void load(int soundName) {
        if (soundName < 0 || soundName >= SOUND_FILES.length) {
            throw new RuntimeException("Unknown sound requested: " + soundName);
        }
        SoundState sound = mSounds[soundName];
        synchronized (sound) {
            switch (sound.state) {
            case STATE_NOT_LOADED:
                if (loadSound(sound) <= 0) {
                    Log.e(TAG, "load() error loading sound: " + soundName);
                }
                break;
            default:
                Log.e(TAG, "load() called in wrong state: " + sound + " for sound: "+ soundName);
                break;
            }
        }
    }

    /**
     * <p>Play one of the predefined platform sounds for media actions.</p>
     *
     * <p>Use this method to play a platform-specific sound for various media
     * actions. The sound playback is done asynchronously, with the same
     * behavior and content as the sounds played by
     * {@link android.hardware.Camera#takePicture Camera.takePicture},
     * {@link android.media.MediaRecorder#start MediaRecorder.start}, and
     * {@link android.media.MediaRecorder#stop MediaRecorder.stop}.</p>
     *
     * <p>With the {@link android.hardware.camera2 camera2} API, this method can be used to play
     * standard camera operation sounds with the appropriate system behavior for such sounds.</p>

     * <p>With the older {@link android.hardware.Camera} API, using this method makes it easy to
     * match the default device sounds when recording or capturing data through the preview
     * callbacks, or when implementing custom camera-like features in your application.</p>
     *
     * <p>If the sound has not been loaded by {@link #load} before calling play,
     * play will load the sound at the cost of some additional latency before
     * sound playback begins. </p>
     *
     * @param soundName The type of sound to play, selected from
     *         SHUTTER_CLICK, FOCUS_COMPLETE, START_VIDEO_RECORDING, or
     *         STOP_VIDEO_RECORDING.
     * @see android.hardware.Camera#takePicture
     * @see android.media.MediaRecorder
     * @see #SHUTTER_CLICK
     * @see #FOCUS_COMPLETE
     * @see #START_VIDEO_RECORDING
     * @see #STOP_VIDEO_RECORDING
     */
    public void play(int soundName) {
        if (soundName < 0 || soundName >= SOUND_FILES.length) {
            throw new RuntimeException("Unknown sound requested: " + soundName);
        }
        SoundState sound = mSounds[soundName];
        synchronized (sound) {
            switch (sound.state) {
            case STATE_NOT_LOADED:
                loadSound(sound);
                if (loadSound(sound) <= 0) {
                    Log.e(TAG, "play() error loading sound: " + soundName);
                    break;
                }
                // FALL THROUGH

            case STATE_LOADING:
                sound.state = STATE_LOADING_PLAY_REQUESTED;
                break;
            case STATE_LOADED:
                mSoundPool.play(sound.id, 1.0f, 1.0f, 0, 0, 1.0f);
                break;
            default:
                Log.e(TAG, "play() called in wrong state: " + sound.state + " for sound: "+ soundName);
                break;
            }
        }
    }

    private SoundPool.OnLoadCompleteListener mLoadCompleteListener =
            new SoundPool.OnLoadCompleteListener() {
        public void onLoadComplete(SoundPool soundPool,
                int sampleId, int status) {
            for (SoundState sound : mSounds) {
                if (sound.id != sampleId) {
                    continue;
                }
                int playSoundId = 0;
                synchronized (sound) {
                    if (status != 0) {
                        sound.state = STATE_NOT_LOADED;
                        sound.id = 0;
                        Log.e(TAG, "OnLoadCompleteListener() error: " + status +
                                " loading sound: "+ sound.name);
                        return;
                    }
                    switch (sound.state) {
                    case STATE_LOADING:
                        sound.state = STATE_LOADED;
                        break;
                    case STATE_LOADING_PLAY_REQUESTED:
                        playSoundId = sound.id;
                        sound.state = STATE_LOADED;
                        break;
                    default:
                        Log.e(TAG, "OnLoadCompleteListener() called in wrong state: "
                                + sound.state + " for sound: "+ sound.name);
                        break;
                    }
                }
                if (playSoundId != 0) {
                    soundPool.play(playSoundId, 1.0f, 1.0f, 0, 0, 1.0f);
                }
                break;
            }
        }
    };

    /**
     * Free up all audio resources used by this MediaActionSound instance. Do
     * not call any other methods on a MediaActionSound instance after calling
     * release().
     */
    public void release() {
        if (mSoundPool != null) {
            for (SoundState sound : mSounds) {
                synchronized (sound) {
                    sound.state = STATE_NOT_LOADED;
                    sound.id = 0;
                }
            }
            mSoundPool.release();
            mSoundPool = null;
        }
    }
}
