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
import android.os.Binder;
import android.os.Bundle;
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
import java.util.Set;


/**
 * Abstract base class for TTS engine implementations. The following methods
 * need to be implemented.
 *
 * <ul>
 *   <li>{@link #onIsLanguageAvailable}</li>
 *   <li>{@link #onLoadLanguage}</li>
 *   <li>{@link #onGetLanguage}</li>
 *   <li>{@link #onSynthesizeText}</li>
 *   <li>{@link #onStop}</li>
 * </ul>
 *
 * The first three deal primarily with language management, and are used to
 * query the engine for it's support for a given language and indicate to it
 * that requests in a given language are imminent.
 *
 * {@link #onSynthesizeText} is central to the engine implementation. The
 * implementation should synthesize text as per the request parameters and
 * return synthesized data via the supplied callback. This class and its helpers
 * will then consume that data, which might mean queueing it for playback or writing
 * it to a file or similar. All calls to this method will be on a single
 * thread, which will be different from the main thread of the service. Synthesis
 * must be synchronous which means the engine must NOT hold on the callback or call
 * any methods on it after the method returns
 *
 * {@link #onStop} tells the engine that it should stop all ongoing synthesis, if
 * any. Any pending data from the current synthesis will be discarded.
 *
 */
public abstract class TextToSpeechService extends Service {

    private static final boolean DBG = false;
    private static final String TAG = "TextToSpeechService";

    private static final int MAX_SPEECH_ITEM_CHAR_LENGTH = 4000;
    private static final String SYNTH_THREAD_NAME = "SynthThread";

    private SynthHandler mSynthHandler;
    // A thread and it's associated handler for playing back any audio
    // associated with this TTS engine. Will handle all requests except synthesis
    // to file requests, which occur on the synthesis thread.
    private AudioPlaybackHandler mAudioPlaybackHandler;
    private TtsEngines mEngineHelper;

    private CallbackMap mCallbacks;
    private String mPackageName;

    @Override
    public void onCreate() {
        if (DBG) Log.d(TAG, "onCreate()");
        super.onCreate();

        SynthThread synthThread = new SynthThread();
        synthThread.start();
        mSynthHandler = new SynthHandler(synthThread.getLooper());

        mAudioPlaybackHandler = new AudioPlaybackHandler();
        mAudioPlaybackHandler.start();

        mEngineHelper = new TtsEngines(this);

        mCallbacks = new CallbackMap();

        mPackageName = getApplicationInfo().packageName;

        String[] defaultLocale = getSettingsLocale();
        // Load default language
        onLoadLanguage(defaultLocale[0], defaultLocale[1], defaultLocale[2]);
    }

    @Override
    public void onDestroy() {
        if (DBG) Log.d(TAG, "onDestroy()");

        // Tell the synthesizer to stop
        mSynthHandler.quit();
        // Tell the audio playback thread to stop.
        mAudioPlaybackHandler.quit();
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
     * @param request The synthesis request.
     * @param callback The callback the the engine must use to make data available for
     *         playback or for writing to a file.
     */
    protected abstract void onSynthesizeText(SynthesisRequest request,
            SynthesisCallback callback);

    /**
     * Queries the service for a set of features supported for a given language.
     *
     * @param lang ISO-3 language code.
     * @param country ISO-3 country code. May be empty or null.
     * @param variant Language variant. May be empty or null.
     * @return A list of features supported for the given language.
     */
    protected Set<String> onGetFeaturesForLanguage(String lang, String country, String variant) {
        return null;
    }

    private int getDefaultSpeechRate() {
        return getSecureSettingInt(Settings.Secure.TTS_DEFAULT_RATE, Engine.DEFAULT_RATE);
    }

    private String[] getSettingsLocale() {
        final String locale = mEngineHelper.getLocalePrefForEngine(mPackageName);
        return TtsEngines.parseLocalePref(locale);
    }

    private int getSecureSettingInt(String name, int defaultValue) {
        return Settings.Secure.getInt(getContentResolver(), name, defaultValue);
    }

    /**
     * Synthesizer thread. This thread is used to run {@link SynthHandler}.
     */
    private class SynthThread extends HandlerThread implements MessageQueue.IdleHandler {

        private boolean mFirstIdle = true;

        public SynthThread() {
            super(SYNTH_THREAD_NAME, android.os.Process.THREAD_PRIORITY_DEFAULT);
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

        private synchronized SpeechItem getCurrentSpeechItem() {
            return mCurrentSpeechItem;
        }

        private synchronized SpeechItem setCurrentSpeechItem(SpeechItem speechItem) {
            SpeechItem old = mCurrentSpeechItem;
            mCurrentSpeechItem = speechItem;
            return old;
        }

        private synchronized SpeechItem maybeRemoveCurrentSpeechItem(Object callerIdentity) {
            if (mCurrentSpeechItem != null &&
                    mCurrentSpeechItem.getCallerIdentity() == callerIdentity) {
                SpeechItem current = mCurrentSpeechItem;
                mCurrentSpeechItem = null;
                return current;
            }

            return null;
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

            // The AudioPlaybackHandler will be destroyed by the caller.
        }

        /**
         * Adds a speech item to the queue.
         *
         * Called on a service binder thread.
         */
        public int enqueueSpeechItem(int queueMode, final SpeechItem speechItem) {
            if (!speechItem.isValid()) {
                speechItem.dispatchOnError();
                return TextToSpeech.ERROR;
            }

            if (queueMode == TextToSpeech.QUEUE_FLUSH) {
                stopForApp(speechItem.getCallerIdentity());
            } else if (queueMode == TextToSpeech.QUEUE_DESTROY) {
                stopAll();
            }
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    setCurrentSpeechItem(speechItem);
                    speechItem.play();
                    setCurrentSpeechItem(null);
                }
            };
            Message msg = Message.obtain(this, runnable);
            // The obj is used to remove all callbacks from the given app in
            // stopForApp(String).
            //
            // Note that this string is interned, so the == comparison works.
            msg.obj = speechItem.getCallerIdentity();
            if (sendMessage(msg)) {
                return TextToSpeech.SUCCESS;
            } else {
                Log.w(TAG, "SynthThread has quit");
                speechItem.dispatchOnError();
                return TextToSpeech.ERROR;
            }
        }

        /**
         * Stops all speech output and removes any utterances still in the queue for
         * the calling app.
         *
         * Called on a service binder thread.
         */
        public int stopForApp(Object callerIdentity) {
            if (callerIdentity == null) {
                return TextToSpeech.ERROR;
            }

            removeCallbacksAndMessages(callerIdentity);
            // This stops writing data to the file / or publishing
            // items to the audio playback handler.
            //
            // Note that the current speech item must be removed only if it
            // belongs to the callingApp, else the item will be "orphaned" and
            // not stopped correctly if a stop request comes along for the item
            // from the app it belongs to.
            SpeechItem current = maybeRemoveCurrentSpeechItem(callerIdentity);
            if (current != null) {
                current.stop();
            }

            // Remove any enqueued audio too.
            mAudioPlaybackHandler.stopForApp(callerIdentity);

            return TextToSpeech.SUCCESS;
        }

        public int stopAll() {
            // Stop the current speech item unconditionally.
            SpeechItem current = setCurrentSpeechItem(null);
            if (current != null) {
                current.stop();
            }
            // Remove all other items from the queue.
            removeCallbacksAndMessages(null);
            // Remove all pending playback as well.
            mAudioPlaybackHandler.stop();

            return TextToSpeech.SUCCESS;
        }
    }

    interface UtteranceProgressDispatcher {
        public void dispatchOnDone();
        public void dispatchOnStart();
        public void dispatchOnError();
    }

    /**
     * An item in the synth thread queue.
     */
    private abstract class SpeechItem implements UtteranceProgressDispatcher {
        private final Object mCallerIdentity;
        protected final Bundle mParams;
        private final int mCallerUid;
        private final int mCallerPid;
        private boolean mStarted = false;
        private boolean mStopped = false;

        public SpeechItem(Object caller, int callerUid, int callerPid, Bundle params) {
            mCallerIdentity = caller;
            mParams = params;
            mCallerUid = callerUid;
            mCallerPid = callerPid;
        }

        public Object getCallerIdentity() {
            return mCallerIdentity;
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

        @Override
        public void dispatchOnDone() {
            final String utteranceId = getUtteranceId();
            if (utteranceId != null) {
                mCallbacks.dispatchOnDone(getCallerIdentity(), utteranceId);
            }
        }

        @Override
        public void dispatchOnStart() {
            final String utteranceId = getUtteranceId();
            if (utteranceId != null) {
                mCallbacks.dispatchOnStart(getCallerIdentity(), utteranceId);
            }
        }

        @Override
        public void dispatchOnError() {
            final String utteranceId = getUtteranceId();
            if (utteranceId != null) {
                mCallbacks.dispatchOnError(getCallerIdentity(), utteranceId);
            }
        }

        public int getCallerUid() {
            return mCallerUid;
        }

        public int getCallerPid() {
            return mCallerPid;
        }

        protected synchronized boolean isStopped() {
             return mStopped;
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

    class SynthesisSpeechItem extends SpeechItem {
        // Never null.
        private final String mText;
        private final SynthesisRequest mSynthesisRequest;
        private final String[] mDefaultLocale;
        // Non null after synthesis has started, and all accesses
        // guarded by 'this'.
        private AbstractSynthesisCallback mSynthesisCallback;
        private final EventLogger mEventLogger;

        public SynthesisSpeechItem(Object callerIdentity, int callerUid, int callerPid,
                Bundle params, String text) {
            super(callerIdentity, callerUid, callerPid, params);
            mText = text;
            mSynthesisRequest = new SynthesisRequest(mText, mParams);
            mDefaultLocale = getSettingsLocale();
            setRequestParams(mSynthesisRequest);
            mEventLogger = new EventLogger(mSynthesisRequest, callerUid, callerPid,
                    mPackageName);
        }

        public String getText() {
            return mText;
        }

        @Override
        public boolean isValid() {
            if (mText == null) {
                Log.wtf(TAG, "Got null text");
                return false;
            }
            if (mText.length() >= MAX_SPEECH_ITEM_CHAR_LENGTH) {
                Log.w(TAG, "Text too long: " + mText.length() + " chars");
                return false;
            }
            return true;
        }

        @Override
        protected int playImpl() {
            AbstractSynthesisCallback synthesisCallback;
            mEventLogger.onRequestProcessingStart();
            synchronized (this) {
                // stop() might have been called before we enter this
                // synchronized block.
                if (isStopped()) {
                    return TextToSpeech.ERROR;
                }
                mSynthesisCallback = createSynthesisCallback();
                synthesisCallback = mSynthesisCallback;
            }
            TextToSpeechService.this.onSynthesizeText(mSynthesisRequest, synthesisCallback);
            return synthesisCallback.isDone() ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
        }

        protected AbstractSynthesisCallback createSynthesisCallback() {
            return new PlaybackSynthesisCallback(getStreamType(), getVolume(), getPan(),
                    mAudioPlaybackHandler, this, getCallerIdentity(), mEventLogger);
        }

        private void setRequestParams(SynthesisRequest request) {
            request.setLanguage(getLanguage(), getCountry(), getVariant());
            request.setSpeechRate(getSpeechRate());

            request.setPitch(getPitch());
        }

        @Override
        protected void stopImpl() {
            AbstractSynthesisCallback synthesisCallback;
            synchronized (this) {
                synthesisCallback = mSynthesisCallback;
            }
            if (synthesisCallback != null) {
                // If the synthesis callback is null, it implies that we haven't
                // entered the synchronized(this) block in playImpl which in
                // turn implies that synthesis would not have started.
                synthesisCallback.stop();
                TextToSpeechService.this.onStop();
            }
        }

        public String getLanguage() {
            return getStringParam(Engine.KEY_PARAM_LANGUAGE, mDefaultLocale[0]);
        }

        private boolean hasLanguage() {
            return !TextUtils.isEmpty(getStringParam(Engine.KEY_PARAM_LANGUAGE, null));
        }

        private String getCountry() {
            if (!hasLanguage()) return mDefaultLocale[1];
            return getStringParam(Engine.KEY_PARAM_COUNTRY, "");
        }

        private String getVariant() {
            if (!hasLanguage()) return mDefaultLocale[2];
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

        public SynthesisToFileSpeechItem(Object callerIdentity, int callerUid, int callerPid,
                Bundle params, String text,
                File file) {
            super(callerIdentity, callerUid, callerPid, params, text);
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
        protected AbstractSynthesisCallback createSynthesisCallback() {
            return new FileSynthesisCallback(mFile);
        }

        @Override
        protected int playImpl() {
            dispatchOnStart();
            int status = super.playImpl();
            if (status == TextToSpeech.SUCCESS) {
                dispatchOnDone();
            } else {
                dispatchOnError();
            }
            return status;
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

        public AudioSpeechItem(Object callerIdentity, int callerUid, int callerPid,
                Bundle params, Uri uri) {
            super(callerIdentity, callerUid, callerPid, params);
            mPlayer = new BlockingMediaPlayer(TextToSpeechService.this, uri, getStreamType());
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        protected int playImpl() {
            mAudioPlaybackHandler.enqueue(new AudioPlaybackQueueItem(
                    this, getCallerIdentity(), mPlayer));
            return TextToSpeech.SUCCESS;
        }

        @Override
        protected void stopImpl() {
            // Do nothing.
        }
    }

    private class SilenceSpeechItem extends SpeechItem {
        private final long mDuration;

        public SilenceSpeechItem(Object callerIdentity, int callerUid, int callerPid,
                Bundle params, long duration) {
            super(callerIdentity, callerUid, callerPid, params);
            mDuration = duration;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        protected int playImpl() {
            mAudioPlaybackHandler.enqueue(new SilencePlaybackQueueItem(
                    this, getCallerIdentity(), mDuration));
            return TextToSpeech.SUCCESS;
        }

        @Override
        protected void stopImpl() {
            // Do nothing, handled by AudioPlaybackHandler#stopForApp
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
    // NOTE: All calls that are passed in a calling app are interned so that
    // they can be used as message objects (which are tested for equality using ==).
    private final ITextToSpeechService.Stub mBinder = new ITextToSpeechService.Stub() {
        @Override
        public int speak(IBinder caller, String text, int queueMode, Bundle params) {
            if (!checkNonNull(caller, text, params)) {
                return TextToSpeech.ERROR;
            }

            SpeechItem item = new SynthesisSpeechItem(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), params, text);
            return mSynthHandler.enqueueSpeechItem(queueMode, item);
        }

        @Override
        public int synthesizeToFile(IBinder caller, String text, String filename,
                Bundle params) {
            if (!checkNonNull(caller, text, filename, params)) {
                return TextToSpeech.ERROR;
            }

            File file = new File(filename);
            SpeechItem item = new SynthesisToFileSpeechItem(caller, Binder.getCallingUid(),
                    Binder.getCallingPid(), params, text, file);
            return mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_ADD, item);
        }

        @Override
        public int playAudio(IBinder caller, Uri audioUri, int queueMode, Bundle params) {
            if (!checkNonNull(caller, audioUri, params)) {
                return TextToSpeech.ERROR;
            }

            SpeechItem item = new AudioSpeechItem(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), params, audioUri);
            return mSynthHandler.enqueueSpeechItem(queueMode, item);
        }

        @Override
        public int playSilence(IBinder caller, long duration, int queueMode, Bundle params) {
            if (!checkNonNull(caller, params)) {
                return TextToSpeech.ERROR;
            }

            SpeechItem item = new SilenceSpeechItem(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), params, duration);
            return mSynthHandler.enqueueSpeechItem(queueMode, item);
        }

        @Override
        public boolean isSpeaking() {
            return mSynthHandler.isSpeaking() || mAudioPlaybackHandler.isSpeaking();
        }

        @Override
        public int stop(IBinder caller) {
            if (!checkNonNull(caller)) {
                return TextToSpeech.ERROR;
            }

            return mSynthHandler.stopForApp(caller);
        }

        @Override
        public String[] getLanguage() {
            return onGetLanguage();
        }

        /*
         * If defaults are enforced, then no language is "available" except
         * perhaps the default language selected by the user.
         */
        @Override
        public int isLanguageAvailable(String lang, String country, String variant) {
            if (!checkNonNull(lang)) {
                return TextToSpeech.ERROR;
            }

            return onIsLanguageAvailable(lang, country, variant);
        }

        @Override
        public String[] getFeaturesForLanguage(String lang, String country, String variant) {
            Set<String> features = onGetFeaturesForLanguage(lang, country, variant);
            String[] featuresArray = null;
            if (features != null) {
                featuresArray = new String[features.size()];
                features.toArray(featuresArray);
            } else {
                featuresArray = new String[0];
            }
            return featuresArray;
        }

        /*
         * There is no point loading a non default language if defaults
         * are enforced.
         */
        @Override
        public int loadLanguage(String lang, String country, String variant) {
            if (!checkNonNull(lang)) {
                return TextToSpeech.ERROR;
            }

            return onLoadLanguage(lang, country, variant);
        }

        @Override
        public void setCallback(IBinder caller, ITextToSpeechCallback cb) {
            // Note that passing in a null callback is a valid use case.
            if (!checkNonNull(caller)) {
                return;
            }

            mCallbacks.setCallback(caller, cb);
        }

        private String intern(String in) {
            // The input parameter will be non null.
            return in.intern();
        }

        private boolean checkNonNull(Object... args) {
            for (Object o : args) {
                if (o == null) return false;
            }
            return true;
        }
    };

    private class CallbackMap extends RemoteCallbackList<ITextToSpeechCallback> {
        private final HashMap<IBinder, ITextToSpeechCallback> mCallerToCallback
                = new HashMap<IBinder, ITextToSpeechCallback>();

        public void setCallback(IBinder caller, ITextToSpeechCallback cb) {
            synchronized (mCallerToCallback) {
                ITextToSpeechCallback old;
                if (cb != null) {
                    register(cb, caller);
                    old = mCallerToCallback.put(caller, cb);
                } else {
                    old = mCallerToCallback.remove(caller);
                }
                if (old != null && old != cb) {
                    unregister(old);
                }
            }
        }

        public void dispatchOnDone(Object callerIdentity, String utteranceId) {
            ITextToSpeechCallback cb = getCallbackFor(callerIdentity);
            if (cb == null) return;
            try {
                cb.onDone(utteranceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Callback onDone failed: " + e);
            }
        }

        public void dispatchOnStart(Object callerIdentity, String utteranceId) {
            ITextToSpeechCallback cb = getCallbackFor(callerIdentity);
            if (cb == null) return;
            try {
                cb.onStart(utteranceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Callback onStart failed: " + e);
            }

        }

        public void dispatchOnError(Object callerIdentity, String utteranceId) {
            ITextToSpeechCallback cb = getCallbackFor(callerIdentity);
            if (cb == null) return;
            try {
                cb.onError(utteranceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Callback onError failed: " + e);
            }
        }

        @Override
        public void onCallbackDied(ITextToSpeechCallback callback, Object cookie) {
            IBinder caller = (IBinder) cookie;
            synchronized (mCallerToCallback) {
                mCallerToCallback.remove(caller);
            }
            mSynthHandler.stopForApp(caller);
        }

        @Override
        public void kill() {
            synchronized (mCallerToCallback) {
                mCallerToCallback.clear();
                super.kill();
            }
        }

        private ITextToSpeechCallback getCallbackFor(Object caller) {
            ITextToSpeechCallback cb;
            IBinder asBinder = (IBinder) caller;
            synchronized (mCallerToCallback) {
                cb = mCallerToCallback.get(asBinder);
            }

            return cb;
        }

    }

}
