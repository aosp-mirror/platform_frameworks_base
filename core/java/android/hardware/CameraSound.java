/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.hardware;

import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemProperties;
import android.util.Log;

import java.io.IOException;

/**
 * <p>Use this class to play an appropriate sound when implementing a custom
 * still or video recording mechanism through the preview callbacks.</p>
 *
 * <p>There is no need to play sounds when using {@link #android.hardware.Camera#takePicture}
 * or {@link android.media.MediaRecorder} for still images or video,
 * respectively, as these play their own sounds when needed.</p>
 *
 * @hide
 */
public class CameraSound {
    private static final String TAG = "CameraSound";
    /**
     * The sound used by {@link android.hardware.Camera#takePicture} to
     * indicate still image capture.
     */
    public static final int SHUTTER_CLICK         = 0;

    /**
     * A sound to indicate that focusing has completed. Because deciding
     * when this occurs is application-dependent, this sound is not used by
     * any methods in the Camera class.
     */
    public static final int FOCUS_COMPLETE        = 1;

    /**
     * The sound used by {@link android.media.MediaRecorder#start} to
     * indicate the start of video recording.
     */
    public static final int START_VIDEO_RECORDING = 2;

    /**
     * The sound used by {@link android.media.MediaRecorder#stop} to
     * indicate the end of video recording.
     */
    public static final int STOP_VIDEO_RECORDING  = 3;

    private static final int NUM_SOUNDS           = 4;
    private CameraSoundPlayer[] mCameraSoundPlayers;

    public CameraSound() {
    }

    /**
     * <p>Play one of the predefined platform sounds for camera actions.</p>
     *
     * <p>Use this method to play a platform-specific sound for various camera
     * actions. The sound playing is done asynchronously, with the same behavior
     * and content as the sounds played by {@link #takePicture takePicture},
     * {@link android.media.MediaRecorder#start MediaRecorder.start}, and
     * {@link android.media.MediaRecorder#stop MediaRecorder.stop}.</p>
     *
     * <p>Using this method makes it easy to match the default device sounds
     * when recording or capturing data through the preview callbacks.</p>
     *
     * @param soundId The type of sound to play, selected from SHUTTER_CLICK,
     *         FOCUS_COMPLETE, START_VIDEO_RECORDING, or STOP_VIDEO_RECORDING.
     * @see android.hardware#takePicture
     * @see android.media.MediaRecorder
     * @see #SHUTTER_CLICK
     * @see #FOCUS_COMPLETE
     * @see #START_VIDEO_RECORDING
     * @see #STOP_VIDEO_RECORDING
     */
    public void playSound(int soundId) {
        if (mCameraSoundPlayers == null) {
            mCameraSoundPlayers = new CameraSoundPlayer[NUM_SOUNDS];
        }
        if (mCameraSoundPlayers[soundId] == null) {
            mCameraSoundPlayers[soundId] = new CameraSoundPlayer(soundId);
        }
        mCameraSoundPlayers[soundId].play();
    }

    public void release() {
        if (mCameraSoundPlayers != null) {
            for (CameraSoundPlayer csp: mCameraSoundPlayers) {
                if (csp != null) {
                    csp.release();
                }
            }
            mCameraSoundPlayers = null;
        }
    }

    private static class CameraSoundPlayer implements Runnable {
        private int mSoundId;
        private int mAudioStreamType;
        private MediaPlayer mPlayer;
        private Thread mThread;
        private boolean mExit;
        private int mPlayCount;

        private static final String mShutterSound    =
                "/system/media/audio/ui/camera_click.ogg";
        private static final String mFocusSound      =
                "/system/media/audio/ui/camera_focus.ogg";
        private static final String mVideoStartSound =
                "/system/media/audio/ui/VideoRecord.ogg";
        private static final String mVideoStopSound  =
                "/system/media/audio/ui/VideoRecord.ogg";

        @Override
        public void run() {
            String soundFilePath;
            switch (mSoundId) {
                case SHUTTER_CLICK:
                    soundFilePath = mShutterSound;
                    break;
                case FOCUS_COMPLETE:
                    soundFilePath = mFocusSound;
                    break;
                case START_VIDEO_RECORDING:
                    soundFilePath = mVideoStartSound;
                    break;
                case STOP_VIDEO_RECORDING:
                    soundFilePath = mVideoStopSound;
                    break;
                default:
                    Log.e(TAG, "Unknown sound " + mSoundId + " requested.");
                    return;
            }
            mPlayer = new MediaPlayer();
            try {
                mPlayer.setAudioStreamType(mAudioStreamType);
                mPlayer.setDataSource(soundFilePath);
                mPlayer.setLooping(false);
                mPlayer.prepare();
            } catch(IOException e) {
                Log.e(TAG, "Error setting up sound " + mSoundId, e);
                return;
            }

            while(true) {
                try {
                    synchronized (this) {
                        while(true) {
                            if (mExit) {
                                return;
                            } else if (mPlayCount <= 0) {
                                wait();
                            } else {
                                mPlayCount--;
                                break;
                            }
                        }
                    }
                    mPlayer.start();
                } catch (Exception e) {
                    Log.e(TAG, "Error playing sound " + mSoundId, e);
                }
            }
        }

        public CameraSoundPlayer(int soundId) {
            mSoundId = soundId;
            if (SystemProperties.get("ro.camera.sound.forced", "0").equals("0")) {
                mAudioStreamType = AudioManager.STREAM_MUSIC;
            } else {
                mAudioStreamType = AudioManager.STREAM_SYSTEM_ENFORCED;
            }
        }

        public void play() {
            if (mThread == null) {
                mThread = new Thread(this);
                mThread.start();
            }
            synchronized (this) {
                mPlayCount++;
                notifyAll();
            }
        }

        public void release() {
            if (mThread != null) {
                synchronized (this) {
                    mExit = true;
                    notifyAll();
                }
                try {
                    mThread.join();
                } catch (InterruptedException e) {
                }
                mThread = null;
            }
            if (mPlayer != null) {
                mPlayer.release();
                mPlayer = null;
            }
        }

        @Override
        protected void finalize() {
            release();
        }
    }
}