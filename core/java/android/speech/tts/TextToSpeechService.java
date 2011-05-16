/*
 * Copyright (C) 2011 The Android Open Source Project
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
package android.speech.tts;

import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.speech.tts.TextToSpeech.Engine;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;


/**
 * Abstract base class for TTS engine implementations.
 */
public abstract class TextToSpeechService extends Service {

    private static final boolean DBG = false;
    private static final String TAG = "TextToSpeechService";

    private static final int MAX_SPEECH_ITEM_CHAR_LENGTH = 4000;
    private static final String SYNTH_THREAD_NAME = "SynthThread";

    private SynthHandler mSynthHandler;

    private CallbackMap mCallbacks;

    @Override
    public void onCreate() {
        if (DBG) Log.d(TAG, "onCreate()");
        super.onCreate();

        SynthThread synthThread = new SynthThread();
        synthThread.start();
        mSynthHandler = new SynthHandler(synthThread.getLooper());

        mCallbacks = new CallbackMap();

        // Load default language
        onLoadLanguage(getDefaultLanguage(), getDefaultCountry(), getDefaultVariant());
    }

    @Override
    public void onDestroy() {
        if (DBG) Log.d(TAG, "onDestroy()");

        // Tell the synthesizer to stop
        mSynthHandler.quit();

        // Unregister all callbacks.
        mCallbacks.kill();

        super.onDestroy();
    }

    /**
     * Checks whether the engine supports a given language.
     *
     * Can be called on multiple threads.
     *
     * @param lang ISO-3 language code.
     * @param country ISO-3 country code. May be empty or null.
     * @param variant Language variant. May be empty or null.
     * @return Code indicating the support status for the locale.
     *         One of {@link TextToSpeech#LANG_AVAILABLE},
     *         {@link TextToSpeech#LANG_COUNTRY_AVAILABLE},
     *         {@link TextToSpeech#LANG_COUNTRY_VAR_AVAILABLE},
     *         {@link TextToSpeech#LANG_MISSING_DATA}
     *         {@link TextToSpeech#LANG_NOT_SUPPORTED}.
     */
    protected abstract int onIsLanguageAvailable(String lang, String country, String variant);

    /**
     * Returns the language, country and variant currently being used by the TTS engine.
     *
     * Can be called on multiple threads.
     *
     * @return A 3-element array, containing language (ISO 3-letter code),
     *         country (ISO 3-letter code) and variant used by the engine.
     *         The country and variant may be {@code ""}. If country is empty, then variant must
     *         be empty too.
     * @see Locale#getISO3Language()
     * @see Locale#getISO3Country()
     * @see Locale#getVariant()
     */
    protected abstract String[] onGetLanguage();

    /**
     * Notifies the engine that it should load a speech synthesis language. There is no guarantee
     * that this method is always called before the language is used for synthesis. It is merely
     * a hint to the engine that it will probably get some synthesis requests for this language
     * at some point in the future.
     *
     * Can be called on multiple threads.
     *
     * @param lang ISO-3 language code.
     * @param country ISO-3 country code. May be empty or null.
     * @param variant Language variant. May be empty or null.
     * @return Code indicating the support status for the locale.
     *         One of {@link TextToSpeech#LANG_AVAILABLE},
     *         {@link TextToSpeech#LANG_COUNTRY_AVAILABLE},
     *         {@link TextToSpeech#LANG_COUNTRY_VAR_AVAILABLE},
     *         {@link TextToSpeech#LANG_MISSING_DATA}
     *         {@link TextToSpeech#LANG_NOT_SUPPORTED}.
     */
    protected abstract int onLoadLanguage(String lang, String country, String variant);

    /**
     * Notifies the service that it should stop any in-progress speech synthesis.
     * This method can be called even if no speech synthesis is currently in progress.
     *
     * Can be called on multiple threads, but not on the synthesis thread.
     */
    protected abstract void onStop();

    /**
     * Tells the service to synthesize speech from the given text. This method should
     * block until the synthesis is finished.
     *
     * Called on the synthesis thread.
     *
     * @param request The synthesis request. The method should use the methods in the request
     *         object to communicate the results of the synthesis.
     */
    protected abstract void onSynthesizeText(SynthesisRequest request);

    private boolean areDefaultsEnforced() {
        return getSecureSettingInt(Settings.Secure.TTS_USE_DEFAULTS,
                TextToSpeech.Engine.USE_DEFAULTS) == 1;
    }

    private int getDefaultSpeechRate() {
        return getSecureSettingInt(Settings.Secure.TTS_DEFAULT_RATE, Engine.DEFAULT_RATE);
    }

    private String getDefaultLanguage() {
        return getSecureSettingString(Settings.Secure.TTS_DEFAULT_LANG,
                Locale.getDefault().getISO3Language());
    }

    private String getDefaultCountry() {
        return getSecureSettingString(Settings.Secure.TTS_DEFAULT_COUNTRY,
                Locale.getDefault().getISO3Country());
    }

    private String getDefaultVariant() {
        return getSecureSettingString(Settings.Secure.TTS_DEFAULT_VARIANT,
                Locale.getDefault().getVariant());
    }

    private int getSecureSettingInt(String name, int defaultValue) {
        return Settings.Secure.getInt(getContentResolver(), name, defaultValue);
    }

    private String getSecureSettingString(String name, String defaultValue) {
        String value = Settings.Secure.getString(getContentResolver(), name);
        return value != null ? value : defaultValue;
    }

    /**
     * Synthesizer thread. This thread is used to run {@link SynthHandler}.
     */
    private class SynthThread extends HandlerThread implements MessageQueue.IdleHandler {

        private boolean mFirstIdle = true;

        public SynthThread() {
            super(SYNTH_THREAD_NAME, android.os.Process.THREAD_PRIORITY_AUDIO);
        }

        @Override
        protected void onLooperPrepared() {
            getLooper().getQueue().addIdleHandler(this);
        }

        @Override
        public boolean queueIdle() {
            if (mFirstIdle) {
                mFirstIdle = false;
            } else {
                broadcastTtsQueueProcessingCompleted();
            }
            return true;
        }

        private void broadcastTtsQueueProcessingCompleted() {
            Intent i = new Intent(TextToSpeech.ACTION_TTS_QUEUE_PROCESSING_COMPLETED);
            if (DBG) Log.d(TAG, "Broadcasting: " + i);
            sendBroadcast(i);
        }
    }

    private class SynthHandler extends Handler {

        private SpeechItem mCurrentSpeechItem = null;

        public SynthHandler(Looper looper) {
            super(looper);
        }

        private void dispatchUtteranceCompleted(SpeechItem item) {
            String utteranceId = item.getUtteranceId();
            if (!TextUtils.isEmpty(utteranceId)) {
                mCallbacks.dispatchUtteranceCompleted(item.getCallingApp(), utteranceId);
            }
        }

        private synchronized SpeechItem getCurrentSpeechItem() {
            return mCurrentSpeechItem;
        }

        private synchronized SpeechItem setCurrentSpeechItem(SpeechItem speechItem) {
            SpeechItem old = mCurrentSpeechItem;
            mCurrentSpeechItem = speechItem;
            return old;
        }

        public boolean isSpeaking() {
            return getCurrentSpeechItem() != null;
        }

        public void quit() {
            // Don't process any more speech items
            getLooper().quit();
            // Stop the current speech item
            SpeechItem current = setCurrentSpeechItem(null);
            if (current != null) {
                current.stop();
            }
        }

        /**
         * Adds a speech item to the queue.
         *
         * Called on a service binder thread.
         */
        public int enqueueSpeechItem(int queueMode, final SpeechItem speechItem) {
            if (!speechItem.isValid()) {
                return TextToSpeech.ERROR;
            }
            // TODO: The old code also supported the undocumented queueMode == 2,
            // which clears out all pending items from the calling app, as well as all
            // non-file items from other apps.
            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                stop(speechItem.getCallingApp());
            }
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    setCurrentSpeechItem(speechItem);
                    if (speechItem.play() == TextToSpeech.SUCCESS) {
                        dispatchUtteranceCompleted(speechItem);
                    }
                    setCurrentSpeechItem(null);
                }
            };
            Message msg = Message.obtain(this, runnable);
            // The obj is used to remove all callbacks from the given app in stop(String).
            msg.obj = speechItem.getCallingApp();
            if (sendMessage(msg)) {
                return TextToSpeech.SUCCESS;
            } else {
                Log.w(TAG, "SynthThread has quit");
                return TextToSpeech.ERROR;
            }
        }

        /**
         * Stops all speech output and removes any utterances still in the queue for
         * the calling app.
         *
         * Called on a service binder thread.
         */
        public int stop(String callingApp) {
            if (TextUtils.isEmpty(callingApp)) {
                return TextToSpeech.ERROR;
            }
            removeCallbacksAndMessages(callingApp);
            SpeechItem current = setCurrentSpeechItem(null);
            if (current != null && TextUtils.equals(callingApp, current.getCallingApp())) {
                current.stop();
            }
            return TextToSpeech.SUCCESS;
        }
    }

    /**
     * An item in the synth thread queue.
     */
    private static abstract class SpeechItem {
        private final String mCallingApp;
        protected final Bundle mParams;
        private boolean mStarted = false;
        private boolean mStopped = false;

        public SpeechItem(String callingApp, Bundle params) {
            mCallingApp = callingApp;
            mParams = params;
        }

        public String getCallingApp() {
            return mCallingApp;
        }

        /**
         * Checker whether the item is valid. If this method returns false, the item should not
         * be played.
         */
        public abstract boolean isValid();

        /**
         * Plays the speech item. Blocks until playback is finished.
         * Must not be called more than once.
         *
         * Only called on the synthesis thread.
         *
         * @return {@link TextToSpeech#SUCCESS} or {@link TextToSpeech#ERROR}.
         */
        public int play() {
            synchronized (this) {
                if (mStarted) {
                    throw new IllegalStateException("play() called twice");
                }
                mStarted = true;
            }
            return playImpl();
        }

        /**
         * Stops the speech item.
         * Must not be called more than once.
         *
         * Can be called on multiple threads,  but not on the synthesis thread.
         */
        public void stop() {
            synchronized (this) {
                if (mStopped) {
                    throw new IllegalStateException("stop() called twice");
                }
                mStopped = true;
            }
            stopImpl();
        }

        protected abstract int playImpl();

        protected abstract void stopImpl();

        public int getStreamType() {
            return getIntParam(Engine.KEY_PARAM_STREAM, Engine.DEFAULT_STREAM);
        }

        public float getVolume() {
            return getFloatParam(Engine.KEY_PARAM_VOLUME, Engine.DEFAULT_VOLUME);
        }

        public float getPan() {
            return getFloatParam(Engine.KEY_PARAM_PAN, Engine.DEFAULT_PAN);
        }

        public String getUtteranceId() {
            return getStringParam(Engine.KEY_PARAM_UTTERANCE_ID, null);
        }

        protected String getStringParam(String key, String defaultValue) {
            return mParams == null ? defaultValue : mParams.getString(key, defaultValue);
        }

        protected int getIntParam(String key, int defaultValue) {
            return mParams == null ? defaultValue : mParams.getInt(key, defaultValue);
        }

        protected float getFloatParam(String key, float defaultValue) {
            return mParams == null ? defaultValue : mParams.getFloat(key, defaultValue);
        }
    }

    private class SynthesisSpeechItem extends SpeechItem {
        private final String mText;
        private SynthesisRequest mSynthesisRequest;

        public SynthesisSpeechItem(String callingApp, Bundle params, String text) {
            super(callingApp, params);
            mText = text;
        }

        public String getText() {
            return mText;
        }

        @Override
        public boolean isValid() {
            if (TextUtils.isEmpty(mText)) {
                Log.w(TAG, "Got empty text");
                return false;
            }
            if (mText.length() >= MAX_SPEECH_ITEM_CHAR_LENGTH){
                Log.w(TAG, "Text too long: " + mText.length() + " chars");
                return false;
            }
            return true;
        }

        @Override
        protected int playImpl() {
            SynthesisRequest synthesisRequest;
            synchronized (this) {
                mSynthesisRequest = createSynthesisRequest();
                synthesisRequest = mSynthesisRequest;
            }
            setRequestParams(synthesisRequest);
            TextToSpeechService.this.onSynthesizeText(synthesisRequest);
            return synthesisRequest.isDone() ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
        }

        protected SynthesisRequest createSynthesisRequest() {
            return new PlaybackSynthesisRequest(mText, mParams,
                    getStreamType(), getVolume(), getPan());
        }

        private void setRequestParams(SynthesisRequest request) {
            if (areDefaultsEnforced()) {
                request.setLanguage(getDefaultLanguage(), getDefaultCountry(), getDefaultVariant());
                request.setSpeechRate(getDefaultSpeechRate());
            } else {
                request.setLanguage(getLanguage(), getCountry(), getVariant());
                request.setSpeechRate(getSpeechRate());
            }
            request.setPitch(getPitch());
        }

        @Override
        protected void stopImpl() {
            SynthesisRequest synthesisRequest;
            synchronized (this) {
                synthesisRequest = mSynthesisRequest;
            }
            synthesisRequest.stop();
            TextToSpeechService.this.onStop();
        }

        public String getLanguage() {
            return getStringParam(Engine.KEY_PARAM_LANGUAGE, getDefaultLanguage());
        }

        private boolean hasLanguage() {
            return !TextUtils.isEmpty(getStringParam(Engine.KEY_PARAM_LANGUAGE, null));
        }

        private String getCountry() {
            if (!hasLanguage()) return getDefaultCountry();
            return getStringParam(Engine.KEY_PARAM_COUNTRY, "");
        }

        private String getVariant() {
            if (!hasLanguage()) return getDefaultVariant();
            return getStringParam(Engine.KEY_PARAM_VARIANT, "");
        }

        private int getSpeechRate() {
            return getIntParam(Engine.KEY_PARAM_RATE, getDefaultSpeechRate());
        }

        private int getPitch() {
            return getIntParam(Engine.KEY_PARAM_PITCH, Engine.DEFAULT_PITCH);
        }
    }

    private class SynthesisToFileSpeechItem extends SynthesisSpeechItem {
        private final File mFile;

        public SynthesisToFileSpeechItem(String callingApp, Bundle params, String text,
                File file) {
            super(callingApp, params, text);
            mFile = file;
        }

        @Override
        public boolean isValid() {
            if (!super.isValid()) {
                return false;
            }
            return checkFile(mFile);
        }

        @Override
        protected SynthesisRequest createSynthesisRequest() {
            return new FileSynthesisRequest(getText(), mParams, mFile);
        }

        /**
         * Checks that the given file can be used for synthesis output.
         */
        private boolean checkFile(File file) {
            try {
                if (file.exists()) {
                    Log.v(TAG, "File " + file + " exists, deleting.");
                    if (!file.delete()) {
                        Log.e(TAG, "Failed to delete " + file);
                        return false;
                    }
                }
                if (!file.createNewFile()) {
                    Log.e(TAG, "Can't create file " + file);
                    return false;
                }
                if (!file.delete()) {
                    Log.e(TAG, "Failed to delete " + file);
                    return false;
                }
                return true;
            } catch (IOException e) {
                Log.e(TAG, "Can't use " + file + " due to exception " + e);
                return false;
            }
        }
    }

    private class AudioSpeechItem extends SpeechItem {

        private final BlockingMediaPlayer mPlayer;

        public AudioSpeechItem(String callingApp, Bundle params, Uri uri) {
            super(callingApp, params);
            mPlayer = new BlockingMediaPlayer(TextToSpeechService.this, uri, getStreamType());
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        protected int playImpl() {
            return mPlayer.startAndWait() ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
        }

        @Override
        protected void stopImpl() {
            mPlayer.stop();
        }
    }

    private class SilenceSpeechItem extends SpeechItem {
        private final long mDuration;
        private final ConditionVariable mDone;

        public SilenceSpeechItem(String callingApp, Bundle params, long duration) {
            super(callingApp, params);
            mDuration = duration;
            mDone = new ConditionVariable();
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        protected int playImpl() {
            boolean aborted = mDone.block(mDuration);
            return aborted ? TextToSpeech.ERROR : TextToSpeech.SUCCESS;
        }

        @Override
        protected void stopImpl() {
            mDone.open();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        if (TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE.equals(intent.getAction())) {
            return mBinder;
        }
        return null;
    }

    /**
     * Binder returned from {@code #onBind(Intent)}. The methods in this class can be
     * called called from several different threads.
     */
    private final ITextToSpeechService.Stub mBinder = new ITextToSpeechService.Stub() {

        public int speak(String callingApp, String text, int queueMode, Bundle params) {
            SpeechItem item = new SynthesisSpeechItem(callingApp, params, text);
            return mSynthHandler.enqueueSpeechItem(queueMode, item);
        }

        public int synthesizeToFile(String callingApp, String text, String filename,
                Bundle params) {
            File file = new File(filename);
            SpeechItem item = new SynthesisToFileSpeechItem(callingApp, params, text, file);
            return mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_ADD, item);
        }

        public int playAudio(String callingApp, Uri audioUri, int queueMode, Bundle params) {
            SpeechItem item = new AudioSpeechItem(callingApp, params, audioUri);
            return mSynthHandler.enqueueSpeechItem(queueMode, item);
        }

        public int playSilence(String callingApp, long duration, int queueMode, Bundle params) {
            SpeechItem item = new SilenceSpeechItem(callingApp, params, duration);
            return mSynthHandler.enqueueSpeechItem(queueMode, item);
        }

        public boolean isSpeaking() {
            return mSynthHandler.isSpeaking();
        }

        public int stop(String callingApp) {
            return mSynthHandler.stop(callingApp);
        }

        public String[] getLanguage() {
            return onGetLanguage();
        }

        public int isLanguageAvailable(String lang, String country, String variant) {
            return onIsLanguageAvailable(lang, country, variant);
        }

        public int loadLanguage(String lang, String country, String variant) {
            return onLoadLanguage(lang, country, variant);
        }

        public void setCallback(String packageName, ITextToSpeechCallback cb) {
            mCallbacks.setCallback(packageName, cb);
        }
    };

    private class CallbackMap extends RemoteCallbackList<ITextToSpeechCallback> {

        private final HashMap<String, ITextToSpeechCallback> mAppToCallback
                = new HashMap<String, ITextToSpeechCallback>();

        public void setCallback(String packageName, ITextToSpeechCallback cb) {
            synchronized (mAppToCallback) {
                ITextToSpeechCallback old;
                if (cb != null) {
                    register(cb, packageName);
                    old = mAppToCallback.put(packageName, cb);
                } else {
                    old = mAppToCallback.remove(packageName);
                }
                if (old != null && old != cb) {
                    unregister(old);
                }
            }
        }

        public void dispatchUtteranceCompleted(String packageName, String utteranceId) {
            ITextToSpeechCallback cb;
            synchronized (mAppToCallback) {
                cb = mAppToCallback.get(packageName);
            }
            if (cb == null) return;
            try {
                cb.utteranceCompleted(utteranceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Callback failed: " + e);
            }
        }

        @Override
        public void onCallbackDied(ITextToSpeechCallback callback, Object cookie) {
            String packageName = (String) cookie;
            synchronized (mAppToCallback) {
                mAppToCallback.remove(packageName);
            }
            mSynthHandler.stop(packageName);
        }

        @Override
        public void kill() {
            synchronized (mAppToCallback) {
                mAppToCallback.clear();
                super.kill();
            }
        }

    }

}
