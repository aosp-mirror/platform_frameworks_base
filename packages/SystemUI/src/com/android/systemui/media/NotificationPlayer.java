/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.media;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedList;

/**
 * @hide
 * This class is provides the same interface and functionality as android.media.AsyncPlayer
 * with the following differences:
 * - whenever audio is played, audio focus is requested,
 * - whenever audio playback is stopped or the playback completed, audio focus is abandoned.
 */
public class NotificationPlayer implements OnCompletionListener {
    private static final int PLAY = 1;
    private static final int STOP = 2;
    private static final boolean mDebug = false;

    private static final class Command {
        int code;
        Context context;
        Uri uri;
        boolean looping;
        AudioAttributes attributes;
        long requestTime;

        public String toString() {
            return "{ code=" + code + " looping=" + looping + " attributes=" + attributes
                    + " uri=" + uri + " }";
        }
    }

    private LinkedList<Command> mCmdQueue = new LinkedList();

    private Looper mLooper;

    /*
     * Besides the use of audio focus, the only implementation difference between AsyncPlayer and
     * NotificationPlayer resides in the creation of the MediaPlayer. For the completion callback,
     * OnCompletionListener, to be called at the end of the playback, the MediaPlayer needs to
     * be created with a looper running so its event handler is not null.
     */
    private final class CreationAndCompletionThread extends Thread {
        public Command mCmd;
        public CreationAndCompletionThread(Command cmd) {
            super();
            mCmd = cmd;
        }

        public void run() {
            Looper.prepare();
            mLooper = Looper.myLooper();
            synchronized(this) {
                AudioManager audioManager =
                    (AudioManager) mCmd.context.getSystemService(Context.AUDIO_SERVICE);
                try {
                    MediaPlayer player = new MediaPlayer();
                    player.setAudioAttributes(mCmd.attributes);
                    player.setDataSource(mCmd.context, mCmd.uri);
                    player.setLooping(mCmd.looping);
                    player.prepare();
                    if ((mCmd.uri != null) && (mCmd.uri.getEncodedPath() != null)
                            && (mCmd.uri.getEncodedPath().length() > 0)) {
                        if (!audioManager.isMusicActiveRemotely()) {
                            synchronized(mQueueAudioFocusLock) {
                                if (mAudioManagerWithAudioFocus == null) {
                                    if (mDebug) Log.d(mTag, "requesting AudioFocus");
                                    if (mCmd.looping) {
                                        audioManager.requestAudioFocus(null,
                                                AudioAttributes.toLegacyStreamType(mCmd.attributes),
                                                AudioManager.AUDIOFOCUS_GAIN);
                                    } else {
                                        audioManager.requestAudioFocus(null,
                                                AudioAttributes.toLegacyStreamType(mCmd.attributes),
                                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
                                    }
                                    mAudioManagerWithAudioFocus = audioManager;
                                } else {
                                    if (mDebug) Log.d(mTag, "AudioFocus was previously requested");
                                }
                            }
                        }
                    }
                    // FIXME Having to start a new thread so we can receive completion callbacks
                    //  is wrong, as we kill this thread whenever a new sound is to be played. This
                    //  can lead to AudioFocus being released too early, before the second sound is
                    //  done playing. This class should be modified to use a single thread, on which
                    //  command are issued, and on which it receives the completion callbacks.
                    player.setOnCompletionListener(NotificationPlayer.this);
                    player.start();
                    if (mPlayer != null) {
                        mPlayer.release();
                    }
                    mPlayer = player;
                }
                catch (Exception e) {
                    Log.w(mTag, "error loading sound for " + mCmd.uri, e);
                }
                this.notify();
            }
            Looper.loop();
        }
    };

    private void startSound(Command cmd) {
        // Preparing can be slow, so if there is something else
        // is playing, let it continue until we're done, so there
        // is less of a glitch.
        try {
            if (mDebug) Log.d(mTag, "Starting playback");
            //-----------------------------------
            // This is were we deviate from the AsyncPlayer implementation and create the
            // MediaPlayer in a new thread with which we're synchronized
            synchronized(mCompletionHandlingLock) {
                // if another sound was already playing, it doesn't matter we won't get notified
                // of the completion, since only the completion notification of the last sound
                // matters
                if((mLooper != null)
                        && (mLooper.getThread().getState() != Thread.State.TERMINATED)) {
                    mLooper.quit();
                }
                mCompletionThread = new CreationAndCompletionThread(cmd);
                synchronized(mCompletionThread) {
                    mCompletionThread.start();
                    mCompletionThread.wait();
                }
            }
            //-----------------------------------

            long delay = SystemClock.uptimeMillis() - cmd.requestTime;
            if (delay > 1000) {
                Log.w(mTag, "Notification sound delayed by " + delay + "msecs");
            }
        }
        catch (Exception e) {
            Log.w(mTag, "error loading sound for " + cmd.uri, e);
        }
    }

    private final class CmdThread extends java.lang.Thread {
        CmdThread() {
            super("NotificationPlayer-" + mTag);
        }

        public void run() {
            while (true) {
                Command cmd = null;

                synchronized (mCmdQueue) {
                    if (mDebug) Log.d(mTag, "RemoveFirst");
                    cmd = mCmdQueue.removeFirst();
                }

                switch (cmd.code) {
                case PLAY:
                    if (mDebug) Log.d(mTag, "PLAY");
                    startSound(cmd);
                    break;
                case STOP:
                    if (mDebug) Log.d(mTag, "STOP");
                    if (mPlayer != null) {
                        long delay = SystemClock.uptimeMillis() - cmd.requestTime;
                        if (delay > 1000) {
                            Log.w(mTag, "Notification stop delayed by " + delay + "msecs");
                        }
                        mPlayer.stop();
                        mPlayer.release();
                        mPlayer = null;
                        synchronized(mQueueAudioFocusLock) {
                            if (mAudioManagerWithAudioFocus != null) {
                                mAudioManagerWithAudioFocus.abandonAudioFocus(null);
                                mAudioManagerWithAudioFocus = null;
                            }
                        }
                        if((mLooper != null)
                                && (mLooper.getThread().getState() != Thread.State.TERMINATED)) {
                            mLooper.quit();
                        }
                    } else {
                        Log.w(mTag, "STOP command without a player");
                    }
                    break;
                }

                synchronized (mCmdQueue) {
                    if (mCmdQueue.size() == 0) {
                        // nothing left to do, quit
                        // doing this check after we're done prevents the case where they
                        // added it during the operation from spawning two threads and
                        // trying to do them in parallel.
                        mThread = null;
                        releaseWakeLock();
                        return;
                    }
                }
            }
        }
    }

    public void onCompletion(MediaPlayer mp) {
        synchronized(mQueueAudioFocusLock) {
            if (mAudioManagerWithAudioFocus != null) {
                if (mDebug) Log.d(mTag, "onCompletion() abandonning AudioFocus");
                mAudioManagerWithAudioFocus.abandonAudioFocus(null);
                mAudioManagerWithAudioFocus = null;
            } else {
                if (mDebug) Log.d(mTag, "onCompletion() no need to abandon AudioFocus");
            }
        }
        // if there are no more sounds to play, end the Looper to listen for media completion
        synchronized (mCmdQueue) {
            if (mCmdQueue.size() == 0) {
                synchronized(mCompletionHandlingLock) {
                    if(mLooper != null) {
                        mLooper.quit();
                    }
                    mCompletionThread = null;
                }
            }
        }
    }

    private String mTag;
    private CmdThread mThread;
    private CreationAndCompletionThread mCompletionThread;
    private final Object mCompletionHandlingLock = new Object();
    private MediaPlayer mPlayer;
    private PowerManager.WakeLock mWakeLock;
    private final Object mQueueAudioFocusLock = new Object();
    private AudioManager mAudioManagerWithAudioFocus; // synchronized on mQueueAudioFocusLock

    // The current state according to the caller.  Reality lags behind
    // because of the asynchronous nature of this class.
    private int mState = STOP;

    /**
     * Construct a NotificationPlayer object.
     *
     * @param tag a string to use for debugging
     */
    public NotificationPlayer(String tag) {
        if (tag != null) {
            mTag = tag;
        } else {
            mTag = "NotificationPlayer";
        }
    }

    /**
     * Start playing the sound.  It will actually start playing at some
     * point in the future.  There are no guarantees about latency here.
     * Calling this before another audio file is done playing will stop
     * that one and start the new one.
     *
     * @param context Your application's context.
     * @param uri The URI to play.  (see {@link MediaPlayer#setDataSource(Context, Uri)})
     * @param looping Whether the audio should loop forever.
     *          (see {@link MediaPlayer#setLooping(boolean)})
     * @param stream the AudioStream to use.
     *          (see {@link MediaPlayer#setAudioStreamType(int)})
     * @deprecated use {@link #play(Context, Uri, boolean, AudioAttributes)} instead.
     */
    @Deprecated
    public void play(Context context, Uri uri, boolean looping, int stream) {
        Command cmd = new Command();
        cmd.requestTime = SystemClock.uptimeMillis();
        cmd.code = PLAY;
        cmd.context = context;
        cmd.uri = uri;
        cmd.looping = looping;
        cmd.attributes = new AudioAttributes.Builder().setInternalLegacyStreamType(stream).build();
        synchronized (mCmdQueue) {
            enqueueLocked(cmd);
            mState = PLAY;
        }
    }

    /**
     * Start playing the sound.  It will actually start playing at some
     * point in the future.  There are no guarantees about latency here.
     * Calling this before another audio file is done playing will stop
     * that one and start the new one.
     *
     * @param context Your application's context.
     * @param uri The URI to play.  (see {@link MediaPlayer#setDataSource(Context, Uri)})
     * @param looping Whether the audio should loop forever.
     *          (see {@link MediaPlayer#setLooping(boolean)})
     * @param attributes the AudioAttributes to use.
     *          (see {@link MediaPlayer#setAudioAttributes(AudioAttributes)})
     */
    public void play(Context context, Uri uri, boolean looping, AudioAttributes attributes) {
        Command cmd = new Command();
        cmd.requestTime = SystemClock.uptimeMillis();
        cmd.code = PLAY;
        cmd.context = context;
        cmd.uri = uri;
        cmd.looping = looping;
        cmd.attributes = attributes;
        synchronized (mCmdQueue) {
            enqueueLocked(cmd);
            mState = PLAY;
        }
    }

    /**
     * Stop a previously played sound.  It can't be played again or unpaused
     * at this point.  Calling this multiple times has no ill effects.
     */
    public void stop() {
        synchronized (mCmdQueue) {
            // This check allows stop to be called multiple times without starting
            // a thread that ends up doing nothing.
            if (mState != STOP) {
                Command cmd = new Command();
                cmd.requestTime = SystemClock.uptimeMillis();
                cmd.code = STOP;
                enqueueLocked(cmd);
                mState = STOP;
            }
        }
    }

    private void enqueueLocked(Command cmd) {
        mCmdQueue.add(cmd);
        if (mThread == null) {
            acquireWakeLock();
            mThread = new CmdThread();
            mThread.start();
        }
    }

    /**
     * We want to hold a wake lock while we do the prepare and play.  The stop probably is
     * optional, but it won't hurt to have it too.  The problem is that if you start a sound
     * while you're holding a wake lock (e.g. an alarm starting a notification), you want the
     * sound to play, but if the CPU turns off before mThread gets to work, it won't.  The
     * simplest way to deal with this is to make it so there is a wake lock held while the
     * thread is starting or running.  You're going to need the WAKE_LOCK permission if you're
     * going to call this.
     *
     * This must be called before the first time play is called.
     *
     * @hide
     */
    public void setUsesWakeLock(Context context) {
        if (mWakeLock != null || mThread != null) {
            // if either of these has happened, we've already played something.
            // and our releases will be out of sync.
            throw new RuntimeException("assertion failed mWakeLock=" + mWakeLock
                    + " mThread=" + mThread);
        }
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, mTag);
    }

    private void acquireWakeLock() {
        if (mWakeLock != null) {
            mWakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock != null) {
            mWakeLock.release();
        }
    }
}
