/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.speech;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.TestApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import com.android.internal.R;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * This class provides access to the speech recognition service. This service allows access to the
 * speech recognizer. Do not instantiate this class directly, instead, call
 * {@link SpeechRecognizer#createSpeechRecognizer(Context)}, or
 * {@link SpeechRecognizer#createOnDeviceSpeechRecognizer(Context)}. This class's methods must be
 * invoked only from the main application thread.
 *
 * <p><strong>Important:</strong> the caller MUST invoke {@link #destroy()} on a
 * SpeechRecognizer object when it is no longer needed.
 *
 * <p>The implementation of this API is likely to stream audio to remote servers to perform speech
 * recognition. As such this API is not intended to be used for continuous recognition, which would
 * consume a significant amount of battery and bandwidth.
 *
 * <p>Please note that the application must have {@link android.Manifest.permission#RECORD_AUDIO}
 * permission to use this class.
 */
public class SpeechRecognizer {

    /**
     * Key used to retrieve an {@code ArrayList<String>} from the {@link Bundle} passed to the
     * {@link RecognitionListener#onResults(Bundle)} and
     * {@link RecognitionListener#onPartialResults(Bundle)} methods. These strings are the possible
     * recognition results, where the first element is the most likely candidate.
     */
    public static final String RESULTS_RECOGNITION = "results_recognition";

    /**
     * Key used to retrieve a float array from the {@link Bundle} passed to the
     * {@link RecognitionListener#onResults(Bundle)} and
     * {@link RecognitionListener#onPartialResults(Bundle)} methods. The array should be
     * the same size as the ArrayList provided in {@link #RESULTS_RECOGNITION}, and should contain
     * values ranging from 0.0 to 1.0, or -1 to represent an unavailable confidence score.
     * <p>
     * Confidence values close to 1.0 indicate high confidence (the speech recognizer is confident
     * that the recognition result is correct), while values close to 0.0 indicate low confidence.
     * <p>
     * This value is optional and might not be provided.
     */
    public static final String CONFIDENCE_SCORES = "confidence_scores";

    /**
     * Key used to retrieve an ArrayList&lt;{@link AlternativeSpans}&gt; from the {@link Bundle}
     * passed to the {@link RecognitionListener#onResults(Bundle)} and
     * {@link RecognitionListener#onPartialResults(Bundle)} methods. The list should be the same
     * size as the ArrayList provided in {@link #RESULTS_RECOGNITION}.
     *
     * <p> A single {@link SpeechRecognizer} result is represented as a {@link String}. For a
     * specific span (substring) of the originally recognized result string the recognizer provides
     * a list of alternative hypotheses in the form of an {@link AlternativeSpan} object.
     * Alternatives for different spans of a result string are listed in an {@link AlternativeSpans}
     * object. Each item from the ArrayList retrieved by this key corresponds to a single result
     * string provided in {@link #RESULTS_RECOGNITION}.
     *
     * <p> This value is optional and might not be provided.
     */
    public static final String RESULTS_ALTERNATIVES = "results_alternatives";

    /**
     * Key used to receive an ArrayList&lt;{@link RecognitionPart}&gt; object from the
     * {@link Bundle} passed to the {@link RecognitionListener#onResults(Bundle)} and
     * {@link RecognitionListener#onSegmentResults(Bundle)} methods.
     *
     * <p> A single {@link SpeechRecognizer} result is represented as a {@link String}. Each word of
     * the resulting String, as well as any potential adjacent punctuation, is represented by a
     * {@link RecognitionPart} item from the ArrayList retrieved by this key.
     */
    public static final String RECOGNITION_PARTS = "recognition_parts";

    /**
     * Key used to retrieve a {@link String} representation of the IETF language tag (as defined by
     * BCP 47, e.g., "en-US", "de-DE") of the detected language of the most recent audio chunk.
     *
     * <p> This info is returned to the client in the {@link Bundle} passed to
     * {@link RecognitionListener#onLanguageDetection(Bundle)} only if
     * {@link RecognizerIntent#EXTRA_ENABLE_LANGUAGE_DETECTION} is set. Additionally, if
     * {@link RecognizerIntent#EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES} are listed,
     * the detected language is constrained to be one from the list.
     */
    public static final String DETECTED_LANGUAGE = "detected_language";

    /**
     * Key used to retrieve the level of confidence of the detected language
     * of the most recent audio chunk,
     * represented by an {@code int} value prefixed by {@code LANGUAGE_DETECTION_CONFIDENCE_LEVEL_}.
     *
     * <p> This info is returned to the client in the {@link Bundle} passed to
     * {@link RecognitionListener#onLanguageDetection(Bundle)} only if
     * {@link RecognizerIntent#EXTRA_ENABLE_LANGUAGE_DETECTION} is set.
     */
    public static final String LANGUAGE_DETECTION_CONFIDENCE_LEVEL =
            "language_detection_confidence_level";

    /**
     * The level of language detection confidence.
     *
     * @hide
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LANGUAGE_DETECTION_CONFIDENCE_LEVEL_"}, value = {
            LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN,
            LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT,
            LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT,
            LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT
    })
    public @interface LanguageDetectionConfidenceLevel {}

    public static final int LANGUAGE_DETECTION_CONFIDENCE_LEVEL_UNKNOWN = 0;
    public static final int LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT = 1;
    public static final int LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT = 2;
    public static final int LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT = 3;

    /**
     * Key used to retrieve an ArrayList&lt;{@link String}&gt; containing representations of the
     * IETF language tags (as defined by BCP 47, e.g., "en-US", "en-UK") denoting the alternative
     * locales for the same language retrieved by the key {@link #DETECTED_LANGUAGE}.
     *
     * This info is returned to the client in the {@link Bundle} passed to
     * {@link RecognitionListener#onLanguageDetection(Bundle)} only if
     * {@link RecognizerIntent#EXTRA_ENABLE_LANGUAGE_DETECTION} is set.
     */
    public static final String TOP_LOCALE_ALTERNATIVES = "top_locale_alternatives";

    /**
     * Key used to retrieve the result of the language switch of the most recent audio chunk,
     * represented by an {@code int} value prefixed by {@code LANGUAGE_SWITCH_}.
     *
     * <p> This info is returned to the client in the {@link Bundle} passed to the
     * {@link RecognitionListener#onLanguageDetection(Bundle)} only if
     * {@link RecognizerIntent#EXTRA_ENABLE_LANGUAGE_SWITCH} is set.
     */
    public static final String LANGUAGE_SWITCH_RESULT = "language_switch_result";

    /**
     * The result of the language switch.
     *
     * @hide
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LANGUAGE_SWITCH_RESULT_"}, value = {
            LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED,
            LANGUAGE_SWITCH_RESULT_SUCCEEDED,
            LANGUAGE_SWITCH_RESULT_FAILED,
            LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL
    })
    public @interface LanguageSwitchResult {}

    /** Switch not attempted. */
    public static final int LANGUAGE_SWITCH_RESULT_NOT_ATTEMPTED = 0;

    /** Switch attempted and succeeded. */
    public static final int LANGUAGE_SWITCH_RESULT_SUCCEEDED = 1;

    /** Switch attempted and failed. */
    public static final int LANGUAGE_SWITCH_RESULT_FAILED = 2;

    /**
     * Switch skipped because the language model is missing
     * or the language is not allowlisted for auto switch.
     */
    public static final int LANGUAGE_SWITCH_RESULT_SKIPPED_NO_MODEL = 3;

    /**
     * The reason speech recognition failed.
     *
     * @hide
     */
    @Documented
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"ERROR_"}, value = {
            ERROR_NETWORK_TIMEOUT,
            ERROR_NETWORK,
            ERROR_AUDIO,
            ERROR_SERVER,
            ERROR_CLIENT,
            ERROR_SPEECH_TIMEOUT,
            ERROR_NO_MATCH,
            ERROR_RECOGNIZER_BUSY,
            ERROR_INSUFFICIENT_PERMISSIONS,
            ERROR_TOO_MANY_REQUESTS,
            ERROR_SERVER_DISCONNECTED,
            ERROR_LANGUAGE_NOT_SUPPORTED,
            ERROR_LANGUAGE_UNAVAILABLE,
            ERROR_CANNOT_CHECK_SUPPORT,
            ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
    })
    public @interface RecognitionError {}

    /** Network operation timed out. */
    public static final int ERROR_NETWORK_TIMEOUT = 1;

    /** Other network related errors. */
    public static final int ERROR_NETWORK = 2;

    /** Audio recording error. */
    public static final int ERROR_AUDIO = 3;

    /** Server sends error status. */
    public static final int ERROR_SERVER = 4;

    /** Other client side errors. */
    public static final int ERROR_CLIENT = 5;

    /** No speech input */
    public static final int ERROR_SPEECH_TIMEOUT = 6;

    /** No recognition result matched. */
    public static final int ERROR_NO_MATCH = 7;

    /** RecognitionService busy. */
    public static final int ERROR_RECOGNIZER_BUSY = 8;

    /** Insufficient permissions */
    public static final int ERROR_INSUFFICIENT_PERMISSIONS = 9;

    /** Too many requests from the same client. */
    public static final int ERROR_TOO_MANY_REQUESTS = 10;

    /** Server has been disconnected, e.g. because the app has crashed. */
    public static final int ERROR_SERVER_DISCONNECTED = 11;

    /** Requested language is not available to be used with the current recognizer. */
    public static final int ERROR_LANGUAGE_NOT_SUPPORTED = 12;

    /** Requested language is supported, but not available currently (e.g. not downloaded yet). */
    public static final int ERROR_LANGUAGE_UNAVAILABLE = 13;

    /** The service does not allow to check for support. */
    public static final int ERROR_CANNOT_CHECK_SUPPORT = 14;

    /** The service does not support listening to model downloads events. */
    public static final int ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS = 15;

    /** action codes */
    private static final int MSG_START = 1;
    private static final int MSG_STOP = 2;
    private static final int MSG_CANCEL = 3;
    private static final int MSG_CHANGE_LISTENER = 4;
    private static final int MSG_SET_TEMPORARY_ON_DEVICE_COMPONENT = 5;
    private static final int MSG_CHECK_RECOGNITION_SUPPORT = 6;
    private static final int MSG_TRIGGER_MODEL_DOWNLOAD = 7;

    SpeechRecognizer() { }

    /**
     * Checks whether a speech recognition service is available on the system. If this method
     * returns {@code false}, {@link SpeechRecognizer#createSpeechRecognizer(Context)} will
     * fail.
     *
     * @param context with which {@code SpeechRecognizer} will be created
     * @return {@code true} if recognition is available, {@code false} otherwise
     */
    public static boolean isRecognitionAvailable(@NonNull Context context) {
        // TODO(b/176578753): make sure this works well with system speech recognizers.
        final List<ResolveInfo> list = context.getPackageManager().queryIntentServices(
                new Intent(RecognitionService.SERVICE_INTERFACE), 0);
        return list != null && list.size() != 0;
    }

    /**
     * Checks whether an on-device speech recognition service is available on the system. If this
     * method returns {@code false},
     * {@link SpeechRecognizer#createOnDeviceSpeechRecognizer(Context)} will
     * fail.
     *
     * @param context with which on-device {@code SpeechRecognizer} will be created
     * @return {@code true} if on-device recognition is available, {@code false} otherwise
     */
    public static boolean isOnDeviceRecognitionAvailable(@NonNull Context context) {
        ComponentName componentName =
                ComponentName.unflattenFromString(
                        context.getString(R.string.config_defaultOnDeviceSpeechRecognitionService));
        return componentName != null;
    }

    /**
     * Factory method to create a new {@code SpeechRecognizer}. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called before dispatching any
     * command to the created {@code SpeechRecognizer}, otherwise no notifications will be
     * received.
     *
     * <p><strong>Important:</strong> the caller MUST invoke {@link #destroy()} on a
     * SpeechRecognizer object when it is no longer needed.
     *
     * <p>For apps targeting Android 11 (API level 30) interaction with a speech recognition
     * service requires <queries> element to be added to the manifest file:
     * <pre>{@code
     * <queries>
     *   <intent>
     *     <action
     *        android:name="android.speech.RecognitionService" />
     *   </intent>
     * </queries>
     * }</pre>
     *
     * @param context in which to create {@code SpeechRecognizer}
     * @return a new {@code SpeechRecognizer}
     */
    @MainThread
    public static SpeechRecognizer createSpeechRecognizer(Context context) {
        return createSpeechRecognizer(context, null);
    }

    /**
     * Factory method to create a new {@code SpeechRecognizer}. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called before dispatching any
     * command to the created {@code SpeechRecognizer}, otherwise no notifications will be
     * received.
     * Use this version of the method to specify a specific service to direct this
     * {@link SpeechRecognizer} to.
     *
     * <p><strong>Important:</strong> the caller MUST invoke {@link #destroy()} on a
     * SpeechRecognizer object when it is no longer needed.
     *
     * <p><strong>Important</strong>: before calling this method, please check via
     * {@link android.content.pm.PackageManager#queryIntentServices(Intent, int)} that {@code
     * serviceComponent} actually exists and provides
     * {@link RecognitionService#SERVICE_INTERFACE}. Normally you would not use this; call
     * {@link #createSpeechRecognizer(Context)} to use the system default recognition
     * service instead or {@link #createOnDeviceSpeechRecognizer(Context)} to use on-device
     * recognition.</p>
     *
     * <p>For apps targeting Android 11 (API level 30) interaction with a speech recognition
     * service requires <queries> element to be added to the manifest file:
     * <pre>{@code
     * <queries>
     *   <intent>
     *     <action
     *        android:name="android.speech.RecognitionService" />
     *   </intent>
     * </queries>
     * }</pre>
     *
     * @param context          in which to create {@code SpeechRecognizer}
     * @param serviceComponent the {@link ComponentName} of a specific service to direct this
     *                         {@code SpeechRecognizer} to
     * @return a new {@code SpeechRecognizer}
     */
    @MainThread
    public static SpeechRecognizer createSpeechRecognizer(Context context,
                                                   ComponentName serviceComponent) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        SpeechRecognizerImpl.checkIsCalledFromMainThread();
        return wrapWithProxy(new SpeechRecognizerImpl(context, serviceComponent));
    }

    /**
     * Factory method to create a new {@code SpeechRecognizer}.
     *
     * <p>Please note that {@link #setRecognitionListener(RecognitionListener)} should be called
     * before dispatching any command to the created {@code SpeechRecognizer}, otherwise no
     * notifications will be received.
     *
     * <p><strong>Important:</strong> the caller MUST invoke {@link #destroy()} on a
     * SpeechRecognizer object when it is no longer needed.
     *
     * @param context in which to create {@code SpeechRecognizer}
     * @return a new on-device {@code SpeechRecognizer}.
     * @throws UnsupportedOperationException iff {@link #isOnDeviceRecognitionAvailable(Context)}
     *                                       is false
     */
    @NonNull
    @MainThread
    public static SpeechRecognizer createOnDeviceSpeechRecognizer(@NonNull Context context) {
        if (!isOnDeviceRecognitionAvailable(context)) {
            throw new UnsupportedOperationException("On-device recognition is not available");
        }
        return wrapWithProxy(SpeechRecognizerImpl.lenientlyCreateOnDeviceSpeechRecognizer(context));
    }

    private static SpeechRecognizer wrapWithProxy(SpeechRecognizer delegate) {
        return new SpeechRecognizerProxy(delegate);
    }

    /**
     * Helper method to create on-device SpeechRecognizer in tests even when the device does not
     * support on-device speech recognition.
     *
     * @hide
     */
    @TestApi
    @NonNull
    @MainThread
    public static SpeechRecognizer createOnDeviceTestingSpeechRecognizer(
            @NonNull Context context) {
        return wrapWithProxy(SpeechRecognizerImpl.lenientlyCreateOnDeviceSpeechRecognizer(context));
    }

    /**
     * Sets the listener that will receive all the callbacks. The previous unfinished commands will
     * be executed with the old listener, while any following command will be executed with the new
     * listener.
     *
     * @param listener listener that will receive all the callbacks from the created
     *                 {@link SpeechRecognizer}, this must not be null.
     */
    @MainThread
    public void setRecognitionListener(RecognitionListener listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Starts listening for speech. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called beforehand, otherwise
     * no notifications will be received.
     *
     * @param recognizerIntent contains parameters for the recognition to be performed. The intent
     *                         may also contain optional extras, see {@link RecognizerIntent}. If
     *                         these values are not set explicitly, default values will be used by
     *                         the recognizer.
     */
    @MainThread
    public void startListening(Intent recognizerIntent) {
        throw new UnsupportedOperationException();
    }

    /**
     * Stops listening for speech. Speech captured so far will be recognized as if the user had
     * stopped speaking at this point.
     *
     * <p>Note that in the default case, this does not need to be called, as the speech endpointer
     * will automatically stop the recognizer listening when it determines speech has completed.
     * However, you can manipulate endpointer parameters directly using the intent extras defined in
     * {@link RecognizerIntent}, in which case you may sometimes want to manually call this method
     * to stop listening sooner.
     *
     * <p>Upon invocation clients must wait until {@link RecognitionListener#onResults} or
     * {@link RecognitionListener#onError} are invoked before calling
     * {@link SpeechRecognizer#startListening} again. Otherwise such an attempt would be rejected by
     * recognition service.
     *
     * <p>Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called beforehand, otherwise
     * no notifications will be received.
     */
    @MainThread
    public void stopListening() {
        throw new UnsupportedOperationException();
    }

    /**
     * Cancels the speech recognition. Please note that
     * {@link #setRecognitionListener(RecognitionListener)} should be called beforehand, otherwise
     * no notifications will be received.
     */
    @MainThread
    public void cancel() {
        throw new UnsupportedOperationException();
    }

    /**
     * Checks whether {@code recognizerIntent} is supported by
     * {@link SpeechRecognizer#startListening(Intent)}.
     *
     * @param recognizerIntent contains parameters for the recognition to be performed. The intent
     *                         may also contain optional extras. See {@link RecognizerIntent} for
     *                         the list of supported extras, any unlisted extra might be ignored.
     * @param supportListener  the listener on which to receive the support query results.
     */
    public void checkRecognitionSupport(
            @NonNull Intent recognizerIntent,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull RecognitionSupportCallback supportListener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to download the support for the given {@code recognizerIntent}. This might trigger
     * user interaction to approve the download. Callers can verify the status of the request via
     * {@link #checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)}.
     *
     * @param recognizerIntent contains parameters for the recognition to be performed. The intent
     *                         may also contain optional extras, see {@link RecognizerIntent}.
     */
    public void triggerModelDownload(@NonNull Intent recognizerIntent) {
        throw new UnsupportedOperationException();
    }

    /**
     * Attempts to download the support for the given {@code recognizerIntent}. This might trigger
     * user interaction to approve the download. Callers can verify the status of the request via
     * {@link #checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)}.
     *
     * <p> The updates about the model download request are received via the given
     * {@link ModelDownloadListener}:
     *
     * <li> If the model is already available, {@link ModelDownloadListener#onSuccess()} will be
     * called directly. The model can be safely used afterwards.
     *
     * <li> If the {@link RecognitionService} has started the download,
     * {@link ModelDownloadListener#onProgress(int)} will be called an unspecified (zero or more)
     * number of times until the download is complete.
     * When the download finishes, {@link ModelDownloadListener#onSuccess()} will be called.
     * The model can be safely used afterwards.
     *
     * <li> If the {@link RecognitionService} has only scheduled the download, but won't satisfy it
     * immediately, {@link ModelDownloadListener#onScheduled()} will be called.
     * There will be no further updates on this listener.
     *
     * <li> If the request fails at any time due to a network or scheduling error,
     * {@link ModelDownloadListener#onError(int)} will be called.
     *
     * @param recognizerIntent contains parameters for the recognition to be performed. The intent
     *                         may also contain optional extras, see {@link RecognizerIntent}.
     * @param executor         for dispatching listener callbacks
     * @param listener         on which to receive updates about the model download request.
     */
    public void triggerModelDownload(
            @NonNull Intent recognizerIntent,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ModelDownloadListener listener) {
        throw new UnsupportedOperationException();
    }

    /**
     * Sets a temporary component to power on-device speech recognizer.
     *
     * <p>This is only expected to be called in tests, system would reject calls from client apps.
     *
     * @param componentName name of the component to set temporary replace speech recognizer. {@code
     *                      null} value resets the recognizer to default.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_SPEECH_RECOGNITION)
    public void setTemporaryOnDeviceRecognizer(@Nullable ComponentName componentName) {
        throw new UnsupportedOperationException();
    }

    /**
     * Destroys the {@code SpeechRecognizer} object.
     */
    public void destroy() {
        throw new UnsupportedOperationException();
    }
}
