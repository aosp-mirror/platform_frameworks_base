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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.PrintWriterPrinter;

import com.android.internal.util.XmlUtils;
import com.android.server.utils.EventLogger;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A helper class for managing sound effects loading / unloading
 * used by AudioService. As its methods are called on the message handler thread
 * of AudioService, the actual work is offloaded to a dedicated thread.
 * This helps keeping AudioService responsive.
 *
 * @hide
 */
class SoundEffectsHelper {
    private static final String TAG = "AS.SfxHelper";

    private static final int NUM_SOUNDPOOL_CHANNELS = 4;

    /* Sound effect file names  */
    private static final String SOUND_EFFECTS_PATH = "/media/audio/ui/";

    private static final int EFFECT_NOT_IN_SOUND_POOL = 0; // SoundPool sample IDs > 0

    private static final int MSG_LOAD_EFFECTS = 0;
    private static final int MSG_UNLOAD_EFFECTS = 1;
    private static final int MSG_PLAY_EFFECT = 2;
    private static final int MSG_LOAD_EFFECTS_TIMEOUT = 3;

    interface OnEffectsLoadCompleteHandler {
        void run(boolean success);
    }

    private final EventLogger
            mSfxLogger = new EventLogger(
            AudioManager.NUM_SOUND_EFFECTS + 10, "Sound Effects Loading");

    private final Context mContext;
    // default attenuation applied to sound played with playSoundEffect()
    private final int mSfxAttenuationDb;

    // thread for doing all work
    private SfxWorker mSfxWorker;
    // thread's message handler
    private SfxHandler mSfxHandler;

    private static final class Resource {
        final String mFileName;
        int mSampleId;
        boolean mLoaded;  // for effects in SoundPool

        Resource(String fileName) {
            mFileName = fileName;
            mSampleId = EFFECT_NOT_IN_SOUND_POOL;
        }

        void unload() {
            mSampleId = EFFECT_NOT_IN_SOUND_POOL;
            mLoaded = false;
        }
    }

    // All the fields below are accessed by the worker thread exclusively
    private final List<Resource> mResources = new ArrayList<Resource>();
    private final int[] mEffects = new int[AudioManager.NUM_SOUND_EFFECTS]; // indexes in mResources
    private SoundPool mSoundPool;
    private SoundPoolLoader mSoundPoolLoader;

    SoundEffectsHelper(Context context) {
        mContext = context;
        mSfxAttenuationDb = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_soundEffectVolumeDb);
        startWorker();
    }

    /*package*/ void loadSoundEffects(OnEffectsLoadCompleteHandler onComplete) {
        sendMsg(MSG_LOAD_EFFECTS, 0, 0, onComplete, 0);
    }

    /**
     * Unloads samples from the sound pool.
     * This method can be called to free some memory when
     * sound effects are disabled.
     */
    /*package*/ void unloadSoundEffects() {
        sendMsg(MSG_UNLOAD_EFFECTS, 0, 0, null, 0);
    }

    /*package*/ void playSoundEffect(int effect, int volume) {
        sendMsg(MSG_PLAY_EFFECT, effect, volume, null, 0);
    }

    /*package*/ void dump(PrintWriter pw, String prefix) {
        if (mSfxHandler != null) {
            pw.println(prefix + "Message handler (watch for unhandled messages):");
            mSfxHandler.dump(new PrintWriterPrinter(pw), "  ");
        } else {
            pw.println(prefix + "Message handler is null");
        }
        pw.println(prefix + "Default attenuation (dB): " + mSfxAttenuationDb);
        mSfxLogger.dump(pw);
    }

    private void startWorker() {
        mSfxWorker = new SfxWorker();
        mSfxWorker.start();
        synchronized (this) {
            while (mSfxHandler == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Log.w(TAG, "Interrupted while waiting " + mSfxWorker.getName() + " to start");
                }
            }
        }
    }

    private void sendMsg(int msg, int arg1, int arg2, Object obj, int delayMs) {
        mSfxHandler.sendMessageDelayed(mSfxHandler.obtainMessage(msg, arg1, arg2, obj), delayMs);
    }

    private void logEvent(String msg) {
        mSfxLogger.enqueue(new EventLogger.StringEvent(msg));
    }

    // All the methods below run on the worker thread
    private void onLoadSoundEffects(OnEffectsLoadCompleteHandler onComplete) {
        if (mSoundPoolLoader != null) {
            // Loading is ongoing.
            mSoundPoolLoader.addHandler(onComplete);
            return;
        }
        if (mSoundPool != null) {
            if (onComplete != null) {
                onComplete.run(true /*success*/);
            }
            return;
        }

        logEvent("effects loading started");
        mSoundPool = new SoundPool.Builder()
                .setMaxStreams(NUM_SOUNDPOOL_CHANNELS)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        loadSoundAssets();

        mSoundPoolLoader = new SoundPoolLoader();
        mSoundPoolLoader.addHandler(new OnEffectsLoadCompleteHandler() {
            @Override
            public void run(boolean success) {
                mSoundPoolLoader = null;
                if (!success) {
                    Log.w(TAG, "onLoadSoundEffects(), Error while loading samples");
                    onUnloadSoundEffects();
                }
            }
        });
        mSoundPoolLoader.addHandler(onComplete);

        int resourcesToLoad = 0;
        for (Resource res : mResources) {
            String filePath = getResourceFilePath(res);
            int sampleId = mSoundPool.load(filePath, 0);
            if (sampleId > 0) {
                res.mSampleId = sampleId;
                res.mLoaded = false;
                resourcesToLoad++;
            } else {
                logEvent("effect " + filePath + " rejected by SoundPool");
                Log.w(TAG, "SoundPool could not load file: " + filePath);
            }
        }

        if (resourcesToLoad > 0) {
            sendMsg(MSG_LOAD_EFFECTS_TIMEOUT, 0, 0, null, SOUND_EFFECTS_LOAD_TIMEOUT_MS);
        } else {
            logEvent("effects loading completed, no effects to load");
            mSoundPoolLoader.onComplete(true /*success*/);
        }
    }

    void onUnloadSoundEffects() {
        if (mSoundPool == null) {
            return;
        }
        if (mSoundPoolLoader != null) {
            mSoundPoolLoader.addHandler(new OnEffectsLoadCompleteHandler() {
                @Override
                public void run(boolean success) {
                    onUnloadSoundEffects();
                }
            });
        }

        logEvent("effects unloading started");
        for (Resource res : mResources) {
            if (res.mSampleId != EFFECT_NOT_IN_SOUND_POOL) {
                mSoundPool.unload(res.mSampleId);
                res.unload();
            }
        }
        mSoundPool.release();
        mSoundPool = null;
        logEvent("effects unloading completed");
    }

    void onPlaySoundEffect(int effect, int volume) {
        float volFloat;
        // use default if volume is not specified by caller
        if (volume < 0) {
            volFloat = (float) Math.pow(10, (float) mSfxAttenuationDb / 20);
        } else {
            volFloat = volume / 1000.0f;
        }

        Resource res = mResources.get(mEffects[effect]);
        if (mSoundPool != null && res.mSampleId != EFFECT_NOT_IN_SOUND_POOL && res.mLoaded) {
            mSoundPool.play(res.mSampleId, volFloat, volFloat, 0, 0, 1.0f);
        } else {
            MediaPlayer mediaPlayer = new MediaPlayer();
            try {
                String filePath = getResourceFilePath(res);
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

    private static final int SOUND_EFFECTS_LOAD_TIMEOUT_MS = 15000;

    private String getResourceFilePath(Resource res) {
        String filePath = Environment.getProductDirectory() + SOUND_EFFECTS_PATH + res.mFileName;
        if (!new File(filePath).isFile()) {
            filePath = Environment.getRootDirectory() + SOUND_EFFECTS_PATH + res.mFileName;
        }
        return filePath;
    }

    private void loadSoundAssetDefaults() {
        int defaultResourceIdx = mResources.size();
        mResources.add(new Resource("Effect_Tick.ogg"));
        Arrays.fill(mEffects, defaultResourceIdx);
    }

    /**
     * Loads the sound assets information from audio_assets.xml
     * The expected format of audio_assets.xml is:
     * <ul>
     *  <li> all {@code <asset>s} listed directly in {@code <audio_assets>} </li>
     *  <li> for backwards compatibility: exactly one {@code <group>} with name
     *  {@link #GROUP_TOUCH_SOUNDS} </li>
     * </ul>
     */
    private void loadSoundAssets() {
        XmlResourceParser parser = null;

        // only load assets once.
        if (!mResources.isEmpty()) {
            return;
        }

        loadSoundAssetDefaults();

        try {
            parser = mContext.getResources().getXml(com.android.internal.R.xml.audio_assets);

            XmlUtils.beginDocument(parser, TAG_AUDIO_ASSETS);
            String version = parser.getAttributeValue(null, ATTR_VERSION);
            Map<Integer, Integer> parserCounter = new HashMap<>();
            if (ASSET_FILE_VERSION.equals(version)) {
                while (true) {
                    XmlUtils.nextElement(parser);
                    String element = parser.getName();
                    if (element == null) {
                        break;
                    }
                    if (element.equals(TAG_GROUP)) {
                        String name = parser.getAttributeValue(null, ATTR_GROUP_NAME);
                        if (!GROUP_TOUCH_SOUNDS.equals(name)) {
                            Log.w(TAG, "Unsupported group name: " + name);
                        }
                    } else if (element.equals(TAG_ASSET)) {
                        String id = parser.getAttributeValue(null, ATTR_ASSET_ID);
                        String file = parser.getAttributeValue(null, ATTR_ASSET_FILE);
                        int fx;

                        try {
                            Field field = AudioManager.class.getField(id);
                            fx = field.getInt(null);
                        } catch (Exception e) {
                            Log.w(TAG, "Invalid sound ID: " + id);
                            continue;
                        }
                        int currentParserCount = parserCounter.getOrDefault(fx, 0) + 1;
                        parserCounter.put(fx, currentParserCount);
                        if (currentParserCount > 1) {
                            Log.w(TAG, "Duplicate definition for sound ID: " + id);
                        }
                        mEffects[fx] = findOrAddResourceByFileName(file);
                    } else {
                        break;
                    }
                }

                boolean navigationRepeatFxParsed = allNavigationRepeatSoundsParsed(parserCounter);
                boolean homeSoundParsed = parserCounter.getOrDefault(AudioManager.FX_HOME, 0) > 0;
                if (navigationRepeatFxParsed || homeSoundParsed) {
                    AudioManager audioManager = mContext.getSystemService(AudioManager.class);
                    if (audioManager != null && navigationRepeatFxParsed) {
                        audioManager.setNavigationRepeatSoundEffectsEnabled(true);
                    }
                    if (audioManager != null && homeSoundParsed) {
                        audioManager.setHomeSoundEffectEnabled(true);
                    }
                }
            }
        } catch (Resources.NotFoundException e) {
            Log.w(TAG, "audio assets file not found", e);
        } catch (XmlPullParserException e) {
            Log.w(TAG, "XML parser exception reading sound assets", e);
        } catch (IOException e) {
            Log.w(TAG, "I/O exception reading sound assets", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    private boolean allNavigationRepeatSoundsParsed(Map<Integer, Integer> parserCounter) {
        int numFastScrollSoundEffectsParsed =
                parserCounter.getOrDefault(AudioManager.FX_FOCUS_NAVIGATION_REPEAT_1, 0)
                        + parserCounter.getOrDefault(AudioManager.FX_FOCUS_NAVIGATION_REPEAT_2, 0)
                        + parserCounter.getOrDefault(AudioManager.FX_FOCUS_NAVIGATION_REPEAT_3, 0)
                        + parserCounter.getOrDefault(AudioManager.FX_FOCUS_NAVIGATION_REPEAT_4, 0);
        return numFastScrollSoundEffectsParsed == AudioManager.NUM_NAVIGATION_REPEAT_SOUND_EFFECTS;
    }

    private int findOrAddResourceByFileName(String fileName) {
        for (int i = 0; i < mResources.size(); i++) {
            if (mResources.get(i).mFileName.equals(fileName)) {
                return i;
            }
        }
        int result = mResources.size();
        mResources.add(new Resource(fileName));
        return result;
    }

    private Resource findResourceBySampleId(int sampleId) {
        for (Resource res : mResources) {
            if (res.mSampleId == sampleId) {
                return res;
            }
        }
        return null;
    }

    private class SfxWorker extends Thread {
        SfxWorker() {
            super("AS.SfxWorker");
        }

        @Override
        public void run() {
            Looper.prepare();
            synchronized (SoundEffectsHelper.this) {
                mSfxHandler = new SfxHandler();
                SoundEffectsHelper.this.notify();
            }
            Looper.loop();
        }
    }

    private class SfxHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_LOAD_EFFECTS:
                    onLoadSoundEffects((OnEffectsLoadCompleteHandler) msg.obj);
                    break;
                case MSG_UNLOAD_EFFECTS:
                    onUnloadSoundEffects();
                    break;
                case MSG_PLAY_EFFECT:
                    final int effect = msg.arg1, volume = msg.arg2;
                    onLoadSoundEffects(new OnEffectsLoadCompleteHandler() {
                        @Override
                        public void run(boolean success) {
                            if (success) {
                                onPlaySoundEffect(effect, volume);
                            }
                        }
                    });
                    break;
                case MSG_LOAD_EFFECTS_TIMEOUT:
                    if (mSoundPoolLoader != null) {
                        mSoundPoolLoader.onTimeout();
                    }
                    break;
            }
        }
    }

    private class SoundPoolLoader implements
            android.media.SoundPool.OnLoadCompleteListener {

        private List<OnEffectsLoadCompleteHandler> mLoadCompleteHandlers =
                new ArrayList<OnEffectsLoadCompleteHandler>();

        SoundPoolLoader() {
            // SoundPool use the current Looper when creating its message handler.
            // Since SoundPoolLoader is created on the SfxWorker thread, SoundPool's
            // message handler ends up running on it (it's OK to have multiple
            // handlers on the same Looper). Thus, onLoadComplete gets executed
            // on the worker thread.
            mSoundPool.setOnLoadCompleteListener(this);
        }

        void addHandler(OnEffectsLoadCompleteHandler handler) {
            if (handler != null) {
                mLoadCompleteHandlers.add(handler);
            }
        }

        @Override
        public void onLoadComplete(SoundPool soundPool, int sampleId, int status) {
            if (status == 0) {
                int remainingToLoad = 0;
                for (Resource res : mResources) {
                    if (res.mSampleId == sampleId && !res.mLoaded) {
                        logEvent("effect " + res.mFileName + " loaded");
                        res.mLoaded = true;
                    }
                    if (res.mSampleId != EFFECT_NOT_IN_SOUND_POOL && !res.mLoaded) {
                        remainingToLoad++;
                    }
                }
                if (remainingToLoad == 0) {
                    onComplete(true);
                }
            } else {
                Resource res = findResourceBySampleId(sampleId);
                String filePath;
                if (res != null) {
                    filePath = getResourceFilePath(res);
                } else {
                    filePath = "with unknown sample ID " + sampleId;
                }
                logEvent("effect " + filePath + " loading failed, status " + status);
                Log.w(TAG, "onLoadSoundEffects(), Error " + status + " while loading sample "
                        + filePath);
                onComplete(false);
            }
        }

        void onTimeout() {
            onComplete(false);
        }

        void onComplete(boolean success) {
            if (mSoundPool != null) {
                mSoundPool.setOnLoadCompleteListener(null);
            }
            for (OnEffectsLoadCompleteHandler handler : mLoadCompleteHandlers) {
                handler.run(success);
            }
            logEvent("effects loading " + (success ? "completed" : "failed"));
        }
    }
}
