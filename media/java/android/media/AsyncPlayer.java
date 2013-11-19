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

package android.media;

import android.content.Context;
import android.net.Uri;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedList;

/**
 * Plays a series of audio URIs, but does all the hard work on another thread
 * so that any slowness with preparing or loading doesn't block the calling thread.
 */
public class AsyncPlayer {
    private static final int PLAY = 1;
    private static final int STOP = 2;
    private static final boolean mDebug = false;

    private static final class Command {
        int code;
        Context context;
        Uri uri;
        boolean looping;
        int stream;
        long requestTime;

        public String toString() {
            return "{ code=" + code + " looping=" + looping + " stream=" + stream
                    + " uri=" + uri + " }";
        }
    }

    private final LinkedList<Command> mCmdQueue = new LinkedList();

    private void startSound(Command cmd) {
        // Preparing can be slow, so if there is something else
        // is playing, let it continue until we're done, so there
        // is less of a glitch.
        try {
            if (mDebug) Log.d(mTag, "Starting playback");
            MediaPlayer player = new MediaPlayer();
            player.setAudioStreamType(cmd.stream);
            player.setDataSource(cmd.context, cmd.uri);
            player.setLooping(cmd.looping);
            player.prepare();
            player.start();
            if (mPlayer != null) {
                mPlayer.release();
            }
            mPlayer = player;
            long delay = SystemClock.uptimeMillis() - cmd.requestTime;
            if (delay > 1000) {
                Log.w(mTag, "Notification sound delayed by " + delay + "msecs");
            }
        }
        catch (Exception e) {
            Log.w(mTag, "error loading sound for " + cmd.uri, e);
        }
    }

    private final class Thread extends java.lang.Thread {
        Thread() {
            super("AsyncPlayer-" + mTag);
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

    private String mTag;
    private Thread mThread;
    private MediaPlayer mPlayer;
    private PowerManager.WakeLock mWakeLock;

    // The current state according to the caller.  Reality lags behind
    // because of the asynchronous nature of this class.
    private int mState = STOP;

    /**
     * Construct an AsyncPlayer object.
     *
     * @param tag a string to use for debugging
     */
    public AsyncPlayer(String tag) {
        if (tag != null) {
            mTag = tag;
        } else {
            mTag = "AsyncPlayer";
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
     */
    public void play(Context context, Uri uri, boolean looping, int stream) {
        Command cmd = new Command();
        cmd.requestTime = SystemClock.uptimeMillis();
        cmd.code = PLAY;
        cmd.context = context;
        cmd.uri = uri;
        cmd.looping = looping;
        cmd.stream = stream;
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
            mThread = new Thread();
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

