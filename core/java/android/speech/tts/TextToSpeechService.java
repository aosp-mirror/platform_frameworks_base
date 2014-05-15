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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;


/**
 * Abstract base class for TTS engine implementations. The following methods
 * need to be implemented for V1 API ({@link TextToSpeech}) implementation.
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
 * <p>
 * In order to fully support the V2 API ({@link TextToSpeechClient}),
 * these methods must be implemented:
 * <ul>
 * <li>{@link #onSynthesizeTextV2}</li>
 * <li>{@link #checkVoicesInfo}</li>
 * <li>{@link #onVoicesInfoChange}</li>
 * <li>{@link #implementsV2API}</li>
 * </ul>
 * In addition {@link #implementsV2API} has to return true.
 * <p>
 * If the service does not implement these methods and {@link #implementsV2API} returns false,
 * then the V2 API will be provided by converting V2 requests ({@link #onSynthesizeTextV2})
 * to V1 requests ({@link #onSynthesizeText}). On service setup, all of the available device
 * locales will be fed to {@link #onIsLanguageAvailable} to check if they are supported.
 * If they are, embedded and/or network voices will be created depending on the result of
 * {@link #onGetFeaturesForLanguage}.
 * <p>
 * Note that a V2 service will still receive requests from V1 clients and has to implement all
 * of the V1 API methods.
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

    private List<VoiceInfo> mVoicesInfoList;
    private Map<String, VoiceInfo> mVoicesInfoLookup;

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
     * Check the available voices data and return an immutable list of the available voices.
     * The output of this method will be passed to clients to allow them to configure synthesis
     * requests.
     *
     * Can be called on multiple threads.
     *
     * The result of this method will be saved and served to all TTS clients. If a TTS service wants
     * to update the set of available voices, it should call the {@link #forceVoicesInfoCheck()}
     * method.
     */
    protected List<VoiceInfo> checkVoicesInfo() {
        if (implementsV2API()) {
            throw new IllegalStateException("For proper V2 API implementation this method has to" +
                    "  be implemented");
        }

        // V2 to V1 interface adapter. This allows using V2 client interface on V1-only services.
        Bundle defaultParams = new Bundle();
        defaultParams.putFloat(TextToSpeechClient.Params.SPEECH_PITCH, 1.0f);
        // Speech speed <= 0 makes it use a system wide setting
        defaultParams.putFloat(TextToSpeechClient.Params.SPEECH_SPEED, 0.0f);

        // Enumerate all locales and check if they are available
        ArrayList<VoiceInfo> voicesInfo = new ArrayList<VoiceInfo>();
        int id = 0;
        for (Locale locale : Locale.getAvailableLocales()) {
            int expectedStatus = TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE;
            if (locale.getVariant().isEmpty()) {
                if (locale.getCountry().isEmpty()) {
                    expectedStatus = TextToSpeech.LANG_AVAILABLE;
                } else {
                    expectedStatus = TextToSpeech.LANG_COUNTRY_AVAILABLE;
                }
            }
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

            VoiceInfo.Builder builder = new VoiceInfo.Builder();
            builder.setLatency(VoiceInfo.LATENCY_NORMAL);
            builder.setQuality(VoiceInfo.QUALITY_NORMAL);
            builder.setLocale(locale);
            builder.setParamsWithDefaults(defaultParams);

            if (features == null || features.contains(
                    TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)) {
                builder.setName(locale.toString() + "-embedded");
                builder.setRequiresNetworkConnection(false);
                voicesInfo.add(builder.build());
            }

            if (features != null && features.contains(
                    TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)) {
                builder.setName(locale.toString() + "-network");
                builder.setRequiresNetworkConnection(true);
                voicesInfo.add(builder.build());
            }
        }

        return voicesInfo;
    }

    /**
     * Tells the synthesis thread that it should reload voice data.
     * There's a high probability that the underlying set of available voice data has changed.
     * Called only on the synthesis thread.
     */
    protected void onVoicesInfoChange() {

    }

    /**
     * Tells the service to synthesize speech from the given text. This method
     * should block until the synthesis is finished. Used for requests from V2
     * client {@link android.speech.tts.TextToSpeechClient}. Called on the
     * synthesis thread.
     *
     * @param request The synthesis request.
     * @param callback The callback the the engine must use to make data
     *            available for playback or for writing to a file.
     */
    protected void onSynthesizeTextV2(SynthesisRequestV2 request,
            VoiceInfo selectedVoice,
            SynthesisCallback callback) {
        if (implementsV2API()) {
            throw new IllegalStateException("For proper V2 API implementation this method has to" +
                    "  be implemented");
        }

        // Convert to V1 params
        int speechRate = (int) (request.getVoiceParams().getFloat(
                TextToSpeechClient.Params.SPEECH_SPEED, 1.0f) * 100);
        int speechPitch = (int) (request.getVoiceParams().getFloat(
                TextToSpeechClient.Params.SPEECH_PITCH, 1.0f) * 100);

        // Provide adapter to V1 API
        Bundle params = new Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, request.getUtteranceId());
        params.putInt(TextToSpeech.Engine.KEY_PARAM_PITCH, speechPitch);
        params.putInt(TextToSpeech.Engine.KEY_PARAM_RATE, speechRate);
        if (selectedVoice.getRequiresNetworkConnection()) {
            params.putString(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, "true");
        } else {
            params.putString(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS, "true");
        }

        // Build V1 request
        SynthesisRequest requestV1 = new SynthesisRequest(request.getText(), params);
        Locale locale = selectedVoice.getLocale();
        requestV1.setLanguage(locale.getISO3Language(), locale.getISO3Country(),
                locale.getVariant());
        requestV1.setSpeechRate(speechRate);
        requestV1.setPitch(speechPitch);

        // Synthesize using V1 interface
        onSynthesizeText(requestV1, callback);
    }

    /**
     * If true, this service implements proper V2 TTS API service. If it's false,
     * V2 API will be provided through adapter.
     */
    protected boolean implementsV2API() {
        return false;
    }

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
        return null;
    }

    private List<VoiceInfo> getVoicesInfo() {
        synchronized (mVoicesInfoLock) {
            if (mVoicesInfoList == null) {
                // Get voices. Defensive copy to make sure TTS engine won't alter the list.
                mVoicesInfoList = new ArrayList<VoiceInfo>(checkVoicesInfo());
                // Build lookup map
                mVoicesInfoLookup = new HashMap<String, VoiceInfo>((int) (
                        mVoicesInfoList.size()*1.5f));
                for (VoiceInfo voiceInfo : mVoicesInfoList) {
                    VoiceInfo prev = mVoicesInfoLookup.put(voiceInfo.getName(), voiceInfo);
                    if (prev != null) {
                        Log.e(TAG, "Duplicate name (" + voiceInfo.getName() + ") of the voice ");
                    }
                }
            }
            return mVoicesInfoList;
        }
    }

    public VoiceInfo getVoicesInfoWithName(String name) {
        synchronized (mVoicesInfoLock) {
            if (mVoicesInfoLookup != null) {
                return mVoicesInfoLookup.get(name);
            }
        }
        return null;
    }

    /**
     * Force TTS service to reevaluate the set of available languages. Will result in
     * a call to {@link #checkVoicesInfo()} on the same thread, {@link #onVoicesInfoChange}
     * on the synthesizer thread and callback to
     * {@link TextToSpeechClient.ConnectionCallbacks#onEngineStatusChange} of all connected
     * TTS clients.
     *
     * Use this method only if you know that set of available languages changed.
     *
     * Can be called on multiple threads.
     */
    public void forceVoicesInfoCheck() {
        synchronized (mVoicesInfoLock) {
            List<VoiceInfo> old = mVoicesInfoList;

            mVoicesInfoList = null; // Force recreation of voices info list
            getVoicesInfo();

            if (mVoicesInfoList == null) {
                throw new IllegalStateException("This method applies only to services " +
                        "supporting V2 TTS API. This services doesn't support V2 TTS API.");
            }

            if (old != null) {
                // Flush all existing items, and inform synthesis thread about the change.
                mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_FLUSH,
                        new VoicesInfoChangeItem());
                // TODO: Handle items that may be added to queue after SynthesizerRestartItem
                // but before client reconnection
                // Disconnect all of them
                mCallbacks.dispatchVoicesInfoChange(mVoicesInfoList);
            }
        }
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
                            TextToSpeechClient.Status.ERROR_INVALID_REQUEST);
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
                if (utterenceProgress != null) {
                    utterenceProgress.dispatchOnError(TextToSpeechClient.Status.ERROR_SERVICE);
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
            // Stop the current speech item unconditionally .
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
        public void dispatchOnFallback();
        public void dispatchOnStop();
        public void dispatchOnSuccess();
        public void dispatchOnStart();
        public void dispatchOnError(int errorCode);
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
                mCallbacks.dispatchOnStop(getCallerIdentity(), utteranceId);
            }
        }

        @Override
        public void dispatchOnFallback() {
            final String utteranceId = getUtteranceId();
            if (utteranceId != null) {
                mCallbacks.dispatchOnFallback(getCallerIdentity(), utteranceId);
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

        SpeechItemV1(Object callerIdentity, int callerUid, int callerPid,
                Bundle params) {
            super(callerIdentity, callerUid, callerPid);
            mParams = params;
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
            return getStringParam(mParams, Engine.KEY_PARAM_UTTERANCE_ID, null);
        }

        int getStreamType() {
            return getIntParam(mParams, Engine.KEY_PARAM_STREAM, Engine.DEFAULT_STREAM);
        }

        float getVolume() {
            return getFloatParam(mParams, Engine.KEY_PARAM_VOLUME, Engine.DEFAULT_VOLUME);
        }

        float getPan() {
            return getFloatParam(mParams, Engine.KEY_PARAM_PAN, Engine.DEFAULT_PAN);
        }
    }

    class SynthesisSpeechItemV2 extends UtteranceSpeechItem {
        private final SynthesisRequestV2 mSynthesisRequest;
        private AbstractSynthesisCallback mSynthesisCallback;
        private final EventLoggerV2 mEventLogger;

        public SynthesisSpeechItemV2(Object callerIdentity, int callerUid, int callerPid,
                SynthesisRequestV2 synthesisRequest) {
            super(callerIdentity, callerUid, callerPid);

            mSynthesisRequest = synthesisRequest;
            mEventLogger = new EventLoggerV2(synthesisRequest, callerUid, callerPid,
                    mPackageName);

            updateSpeechSpeedParam(synthesisRequest);
        }

        private void updateSpeechSpeedParam(SynthesisRequestV2 synthesisRequest) {
            Bundle voiceParams = mSynthesisRequest.getVoiceParams();

            // Inject default speech speed if needed
            if (voiceParams.containsKey(TextToSpeechClient.Params.SPEECH_SPEED)) {
                if (voiceParams.getFloat(TextToSpeechClient.Params.SPEECH_SPEED) <= 0) {
                    voiceParams.putFloat(TextToSpeechClient.Params.SPEECH_SPEED,
                            getDefaultSpeechRate() / 100.0f);
                }
            }
        }

        @Override
        public boolean isValid() {
            if (mSynthesisRequest.getText() == null) {
                Log.e(TAG, "null synthesis text");
                return false;
            }
            if (mSynthesisRequest.getText().length() >= TextToSpeech.getMaxSpeechInputLength()) {
                Log.w(TAG, "Text too long: " + mSynthesisRequest.getText().length() + " chars");
                return false;
            }

            return true;
        }

        @Override
        protected void playImpl() {
            AbstractSynthesisCallback synthesisCallback;
            if (mEventLogger != null) {
                mEventLogger.onRequestProcessingStart();
            }
            synchronized (this) {
                // stop() might have been called before we enter this
                // synchronized block.
                if (isStopped()) {
                    return;
                }
                mSynthesisCallback = createSynthesisCallback();
                synthesisCallback = mSynthesisCallback;
            }

            // Get voice info
            VoiceInfo voiceInfo = getVoicesInfoWithName(mSynthesisRequest.getVoiceName());
            if (voiceInfo != null) {
                // Primary voice
                TextToSpeechService.this.onSynthesizeTextV2(mSynthesisRequest, voiceInfo,
                        synthesisCallback);
            } else {
                Log.e(TAG, "Unknown voice name:" + mSynthesisRequest.getVoiceName());
                synthesisCallback.error(TextToSpeechClient.Status.ERROR_INVALID_REQUEST);
            }

            // Fix for case where client called .start() & .error(), but did not called .done()
            if (!synthesisCallback.hasFinished()) {
                synthesisCallback.done();
            }
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

        protected AbstractSynthesisCallback createSynthesisCallback() {
            return new PlaybackSynthesisCallback(getStreamType(), getVolume(), getPan(),
                    mAudioPlaybackHandler, this, getCallerIdentity(), mEventLogger,
                    implementsV2API());
        }

        private int getStreamType() {
            return getIntParam(mSynthesisRequest.getAudioParams(),
                    TextToSpeechClient.Params.AUDIO_PARAM_STREAM,
                    Engine.DEFAULT_STREAM);
        }

        private float getVolume() {
            return getFloatParam(mSynthesisRequest.getAudioParams(),
                    TextToSpeechClient.Params.AUDIO_PARAM_VOLUME,
                    Engine.DEFAULT_VOLUME);
        }

        private float getPan() {
            return getFloatParam(mSynthesisRequest.getAudioParams(),
                    TextToSpeechClient.Params.AUDIO_PARAM_PAN,
                    Engine.DEFAULT_PAN);
        }

        @Override
        public String getUtteranceId() {
            return mSynthesisRequest.getUtteranceId();
        }
    }

    private class SynthesisToFileOutputStreamSpeechItemV2 extends SynthesisSpeechItemV2 {
        private final FileOutputStream mFileOutputStream;

        public SynthesisToFileOutputStreamSpeechItemV2(Object callerIdentity, int callerUid,
                int callerPid,
                SynthesisRequestV2 synthesisRequest,
                FileOutputStream fileOutputStream) {
            super(callerIdentity, callerUid, callerPid, synthesisRequest);
            mFileOutputStream = fileOutputStream;
        }

        @Override
        protected AbstractSynthesisCallback createSynthesisCallback() {
            return new FileSynthesisCallback(mFileOutputStream.getChannel(),
                    this, getCallerIdentity(), implementsV2API());
        }

        @Override
        protected void playImpl() {
            super.playImpl();
            try {
              mFileOutputStream.close();
            } catch(IOException e) {
              Log.w(TAG, "Failed to close output file", e);
            }
        }
    }

    private class AudioSpeechItemV2 extends UtteranceSpeechItem {
        private final AudioPlaybackQueueItem mItem;
        private final Bundle mAudioParams;
        private final String mUtteranceId;

        public AudioSpeechItemV2(Object callerIdentity, int callerUid, int callerPid,
                String utteranceId, Bundle audioParams, Uri uri) {
            super(callerIdentity, callerUid, callerPid);
            mUtteranceId = utteranceId;
            mAudioParams = audioParams;
            mItem = new AudioPlaybackQueueItem(this, getCallerIdentity(),
                    TextToSpeechService.this, uri, getStreamType());
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

        protected int getStreamType() {
            return mAudioParams.getInt(TextToSpeechClient.Params.AUDIO_PARAM_STREAM);
        }

        public String getUtteranceId() {
            return mUtteranceId;
        }
    }


    class SynthesisSpeechItemV1 extends SpeechItemV1 {
        // Never null.
        private final String mText;
        private final SynthesisRequest mSynthesisRequest;
        private final String[] mDefaultLocale;
        // Non null after synthesis has started, and all accesses
        // guarded by 'this'.
        private AbstractSynthesisCallback mSynthesisCallback;
        private final EventLoggerV1 mEventLogger;
        private final int mCallerUid;

        public SynthesisSpeechItemV1(Object callerIdentity, int callerUid, int callerPid,
                Bundle params, String text) {
            super(callerIdentity, callerUid, callerPid, params);
            mText = text;
            mCallerUid = callerUid;
            mSynthesisRequest = new SynthesisRequest(mText, mParams);
            mDefaultLocale = getSettingsLocale();
            setRequestParams(mSynthesisRequest);
            mEventLogger = new EventLoggerV1(mSynthesisRequest, callerUid, callerPid,
                    mPackageName);
        }

        public String getText() {
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
            return new PlaybackSynthesisCallback(getStreamType(), getVolume(), getPan(),
                    mAudioPlaybackHandler, this, getCallerIdentity(), mEventLogger, false);
        }

        private void setRequestParams(SynthesisRequest request) {
            request.setLanguage(getLanguage(), getCountry(), getVariant());
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
    }

    private class SynthesisToFileOutputStreamSpeechItemV1 extends SynthesisSpeechItemV1 {
        private final FileOutputStream mFileOutputStream;

        public SynthesisToFileOutputStreamSpeechItemV1(Object callerIdentity, int callerUid,
                int callerPid, Bundle params, String text, FileOutputStream fileOutputStream) {
            super(callerIdentity, callerUid, callerPid, params, text);
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
                Bundle params, Uri uri) {
            super(callerIdentity, callerUid, callerPid, params);
            mItem = new AudioPlaybackQueueItem(this, getCallerIdentity(),
                    TextToSpeechService.this, uri, getStreamType());
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
     * Call {@link TextToSpeechService#onVoicesInfoChange} on synthesis thread.
     */
    private class VoicesInfoChangeItem extends SpeechItem {
        public VoicesInfoChangeItem() {
            super(null, 0, 0); // It's never initiated by an user
        }

        @Override
        public boolean isValid() {
            return true;
        }

        @Override
        protected void playImpl() {
            TextToSpeechService.this.onVoicesInfoChange();
        }

        @Override
        protected void stopImpl() {
            // No-op
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

            SpeechItem item = new SynthesisSpeechItemV1(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), params, text);
            return mSynthHandler.enqueueSpeechItem(queueMode, item);
        }

        @Override
        public int synthesizeToFileDescriptor(IBinder caller, String text, ParcelFileDescriptor
                fileDescriptor, Bundle params) {
            if (!checkNonNull(caller, text, fileDescriptor, params)) {
                return TextToSpeech.ERROR;
            }

            // In test env, ParcelFileDescriptor instance may be EXACTLY the same
            // one that is used by client. And it will be closed by a client, thus
            // preventing us from writing anything to it.
            final ParcelFileDescriptor sameFileDescriptor = ParcelFileDescriptor.adoptFd(
                    fileDescriptor.detachFd());

            SpeechItem item = new SynthesisToFileOutputStreamSpeechItemV1(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), params, text,
                    new ParcelFileDescriptor.AutoCloseOutputStream(sameFileDescriptor));
            return mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_ADD, item);
        }

        @Override
        public int playAudio(IBinder caller, Uri audioUri, int queueMode, Bundle params) {
            if (!checkNonNull(caller, audioUri, params)) {
                return TextToSpeech.ERROR;
            }

            SpeechItem item = new AudioSpeechItemV1(caller,
                    Binder.getCallingUid(), Binder.getCallingPid(), params, audioUri);
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

        @Override
        public List<VoiceInfo> getVoicesInfo() {
            return TextToSpeechService.this.getVoicesInfo();
        }

        @Override
        public int speakV2(IBinder callingInstance,
                SynthesisRequestV2 request) {
            if (!checkNonNull(callingInstance, request)) {
                return TextToSpeech.ERROR;
            }

            SpeechItem item = new SynthesisSpeechItemV2(callingInstance,
                    Binder.getCallingUid(), Binder.getCallingPid(), request);
            return mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_ADD, item);
        }

        @Override
        public int synthesizeToFileDescriptorV2(IBinder callingInstance,
                ParcelFileDescriptor fileDescriptor,
                SynthesisRequestV2 request) {
            if (!checkNonNull(callingInstance, request, fileDescriptor)) {
                return TextToSpeech.ERROR;
            }

            // In test env, ParcelFileDescriptor instance may be EXACTLY the same
            // one that is used by client. And it will be closed by a client, thus
            // preventing us from writing anything to it.
            final ParcelFileDescriptor sameFileDescriptor = ParcelFileDescriptor.adoptFd(
                    fileDescriptor.detachFd());

            SpeechItem item = new SynthesisToFileOutputStreamSpeechItemV2(callingInstance,
                    Binder.getCallingUid(), Binder.getCallingPid(), request,
                    new ParcelFileDescriptor.AutoCloseOutputStream(sameFileDescriptor));
            return mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_ADD, item);

        }

        @Override
        public int playAudioV2(
                IBinder callingInstance, Uri audioUri, String utteranceId,
                Bundle systemParameters) {
            if (!checkNonNull(callingInstance, audioUri, systemParameters)) {
                return TextToSpeech.ERROR;
            }

            SpeechItem item = new AudioSpeechItemV2(callingInstance,
                    Binder.getCallingUid(), Binder.getCallingPid(), utteranceId, systemParameters,
                    audioUri);
            return mSynthHandler.enqueueSpeechItem(TextToSpeech.QUEUE_ADD, item);
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

        public void dispatchOnFallback(Object callerIdentity, String utteranceId) {
            ITextToSpeechCallback cb = getCallbackFor(callerIdentity);
            if (cb == null) return;
            try {
                cb.onFallback(utteranceId);
            } catch (RemoteException e) {
                Log.e(TAG, "Callback onFallback failed: " + e);
            }
        }

        public void dispatchOnStop(Object callerIdentity, String utteranceId) {
            ITextToSpeechCallback cb = getCallbackFor(callerIdentity);
            if (cb == null) return;
            try {
                cb.onStop(utteranceId);
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

        public void dispatchVoicesInfoChange(List<VoiceInfo> voicesInfo) {
            synchronized (mCallerToCallback) {
                for (ITextToSpeechCallback callback : mCallerToCallback.values()) {
                    try {
                        callback.onVoicesInfoChange(voicesInfo);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to request reconnect", e);
                    }
                }
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
