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

package android.webkit;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.net.http.SslError;
import android.os.Message;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.ViewRootImpl;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class WebViewClient {

    /**
     * Give the host application a chance to take control when a URL is about to be loaded in the
     * current WebView. If a WebViewClient is not provided, by default WebView will ask Activity
     * Manager to choose the proper handler for the URL. If a WebViewClient is provided, returning
     * {@code true} causes the current WebView to abort loading the URL, while returning
     * {@code false} causes the WebView to continue loading the URL as usual.
     *
     * <p class="note"><b>Note:</b> Do not call {@link WebView#loadUrl(String)} with the same
     * URL and then return {@code true}. This unnecessarily cancels the current load and starts a
     * new load with the same URL. The correct way to continue loading a given URL is to simply
     * return {@code false}, without calling {@link WebView#loadUrl(String)}.
     *
     * <p class="note"><b>Note:</b> This method is not called for POST requests.
     *
     * <p class="note"><b>Note:</b> This method may be called for subframes and with non-HTTP(S)
     * schemes; calling {@link WebView#loadUrl(String)} with such a URL will fail.
     *
     * @param view The WebView that is initiating the callback.
     * @param url The URL to be loaded.
     * @return {@code true} to cancel the current load, otherwise return {@code false}.
     * @deprecated Use {@link #shouldOverrideUrlLoading(WebView, WebResourceRequest)
     *             shouldOverrideUrlLoading(WebView, WebResourceRequest)} instead.
     */
    @Deprecated
    public boolean shouldOverrideUrlLoading(WebView view, String url) {
        return false;
    }

    /**
     * Give the host application a chance to take control when a URL is about to be loaded in the
     * current WebView. If a WebViewClient is not provided, by default WebView will ask Activity
     * Manager to choose the proper handler for the URL. If a WebViewClient is provided, returning
     * {@code true} causes the current WebView to abort loading the URL, while returning
     * {@code false} causes the WebView to continue loading the URL as usual.
     *
     * <p class="note"><b>Note:</b> Do not call {@link WebView#loadUrl(String)} with the request's
     * URL and then return {@code true}. This unnecessarily cancels the current load and starts a
     * new load with the same URL. The correct way to continue loading a given URL is to simply
     * return {@code false}, without calling {@link WebView#loadUrl(String)}.
     *
     * <p class="note"><b>Note:</b> This method is not called for POST requests.
     *
     * <p class="note"><b>Note:</b> This method may be called for subframes and with non-HTTP(S)
     * schemes; calling {@link WebView#loadUrl(String)} with such a URL will fail.
     *
     * @param view The WebView that is initiating the callback.
     * @param request Object containing the details of the request.
     * @return {@code true} to cancel the current load, otherwise return {@code false}.
     */
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return shouldOverrideUrlLoading(view, request.getUrl().toString());
    }

    /**
     * Notify the host application that a page has started loading. This method
     * is called once for each main frame load so a page with iframes or
     * framesets will call onPageStarted one time for the main frame. This also
     * means that onPageStarted will not be called when the contents of an
     * embedded frame changes, i.e. clicking a link whose target is an iframe,
     * it will also not be called for fragment navigations (navigations to
     * #fragment_id).
     *
     * @param view The WebView that is initiating the callback.
     * @param url The url to be loaded.
     * @param favicon The favicon for this page if it already exists in the
     *            database.
     */
    public void onPageStarted(WebView view, String url, Bitmap favicon) {
    }

    /**
     * Notify the host application that a page has finished loading. This method
     * is called only for main frame. Receiving an {@code onPageFinished()} callback does not
     * guarantee that the next frame drawn by WebView will reflect the state of the DOM at this
     * point. In order to be notified that the current DOM state is ready to be rendered, request a
     * visual state callback with {@link WebView#postVisualStateCallback} and wait for the supplied
     * callback to be triggered.
     *
     * @param view The WebView that is initiating the callback.
     * @param url The url of the page.
     */
    public void onPageFinished(WebView view, String url) {
    }

    /**
     * Notify the host application that the WebView will load the resource
     * specified by the given url.
     *
     * @param view The WebView that is initiating the callback.
     * @param url The url of the resource the WebView will load.
     */
    public void onLoadResource(WebView view, String url) {
    }

    /**
     * Notify the host application that {@link android.webkit.WebView} content left over from
     * previous page navigations will no longer be drawn.
     *
     * <p>This callback can be used to determine the point at which it is safe to make a recycled
     * {@link android.webkit.WebView} visible, ensuring that no stale content is shown. It is called
     * at the earliest point at which it can be guaranteed that {@link WebView#onDraw} will no
     * longer draw any content from previous navigations. The next draw will display either the
     * {@link WebView#setBackgroundColor background color} of the {@link WebView}, or some of the
     * contents of the newly loaded page.
     *
     * <p>This method is called when the body of the HTTP response has started loading, is reflected
     * in the DOM, and will be visible in subsequent draws. This callback occurs early in the
     * document loading process, and as such you should expect that linked resources (for example,
     * CSS and images) may not be available.
     *
     * <p>For more fine-grained notification of visual state updates, see {@link
     * WebView#postVisualStateCallback}.
     *
     * <p>Please note that all the conditions and recommendations applicable to
     * {@link WebView#postVisualStateCallback} also apply to this API.
     *
     * <p>This callback is only called for main frame navigations.
     *
     * @param view The {@link android.webkit.WebView} for which the navigation occurred.
     * @param url  The URL corresponding to the page navigation that triggered this callback.
     */
    public void onPageCommitVisible(WebView view, String url) {
    }

    /**
     * Notify the host application of a resource request and allow the
     * application to return the data.  If the return value is {@code null}, the WebView
     * will continue to load the resource as usual.  Otherwise, the return
     * response and data will be used.
     *
     * <p>This callback is invoked for a variety of URL schemes (e.g., {@code http(s):}, {@code
     * data:}, {@code file:}, etc.), not only those schemes which send requests over the network.
     * This is not called for {@code javascript:} URLs, {@code blob:} URLs, or for assets accessed
     * via {@code file:///android_asset/} or {@code file:///android_res/} URLs.
     *
     * <p>In the case of redirects, this is only called for the initial resource URL, not any
     * subsequent redirect URLs.
     *
     * <p class="note"><b>Note:</b> This method is called on a thread
     * other than the UI thread so clients should exercise caution
     * when accessing private data or the view system.
     *
     * <p class="note"><b>Note:</b> When Safe Browsing is enabled, these URLs still undergo Safe
     * Browsing checks. If this is undesired, whitelist the URL with {@link
     * WebView#setSafeBrowsingWhitelist} or ignore the warning with {@link #onSafeBrowsingHit}.
     *
     * @param view The {@link android.webkit.WebView} that is requesting the
     *             resource.
     * @param url The raw url of the resource.
     * @return A {@link android.webkit.WebResourceResponse} containing the
     *         response information or {@code null} if the WebView should load the
     *         resource itself.
     * @deprecated Use {@link #shouldInterceptRequest(WebView, WebResourceRequest)
     *             shouldInterceptRequest(WebView, WebResourceRequest)} instead.
     */
    @Deprecated
    @Nullable
    public WebResourceResponse shouldInterceptRequest(WebView view,
            String url) {
        return null;
    }

    /**
     * Notify the host application of a resource request and allow the
     * application to return the data.  If the return value is {@code null}, the WebView
     * will continue to load the resource as usual.  Otherwise, the return
     * response and data will be used.
     *
     * <p>This callback is invoked for a variety of URL schemes (e.g., {@code http(s):}, {@code
     * data:}, {@code file:}, etc.), not only those schemes which send requests over the network.
     * This is not called for {@code javascript:} URLs, {@code blob:} URLs, or for assets accessed
     * via {@code file:///android_asset/} or {@code file:///android_res/} URLs.
     *
     * <p>In the case of redirects, this is only called for the initial resource URL, not any
     * subsequent redirect URLs.
     *
     * <p class="note"><b>Note:</b> This method is called on a thread
     * other than the UI thread so clients should exercise caution
     * when accessing private data or the view system.
     *
     * <p class="note"><b>Note:</b> When Safe Browsing is enabled, these URLs still undergo Safe
     * Browsing checks. If this is undesired, whitelist the URL with {@link
     * WebView#setSafeBrowsingWhitelist} or ignore the warning with {@link #onSafeBrowsingHit}.
     *
     * @param view The {@link android.webkit.WebView} that is requesting the
     *             resource.
     * @param request Object containing the details of the request.
     * @return A {@link android.webkit.WebResourceResponse} containing the
     *         response information or {@code null} if the WebView should load the
     *         resource itself.
     */
    @Nullable
    public WebResourceResponse shouldInterceptRequest(WebView view,
            WebResourceRequest request) {
        return shouldInterceptRequest(view, request.getUrl().toString());
    }

    /**
     * Notify the host application that there have been an excessive number of
     * HTTP redirects. As the host application if it would like to continue
     * trying to load the resource. The default behavior is to send the cancel
     * message.
     *
     * @param view The WebView that is initiating the callback.
     * @param cancelMsg The message to send if the host wants to cancel
     * @param continueMsg The message to send if the host wants to continue
     * @deprecated This method is no longer called. When the WebView encounters
     *             a redirect loop, it will cancel the load.
     */
    @Deprecated
    public void onTooManyRedirects(WebView view, Message cancelMsg,
            Message continueMsg) {
        cancelMsg.sendToTarget();
    }

    // These ints must match up to the hidden values in EventHandler.
    /** Generic error */
    public static final int ERROR_UNKNOWN = -1;
    /** Server or proxy hostname lookup failed */
    public static final int ERROR_HOST_LOOKUP = -2;
    /** Unsupported authentication scheme (not basic or digest) */
    public static final int ERROR_UNSUPPORTED_AUTH_SCHEME = -3;
    /** User authentication failed on server */
    public static final int ERROR_AUTHENTICATION = -4;
    /** User authentication failed on proxy */
    public static final int ERROR_PROXY_AUTHENTICATION = -5;
    /** Failed to connect to the server */
    public static final int ERROR_CONNECT = -6;
    /** Failed to read or write to the server */
    public static final int ERROR_IO = -7;
    /** Connection timed out */
    public static final int ERROR_TIMEOUT = -8;
    /** Too many redirects */
    public static final int ERROR_REDIRECT_LOOP = -9;
    /** Unsupported URI scheme */
    public static final int ERROR_UNSUPPORTED_SCHEME = -10;
    /** Failed to perform SSL handshake */
    public static final int ERROR_FAILED_SSL_HANDSHAKE = -11;
    /** Malformed URL */
    public static final int ERROR_BAD_URL = -12;
    /** Generic file error */
    public static final int ERROR_FILE = -13;
    /** File not found */
    public static final int ERROR_FILE_NOT_FOUND = -14;
    /** Too many requests during this load */
    public static final int ERROR_TOO_MANY_REQUESTS = -15;
    /** Resource load was canceled by Safe Browsing */
    public static final int ERROR_UNSAFE_RESOURCE = -16;

    /** @hide */
    @IntDef(prefix = { "SAFE_BROWSING_THREAT_" }, value = {
            SAFE_BROWSING_THREAT_UNKNOWN,
            SAFE_BROWSING_THREAT_MALWARE,
            SAFE_BROWSING_THREAT_PHISHING,
            SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE,
            SAFE_BROWSING_THREAT_BILLING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SafeBrowsingThreat {}

    /** The resource was blocked for an unknown reason. */
    public static final int SAFE_BROWSING_THREAT_UNKNOWN = 0;
    /** The resource was blocked because it contains malware. */
    public static final int SAFE_BROWSING_THREAT_MALWARE = 1;
    /** The resource was blocked because it contains deceptive content. */
    public static final int SAFE_BROWSING_THREAT_PHISHING = 2;
    /** The resource was blocked because it contains unwanted software. */
    public static final int SAFE_BROWSING_THREAT_UNWANTED_SOFTWARE = 3;
    /**
     * The resource was blocked because it may trick the user into a billing agreement.
     *
     * <p>This constant is only used when targetSdkVersion is at least {@link
     * android.os.Build.VERSION_CODES#Q}. Otherwise, {@link #SAFE_BROWSING_THREAT_UNKNOWN} is used
     * instead.
     */
    public static final int SAFE_BROWSING_THREAT_BILLING = 4;

    /**
     * Report an error to the host application. These errors are unrecoverable
     * (i.e. the main resource is unavailable). The {@code errorCode} parameter
     * corresponds to one of the {@code ERROR_*} constants.
     * @param view The WebView that is initiating the callback.
     * @param errorCode The error code corresponding to an ERROR_* value.
     * @param description A String describing the error.
     * @param failingUrl The url that failed to load.
     * @deprecated Use {@link #onReceivedError(WebView, WebResourceRequest, WebResourceError)
     *             onReceivedError(WebView, WebResourceRequest, WebResourceError)} instead.
     */
    @Deprecated
    public void onReceivedError(WebView view, int errorCode,
            String description, String failingUrl) {
    }

    /**
     * Report web resource loading error to the host application. These errors usually indicate
     * inability to connect to the server. Note that unlike the deprecated version of the callback,
     * the new version will be called for any resource (iframe, image, etc.), not just for the main
     * page. Thus, it is recommended to perform minimum required work in this callback.
     * @param view The WebView that is initiating the callback.
     * @param request The originating request.
     * @param error Information about the error occurred.
     */
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request.isForMainFrame()) {
            onReceivedError(view,
                    error.getErrorCode(), error.getDescription().toString(),
                    request.getUrl().toString());
        }
    }

    /**
     * Notify the host application that an HTTP error has been received from the server while
     * loading a resource.  HTTP errors have status codes &gt;= 400.  This callback will be called
     * for any resource (iframe, image, etc.), not just for the main page. Thus, it is recommended
     * to perform minimum required work in this callback. Note that the content of the server
     * response may not be provided within the {@code errorResponse} parameter.
     * @param view The WebView that is initiating the callback.
     * @param request The originating request.
     * @param errorResponse Information about the error occurred.
     */
    public void onReceivedHttpError(
            WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
    }

    /**
     * As the host application if the browser should resend data as the
     * requested page was a result of a POST. The default is to not resend the
     * data.
     *
     * @param view The WebView that is initiating the callback.
     * @param dontResend The message to send if the browser should not resend
     * @param resend The message to send if the browser should resend data
     */
    public void onFormResubmission(WebView view, Message dontResend,
            Message resend) {
        dontResend.sendToTarget();
    }

    /**
     * Notify the host application to update its visited links database.
     *
     * @param view The WebView that is initiating the callback.
     * @param url The url being visited.
     * @param isReload {@code true} if this url is being reloaded.
     */
    public void doUpdateVisitedHistory(WebView view, String url,
            boolean isReload) {
    }

    /**
     * Notify the host application that an SSL error occurred while loading a
     * resource. The host application must call either {@link SslErrorHandler#cancel} or
     * {@link SslErrorHandler#proceed}. Note that the decision may be retained for use in
     * response to future SSL errors. The default behavior is to cancel the
     * load.
     * <p>
     * This API is only called for recoverable SSL certificate errors. In the case of
     * non-recoverable errors (such as when the server fails the client), WebView will call {@link
     * #onReceivedError(WebView, WebResourceRequest, WebResourceError)} with {@link
     * #ERROR_FAILED_SSL_HANDSHAKE}.
     * <p>
     * Applications are advised not to prompt the user about SSL errors, as
     * the user is unlikely to be able to make an informed security decision
     * and WebView does not provide any UI for showing the details of the
     * error in a meaningful way.
     * <p>
     * Application overrides of this method may display custom error pages or
     * silently log issues, but it is strongly recommended to always call
     * {@link SslErrorHandler#cancel} and never allow proceeding past errors.
     *
     * @param view The WebView that is initiating the callback.
     * @param handler An {@link SslErrorHandler} that will handle the user's
     *            response.
     * @param error The SSL error object.
     */
    public void onReceivedSslError(WebView view, SslErrorHandler handler,
            SslError error) {
        handler.cancel();
    }

    /**
     * Notify the host application to handle a SSL client certificate request. The host application
     * is responsible for showing the UI if desired and providing the keys. There are three ways to
     * respond: {@link ClientCertRequest#proceed}, {@link ClientCertRequest#cancel}, or {@link
     * ClientCertRequest#ignore}. Webview stores the response in memory (for the life of the
     * application) if {@link ClientCertRequest#proceed} or {@link ClientCertRequest#cancel} is
     * called and does not call {@code onReceivedClientCertRequest()} again for the same host and
     * port pair. Webview does not store the response if {@link ClientCertRequest#ignore}
     * is called. Note that, multiple layers in chromium network stack might be
     * caching the responses, so the behavior for ignore is only a best case
     * effort.
     *
     * This method is called on the UI thread. During the callback, the
     * connection is suspended.
     *
     * For most use cases, the application program should implement the
     * {@link android.security.KeyChainAliasCallback} interface and pass it to
     * {@link android.security.KeyChain#choosePrivateKeyAlias} to start an
     * activity for the user to choose the proper alias. The keychain activity will
     * provide the alias through the callback method in the implemented interface. Next
     * the application should create an async task to call
     * {@link android.security.KeyChain#getPrivateKey} to receive the key.
     *
     * An example implementation of client certificates can be seen at
     * <A href="https://android.googlesource.com/platform/packages/apps/Browser/+/android-5.1.1_r1/src/com/android/browser/Tab.java">
     * AOSP Browser</a>
     *
     * The default behavior is to cancel, returning no client certificate.
     *
     * @param view The WebView that is initiating the callback
     * @param request An instance of a {@link ClientCertRequest}
     *
     */
    public void onReceivedClientCertRequest(WebView view, ClientCertRequest request) {
        request.cancel();
    }

    /**
     * Notifies the host application that the WebView received an HTTP
     * authentication request. The host application can use the supplied
     * {@link HttpAuthHandler} to set the WebView's response to the request.
     * The default behavior is to cancel the request.
     *
     * @param view the WebView that is initiating the callback
     * @param handler the HttpAuthHandler used to set the WebView's response
     * @param host the host requiring authentication
     * @param realm the realm for which authentication is required
     * @see WebView#getHttpAuthUsernamePassword
     */
    public void onReceivedHttpAuthRequest(WebView view,
            HttpAuthHandler handler, String host, String realm) {
        handler.cancel();
    }

    /**
     * Give the host application a chance to handle the key event synchronously.
     * e.g. menu shortcut key events need to be filtered this way. If return
     * true, WebView will not handle the key event. If return {@code false}, WebView
     * will always handle the key event, so none of the super in the view chain
     * will see the key event. The default behavior returns {@code false}.
     *
     * @param view The WebView that is initiating the callback.
     * @param event The key event.
     * @return {@code true} if the host application wants to handle the key event
     *         itself, otherwise return {@code false}
     */
    public boolean shouldOverrideKeyEvent(WebView view, KeyEvent event) {
        return false;
    }

    /**
     * Notify the host application that a key was not handled by the WebView.
     * Except system keys, WebView always consumes the keys in the normal flow
     * or if {@link #shouldOverrideKeyEvent} returns {@code true}. This is called asynchronously
     * from where the key is dispatched. It gives the host application a chance
     * to handle the unhandled key events.
     *
     * @param view The WebView that is initiating the callback.
     * @param event The key event.
     */
    public void onUnhandledKeyEvent(WebView view, KeyEvent event) {
        onUnhandledInputEventInternal(view, event);
    }

    /**
     * Notify the host application that a input event was not handled by the WebView.
     * Except system keys, WebView always consumes input events in the normal flow
     * or if {@link #shouldOverrideKeyEvent} returns {@code true}. This is called asynchronously
     * from where the event is dispatched. It gives the host application a chance
     * to handle the unhandled input events.
     *
     * Note that if the event is a {@link android.view.MotionEvent}, then it's lifetime is only
     * that of the function call. If the WebViewClient wishes to use the event beyond that, then it
     * <i>must</i> create a copy of the event.
     *
     * It is the responsibility of overriders of this method to call
     * {@link #onUnhandledKeyEvent(WebView, KeyEvent)}
     * when appropriate if they wish to continue receiving events through it.
     *
     * @param view The WebView that is initiating the callback.
     * @param event The input event.
     * @removed
     */
    public void onUnhandledInputEvent(WebView view, InputEvent event) {
        if (event instanceof KeyEvent) {
            onUnhandledKeyEvent(view, (KeyEvent) event);
            return;
        }
        onUnhandledInputEventInternal(view, event);
    }

    private void onUnhandledInputEventInternal(WebView view, InputEvent event) {
        ViewRootImpl root = view.getViewRootImpl();
        if (root != null) {
            root.dispatchUnhandledInputEvent(event);
        }
    }

    /**
     * Notify the host application that the scale applied to the WebView has
     * changed.
     *
     * @param view The WebView that is initiating the callback.
     * @param oldScale The old scale factor
     * @param newScale The new scale factor
     */
    public void onScaleChanged(WebView view, float oldScale, float newScale) {
    }

    /**
     * Notify the host application that a request to automatically log in the
     * user has been processed.
     * @param view The WebView requesting the login.
     * @param realm The account realm used to look up accounts.
     * @param account An optional account. If not {@code null}, the account should be
     *                checked against accounts on the device. If it is a valid
     *                account, it should be used to log in the user.
     * @param args Authenticator specific arguments used to log in the user.
     */
    public void onReceivedLoginRequest(WebView view, String realm,
            @Nullable String account, String args) {
    }

    /**
     * Notify host application that the given WebView's render process has exited.
     *
     * Multiple WebView instances may be associated with a single render process;
     * onRenderProcessGone will be called for each WebView that was affected.
     * The application's implementation of this callback should only attempt to
     * clean up the specific WebView given as a parameter, and should not assume
     * that other WebView instances are affected.
     *
     * The given WebView can't be used, and should be removed from the view hierarchy,
     * all references to it should be cleaned up, e.g any references in the Activity
     * or other classes saved using {@link android.view.View#findViewById} and similar calls, etc.
     *
     * To cause an render process crash for test purpose, the application can
     * call {@code loadUrl("chrome://crash")} on the WebView. Note that multiple WebView
     * instances may be affected if they share a render process, not just the
     * specific WebView which loaded chrome://crash.
     *
     * @param view The WebView which needs to be cleaned up.
     * @param detail the reason why it exited.
     * @return {@code true} if the host application handled the situation that process has
     *         exited, otherwise, application will crash if render process crashed,
     *         or be killed if render process was killed by the system.
     */
    public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        return false;
    }

    /**
     * Notify the host application that a loading URL has been flagged by Safe Browsing.
     *
     * The application must invoke the callback to indicate the preferred response. The default
     * behavior is to show an interstitial to the user, with the reporting checkbox visible.
     *
     * If the application needs to show its own custom interstitial UI, the callback can be invoked
     * asynchronously with {@link SafeBrowsingResponse#backToSafety} or {@link
     * SafeBrowsingResponse#proceed}, depending on user response.
     *
     * @param view The WebView that hit the malicious resource.
     * @param request Object containing the details of the request.
     * @param threatType The reason the resource was caught by Safe Browsing, corresponding to a
     *                   {@code SAFE_BROWSING_THREAT_*} value.
     * @param callback Applications must invoke one of the callback methods.
     */
    public void onSafeBrowsingHit(WebView view, WebResourceRequest request,
            @SafeBrowsingThreat int threatType, SafeBrowsingResponse callback) {
        callback.showInterstitial(/* allowReporting */ true);
    }
}
