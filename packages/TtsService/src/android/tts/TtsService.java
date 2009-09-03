/*
 * Copyright (C) 2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package android.tts;

import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.speech.tts.ITts.Stub;
import android.speech.tts.ITtsCallback;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;


/**
 * @hide Synthesizes speech from text. This is implemented as a service so that
 *       other applications can call the TTS without needing to bundle the TTS
 *       in the build.
 *
 */
public class TtsService extends Service implements OnCompletionListener {

    private static class SpeechItem {
        public static final int TEXT = 0;
        public static final int EARCON = 1;
        public static final int SILENCE = 2;
        public static final int TEXT_TO_FILE = 3;
        public String mText = "";
        public ArrayList<String> mParams = null;
        public int mType = TEXT;
        public long mDuration = 0;
        public String mFilename = null;
        public String mCallingApp = "";

        public SpeechItem(String source, String text, ArrayList<String> params, int itemType) {
            mText = text;
            mParams = params;
            mType = itemType;
            mCallingApp = source;
        }

        public SpeechItem(String source, long silenceTime, ArrayList<String> params) {
            mDuration = silenceTime;
            mParams = params;
            mType = SILENCE;
            mCallingApp = source;
        }

        public SpeechItem(String source, String text, ArrayList<String> params,
                int itemType, String filename) {
            mText = text;
            mParams = params;
            mType = itemType;
            mFilename = filename;
            mCallingApp = source;
        }

    }

    /**
     * Contains the information needed to access a sound resource; the name of
     * the package that contains the resource and the resID of the resource
     * within that package.
     */
    private static class SoundResource {
        public String mSourcePackageName = null;
        public int mResId = -1;
        public String mFilename = null;

        public SoundResource(String packageName, int id) {
            mSourcePackageName = packageName;
            mResId = id;
            mFilename = null;
        }

        public SoundResource(String file) {
            mSourcePackageName = null;
            mResId = -1;
            mFilename = file;
        }
    }
    // If the speech queue is locked for more than 5 seconds, something has gone
    // very wrong with processSpeechQueue.
    private static final int SPEECHQUEUELOCK_TIMEOUT = 5000;
    private static final int MAX_SPEECH_ITEM_CHAR_LENGTH = 4000;
    private static final int MAX_FILENAME_LENGTH = 250;
    // TODO use the TTS stream type when available
    private static final int DEFAULT_STREAM_TYPE = AudioManager.STREAM_MUSIC;

    private static final String ACTION = "android.intent.action.START_TTS_SERVICE";
    private static final String CATEGORY = "android.intent.category.TTS";
    private static final String PKGNAME = "android.tts";

    private final RemoteCallbackList<ITtsCallback> mCallbacks
            = new RemoteCallbackList<ITtsCallback>();

    private HashMap<String, ITtsCallback> mCallbacksMap;

    private Boolean mIsSpeaking;
    private ArrayList<SpeechItem> mSpeechQueue;
    private HashMap<String, SoundResource> mEarcons;
    private HashMap<String, SoundResource> mUtterances;
    private MediaPlayer mPlayer;
    private SpeechItem mCurrentSpeechItem;
    private HashMap<SpeechItem, Boolean> mKillList; // Used to ensure that in-flight synth calls
                                                    // are killed when stop is used.
    private TtsService mSelf;

    private ContentResolver mResolver;

    private final ReentrantLock speechQueueLock = new ReentrantLock();
    private final ReentrantLock synthesizerLock = new ReentrantLock();

    private static SynthProxy sNativeSynth = null;
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i("TtsService", "TtsService.onCreate()");

        mResolver = getContentResolver();

        String soLibPath = "/system/lib/libttspico.so";
        if (sNativeSynth == null) {
            sNativeSynth = new SynthProxy(soLibPath);
        }

        mSelf = this;
        mIsSpeaking = false;

        mEarcons = new HashMap<String, SoundResource>();
        mUtterances = new HashMap<String, SoundResource>();
        mCallbacksMap = new HashMap<String, android.speech.tts.ITtsCallback>();

        mSpeechQueue = new ArrayList<SpeechItem>();
        mPlayer = null;
        mCurrentSpeechItem = null;
        mKillList = new HashMap<SpeechItem, Boolean>();

        setDefaultSettings();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // TODO replace the call to stopAll() with a method to clear absolutely all upcoming
        // uses of the native synth, including synthesis to a file, and delete files for which
        // synthesis was not complete.
        stopAll("");

        // Don't hog the media player
        cleanUpPlayer();

        if (sNativeSynth != null) {
            sNativeSynth.shutdown();
        }
        sNativeSynth = null;

        // Unregister all callbacks.
        mCallbacks.kill();
    }


    private void setDefaultSettings() {
        setLanguage("", this.getDefaultLanguage(), getDefaultCountry(), getDefaultLocVariant());

        // speech rate
        setSpeechRate("", getDefaultRate());
    }


    private boolean isDefaultEnforced() {
        return (android.provider.Settings.Secure.getInt(mResolver,
                    android.provider.Settings.Secure.TTS_USE_DEFAULTS,
                    TextToSpeech.Engine.USE_DEFAULTS)
                == 1 );
    }


    private int getDefaultRate() {
        return android.provider.Settings.Secure.getInt(mResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_RATE,
                TextToSpeech.Engine.DEFAULT_RATE);
    }


    private String getDefaultLanguage() {
        String defaultLang = android.provider.Settings.Secure.getString(mResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_LANG);
        if (defaultLang == null) {
            // no setting found, use the current Locale to determine the default language
            return Locale.getDefault().getISO3Language();
        } else {
            return defaultLang;
        }
    }


    private String getDefaultCountry() {
        String defaultCountry = android.provider.Settings.Secure.getString(mResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_COUNTRY);
        if (defaultCountry == null) {
            // no setting found, use the current Locale to determine the default country
            return Locale.getDefault().getISO3Country();
        } else {
            return defaultCountry;
        }
    }


    private String getDefaultLocVariant() {
        String defaultVar = android.provider.Settings.Secure.getString(mResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_VARIANT);
        if (defaultVar == null) {
            // no setting found, use the current Locale to determine the default variant
            return Locale.getDefault().getVariant();
        } else {
            return defaultVar;
        }
    }


    private int setSpeechRate(String callingApp, int rate) {
        int res = TextToSpeech.ERROR;
        try {
            if (isDefaultEnforced()) {
                res = sNativeSynth.setSpeechRate(getDefaultRate());
            } else {
                res = sNativeSynth.setSpeechRate(rate);
            }
        } catch (NullPointerException e) {
            // synth will become null during onDestroy()
            res = TextToSpeech.ERROR;
        }
        return res;
    }


    private int setPitch(String callingApp, int pitch) {
        int res = TextToSpeech.ERROR;
        try {
            res = sNativeSynth.setPitch(pitch);
        } catch (NullPointerException e) {
            // synth will become null during onDestroy()
            res = TextToSpeech.ERROR;
        }
        return res;
    }


    private int isLanguageAvailable(String lang, String country, String variant) {
        //Log.v("TtsService", "TtsService.isLanguageAvailable(" + lang + ", " + country + ", " +variant+")");
        int res = TextToSpeech.LANG_NOT_SUPPORTED;
        try {
            res = sNativeSynth.isLanguageAvailable(lang, country, variant);
        } catch (NullPointerException e) {
            // synth will become null during onDestroy()
            res = TextToSpeech.LANG_NOT_SUPPORTED;
        }
        return res;
    }


    private String[] getLanguage() {
        try {
            return sNativeSynth.getLanguage();
        } catch (Exception e) {
            return null;
        }
    }


    private int setLanguage(String callingApp, String lang, String country, String variant) {
        Log.v("TtsService", "TtsService.setLanguage(" + lang + ", " + country + ", " + variant + ")");
        int res = TextToSpeech.ERROR;
        try {
            if (isDefaultEnforced()) {
                res = sNativeSynth.setLanguage(getDefaultLanguage(), getDefaultCountry(),
                        getDefaultLocVariant());
            } else {
                res = sNativeSynth.setLanguage(lang, country, variant);
            }
        } catch (NullPointerException e) {
            // synth will become null during onDestroy()
            res = TextToSpeech.ERROR;
        }
        return res;
    }


    /**
     * Adds a sound resource to the TTS.
     *
     * @param text
     *            The text that should be associated with the sound resource
     * @param packageName
     *            The name of the package which has the sound resource
     * @param resId
     *            The resource ID of the sound within its package
     */
    private void addSpeech(String callingApp, String text, String packageName, int resId) {
        mUtterances.put(text, new SoundResource(packageName, resId));
    }

    /**
     * Adds a sound resource to the TTS.
     *
     * @param text
     *            The text that should be associated with the sound resource
     * @param filename
     *            The filename of the sound resource. This must be a complete
     *            path like: (/sdcard/mysounds/mysoundbite.mp3).
     */
    private void addSpeech(String callingApp, String text, String filename) {
        mUtterances.put(text, new SoundResource(filename));
    }

    /**
     * Adds a sound resource to the TTS as an earcon.
     *
     * @param earcon
     *            The text that should be associated with the sound resource
     * @param packageName
     *            The name of the package which has the sound resource
     * @param resId
     *            The resource ID of the sound within its package
     */
    private void addEarcon(String callingApp, String earcon, String packageName, int resId) {
        mEarcons.put(earcon, new SoundResource(packageName, resId));
    }

    /**
     * Adds a sound resource to the TTS as an earcon.
     *
     * @param earcon
     *            The text that should be associated with the sound resource
     * @param filename
     *            The filename of the sound resource. This must be a complete
     *            path like: (/sdcard/mysounds/mysoundbite.mp3).
     */
    private void addEarcon(String callingApp, String earcon, String filename) {
        mEarcons.put(earcon, new SoundResource(filename));
    }

    /**
     * Speaks the given text using the specified queueing mode and parameters.
     *
     * @param text
     *            The text that should be spoken
     * @param queueMode
     *            TextToSpeech.TTS_QUEUE_FLUSH for no queue (interrupts all previous utterances),
     *            TextToSpeech.TTS_QUEUE_ADD for queued
     * @param params
     *            An ArrayList of parameters. This is not implemented for all
     *            engines.
     */
    private int speak(String callingApp, String text, int queueMode, ArrayList<String> params) {
        Log.v("TtsService", "TTS service received " + text);
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            stop(callingApp);
        } else if (queueMode == 2) {
            stopAll(callingApp);
        }
        mSpeechQueue.add(new SpeechItem(callingApp, text, params, SpeechItem.TEXT));
        if (!mIsSpeaking) {
            processSpeechQueue();
        }
        return TextToSpeech.SUCCESS;
    }

    /**
     * Plays the earcon using the specified queueing mode and parameters.
     *
     * @param earcon
     *            The earcon that should be played
     * @param queueMode
     *            TextToSpeech.TTS_QUEUE_FLUSH for no queue (interrupts all previous utterances),
     *            TextToSpeech.TTS_QUEUE_ADD for queued
     * @param params
     *            An ArrayList of parameters. This is not implemented for all
     *            engines.
     */
    private int playEarcon(String callingApp, String earcon, int queueMode,
            ArrayList<String> params) {
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            stop(callingApp);
        } else if (queueMode == 2) {
            stopAll(callingApp);
        }
        mSpeechQueue.add(new SpeechItem(callingApp, earcon, params, SpeechItem.EARCON));
        if (!mIsSpeaking) {
            processSpeechQueue();
        }
        return TextToSpeech.SUCCESS;
    }

    /**
     * Stops all speech output and removes any utterances still in the queue for the calling app.
     */
    private int stop(String callingApp) {
        int result = TextToSpeech.ERROR;
        boolean speechQueueAvailable = false;
        try{
            speechQueueAvailable =
                    speechQueueLock.tryLock(SPEECHQUEUELOCK_TIMEOUT, TimeUnit.MILLISECONDS);
            if (speechQueueAvailable) {
                Log.i("TtsService", "Stopping");
                for (int i = mSpeechQueue.size() - 1; i > -1; i--){
                    if (mSpeechQueue.get(i).mCallingApp.equals(callingApp)){
                        mSpeechQueue.remove(i);
                    }
                }
                if ((mCurrentSpeechItem != null) &&
                     mCurrentSpeechItem.mCallingApp.equals(callingApp)) {
                    try {
                        result = sNativeSynth.stop();
                    } catch (NullPointerException e1) {
                        // synth will become null during onDestroy()
                        result = TextToSpeech.ERROR;
                    }
                    mKillList.put(mCurrentSpeechItem, true);
                    if (mPlayer != null) {
                        try {
                            mPlayer.stop();
                        } catch (IllegalStateException e) {
                            // Do nothing, the player is already stopped.
                        }
                    }
                    mIsSpeaking = false;
                    mCurrentSpeechItem = null;
                } else {
                    result = TextToSpeech.SUCCESS;
                }
                Log.i("TtsService", "Stopped");
            }
        } catch (InterruptedException e) {
          Log.e("TtsService", "TTS stop: tryLock interrupted");
          e.printStackTrace();
        } finally {
            // This check is needed because finally will always run; even if the
            // method returns somewhere in the try block.
            if (speechQueueAvailable) {
                speechQueueLock.unlock();
            }
            return result;
        }
    }



    /**
     * Stops all speech output and removes any utterances still in the queue globally, except
     * those intended to be synthesized to file.
     */
    private int stopAll(String callingApp) {
        int result = TextToSpeech.ERROR;
        boolean speechQueueAvailable = false;
        try{
            speechQueueAvailable =
                    speechQueueLock.tryLock(SPEECHQUEUELOCK_TIMEOUT, TimeUnit.MILLISECONDS);
            if (speechQueueAvailable) {
                for (int i = mSpeechQueue.size() - 1; i > -1; i--){
                    if (mSpeechQueue.get(i).mType != SpeechItem.TEXT_TO_FILE){
                        mSpeechQueue.remove(i);
                    }
                }
                if ((mCurrentSpeechItem != null) &&
                    ((mCurrentSpeechItem.mType != SpeechItem.TEXT_TO_FILE) ||
                      mCurrentSpeechItem.mCallingApp.equals(callingApp))) {
                    try {
                        result = sNativeSynth.stop();
                    } catch (NullPointerException e1) {
                        // synth will become null during onDestroy()
                        result = TextToSpeech.ERROR;
                    }
                    mKillList.put(mCurrentSpeechItem, true);
                    if (mPlayer != null) {
                        try {
                            mPlayer.stop();
                        } catch (IllegalStateException e) {
                            // Do nothing, the player is already stopped.
                        }
                    }
                    mIsSpeaking = false;
                    mCurrentSpeechItem = null;
                } else {
                    result = TextToSpeech.SUCCESS;
                }
                Log.i("TtsService", "Stopped all");
            }
        } catch (InterruptedException e) {
          Log.e("TtsService", "TTS stopAll: tryLock interrupted");
          e.printStackTrace();
        } finally {
            // This check is needed because finally will always run; even if the
            // method returns somewhere in the try block.
            if (speechQueueAvailable) {
                speechQueueLock.unlock();
            }
            return result;
        }
    }

    public void onCompletion(MediaPlayer arg0) {
        String callingApp = mCurrentSpeechItem.mCallingApp;
        ArrayList<String> params = mCurrentSpeechItem.mParams;
        String utteranceId = "";
        if (params != null){
            for (int i = 0; i < params.size() - 1; i = i + 2){
            String param = params.get(i);
                if (param.equals(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID)){
                    utteranceId = params.get(i+1);
                }
            }
        }
        if (utteranceId.length() > 0){
            dispatchUtteranceCompletedCallback(utteranceId, callingApp);
        }
        processSpeechQueue();
    }

    private int playSilence(String callingApp, long duration, int queueMode,
            ArrayList<String> params) {
        if (queueMode == TextToSpeech.QUEUE_FLUSH) {
            stop(callingApp);
        }
        mSpeechQueue.add(new SpeechItem(callingApp, duration, params));
        if (!mIsSpeaking) {
            processSpeechQueue();
        }
        return TextToSpeech.SUCCESS;
    }

    private void silence(final SpeechItem speechItem) {
        class SilenceThread implements Runnable {
            public void run() {
                String utteranceId = "";
                if (speechItem.mParams != null){
                    for (int i = 0; i < speechItem.mParams.size() - 1; i = i + 2){
                        String param = speechItem.mParams.get(i);
                        if (param.equals(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID)){
                            utteranceId = speechItem.mParams.get(i+1);
                        }
                    }
                }
                try {
                    Thread.sleep(speechItem.mDuration);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    if (utteranceId.length() > 0){
                        dispatchUtteranceCompletedCallback(utteranceId, speechItem.mCallingApp);
                    }
                    processSpeechQueue();
                }
            }
        }
        Thread slnc = (new Thread(new SilenceThread()));
        slnc.setPriority(Thread.MIN_PRIORITY);
        slnc.start();
    }

    private void speakInternalOnly(final SpeechItem speechItem) {
        class SynthThread implements Runnable {
            public void run() {
                boolean synthAvailable = false;
                String utteranceId = "";
                try {
                    synthAvailable = synthesizerLock.tryLock();
                    if (!synthAvailable) {
                        Thread.sleep(100);
                        Thread synth = (new Thread(new SynthThread()));
                        //synth.setPriority(Thread.MIN_PRIORITY);
                        synth.start();
                        return;
                    }
                    int streamType = DEFAULT_STREAM_TYPE;
                    String language = "";
                    String country = "";
                    String variant = "";
                    String speechRate = "";
                    if (speechItem.mParams != null){
                        for (int i = 0; i < speechItem.mParams.size() - 1; i = i + 2){
                            String param = speechItem.mParams.get(i);
                            if (param != null) {
                                if (param.equals(TextToSpeech.Engine.KEY_PARAM_RATE)) {
                                    speechRate = speechItem.mParams.get(i+1);
                                } else if (param.equals(TextToSpeech.Engine.KEY_PARAM_LANGUAGE)){
                                    language = speechItem.mParams.get(i+1);
                                } else if (param.equals(TextToSpeech.Engine.KEY_PARAM_COUNTRY)){
                                    country = speechItem.mParams.get(i+1);
                                } else if (param.equals(TextToSpeech.Engine.KEY_PARAM_VARIANT)){
                                    variant = speechItem.mParams.get(i+1);
                                } else if (param.equals(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID)){
                                    utteranceId = speechItem.mParams.get(i+1);
                                } else if (param.equals(TextToSpeech.Engine.KEY_PARAM_STREAM)) {
                                    try {
                                        streamType
                                                = Integer.parseInt(speechItem.mParams.get(i + 1));
                                    } catch (NumberFormatException e) {
                                        streamType = DEFAULT_STREAM_TYPE;
                                    }
                                }
                            }
                        }
                    }
                    // Only do the synthesis if it has not been killed by a subsequent utterance.
                    if (mKillList.get(speechItem) == null) {
                        if (language.length() > 0){
                            setLanguage("", language, country, variant);
                        }
                        if (speechRate.length() > 0){
                            setSpeechRate("", Integer.parseInt(speechRate));
                        }
                        try {
                            sNativeSynth.speak(speechItem.mText, streamType);
                        } catch (NullPointerException e) {
                            // synth will become null during onDestroy()
                            Log.v("TtsService", " null synth, can't speak");
                        }
                    }
                } catch (InterruptedException e) {
                    Log.e("TtsService", "TTS speakInternalOnly(): tryLock interrupted");
                    e.printStackTrace();
                } finally {
                    // This check is needed because finally will always run;
                    // even if the
                    // method returns somewhere in the try block.
                    if (utteranceId.length() > 0){
                        dispatchUtteranceCompletedCallback(utteranceId, speechItem.mCallingApp);
                    }
                    if (synthAvailable) {
                        synthesizerLock.unlock();
                    }
                    processSpeechQueue();
                }
            }
        }
        Thread synth = (new Thread(new SynthThread()));
        //synth.setPriority(Thread.MIN_PRIORITY);
        synth.start();
    }

    private void synthToFileInternalOnly(final SpeechItem speechItem) {
        class SynthThread implements Runnable {
            public void run() {
                boolean synthAvailable = false;
                String utteranceId = "";
                Log.i("TtsService", "Synthesizing to " + speechItem.mFilename);
                try {
                    synthAvailable = synthesizerLock.tryLock();
                    if (!synthAvailable) {
                        Thread.sleep(100);
                        Thread synth = (new Thread(new SynthThread()));
                        //synth.setPriority(Thread.MIN_PRIORITY);
                        synth.start();
                        return;
                    }
                    String language = "";
                    String country = "";
                    String variant = "";
                    String speechRate = "";
                    if (speechItem.mParams != null){
                        for (int i = 0; i < speechItem.mParams.size() - 1; i = i + 2){
                            String param = speechItem.mParams.get(i);
                            if (param != null) {
                                if (param.equals(TextToSpeech.Engine.KEY_PARAM_RATE)) {
                                    speechRate = speechItem.mParams.get(i+1);
                                } else if (param.equals(TextToSpeech.Engine.KEY_PARAM_LANGUAGE)){
                                    language = speechItem.mParams.get(i+1);
                                } else if (param.equals(TextToSpeech.Engine.KEY_PARAM_COUNTRY)){
                                    country = speechItem.mParams.get(i+1);
                                } else if (param.equals(TextToSpeech.Engine.KEY_PARAM_VARIANT)){
                                    variant = speechItem.mParams.get(i+1);
                                } else if (param.equals(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID)){
                                    utteranceId = speechItem.mParams.get(i+1);
                                }
                            }
                        }
                    }
                    // Only do the synthesis if it has not been killed by a subsequent utterance.
                    if (mKillList.get(speechItem) == null){
                        if (language.length() > 0){
                            setLanguage("", language, country, variant);
                        }
                        if (speechRate.length() > 0){
                            setSpeechRate("", Integer.parseInt(speechRate));
                        }
                        try {
                            sNativeSynth.synthesizeToFile(speechItem.mText, speechItem.mFilename);
                        } catch (NullPointerException e) {
                            // synth will become null during onDestroy()
                            Log.v("TtsService", " null synth, can't synthesize to file");
                        }
                    }
                } catch (InterruptedException e) {
                    Log.e("TtsService", "TTS synthToFileInternalOnly(): tryLock interrupted");
                    e.printStackTrace();
                } finally {
                    // This check is needed because finally will always run;
                    // even if the
                    // method returns somewhere in the try block.
                    if (utteranceId.length() > 0){
                        dispatchUtteranceCompletedCallback(utteranceId, speechItem.mCallingApp);
                    }
                    if (synthAvailable) {
                        synthesizerLock.unlock();
                    }
                    processSpeechQueue();
                }
            }
        }
        Thread synth = (new Thread(new SynthThread()));
        //synth.setPriority(Thread.MIN_PRIORITY);
        synth.start();
    }

    private SoundResource getSoundResource(SpeechItem speechItem) {
        SoundResource sr = null;
        String text = speechItem.mText;
        if (speechItem.mType == SpeechItem.SILENCE) {
            // Do nothing if this is just silence
        } else if (speechItem.mType == SpeechItem.EARCON) {
            sr = mEarcons.get(text);
        } else {
            sr = mUtterances.get(text);
        }
        return sr;
    }

    private void broadcastTtsQueueProcessingCompleted(){
        Intent i = new Intent(TextToSpeech.ACTION_TTS_QUEUE_PROCESSING_COMPLETED);
        sendBroadcast(i);
    }


    private void dispatchUtteranceCompletedCallback(String utteranceId, String packageName) {
        ITtsCallback cb = mCallbacksMap.get(packageName);
        if (cb == null){
            return;
        }
        Log.i("TtsService", "TTS callback: dispatch started");
        // Broadcast to all clients the new value.
        final int N = mCallbacks.beginBroadcast();
        try {
            cb.utteranceCompleted(utteranceId);
        } catch (RemoteException e) {
            // The RemoteCallbackList will take care of removing
            // the dead object for us.
        }
        mCallbacks.finishBroadcast();
        Log.i("TtsService", "TTS callback: dispatch completed to " + N);
    }

    private SpeechItem splitCurrentTextIfNeeded(SpeechItem currentSpeechItem){
        if (currentSpeechItem.mText.length() < MAX_SPEECH_ITEM_CHAR_LENGTH){
            return currentSpeechItem;
        } else {
            String callingApp = currentSpeechItem.mCallingApp;
            ArrayList<SpeechItem> splitItems = new ArrayList<SpeechItem>();
            int start = 0;
            int end = start + MAX_SPEECH_ITEM_CHAR_LENGTH - 1;
            String splitText;
            SpeechItem splitItem;
            while (end < currentSpeechItem.mText.length()){
                splitText = currentSpeechItem.mText.substring(start, end);
                splitItem = new SpeechItem(callingApp, splitText, null, SpeechItem.TEXT);
                splitItems.add(splitItem);
                start = end;
                end = start + MAX_SPEECH_ITEM_CHAR_LENGTH - 1;
            }
            splitText = currentSpeechItem.mText.substring(start);
            splitItem = new SpeechItem(callingApp, splitText, null, SpeechItem.TEXT);
            splitItems.add(splitItem);
            mSpeechQueue.remove(0);
            for (int i = splitItems.size() - 1; i >= 0; i--){
                mSpeechQueue.add(0, splitItems.get(i));
            }
            return mSpeechQueue.get(0);
        }
    }

    private void processSpeechQueue() {
        boolean speechQueueAvailable = false;
        try {
            speechQueueAvailable =
                    speechQueueLock.tryLock(SPEECHQUEUELOCK_TIMEOUT, TimeUnit.MILLISECONDS);
            if (!speechQueueAvailable) {
                Log.e("TtsService", "processSpeechQueue - Speech queue is unavailable.");
                return;
            }
            if (mSpeechQueue.size() < 1) {
                mIsSpeaking = false;
                broadcastTtsQueueProcessingCompleted();
                return;
            }

            mCurrentSpeechItem = mSpeechQueue.get(0);
            mIsSpeaking = true;
            SoundResource sr = getSoundResource(mCurrentSpeechItem);
            // Synth speech as needed - synthesizer should call
            // processSpeechQueue to continue running the queue
            Log.i("TtsService", "TTS processing: " + mCurrentSpeechItem.mText);
            if (sr == null) {
                if (mCurrentSpeechItem.mType == SpeechItem.TEXT) {
                    mCurrentSpeechItem = splitCurrentTextIfNeeded(mCurrentSpeechItem);
                    speakInternalOnly(mCurrentSpeechItem);
                } else if (mCurrentSpeechItem.mType == SpeechItem.TEXT_TO_FILE) {
                    synthToFileInternalOnly(mCurrentSpeechItem);
                } else {
                    // This is either silence or an earcon that was missing
                    silence(mCurrentSpeechItem);
                }
            } else {
                cleanUpPlayer();
                if (sr.mSourcePackageName == PKGNAME) {
                    // Utterance is part of the TTS library
                    mPlayer = MediaPlayer.create(this, sr.mResId);
                } else if (sr.mSourcePackageName != null) {
                    // Utterance is part of the app calling the library
                    Context ctx;
                    try {
                        ctx = this.createPackageContext(sr.mSourcePackageName, 0);
                    } catch (NameNotFoundException e) {
                        e.printStackTrace();
                        mSpeechQueue.remove(0); // Remove it from the queue and
                        // move on
                        mIsSpeaking = false;
                        return;
                    }
                    mPlayer = MediaPlayer.create(ctx, sr.mResId);
                } else {
                    // Utterance is coming from a file
                    mPlayer = MediaPlayer.create(this, Uri.parse(sr.mFilename));
                }

                // Check if Media Server is dead; if it is, clear the queue and
                // give up for now - hopefully, it will recover itself.
                if (mPlayer == null) {
                    mSpeechQueue.clear();
                    mIsSpeaking = false;
                    return;
                }
                mPlayer.setOnCompletionListener(this);
                try {
                    mPlayer.setAudioStreamType(getStreamTypeFromParams(mCurrentSpeechItem.mParams));
                    mPlayer.start();
                } catch (IllegalStateException e) {
                    mSpeechQueue.clear();
                    mIsSpeaking = false;
                    cleanUpPlayer();
                    return;
                }
            }
            if (mSpeechQueue.size() > 0) {
                mSpeechQueue.remove(0);
            }
        } catch (InterruptedException e) {
          Log.e("TtsService", "TTS processSpeechQueue: tryLock interrupted");
          e.printStackTrace();
        } finally {
            // This check is needed because finally will always run; even if the
            // method returns somewhere in the try block.
            if (speechQueueAvailable) {
                speechQueueLock.unlock();
            }
        }
    }

    private int getStreamTypeFromParams(ArrayList<String> paramList) {
        int streamType = DEFAULT_STREAM_TYPE;
        if (paramList == null) {
            return streamType;
        }
        for (int i = 0; i < paramList.size() - 1; i = i + 2) {
            String param = paramList.get(i);
            if ((param != null) && (param.equals(TextToSpeech.Engine.KEY_PARAM_STREAM))) {
                try {
                    streamType = Integer.parseInt(paramList.get(i + 1));
                } catch (NumberFormatException e) {
                    streamType = DEFAULT_STREAM_TYPE;
                }
            }
        }
        return streamType;
    }

    private void cleanUpPlayer() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
        }
    }

    /**
     * Synthesizes the given text to a file using the specified parameters.
     *
     * @param text
     *            The String of text that should be synthesized
     * @param params
     *            An ArrayList of parameters. The first element of this array
     *            controls the type of voice to use.
     * @param filename
     *            The string that gives the full output filename; it should be
     *            something like "/sdcard/myappsounds/mysound.wav".
     * @return A boolean that indicates if the synthesis succeeded
     */
    private boolean synthesizeToFile(String callingApp, String text, ArrayList<String> params,
            String filename) {
        // Don't allow a filename that is too long
        if (filename.length() > MAX_FILENAME_LENGTH) {
            return false;
        }
        // Don't allow anything longer than the max text length; since this
        // is synthing to a file, don't even bother splitting it.
        if (text.length() >= MAX_SPEECH_ITEM_CHAR_LENGTH){
            return false;
        }
        mSpeechQueue.add(new SpeechItem(callingApp, text, params, SpeechItem.TEXT_TO_FILE, filename));
        if (!mIsSpeaking) {
            processSpeechQueue();
        }
        return true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (ACTION.equals(intent.getAction())) {
            for (String category : intent.getCategories()) {
                if (category.equals(CATEGORY)) {
                    return mBinder;
                }
            }
        }
        return null;
    }

    private final android.speech.tts.ITts.Stub mBinder = new Stub() {

        public int registerCallback(String packageName, ITtsCallback cb) {
            if (cb != null) {
                mCallbacks.register(cb);
                mCallbacksMap.put(packageName, cb);
                return TextToSpeech.SUCCESS;
            }
            return TextToSpeech.ERROR;
        }

        public int unregisterCallback(String packageName, ITtsCallback cb) {
            if (cb != null) {
                mCallbacksMap.remove(packageName);
                mCallbacks.unregister(cb);
                return TextToSpeech.SUCCESS;
            }
            return TextToSpeech.ERROR;
        }

        /**
         * Speaks the given text using the specified queueing mode and
         * parameters.
         *
         * @param text
         *            The text that should be spoken
         * @param queueMode
         *            TextToSpeech.TTS_QUEUE_FLUSH for no queue (interrupts all previous utterances)
         *            TextToSpeech.TTS_QUEUE_ADD for queued
         * @param params
         *            An ArrayList of parameters. The first element of this
         *            array controls the type of voice to use.
         */
        public int speak(String callingApp, String text, int queueMode, String[] params) {
            ArrayList<String> speakingParams = new ArrayList<String>();
            if (params != null) {
                speakingParams = new ArrayList<String>(Arrays.asList(params));
            }
            return mSelf.speak(callingApp, text, queueMode, speakingParams);
        }

        /**
         * Plays the earcon using the specified queueing mode and parameters.
         *
         * @param earcon
         *            The earcon that should be played
         * @param queueMode
         *            TextToSpeech.TTS_QUEUE_FLUSH for no queue (interrupts all previous utterances)
         *            TextToSpeech.TTS_QUEUE_ADD for queued
         * @param params
         *            An ArrayList of parameters.
         */
        public int playEarcon(String callingApp, String earcon, int queueMode, String[] params) {
            ArrayList<String> speakingParams = new ArrayList<String>();
            if (params != null) {
                speakingParams = new ArrayList<String>(Arrays.asList(params));
            }
            return mSelf.playEarcon(callingApp, earcon, queueMode, speakingParams);
        }

        /**
         * Plays the silence using the specified queueing mode and parameters.
         *
         * @param duration
         *            The duration of the silence that should be played
         * @param queueMode
         *            TextToSpeech.TTS_QUEUE_FLUSH for no queue (interrupts all previous utterances)
         *            TextToSpeech.TTS_QUEUE_ADD for queued
         * @param params
         *            An ArrayList of parameters.
         */
        public int playSilence(String callingApp, long duration, int queueMode, String[] params) {
            ArrayList<String> speakingParams = new ArrayList<String>();
            if (params != null) {
                speakingParams = new ArrayList<String>(Arrays.asList(params));
            }
            return mSelf.playSilence(callingApp, duration, queueMode, speakingParams);
        }

        /**
         * Stops all speech output and removes any utterances still in the
         * queue.
         */
        public int stop(String callingApp) {
            return mSelf.stop(callingApp);
        }

        /**
         * Returns whether or not the TTS is speaking.
         *
         * @return Boolean to indicate whether or not the TTS is speaking
         */
        public boolean isSpeaking() {
            return (mSelf.mIsSpeaking && (mSpeechQueue.size() < 1));
        }

        /**
         * Adds a sound resource to the TTS.
         *
         * @param text
         *            The text that should be associated with the sound resource
         * @param packageName
         *            The name of the package which has the sound resource
         * @param resId
         *            The resource ID of the sound within its package
         */
        public void addSpeech(String callingApp, String text, String packageName, int resId) {
            mSelf.addSpeech(callingApp, text, packageName, resId);
        }

        /**
         * Adds a sound resource to the TTS.
         *
         * @param text
         *            The text that should be associated with the sound resource
         * @param filename
         *            The filename of the sound resource. This must be a
         *            complete path like: (/sdcard/mysounds/mysoundbite.mp3).
         */
        public void addSpeechFile(String callingApp, String text, String filename) {
            mSelf.addSpeech(callingApp, text, filename);
        }

        /**
         * Adds a sound resource to the TTS as an earcon.
         *
         * @param earcon
         *            The text that should be associated with the sound resource
         * @param packageName
         *            The name of the package which has the sound resource
         * @param resId
         *            The resource ID of the sound within its package
         */
        public void addEarcon(String callingApp, String earcon, String packageName, int resId) {
            mSelf.addEarcon(callingApp, earcon, packageName, resId);
        }

        /**
         * Adds a sound resource to the TTS as an earcon.
         *
         * @param earcon
         *            The text that should be associated with the sound resource
         * @param filename
         *            The filename of the sound resource. This must be a
         *            complete path like: (/sdcard/mysounds/mysoundbite.mp3).
         */
        public void addEarconFile(String callingApp, String earcon, String filename) {
            mSelf.addEarcon(callingApp, earcon, filename);
        }

        /**
         * Sets the speech rate for the TTS. Note that this will only have an
         * effect on synthesized speech; it will not affect pre-recorded speech.
         *
         * @param speechRate
         *            The speech rate that should be used
         */
        public int setSpeechRate(String callingApp, int speechRate) {
            return mSelf.setSpeechRate(callingApp, speechRate);
        }

        /**
         * Sets the pitch for the TTS. Note that this will only have an
         * effect on synthesized speech; it will not affect pre-recorded speech.
         *
         * @param pitch
         *            The pitch that should be used for the synthesized voice
         */
        public int setPitch(String callingApp, int pitch) {
            return mSelf.setPitch(callingApp, pitch);
        }

        /**
         * Returns the level of support for the specified language.
         *
         * @param lang  the three letter ISO language code.
         * @param country  the three letter ISO country code.
         * @param variant  the variant code associated with the country and language pair.
         * @return one of TTS_LANG_NOT_SUPPORTED, TTS_LANG_MISSING_DATA, TTS_LANG_AVAILABLE,
         *      TTS_LANG_COUNTRY_AVAILABLE, TTS_LANG_COUNTRY_VAR_AVAILABLE as defined in
         *      android.speech.tts.TextToSpeech.
         */
        public int isLanguageAvailable(String lang, String country, String variant) {
            return mSelf.isLanguageAvailable(lang, country, variant);
        }

        /**
         * Returns the currently set language / country / variant strings representing the
         * language used by the TTS engine.
         * @return null is no language is set, or an array of 3 string containing respectively
         *      the language, country and variant.
         */
        public String[] getLanguage() {
            return mSelf.getLanguage();
        }

        /**
         * Sets the speech rate for the TTS, which affects the synthesized voice.
         *
         * @param lang  the three letter ISO language code.
         * @param country  the three letter ISO country code.
         * @param variant  the variant code associated with the country and language pair.
         */
        public int setLanguage(String callingApp, String lang, String country, String variant) {
            return mSelf.setLanguage(callingApp, lang, country, variant);
        }

        /**
         * Synthesizes the given text to a file using the specified
         * parameters.
         *
         * @param text
         *            The String of text that should be synthesized
         * @param params
         *            An ArrayList of parameters. The first element of this
         *            array controls the type of voice to use.
         * @param filename
         *            The string that gives the full output filename; it should
         *            be something like "/sdcard/myappsounds/mysound.wav".
         * @return A boolean that indicates if the synthesis succeeded
         */
        public boolean synthesizeToFile(String callingApp, String text, String[] params,
                String filename) {
            ArrayList<String> speakingParams = new ArrayList<String>();
            if (params != null) {
                speakingParams = new ArrayList<String>(Arrays.asList(params));
            }
            return mSelf.synthesizeToFile(callingApp, text, speakingParams, filename);
        }

    };

}
