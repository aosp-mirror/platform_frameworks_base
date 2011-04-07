/*
 * Copyright (c) 2009, Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.soundpooltest;

import android.app.Activity;
import android.widget.LinearLayout;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.KeyEvent;
import android.media.AudioSystem;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.SoundPool.OnLoadCompleteListener;
import android.util.Log;
import java.util.HashMap;
import java.lang.Math;

import com.android.soundpooltest.R;

public class SoundPoolTest extends Activity
{
    private static final String LOG_TAG = "SoundPoolTest";
    private static final boolean DEBUG = true;
    private static final boolean VERBOSE = false;
    private TestThread mThread;

    private static final int[] mTestFiles = new int[] {
        R.raw.organ441,
        R.raw.sine441,
        R.raw.test1,
        R.raw.test2,
        R.raw.test3,
        R.raw.test4,
        R.raw.test5
    };

    private final static float SEMITONE = 1.059463094f;
    private final static float DEFAULT_VOLUME = 0.707f;
    private final static float MAX_VOLUME = 1.0f;
    private final static float MIN_VOLUME = 0.01f;
    private final static int LOW_PRIORITY = 1000;
    private final static int NORMAL_PRIORITY = 2000;
    private final static int HIGH_PRIORITY = 3000;
    private final static int DEFAULT_LOOP = -1;
    private final static int DEFAULT_SRC_QUALITY = 0;
    private final static double PI_OVER_2 = Math.PI / 2.0;

    public SoundPoolTest() {}

    private final class TestThread extends java.lang.Thread {
        private boolean mRunning;
        private SoundPool mSoundPool = null;
        private int mLastSample;
        private int mMaxStreams;
        private int mLoadStatus;
        private int[] mSounds;
        private float mScale[];

        TestThread() {
            super("SoundPool.TestThread");
        }

        private final class LoadCompleteCallback implements
            android.media.SoundPool.OnLoadCompleteListener {
            public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
                synchronized(mSoundPool) {
                    if (DEBUG) Log.d(LOG_TAG, "Sample " + sampleId + " load status = " + status);
                    if (status != 0) {
                        mLoadStatus = status;
                    }
                    if (sampleId == mLastSample) {
                        mSoundPool.notify();
                    }
                }
            }
        }

        private int loadSound(int resId, int priority) {
            int id = mSoundPool.load(getApplicationContext(), resId, priority);
            if (id == 0) {
                Log.e(LOG_TAG, "Unable to open resource");
            }
            return id;
        }

        private int initSoundPool(int numStreams) throws java.lang.InterruptedException {

            if (mSoundPool != null) {
                if ((mMaxStreams == numStreams) && (mLoadStatus == 0)) return mLoadStatus;
                mSoundPool.release();
                mSoundPool = null;
            }

            // create sound pool
            mLoadStatus = 0;
            mMaxStreams = numStreams;
            mSoundPool = new SoundPool(numStreams, AudioSystem.STREAM_MUSIC, 0);
            mSoundPool.setOnLoadCompleteListener(new LoadCompleteCallback());
            int numSounds = mTestFiles.length;
            mSounds = new int[numSounds];

            // load sounds
            synchronized(mSoundPool) {
                for (int index = 0; index < numSounds; index++) {
                    mSounds[index] = loadSound(mTestFiles[index], NORMAL_PRIORITY);
                    mLastSample = mSounds[index];
                }
                mSoundPool.wait();
            }
            return mLoadStatus;
        }

        private boolean TestSounds() throws java.lang.InterruptedException {
            if (DEBUG) Log.d(LOG_TAG, "Begin sounds test");
            int count = mSounds.length;
            for (int index = 0; index < count; index++) {
                int id = mSoundPool.play(mSounds[index], DEFAULT_VOLUME, DEFAULT_VOLUME,
                        NORMAL_PRIORITY, DEFAULT_LOOP, 1.0f);
                if (DEBUG) Log.d(LOG_TAG, "Start note " + id);
                if (id == 0) {
                    Log.e(LOG_TAG, "Error occurred starting note");
                    return false;
                }
                sleep(450);
                mSoundPool.stop(id);
                if (DEBUG) Log.d(LOG_TAG, "Stop note " + id);
                sleep(50);
            }
            if (DEBUG) Log.d(LOG_TAG, "End scale test");
            return true;
        }

        private boolean TestScales() throws java.lang.InterruptedException {
            if (DEBUG) Log.d(LOG_TAG, "Begin scale test");

            // interate through pitch table
            int count = mScale.length;
            for (int step = 0; step < count; step++) {
                int id = mSoundPool.play(mSounds[0], DEFAULT_VOLUME, DEFAULT_VOLUME,
                        NORMAL_PRIORITY, DEFAULT_LOOP, mScale[step]);
                if (DEBUG) Log.d(LOG_TAG, "Start note " + id);
                if (id == 0) {
                    Log.e(LOG_TAG, "Error occurred starting note");
                    return false;
                }
                sleep(450);
                mSoundPool.stop(id);
                if (DEBUG) Log.d(LOG_TAG, "Stop note " + id);
                sleep(50);
            }
            if (DEBUG) Log.d(LOG_TAG, "End sounds test");
            return true;
        }

        private boolean TestRates() throws java.lang.InterruptedException {
            if (DEBUG) Log.d(LOG_TAG, "Begin rate test");

            // start the note
            int count = mScale.length;
            int id = mSoundPool.play(mSounds[0], DEFAULT_VOLUME, DEFAULT_VOLUME,
                    NORMAL_PRIORITY, DEFAULT_LOOP, mScale[0]);
            if (DEBUG) Log.d(LOG_TAG, "Start note " + id);
            if (id == 0) {
                Log.e(LOG_TAG, "Test failed - exiting");
                return false;
            }

            // modify the pitch
            for (int step = 1; step < count; step++) {
                sleep(250);
                mSoundPool.setRate(id, mScale[step]);
                if (DEBUG) Log.d(LOG_TAG, "Change rate " + mScale[step]);
            }
            mSoundPool.stop(id);
            if (DEBUG) Log.d(LOG_TAG, "End rate test");
            return true;
        }

        private boolean TestPriority() throws java.lang.InterruptedException {
            if (DEBUG) Log.d(LOG_TAG, "Begin priority test");
            boolean result = true;

            // play a normal priority looping sound
            int normalId = mSoundPool.play(mSounds[0], DEFAULT_VOLUME, DEFAULT_VOLUME,
                    NORMAL_PRIORITY, DEFAULT_LOOP, 1.0f);
            if (DEBUG) Log.d(LOG_TAG, "Start note " + normalId);
            if (normalId == 0) {
                Log.e(LOG_TAG, "Error occurred starting note");
                return false;
            }
            sleep(250);

            // play a low priority sound
            int id = mSoundPool.play(mSounds[0], DEFAULT_VOLUME, DEFAULT_VOLUME,
                    LOW_PRIORITY, DEFAULT_LOOP, 1.0f);
            if (id > 0) {
                Log.e(LOG_TAG, "Normal > Low priority test failed");
                result = false;
                mSoundPool.stop(id);
            } else {
                Log.e(LOG_TAG, "Normal > Low priority test passed");
            }
            sleep(250);

            // play a high priority sound
            id = mSoundPool.play(mSounds[0], DEFAULT_VOLUME, DEFAULT_VOLUME,
                    HIGH_PRIORITY, DEFAULT_LOOP, 1.0f);
            if (id == 0) {
                Log.e(LOG_TAG, "High > Normal priority test failed");
                result = false;
            } else {
                Log.e(LOG_TAG, "High > Normal priority test passed");
            }
            sleep(250);
            mSoundPool.stop(id);

            // stop normal note
            mSoundPool.stop(normalId);

            if (DEBUG) Log.d(LOG_TAG, "End priority test");
            return result;
        }

        private boolean TestPauseResume() throws java.lang.InterruptedException {
            if (DEBUG) Log.d(LOG_TAG, "Begin pause/resume test");
            boolean result = true;

            // play a normal priority looping sound
            int id = mSoundPool.play(mSounds[0], DEFAULT_VOLUME, DEFAULT_VOLUME,
                    NORMAL_PRIORITY, DEFAULT_LOOP, 1.0f);
            if (DEBUG) Log.d(LOG_TAG, "Start note " + id);
            if (id == 0) {
                Log.e(LOG_TAG, "Error occurred starting note");
                return false;
            }
            sleep(250);

            // pause and resume sound a few times
            for (int count = 0; count < 5; count++) {
                mSoundPool.pause(id);
                sleep(250);
                mSoundPool.resume(id);
                sleep(250);
            }

            mSoundPool.stop(id);

            // play 5 sounds, forces one to be stolen
            int ids[] = new int[5];
            for (int i = 0; i < 5; i++) {
                ids[i] = mSoundPool.play(mSounds[0], DEFAULT_VOLUME, DEFAULT_VOLUME,
                        NORMAL_PRIORITY, DEFAULT_LOOP, mScale[i]);
                if (DEBUG) Log.d(LOG_TAG, "Start note " + ids[i]);
                if (ids[i] == 0) {
                    Log.e(LOG_TAG, "Error occurred starting note");
                    return false;
                }
                sleep(250);
            }

            // pause and resume sound a few times
            for (int count = 0; count < 5; count++) {
                mSoundPool.autoPause();
                sleep(250);
                mSoundPool.autoResume();
                sleep(250);
            }

            for (int i = 0; i < 5; i++) {
                mSoundPool.stop(ids[i]);
            }

            if (DEBUG) Log.d(LOG_TAG, "End pause/resume test");
            return result;
        }

        private boolean TestVolume() throws java.lang.InterruptedException {
            if (DEBUG) Log.d(LOG_TAG, "Begin volume test");

            // start the note
            int id = mSoundPool.play(mSounds[0], 0.0f, 1.0f, NORMAL_PRIORITY, DEFAULT_LOOP, mScale[0]);
            if (DEBUG) Log.d(LOG_TAG, "Start note " + id);
            if (id == 0) {
                Log.e(LOG_TAG, "Test failed - exiting");
                return false;
            }

            // pan from left to right
            for (int count = 0; count < 101; count++) {
                sleep(20);
                double radians = PI_OVER_2 * count / 100.0;
                float leftVolume = (float) Math.sin(radians);
                float rightVolume = (float) Math.cos(radians);
                mSoundPool.setVolume(id, leftVolume, rightVolume);
                if (DEBUG) Log.d(LOG_TAG, "Change volume (" + leftVolume + "," + rightVolume + ")");
            }

            mSoundPool.stop(id);
            if (DEBUG) Log.d(LOG_TAG, "End volume test");
            return true;
        }

        public void run() {
            if (DEBUG) Log.d(LOG_TAG, "Test thread running");

            // initialize
            mRunning = true;
            int failures = 0;

            // initialize pitch table
            float pitch = 0.5f;
            mScale = new float[13];
            for (int i = 0; i < 13; ++i) {
                mScale[i] = pitch;
                pitch *= SEMITONE;
            }

            try {

                // do single stream tests
                initSoundPool(1);
                if (!TestSounds()) failures = failures + 1;
                if (!TestScales()) failures = failures + 1;
                if (!TestRates()) failures = failures + 1;
                if (!TestPriority()) failures = failures + 1;
                if (!TestVolume()) failures = failures + 1;

                // do multiple stream tests
                initSoundPool(4);
                if (!TestPauseResume()) failures = failures + 1;

            } catch (java.lang.InterruptedException e) {
                if (DEBUG) Log.d(LOG_TAG, "Test interrupted");
                failures = failures + 1;
            } finally {
                mRunning = false;
            }

            // release sound pool
            if (mSoundPool != null) {
                mSoundPool.release();
                mSoundPool = null;
            }

            // output stats
            if (DEBUG) Log.d(LOG_TAG, "Test thread exit");
            if (failures == 0) {
                Log.i(LOG_TAG, "All tests passed");
            } else {
                Log.i(LOG_TAG, failures + " tests failed");
            }
        }

        public void quit() {
            if (DEBUG) Log.d(LOG_TAG, "interrupt");
            interrupt();
            while (mRunning) {
                try {
                    sleep(20);
                } catch (java.lang.InterruptedException e) { }
            }
            if (DEBUG) Log.d(LOG_TAG, "quit");
        }
    }

    private void startTests() {
        mThread = new TestThread();
        mThread.start();
    }

    protected void onPause()
    {
        Log.v(LOG_TAG, "onPause");
        super.onPause();
        mThread.quit();
        mThread = null;
    }

    protected void onResume()
    {
        Log.v(LOG_TAG, "onResume");
        super.onResume();
        startTests();
    }

    public void onCreate(Bundle icicle)
    {
        super.onCreate(icicle);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }
}

