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

        public SpeechItem(String text, ArrayList<String> params, int itemType) {
            mText = text;
            mParams = params;
            mType = itemType;
        }

        public SpeechItem(long silenceTime) {
            mDuration = silenceTime;
            mType = SILENCE;
        }

        public SpeechItem(String text, ArrayList<String> params, int itemType, String filename) {
            mText = text;
            mParams = params;
            mType = itemType;
            mFilename = filename;
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

    private static final int MAX_SPEECH_ITEM_CHAR_LENGTH = 4000;
    private static final int MAX_FILENAME_LENGTH = 250;

    private static final String ACTION = "android.intent.action.START_TTS_SERVICE";
    private static final String CATEGORY = "android.intent.category.TTS";
    private static final String PKGNAME = "android.tts";

    final RemoteCallbackList<android.speech.tts.ITtsCallback> mCallbacks = new RemoteCallbackList<ITtsCallback>();

    private Boolean mIsSpeaking;
    private ArrayList<SpeechItem> mSpeechQueue;
    private HashMap<String, SoundResource> mEarcons;
    private HashMap<String, SoundResource> mUtterances;
    private MediaPlayer mPlayer;
    private TtsService mSelf;

    private ContentResolver mResolver;

    private final ReentrantLock speechQueueLock = new ReentrantLock();
    private final ReentrantLock synthesizerLock = new ReentrantLock();

    private SynthProxy nativeSynth;
    @Override
    public void onCreate() {
        super.onCreate();
        //Log.i("TTS", "TTS starting");

        mResolver = getContentResolver();

        String soLibPath = "/system/lib/libttspico.so";
        nativeSynth = new SynthProxy(soLibPath);

        mSelf = this;
        mIsSpeaking = false;

        mEarcons = new HashMap<String, SoundResource>();
        mUtterances = new HashMap<String, SoundResource>();

        mSpeechQueue = new ArrayList<SpeechItem>();
        mPlayer = null;

        setDefaultSettings();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Don't hog the media player
        cleanUpPlayer();

        nativeSynth.shutdown();

        // Unregister all callbacks.
        mCallbacks.kill();
    }


    private void setDefaultSettings() {
        setLanguage(this.getDefaultLanguage(), getDefaultCountry(), getDefaultLocVariant());

        // speech rate
        setSpeechRate(getDefaultRate());
    }


    private boolean isDefaultEnforced() {
        return (android.provider.Settings.Secure.getInt(mResolver,
                    android.provider.Settings.Secure.TTS_USE_DEFAULTS,
                    TextToSpeech.Engine.FALLBACK_TTS_USE_DEFAULTS)
                == 1 );
    }


    private int getDefaultRate() {
        return android.provider.Settings.Secure.getInt(mResolver,
                android.provider.Settings.Secure.TTS_DEFAULT_RATE,
                TextToSpeech.Engine.FALLBACK_TTS_DEFAULT_RATE);
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


    private int setSpeechRate(int rate) {
        if (isDefaultEnforced()) {
            return nativeSynth.setSpeechRate(getDefaultRate());
        } else {
            return nativeSynth.setSpeechRate(rate);
        }
    }


    private int setPitch(int pitch) {
        return nativeSynth.setPitch(pitch);
    }


    private int isLanguageAvailable(String lang, String country, String variant) {
        //Log.v("TTS", "TtsService.isLanguageAvailable(" + lang + ", " + country + ", " +variant+")");
        return nativeSynth.isLanguageAvailable(lang, country, variant);
    }


    private String[] getLanguage() {
        return nativeSynth.getLanguage();
    }


    private int setLanguage(String lang, String country, String variant) {
        //Log.v("TTS", "TtsService.setLanguage(" + lang + ", " + country + ", " + variant + ")");
        if (isDefaultEnforced()) {
            return nativeSynth.setLanguage(getDefaultLanguage(), getDefaultCountry(),
                    getDefaultLocVariant());
        } else {
            return nativeSynth.setLanguage(lang, country, variant);
        }
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
    private void addSpeech(String text, String packageName, int resId) {
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
    private void addSpeech(String text, String filename) {
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
    private void addEarcon(String earcon, String packageName, int resId) {
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
    private void addEarcon(String earcon, String filename) {
        mEarcons.put(earcon, new SoundResource(filename));
    }

    /**
     * Speaks the given text using the specified queueing mode and parameters.
     *
     * @param text
     *            The text that should be spoken
     * @param queueMode
     *            0 for no queue (interrupts all previous utterances), 1 for
     *            queued
     * @param params
     *            An ArrayList of parameters. This is not implemented for all
     *            engines.
     */
    private int speak(String text, int queueMode, ArrayList<String> params) {
        if (queueMode == 0) {
            stop();
        }
        mSpeechQueue.add(new SpeechItem(text, params, SpeechItem.TEXT));
        if (!mIsSpeaking) {
            processSpeechQueue();
        }
        return TextToSpeech.TTS_SUCCESS;
    }

    /**
     * Plays the earcon using the specified queueing mode and parameters.
     *
     * @param earcon
     *            The earcon that should be played
     * @param queueMode
     *            0 for no queue (interrupts all previous utterances), 1 for
     *            queued
     * @param params
     *            An ArrayList of parameters. This is not implemented for all
     *            engines.
     */
    private int playEarcon(String earcon, int queueMode,
            ArrayList<String> params) {
        if (queueMode == 0) {
            stop();
        }
        mSpeechQueue.add(new SpeechItem(earcon, params, SpeechItem.EARCON));
        if (!mIsSpeaking) {
            processSpeechQueue();
        }
        return TextToSpeech.TTS_SUCCESS;
    }

    /**
     * Stops all speech output and removes any utterances still in the queue.
     */
    private int stop() {
        Log.i("TTS", "Stopping");
        mSpeechQueue.clear();

        int result = nativeSynth.stop();
        mIsSpeaking = false;
        if (mPlayer != null) {
            try {
                mPlayer.stop();
            } catch (IllegalStateException e) {
                // Do nothing, the player is already stopped.
            }
        }
        Log.i("TTS", "Stopped");
        return result;
    }

    public void onCompletion(MediaPlayer arg0) {
        processSpeechQueue();
    }

    private int playSilence(long duration, int queueMode,
            ArrayList<String> params) {
        if (queueMode == 0) {
            stop();
        }
        mSpeechQueue.add(new SpeechItem(duration));
        if (!mIsSpeaking) {
            processSpeechQueue();
        }
        return TextToSpeech.TTS_SUCCESS;
    }

    private void silence(final long duration) {
        class SilenceThread implements Runnable {
            public void run() {
                try {
                    Thread.sleep(duration);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    processSpeechQueue();
                }
            }
        }
        Thread slnc = (new Thread(new SilenceThread()));
        slnc.setPriority(Thread.MIN_PRIORITY);
        slnc.start();
    }

    private void speakInternalOnly(final String text,
            final ArrayList<String> params) {
        class SynthThread implements Runnable {
            public void run() {
                boolean synthAvailable = false;
                try {
                    synthAvailable = synthesizerLock.tryLock();
                    if (!synthAvailable) {
                        Thread.sleep(100);
                        Thread synth = (new Thread(new SynthThread()));
                        synth.setPriority(Thread.MIN_PRIORITY);
                        synth.start();
                        return;
                    }
                    if (params != null){
                        String language = "";
                        String country = "";
                        String variant = "";
                        for (int i = 0; i < params.size() - 1; i = i + 2){
                            String param = params.get(i);
                            if (param.equals(TextToSpeech.Engine.TTS_KEY_PARAM_RATE)){
                                setSpeechRate(Integer.parseInt(params.get(i+1)));
                            } else if (param.equals(TextToSpeech.Engine.TTS_KEY_PARAM_LANGUAGE)){
                                language = params.get(i+1);
                            } else if (param.equals(TextToSpeech.Engine.TTS_KEY_PARAM_COUNTRY)){
                                country = params.get(i+1);
                            } else if (param.equals(TextToSpeech.Engine.TTS_KEY_PARAM_VARIANT)){
                                variant = params.get(i+1);
                            }
                        }
                        if (language.length() > 0){
                            setLanguage(language, country, variant);
                        }
                    }
                    nativeSynth.speak(text);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    // This check is needed because finally will always run;
                    // even if the
                    // method returns somewhere in the try block.
                    if (synthAvailable) {
                        synthesizerLock.unlock();
                    }
                    processSpeechQueue();
                }
            }
        }
        Thread synth = (new Thread(new SynthThread()));
        synth.setPriority(Thread.MIN_PRIORITY);
        synth.start();
    }

    private void synthToFileInternalOnly(final String text,
            final ArrayList<String> params, final String filename) {
        class SynthThread implements Runnable {
            public void run() {
                Log.i("TTS", "Synthesizing to " + filename);
                boolean synthAvailable = false;
                try {
                    synthAvailable = synthesizerLock.tryLock();
                    if (!synthAvailable) {
                        Thread.sleep(100);
                        Thread synth = (new Thread(new SynthThread()));
                        synth.setPriority(Thread.MIN_PRIORITY);
                        synth.start();
                        return;
                    }
                    if (params != null){
                        String language = "";
                        String country = "";
                        String variant = "";
                        for (int i = 0; i < params.size() - 1; i = i + 2){
                            String param = params.get(i);
                            if (param.equals(TextToSpeech.Engine.TTS_KEY_PARAM_RATE)){
                                setSpeechRate(Integer.parseInt(params.get(i+1)));
                            } else if (param.equals(TextToSpeech.Engine.TTS_KEY_PARAM_LANGUAGE)){
                                language = params.get(i+1);
                            } else if (param.equals(TextToSpeech.Engine.TTS_KEY_PARAM_COUNTRY)){
                                country = params.get(i+1);
                            } else if (param.equals(TextToSpeech.Engine.TTS_KEY_PARAM_VARIANT)){
                                variant = params.get(i+1);
                            }
                        }
                        if (language.length() > 0){
                            setLanguage(language, country, variant);
                        }
                    }
                    nativeSynth.synthesizeToFile(text, filename);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    // This check is needed because finally will always run;
                    // even if the
                    // method returns somewhere in the try block.
                    if (synthAvailable) {
                        synthesizerLock.unlock();
                    }
                    processSpeechQueue();
                }
            }
        }
        Thread synth = (new Thread(new SynthThread()));
        synth.setPriority(Thread.MIN_PRIORITY);
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
        Intent i = new Intent(Intent.ACTION_TTS_QUEUE_PROCESSING_COMPLETED);
        sendBroadcast(i);
    }

    private void dispatchSpeechCompletedCallbacks(String mark) {
        Log.i("TTS callback", "dispatch started");
        // Broadcast to all clients the new value.
        final int N = mCallbacks.beginBroadcast();
        for (int i = 0; i < N; i++) {
            try {
                mCallbacks.getBroadcastItem(i).markReached(mark);
            } catch (RemoteException e) {
                // The RemoteCallbackList will take care of removing
                // the dead object for us.
            }
        }
        mCallbacks.finishBroadcast();
        Log.i("TTS callback", "dispatch completed to " + N);
    }

    private SpeechItem splitCurrentTextIfNeeded(SpeechItem currentSpeechItem){
        if (currentSpeechItem.mText.length() < MAX_SPEECH_ITEM_CHAR_LENGTH){
            return currentSpeechItem;
        } else {
            ArrayList<SpeechItem> splitItems = new ArrayList<SpeechItem>();
            int start = 0;
            int end = start + MAX_SPEECH_ITEM_CHAR_LENGTH - 1;
            String splitText;
            SpeechItem splitItem;
            while (end < currentSpeechItem.mText.length()){
                splitText = currentSpeechItem.mText.substring(start, end);
                splitItem = new SpeechItem(splitText, null, SpeechItem.TEXT);
                splitItems.add(splitItem);
                start = end;
                end = start + MAX_SPEECH_ITEM_CHAR_LENGTH - 1;
            }
            splitText = currentSpeechItem.mText.substring(start);
            splitItem = new SpeechItem(splitText, null, SpeechItem.TEXT);
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
            speechQueueAvailable = speechQueueLock.tryLock();
            if (!speechQueueAvailable) {
                return;
            }
            if (mSpeechQueue.size() < 1) {
                mIsSpeaking = false;
                broadcastTtsQueueProcessingCompleted();
                return;
            }

            SpeechItem currentSpeechItem = mSpeechQueue.get(0);
            mIsSpeaking = true;
            SoundResource sr = getSoundResource(currentSpeechItem);
            // Synth speech as needed - synthesizer should call
            // processSpeechQueue to continue running the queue
            Log.i("TTS processing: ", currentSpeechItem.mText);
            if (sr == null) {
                if (currentSpeechItem.mType == SpeechItem.TEXT) {
                    currentSpeechItem = splitCurrentTextIfNeeded(currentSpeechItem);
                    speakInternalOnly(currentSpeechItem.mText,
                            currentSpeechItem.mParams);
                } else if (currentSpeechItem.mType == SpeechItem.TEXT_TO_FILE) {
                    synthToFileInternalOnly(currentSpeechItem.mText,
                            currentSpeechItem.mParams, currentSpeechItem.mFilename);
                } else {
                    // This is either silence or an earcon that was missing
                    silence(currentSpeechItem.mDuration);
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
                        ctx = this.createPackageContext(sr.mSourcePackageName,
                                0);
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
        } finally {
            // This check is needed because finally will always run; even if the
            // method returns somewhere in the try block.
            if (speechQueueAvailable) {
                speechQueueLock.unlock();
            }
        }
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
    private boolean synthesizeToFile(String text, ArrayList<String> params,
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
        mSpeechQueue.add(new SpeechItem(text, params, SpeechItem.TEXT_TO_FILE, filename));
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

        public void registerCallback(ITtsCallback cb) {
            if (cb != null)
                mCallbacks.register(cb);
        }

        public void unregisterCallback(ITtsCallback cb) {
            if (cb != null)
                mCallbacks.unregister(cb);
        }

        /**
         * Speaks the given text using the specified queueing mode and
         * parameters.
         *
         * @param text
         *            The text that should be spoken
         * @param queueMode
         *            0 for no queue (interrupts all previous utterances), 1 for
         *            queued
         * @param params
         *            An ArrayList of parameters. The first element of this
         *            array controls the type of voice to use.
         */
        public int speak(String text, int queueMode, String[] params) {
            ArrayList<String> speakingParams = new ArrayList<String>();
            if (params != null) {
                speakingParams = new ArrayList<String>(Arrays.asList(params));
            }
            return mSelf.speak(text, queueMode, speakingParams);
        }

        /**
         * Plays the earcon using the specified queueing mode and parameters.
         *
         * @param earcon
         *            The earcon that should be played
         * @param queueMode
         *            0 for no queue (interrupts all previous utterances), 1 for
         *            queued
         * @param params
         *            An ArrayList of parameters.
         */
        public int playEarcon(String earcon, int queueMode, String[] params) {
            ArrayList<String> speakingParams = new ArrayList<String>();
            if (params != null) {
                speakingParams = new ArrayList<String>(Arrays.asList(params));
            }
            return mSelf.playEarcon(earcon, queueMode, speakingParams);
        }

        /**
         * Plays the silence using the specified queueing mode and parameters.
         *
         * @param duration
         *            The duration of the silence that should be played
         * @param queueMode
         *            0 for no queue (interrupts all previous utterances), 1 for
         *            queued
         * @param params
         *            An ArrayList of parameters.
         */
        public int playSilence(long duration, int queueMode, String[] params) {
            ArrayList<String> speakingParams = new ArrayList<String>();
            if (params != null) {
                speakingParams = new ArrayList<String>(Arrays.asList(params));
            }
            return mSelf.playSilence(duration, queueMode, speakingParams);
        }

        /**
         * Stops all speech output and removes any utterances still in the
         * queue.
         */
        public int stop() {
            return mSelf.stop();
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
        public void addSpeech(String text, String packageName, int resId) {
            mSelf.addSpeech(text, packageName, resId);
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
        public void addSpeechFile(String text, String filename) {
            mSelf.addSpeech(text, filename);
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
        public void addEarcon(String earcon, String packageName, int resId) {
            mSelf.addEarcon(earcon, packageName, resId);
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
        public void addEarconFile(String earcon, String filename) {
            mSelf.addEarcon(earcon, filename);
        }

        /**
         * Sets the speech rate for the TTS. Note that this will only have an
         * effect on synthesized speech; it will not affect pre-recorded speech.
         *
         * @param speechRate
         *            The speech rate that should be used
         */
        public int setSpeechRate(int speechRate) {
            return mSelf.setSpeechRate(speechRate);
        }

        /**
         * Sets the pitch for the TTS. Note that this will only have an
         * effect on synthesized speech; it will not affect pre-recorded speech.
         *
         * @param pitch
         *            The pitch that should be used for the synthesized voice
         */
        public int setPitch(int pitch) {
            return mSelf.setPitch(pitch);
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
        public int setLanguage(String lang, String country, String variant) {
            return mSelf.setLanguage(lang, country, variant);
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
        public boolean synthesizeToFile(String text, String[] params,
                String filename) {
            ArrayList<String> speakingParams = new ArrayList<String>();
            if (params != null) {
                speakingParams = new ArrayList<String>(Arrays.asList(params));
            }
            return mSelf.synthesizeToFile(text, speakingParams, filename);
        }

    };

}
