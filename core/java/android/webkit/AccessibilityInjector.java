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
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
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
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles injecting accessibility JavaScript and related JavaScript -> Java
 * APIs.
 */
class AccessibilityInjector {
    // The WebViewClassic this injector is responsible for managing.
    private final WebViewClassic mWebViewClassic;

    // Cached reference to mWebViewClassic.getContext(), for convenience.
    private final Context mContext;

    // Cached reference to mWebViewClassic.getWebView(), for convenience.
    private final WebView mWebView;

    // The Java objects that are exposed to JavaScript.
    private TextToSpeech mTextToSpeech;
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
            "cvox.AndroidVox.performAction('%1s')";

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
     * Attempts to load scripting interfaces for accessibility.
     * <p>
     * This should be called when the window is attached.
     * </p>
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
     * This should be called when the window is detached.
     * </p>
     */
    public void removeAccessibilityApisIfNecessary() {
        removeTtsApis();
        removeCallbackApis();
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
            mAccessibilityScriptInjected = false;
            toggleFallbackAccessibilityInjector(false);
            return;
        }

        if (!shouldInjectJavaScript(url)) {
            toggleFallbackAccessibilityInjector(true);
            return;
        }

        toggleFallbackAccessibilityInjector(false);

        final String injectionUrl = getScreenReaderInjectionUrl();
        mWebView.loadUrl(injectionUrl);

        mAccessibilityScriptInjected = true;
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
        if (mTextToSpeech != null) {
            return;
        }

        final String pkgName = mContext.getPackageName();

        mTextToSpeech = new TextToSpeech(mContext, null, null, pkgName + ".**webview**", true);
        mWebView.addJavascriptInterface(mTextToSpeech, ALIAS_TTS_JS_INTERFACE);
    }

    /**
     * Attempts to shutdown and remove interfaces for TTS, if that hasn't
     * already been done.
     */
    private void removeTtsApis() {
        if (mTextToSpeech == null) {
            return;
        }

        mWebView.removeJavascriptInterface(ALIAS_TTS_JS_INTERFACE);
        mTextToSpeech.stop();
        mTextToSpeech.shutdown();
        mTextToSpeech = null;
    }

    private void addCallbackApis() {
        if (mCallback != null) {
            return;
        }

        mCallback = new CallbackHandler(ALIAS_TRAVERSAL_JS_INTERFACE);
        mWebView.addJavascriptInterface(mCallback, ALIAS_TRAVERSAL_JS_INTERFACE);
    }

    private void removeCallbackApis() {
        if (mCallback == null) {
            return;
        }

        mWebView.removeJavascriptInterface(ALIAS_TRAVERSAL_JS_INTERFACE);
        mCallback = null;
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
        return mWebView.getSettings().getJavaScriptEnabled();
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

        private boolean mResult = false;
        private long mResultId = -1;

        private CallbackHandler(String interfaceName) {
            mInterfaceName = interfaceName;
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
            long waitTimeMillis = RESULT_TIMEOUT;
            final long startTimeMillis = SystemClock.uptimeMillis();
            while (true) {
                try {
                    if (mResultId == resultId) {
                        return true;
                    }
                    if (mResultId > resultId) {
                        return false;
                    }
                    final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                    waitTimeMillis = RESULT_TIMEOUT - elapsedTimeMillis;
                    if (waitTimeMillis <= 0) {
                        return false;
                    }
                    mResultLock.wait(waitTimeMillis);
                } catch (InterruptedException ie) {
                    /* ignore */
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
        @SuppressWarnings("unused")
        public void onResult(String id, String result) {
            final long resultId;

            try {
                resultId = Long.parseLong(id);
            } catch (NumberFormatException e) {
                return;
            }

            synchronized (mResultLock) {
                if (resultId > mResultId) {
                    mResult = Boolean.parseBoolean(result);
                    mResultId = resultId;
                }
                mResultLock.notifyAll();
            }
        }
    }
}
