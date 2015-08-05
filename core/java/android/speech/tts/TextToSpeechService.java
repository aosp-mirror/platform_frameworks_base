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
import android.media.AudioAttributes;
import android.media.AudioSystem;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.MessageQueue;
import android.os.ParcelFileDescriptor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.speech.tts.TextToSpeech.Engine;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Set;


/**
 * Abstract base class for TTS engine implementations. The following methods
 * need to be implemented:
 * <ul>
 * <li>{@link #onIsLanguageAvailable}</li>
 * <li>{@link #onLoadLanguage}</li>
 * <li>{@link #onGetLanguage}</li>
 * <li>{@link #onSynthesizeText}</li>
 * <li>{@link #onStop}</li>
 * </ul>
 * The first three deal primarily with language management, and are used to
 * query the engine for it's support for a given language and indicate to it
 * that requests in a given language are imminent.
 *
 * {@link #onSynthesizeText} is central to the engine implementation. The
 * implementation should synthesize text as per the request parameters and
 * return synthesized data via the supplied callback. This class and its helpers
 * will then consume that data, which might mean queuing it for playback or writing
 * it to a file or similar. All calls to this method will be on a single thread,
 * which will be different from the main thread of the service. Synthesis must be
 * synchronous which means the engine must NOT hold on to the callback or call any
 * methods on it after the method returns.
 *
 * {@link #onStop} tells the engine that it should stop
 * all ongoing synthesis, if any. Any pending data from the current synthesis
 * will be discarded.
 *
 * {@link #onGetLanguage} is not required as of JELLYBEAN_MR2 (API 18) and later, it is only
 * called on earlier versions of Android.
 *
 * API Level 20 adds support for Voice objects. Voices are an abstraction that allow the TTS
 * service to expose multiple backends for a single locale. Each one of them can have a different
 * features set. In order to fully take advantage of voices, an engine should implement
 * the following methods:
 * <ul>
 * <li>{@link #onGetVoices()}</li>
 * <li>{@link #onIsValidVoiceName(String)}</li>
 * <li>{@link #onLoadVoice(String)}</li>
 * <li>{@link #onGetDefaultVoiceNameFor(String, String, String)}</li>
 * </ul>
 * The first three methods are siblings of the {@link #onGetLanguage},
 * {@link #onIsLanguageAvailable} and {@link #onLoadLanguage} methods. The last one,
 * {@link #onGetDefaultVoiceNameFor(String, String, String)} is a link between locale and voice
 * based methods. Since API level 21 {@link TextToSpeech#setLanguage} is implemented by
 * calling {@link TextToSpeech#setVoice} with the voice returned by
 * {@link #onGetDefaultVoiceNameFor(String, String, String)}.
 *
 * If the client uses a voice instead of a locale, {@link SynthesisRequest} will contain the
 * requested voice name.
 *
 * The default implementations of Voice-related methods implement them using the
 * pre-existing locale-based implementation.
 */
public abstract class TextToSpeechService extends Service {

    private static final boolean DBG = false;
    private static final String TAG = "TextToSpeechService";

    private static final String SYNTH_THREAD_NAME = "SynthThread";

    private SynthHandler mSynthHandler;
    // A thread and it's associated handler for playing back any audio
    // associated with this TTS engine. Will handle all requests except synthesis
    // to file requests, which occur on the synthesis thread.
    private AudioPlaybackHandler mAudioPlaybackHandler;
    private TtsEngines mEngineHelper;

    private CallbackMap mCallbacks;
    private String mPackageName;

    private final Object mVoicesInfoLock = new Object();

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
     * Its return values HAVE to be consistent with onLoadLanguage.
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
     * This method will be called only on Android 4.2 and before (API <= 17). In later versions
     * this method is not called by the Android TTS framework.
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
     * In <= Android 4.2 (<= API 17) can be called on main and service binder threads.
     * In > Android 4.2 (> API 17) can be called on main and synthesis threads.
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
     * Tells the service to synthesize speech from the given text. This method
     * should block until the synthesis is finished. Used for requests from V1
     * clients ({@link android.speech.tts.TextToSpeech}). Called on the synthesis
     * thread.
     *
     * @param request The synthesis request.
     * @param callback The callback that the engine must use to make data
     *            available for playback or for writing to a file.
     */
    protected abstract void onSynthesizeText(SynthesisRequest request,
            SynthesisCallback callback);

    /**
     * Queries the service for a set of features supported for a given language.
     *
     * Can be called on multiple threads.
     *
     * @param lang ISO-3 language code.
     * @param country ISO-3 country code. May be empty or null.
     * @param variant Language variant. May be empty or null.
     * @return A list of features supported for the given language.
     */
    protected Set<String> onGetFeaturesForLanguage(String lang, String country, String variant) {
        return new HashSet<String>();
    }

    private int getExpectedLanguageAvailableStatus(Locale locale) {
        int expectedStatus = TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
        if (locale.getVariant().isEmpty()) {
            if (locale.getCountry().isEmpty()) {
                expectedStatus = TextToSpeech.LANG_AVAILABLE;
            } else {
                expectedStatus = TextToSpeech.LANG_COUNTRY_AVAILABLE;
            }
        }
        return expectedStatus;
    }

    /**
     * Queries the service for a set of supported voices.
     *
     * Can be called on multiple threads.
     *
     * The default implementation tries to enumerate all available locales, pass them to
     * {@link #onIsLanguageAvailable(String, String, String)} and create Voice instances (using
     * the locale's BCP-47 language tag as the voice name) for the ones that are supported.
     * Note, that this implementation is suitable only for engines that don't have multiple voices
     * for a single locale. Also, this implementation won't work with Locales not listed in the
     * set returned by the {@link Locale#getAvailableLocales()} method.
     *
     * @return A list of voices supported.
     */
    public List<Voice> onGetVoices() {
        // Enumerate all locales and check if they are available
        ArrayList<Voice> voices = new ArrayList<Voice>();
        for (Locale locale : Locale.getAvailableLocales()) {
            int expectedStatus = getExpectedLanguageAvailableStatus(locale);
            try {
                int localeStatus = onIsLanguageAvailable(locale.getISO3Language(),
                        locale.getISO3Country(), locale.getVariant());
                if (localeStatus != expectedStatus) {
                    continue;
                }
            } catch (MissingResourceException e) {
                // Ignore locale without iso 3 codes
                continue;
            }
            Set<String> features = onGetFeaturesForLanguage(locale.getISO3Language(),
                    locale.getISO3Country(), locale.getVariant());
            String voiceName = onGetDefaultVoiceNameFor(locale.getISO3Language(),
                    locale.getISO3Country(), locale.getVariant());
            voices.add(new Voice(voiceName, locale, Voice.QUALITY_NORMAL,
                    Voice.LATENCY_NORMAL, false, features));
        }
        return voices;
    }

    /**
     * Return a name of the default voice for a given locale.
     *
     * This method provides a mapping between locales and available voices. This method is
     * used in {@link TextToSpeech#setLanguage}, which calls this method and then calls
     * {@link TextToSpeech#setVoice} with the voice returned by this method.
     *
     * Also, it's used by {@link TextToSpeech#getDefaultVoice()} to find a default voice for
     * the default locale.
     *
     * @param lang ISO-3 language code.
     * @param country ISO-3 country code. May be empty or null.
     * @param variant Language variant. May be empty or null.

     * @return A name of the default voice for a given locale.
     */
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        int localeStatus = onIsLanguageAvailable(lang, country, variant);
        Locale iso3Locale = null;
        switch (localeStatus) {
            case TextToSpeech.LANG_AVAILABLE:
                iso3Locale = new Locale(lang);
                break;
            case TextToSpeech.LANG_COUNTRY_AVAILABLE:
                iso3Locale = new Locale(lang, country);
                break;
            case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
                iso3Locale = new Locale(lang, country, variant);
                break;
            default:
                return null;
        }
        Locale properLocale = TtsEngines.normalizeTTSLocale(iso3Locale);
        String voiceName = properLocale.toLanguageTag();
        if (onIsValidVoiceName(voiceName) == TextToSpeech.SUCCESS) {
            return voiceName;
        } else {
            return null;
        }
    }

    /**
     * Notifies the engine that it should load a speech synthesis voice. There is no guarantee
     * that this method is always called before the voice is used for synthesis. It is merely
     * a hint to the engine that it will probably get some synthesis requests for this voice
     * at some point in the future.
     *
     * Will be called only on synthesis thread.
     *
     * The default implementation creates a Locale from the voice name (by interpreting the name as
     * a BCP-47 tag for the locale), and passes it to
     * {@link #onLoadLanguage(String, String, String)}.
     *
     * @param voiceName Name of the voice.
     * @return {@link TextToSpeech#ERROR} or {@link TextToSpeech#SUCCESS}.
     */
    public int onLoadVoice(String voiceName) {
        Locale locale = Locale.forLanguageTag(voiceName);
        if (locale == null) {
            return TextToSpeech.ERROR;
        }
        int expectedStatus = getExpectedLanguageAvailableStatus(locale);
        try {
            int localeStatus = onIsLanguageAvailable(locale.getISO3Language(),
                    locale.getISO3Country(), locale.getVariant());
            if (localeStatus != expectedStatus) {
                return TextToSpeech.ERROR;
            }
            onLoadLanguage(locale.getISO3Language(),
                    locale.getISO3Country(), locale.getVariant());
            return TextToSpeech.SUCCESS;
        } catch (MissingResourceException e) {
            return TextToSpeech.ERROR;
        }
    }

    /**
     * Checks whether the engine supports a voice with a given name.
     *
     * Can be called on multiple threads.
     *
     * The default implementation treats the voice name as a language tag, creating a Locale from
     * the voice name, and passes it to {@link #onIsLanguageAvailable(String, String, String)}.
     *
     * @param voiceName Name of the voice.
     * @return {@link TextToSpeech#ERROR} or {@link TextToSpeech#SUCCESS}.
     */
    public int onIsValidVoiceName(String voiceName) {
        Locale locale = Locale.forLanguageTag(voiceName);
        if (locale == null) {
            return TextToSpeech.ERROR;
        }
        int expectedStatus = getExpectedLanguageAvailableStatus(locale);
        try {
            int localeStatus = onIsLanguageAvailable(locale.getISO3Language(),
                    locale.getISO3Country(), locale.getVariant());
            if (localeStatus != expectedStatus) {
                return TextToSpeech.ERROR;
            }
            return TextToSpeech.SUCCESS;
        } catch (MissingResourceException e) {
            return TextToSpeech.ERROR;
        }
    }

    private int getDefaultSpeechRate() {
        return getSecureSettingInt(Settings.Secure.TTS_DEFAULT_RATE, Engine.DEFAULT_RATE);
    }

    private String[] getSettingsLocale() {
        final Locale locale = mEngineHelper.getLocalePrefForEngine(mPackageName);
        return TtsEngines.toOldLocaleStringFormat(locale);
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

        private ArrayList<Object> mFlushedObjects = new ArrayList<Object>();
        private boolean mFlushAll;

        public SynthHandler(Looper looper) {
            super(looper);
        }

        private void startFlushingSpeechItems(Object callerIdentity) {
            synchronized (mFlushedObjects) {
                if (callerIdentity == null) {
                    mFlushAll = true;
                } else {
                    mFlushedObjects.add(callerIdentity);
                }
            }
        }
        private void endFlushingSpeechItems(Object callerIdentity) {
            synchronized (mFlushedObjects) {
                if (callerIdentity == null) {
                    mFlushAll = false;
                } else {
                    mFlushedObjects.remove(callerIdentity);
                }
            }
        }
        private boolean isFlushed(SpeechItem speechItem) {
            synchronized (mFlushedObjects) {
                return mFlushAll || mFlushedObjects.contains(speechItem.getCallerIdentity());
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

        private synchronized SpeechItem maybeRemoveCurrentSpeechItem(Object callerIdentity) {
            if (mCurrentSpeechItem != null &&
                    (mCurrentSpeechItem.getCallerIdentity() == callerIdentity)) {
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
            UtteranceProgressDispatcher utterenceProgress = null;
            if (speechItem instanceof UtteranceProgressDispatcher) {
                utterenceProgress = (UtteranceProgressDispatcher) speechItem;
            }

            if (!speechItem.isValid()) {
                if (utterenceProgress != null) {
                    utterenceProgress.dispatchOnError(
                            TextToSpeech.ERROR_INVALID_REQUEST);
                }
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
                    if (isFlushed(speechItem)) {
                        speechItem.stop();
                    } else {
                        setCurrentSpeechItem(speechItem);
                        speechItem.play();
                        setCurrentSpeechItem(null);
                    }
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
                if (utterenceProgress != null) {
                    utterenceProgress.dispatchOnError(TextToSpeech.ERROR_SERVICE);
                }
                return TextToSpeech.ERROR;
            }
        }

        /**
         * Stops all speech output and removes any utterances still in the queue for
         * the calling app.
         *
         * Called on a service binder thread.
         */
        public int stopForApp(final Object callerIdentity) {
            if (callerIdentity == null) {
                return TextToSpeech.ERROR;
            }

            // Flush pending messages from callerIdentity
            startFlushingSpeechItems(callerIdentity);

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

            // Stop flushing pending messages
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    endFlushingSpeechItems(callerIdentity);
                }
            };
            sendMessage(Message.obtain(this, runnable));
            return TextToSpeech.SUCCESS;
        }

        public int stopAll() {
            // Order to flush pending messages
            startFlushingSpeechItems(null);

            // Stop the current speech item unconditionally .
            SpeechItem current = setCurrentSpeechItem(null);
            if (current != null) {
                current.stop();
            }
            // Remove all pending playback as well.
            mAudioPlaybackHandler.stop();

            // Message to stop flushing pending messages
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    endFlushingSpeechItems(null);
                }
            };
            sendMessage(Message.obtain(this, runnable));


            return TextToSpeech.SUCCESS;
        }
    }

    interface UtteranceProgressDispatcher {
        public void dispatchOnStop();
        public void dispatchOnSuccess();
        public void dispatchOnStart();
        public void dispatchOnError(int errorCode);
    }

    /** Set of parameters affecting audio output. */
    static class AudioOutputParams {
        /**
         * Audio session identifier. May be used to associate audio playback with one of the
         * {@link android.media.audiofx.AudioEffect} objects. If not specified by client,
         * it should be equal to {@link AudioSystem#AUDIO_SESSION_ALLOCATE}.
         */
        public final int mSessionId;

        /**
         * Volume, in the range [0.0f, 1.0f]. The default value is
         * {@link TextToSpeech.Engine#DEFAULT_VOLUME} (1.0f).
         */
        public final float mVolume;

        /**
         * Left/right position of the audio, in the range [-1.0f, 1.0f].
         * The default value is {@link TextToSpeech.Engine#DEFAULT_PAN} (0.0f).
         */
        public final float mPan;


        /**
         * Audio attributes, set by {@link TextToSpeech#setAudioAttributes}
         * or created from the value of {@link TextToSpeech.Engine#KEY_PARAM_STREAM}.
         */
        public final AudioAttributes mAudioAttributes;

        /** Create AudioOutputParams with default values */
        AudioOutputParams() {
            mSessionId = AudioSystem.AUDIO_SESSION_ALLOCATE;
            mVolume = Engine.DEFAULT_VOLUME;
            mPan = Engine.DEFAULT_PAN;
            mAudioAttributes = null;
        }

        AudioOutputParams(int sessionId, float volume, float pan,
                AudioAttributes audioAttributes) {
            mSessionId = sessionId;
            mVolume = volume;
            mPan = pan;
            mAudioAttributes = audioAttributes;
        }

        /** Create AudioOutputParams from A {@link SynthesisRequest#getParams()} bundle */
        static AudioOutputParams createFromV1ParamsBundle(Bundle paramsBundle,
                boolean isSpeech) {
            if (paramsBundle == null) {
                return new AudioOutputParams();
            }

            AudioAttributes audioAttributes =
                    (AudioAttributes) paramsBundle.getParcelable(
                            Engine.KEY_PARAM_AUDIO_ATTRIBUTES);
            if (audioAttributes == null) {
                int streamType = paramsBundle.getInt(
                        Engine.KEY_PARAM_STREAM, Engine.DEFAULT_STREAM);
                audioAttributes = (new AudioAttributes.Builder())
                        .setLegacyStreamType(streamType)
                        .setContentType((isSpeech ?
                                AudioAttributes.CONTENT_TYPE_SPEECH :
                                AudioAttributes.CONTENT_TYPE_SONIFICATION))
                        .build();
            }

            return new AudioOutputParams(
                    paramsBundle.getInt(
                            Engine.KEY_PARAM_SESSION_ID,
                            AudioSystem.AUDIO_SESSION_ALLOCATE),
                    paramsBundle.getFloat(
                            Engine.KEY_PARAM_VOLUME,
                            Engine.DEFAULT_VOLUME),
                    paramsBundle.getFloat(
                            Engine.KEY_PARAM_PAN,
                            Engine.DEFAULT_PAN),
                    audioAttributes);
        }
    }


    /**
     * An item in the synth thread queue.
     */
    private abstract class SpeechItem {
        private final Object mCallerIdentity;
        private final int mCallerUid;
        private final int mCallerPid;
        private boolean mStarted = false;
        private boolean mStopped = false;

        public SpeechItem(Object caller, int callerUid, int callerPid) {
            mCallerIdentity = caller;
            mCallerUid = callerUid;
            mCallerPid = callerPid;
        }

        public Object getCallerIdentity() {
            return mCallerIdentity;
        }

        public int getCallerUid() {
            return mCallerUid;
        }

        public int getCallerPid() {
            return mCallerPid;
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
         */
        public void play() {
            synchronized (this) {
                if (mStarted) {
                    throw new IllegalStateException("play() called twice");
                }
                mStarted = true;
            }
            playImpl();
        }

        protected abstract void playImpl();

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

        protected abstract void stopImpl();

        protected synchronized boolean isStopped() {
             return mStopped;
        }

        protected synchronized boolean isStarted() {
            return mStarted;
       }
    }

    /**
     * An item in the synth thread queue that process utterance (and call back to client about
     * progress).
     */
    private abstract class UtteranceSpeechItem extends SpeechItem
        implements UtteranceProgressDispatcher  {

        public UtteranceSpeechItem(Object caller, int callerUid, int callerPid) {
            super(caller, callerUid, callerPid);
        }

        @Override
        public void dispatchOnSuccess() {
            final String utteranceId = getUtteranceId();
            if (utteranceId != null) {
                mCallbacks.dispatchOnSuccess(getCallerIdentity(), utteranceId);
            }
        }

        @Override
        public void dispatchOnStop() {
            final String utteranceId = getUtteranceId();
            if (utteranceId != null) {
                mCallbacks.dispatchOnStop(getCallerIdentity(), utteranceId, isStarted());
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
        public void dispatchOnError(int errorCode) {
            final String utteranceId = getUtteranceId();
            if (utteranceId != null) {
                mCallbacks.dispatchOnError(getCallerIdentity(), utteranceId, errorCode);
            }
        }

        abstract public String getUtteranceId();

        String getStringParam(Bundle params, String key, String defaultValue) {
            return params == null ? defaultValue : params.getString(key, defaultValue);
        }

        int getIntParam(Bundle params, String key, int defaultValue) {
            return params == null ? defaultValue : params.getInt(key, defaultValue);
        }

        float getFloatParam(Bundle params, String key, float defaultValue) {
            return params == null ? defaultValue : params.getFloat(key, defaultValue);
        }
    }

    /**
     * UtteranceSpeechItem for V1 API speech items. V1 API speech items keep
     * synthesis parameters in a single Bundle passed as parameter. This class
     * allow subclasses to access them conveniently.
     */
    private abstract class SpeechItemV1 extends UtteranceSpeechItem {
        protected final Bundle mParams;
        protected final String mUtteranceId;

        SpeechItemV1(Object callerIdentity, int callerUid, int callerPid,
                Bundle params, String utteranceId) {
            super(callerIdentity, callerUid, callerPid);
            mParams = params;
            mUtteranceId = utteranceId;
        }

        boolean hasLanguage() {
            return !TextUtils.isEmpty(getStringParam(mParams, Engine.KEY_PARAM_LANGUAGE, null));
        }

        int getSpeechRate() {
            return getIntParam(mParams, Engine.KEY_PARAM_RATE, getDefaultSpeechRate());
        }

        int getPitch() {
            return getIntParam(mParams, Engine.KEY_PARAM_PITCH, Engine.DEFAULT_PITCH);
        }

        @Override
        public String getUtteranceId() {
            return mUtteranceId;
        }

        AudioOutputParams getAudioParams() {
            return AudioOutputParams.createFromV1ParamsBundle(mParams, true);
        }
    }

    class SynthesisSpeechItemV1 extends SpeechItemV1 {
        // Never null.
        private final CharSequence mText;
        private final SynthesisRequest mSynthesisRequest;
        private final String[] mDefaultLocale;
        // Non null after synthesis has started, and all accesses
        // guarded by 'this'.
        private AbstractSynthesisCallback mSynthesisCallback;
        private final EventLoggerV1 mEventLogger;
        private final int mCallerUid;

        public SynthesisSpeechItemV1(Object callerIdentity, int callerUid, int callerPid,
                Bundle params, String utteranceId, CharSequence text) {
            super(callerIdentity, callerUid, callerPid, params, utteranceId);
            mText = text;
            mCallerUid = callerUid;
            mSynthesisRequest = new SynthesisRequest(mText, mParams);
            mDefaultLocale = getSettingsLocale();
            setRequestParams(mSynthesisRequest);
            mEventLogger = new EventLoggerV1(mSynthesisRequest, callerUid, callerPid,
                    mPackageName);
        }

        public CharSequence getText() {
            return mText;
        }

        @Override
        public boolean isValid() {
            if (mText == null) {
                Log.e(TAG, "null synthesis text");
                return false;
            }
            if (mText.length() >= TextToSpeech.getMaxSpeechInputLength()) {
                Log.w(TAG, "Text too long: " + mText.length() + " chars");
                return false;
            }
            return true;
        }

        @Override
        protected void playImpl() {
            AbstractSynthesisCallback synthesisCallback;
            mEventLogger.onRequestProcessingStart();
            synchronized (this) {
                // stop() might have been called before we enter this
                // synchronized block.
                if (isStopped()) {
                    return;
                }
                mSynthesisCallback = createSynthesisCallback();
                synthesisCallback = mSynthesisCallback;
            }

            TextToSpeechService.this.onSynthesizeText(mSynthesisRequest, synthesisCallback);

            // Fix for case where client called .start() & .error(), but did not called .done()
            if (synthesisCallback.hasStarted() && !synthesisCallback.hasFinished()) {
                synthesisCallback.done();
            }
        }

        protected AbstractSynthesisCallback createSynthesisCallback() {
            return new PlaybackSynthesisCallback(getAudioParams(),
                    mAudioPlaybackHandler, this, getCallerIdentity(), mEventLogger, false);
        }

        private void setRequestParams(SynthesisRequest request) {
            String voiceName = getVoiceName();
            request.setLanguage(getLanguage(), getCountry(), getVariant());
            if (!TextUtils.isEmpty(voiceName)) {
                request.setVoiceName(getVoiceName());
            }
            request.setSpeechRate(getSpeechRate());
            request.setCallerUid(mCallerUid);
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
            } else {
                dispatchOnStop();
            }
        }

        private String getCountry() {
            if (!hasLanguage()) return mDefaultLocale[1];
            return getStringParam(mParams, Engine.KEY_PARAM_COUNTRY, "");
        }

        private String getVariant() {
            if (!hasLanguage()) return mDefaultLocale[2];
            return getStringParam(mParams, Engine.KEY_PARAM_VARIANT, "");
        }

        public String getLanguage() {
            return getStringParam(mParams, Engine.KEY_PARAM_LANGUAGE, mDefaultLocale[0]);
        }

        public String getVoiceName() {
            return getStringParam(mParams, Engine.KEY_PARAM_VOICE_NAME, "");
        }
    }

    private class SynthesisToFileOutputStreamSpeechItemV1 extends SynthesisSpeechItemV1 {
        private final FileOutputStream mFileOutputStream;

        public SynthesisToFileOutputStreamSpeechItemV1(Object callerIdentity, int callerUid,
                int callerPid, Bundle params, String utteranceId, CharSequence text,
                FileOutputStream fileOutputStream) {
            super(callerIdentity, callerUid, callerPid, params, utteranceId, text);
            mFileOutputStream = fileOutputStream;
        }

        @Override
        protected AbstractSynthesisCallback createSynthesisCallback() {
            return new FileSynthesisCallback(mFileOutputStream.getChannel(),
                    this, getCallerIdentity(), false);
        }

        @Override
        protected void playImpl() {
            dispatchOnStart();
            super.playImpl();
            try {
              mFileOutputStream.close();
            } catch(IOException e) {
              Log.w(TAG, "Failed to close output file", e);
            }
        }
    }

    private class AudioSpeechItemV1 extends SpeechItemV1 {
        private final AudioPlaybackQueueItem mItem;

        public AudioSpeechItemV1(Object callerIdentity, int callerUid, int callerPid,
                Bundle params, String utteranceId, Uri uri) {
            super(callerIdentity, callerUid, callerPid, params, utteranceId);
            mItem = new AudioPlaybackQueueItem(this, getCallerIdentity(),
                    TextToSpeechService.this, uri, getAudioParams());
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        protected void playImpl() {
            mAudioPlaybackHandler.enqueue(mItem);
        }

        @Override
        protected void stopImpl() {
            // Do nothing.
        }

        @Override
        public String getUtteranceId() {
            return getStringParam(mParams, Engine.KEY_PARAM_UTTERANCE_ID, null);
        }

        @Override
        AudioOutputParams getAudioParams() {
            return AudioOutputParams.createFromV1ParamsBundle(mParams, false);
        }
    }

    private class SilenceSpeechItem extends UtteranceSpeechItem {
        private final long mDuration;
        private final String mUtteranceId;

        public SilenceSpeechItem(Object callerIdentity, int callerUid, int callerPid,
                String utteranceId, long duration) {
            super(callerIdentity, callerUid, callerPid);
            mUtteranceId = utteranceId;
            mDuration = duration;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        protected void playImpl() {
            mAudioPlaybackHandler.enqueue(new SilencePlaybackQueueItem(
                    this, getCallerIdentity(), mDuration));
        }

        @Override
        protected void stopImpl() {

        }

        @Override
        public String getUtteranceId() {
            return mUtteranceId;
        }
    }

    /**
     * Call {@link TextToSpeechService#onLoadLanguage} on synth thread.
     */
    private class LoadLanguageItem extends SpeechItem {
        private final String mLanguage;
        private final String mCountry;
        private final String mVariant;

        public LoadLanguageItem(Object callerIdentity, int callerUid, int callerPid,
                String language, String country, String variant) {
            super(callerIdentity, callerUid, callerPid);
            mLanguage = language;
            mCountry = country;
            mVariant = variant;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        protected void playImpl() {
            TextToSpeechService.this.onLoadLanguage(mLanguage, mCountry, mVariant);
        }

        @Override
        protected void stopImpl() {
            // No-op
        }
    }

    /**
     * Call {@link TextToSpeechService#onLoadLanguage} on synth thread.
     */
    private class LoadVoiceItem extends SpeechItem {
        private final String mVoiceName;

        public LoadVoiceItem(Object callerIdentity, int callerUid, int callerPid,
                String voiceName) {
            super(callerIdentity, callerUid, callerPid);
            mVoiceName = voiceName;
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        protected void playImpl() {
            TextToSpeechService.this.onLoadVoice(mVoiceName);
        }

        @Override
        protected void stopImpl() {
            // No-op
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
        public int speak(IBinder caller, CharSequence text, int queueMode, Bundle params,
                String utteranceId) {
            if (!checkNonNull(caller, text, params)) {
                return TextToSpeech.ERROR;
            }

            SpeechItem item = new SynthesisSpeechItemV1(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), params, utteranceId, text);
            return mSynthHandler.enqueueSpeechItem(queueMode, item);
        }

        @Override
        public int synthesizeToFileDescriptor(IBinder caller, CharSequence text, ParcelFileDescriptor
                fileDescriptor, Bundle params, String utteranceId) {
            if (!checkNonNull(caller, text, fileDescriptor, params)) {
                return TextToSpeech.ERROR;
            }

            // In test env, ParcelFileDescriptor instance may be EXACTLY the same
            // one that is used by client. And it will be closed by a client, thus
            // preventing us from writing anything to it.
            final ParcelFileDescriptor sameFileDescriptor = ParcelFileDescriptor.adoptFd(
                    fileDescriptor.detachFd());

            SpeechItem item = new SynthesisToFileOutputStreamSpeechItemV1(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), params, utteranceId, text,
                    new ParcelFileDescriptor.AutoCloseOutputStream(sameFileDescriptor));
            return mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_ADD, item);
        }

        @Override
        public int playAudio(IBinder caller, Uri audioUri, int queueMode, Bundle params,
                String utteranceId) {
            if (!checkNonNull(caller, audioUri, params)) {
                return TextToSpeech.ERROR;
            }

            SpeechItem item = new AudioSpeechItemV1(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), params, utteranceId, audioUri);
            return mSynthHandler.enqueueSpeechItem(queueMode, item);
        }

        @Override
        public int playSilence(IBinder caller, long duration, int queueMode, String utteranceId) {
            if (!checkNonNull(caller)) {
                return TextToSpeech.ERROR;
            }

            SpeechItem item = new SilenceSpeechItem(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), utteranceId, duration);
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

        @Override
        public String[] getClientDefaultLanguage() {
            return getSettingsLocale();
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
        public int loadLanguage(IBinder caller, String lang, String country, String variant) {
            if (!checkNonNull(lang)) {
                return TextToSpeech.ERROR;
            }
            int retVal = onIsLanguageAvailable(lang, country, variant);

            if (retVal == TextToSpeech.LANG_AVAILABLE ||
                    retVal == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    retVal == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {

                SpeechItem item = new LoadLanguageItem(caller, Binder.getCallingUid(),
                        Binder.getCallingPid(), lang, country, variant);

                if (mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_ADD, item) !=
                        TextToSpeech.SUCCESS) {
                    return TextToSpeech.ERROR;
                }
            }
            return retVal;
        }

        @Override
        public List<Voice> getVoices() {
            return onGetVoices();
        }

        @Override
        public int loadVoice(IBinder caller, String voiceName) {
            if (!checkNonNull(voiceName)) {
                return TextToSpeech.ERROR;
            }
            int retVal = onIsValidVoiceName(voiceName);

            if (retVal == TextToSpeech.SUCCESS) {
                SpeechItem item = new LoadVoiceItem(caller, Binder.getCallingUid(),
                        Binder.getCallingPid(), voiceName);
                if (mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_ADD, item) !=
                        TextToSpeech.SUCCESS) {
                    return TextToSpeech.ERROR;
                }
            }
            return retVal;
        }

        public String getDefaultVoiceNameFor(String lang, String country, String variant) {
            if (!checkNonNull(lang)) {
                return null;
            }
            int retVal = onIsLanguageAvailable(lang, country, variant);

            if (retVal == TextToSpeech.LANG_AVAILABLE ||
                    retVal == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                    retVal == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                return onGetDefaultVoiceNameFor(lang, country, variant);
            } else {
                return null;
            }
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

        public void dispatchOnStop(Object callerIdentity, String utteranceId, boolean started) {
            ITextToSpeechCallback cb = getCallbackFor(callerIdentity);
            if (cb == null) return;
            try {
                cb.onStop(utteranceId, started);
            } catch (RemoteException e) {
                Log.e(TAG, "Callback onStop failed: " + e);
            }
        }

        public void dispatchOnSuccess(Object callerIdentity, String utteranceId) {
            ITextToSpeechCallback cb = getCallbackFor(callerIdentity);
            if (cb == null) return;
            try {
                cb.onSuccess(utteranceId);
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

        public void dispatchOnError(Object callerIdentity, String utteranceId,
                int errorCode) {
            ITextToSpeechCallback cb = getCallbackFor(callerIdentity);
            if (cb == null) return;
            try {
                cb.onError(utteranceId, errorCode);
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
            //mSynthHandler.stopForApp(caller);
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
