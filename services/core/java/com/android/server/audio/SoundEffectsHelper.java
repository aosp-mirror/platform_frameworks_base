/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.audio;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioSystem;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.SoundPool;
import android.os.Environment;
import android.os.Looper;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * A helper class for managing sound effects loading / unloading
 * used by AudioService.
 * @hide
 */
class SoundEffectsHelper {
    private static final String TAG = "AS.SoundEffectsHelper";

    private final Object mSoundEffectsLock = new Object();
    @GuardedBy("mSoundEffectsLock")
    private SoundPool mSoundPool;
    private static final int NUM_SOUNDPOOL_CHANNELS = 4;

    /* Sound effect file names  */
    private static final String SOUND_EFFECTS_PATH = "/media/audio/ui/";
    private static final List<String> SOUND_EFFECT_FILES = new ArrayList<String>();

    /* Sound effect file name mapping sound effect id (AudioManager.FX_xxx) to
     * file index in SOUND_EFFECT_FILES[] (first column) and indicating if effect
     * uses soundpool (second column) */
    private final int[][] mSoundEffectFilesMap = new int[AudioManager.NUM_SOUND_EFFECTS][2];

    private final Context mContext;

    // listener for SoundPool sample load completion indication
    @GuardedBy("mSoundEffectsLock")
    private SoundPoolCallback mSoundPoolCallBack;
    // thread for SoundPool listener
    private SoundPoolListenerThread mSoundPoolListenerThread;
    // message looper for SoundPool listener
    @GuardedBy("mSoundEffectsLock")
    private Looper mSoundPoolLooper = null;

    // volume applied to sound played with playSoundEffect()
    private static int sSoundEffectVolumeDb;

    interface OnEffectsLoadCompleteHandler {
        void run(boolean success);
    }

    SoundEffectsHelper(Context context) {
        mContext = context;
        sSoundEffectVolumeDb = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_soundEffectVolumeDb);
    }

    /*package*/ void loadSoundEffects(OnEffectsLoadCompleteHandler onComplete) {
        boolean success = doLoadSoundEffects();
        if (onComplete != null) {
            onComplete.run(success);
        }
    }

    private boolean doLoadSoundEffects() {
        int status;

        synchronized (mSoundEffectsLock) {
            if (mSoundPool != null) {
                return true;
            }

            loadTouchSoundAssets();

            mSoundPool = new SoundPool.Builder()
                    .setMaxStreams(NUM_SOUNDPOOL_CHANNELS)
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                    .build();
            mSoundPoolCallBack = null;
            mSoundPoolListenerThread = new SoundPoolListenerThread();
            mSoundPoolListenerThread.start();
            int attempts = 3;
            while ((mSoundPoolCallBack == null) && (attempts-- > 0)) {
                try {
                    // Wait for mSoundPoolCallBack to be set by the other thread
                    mSoundEffectsLock.wait(SOUND_EFFECTS_LOAD_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Log.w(TAG, "Interrupted while waiting sound pool listener thread.");
                }
            }

            if (mSoundPoolCallBack == null) {
                Log.w(TAG, "loadSoundEffects() SoundPool listener or thread creation error");
                if (mSoundPoolLooper != null) {
                    mSoundPoolLooper.quit();
                    mSoundPoolLooper = null;
                }
                mSoundPoolListenerThread = null;
                mSoundPool.release();
                mSoundPool = null;
                return false;
            }
            /*
             * poolId table: The value -1 in this table indicates that corresponding
             * file (same index in SOUND_EFFECT_FILES[] has not been loaded.
             * Once loaded, the value in poolId is the sample ID and the same
             * sample can be reused for another effect using the same file.
             */
            int[] poolId = new int[SOUND_EFFECT_FILES.size()];
            for (int fileIdx = 0; fileIdx < SOUND_EFFECT_FILES.size(); fileIdx++) {
                poolId[fileIdx] = -1;
            }
            /*
             * Effects whose value in mSoundEffectFilesMap[effect][1] is -1 must be loaded.
             * If load succeeds, value in mSoundEffectFilesMap[effect][1] is > 0:
             * this indicates we have a valid sample loaded for this effect.
             */

            int numSamples = 0;
            for (int effect = 0; effect < AudioManager.NUM_SOUND_EFFECTS; effect++) {
                // Do not load sample if this effect uses the MediaPlayer
                if (mSoundEffectFilesMap[effect][1] == 0) {
                    continue;
                }
                if (poolId[mSoundEffectFilesMap[effect][0]] == -1) {
                    String filePath = getSoundEffectFilePath(effect);
                    int sampleId = mSoundPool.load(filePath, 0);
                    if (sampleId <= 0) {
                        Log.w(TAG, "Soundpool could not load file: " + filePath);
                    } else {
                        mSoundEffectFilesMap[effect][1] = sampleId;
                        poolId[mSoundEffectFilesMap[effect][0]] = sampleId;
                        numSamples++;
                    }
                } else {
                    mSoundEffectFilesMap[effect][1] =
                            poolId[mSoundEffectFilesMap[effect][0]];
                }
            }
            // wait for all samples to be loaded
            if (numSamples > 0) {
                mSoundPoolCallBack.setSamples(poolId);

                attempts = 3;
                status = 1;
                while ((status == 1) && (attempts-- > 0)) {
                    try {
                        mSoundEffectsLock.wait(SOUND_EFFECTS_LOAD_TIMEOUT_MS);
                        status = mSoundPoolCallBack.status();
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Interrupted while waiting sound pool callback.");
                    }
                }
            } else {
                status = -1;
            }

            if (mSoundPoolLooper != null) {
                mSoundPoolLooper.quit();
                mSoundPoolLooper = null;
            }
            mSoundPoolListenerThread = null;
            if (status != 0) {
                Log.w(TAG,
                        "loadSoundEffects(), Error " + status + " while loading samples");
                for (int effect = 0; effect < AudioManager.NUM_SOUND_EFFECTS; effect++) {
                    if (mSoundEffectFilesMap[effect][1] > 0) {
                        mSoundEffectFilesMap[effect][1] = -1;
                    }
                }

                mSoundPool.release();
                mSoundPool = null;
            }
        }
        return (status == 0);
    }

    /**
     *  Unloads samples from the sound pool.
     *  This method can be called to free some memory when
     *  sound effects are disabled.
     */
    /*package*/ void unloadSoundEffects() {
        synchronized (mSoundEffectsLock) {
            if (mSoundPool == null) {
                return;
            }

            int[] poolId = new int[SOUND_EFFECT_FILES.size()];
            for (int fileIdx = 0; fileIdx < SOUND_EFFECT_FILES.size(); fileIdx++) {
                poolId[fileIdx] = 0;
            }

            for (int effect = 0; effect < AudioManager.NUM_SOUND_EFFECTS; effect++) {
                if (mSoundEffectFilesMap[effect][1] <= 0) {
                    continue;
                }
                if (poolId[mSoundEffectFilesMap[effect][0]] == 0) {
                    mSoundPool.unload(mSoundEffectFilesMap[effect][1]);
                    mSoundEffectFilesMap[effect][1] = -1;
                    poolId[mSoundEffectFilesMap[effect][0]] = -1;
                }
            }
            mSoundPool.release();
            mSoundPool = null;
        }
    }

    /*package*/ void playSoundEffect(int effectType, int volume) {
        synchronized (mSoundEffectsLock) {

            doLoadSoundEffects();

            if (mSoundPool == null) {
                return;
            }
            float volFloat;
            // use default if volume is not specified by caller
            if (volume < 0) {
                volFloat = (float) Math.pow(10, (float) sSoundEffectVolumeDb / 20);
            } else {
                volFloat = volume / 1000.0f;
            }

            if (mSoundEffectFilesMap[effectType][1] > 0) {
                mSoundPool.play(mSoundEffectFilesMap[effectType][1],
                                    volFloat, volFloat, 0, 0, 1.0f);
            } else {
                MediaPlayer mediaPlayer = new MediaPlayer();
                try {
                    String filePath = getSoundEffectFilePath(effectType);
                    mediaPlayer.setDataSource(filePath);
                    mediaPlayer.setAudioStreamType(AudioSystem.STREAM_SYSTEM);
                    mediaPlayer.prepare();
                    mediaPlayer.setVolume(volFloat);
                    mediaPlayer.setOnCompletionListener(new OnCompletionListener() {
                        public void onCompletion(MediaPlayer mp) {
                            cleanupPlayer(mp);
                        }
                    });
                    mediaPlayer.setOnErrorListener(new OnErrorListener() {
                        public boolean onError(MediaPlayer mp, int what, int extra) {
                            cleanupPlayer(mp);
                            return true;
                        }
                    });
                    mediaPlayer.start();
                } catch (IOException ex) {
                    Log.w(TAG, "MediaPlayer IOException: " + ex);
                } catch (IllegalArgumentException ex) {
                    Log.w(TAG, "MediaPlayer IllegalArgumentException: " + ex);
                } catch (IllegalStateException ex) {
                    Log.w(TAG, "MediaPlayer IllegalStateException: " + ex);
                }
            }
        }
    }

    private static void cleanupPlayer(MediaPlayer mp) {
        if (mp != null) {
            try {
                mp.stop();
                mp.release();
            } catch (IllegalStateException ex) {
                Log.w(TAG, "MediaPlayer IllegalStateException: " + ex);
            }
        }
    }

    private static final String TAG_AUDIO_ASSETS = "audio_assets";
    private static final String ATTR_VERSION = "version";
    private static final String TAG_GROUP = "group";
    private static final String ATTR_GROUP_NAME = "name";
    private static final String TAG_ASSET = "asset";
    private static final String ATTR_ASSET_ID = "id";
    private static final String ATTR_ASSET_FILE = "file";

    private static final String ASSET_FILE_VERSION = "1.0";
    private static final String GROUP_TOUCH_SOUNDS = "touch_sounds";

    private static final int SOUND_EFFECTS_LOAD_TIMEOUT_MS = 5000;

    private String getSoundEffectFilePath(int effectType) {
        String filePath = Environment.getProductDirectory() + SOUND_EFFECTS_PATH
                + SOUND_EFFECT_FILES.get(mSoundEffectFilesMap[effectType][0]);
        if (!new File(filePath).isFile()) {
            filePath = Environment.getRootDirectory() + SOUND_EFFECTS_PATH
                    + SOUND_EFFECT_FILES.get(mSoundEffectFilesMap[effectType][0]);
        }
        return filePath;
    }

    private void loadTouchSoundAssetDefaults() {
        SOUND_EFFECT_FILES.add("Effect_Tick.ogg");
        for (int i = 0; i < AudioManager.NUM_SOUND_EFFECTS; i++) {
            mSoundEffectFilesMap[i][0] = 0;
            mSoundEffectFilesMap[i][1] = -1;
        }
    }

    private void loadTouchSoundAssets() {
        XmlResourceParser parser = null;

        // only load assets once.
        if (!SOUND_EFFECT_FILES.isEmpty()) {
            return;
        }

        loadTouchSoundAssetDefaults();

        try {
            parser = mContext.getResources().getXml(com.android.internal.R.xml.audio_assets);

            XmlUtils.beginDocument(parser, TAG_AUDIO_ASSETS);
            String version = parser.getAttributeValue(null, ATTR_VERSION);
            boolean inTouchSoundsGroup = false;

            if (ASSET_FILE_VERSION.equals(version)) {
                while (true) {
                    XmlUtils.nextElement(parser);
                    String element = parser.getName();
                    if (element == null) {
                        break;
                    }
                    if (element.equals(TAG_GROUP)) {
                        String name = parser.getAttributeValue(null, ATTR_GROUP_NAME);
                        if (GROUP_TOUCH_SOUNDS.equals(name)) {
                            inTouchSoundsGroup = true;
                            break;
                        }
                    }
                }
                while (inTouchSoundsGroup) {
                    XmlUtils.nextElement(parser);
                    String element = parser.getName();
                    if (element == null) {
                        break;
                    }
                    if (element.equals(TAG_ASSET)) {
                        String id = parser.getAttributeValue(null, ATTR_ASSET_ID);
                        String file = parser.getAttributeValue(null, ATTR_ASSET_FILE);
                        int fx;

                        try {
                            Field field = AudioManager.class.getField(id);
                            fx = field.getInt(null);
                        } catch (Exception e) {
                            Log.w(TAG, "Invalid touch sound ID: " + id);
                            continue;
                        }

                        int i = SOUND_EFFECT_FILES.indexOf(file);
                        if (i == -1) {
                            i = SOUND_EFFECT_FILES.size();
                            SOUND_EFFECT_FILES.add(file);
                        }
                        mSoundEffectFilesMap[fx][0] = i;
                    } else {
                        break;
                    }
                }
            }
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "audio assets file not found", e);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "XML parser exception reading touch sound assets", e);
        } catch (IOException e) {
            Log.w(TAG, "I/O exception reading touch sound assets", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    private final class SoundPoolListenerThread extends Thread {
        SoundPoolListenerThread() {
            super("SoundPoolListenerThread");
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (mSoundEffectsLock) {
                mSoundPoolLooper = Looper.myLooper();
                if (mSoundPool != null) {
                    mSoundPoolCallBack = new SoundPoolCallback();
                    // This call makes SoundPool to start using the thread's looper
                    // for load complete message handling.
                    mSoundPool.setOnLoadCompleteListener(mSoundPoolCallBack);
                }
                mSoundEffectsLock.notify();
            }
            Looper.loop();
        }
    }

    private final class SoundPoolCallback implements
            android.media.SoundPool.OnLoadCompleteListener {

        @GuardedBy("mSoundEffectsLock")
        private int mStatus = 1; // 1 means neither error nor last sample loaded yet
        @GuardedBy("mSoundEffectsLock")
        List<Integer> mSamples = new ArrayList<Integer>();

        @GuardedBy("mSoundEffectsLock")
        public int status() {
            return mStatus;
        }

        @GuardedBy("mSoundEffectsLock")
        public void setSamples(int[] samples) {
            for (int i = 0; i < samples.length; i++) {
                // do not wait ack for samples rejected upfront by SoundPool
                if (samples[i] > 0) {
                    mSamples.add(samples[i]);
                }
            }
        }

        public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
            synchronized (mSoundEffectsLock) {
                int i = mSamples.indexOf(sampleId);
                if (i >= 0) {
                    mSamples.remove(i);
                }
                if ((status != 0) || mSamples.isEmpty()) {
                    mStatus = status;
                    mSoundEffectsLock.notify();
                }
            }
        }
    }
}
