/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.webkit;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.Engine;
import android.speech.tts.TextToSpeech.OnInitListener;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebViewCore.EventHub;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles injecting accessibility JavaScript and related JavaScript -> Java
 * APIs.
 */
class AccessibilityInjector {
    private static final String TAG = AccessibilityInjector.class.getSimpleName();

    private static boolean DEBUG = false;

    // The WebViewClassic this injector is responsible for managing.
    private final WebViewClassic mWebViewClassic;

    // Cached reference to mWebViewClassic.getContext(), for convenience.
    private final Context mContext;

    // Cached reference to mWebViewClassic.getWebView(), for convenience.
    private final WebView mWebView;

    // The Java objects that are exposed to JavaScript.
    private TextToSpeechWrapper mTextToSpeech;
    private CallbackHandler mCallback;

    // Lazily loaded helper objects.
    private AccessibilityManager mAccessibilityManager;
    private AccessibilityInjectorFallback mAccessibilityInjectorFallback;
    private JSONObject mAccessibilityJSONObject;

    // Whether the accessibility script has been injected into the current page.
    private boolean mAccessibilityScriptInjected;

    // Constants for determining script injection strategy.
    private static final int ACCESSIBILITY_SCRIPT_INJECTION_UNDEFINED = -1;
    private static final int ACCESSIBILITY_SCRIPT_INJECTION_OPTED_OUT = 0;
    @SuppressWarnings("unused")
    private static final int ACCESSIBILITY_SCRIPT_INJECTION_PROVIDED = 1;

    // Alias for TTS API exposed to JavaScript.
    private static final String ALIAS_TTS_JS_INTERFACE = "accessibility";

    // Alias for traversal callback exposed to JavaScript.
    private static final String ALIAS_TRAVERSAL_JS_INTERFACE = "accessibilityTraversal";

    // Template for JavaScript that injects a screen-reader.
    private static final String ACCESSIBILITY_SCREEN_READER_JAVASCRIPT_TEMPLATE =
            "javascript:(function() {" +
                    "    var chooser = document.createElement('script');" +
                    "    chooser.type = 'text/javascript';" +
                    "    chooser.src = '%1s';" +
                    "    document.getElementsByTagName('head')[0].appendChild(chooser);" +
                    "  })();";

    // Template for JavaScript that performs AndroidVox actions.
    private static final String ACCESSIBILITY_ANDROIDVOX_TEMPLATE =
            "(function() {" +
                    "  if ((typeof(cvox) != 'undefined')" +
                    "      && (cvox != null)" +
                    "      && (typeof(cvox.ChromeVox) != 'undefined')" +
                    "      && (cvox.ChromeVox != null)" +
                    "      && (typeof(cvox.AndroidVox) != 'undefined')" +
                    "      && (cvox.AndroidVox != null)" +
                    "      && cvox.ChromeVox.isActive) {" +
                    "    return cvox.AndroidVox.performAction('%1s');" +
                    "  } else {" +
                    "    return false;" +
                    "  }" +
                    "})()";

    // JS code used to shut down an active AndroidVox instance.
    private static final String TOGGLE_CVOX_TEMPLATE =
            "javascript:(function() {" +
                    "  if ((typeof(cvox) != 'undefined')" +
                    "      && (cvox != null)" +
                    "      && (typeof(cvox.ChromeVox) != 'undefined')" +
                    "      && (cvox.ChromeVox != null)" +
                    "      && (typeof(cvox.ChromeVox.host) != 'undefined')" +
                    "      && (cvox.ChromeVox.host != null)) {" +
                    "    cvox.ChromeVox.host.activateOrDeactivateChromeVox(%b);" +
                    "  }" +
                    "})();";

    /**
     * Creates an instance of the AccessibilityInjector based on
     * {@code webViewClassic}.
     *
     * @param webViewClassic The WebViewClassic that this AccessibilityInjector
     *            manages.
     */
    public AccessibilityInjector(WebViewClassic webViewClassic) {
        mWebViewClassic = webViewClassic;
        mWebView = webViewClassic.getWebView();
        mContext = webViewClassic.getContext();
        mAccessibilityManager = AccessibilityManager.getInstance(mContext);
    }

    /**
     * If JavaScript is enabled, pauses or resumes AndroidVox.
     *
     * @param enabled Whether feedback should be enabled.
     */
    public void toggleAccessibilityFeedback(boolean enabled) {
        if (!isAccessibilityEnabled() || !isJavaScriptEnabled()) {
            return;
        }

        toggleAndroidVox(enabled);

        if (!enabled && (mTextToSpeech != null)) {
            mTextToSpeech.stop();
        }
    }

    /**
     * Attempts to load scripting interfaces for accessibility.
     * <p>
     * This should only be called before a page loads.
     */
    public void addAccessibilityApisIfNecessary() {
        if (!isAccessibilityEnabled() || !isJavaScriptEnabled()) {
            return;
        }

        addTtsApis();
        addCallbackApis();
    }

    /**
     * Attempts to unload scripting interfaces for accessibility.
     * <p>
     * This should only be called before a page loads.
     */
    private void removeAccessibilityApisIfNecessary() {
        removeTtsApis();
        removeCallbackApis();
    }

    /**
     * Destroys this accessibility injector.
     */
    public void destroy() {
        if (mTextToSpeech != null) {
            mTextToSpeech.shutdown();
            mTextToSpeech = null;
        }

        if (mCallback != null) {
            mCallback = null;
        }
    }

    private void toggleAndroidVox(boolean state) {
        if (!mAccessibilityScriptInjected) {
            return;
        }

        final String code = String.format(TOGGLE_CVOX_TEMPLATE, state);
        mWebView.loadUrl(code);
    }

    /**
     * Initializes an {@link AccessibilityNodeInfo} with the actions and
     * movement granularity levels supported by this
     * {@link AccessibilityInjector}.
     * <p>
     * If an action identifier is added in this method, this
     * {@link AccessibilityInjector} should also return {@code true} from
     * {@link #supportsAccessibilityAction(int)}.
     * </p>
     *
     * @param info The info to initialize.
     * @see View#onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo)
     */
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        info.setMovementGranularities(AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_WORD
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_LINE
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PARAGRAPH
                | AccessibilityNodeInfo.MOVEMENT_GRANULARITY_PAGE);
        info.addAction(AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY);
        info.addAction(AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
        info.addAction(AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT);
        info.addAction(AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT);
        info.addAction(AccessibilityNodeInfo.ACTION_CLICK);
        info.setClickable(true);
    }

    /**
     * Returns {@code true} if this {@link AccessibilityInjector} should handle
     * the specified action.
     *
     * @param action An accessibility action identifier.
     * @return {@code true} if this {@link AccessibilityInjector} should handle
     *         the specified action.
     */
    public boolean supportsAccessibilityAction(int action) {
        switch (action) {
            case AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY:
            case AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY:
            case AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT:
            case AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT:
            case AccessibilityNodeInfo.ACTION_CLICK:
                return true;
            default:
                return false;
        }
    }

    /**
     * Performs the specified accessibility action.
     *
     * @param action The identifier of the action to perform.
     * @param arguments The action arguments, or {@code null} if no arguments.
     * @return {@code true} if the action was successful.
     * @see View#performAccessibilityAction(int, Bundle)
     */
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (!isAccessibilityEnabled()) {
            mAccessibilityScriptInjected = false;
            toggleFallbackAccessibilityInjector(false);
            return false;
        }

        if (mAccessibilityScriptInjected) {
            return sendActionToAndroidVox(action, arguments);
        }

        if (mAccessibilityInjectorFallback != null) {
            return mAccessibilityInjectorFallback.performAccessibilityAction(action, arguments);
        }

        return false;
    }

    /**
     * Attempts to handle key events when accessibility is turned on.
     *
     * @param event The key event to handle.
     * @return {@code true} if the event was handled.
     */
    public boolean handleKeyEventIfNecessary(KeyEvent event) {
        if (!isAccessibilityEnabled()) {
            mAccessibilityScriptInjected = false;
            toggleFallbackAccessibilityInjector(false);
            return false;
        }

        if (mAccessibilityScriptInjected) {
            // if an accessibility script is injected we delegate to it the key
            // handling. this script is a screen reader which is a fully fledged
            // solution for blind users to navigate in and interact with web
            // pages.
            if (event.getAction() == KeyEvent.ACTION_UP) {
                mWebViewClassic.sendBatchableInputMessage(EventHub.KEY_UP, 0, 0, event);
            } else if (event.getAction() == KeyEvent.ACTION_DOWN) {
                mWebViewClassic.sendBatchableInputMessage(EventHub.KEY_DOWN, 0, 0, event);
            } else {
                return false;
            }

            return true;
        }

        if (mAccessibilityInjectorFallback != null) {
            // if an accessibility injector is present (no JavaScript enabled or
            // the site opts out injecting our JavaScript screen reader) we let
            // it decide whether to act on and consume the event.
            return mAccessibilityInjectorFallback.onKeyEvent(event);
        }

        return false;
    }

    /**
     * Attempts to handle selection change events when accessibility is using a
     * non-JavaScript method.
     *
     * @param selectionString The selection string.
     */
    public void handleSelectionChangedIfNecessary(String selectionString) {
        if (mAccessibilityInjectorFallback != null) {
            mAccessibilityInjectorFallback.onSelectionStringChange(selectionString);
        }
    }

    /**
     * Prepares for injecting accessibility scripts into a new page.
     *
     * @param url The URL that will be loaded.
     */
    public void onPageStarted(String url) {
        mAccessibilityScriptInjected = false;
        if (DEBUG) {
            Log.w(TAG, "[" + mWebView.hashCode() + "] Started loading new page");
        }
        addAccessibilityApisIfNecessary();
    }

    /**
     * Attempts to inject the accessibility script using a {@code <script>} tag.
     * <p>
     * This should be called after a page has finished loading.
     * </p>
     *
     * @param url The URL that just finished loading.
     */
    public void onPageFinished(String url) {
        if (!isAccessibilityEnabled()) {
            toggleFallbackAccessibilityInjector(false);
            return;
        }

        toggleFallbackAccessibilityInjector(true);

        if (shouldInjectJavaScript(url)) {
            // If we're supposed to use the JS screen reader, request a
            // callback to confirm that CallbackHandler is working.
            if (DEBUG) {
                Log.d(TAG, "[" + mWebView.hashCode() + "] Request callback ");
            }

            mCallback.requestCallback(mWebView, mInjectScriptRunnable);
        }
    }

    /**
     * Runnable used to inject the JavaScript-based screen reader if the
     * {@link CallbackHandler} API was successfully exposed to JavaScript.
     */
    private Runnable mInjectScriptRunnable = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) {
                Log.d(TAG, "[" + mWebView.hashCode() + "] Received callback");
            }

            injectJavaScript();
        }
    };

    /**
     * Called by {@link #mInjectScriptRunnable} to inject the JavaScript-based
     * screen reader after confirming that the {@link CallbackHandler} API is
     * functional.
     */
    private void injectJavaScript() {
        toggleFallbackAccessibilityInjector(false);

        if (!mAccessibilityScriptInjected) {
            mAccessibilityScriptInjected = true;
            final String injectionUrl = getScreenReaderInjectionUrl();
            mWebView.loadUrl(injectionUrl);
            if (DEBUG) {
                Log.d(TAG, "[" + mWebView.hashCode() + "] Loading screen reader into WebView");
            }
        } else {
            if (DEBUG) {
                Log.w(TAG, "[" + mWebView.hashCode() + "] Attempted to inject screen reader twice");
            }
        }
    }

    /**
     * Adjusts the accessibility injection state to reflect changes in the
     * JavaScript enabled state.
     *
     * @param enabled Whether JavaScript is enabled.
     */
    public void updateJavaScriptEnabled(boolean enabled) {
        if (enabled) {
            addAccessibilityApisIfNecessary();
        } else {
            removeAccessibilityApisIfNecessary();
        }

        // We have to reload the page after adding or removing APIs.
        mWebView.reload();
    }

    /**
     * Toggles the non-JavaScript method for handling accessibility.
     *
     * @param enabled {@code true} to enable the non-JavaScript method, or
     *            {@code false} to disable it.
     */
    private void toggleFallbackAccessibilityInjector(boolean enabled) {
        if (enabled && (mAccessibilityInjectorFallback == null)) {
            mAccessibilityInjectorFallback = new AccessibilityInjectorFallback(mWebViewClassic);
        } else {
            mAccessibilityInjectorFallback = null;
        }
    }

    /**
     * Determines whether it's okay to inject JavaScript into a given URL.
     *
     * @param url The URL to check.
     * @return {@code true} if JavaScript should be injected, {@code false} if a
     *         non-JavaScript method should be used.
     */
    private boolean shouldInjectJavaScript(String url) {
        // Respect the WebView's JavaScript setting.
        if (!isJavaScriptEnabled()) {
            return false;
        }

        // Allow the page to opt out of Accessibility script injection.
        if (getAxsUrlParameterValue(url) == ACCESSIBILITY_SCRIPT_INJECTION_OPTED_OUT) {
            return false;
        }

        // The user must explicitly enable Accessibility script injection.
        if (!isScriptInjectionEnabled()) {
            return false;
        }

        return true;
    }

    /**
     * @return {@code true} if the user has explicitly enabled Accessibility
     *         script injection.
     */
    private boolean isScriptInjectionEnabled() {
        final int injectionSetting = Settings.Secure.getInt(
                mContext.getContentResolver(), Settings.Secure.ACCESSIBILITY_SCRIPT_INJECTION, 0);
        return (injectionSetting == 1);
    }

    /**
     * Attempts to initialize and add interfaces for TTS, if that hasn't already
     * been done.
     */
    private void addTtsApis() {
        if (mTextToSpeech == null) {
            mTextToSpeech = new TextToSpeechWrapper(mContext);
        }

        mWebView.addJavascriptInterface(mTextToSpeech, ALIAS_TTS_JS_INTERFACE);
    }

    /**
     * Attempts to shutdown and remove interfaces for TTS, if that hasn't
     * already been done.
     */
    private void removeTtsApis() {
        if (mTextToSpeech != null) {
            mTextToSpeech.stop();
            mTextToSpeech.shutdown();
            mTextToSpeech = null;
        }

        mWebView.removeJavascriptInterface(ALIAS_TTS_JS_INTERFACE);
    }

    private void addCallbackApis() {
        if (mCallback == null) {
            mCallback = new CallbackHandler(ALIAS_TRAVERSAL_JS_INTERFACE);
        }

        mWebView.addJavascriptInterface(mCallback, ALIAS_TRAVERSAL_JS_INTERFACE);
    }

    private void removeCallbackApis() {
        if (mCallback != null) {
            mCallback = null;
        }

        mWebView.removeJavascriptInterface(ALIAS_TRAVERSAL_JS_INTERFACE);
    }

    /**
     * Returns the script injection preference requested by the URL, or
     * {@link #ACCESSIBILITY_SCRIPT_INJECTION_UNDEFINED} if the page has no
     * preference.
     *
     * @param url The URL to check.
     * @return A script injection preference.
     */
    private int getAxsUrlParameterValue(String url) {
        if (url == null) {
            return ACCESSIBILITY_SCRIPT_INJECTION_UNDEFINED;
        }

        try {
            final List<NameValuePair> params = URLEncodedUtils.parse(new URI(url), null);

            for (NameValuePair param : params) {
                if ("axs".equals(param.getName())) {
                    return verifyInjectionValue(param.getValue());
                }
            }
        } catch (URISyntaxException e) {
            // Do nothing.
        } catch (IllegalArgumentException e) {
            // Catch badly-formed URLs.
        }

        return ACCESSIBILITY_SCRIPT_INJECTION_UNDEFINED;
    }

    private int verifyInjectionValue(String value) {
        try {
            final int parsed = Integer.parseInt(value);

            switch (parsed) {
                case ACCESSIBILITY_SCRIPT_INJECTION_OPTED_OUT:
                    return ACCESSIBILITY_SCRIPT_INJECTION_OPTED_OUT;
                case ACCESSIBILITY_SCRIPT_INJECTION_PROVIDED:
                    return ACCESSIBILITY_SCRIPT_INJECTION_PROVIDED;
            }
        } catch (NumberFormatException e) {
            // Do nothing.
        }

        return ACCESSIBILITY_SCRIPT_INJECTION_UNDEFINED;
    }

    /**
     * @return The URL for injecting the screen reader.
     */
    private String getScreenReaderInjectionUrl() {
        final String screenReaderUrl = Settings.Secure.getString(
                mContext.getContentResolver(), Settings.Secure.ACCESSIBILITY_SCREEN_READER_URL);
        return String.format(ACCESSIBILITY_SCREEN_READER_JAVASCRIPT_TEMPLATE, screenReaderUrl);
    }

    /**
     * @return {@code true} if JavaScript is enabled in the {@link WebView}
     *         settings.
     */
    private boolean isJavaScriptEnabled() {
        final WebSettings settings = mWebView.getSettings();
        if (settings == null) {
            return false;
        }

        return settings.getJavaScriptEnabled();
    }

    /**
     * @return {@code true} if accessibility is enabled.
     */
    private boolean isAccessibilityEnabled() {
        return mAccessibilityManager.isEnabled();
    }

    /**
     * Packs an accessibility action into a JSON object and sends it to AndroidVox.
     *
     * @param action The action identifier.
     * @param arguments The action arguments, if applicable.
     * @return The result of the action.
     */
    private boolean sendActionToAndroidVox(int action, Bundle arguments) {
        if (mAccessibilityJSONObject == null) {
            mAccessibilityJSONObject = new JSONObject();
        } else {
            // Remove all keys from the object.
            final Iterator<?> keys = mAccessibilityJSONObject.keys();
            while (keys.hasNext()) {
                keys.next();
                keys.remove();
            }
        }

        try {
            mAccessibilityJSONObject.accumulate("action", action);

            switch (action) {
                case AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY:
                case AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY:
                    if (arguments != null) {
                        final int granularity = arguments.getInt(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT);
                        mAccessibilityJSONObject.accumulate("granularity", granularity);
                    }
                    break;
                case AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT:
                case AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT:
                    if (arguments != null) {
                        final String element = arguments.getString(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_HTML_ELEMENT_STRING);
                        mAccessibilityJSONObject.accumulate("element", element);
                    }
                    break;
            }
        } catch (JSONException e) {
            return false;
        }

        final String jsonString = mAccessibilityJSONObject.toString();
        final String jsCode = String.format(ACCESSIBILITY_ANDROIDVOX_TEMPLATE, jsonString);
        return mCallback.performAction(mWebView, jsCode);
    }

    /**
     * Used to protect the TextToSpeech class, only exposing the methods we want to expose.
     */
    private static class TextToSpeechWrapper {
        private static final String WRAP_TAG = TextToSpeechWrapper.class.getSimpleName();

        private final HashMap<String, String> mTtsParams;
        private final TextToSpeech mTextToSpeech;

        /**
         * Whether this wrapper is ready to speak. If this is {@code true} then
         * {@link #mShutdown} is guaranteed to be {@code false}.
         */
        private volatile boolean mReady;

        /**
         * Whether this wrapper was shut down. If this is {@code true} then
         * {@link #mReady} is guaranteed to be {@code false}.
         */
        private volatile boolean mShutdown;

        public TextToSpeechWrapper(Context context) {
            if (DEBUG) {
                Log.d(WRAP_TAG, "[" + hashCode() + "] Initializing text-to-speech on thread "
                        + Thread.currentThread().getId() + "...");
            }

            final String pkgName = context.getPackageName();

            mReady = false;
            mShutdown = false;

            mTtsParams = new HashMap<String, String>();
            mTtsParams.put(Engine.KEY_PARAM_UTTERANCE_ID, WRAP_TAG);

            mTextToSpeech = new TextToSpeech(
                    context, mInitListener, null, pkgName + ".**webview**", true);
            mTextToSpeech.setOnUtteranceProgressListener(mErrorListener);
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public boolean isSpeaking() {
            synchronized (mTextToSpeech) {
                if (!mReady) {
                    return false;
                }

                return mTextToSpeech.isSpeaking();
            }
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public int speak(String text, int queueMode, HashMap<String, String> params) {
            synchronized (mTextToSpeech) {
                if (!mReady) {
                    if (DEBUG) {
                        Log.w(WRAP_TAG, "[" + hashCode() + "] Attempted to speak before TTS init");
                    }
                    return TextToSpeech.ERROR;
                } else {
                    if (DEBUG) {
                        Log.i(WRAP_TAG, "[" + hashCode() + "] Speak called from JS binder");
                    }
                }

                return mTextToSpeech.speak(text, queueMode, params);
            }
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public int stop() {
            synchronized (mTextToSpeech) {
                if (!mReady) {
                    if (DEBUG) {
                        Log.w(WRAP_TAG, "[" + hashCode() + "] Attempted to stop before initialize");
                    }
                    return TextToSpeech.ERROR;
                } else {
                    if (DEBUG) {
                        Log.i(WRAP_TAG, "[" + hashCode() + "] Stop called from JS binder");
                    }
                }

                return mTextToSpeech.stop();
            }
        }

        @SuppressWarnings("unused")
        protected void shutdown() {
            synchronized (mTextToSpeech) {
                if (!mReady) {
                    if (DEBUG) {
                        Log.w(WRAP_TAG, "[" + hashCode() + "] Called shutdown before initialize");
                    }
                } else {
                    if (DEBUG) {
                        Log.i(WRAP_TAG, "[" + hashCode() + "] Shutting down text-to-speech from "
                                + "thread " + Thread.currentThread().getId() + "...");
                    }
                }
                mShutdown = true;
                mReady = false;
                mTextToSpeech.shutdown();
            }
        }

        private final OnInitListener mInitListener = new OnInitListener() {
            @Override
            public void onInit(int status) {
                synchronized (mTextToSpeech) {
                    if (!mShutdown && (status == TextToSpeech.SUCCESS)) {
                        if (DEBUG) {
                            Log.d(WRAP_TAG, "[" + TextToSpeechWrapper.this.hashCode()
                                    + "] Initialized successfully");
                        }
                        mReady = true;
                    } else {
                        if (DEBUG) {
                            Log.w(WRAP_TAG, "[" + TextToSpeechWrapper.this.hashCode()
                                    + "] Failed to initialize");
                        }
                        mReady = false;
                    }
                }
            }
        };

        private final UtteranceProgressListener mErrorListener = new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // Do nothing.
            }

            @Override
            public void onError(String utteranceId) {
                if (DEBUG) {
                    Log.w(WRAP_TAG, "[" + TextToSpeechWrapper.this.hashCode()
                            + "] Failed to speak utterance");
                }
            }

            @Override
            public void onDone(String utteranceId) {
                // Do nothing.
            }
        };
    }

    /**
     * Exposes result interface to JavaScript.
     */
    private static class CallbackHandler {
        private static final String JAVASCRIPT_ACTION_TEMPLATE =
                "javascript:(function() { %s.onResult(%d, %s); })();";

        // Time in milliseconds to wait for a result before failing.
        private static final long RESULT_TIMEOUT = 5000;

        private final AtomicInteger mResultIdCounter = new AtomicInteger();
        private final Object mResultLock = new Object();
        private final String mInterfaceName;
        private final Handler mMainHandler;

        private Runnable mCallbackRunnable;

        private boolean mResult = false;
        private int mResultId = -1;

        private CallbackHandler(String interfaceName) {
            mInterfaceName = interfaceName;
            mMainHandler = new Handler();
        }

        /**
         * Performs an action and attempts to wait for a result.
         *
         * @param webView The WebView to perform the action on.
         * @param code JavaScript code that evaluates to a result.
         * @return The result of the action, or false if it timed out.
         */
        private boolean performAction(WebView webView, String code) {
            final int resultId = mResultIdCounter.getAndIncrement();
            final String url = String.format(
                    JAVASCRIPT_ACTION_TEMPLATE, mInterfaceName, resultId, code);
            webView.loadUrl(url);

            return getResultAndClear(resultId);
        }

        /**
         * Gets the result of a request to perform an accessibility action.
         *
         * @param resultId The result id to match the result with the request.
         * @return The result of the request.
         */
        private boolean getResultAndClear(int resultId) {
            synchronized (mResultLock) {
                final boolean success = waitForResultTimedLocked(resultId);
                final boolean result = success ? mResult : false;
                clearResultLocked();
                return result;
            }
        }

        /**
         * Clears the result state.
         */
        private void clearResultLocked() {
            mResultId = -1;
            mResult = false;
        }

        /**
         * Waits up to a given bound for a result of a request and returns it.
         *
         * @param resultId The result id to match the result with the request.
         * @return Whether the result was received.
         */
        private boolean waitForResultTimedLocked(int resultId) {
            final long startTimeMillis = SystemClock.uptimeMillis();

            if (DEBUG) {
                Log.d(TAG, "Waiting for CVOX result with ID " + resultId + "...");
            }

            while (true) {
                // Fail if we received a callback from the future.
                if (mResultId > resultId) {
                    if (DEBUG) {
                        Log.w(TAG, "Aborted CVOX result");
                    }
                    return false;
                }

                final long elapsedTimeMillis = (SystemClock.uptimeMillis() - startTimeMillis);

                // Succeed if we received the callback we were expecting.
                if (DEBUG) {
                    Log.w(TAG, "Check " + mResultId + " versus expected " + resultId);
                }
                if (mResultId == resultId) {
                    if (DEBUG) {
                        Log.w(TAG, "Received CVOX result after " + elapsedTimeMillis + " ms");
                    }
                    return true;
                }

                final long waitTimeMillis = (RESULT_TIMEOUT - elapsedTimeMillis);

                // Fail if we've already exceeded the timeout.
                if (waitTimeMillis <= 0) {
                    if (DEBUG) {
                        Log.w(TAG, "Timed out while waiting for CVOX result");
                    }
                    return false;
                }

                try {
                    if (DEBUG) {
                        Log.w(TAG, "Start waiting...");
                    }
                    mResultLock.wait(waitTimeMillis);
                } catch (InterruptedException ie) {
                    if (DEBUG) {
                        Log.w(TAG, "Interrupted while waiting for CVOX result");
                    }
                }
            }
        }

        /**
         * Callback exposed to JavaScript. Handles returning the result of a
         * request to a waiting (or potentially timed out) thread.
         *
         * @param id The result id of the request as a {@link String}.
         * @param result The result of the request as a {@link String}.
         */
        @JavascriptInterface
        @SuppressWarnings("unused")
        public void onResult(String id, String result) {
            if (DEBUG) {
                Log.w(TAG, "Saw CVOX result of '" + result + "' for ID " + id);
            }
            final int resultId;

            try {
                resultId = Integer.parseInt(id);
            } catch (NumberFormatException e) {
                return;
            }

            synchronized (mResultLock) {
                if (resultId > mResultId) {
                    mResult = Boolean.parseBoolean(result);
                    mResultId = resultId;
                } else {
                    if (DEBUG) {
                        Log.w(TAG, "Result with ID " + resultId + " was stale vesus " + mResultId);
                    }
                }
                mResultLock.notifyAll();
            }
        }

        /**
         * Requests a callback to ensure that the JavaScript interface for this
         * object has been added successfully.
         *
         * @param webView The web view to request a callback from.
         * @param callbackRunnable Runnable to execute if a callback is received.
         */
        public void requestCallback(WebView webView, Runnable callbackRunnable) {
            mCallbackRunnable = callbackRunnable;

            webView.loadUrl("javascript:(function() { " + mInterfaceName + ".callback(); })();");
        }

        @JavascriptInterface
        @SuppressWarnings("unused")
        public void callback() {
            if (mCallbackRunnable != null) {
                mMainHandler.post(mCallbackRunnable);
                mCallbackRunnable = null;
            }
        }
    }
}
