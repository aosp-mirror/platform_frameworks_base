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
import android.os.Vibrator;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityManager;
import android.webkit.WebViewCore.EventHub;

import org.apache.http.NameValuePair;
import org.apache.http.client.utils.URLEncodedUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

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

    // Lazily loaded helper objects.
    private AccessibilityManager mAccessibilityManager;
    private AccessibilityInjectorFallback mAccessibilityInjector;

    // Whether the accessibility script has been injected into the current page.
    private boolean mAccessibilityScriptInjected;

    // Constants for determining script injection strategy.
    private static final int ACCESSIBILITY_SCRIPT_INJECTION_UNDEFINED = -1;
    private static final int ACCESSIBILITY_SCRIPT_INJECTION_OPTED_OUT = 0;
    @SuppressWarnings("unused")
    private static final int ACCESSIBILITY_SCRIPT_INJECTION_PROVIDED = 1;

    // Aliases for Java objects exposed to JavaScript.
    private static final String ALIAS_ACCESSIBILITY_JS_INTERFACE = "accessibility";

    // Template for JavaScript that injects a screen-reader.
    private static final String ACCESSIBILITY_SCREEN_READER_JAVASCRIPT_TEMPLATE =
            "javascript:(function() {" +
                    "    var chooser = document.createElement('script');" +
                    "    chooser.type = 'text/javascript';" +
                    "    chooser.src = '%1s';" +
                    "    document.getElementsByTagName('head')[0].appendChild(chooser);" +
                    "  })();";

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
    }

    /**
     * Attempts to unload scripting interfaces for accessibility.
     * <p>
     * This should be called when the window is detached.
     * </p>
     */
    public void removeAccessibilityApisIfNecessary() {
        removeTtsApis();
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

        if (mAccessibilityInjector != null) {
            // if an accessibility injector is present (no JavaScript enabled or
            // the site opts out injecting our JavaScript screen reader) we let
            // it decide whether to act on and consume the event.
            return mAccessibilityInjector.onKeyEvent(event);
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
        if (mAccessibilityInjector != null) {
            mAccessibilityInjector.onSelectionStringChange(selectionString);
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
        if (enabled && (mAccessibilityInjector == null)) {
            mAccessibilityInjector = new AccessibilityInjectorFallback(mWebViewClassic);
        } else {
            mAccessibilityInjector = null;
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
        mWebView.addJavascriptInterface(mTextToSpeech, ALIAS_ACCESSIBILITY_JS_INTERFACE);
    }

    /**
     * Attempts to shutdown and remove interfaces for TTS, if that hasn't
     * already been done.
     */
    private void removeTtsApis() {
        if (mTextToSpeech == null) {
            return;
        }

        mWebView.removeJavascriptInterface(ALIAS_ACCESSIBILITY_JS_INTERFACE);
        mTextToSpeech.stop();
        mTextToSpeech.shutdown();
        mTextToSpeech = null;
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
}
