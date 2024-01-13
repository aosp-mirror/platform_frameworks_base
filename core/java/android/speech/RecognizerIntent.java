/*
 * Copyright (C) 2008 The Android Open Source Project
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

import static android.speech.flags.Flags.FLAG_MULTILANG_EXTRA_LAUNCH;

import android.annotation.FlaggedApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import java.util.ArrayList;

/**
 * Constants for supporting speech recognition through starting an {@link Intent}
 */
public class RecognizerIntent {

    private RecognizerIntent() {
        // Not for instantiating.
    }

    /**
     * Starts an activity that will prompt the user for speech and send it through a
     * speech recognizer.  The results will be returned via activity results (in
     * {@link Activity#onActivityResult}, if you start the intent using
     * {@link Activity#startActivityForResult(Intent, int)}), or forwarded via a PendingIntent
     * if one is provided.
     *
     * <p>Starting this intent with just {@link Activity#startActivity(Intent)} is not supported.
     * You must either use {@link Activity#startActivityForResult(Intent, int)}, or provide a
     * PendingIntent, to receive recognition results.
     *
     * <p>The implementation of this API is likely to stream audio to remote servers to perform
     * speech recognition which can use a substantial amount of bandwidth.
     *
     * <p>Required extras:
     * <ul>
     *   <li>{@link #EXTRA_LANGUAGE_MODEL}
     * </ul>
     *
     * <p>Optional extras:
     * <ul>
     *   <li>{@link #EXTRA_PROMPT}
     *   <li>{@link #EXTRA_LANGUAGE}
     *   <li>{@link #EXTRA_MAX_RESULTS}
     *   <li>{@link #EXTRA_RESULTS_PENDINGINTENT}
     *   <li>{@link #EXTRA_RESULTS_PENDINGINTENT_BUNDLE}
     * </ul>
     *
     * <p> Result extras (returned in the result, not to be specified in the request):
     * <ul>
     *   <li>{@link #EXTRA_RESULTS}
     * </ul>
     *
     * <p>NOTE: There may not be any applications installed to handle this action, so you should
     * make sure to catch {@link ActivityNotFoundException}.
     */
    public static final String ACTION_RECOGNIZE_SPEECH = "android.speech.action.RECOGNIZE_SPEECH";

    /**
     * Starts an activity that will prompt the user for speech, send it through a
     * speech recognizer, and either display a web search result or trigger
     * another type of action based on the user's speech.
     *
     * <p>If you want to avoid triggering any type of action besides web search, you can use
     * the {@link #EXTRA_WEB_SEARCH_ONLY} extra.
     *
     * <p>Required extras:
     * <ul>
     *   <li>{@link #EXTRA_LANGUAGE_MODEL}
     * </ul>
     *
     * <p>Optional extras:
     * <ul>
     *   <li>{@link #EXTRA_PROMPT}
     *   <li>{@link #EXTRA_LANGUAGE}
     *   <li>{@link #EXTRA_MAX_RESULTS}
     *   <li>{@link #EXTRA_PARTIAL_RESULTS}
     *   <li>{@link #EXTRA_WEB_SEARCH_ONLY}
     *   <li>{@link #EXTRA_ORIGIN}
     * </ul>
     *
     * <p> Result extras (returned in the result, not to be specified in the request):
     * <ul>
     *   <li>{@link #EXTRA_RESULTS}
     *   <li>{@link #EXTRA_CONFIDENCE_SCORES} (optional)
     * </ul>
     *
     * <p>NOTE: There may not be any applications installed to handle this action, so you should
     * make sure to catch {@link ActivityNotFoundException}.
     */
    public static final String ACTION_WEB_SEARCH = "android.speech.action.WEB_SEARCH";

    /**
     * Starts an activity that will prompt the user for speech without requiring the user's
     * visual attention or touch input. It will send it through a speech recognizer,
     * and either synthesize speech for a web search result or trigger
     * another type of action based on the user's speech.
     *
     * This activity may be launched while device is locked in a secure mode.
     * Special care must be taken to ensure that the voice actions that are performed while
     * hands free cannot compromise the device's security.
     * The activity should check the value of the {@link #EXTRA_SECURE} extra to determine
     * whether the device has been securely locked. If so, the activity should either restrict
     * the set of voice actions that are permitted or require some form of secure
     * authentication before proceeding.
     *
     * To ensure that the activity's user interface is visible while the lock screen is showing,
     * the activity should set the
     * {@link android.view.WindowManager.LayoutParams#FLAG_SHOW_WHEN_LOCKED} window flag.
     * Otherwise the activity's user interface may be hidden by the lock screen. The activity
     * should take care not to leak private information when the device is securely locked.
     *
     * <p>Optional extras:
     * <ul>
     *   <li>{@link #EXTRA_SECURE}
     * </ul>
     *
     * <p class="note">
     * In some cases, a matching Activity may not exist, so ensure you
     * safeguard against this.
     */
    public static final String ACTION_VOICE_SEARCH_HANDS_FREE =
            "android.speech.action.VOICE_SEARCH_HANDS_FREE";

    /**
     * Optional {@link android.os.ParcelFileDescriptor} pointing to an already opened audio
     * source for the recognizer to use. The caller of the recognizer is responsible for closing
     * the audio. If this extra is not set or the recognizer does not support this feature, the
     * recognizer will open the mic for audio and close it when the recognition is finished.
     *
     * <p>Along with this extra, please send {@link #EXTRA_AUDIO_SOURCE_CHANNEL_COUNT},
     * {@link #EXTRA_AUDIO_SOURCE_ENCODING}, and {@link #EXTRA_AUDIO_SOURCE_SAMPLING_RATE}
     * extras, otherwise the default values of these extras will be used.
     *
     * <p>Additionally, {@link #EXTRA_ENABLE_BIASING_DEVICE_CONTEXT} may have no effect when this
     * extra is set.
     *
     * <p>This can also be used as the string value for {@link #EXTRA_SEGMENTED_SESSION} to
     * enable segmented session mode. The audio must be passed in using this extra. The
     * recognition session will end when and only when the audio is closed.
     *
     * @see #EXTRA_SEGMENTED_SESSION
     */
    public static final String EXTRA_AUDIO_SOURCE = "android.speech.extra.AUDIO_SOURCE";

    /**
     * Optional integer, to be used with {@link #EXTRA_AUDIO_SOURCE}, to indicate the number of
     * channels in the audio. The default value is 1.
     */
    public static final String EXTRA_AUDIO_SOURCE_CHANNEL_COUNT =
            "android.speech.extra.AUDIO_SOURCE_CHANNEL_COUNT";

    /**
     * Optional integer (from {@link android.media.AudioFormat}), to be used with
     * {@link #EXTRA_AUDIO_SOURCE}, to indicate the audio encoding. The default value is
     * {@link android.media.AudioFormat#ENCODING_PCM_16BIT}.
     */
    public static final String EXTRA_AUDIO_SOURCE_ENCODING =
            "android.speech.extra.AUDIO_SOURCE_ENCODING";

    /**
     * Optional integer, to be used with {@link #EXTRA_AUDIO_SOURCE}, to indicate the sampling
     * rate of the audio. The default value is 16000.
     */
    public static final String EXTRA_AUDIO_SOURCE_SAMPLING_RATE =
            "android.speech.extra.AUDIO_SOURCE_SAMPLING_RATE";

    /**
     * Optional boolean to enable biasing towards device context. The recognizer will use the
     * device context to tune the recognition results.
     *
     * <p>Depending on the recognizer implementation, this value may have no effect.
     */
    public static final String EXTRA_ENABLE_BIASING_DEVICE_CONTEXT =
            "android.speech.extra.ENABLE_BIASING_DEVICE_CONTEXT";

    /**
     * Optional list of strings, towards which the recognizer should bias the recognition results.
     * These are separate from the device context.
     */
    public static final String EXTRA_BIASING_STRINGS = "android.speech.extra.BIASING_STRINGS";

    /**
     * Optional string to enable text formatting (e.g. unspoken punctuation (examples: question
     * mark, comma, period, etc.), capitalization, etc.) and specify the optimization strategy.
     * If set, the partial and final result texts will be formatted. Each result list will
     * contain two hypotheses in the order of 1) formatted text 2) raw text.
     *
     * <p>Depending on the recognizer implementation, this value may have no effect.
     *
     * @see #FORMATTING_OPTIMIZE_QUALITY
     * @see #FORMATTING_OPTIMIZE_LATENCY
     */
    public static final String EXTRA_ENABLE_FORMATTING = "android.speech.extra.ENABLE_FORMATTING";

    /**
     * Optimizes formatting quality. This will increase latency but provide the highest
     * punctuation quality. This is a value to use for {@link #EXTRA_ENABLE_FORMATTING}.
     *
     * @see #EXTRA_ENABLE_FORMATTING
     */
    public static final String FORMATTING_OPTIMIZE_QUALITY = "quality";
    /**
     * Optimizes formatting latency. This will result in a slightly lower quality of punctuation
     * but can improve the experience for real-time use cases. This is a value to use for
     * {@link #EXTRA_ENABLE_FORMATTING}.
     *
     * @see #EXTRA_ENABLE_FORMATTING
     */
    public static final String FORMATTING_OPTIMIZE_LATENCY = "latency";

    /**
     * Optional boolean, to be used with {@link #EXTRA_ENABLE_FORMATTING}, to prevent the
     * recognizer adding punctuation after the last word of the partial results. The default is
     * false.
     */
    public static final String EXTRA_HIDE_PARTIAL_TRAILING_PUNCTUATION =
            "android.speech.extra.HIDE_PARTIAL_TRAILING_PUNCTUATION";

    /**
     * Optional boolean indicating whether the recognizer should mask the offensive words in
     * recognition results. The Default is true.
     */
    public static final String EXTRA_MASK_OFFENSIVE_WORDS =
            "android.speech.extra.MASK_OFFENSIVE_WORDS";

    /**
     * The extra key used in an intent to the speech recognizer for voice search. Not
     * generally to be used by developers. The system search dialog uses this, for example,
     * to set a calling package for identification by a voice search API. If this extra
     * is set by anyone but the system process, it should be overridden by the voice search
     * implementation.
     */
    public static final String EXTRA_CALLING_PACKAGE = "calling_package";

    /**
     * The extra key used in an intent which is providing an already opened audio source for the
     * RecognitionService to use. Data should be a URI to an audio resource.
     *
     * <p>Depending on the recognizer implementation, this value may have no effect.
     *
     * @deprecated Replaced with {@link #EXTRA_AUDIO_SOURCE}
     */
    @Deprecated
    public static final String EXTRA_AUDIO_INJECT_SOURCE =
            "android.speech.extra.AUDIO_INJECT_SOURCE";

    /**
     * Optional boolean to indicate that a "hands free" voice search was performed while the device
     * was in a secure mode. An example of secure mode is when the device's screen lock is active,
     * and it requires some form of authentication to be unlocked.
     *
     * When the device is securely locked, the voice search activity should either restrict
     * the set of voice actions that are permitted, or require some form of secure authentication
     * before proceeding.
     */
    public static final String EXTRA_SECURE = "android.speech.extras.EXTRA_SECURE";

    /**
     * Optional integer to indicate the minimum length of the recognition session. The recognizer
     * will not stop recognizing speech before this amount of time.
     *
     * <p>Note that it is extremely rare you'd want to specify this value in an intent.
     * Generally, it should be specified only when it is also used as the value for
     * {@link #EXTRA_SEGMENTED_SESSION} to enable segmented session mode. Note also that certain
     * values may cause undesired or unexpected results - use judiciously!
     *
     * <p>Depending on the recognizer implementation, these values may have no effect.
     */
    public static final String EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS =
            "android.speech.extras.SPEECH_INPUT_MINIMUM_LENGTH_MILLIS";

    /**
     * The amount of time that it should take after the recognizer stops hearing speech to
     * consider the input complete hence end the recognition session.
     *
     * <p>Note that it is extremely rare you'd want to specify this value in an intent.
     * Generally, it should be specified only when it is also used as the value for
     * {@link #EXTRA_SEGMENTED_SESSION} to enable segmented session mode. Note also that certain
     * values may cause undesired or unexpected results - use judiciously!
     *
     * <p>Depending on the recognizer implementation, these values may have no effect.
     */
    public static final String EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS =
            "android.speech.extras.SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS";

    /**
     * The amount of time that it should take after we stop hearing speech to consider the input
     * possibly complete. This is used to prevent the endpointer cutting off during very short
     * mid-speech pauses.
     *
     * Note that it is extremely rare you'd want to specify this value in an intent. If
     * you don't have a very good reason to change these, you should leave them as they are. Note
     * also that certain values may cause undesired or unexpected results - use judiciously!
     * Additionally, depending on the recognizer implementation, these values may have no effect.
     */
    public static final String EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS =
            "android.speech.extras.SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS";

    /**
     * Informs the recognizer which speech model to prefer when performing
     * {@link #ACTION_RECOGNIZE_SPEECH}. The recognizer uses this
     * information to fine tune the results. This extra is required. Activities implementing
     * {@link #ACTION_RECOGNIZE_SPEECH} may interpret the values as they see fit.
     *
     *  @see #LANGUAGE_MODEL_FREE_FORM
     *  @see #LANGUAGE_MODEL_WEB_SEARCH
     */
    public static final String EXTRA_LANGUAGE_MODEL = "android.speech.extra.LANGUAGE_MODEL";

    /**
     * Use a language model based on free-form speech recognition.  This is a value to use for
     * {@link #EXTRA_LANGUAGE_MODEL}.
     * @see #EXTRA_LANGUAGE_MODEL
     */
    public static final String LANGUAGE_MODEL_FREE_FORM = "free_form";
    /**
     * Use a language model based on web search terms.  This is a value to use for
     * {@link #EXTRA_LANGUAGE_MODEL}.
     * @see #EXTRA_LANGUAGE_MODEL
     */
    public static final String LANGUAGE_MODEL_WEB_SEARCH = "web_search";

    /** Optional text prompt to show to the user when asking them to speak. */
    public static final String EXTRA_PROMPT = "android.speech.extra.PROMPT";

    /**
     * Optional IETF language tag (as defined by BCP 47), for example "en-US". This tag informs the
     * recognizer to perform speech recognition in a language different than the one set in the
     * {@link java.util.Locale#getDefault()}.
     */
    public static final String EXTRA_LANGUAGE = "android.speech.extra.LANGUAGE";

    /**
     * Optional value which can be used to indicate the referer url of a page in which
     * speech was requested. For example, a web browser may choose to provide this for
     * uses of speech on a given page.
     */
    public static final String EXTRA_ORIGIN = "android.speech.extra.ORIGIN";

    /**
     * Optional limit on the maximum number of results to return. If omitted the recognizer
     * will choose how many results to return. Must be an integer.
     */
    public static final String EXTRA_MAX_RESULTS = "android.speech.extra.MAX_RESULTS";

    /**
     * Optional boolean, to be used with {@link #ACTION_WEB_SEARCH}, to indicate whether to
     * only fire web searches in response to a user's speech. The default is false, meaning
     * that other types of actions can be taken based on the user's speech.
     */
    public static final String EXTRA_WEB_SEARCH_ONLY = "android.speech.extra.WEB_SEARCH_ONLY";

    /**
     * Optional boolean to indicate whether partial results should be returned by the recognizer
     * as the user speaks (default is false).  The server may ignore a request for partial
     * results in some or all cases.
     */
    public static final String EXTRA_PARTIAL_RESULTS = "android.speech.extra.PARTIAL_RESULTS";

    /**
     * When the intent is {@link #ACTION_RECOGNIZE_SPEECH}, the speech input activity will
     * return results to you via the activity results mechanism.  Alternatively, if you use this
     * extra to supply a PendingIntent, the results will be added to its bundle and the
     * PendingIntent will be sent to its target.
     */
    public static final String EXTRA_RESULTS_PENDINGINTENT =
            "android.speech.extra.RESULTS_PENDINGINTENT";

    /**
     * If you use {@link #EXTRA_RESULTS_PENDINGINTENT} to supply a forwarding intent, you can
     * also use this extra to supply additional extras for the final intent.  The search results
     * will be added to this bundle, and the combined bundle will be sent to the target.
     */
    public static final String EXTRA_RESULTS_PENDINGINTENT_BUNDLE =
            "android.speech.extra.RESULTS_PENDINGINTENT_BUNDLE";

    /** Result code returned when no matches are found for the given speech */
    public static final int RESULT_NO_MATCH = Activity.RESULT_FIRST_USER;
    /** Result code returned when there is a generic client error */
    public static final int RESULT_CLIENT_ERROR = Activity.RESULT_FIRST_USER + 1;
    /** Result code returned when the recognition server returns an error */
    public static final int RESULT_SERVER_ERROR = Activity.RESULT_FIRST_USER + 2;
    /** Result code returned when a network error was encountered */
    public static final int RESULT_NETWORK_ERROR = Activity.RESULT_FIRST_USER + 3;
    /** Result code returned when an audio error was encountered */
    public static final int RESULT_AUDIO_ERROR = Activity.RESULT_FIRST_USER + 4;

    /**
     * An ArrayList&lt;String&gt; of the recognition results when performing
     * {@link #ACTION_RECOGNIZE_SPEECH}. Generally this list should be ordered in
     * descending order of speech recognizer confidence. (See {@link #EXTRA_CONFIDENCE_SCORES}).
     * Returned in the results; not to be specified in the recognition request. Only present
     * when {@link Activity#RESULT_OK} is returned in an activity result. In a PendingIntent,
     * the lack of this extra indicates failure.
     */
    public static final String EXTRA_RESULTS = "android.speech.extra.RESULTS";

    /**
     * A float array of confidence scores of the recognition results when performing
     * {@link #ACTION_RECOGNIZE_SPEECH}. The array should be the same size as the ArrayList
     * returned in {@link #EXTRA_RESULTS}, and should contain values ranging from 0.0 to 1.0,
     * or -1 to represent an unavailable confidence score.
     * <p>
     * Confidence values close to 1.0 indicate high confidence (the speech recognizer is
     * confident that the recognition result is correct), while values close to 0.0 indicate
     * low confidence.
     * <p>
     * Returned in the results; not to be specified in the recognition request. This extra is
     * optional and might not be provided. Only present when {@link Activity#RESULT_OK} is
     * returned in an activity result.
     */
    public static final String EXTRA_CONFIDENCE_SCORES = "android.speech.extra.CONFIDENCE_SCORES";

    /**
     * Returns the broadcast intent to fire with
     * {@link Context#sendOrderedBroadcast(Intent, String, BroadcastReceiver, android.os.Handler, int, String, Bundle)}
     * to receive details from the package that implements voice search.
     * <p>
     * This is based on the value specified by the voice search {@link Activity} in
     * {@link #DETAILS_META_DATA}, and if this is not specified, will return null. Also if there
     * is no chosen default to resolve for {@link #ACTION_WEB_SEARCH}, this will return null.
     * <p>
     * If an intent is returned and is fired, a {@link Bundle} of extras will be returned to the
     * provided result receiver, and should ideally contain values for
     * {@link #EXTRA_LANGUAGE_PREFERENCE} and {@link #EXTRA_SUPPORTED_LANGUAGES}.
     * <p>
     * (Whether these are actually provided is up to the particular implementation. It is
     * recommended that {@link Activity}s implementing {@link #ACTION_WEB_SEARCH} provide this
     * information, but it is not required.)
     *
     * @param context a context object
     * @return the broadcast intent to fire or null if not available
     */
    public static final Intent getVoiceDetailsIntent(Context context) {
        Intent voiceSearchIntent = new Intent(ACTION_WEB_SEARCH);
        ResolveInfo ri = context.getPackageManager().resolveActivity(
                voiceSearchIntent, PackageManager.GET_META_DATA);
        if (ri == null || ri.activityInfo == null || ri.activityInfo.metaData == null) return null;

        String className = ri.activityInfo.metaData.getString(DETAILS_META_DATA);
        if (className == null) return null;

        Intent detailsIntent = new Intent(ACTION_GET_LANGUAGE_DETAILS);
        detailsIntent.setComponent(new ComponentName(ri.activityInfo.packageName, className));
        return detailsIntent;
    }

    /**
     * Meta-data name under which an {@link Activity} implementing {@link #ACTION_WEB_SEARCH} can
     * use to expose the class name of a {@link BroadcastReceiver} which can respond to request for
     * more information, from any of the broadcast intents specified in this class.
     * <p>
     * Broadcast intents can be directed to the class name specified in the meta-data by creating
     * an {@link Intent}, setting the component with
     * {@link Intent#setComponent(android.content.ComponentName)}, and using
     * {@link Context#sendOrderedBroadcast(Intent, String, BroadcastReceiver, android.os.Handler, int, String, android.os.Bundle)}
     * with another {@link BroadcastReceiver} which can receive the results.
     * <p>
     * The {@link #getVoiceDetailsIntent(Context)} method is provided as a convenience to create
     * a broadcast intent based on the value of this meta-data, if available.
     * <p>
     * This is optional and not all {@link Activity}s which implement {@link #ACTION_WEB_SEARCH}
     * are required to implement this. Thus retrieving this meta-data may be null.
     */
    public static final String DETAILS_META_DATA = "android.speech.DETAILS";

    /**
     * A broadcast intent which can be fired to the {@link BroadcastReceiver} component specified
     * in the meta-data defined in the {@link #DETAILS_META_DATA} meta-data of an
     * {@link Activity} satisfying {@link #ACTION_WEB_SEARCH}.
     * <p>
     * When fired with
     * {@link Context#sendOrderedBroadcast(Intent, String, BroadcastReceiver, android.os.Handler, int, String, android.os.Bundle)},
     * a {@link Bundle} of extras will be returned to the provided result receiver, and should
     * ideally contain values for {@link #EXTRA_LANGUAGE_PREFERENCE} and
     * {@link #EXTRA_SUPPORTED_LANGUAGES}.
     * <p>
     * (Whether these are actually provided is up to the particular implementation. It is
     * recommended that {@link Activity}s implementing {@link #ACTION_WEB_SEARCH} provide this
     * information, but it is not required.)
     */
    public static final String ACTION_GET_LANGUAGE_DETAILS =
            "android.speech.action.GET_LANGUAGE_DETAILS";

    /**
     * Specify this boolean extra in a broadcast of {@link #ACTION_GET_LANGUAGE_DETAILS} to
     * indicate that only the current language preference is needed in the response. This
     * avoids any additional computation if all you need is {@link #EXTRA_LANGUAGE_PREFERENCE}
     * in the response.
     */
    public static final String EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE =
            "android.speech.extra.ONLY_RETURN_LANGUAGE_PREFERENCE";

    /**
     * The key to the extra in the {@link Bundle} returned by {@link #ACTION_GET_LANGUAGE_DETAILS}
     * which is a {@link String} that represents the current language preference this user has
     * specified - a locale string like "en-US".
     */
    public static final String EXTRA_LANGUAGE_PREFERENCE =
            "android.speech.extra.LANGUAGE_PREFERENCE";

    /**
     * The key to the extra in the {@link Bundle} returned by {@link #ACTION_GET_LANGUAGE_DETAILS}
     * which is an {@link ArrayList} of {@link String}s that represents the languages supported by
     * this implementation of voice recognition - a list of strings like "en-US", "cmn-Hans-CN",
     * etc.
     */
    public static final String EXTRA_SUPPORTED_LANGUAGES =
            "android.speech.extra.SUPPORTED_LANGUAGES";

    /**
     * Optional boolean, to be used with {@link #ACTION_RECOGNIZE_SPEECH},
     * {@link #ACTION_VOICE_SEARCH_HANDS_FREE}, {@link #ACTION_WEB_SEARCH} to indicate whether to
     * only use an offline speech recognition engine. The default is false, meaning that either
     * network or offline recognition engines may be used.
     *
     * <p>Depending on the recognizer implementation, these values may have
     * no effect.</p>
     *
     */
    public static final String EXTRA_PREFER_OFFLINE = "android.speech.extra.PREFER_OFFLINE";

    /**
     * Optional string to enable segmented session mode of the specified type, which can be
     * {@link #EXTRA_AUDIO_SOURCE}, {@link #EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS} or
     * {@link #EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS}. When segmented session mode is
     * supported by the recognizer implementation and this extra is set, it will return the
     * recognition results in segments via {@link RecognitionListener#onSegmentResults(Bundle)}
     * and terminate the session with {@link RecognitionListener#onEndOfSegmentedSession()}.
     *
     * <p>When setting this extra, make sure the extra used as the string value here is also set
     * in the same intent with proper value.
     *
     * <p>Depending on the recognizer implementation, this value may have no effect.
     *
     * @see #EXTRA_AUDIO_SOURCE
     * @see #EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS
     * @see #EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS
     */
    public static final String EXTRA_SEGMENTED_SESSION = "android.speech.extra.SEGMENTED_SESSION";

    /**
     * Optional boolean indicating whether the recognizer should return the timestamp
     * of each word in the final recognition results.
     */
    public static final String EXTRA_REQUEST_WORD_TIMING =
            "android.speech.extra.REQUEST_WORD_TIMING";

    /**
     * Optional boolean indicating whether the recognizer should return the confidence
     * level of each word in the final recognition results.
     */
    public static final String EXTRA_REQUEST_WORD_CONFIDENCE =
            "android.speech.extra.REQUEST_WORD_CONFIDENCE";

    /**
     * Optional boolean indicating whether to enable language detection. When enabled, the
     * recognizer will consistently identify the language of the current spoken utterance and
     * provide that info via {@link RecognitionListener#onLanguageDetection(Bundle)}.
     *
     * <p> Depending on the recognizer implementation, this flag may have no effect.
     */
    public static final String EXTRA_ENABLE_LANGUAGE_DETECTION =
            "android.speech.extra.ENABLE_LANGUAGE_DETECTION";

    /**
     * Optional list of IETF language tags (as defined by BCP 47, e.g. "en-US", "de-DE").
     * This extra is to be used with {@link #EXTRA_ENABLE_LANGUAGE_DETECTION}.
     * If set, the recognizer will constrain the language detection output
     * to this list of languages, potentially improving detection accuracy.
     */
    public static final String EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES =
            "android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES";

    /**
     * Optional string to enable automatic switching to the language being spoken with
     * the desired sensitivity level, instead of being restricted to a single language.
     * The corresponding language models must be downloaded to support the switch.
     * Otherwise, the recognizer will report an error on a switch failure. The recognizer
     * provides the switch results via {@link RecognitionListener#onLanguageDetection(Bundle)}.
     *
     * <p> Since detection is a necessary requirement for the language switching,
     * setting this value implicitly enables {@link #EXTRA_ENABLE_LANGUAGE_DETECTION}.
     *
     * <p> Depending on the recognizer implementation, this value may have no effect.
     *
     * @see #LANGUAGE_SWITCH_HIGH_PRECISION
     * @see #LANGUAGE_SWITCH_BALANCED
     * @see #LANGUAGE_SWITCH_QUICK_RESPONSE
     */
    public static final String EXTRA_ENABLE_LANGUAGE_SWITCH =
            "android.speech.extra.ENABLE_LANGUAGE_SWITCH";

    /**
     * A value to use for {@link #EXTRA_ENABLE_LANGUAGE_SWITCH}.
     *
     * <p> Enables language switch only when a new language is detected as
     * {@link SpeechRecognizer#LANGUAGE_DETECTION_CONFIDENCE_LEVEL_HIGHLY_CONFIDENT},
     * which means the service may wait for longer before switching.
     *
     * @see #EXTRA_ENABLE_LANGUAGE_SWITCH
     */
    public static final String LANGUAGE_SWITCH_HIGH_PRECISION = "high_precision";

    /**
     * A value to use for {@link #EXTRA_ENABLE_LANGUAGE_SWITCH}.
     *
     * <p> Enables language switch only when a new language is detected as at least
     * {@link SpeechRecognizer#LANGUAGE_DETECTION_CONFIDENCE_LEVEL_CONFIDENT}, which means
     * the service is balancing between detecting a new language confidently and switching early.
     *
     * @see #EXTRA_ENABLE_LANGUAGE_SWITCH
     */
    public static final String LANGUAGE_SWITCH_BALANCED = "balanced";

    /**
     * A value to use for {@link #EXTRA_ENABLE_LANGUAGE_SWITCH}.
     *
     * <p> Enables language switch only when a new language is detected as at least
     * {@link SpeechRecognizer#LANGUAGE_DETECTION_CONFIDENCE_LEVEL_NOT_CONFIDENT},
     * which means the service should switch at the earliest moment possible.
     *
     * @see #EXTRA_ENABLE_LANGUAGE_SWITCH
     */
    public static final String LANGUAGE_SWITCH_QUICK_RESPONSE = "quick_response";

    /**
     * Optional list of IETF language tags (as defined by BCP 47, e.g. "en-US", "de-DE"). This extra
     * is to be used with {@link #EXTRA_ENABLE_LANGUAGE_SWITCH}. If set, the recognizer will apply
     * the auto switch only to these languages, even if the speech models of other languages also
     * exist. The corresponding language models must be downloaded to support the switch.
     * Otherwise, the recognizer will report an error on a switch failure.
     */
    public static final String EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES =
            "android.speech.extra.LANGUAGE_SWITCH_ALLOWED_LANGUAGES";

    /**
     * Optional integer to use for {@link #EXTRA_ENABLE_LANGUAGE_SWITCH}. If set, the language
     * switch will be deactivated when LANGUAGE_SWITCH_MAX_SWITCHES reached.
     *
     * <p> Depending on the recognizer implementation, this flag may have no effect.
     *
     * @see #EXTRA_ENABLE_LANGUAGE_SWITCH
     */
    @FlaggedApi(FLAG_MULTILANG_EXTRA_LAUNCH)
    public static final String EXTRA_LANGUAGE_SWITCH_MAX_SWITCHES =
            "android.speech.extra.LANGUAGE_SWITCH_MAX_SWITCHES";

    /**
     * Optional integer to use for {@link #EXTRA_ENABLE_LANGUAGE_SWITCH}. If set, the language
     * switch will only be activated for this value of ms of audio since the START_OF_SPEECH. This
     * could provide a more stable recognition result when the language switch is only required in
     * the beginning of the session.
     *
     * <p> Depending on the recognizer implementation, this flag may have no effect.
     *
     * @see #EXTRA_ENABLE_LANGUAGE_SWITCH
     */
    @FlaggedApi(FLAG_MULTILANG_EXTRA_LAUNCH)
    public static final String EXTRA_LANGUAGE_SWITCH_INITIAL_ACTIVE_DURATION_TIME_MILLIS =
            "android.speech.extra.LANGUAGE_SWITCH_INITIAL_ACTIVE_DURATION_TIME_MILLIS";
}
