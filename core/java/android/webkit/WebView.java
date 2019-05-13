/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.Widget;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.StrictMode;
import android.print.PrintDocumentAdapter;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewHierarchyEncoder;
import android.view.ViewStructure;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.autofill.AutofillValue;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.textclassifier.TextClassifier;
import android.widget.AbsoluteLayout;

import java.io.BufferedWriter;
import java.io.File;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.Map;

/**
 * A View that displays web pages.
 *
 * <h3>Basic usage</h3>
 *
 *
 * <p>In most cases, we recommend using a standard web browser, like Chrome, to deliver
 * content to the user. To learn more about web browsers, read the guide on
 * <a href="/guide/components/intents-common#Browser">
 * invoking a browser with an intent</a>.
 *
 * <p>WebView objects allow you to display web content as part of your activity layout, but
 * lack some of the features of fully-developed browsers. A WebView is useful when
 * you need increased control over the UI and advanced configuration options that will allow
 * you to embed web pages in a specially-designed environment for your app.
 *
 * <p>To learn more about WebView and alternatives for serving web content, read the
 * documentation on
 * <a href="/guide/webapps/">
 * Web-based content</a>.
 *
 */
// Implementation notes.
// The WebView is a thin API class that delegates its public API to a backend WebViewProvider
// class instance. WebView extends {@link AbsoluteLayout} for backward compatibility reasons.
// Methods are delegated to the provider implementation: all public API methods introduced in this
// file are fully delegated, whereas public and protected methods from the View base classes are
// only delegated where a specific need exists for them to do so.
@Widget
public class WebView extends AbsoluteLayout
        implements ViewTreeObserver.OnGlobalFocusChangeListener,
        ViewGroup.OnHierarchyChangeListener, ViewDebug.HierarchyHandler {

    private static final String LOGTAG = "WebView";

    // Throwing an exception for incorrect thread usage if the
    // build target is JB MR2 or newer. Defaults to false, and is
    // set in the WebView constructor.
    private static volatile boolean sEnforceThreadChecking = false;

    /**
     *  Transportation object for returning WebView across thread boundaries.
     */
    public class WebViewTransport {
        private WebView mWebview;

        /**
         * Sets the WebView to the transportation object.
         *
         * @param webview the WebView to transport
         */
        public synchronized void setWebView(WebView webview) {
            mWebview = webview;
        }

        /**
         * Gets the WebView object.
         *
         * @return the transported WebView object
         */
        public synchronized WebView getWebView() {
            return mWebview;
        }
    }

    /**
     * URI scheme for telephone number.
     */
    public static final String SCHEME_TEL = "tel:";
    /**
     * URI scheme for email address.
     */
    public static final String SCHEME_MAILTO = "mailto:";
    /**
     * URI scheme for map address.
     */
    public static final String SCHEME_GEO = "geo:0,0?q=";

    /**
     * Interface to listen for find results.
     */
    public interface FindListener {
        /**
         * Notifies the listener about progress made by a find operation.
         *
         * @param activeMatchOrdinal the zero-based ordinal of the currently selected match
         * @param numberOfMatches how many matches have been found
         * @param isDoneCounting whether the find operation has actually completed. The listener
         *                       may be notified multiple times while the
         *                       operation is underway, and the numberOfMatches
         *                       value should not be considered final unless
         *                       isDoneCounting is {@code true}.
         */
        public void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches,
            boolean isDoneCounting);
    }

    /**
     * Callback interface supplied to {@link #postVisualStateCallback} for receiving
     * notifications about the visual state.
     */
    public static abstract class VisualStateCallback {
        /**
         * Invoked when the visual state is ready to be drawn in the next {@link #onDraw}.
         *
         * @param requestId The identifier passed to {@link #postVisualStateCallback} when this
         *                  callback was posted.
         */
        public abstract void onComplete(long requestId);
    }

    /**
     * Interface to listen for new pictures as they change.
     *
     * @deprecated This interface is now obsolete.
     */
    @Deprecated
    public interface PictureListener {
        /**
         * Used to provide notification that the WebView's picture has changed.
         * See {@link WebView#capturePicture} for details of the picture.
         *
         * @param view the WebView that owns the picture
         * @param picture the new picture. Applications targeting
         *     {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2} or above
         *     will always receive a {@code null} Picture.
         * @deprecated Deprecated due to internal changes.
         */
        @Deprecated
        void onNewPicture(WebView view, @Nullable Picture picture);
    }

    public static class HitTestResult {
        /**
         * Default HitTestResult, where the target is unknown.
         */
        public static final int UNKNOWN_TYPE = 0;
        /**
         * @deprecated This type is no longer used.
         */
        @Deprecated
        public static final int ANCHOR_TYPE = 1;
        /**
         * HitTestResult for hitting a phone number.
         */
        public static final int PHONE_TYPE = 2;
        /**
         * HitTestResult for hitting a map address.
         */
        public static final int GEO_TYPE = 3;
        /**
         * HitTestResult for hitting an email address.
         */
        public static final int EMAIL_TYPE = 4;
        /**
         * HitTestResult for hitting an HTML::img tag.
         */
        public static final int IMAGE_TYPE = 5;
        /**
         * @deprecated This type is no longer used.
         */
        @Deprecated
        public static final int IMAGE_ANCHOR_TYPE = 6;
        /**
         * HitTestResult for hitting a HTML::a tag with src=http.
         */
        public static final int SRC_ANCHOR_TYPE = 7;
        /**
         * HitTestResult for hitting a HTML::a tag with src=http + HTML::img.
         */
        public static final int SRC_IMAGE_ANCHOR_TYPE = 8;
        /**
         * HitTestResult for hitting an edit text area.
         */
        public static final int EDIT_TEXT_TYPE = 9;

        private int mType;
        private String mExtra;

        /**
         * @hide Only for use by WebViewProvider implementations
         */
        @SystemApi
        public HitTestResult() {
            mType = UNKNOWN_TYPE;
        }

        /**
         * @hide Only for use by WebViewProvider implementations
         */
        @SystemApi
        public void setType(int type) {
            mType = type;
        }

        /**
         * @hide Only for use by WebViewProvider implementations
         */
        @SystemApi
        public void setExtra(String extra) {
            mExtra = extra;
        }

        /**
         * Gets the type of the hit test result. See the XXX_TYPE constants
         * defined in this class.
         *
         * @return the type of the hit test result
         */
        public int getType() {
            return mType;
        }

        /**
         * Gets additional type-dependant information about the result. See
         * {@link WebView#getHitTestResult()} for details. May either be {@code null}
         * or contain extra information about this result.
         *
         * @return additional type-dependant information about the result
         */
        @Nullable
        public String getExtra() {
            return mExtra;
        }
    }

    /**
     * Constructs a new WebView with an Activity Context object.
     *
     * <p class="note"><b>Note:</b> WebView should always be instantiated with an Activity Context.
     * If instantiated with an Application Context, WebView will be unable to provide several
     * features, such as JavaScript dialogs and autofill.
     *
     * @param context an Activity Context to access application assets
     */
    public WebView(Context context) {
        this(context, null);
    }

    /**
     * Constructs a new WebView with layout parameters.
     *
     * @param context an Activity Context to access application assets
     * @param attrs an AttributeSet passed to our parent
     */
    public WebView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.webViewStyle);
    }

    /**
     * Constructs a new WebView with layout parameters and a default style.
     *
     * @param context an Activity Context to access application assets
     * @param attrs an AttributeSet passed to our parent
     * @param defStyleAttr an attribute in the current theme that contains a
     *        reference to a style resource that supplies default values for
     *        the view. Can be 0 to not look for defaults.
     */
    public WebView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    /**
     * Constructs a new WebView with layout parameters and a default style.
     *
     * @param context an Activity Context to access application assets
     * @param attrs an AttributeSet passed to our parent
     * @param defStyleAttr an attribute in the current theme that contains a
     *        reference to a style resource that supplies default values for
     *        the view. Can be 0 to not look for defaults.
     * @param defStyleRes a resource identifier of a style resource that
     *        supplies default values for the view, used only if
     *        defStyleAttr is 0 or can not be found in the theme. Can be 0
     *        to not look for defaults.
     */
    public WebView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        this(context, attrs, defStyleAttr, defStyleRes, null, false);
    }

    /**
     * Constructs a new WebView with layout parameters and a default style.
     *
     * @param context an Activity Context to access application assets
     * @param attrs an AttributeSet passed to our parent
     * @param defStyleAttr an attribute in the current theme that contains a
     *        reference to a style resource that supplies default values for
     *        the view. Can be 0 to not look for defaults.
     * @param privateBrowsing whether this WebView will be initialized in
     *                        private mode
     *
     * @deprecated Private browsing is no longer supported directly via
     * WebView and will be removed in a future release. Prefer using
     * {@link WebSettings}, {@link WebViewDatabase}, {@link CookieManager}
     * and {@link WebStorage} for fine-grained control of privacy data.
     */
    @Deprecated
    public WebView(Context context, AttributeSet attrs, int defStyleAttr,
            boolean privateBrowsing) {
        this(context, attrs, defStyleAttr, 0, null, privateBrowsing);
    }

    /**
     * Constructs a new WebView with layout parameters, a default style and a set
     * of custom JavaScript interfaces to be added to this WebView at initialization
     * time. This guarantees that these interfaces will be available when the JS
     * context is initialized.
     *
     * @param context an Activity Context to access application assets
     * @param attrs an AttributeSet passed to our parent
     * @param defStyleAttr an attribute in the current theme that contains a
     *        reference to a style resource that supplies default values for
     *        the view. Can be 0 to not look for defaults.
     * @param javaScriptInterfaces a Map of interface names, as keys, and
     *                             object implementing those interfaces, as
     *                             values
     * @param privateBrowsing whether this WebView will be initialized in
     *                        private mode
     * @hide This is used internally by dumprendertree, as it requires the JavaScript interfaces to
     *       be added synchronously, before a subsequent loadUrl call takes effect.
     */
    protected WebView(Context context, AttributeSet attrs, int defStyleAttr,
            Map<String, Object> javaScriptInterfaces, boolean privateBrowsing) {
        this(context, attrs, defStyleAttr, 0, javaScriptInterfaces, privateBrowsing);
    }

    /**
     * @hide
     */
    @SuppressWarnings("deprecation")  // for super() call into deprecated base class constructor.
    protected WebView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes,
            Map<String, Object> javaScriptInterfaces, boolean privateBrowsing) {
        super(context, attrs, defStyleAttr, defStyleRes);

        // WebView is important by default, unless app developer overrode attribute.
        if (getImportantForAutofill() == IMPORTANT_FOR_AUTOFILL_AUTO) {
            setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_YES);
        }

        if (context == null) {
            throw new IllegalArgumentException("Invalid context argument");
        }
        if (mWebViewThread == null) {
            throw new RuntimeException(
                "WebView cannot be initialized on a thread that has no Looper.");
        }
        sEnforceThreadChecking = context.getApplicationInfo().targetSdkVersion >=
                Build.VERSION_CODES.JELLY_BEAN_MR2;
        checkThread();

        ensureProviderCreated();
        mProvider.init(javaScriptInterfaces, privateBrowsing);
        // Post condition of creating a webview is the CookieSyncManager.getInstance() is allowed.
        CookieSyncManager.setGetInstanceIsAllowed();
    }

    /**
     * Specifies whether the horizontal scrollbar has overlay style.
     *
     * @deprecated This method has no effect.
     * @param overlay {@code true} if horizontal scrollbar should have overlay style
     */
    @Deprecated
    public void setHorizontalScrollbarOverlay(boolean overlay) {
    }

    /**
     * Specifies whether the vertical scrollbar has overlay style.
     *
     * @deprecated This method has no effect.
     * @param overlay {@code true} if vertical scrollbar should have overlay style
     */
    @Deprecated
    public void setVerticalScrollbarOverlay(boolean overlay) {
    }

    /**
     * Gets whether horizontal scrollbar has overlay style.
     *
     * @deprecated This method is now obsolete.
     * @return {@code true}
     */
    @Deprecated
    public boolean overlayHorizontalScrollbar() {
        // The old implementation defaulted to true, so return true for consistency
        return true;
    }

    /**
     * Gets whether vertical scrollbar has overlay style.
     *
     * @deprecated This method is now obsolete.
     * @return {@code false}
     */
    @Deprecated
    public boolean overlayVerticalScrollbar() {
        // The old implementation defaulted to false, so return false for consistency
        return false;
    }

    /**
     * Gets the visible height (in pixels) of the embedded title bar (if any).
     *
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public int getVisibleTitleHeight() {
        checkThread();
        return mProvider.getVisibleTitleHeight();
    }

    /**
     * Gets the SSL certificate for the main top-level page or {@code null} if there is
     * no certificate (the site is not secure).
     *
     * @return the SSL certificate for the main top-level page
     */
    @Nullable
    public SslCertificate getCertificate() {
        checkThread();
        return mProvider.getCertificate();
    }

    /**
     * Sets the SSL certificate for the main top-level page.
     *
     * @deprecated Calling this function has no useful effect, and will be
     * ignored in future releases.
     */
    @Deprecated
    public void setCertificate(SslCertificate certificate) {
        checkThread();
        mProvider.setCertificate(certificate);
    }

    //-------------------------------------------------------------------------
    // Methods called by activity
    //-------------------------------------------------------------------------

    /**
     * Sets a username and password pair for the specified host. This data is
     * used by the WebView to autocomplete username and password fields in web
     * forms. Note that this is unrelated to the credentials used for HTTP
     * authentication.
     *
     * @param host the host that required the credentials
     * @param username the username for the given host
     * @param password the password for the given host
     * @see WebViewDatabase#clearUsernamePassword
     * @see WebViewDatabase#hasUsernamePassword
     * @deprecated Saving passwords in WebView will not be supported in future versions.
     */
    @Deprecated
    public void savePassword(String host, String username, String password) {
        checkThread();
        mProvider.savePassword(host, username, password);
    }

    /**
     * Stores HTTP authentication credentials for a given host and realm to the {@link WebViewDatabase}
     * instance.
     *
     * @param host the host to which the credentials apply
     * @param realm the realm to which the credentials apply
     * @param username the username
     * @param password the password
     * @deprecated Use {@link WebViewDatabase#setHttpAuthUsernamePassword} instead
     */
    @Deprecated
    public void setHttpAuthUsernamePassword(String host, String realm,
            String username, String password) {
        checkThread();
        mProvider.setHttpAuthUsernamePassword(host, realm, username, password);
    }

    /**
     * Retrieves HTTP authentication credentials for a given host and realm from the {@link
     * WebViewDatabase} instance.
     * @param host the host to which the credentials apply
     * @param realm the realm to which the credentials apply
     * @return the credentials as a String array, if found. The first element
     *         is the username and the second element is the password. {@code null} if
     *         no credentials are found.
     * @deprecated Use {@link WebViewDatabase#getHttpAuthUsernamePassword} instead
     */
    @Deprecated
    @Nullable
    public String[] getHttpAuthUsernamePassword(String host, String realm) {
        checkThread();
        return mProvider.getHttpAuthUsernamePassword(host, realm);
    }

    /**
     * Destroys the internal state of this WebView. This method should be called
     * after this WebView has been removed from the view system. No other
     * methods may be called on this WebView after destroy.
     */
    public void destroy() {
        checkThread();
        mProvider.destroy();
    }

    /**
     * Enables platform notifications of data state and proxy changes.
     * Notifications are enabled by default.
     *
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public static void enablePlatformNotifications() {
        // noop
    }

    /**
     * Disables platform notifications of data state and proxy changes.
     * Notifications are enabled by default.
     *
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public static void disablePlatformNotifications() {
        // noop
    }

    /**
     * Used only by internal tests to free up memory.
     *
     * @hide
     */
    public static void freeMemoryForTests() {
        getFactory().getStatics().freeMemoryForTests();
    }

    /**
     * Informs WebView of the network state. This is used to set
     * the JavaScript property window.navigator.isOnline and
     * generates the online/offline event as specified in HTML5, sec. 5.7.7
     *
     * @param networkUp a boolean indicating if network is available
     */
    public void setNetworkAvailable(boolean networkUp) {
        checkThread();
        mProvider.setNetworkAvailable(networkUp);
    }

    /**
     * Saves the state of this WebView used in
     * {@link android.app.Activity#onSaveInstanceState}. Please note that this
     * method no longer stores the display data for this WebView. The previous
     * behavior could potentially leak files if {@link #restoreState} was never
     * called.
     *
     * @param outState the Bundle to store this WebView's state
     * @return the same copy of the back/forward list used to save the state, {@code null} if the
     *         method fails.
     */
    @Nullable
    public WebBackForwardList saveState(Bundle outState) {
        checkThread();
        return mProvider.saveState(outState);
    }

    /**
     * Saves the current display data to the Bundle given. Used in conjunction
     * with {@link #saveState}.
     * @param b a Bundle to store the display data
     * @param dest the file to store the serialized picture data. Will be
     *             overwritten with this WebView's picture data.
     * @return {@code true} if the picture was successfully saved
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public boolean savePicture(Bundle b, final File dest) {
        checkThread();
        return mProvider.savePicture(b, dest);
    }

    /**
     * Restores the display data that was saved in {@link #savePicture}. Used in
     * conjunction with {@link #restoreState}. Note that this will not work if
     * this WebView is hardware accelerated.
     *
     * @param b a Bundle containing the saved display data
     * @param src the file where the picture data was stored
     * @return {@code true} if the picture was successfully restored
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public boolean restorePicture(Bundle b, File src) {
        checkThread();
        return mProvider.restorePicture(b, src);
    }

    /**
     * Restores the state of this WebView from the given Bundle. This method is
     * intended for use in {@link android.app.Activity#onRestoreInstanceState}
     * and should be called to restore the state of this WebView. If
     * it is called after this WebView has had a chance to build state (load
     * pages, create a back/forward list, etc.) there may be undesirable
     * side-effects. Please note that this method no longer restores the
     * display data for this WebView.
     *
     * @param inState the incoming Bundle of state
     * @return the restored back/forward list or {@code null} if restoreState failed
     */
    @Nullable
    public WebBackForwardList restoreState(Bundle inState) {
        checkThread();
        return mProvider.restoreState(inState);
    }

    /**
     * Loads the given URL with the specified additional HTTP headers.
     * <p>
     * Also see compatibility note on {@link #evaluateJavascript}.
     *
     * @param url the URL of the resource to load
     * @param additionalHttpHeaders the additional headers to be used in the
     *            HTTP request for this URL, specified as a map from name to
     *            value. Note that if this map contains any of the headers
     *            that are set by default by this WebView, such as those
     *            controlling caching, accept types or the User-Agent, their
     *            values may be overridden by this WebView's defaults.
     */
    public void loadUrl(String url, Map<String, String> additionalHttpHeaders) {
        checkThread();
        mProvider.loadUrl(url, additionalHttpHeaders);
    }

    /**
     * Loads the given URL.
     * <p>
     * Also see compatibility note on {@link #evaluateJavascript}.
     *
     * @param url the URL of the resource to load
     */
    public void loadUrl(String url) {
        checkThread();
        mProvider.loadUrl(url);
    }

    /**
     * Loads the URL with postData using "POST" method into this WebView. If url
     * is not a network URL, it will be loaded with {@link #loadUrl(String)}
     * instead, ignoring the postData param.
     *
     * @param url the URL of the resource to load
     * @param postData the data will be passed to "POST" request, which must be
     *     be "application/x-www-form-urlencoded" encoded.
     */
    public void postUrl(String url, byte[] postData) {
        checkThread();
        if (URLUtil.isNetworkUrl(url)) {
            mProvider.postUrl(url, postData);
        } else {
            mProvider.loadUrl(url);
        }
    }

    /**
     * Loads the given data into this WebView using a 'data' scheme URL.
     * <p>
     * Note that JavaScript's same origin policy means that script running in a
     * page loaded using this method will be unable to access content loaded
     * using any scheme other than 'data', including 'http(s)'. To avoid this
     * restriction, use {@link
     * #loadDataWithBaseURL(String,String,String,String,String)
     * loadDataWithBaseURL()} with an appropriate base URL.
     * <p>
     * The {@code encoding} parameter specifies whether the data is base64 or URL
     * encoded. If the data is base64 encoded, the value of the encoding
     * parameter must be 'base64'. HTML can be encoded with {@link
     * android.util.Base64#encodeToString(byte[],int)} like so:
     * <pre>
     * String unencodedHtml =
     *     "&lt;html&gt;&lt;body&gt;'%28' is the code for '('&lt;/body&gt;&lt;/html&gt;";
     * String encodedHtml = Base64.encodeToString(unencodedHtml.getBytes(), Base64.NO_PADDING);
     * webView.loadData(encodedHtml, "text/html", "base64");
     * </pre>
     * <p>
     * For all other values of {@code encoding} (including {@code null}) it is assumed that the
     * data uses ASCII encoding for octets inside the range of safe URL characters and use the
     * standard %xx hex encoding of URLs for octets outside that range. See <a
     * href="https://tools.ietf.org/html/rfc3986#section-2.2">RFC 3986</a> for more information.
     * <p>
     * The {@code mimeType} parameter specifies the format of the data.
     * If WebView can't handle the specified MIME type, it will download the data.
     * If {@code null}, defaults to 'text/html'.
     * <p>
     * The 'data' scheme URL formed by this method uses the default US-ASCII
     * charset. If you need to set a different charset, you should form a
     * 'data' scheme URL which explicitly specifies a charset parameter in the
     * mediatype portion of the URL and call {@link #loadUrl(String)} instead.
     * Note that the charset obtained from the mediatype portion of a data URL
     * always overrides that specified in the HTML or XML document itself.
     * <p>
     * Content loaded using this method will have a {@code window.origin} value
     * of {@code "null"}. This must not be considered to be a trusted origin
     * by the application or by any JavaScript code running inside the WebView
     * (for example, event sources in DOM event handlers or web messages),
     * because malicious content can also create frames with a null origin. If
     * you need to identify the main frame's origin in a trustworthy way, you
     * should use {@link #loadDataWithBaseURL(String,String,String,String,String)
     * loadDataWithBaseURL()} with a valid HTTP or HTTPS base URL to set the
     * origin.
     *
     * @param data a String of data in the given encoding
     * @param mimeType the MIME type of the data, e.g. 'text/html'.
     * @param encoding the encoding of the data
     */
    public void loadData(String data, @Nullable String mimeType, @Nullable String encoding) {
        checkThread();
        mProvider.loadData(data, mimeType, encoding);
    }

    /**
     * Loads the given data into this WebView, using baseUrl as the base URL for
     * the content. The base URL is used both to resolve relative URLs and when
     * applying JavaScript's same origin policy. The historyUrl is used for the
     * history entry.
     * <p>
     * The {@code mimeType} parameter specifies the format of the data.
     * If WebView can't handle the specified MIME type, it will download the data.
     * If {@code null}, defaults to 'text/html'.
     * <p>
     * Note that content specified in this way can access local device files
     * (via 'file' scheme URLs) only if baseUrl specifies a scheme other than
     * 'http', 'https', 'ftp', 'ftps', 'about' or 'javascript'.
     * <p>
     * If the base URL uses the data scheme, this method is equivalent to
     * calling {@link #loadData(String,String,String) loadData()} and the
     * historyUrl is ignored, and the data will be treated as part of a data: URL.
     * If the base URL uses any other scheme, then the data will be loaded into
     * the WebView as a plain string (i.e. not part of a data URL) and any URL-encoded
     * entities in the string will not be decoded.
     * <p>
     * Note that the baseUrl is sent in the 'Referer' HTTP header when
     * requesting subresources (images, etc.) of the page loaded using this method.
     * <p>
     * If a valid HTTP or HTTPS base URL is not specified in {@code baseUrl}, then
     * content loaded using this method will have a {@code window.origin} value
     * of {@code "null"}. This must not be considered to be a trusted origin
     * by the application or by any JavaScript code running inside the WebView
     * (for example, event sources in DOM event handlers or web messages),
     * because malicious content can also create frames with a null origin. If
     * you need to identify the main frame's origin in a trustworthy way, you
     * should use a valid HTTP or HTTPS base URL to set the origin.
     *
     * @param baseUrl the URL to use as the page's base URL. If {@code null} defaults to
     *                'about:blank'.
     * @param data a String of data in the given encoding
     * @param mimeType the MIME type of the data, e.g. 'text/html'.
     * @param encoding the encoding of the data
     * @param historyUrl the URL to use as the history entry. If {@code null} defaults
     *                   to 'about:blank'. If non-null, this must be a valid URL.
     */
    public void loadDataWithBaseURL(@Nullable String baseUrl, String data,
            @Nullable String mimeType, @Nullable String encoding, @Nullable String historyUrl) {
        checkThread();
        mProvider.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl);
    }

    /**
     * Asynchronously evaluates JavaScript in the context of the currently displayed page.
     * If non-null, {@code resultCallback} will be invoked with any result returned from that
     * execution. This method must be called on the UI thread and the callback will
     * be made on the UI thread.
     * <p>
     * Compatibility note. Applications targeting {@link android.os.Build.VERSION_CODES#N} or
     * later, JavaScript state from an empty WebView is no longer persisted across navigations like
     * {@link #loadUrl(String)}. For example, global variables and functions defined before calling
     * {@link #loadUrl(String)} will not exist in the loaded page. Applications should use
     * {@link #addJavascriptInterface} instead to persist JavaScript objects across navigations.
     *
     * @param script the JavaScript to execute.
     * @param resultCallback A callback to be invoked when the script execution
     *                       completes with the result of the execution (if any).
     *                       May be {@code null} if no notification of the result is required.
     */
    public void evaluateJavascript(String script, @Nullable ValueCallback<String> resultCallback) {
        checkThread();
        mProvider.evaluateJavaScript(script, resultCallback);
    }

    /**
     * Saves the current view as a web archive.
     *
     * @param filename the filename where the archive should be placed
     */
    public void saveWebArchive(String filename) {
        checkThread();
        mProvider.saveWebArchive(filename);
    }

    /**
     * Saves the current view as a web archive.
     *
     * @param basename the filename where the archive should be placed
     * @param autoname if {@code false}, takes basename to be a file. If {@code true}, basename
     *                 is assumed to be a directory in which a filename will be
     *                 chosen according to the URL of the current page.
     * @param callback called after the web archive has been saved. The
     *                 parameter for onReceiveValue will either be the filename
     *                 under which the file was saved, or {@code null} if saving the
     *                 file failed.
     */
    public void saveWebArchive(String basename, boolean autoname, @Nullable ValueCallback<String>
            callback) {
        checkThread();
        mProvider.saveWebArchive(basename, autoname, callback);
    }

    /**
     * Stops the current load.
     */
    public void stopLoading() {
        checkThread();
        mProvider.stopLoading();
    }

    /**
     * Reloads the current URL.
     */
    public void reload() {
        checkThread();
        mProvider.reload();
    }

    /**
     * Gets whether this WebView has a back history item.
     *
     * @return {@code true} if this WebView has a back history item
     */
    public boolean canGoBack() {
        checkThread();
        return mProvider.canGoBack();
    }

    /**
     * Goes back in the history of this WebView.
     */
    public void goBack() {
        checkThread();
        mProvider.goBack();
    }

    /**
     * Gets whether this WebView has a forward history item.
     *
     * @return {@code true} if this WebView has a forward history item
     */
    public boolean canGoForward() {
        checkThread();
        return mProvider.canGoForward();
    }

    /**
     * Goes forward in the history of this WebView.
     */
    public void goForward() {
        checkThread();
        mProvider.goForward();
    }

    /**
     * Gets whether the page can go back or forward the given
     * number of steps.
     *
     * @param steps the negative or positive number of steps to move the
     *              history
     */
    public boolean canGoBackOrForward(int steps) {
        checkThread();
        return mProvider.canGoBackOrForward(steps);
    }

    /**
     * Goes to the history item that is the number of steps away from
     * the current item. Steps is negative if backward and positive
     * if forward.
     *
     * @param steps the number of steps to take back or forward in the back
     *              forward list
     */
    public void goBackOrForward(int steps) {
        checkThread();
        mProvider.goBackOrForward(steps);
    }

    /**
     * Gets whether private browsing is enabled in this WebView.
     */
    public boolean isPrivateBrowsingEnabled() {
        checkThread();
        return mProvider.isPrivateBrowsingEnabled();
    }

    /**
     * Scrolls the contents of this WebView up by half the view size.
     *
     * @param top {@code true} to jump to the top of the page
     * @return {@code true} if the page was scrolled
     */
    public boolean pageUp(boolean top) {
        checkThread();
        return mProvider.pageUp(top);
    }

    /**
     * Scrolls the contents of this WebView down by half the page size.
     *
     * @param bottom {@code true} to jump to bottom of page
     * @return {@code true} if the page was scrolled
     */
    public boolean pageDown(boolean bottom) {
        checkThread();
        return mProvider.pageDown(bottom);
    }

    /**
     * Posts a {@link VisualStateCallback}, which will be called when
     * the current state of the WebView is ready to be drawn.
     *
     * <p>Because updates to the DOM are processed asynchronously, updates to the DOM may not
     * immediately be reflected visually by subsequent {@link WebView#onDraw} invocations. The
     * {@link VisualStateCallback} provides a mechanism to notify the caller when the contents of
     * the DOM at the current time are ready to be drawn the next time the {@link WebView}
     * draws.
     *
     * <p>The next draw after the callback completes is guaranteed to reflect all the updates to the
     * DOM up to the point at which the {@link VisualStateCallback} was posted, but it may also
     * contain updates applied after the callback was posted.
     *
     * <p>The state of the DOM covered by this API includes the following:
     * <ul>
     * <li>primitive HTML elements (div, img, span, etc..)</li>
     * <li>images</li>
     * <li>CSS animations</li>
     * <li>WebGL</li>
     * <li>canvas</li>
     * </ul>
     * It does not include the state of:
     * <ul>
     * <li>the video tag</li>
     * </ul>
     *
     * <p>To guarantee that the {@link WebView} will successfully render the first frame
     * after the {@link VisualStateCallback#onComplete} method has been called a set of conditions
     * must be met:
     * <ul>
     * <li>If the {@link WebView}'s visibility is set to {@link View#VISIBLE VISIBLE} then
     * the {@link WebView} must be attached to the view hierarchy.</li>
     * <li>If the {@link WebView}'s visibility is set to {@link View#INVISIBLE INVISIBLE}
     * then the {@link WebView} must be attached to the view hierarchy and must be made
     * {@link View#VISIBLE VISIBLE} from the {@link VisualStateCallback#onComplete} method.</li>
     * <li>If the {@link WebView}'s visibility is set to {@link View#GONE GONE} then the
     * {@link WebView} must be attached to the view hierarchy and its
     * {@link AbsoluteLayout.LayoutParams LayoutParams}'s width and height need to be set to fixed
     * values and must be made {@link View#VISIBLE VISIBLE} from the
     * {@link VisualStateCallback#onComplete} method.</li>
     * </ul>
     *
     * <p>When using this API it is also recommended to enable pre-rasterization if the {@link
     * WebView} is off screen to avoid flickering. See {@link WebSettings#setOffscreenPreRaster} for
     * more details and do consider its caveats.
     *
     * @param requestId An id that will be returned in the callback to allow callers to match
     *                  requests with callbacks.
     * @param callback  The callback to be invoked.
     */
    public void postVisualStateCallback(long requestId, VisualStateCallback callback) {
        checkThread();
        mProvider.insertVisualStateCallback(requestId, callback);
    }

    /**
     * Clears this WebView so that onDraw() will draw nothing but white background,
     * and onMeasure() will return 0 if MeasureSpec is not MeasureSpec.EXACTLY.
     * @deprecated Use WebView.loadUrl("about:blank") to reliably reset the view state
     *             and release page resources (including any running JavaScript).
     */
    @Deprecated
    public void clearView() {
        checkThread();
        mProvider.clearView();
    }

    /**
     * Gets a new picture that captures the current contents of this WebView.
     * The picture is of the entire document being displayed, and is not
     * limited to the area currently displayed by this WebView. Also, the
     * picture is a static copy and is unaffected by later changes to the
     * content being displayed.
     * <p>
     * Note that due to internal changes, for API levels between
     * {@link android.os.Build.VERSION_CODES#HONEYCOMB} and
     * {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH} inclusive, the
     * picture does not include fixed position elements or scrollable divs.
     * <p>
     * Note that from {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1} the returned picture
     * should only be drawn into bitmap-backed Canvas - using any other type of Canvas will involve
     * additional conversion at a cost in memory and performance. Also the
     * {@link android.graphics.Picture#createFromStream} and
     * {@link android.graphics.Picture#writeToStream} methods are not supported on the
     * returned object.
     *
     * @deprecated Use {@link #onDraw} to obtain a bitmap snapshot of the WebView, or
     * {@link #saveWebArchive} to save the content to a file.
     *
     * @return a picture that captures the current contents of this WebView
     */
    @Deprecated
    public Picture capturePicture() {
        checkThread();
        return mProvider.capturePicture();
    }

    /**
     * @deprecated Use {@link #createPrintDocumentAdapter(String)} which requires user
     *             to provide a print document name.
     */
    @Deprecated
    public PrintDocumentAdapter createPrintDocumentAdapter() {
        checkThread();
        return mProvider.createPrintDocumentAdapter("default");
    }

    /**
     * Creates a PrintDocumentAdapter that provides the content of this WebView for printing.
     *
     * The adapter works by converting the WebView contents to a PDF stream. The WebView cannot
     * be drawn during the conversion process - any such draws are undefined. It is recommended
     * to use a dedicated off screen WebView for the printing. If necessary, an application may
     * temporarily hide a visible WebView by using a custom PrintDocumentAdapter instance
     * wrapped around the object returned and observing the onStart and onFinish methods. See
     * {@link android.print.PrintDocumentAdapter} for more information.
     *
     * @param documentName  The user-facing name of the printed document. See
     *                      {@link android.print.PrintDocumentInfo}
     */
    public PrintDocumentAdapter createPrintDocumentAdapter(String documentName) {
        checkThread();
        return mProvider.createPrintDocumentAdapter(documentName);
    }

    /**
     * Gets the current scale of this WebView.
     *
     * @return the current scale
     *
     * @deprecated This method is prone to inaccuracy due to race conditions
     * between the web rendering and UI threads; prefer
     * {@link WebViewClient#onScaleChanged}.
     */
    @Deprecated
    @ViewDebug.ExportedProperty(category = "webview")
    public float getScale() {
        checkThread();
        return mProvider.getScale();
    }

    /**
     * Sets the initial scale for this WebView. 0 means default.
     * The behavior for the default scale depends on the state of
     * {@link WebSettings#getUseWideViewPort()} and
     * {@link WebSettings#getLoadWithOverviewMode()}.
     * If the content fits into the WebView control by width, then
     * the zoom is set to 100%. For wide content, the behavior
     * depends on the state of {@link WebSettings#getLoadWithOverviewMode()}.
     * If its value is {@code true}, the content will be zoomed out to be fit
     * by width into the WebView control, otherwise not.
     *
     * If initial scale is greater than 0, WebView starts with this value
     * as initial scale.
     * Please note that unlike the scale properties in the viewport meta tag,
     * this method doesn't take the screen density into account.
     *
     * @param scaleInPercent the initial scale in percent
     */
    public void setInitialScale(int scaleInPercent) {
        checkThread();
        mProvider.setInitialScale(scaleInPercent);
    }

    /**
     * Invokes the graphical zoom picker widget for this WebView. This will
     * result in the zoom widget appearing on the screen to control the zoom
     * level of this WebView.
     */
    public void invokeZoomPicker() {
        checkThread();
        mProvider.invokeZoomPicker();
    }

    /**
     * Gets a HitTestResult based on the current cursor node. If a HTML::a
     * tag is found and the anchor has a non-JavaScript URL, the HitTestResult
     * type is set to SRC_ANCHOR_TYPE and the URL is set in the "extra" field.
     * If the anchor does not have a URL or if it is a JavaScript URL, the type
     * will be UNKNOWN_TYPE and the URL has to be retrieved through
     * {@link #requestFocusNodeHref} asynchronously. If a HTML::img tag is
     * found, the HitTestResult type is set to IMAGE_TYPE and the URL is set in
     * the "extra" field. A type of
     * SRC_IMAGE_ANCHOR_TYPE indicates an anchor with a URL that has an image as
     * a child node. If a phone number is found, the HitTestResult type is set
     * to PHONE_TYPE and the phone number is set in the "extra" field of
     * HitTestResult. If a map address is found, the HitTestResult type is set
     * to GEO_TYPE and the address is set in the "extra" field of HitTestResult.
     * If an email address is found, the HitTestResult type is set to EMAIL_TYPE
     * and the email is set in the "extra" field of HitTestResult. Otherwise,
     * HitTestResult type is set to UNKNOWN_TYPE.
     */
    public HitTestResult getHitTestResult() {
        checkThread();
        return mProvider.getHitTestResult();
    }

    /**
     * Requests the anchor or image element URL at the last tapped point.
     * If hrefMsg is {@code null}, this method returns immediately and does not
     * dispatch hrefMsg to its target. If the tapped point hits an image,
     * an anchor, or an image in an anchor, the message associates
     * strings in named keys in its data. The value paired with the key
     * may be an empty string.
     *
     * @param hrefMsg the message to be dispatched with the result of the
     *                request. The message data contains three keys. "url"
     *                returns the anchor's href attribute. "title" returns the
     *                anchor's text. "src" returns the image's src attribute.
     */
    public void requestFocusNodeHref(@Nullable Message hrefMsg) {
        checkThread();
        mProvider.requestFocusNodeHref(hrefMsg);
    }

    /**
     * Requests the URL of the image last touched by the user. msg will be sent
     * to its target with a String representing the URL as its object.
     *
     * @param msg the message to be dispatched with the result of the request
     *            as the data member with "url" as key. The result can be {@code null}.
     */
    public void requestImageRef(Message msg) {
        checkThread();
        mProvider.requestImageRef(msg);
    }

    /**
     * Gets the URL for the current page. This is not always the same as the URL
     * passed to WebViewClient.onPageStarted because although the load for
     * that URL has begun, the current page may not have changed.
     *
     * @return the URL for the current page
     */
    @ViewDebug.ExportedProperty(category = "webview")
    public String getUrl() {
        checkThread();
        return mProvider.getUrl();
    }

    /**
     * Gets the original URL for the current page. This is not always the same
     * as the URL passed to WebViewClient.onPageStarted because although the
     * load for that URL has begun, the current page may not have changed.
     * Also, there may have been redirects resulting in a different URL to that
     * originally requested.
     *
     * @return the URL that was originally requested for the current page
     */
    @ViewDebug.ExportedProperty(category = "webview")
    public String getOriginalUrl() {
        checkThread();
        return mProvider.getOriginalUrl();
    }

    /**
     * Gets the title for the current page. This is the title of the current page
     * until WebViewClient.onReceivedTitle is called.
     *
     * @return the title for the current page
     */
    @ViewDebug.ExportedProperty(category = "webview")
    public String getTitle() {
        checkThread();
        return mProvider.getTitle();
    }

    /**
     * Gets the favicon for the current page. This is the favicon of the current
     * page until WebViewClient.onReceivedIcon is called.
     *
     * @return the favicon for the current page
     */
    public Bitmap getFavicon() {
        checkThread();
        return mProvider.getFavicon();
    }

    /**
     * Gets the touch icon URL for the apple-touch-icon <link> element, or
     * a URL on this site's server pointing to the standard location of a
     * touch icon.
     *
     * @hide
     */
    public String getTouchIconUrl() {
        return mProvider.getTouchIconUrl();
    }

    /**
     * Gets the progress for the current page.
     *
     * @return the progress for the current page between 0 and 100
     */
    public int getProgress() {
        checkThread();
        return mProvider.getProgress();
    }

    /**
     * Gets the height of the HTML content.
     *
     * @return the height of the HTML content
     */
    @ViewDebug.ExportedProperty(category = "webview")
    public int getContentHeight() {
        checkThread();
        return mProvider.getContentHeight();
    }

    /**
     * Gets the width of the HTML content.
     *
     * @return the width of the HTML content
     * @hide
     */
    @ViewDebug.ExportedProperty(category = "webview")
    public int getContentWidth() {
        return mProvider.getContentWidth();
    }

    /**
     * Pauses all layout, parsing, and JavaScript timers for all WebViews. This
     * is a global requests, not restricted to just this WebView. This can be
     * useful if the application has been paused.
     */
    public void pauseTimers() {
        checkThread();
        mProvider.pauseTimers();
    }

    /**
     * Resumes all layout, parsing, and JavaScript timers for all WebViews.
     * This will resume dispatching all timers.
     */
    public void resumeTimers() {
        checkThread();
        mProvider.resumeTimers();
    }

    /**
     * Does a best-effort attempt to pause any processing that can be paused
     * safely, such as animations and geolocation. Note that this call
     * does not pause JavaScript. To pause JavaScript globally, use
     * {@link #pauseTimers}.
     *
     * To resume WebView, call {@link #onResume}.
     */
    public void onPause() {
        checkThread();
        mProvider.onPause();
    }

    /**
     * Resumes a WebView after a previous call to {@link #onPause}.
     */
    public void onResume() {
        checkThread();
        mProvider.onResume();
    }

    /**
     * Gets whether this WebView is paused, meaning onPause() was called.
     * Calling onResume() sets the paused state back to {@code false}.
     *
     * @hide
     */
    public boolean isPaused() {
        return mProvider.isPaused();
    }

    /**
     * Informs this WebView that memory is low so that it can free any available
     * memory.
     * @deprecated Memory caches are automatically dropped when no longer needed, and in response
     *             to system memory pressure.
     */
    @Deprecated
    public void freeMemory() {
        checkThread();
        mProvider.freeMemory();
    }

    /**
     * Clears the resource cache. Note that the cache is per-application, so
     * this will clear the cache for all WebViews used.
     *
     * @param includeDiskFiles if {@code false}, only the RAM cache is cleared
     */
    public void clearCache(boolean includeDiskFiles) {
        checkThread();
        mProvider.clearCache(includeDiskFiles);
    }

    /**
     * Removes the autocomplete popup from the currently focused form field, if
     * present. Note this only affects the display of the autocomplete popup,
     * it does not remove any saved form data from this WebView's store. To do
     * that, use {@link WebViewDatabase#clearFormData}.
     */
    public void clearFormData() {
        checkThread();
        mProvider.clearFormData();
    }

    /**
     * Tells this WebView to clear its internal back/forward list.
     */
    public void clearHistory() {
        checkThread();
        mProvider.clearHistory();
    }

    /**
     * Clears the SSL preferences table stored in response to proceeding with
     * SSL certificate errors.
     */
    public void clearSslPreferences() {
        checkThread();
        mProvider.clearSslPreferences();
    }

    /**
     * Clears the client certificate preferences stored in response
     * to proceeding/cancelling client cert requests. Note that WebView
     * automatically clears these preferences when the system keychain is updated.
     * The preferences are shared by all the WebViews that are created by the embedder application.
     *
     * @param onCleared  A runnable to be invoked when client certs are cleared.
     *                   The runnable will be called in UI thread.
     */
    public static void clearClientCertPreferences(@Nullable Runnable onCleared) {
        getFactory().getStatics().clearClientCertPreferences(onCleared);
    }

    /**
     * Starts Safe Browsing initialization.
     * <p>
     * URL loads are not guaranteed to be protected by Safe Browsing until after {@code callback} is
     * invoked with {@code true}. Safe Browsing is not fully supported on all devices. For those
     * devices {@code callback} will receive {@code false}.
     * <p>
     * This should not be called if Safe Browsing has been disabled by manifest tag or {@link
     * WebSettings#setSafeBrowsingEnabled}. This prepares resources used for Safe Browsing.
     * <p>
     * This should be called with the Application Context (and will always use the Application
     * context to do its work regardless).
     *
     * @param context Application Context.
     * @param callback will be called on the UI thread with {@code true} if initialization is
     * successful, {@code false} otherwise.
     */
    public static void startSafeBrowsing(@NonNull Context context,
            @Nullable ValueCallback<Boolean> callback) {
        getFactory().getStatics().initSafeBrowsing(context, callback);
    }

    /**
     * Sets the list of hosts (domain names/IP addresses) that are exempt from SafeBrowsing checks.
     * The list is global for all the WebViews.
     * <p>
     * Each rule should take one of these:
     * <table>
     * <tr><th> Rule </th> <th> Example </th> <th> Matches Subdomain</th> </tr>
     * <tr><td> HOSTNAME </td> <td> example.com </td> <td> Yes </td> </tr>
     * <tr><td> .HOSTNAME </td> <td> .example.com </td> <td> No </td> </tr>
     * <tr><td> IPV4_LITERAL </td> <td> 192.168.1.1 </td> <td> No </td></tr>
     * <tr><td> IPV6_LITERAL_WITH_BRACKETS </td><td>[10:20:30:40:50:60:70:80]</td><td>No</td></tr>
     * </table>
     * <p>
     * All other rules, including wildcards, are invalid.
     * <p>
     * The correct syntax for hosts is defined by <a
     * href="https://tools.ietf.org/html/rfc3986#section-3.2.2">RFC 3986</a>.
     *
     * @param hosts the list of hosts
     * @param callback will be called with {@code true} if hosts are successfully added to the
     * whitelist. It will be called with {@code false} if any hosts are malformed. The callback
     * will be run on the UI thread
     */
    public static void setSafeBrowsingWhitelist(@NonNull List<String> hosts,
            @Nullable ValueCallback<Boolean> callback) {
        getFactory().getStatics().setSafeBrowsingWhitelist(hosts, callback);
    }

    /**
     * Returns a URL pointing to the privacy policy for Safe Browsing reporting.
     *
     * @return the url pointing to a privacy policy document which can be displayed to users.
     */
    @NonNull
    public static Uri getSafeBrowsingPrivacyPolicyUrl() {
        return getFactory().getStatics().getSafeBrowsingPrivacyPolicyUrl();
    }

    /**
     * Gets the WebBackForwardList for this WebView. This contains the
     * back/forward list for use in querying each item in the history stack.
     * This is a copy of the private WebBackForwardList so it contains only a
     * snapshot of the current state. Multiple calls to this method may return
     * different objects. The object returned from this method will not be
     * updated to reflect any new state.
     */
    public WebBackForwardList copyBackForwardList() {
        checkThread();
        return mProvider.copyBackForwardList();

    }

    /**
     * Registers the listener to be notified as find-on-page operations
     * progress. This will replace the current listener.
     *
     * @param listener an implementation of {@link FindListener}
     */
    public void setFindListener(FindListener listener) {
        checkThread();
        setupFindListenerIfNeeded();
        mFindListener.mUserFindListener = listener;
    }

    /**
     * Highlights and scrolls to the next match found by
     * {@link #findAllAsync}, wrapping around page boundaries as necessary.
     * Notifies any registered {@link FindListener}. If {@link #findAllAsync(String)}
     * has not been called yet, or if {@link #clearMatches} has been called since the
     * last find operation, this function does nothing.
     *
     * @param forward the direction to search
     * @see #setFindListener
     */
    public void findNext(boolean forward) {
        checkThread();
        mProvider.findNext(forward);
    }

    /**
     * Finds all instances of find on the page and highlights them.
     * Notifies any registered {@link FindListener}.
     *
     * @param find the string to find
     * @return the number of occurrences of the String "find" that were found
     * @deprecated {@link #findAllAsync} is preferred.
     * @see #setFindListener
     */
    @Deprecated
    public int findAll(String find) {
        checkThread();
        StrictMode.noteSlowCall("findAll blocks UI: prefer findAllAsync");
        return mProvider.findAll(find);
    }

    /**
     * Finds all instances of find on the page and highlights them,
     * asynchronously. Notifies any registered {@link FindListener}.
     * Successive calls to this will cancel any pending searches.
     *
     * @param find the string to find.
     * @see #setFindListener
     */
    public void findAllAsync(String find) {
        checkThread();
        mProvider.findAllAsync(find);
    }

    /**
     * Starts an ActionMode for finding text in this WebView.  Only works if this
     * WebView is attached to the view system.
     *
     * @param text if non-null, will be the initial text to search for.
     *             Otherwise, the last String searched for in this WebView will
     *             be used to start.
     * @param showIme if {@code true}, show the IME, assuming the user will begin typing.
     *                If {@code false} and text is non-null, perform a find all.
     * @return {@code true} if the find dialog is shown, {@code false} otherwise
     * @deprecated This method does not work reliably on all Android versions;
     *             implementing a custom find dialog using WebView.findAllAsync()
     *             provides a more robust solution.
     */
    @Deprecated
    public boolean showFindDialog(@Nullable String text, boolean showIme) {
        checkThread();
        return mProvider.showFindDialog(text, showIme);
    }

    /**
     * Gets the first substring consisting of the address of a physical
     * location. Currently, only addresses in the United States are detected,
     * and consist of:
     * <ul>
     *   <li>a house number</li>
     *   <li>a street name</li>
     *   <li>a street type (Road, Circle, etc), either spelled out or
     *       abbreviated</li>
     *   <li>a city name</li>
     *   <li>a state or territory, either spelled out or two-letter abbr</li>
     *   <li>an optional 5 digit or 9 digit zip code</li>
     * </ul>
     * All names must be correctly capitalized, and the zip code, if present,
     * must be valid for the state. The street type must be a standard USPS
     * spelling or abbreviation. The state or territory must also be spelled
     * or abbreviated using USPS standards. The house number may not exceed
     * five digits.
     *
     * @param addr the string to search for addresses
     * @return the address, or if no address is found, {@code null}
     * @deprecated this method is superseded by {@link TextClassifier#generateLinks(
     * android.view.textclassifier.TextLinks.Request)}. Avoid using this method even when targeting
     * API levels where no alternative is available.
     */
    @Nullable
    @Deprecated
    public static String findAddress(String addr) {
        if (addr == null) {
            throw new NullPointerException("addr is null");
        }
        return FindAddress.findAddress(addr);
    }

    /**
     * For apps targeting the L release, WebView has a new default behavior that reduces
     * memory footprint and increases performance by intelligently choosing
     * the portion of the HTML document that needs to be drawn. These
     * optimizations are transparent to the developers. However, under certain
     * circumstances, an App developer may want to disable them:
     * <ol>
     *   <li>When an app uses {@link #onDraw} to do own drawing and accesses portions
     *       of the page that is way outside the visible portion of the page.</li>
     *   <li>When an app uses {@link #capturePicture} to capture a very large HTML document.
     *       Note that capturePicture is a deprecated API.</li>
     * </ol>
     * Enabling drawing the entire HTML document has a significant performance
     * cost. This method should be called before any WebViews are created.
     */
    public static void enableSlowWholeDocumentDraw() {
        getFactory().getStatics().enableSlowWholeDocumentDraw();
    }

    /**
     * Clears the highlighting surrounding text matches created by
     * {@link #findAllAsync}.
     */
    public void clearMatches() {
        checkThread();
        mProvider.clearMatches();
    }

    /**
     * Queries the document to see if it contains any image references. The
     * message object will be dispatched with arg1 being set to 1 if images
     * were found and 0 if the document does not reference any images.
     *
     * @param response the message that will be dispatched with the result
     */
    public void documentHasImages(Message response) {
        checkThread();
        mProvider.documentHasImages(response);
    }

    /**
     * Sets the WebViewClient that will receive various notifications and
     * requests. This will replace the current handler.
     *
     * @param client an implementation of WebViewClient
     * @see #getWebViewClient
     */
    public void setWebViewClient(WebViewClient client) {
        checkThread();
        mProvider.setWebViewClient(client);
    }

    /**
     * Gets the WebViewClient.
     *
     * @return the WebViewClient, or a default client if not yet set
     * @see #setWebViewClient
     */
    public WebViewClient getWebViewClient() {
        checkThread();
        return mProvider.getWebViewClient();
    }

    /**
     * Registers the interface to be used when content can not be handled by
     * the rendering engine, and should be downloaded instead. This will replace
     * the current handler.
     *
     * @param listener an implementation of DownloadListener
     */
    public void setDownloadListener(DownloadListener listener) {
        checkThread();
        mProvider.setDownloadListener(listener);
    }

    /**
     * Sets the chrome handler. This is an implementation of WebChromeClient for
     * use in handling JavaScript dialogs, favicons, titles, and the progress.
     * This will replace the current handler.
     *
     * @param client an implementation of WebChromeClient
     * @see #getWebChromeClient
     */
    public void setWebChromeClient(WebChromeClient client) {
        checkThread();
        mProvider.setWebChromeClient(client);
    }

    /**
     * Gets the chrome handler.
     *
     * @return the WebChromeClient, or {@code null} if not yet set
     * @see #setWebChromeClient
     */
    @Nullable
    public WebChromeClient getWebChromeClient() {
        checkThread();
        return mProvider.getWebChromeClient();
    }

    /**
     * Sets the Picture listener. This is an interface used to receive
     * notifications of a new Picture.
     *
     * @param listener an implementation of WebView.PictureListener
     * @deprecated This method is now obsolete.
     */
    @Deprecated
    public void setPictureListener(PictureListener listener) {
        checkThread();
        mProvider.setPictureListener(listener);
    }

    /**
     * Injects the supplied Java object into this WebView. The object is
     * injected into the JavaScript context of the main frame, using the
     * supplied name. This allows the Java object's methods to be
     * accessed from JavaScript. For applications targeted to API
     * level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     * and above, only public methods that are annotated with
     * {@link android.webkit.JavascriptInterface} can be accessed from JavaScript.
     * For applications targeted to API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN} or below,
     * all public methods (including the inherited ones) can be accessed, see the
     * important security note below for implications.
     * <p> Note that injected objects will not appear in JavaScript until the page is next
     * (re)loaded. JavaScript should be enabled before injecting the object. For example:
     * <pre>
     * class JsObject {
     *    {@literal @}JavascriptInterface
     *    public String toString() { return "injectedObject"; }
     * }
     * webview.getSettings().setJavaScriptEnabled(true);
     * webView.addJavascriptInterface(new JsObject(), "injectedObject");
     * webView.loadData("<!DOCTYPE html><title></title>", "text/html", null);
     * webView.loadUrl("javascript:alert(injectedObject.toString())");</pre>
     * <p>
     * <strong>IMPORTANT:</strong>
     * <ul>
     * <li> This method can be used to allow JavaScript to control the host
     * application. This is a powerful feature, but also presents a security
     * risk for apps targeting {@link android.os.Build.VERSION_CODES#JELLY_BEAN} or earlier.
     * Apps that target a version later than {@link android.os.Build.VERSION_CODES#JELLY_BEAN}
     * are still vulnerable if the app runs on a device running Android earlier than 4.2.
     * The most secure way to use this method is to target {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     * and to ensure the method is called only when running on Android 4.2 or later.
     * With these older versions, JavaScript could use reflection to access an
     * injected object's public fields. Use of this method in a WebView
     * containing untrusted content could allow an attacker to manipulate the
     * host application in unintended ways, executing Java code with the
     * permissions of the host application. Use extreme care when using this
     * method in a WebView which could contain untrusted content.</li>
     * <li> JavaScript interacts with Java object on a private, background
     * thread of this WebView. Care is therefore required to maintain thread
     * safety.
     * </li>
     * <li> The Java object's fields are not accessible.</li>
     * <li> For applications targeted to API level {@link android.os.Build.VERSION_CODES#LOLLIPOP}
     * and above, methods of injected Java objects are enumerable from
     * JavaScript.</li>
     * </ul>
     *
     * @param object the Java object to inject into this WebView's JavaScript
     *               context. {@code null} values are ignored.
     * @param name the name used to expose the object in JavaScript
     */
    public void addJavascriptInterface(Object object, String name) {
        checkThread();
        mProvider.addJavascriptInterface(object, name);
    }

    /**
     * Removes a previously injected Java object from this WebView. Note that
     * the removal will not be reflected in JavaScript until the page is next
     * (re)loaded. See {@link #addJavascriptInterface}.
     *
     * @param name the name used to expose the object in JavaScript
     */
    public void removeJavascriptInterface(@NonNull String name) {
        checkThread();
        mProvider.removeJavascriptInterface(name);
    }

    /**
     * Creates a message channel to communicate with JS and returns the message
     * ports that represent the endpoints of this message channel. The HTML5 message
     * channel functionality is described
     * <a href="https://html.spec.whatwg.org/multipage/comms.html#messagechannel">here
     * </a>
     *
     * <p>The returned message channels are entangled and already in started state.
     *
     * @return the two message ports that form the message channel.
     */
    public WebMessagePort[] createWebMessageChannel() {
        checkThread();
        return mProvider.createWebMessageChannel();
    }

    /**
     * Post a message to main frame. The embedded application can restrict the
     * messages to a certain target origin. See
     * <a href="https://html.spec.whatwg.org/multipage/comms.html#posting-messages">
     * HTML5 spec</a> for how target origin can be used.
     * <p>
     * A target origin can be set as a wildcard ("*"). However this is not recommended.
     * See the page above for security issues.
     * <p>
     * Content loaded via {@link #loadData(String,String,String)} will not have a
     * valid origin, and thus cannot be sent messages securely. If you need to send
     * messages using this function, you should use
     * {@link #loadDataWithBaseURL(String,String,String,String,String)} with a valid
     * HTTP or HTTPS {@code baseUrl} to define a valid origin that can be used for
     * messaging.
     *
     * @param message the WebMessage
     * @param targetOrigin the target origin.
     */
    public void postWebMessage(WebMessage message, Uri targetOrigin) {
        checkThread();
        mProvider.postMessageToMainFrame(message, targetOrigin);
    }

    /**
     * Gets the WebSettings object used to control the settings for this
     * WebView.
     *
     * @return a WebSettings object that can be used to control this WebView's
     *         settings
     */
    public WebSettings getSettings() {
        checkThread();
        return mProvider.getSettings();
    }

    /**
     * Enables debugging of web contents (HTML / CSS / JavaScript)
     * loaded into any WebViews of this application. This flag can be enabled
     * in order to facilitate debugging of web layouts and JavaScript
     * code running inside WebViews. Please refer to WebView documentation
     * for the debugging guide.
     *
     * The default is {@code false}.
     *
     * @param enabled whether to enable web contents debugging
     */
    public static void setWebContentsDebuggingEnabled(boolean enabled) {
        getFactory().getStatics().setWebContentsDebuggingEnabled(enabled);
    }

    /**
     * Gets the list of currently loaded plugins.
     *
     * @return the list of currently loaded plugins
     * @deprecated This was used for Gears, which has been deprecated.
     * @hide
     */
    @Deprecated
    public static synchronized PluginList getPluginList() {
        return new PluginList();
    }

    /**
     * Define the directory used to store WebView data for the current process.
     * The provided suffix will be used when constructing data and cache
     * directory paths. If this API is not called, no suffix will be used.
     * Each directory can be used by only one process in the application. If more
     * than one process in an app wishes to use WebView, only one process can use
     * the default directory, and other processes must call this API to define
     * a unique suffix.
     * <p>
     * This means that different processes in the same application cannot directly
     * share WebView-related data, since the data directories must be distinct.
     * Applications that use this API may have to explicitly pass data between
     * processes. For example, login cookies may have to be copied from one
     * process's cookie jar to the other using {@link CookieManager} if both
     * processes' WebViews are intended to be logged in.
     * <p>
     * Most applications should simply ensure that all components of the app
     * that rely on WebView are in the same process, to avoid needing multiple
     * data directories. The {@link #disableWebView} method can be used to ensure
     * that the other processes do not use WebView by accident in this case.
     * <p>
     * This API must be called before any instances of WebView are created in
     * this process and before any other methods in the android.webkit package
     * are called by this process.
     *
     * @param suffix The directory name suffix to be used for the current
     *               process. Must not contain a path separator.
     * @throws IllegalStateException if WebView has already been initialized
     *                               in the current process.
     * @throws IllegalArgumentException if the suffix contains a path separator.
     */
    public static void setDataDirectorySuffix(String suffix) {
        WebViewFactory.setDataDirectorySuffix(suffix);
    }

    /**
     * Indicate that the current process does not intend to use WebView, and
     * that an exception should be thrown if a WebView is created or any other
     * methods in the android.webkit package are used.
     * <p>
     * Applications with multiple processes may wish to call this in processes
     * that are not intended to use WebView to avoid accidentally incurring
     * the memory usage of initializing WebView in long-lived processes that
     * have no need for it, and to prevent potential data directory conflicts
     * (see {@link #setDataDirectorySuffix}).
     * <p>
     * For example, an audio player application with one process for its
     * activities and another process for its playback service may wish to call
     * this method in the playback service's {@link android.app.Service#onCreate}.
     *
     * @throws IllegalStateException if WebView has already been initialized
     *                               in the current process.
     */
    public static void disableWebView() {
        WebViewFactory.disableWebView();
    }


    /**
     * @deprecated This was used for Gears, which has been deprecated.
     * @hide
     */
    @Deprecated
    public void refreshPlugins(boolean reloadOpenPages) {
        checkThread();
    }

    /**
     * Puts this WebView into text selection mode. Do not rely on this
     * functionality; it will be deprecated in the future.
     *
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public void emulateShiftHeld() {
        checkThread();
    }

    /**
     * @deprecated WebView no longer needs to implement
     * ViewGroup.OnHierarchyChangeListener.  This method does nothing now.
     */
    @Override
    // Cannot add @hide as this can always be accessed via the interface.
    @Deprecated
    public void onChildViewAdded(View parent, View child) {}

    /**
     * @deprecated WebView no longer needs to implement
     * ViewGroup.OnHierarchyChangeListener.  This method does nothing now.
     */
    @Override
    // Cannot add @hide as this can always be accessed via the interface.
    @Deprecated
    public void onChildViewRemoved(View p, View child) {}

    /**
     * @deprecated WebView should not have implemented
     * ViewTreeObserver.OnGlobalFocusChangeListener. This method does nothing now.
     */
    @Override
    // Cannot add @hide as this can always be accessed via the interface.
    @Deprecated
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
    }

    /**
     * @deprecated Only the default case, {@code true}, will be supported in a future version.
     */
    @Deprecated
    public void setMapTrackballToArrowKeys(boolean setMap) {
        checkThread();
        mProvider.setMapTrackballToArrowKeys(setMap);
    }


    public void flingScroll(int vx, int vy) {
        checkThread();
        mProvider.flingScroll(vx, vy);
    }

    /**
     * Gets the zoom controls for this WebView, as a separate View. The caller
     * is responsible for inserting this View into the layout hierarchy.
     * <p/>
     * API level {@link android.os.Build.VERSION_CODES#CUPCAKE} introduced
     * built-in zoom mechanisms for the WebView, as opposed to these separate
     * zoom controls. The built-in mechanisms are preferred and can be enabled
     * using {@link WebSettings#setBuiltInZoomControls}.
     *
     * @deprecated the built-in zoom mechanisms are preferred
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN}
     */
    @Deprecated
    public View getZoomControls() {
        checkThread();
        return mProvider.getZoomControls();
    }

    /**
     * Gets whether this WebView can be zoomed in.
     *
     * @return {@code true} if this WebView can be zoomed in
     *
     * @deprecated This method is prone to inaccuracy due to race conditions
     * between the web rendering and UI threads; prefer
     * {@link WebViewClient#onScaleChanged}.
     */
    @Deprecated
    public boolean canZoomIn() {
        checkThread();
        return mProvider.canZoomIn();
    }

    /**
     * Gets whether this WebView can be zoomed out.
     *
     * @return {@code true} if this WebView can be zoomed out
     *
     * @deprecated This method is prone to inaccuracy due to race conditions
     * between the web rendering and UI threads; prefer
     * {@link WebViewClient#onScaleChanged}.
     */
    @Deprecated
    public boolean canZoomOut() {
        checkThread();
        return mProvider.canZoomOut();
    }

    /**
     * Performs a zoom operation in this WebView.
     *
     * @param zoomFactor the zoom factor to apply. The zoom factor will be clamped to the WebView's
     * zoom limits. This value must be in the range 0.01 to 100.0 inclusive.
     */
    public void zoomBy(float zoomFactor) {
        checkThread();
        if (zoomFactor < 0.01)
            throw new IllegalArgumentException("zoomFactor must be greater than 0.01.");
        if (zoomFactor > 100.0)
            throw new IllegalArgumentException("zoomFactor must be less than 100.");
        mProvider.zoomBy(zoomFactor);
    }

    /**
     * Performs zoom in in this WebView.
     *
     * @return {@code true} if zoom in succeeds, {@code false} if no zoom changes
     */
    public boolean zoomIn() {
        checkThread();
        return mProvider.zoomIn();
    }

    /**
     * Performs zoom out in this WebView.
     *
     * @return {@code true} if zoom out succeeds, {@code false} if no zoom changes
     */
    public boolean zoomOut() {
        checkThread();
        return mProvider.zoomOut();
    }

    /**
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public void debugDump() {
        checkThread();
    }

    /**
     * See {@link ViewDebug.HierarchyHandler#dumpViewHierarchyWithProperties(BufferedWriter, int)}
     * @hide
     */
    @Override
    public void dumpViewHierarchyWithProperties(BufferedWriter out, int level) {
        mProvider.dumpViewHierarchyWithProperties(out, level);
    }

    /**
     * See {@link ViewDebug.HierarchyHandler#findHierarchyView(String, int)}
     * @hide
     */
    @Override
    public View findHierarchyView(String className, int hashCode) {
        return mProvider.findHierarchyView(className, hashCode);
    }

    /** @hide */
    @IntDef(prefix = { "RENDERER_PRIORITY_" }, value = {
            RENDERER_PRIORITY_WAIVED,
            RENDERER_PRIORITY_BOUND,
            RENDERER_PRIORITY_IMPORTANT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RendererPriority {}

    /**
     * The renderer associated with this WebView is bound with
     * {@link Context#BIND_WAIVE_PRIORITY}. At this priority level
     * {@link WebView} renderers will be strong targets for out of memory
     * killing.
     *
     * Use with {@link #setRendererPriorityPolicy}.
     */
    public static final int RENDERER_PRIORITY_WAIVED = 0;
    /**
     * The renderer associated with this WebView is bound with
     * the default priority for services.
     *
     * Use with {@link #setRendererPriorityPolicy}.
     */
    public static final int RENDERER_PRIORITY_BOUND = 1;
    /**
     * The renderer associated with this WebView is bound with
     * {@link Context#BIND_IMPORTANT}.
     *
     * Use with {@link #setRendererPriorityPolicy}.
     */
    public static final int RENDERER_PRIORITY_IMPORTANT = 2;

    /**
     * Set the renderer priority policy for this {@link WebView}. The
     * priority policy will be used to determine whether an out of
     * process renderer should be considered to be a target for OOM
     * killing.
     *
     * Because a renderer can be associated with more than one
     * WebView, the final priority it is computed as the maximum of
     * any attached WebViews. When a WebView is destroyed it will
     * cease to be considerered when calculating the renderer
     * priority. Once no WebViews remain associated with the renderer,
     * the priority of the renderer will be reduced to
     * {@link #RENDERER_PRIORITY_WAIVED}.
     *
     * The default policy is to set the priority to
     * {@link #RENDERER_PRIORITY_IMPORTANT} regardless of visibility,
     * and this should not be changed unless the caller also handles
     * renderer crashes with
     * {@link WebViewClient#onRenderProcessGone}. Any other setting
     * will result in WebView renderers being killed by the system
     * more aggressively than the application.
     *
     * @param rendererRequestedPriority the minimum priority at which
     *        this WebView desires the renderer process to be bound.
     * @param waivedWhenNotVisible if {@code true}, this flag specifies that
     *        when this WebView is not visible, it will be treated as
     *        if it had requested a priority of
     *        {@link #RENDERER_PRIORITY_WAIVED}.
     */
    public void setRendererPriorityPolicy(
            @RendererPriority int rendererRequestedPriority,
            boolean waivedWhenNotVisible) {
        mProvider.setRendererPriorityPolicy(rendererRequestedPriority, waivedWhenNotVisible);
    }

    /**
     * Get the requested renderer priority for this WebView.
     *
     * @return the requested renderer priority policy.
     */
    @RendererPriority
    public int getRendererRequestedPriority() {
        return mProvider.getRendererRequestedPriority();
    }

    /**
     * Return whether this WebView requests a priority of
     * {@link #RENDERER_PRIORITY_WAIVED} when not visible.
     *
     * @return whether this WebView requests a priority of
     * {@link #RENDERER_PRIORITY_WAIVED} when not visible.
     */
    public boolean getRendererPriorityWaivedWhenNotVisible() {
        return mProvider.getRendererPriorityWaivedWhenNotVisible();
    }

    /**
     * Sets the {@link TextClassifier} for this WebView.
     */
    public void setTextClassifier(@Nullable TextClassifier textClassifier) {
        mProvider.setTextClassifier(textClassifier);
    }

    /**
     * Returns the {@link TextClassifier} used by this WebView.
     * If no TextClassifier has been set, this WebView uses the default set by the system.
     */
    @NonNull
    public TextClassifier getTextClassifier() {
        return mProvider.getTextClassifier();
    }

    /**
     * Returns the {@link ClassLoader} used to load internal WebView classes.
     * This method is meant for use by the WebView Support Library, there is no reason to use this
     * method otherwise.
     */
    @NonNull
    public static ClassLoader getWebViewClassLoader() {
        return getFactory().getWebViewClassLoader();
    }

    /**
     * Returns the {@link Looper} corresponding to the thread on which WebView calls must be made.
     */
    @NonNull
    public Looper getWebViewLooper() {
        return mWebViewThread;
    }

    //-------------------------------------------------------------------------
    // Interface for WebView providers
    //-------------------------------------------------------------------------

    /**
     * Gets the WebViewProvider. Used by providers to obtain the underlying
     * implementation, e.g. when the application responds to
     * WebViewClient.onCreateWindow() request.
     *
     * @hide WebViewProvider is not public API.
     */
    @SystemApi
    public WebViewProvider getWebViewProvider() {
        return mProvider;
    }

    /**
     * Callback interface, allows the provider implementation to access non-public methods
     * and fields, and make super-class calls in this WebView instance.
     * @hide Only for use by WebViewProvider implementations
     */
    @SystemApi
    public class PrivateAccess {
        // ---- Access to super-class methods ----
        public int super_getScrollBarStyle() {
            return WebView.super.getScrollBarStyle();
        }

        public void super_scrollTo(int scrollX, int scrollY) {
            WebView.super.scrollTo(scrollX, scrollY);
        }

        public void super_computeScroll() {
            WebView.super.computeScroll();
        }

        public boolean super_onHoverEvent(MotionEvent event) {
            return WebView.super.onHoverEvent(event);
        }

        public boolean super_performAccessibilityAction(int action, Bundle arguments) {
            return WebView.super.performAccessibilityActionInternal(action, arguments);
        }

        public boolean super_performLongClick() {
            return WebView.super.performLongClick();
        }

        public boolean super_setFrame(int left, int top, int right, int bottom) {
            return WebView.super.setFrame(left, top, right, bottom);
        }

        public boolean super_dispatchKeyEvent(KeyEvent event) {
            return WebView.super.dispatchKeyEvent(event);
        }

        public boolean super_onGenericMotionEvent(MotionEvent event) {
            return WebView.super.onGenericMotionEvent(event);
        }

        public boolean super_requestFocus(int direction, Rect previouslyFocusedRect) {
            return WebView.super.requestFocus(direction, previouslyFocusedRect);
        }

        public void super_setLayoutParams(ViewGroup.LayoutParams params) {
            WebView.super.setLayoutParams(params);
        }

        public void super_startActivityForResult(Intent intent, int requestCode) {
            WebView.super.startActivityForResult(intent, requestCode);
        }

        // ---- Access to non-public methods ----
        public void overScrollBy(int deltaX, int deltaY,
                int scrollX, int scrollY,
                int scrollRangeX, int scrollRangeY,
                int maxOverScrollX, int maxOverScrollY,
                boolean isTouchEvent) {
            WebView.this.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY,
                    maxOverScrollX, maxOverScrollY, isTouchEvent);
        }

        public void awakenScrollBars(int duration) {
            WebView.this.awakenScrollBars(duration);
        }

        public void awakenScrollBars(int duration, boolean invalidate) {
            WebView.this.awakenScrollBars(duration, invalidate);
        }

        public float getVerticalScrollFactor() {
            return WebView.this.getVerticalScrollFactor();
        }

        public float getHorizontalScrollFactor() {
            return WebView.this.getHorizontalScrollFactor();
        }

        public void setMeasuredDimension(int measuredWidth, int measuredHeight) {
            WebView.this.setMeasuredDimension(measuredWidth, measuredHeight);
        }

        public void onScrollChanged(int l, int t, int oldl, int oldt) {
            WebView.this.onScrollChanged(l, t, oldl, oldt);
        }

        public int getHorizontalScrollbarHeight() {
            return WebView.this.getHorizontalScrollbarHeight();
        }

        public void super_onDrawVerticalScrollBar(Canvas canvas, Drawable scrollBar,
                int l, int t, int r, int b) {
            WebView.super.onDrawVerticalScrollBar(canvas, scrollBar, l, t, r, b);
        }

        // ---- Access to (non-public) fields ----
        /** Raw setter for the scroll X value, without invoking onScrollChanged handlers etc. */
        public void setScrollXRaw(int scrollX) {
            WebView.this.mScrollX = scrollX;
        }

        /** Raw setter for the scroll Y value, without invoking onScrollChanged handlers etc. */
        public void setScrollYRaw(int scrollY) {
            WebView.this.mScrollY = scrollY;
        }

    }

    //-------------------------------------------------------------------------
    // Package-private internal stuff
    //-------------------------------------------------------------------------

    // Only used by android.webkit.FindActionModeCallback.
    void setFindDialogFindListener(FindListener listener) {
        checkThread();
        setupFindListenerIfNeeded();
        mFindListener.mFindDialogFindListener = listener;
    }

    // Only used by android.webkit.FindActionModeCallback.
    void notifyFindDialogDismissed() {
        checkThread();
        mProvider.notifyFindDialogDismissed();
    }

    //-------------------------------------------------------------------------
    // Private internal stuff
    //-------------------------------------------------------------------------

    private WebViewProvider mProvider;

    /**
     * In addition to the FindListener that the user may set via the WebView.setFindListener
     * API, FindActionModeCallback will register it's own FindListener. We keep them separate
     * via this class so that the two FindListeners can potentially exist at once.
     */
    private class FindListenerDistributor implements FindListener {
        private FindListener mFindDialogFindListener;
        private FindListener mUserFindListener;

        @Override
        public void onFindResultReceived(int activeMatchOrdinal, int numberOfMatches,
                boolean isDoneCounting) {
            if (mFindDialogFindListener != null) {
                mFindDialogFindListener.onFindResultReceived(activeMatchOrdinal, numberOfMatches,
                        isDoneCounting);
            }

            if (mUserFindListener != null) {
                mUserFindListener.onFindResultReceived(activeMatchOrdinal, numberOfMatches,
                        isDoneCounting);
            }
        }
    }
    private FindListenerDistributor mFindListener;

    private void setupFindListenerIfNeeded() {
        if (mFindListener == null) {
            mFindListener = new FindListenerDistributor();
            mProvider.setFindListener(mFindListener);
        }
    }

    private void ensureProviderCreated() {
        checkThread();
        if (mProvider == null) {
            // As this can get called during the base class constructor chain, pass the minimum
            // number of dependencies here; the rest are deferred to init().
            mProvider = getFactory().createWebView(this, new PrivateAccess());
        }
    }

    private static WebViewFactoryProvider getFactory() {
        return WebViewFactory.getProvider();
    }

    private final Looper mWebViewThread = Looper.myLooper();

    private void checkThread() {
        // Ignore mWebViewThread == null because this can be called during in the super class
        // constructor, before this class's own constructor has even started.
        if (mWebViewThread != null && Looper.myLooper() != mWebViewThread) {
            Throwable throwable = new Throwable(
                    "A WebView method was called on thread '" +
                    Thread.currentThread().getName() + "'. " +
                    "All WebView methods must be called on the same thread. " +
                    "(Expected Looper " + mWebViewThread + " called on " + Looper.myLooper() +
                    ", FYI main Looper is " + Looper.getMainLooper() + ")");
            Log.w(LOGTAG, Log.getStackTraceString(throwable));
            StrictMode.onWebViewMethodCalledOnWrongThread(throwable);

            if (sEnforceThreadChecking) {
                throw new RuntimeException(throwable);
            }
        }
    }

    //-------------------------------------------------------------------------
    // Override View methods
    //-------------------------------------------------------------------------

    // TODO: Add a test that enumerates all methods in ViewDelegte & ScrollDelegate, and ensures
    // there's a corresponding override (or better, caller) for each of them in here.

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mProvider.getViewDelegate().onAttachedToWindow();
    }

    /** @hide */
    @Override
    protected void onDetachedFromWindowInternal() {
        mProvider.getViewDelegate().onDetachedFromWindow();
        super.onDetachedFromWindowInternal();
    }

    /** @hide */
    @Override
    public void onMovedToDisplay(int displayId, Configuration config) {
        mProvider.getViewDelegate().onMovedToDisplay(displayId, config);
    }

    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        mProvider.getViewDelegate().setLayoutParams(params);
    }

    @Override
    public void setOverScrollMode(int mode) {
        super.setOverScrollMode(mode);
        // This method may be called in the constructor chain, before the WebView provider is
        // created.
        ensureProviderCreated();
        mProvider.getViewDelegate().setOverScrollMode(mode);
    }

    @Override
    public void setScrollBarStyle(int style) {
        mProvider.getViewDelegate().setScrollBarStyle(style);
        super.setScrollBarStyle(style);
    }

    @Override
    protected int computeHorizontalScrollRange() {
        return mProvider.getScrollDelegate().computeHorizontalScrollRange();
    }

    @Override
    protected int computeHorizontalScrollOffset() {
        return mProvider.getScrollDelegate().computeHorizontalScrollOffset();
    }

    @Override
    protected int computeVerticalScrollRange() {
        return mProvider.getScrollDelegate().computeVerticalScrollRange();
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return mProvider.getScrollDelegate().computeVerticalScrollOffset();
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return mProvider.getScrollDelegate().computeVerticalScrollExtent();
    }

    @Override
    public void computeScroll() {
        mProvider.getScrollDelegate().computeScroll();
    }

    @Override
    public boolean onHoverEvent(MotionEvent event) {
        return mProvider.getViewDelegate().onHoverEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return mProvider.getViewDelegate().onTouchEvent(event);
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        return mProvider.getViewDelegate().onGenericMotionEvent(event);
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        return mProvider.getViewDelegate().onTrackballEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        return mProvider.getViewDelegate().onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return mProvider.getViewDelegate().onKeyUp(keyCode, event);
    }

    @Override
    public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
        return mProvider.getViewDelegate().onKeyMultiple(keyCode, repeatCount, event);
    }

    /*
    TODO: These are not currently implemented in WebViewClassic, but it seems inconsistent not
    to be delegating them too.

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        return mProvider.getViewDelegate().onKeyPreIme(keyCode, event);
    }
    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        return mProvider.getViewDelegate().onKeyLongPress(keyCode, event);
    }
    @Override
    public boolean onKeyShortcut(int keyCode, KeyEvent event) {
        return mProvider.getViewDelegate().onKeyShortcut(keyCode, event);
    }
    */

    @Override
    public AccessibilityNodeProvider getAccessibilityNodeProvider() {
        AccessibilityNodeProvider provider =
                mProvider.getViewDelegate().getAccessibilityNodeProvider();
        return provider == null ? super.getAccessibilityNodeProvider() : provider;
    }

    @Deprecated
    @Override
    public boolean shouldDelayChildPressedState() {
        return mProvider.getViewDelegate().shouldDelayChildPressedState();
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return WebView.class.getName();
    }

    @Override
    public void onProvideVirtualStructure(ViewStructure structure) {
        mProvider.getViewDelegate().onProvideVirtualStructure(structure);
    }

    /**
     * {@inheritDoc}
     *
     * <p>The {@link ViewStructure} traditionally represents a {@link View}, while for web pages
     * it represent HTML nodes. Hence, it's necessary to "map" the HTML properties in a way that is
     * understood by the {@link android.service.autofill.AutofillService} implementations:
     *
     * <ol>
     *   <li>Only the HTML nodes inside a {@code FORM} are generated.
     *   <li>The source of the HTML is set using {@link ViewStructure#setWebDomain(String)} in the
     *   node representing the WebView.
     *   <li>If a web page has multiple {@code FORM}s, only the data for the current form is
     *   represented&mdash;if the user taps a field from another form, then the current autofill
     *   context is canceled (by calling {@link android.view.autofill.AutofillManager#cancel()} and
     *   a new context is created for that {@code FORM}.
     *   <li>Similarly, if the page has {@code IFRAME} nodes, they are not initially represented in
     *   the view structure until the user taps a field from a {@code FORM} inside the
     *   {@code IFRAME}, in which case it would be treated the same way as multiple forms described
     *   above, except that the {@link ViewStructure#setWebDomain(String) web domain} of the
     *   {@code FORM} contains the {@code src} attribute from the {@code IFRAME} node.
     *   <li>The W3C autofill field ({@code autocomplete} tag attribute) maps to
     *   {@link ViewStructure#setAutofillHints(String[])}.
     *   <li>If the view is editable, the {@link ViewStructure#setAutofillType(int)} and
     *   {@link ViewStructure#setAutofillValue(AutofillValue)} must be set.
     *   <li>The {@code placeholder} attribute maps to {@link ViewStructure#setHint(CharSequence)}.
     *   <li>Other HTML attributes can be represented through
     *   {@link ViewStructure#setHtmlInfo(android.view.ViewStructure.HtmlInfo)}.
     * </ol>
     *
     * <p>If the WebView implementation can determine that the value of a field was set statically
     * (for example, not through Javascript), it should also call
     * {@code structure.setDataIsSensitive(false)}.
     *
     * <p>For example, an HTML form with 2 fields for username and password:
     *
     * <pre class="prettyprint">
     *    &lt;label&gt;Username:&lt;/label&gt;
     *    &lt;input type="text" name="username" id="user" value="Type your username" autocomplete="username" placeholder="Email or username"&gt;
     *    &lt;label&gt;Password:&lt;/label&gt;
     *    &lt;input type="password" name="password" id="pass" autocomplete="current-password" placeholder="Password"&gt;
     * </pre>
     *
     * <p>Would map to:
     *
     * <pre class="prettyprint">
     *     int index = structure.addChildCount(2);
     *     ViewStructure username = structure.newChild(index);
     *     username.setAutofillId(structure.getAutofillId(), 1); // id 1 - first child
     *     username.setAutofillHints("username");
     *     username.setHtmlInfo(username.newHtmlInfoBuilder("input")
     *         .addAttribute("type", "text")
     *         .addAttribute("name", "username")
     *         .addAttribute("label", "Username:")
     *         .build());
     *     username.setHint("Email or username");
     *     username.setAutofillType(View.AUTOFILL_TYPE_TEXT);
     *     username.setAutofillValue(AutofillValue.forText("Type your username"));
     *     // Value of the field is not sensitive because it was created statically and not changed.
     *     username.setDataIsSensitive(false);
     *
     *     ViewStructure password = structure.newChild(index + 1);
     *     username.setAutofillId(structure, 2); // id 2 - second child
     *     password.setAutofillHints("current-password");
     *     password.setHtmlInfo(password.newHtmlInfoBuilder("input")
     *         .addAttribute("type", "password")
     *         .addAttribute("name", "password")
     *         .addAttribute("label", "Password:")
     *         .build());
     *     password.setHint("Password");
     *     password.setAutofillType(View.AUTOFILL_TYPE_TEXT);
     * </pre>
     */
    @Override
    public void onProvideAutofillVirtualStructure(ViewStructure structure, int flags) {
        mProvider.getViewDelegate().onProvideAutofillVirtualStructure(structure, flags);
    }

    @Override
    public void autofill(SparseArray<AutofillValue>values) {
        mProvider.getViewDelegate().autofill(values);
    }

    @Override
    public boolean isVisibleToUserForAutofill(int virtualId) {
        return mProvider.getViewDelegate().isVisibleToUserForAutofill(virtualId);
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityNodeInfoInternal(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfoInternal(info);
        mProvider.getViewDelegate().onInitializeAccessibilityNodeInfo(info);
    }

    /** @hide */
    @Override
    public void onInitializeAccessibilityEventInternal(AccessibilityEvent event) {
        super.onInitializeAccessibilityEventInternal(event);
        mProvider.getViewDelegate().onInitializeAccessibilityEvent(event);
    }

    /** @hide */
    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        return mProvider.getViewDelegate().performAccessibilityAction(action, arguments);
    }

    /** @hide */
    @Override
    protected void onDrawVerticalScrollBar(Canvas canvas, Drawable scrollBar,
            int l, int t, int r, int b) {
        mProvider.getViewDelegate().onDrawVerticalScrollBar(canvas, scrollBar, l, t, r, b);
    }

    @Override
    protected void onOverScrolled(int scrollX, int scrollY, boolean clampedX, boolean clampedY) {
        mProvider.getViewDelegate().onOverScrolled(scrollX, scrollY, clampedX, clampedY);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        mProvider.getViewDelegate().onWindowVisibilityChanged(visibility);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mProvider.getViewDelegate().onDraw(canvas);
    }

    @Override
    public boolean performLongClick() {
        return mProvider.getViewDelegate().performLongClick();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        mProvider.getViewDelegate().onConfigurationChanged(newConfig);
    }

    /**
     * Creates a new InputConnection for an InputMethod to interact with the WebView.
     * This is similar to {@link View#onCreateInputConnection} but note that WebView
     * calls InputConnection methods on a thread other than the UI thread.
     * If these methods are overridden, then the overriding methods should respect
     * thread restrictions when calling View methods or accessing data.
     */
    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        return mProvider.getViewDelegate().onCreateInputConnection(outAttrs);
    }

    @Override
    public boolean onDragEvent(DragEvent event) {
        return mProvider.getViewDelegate().onDragEvent(event);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // This method may be called in the constructor chain, before the WebView provider is
        // created.
        ensureProviderCreated();
        mProvider.getViewDelegate().onVisibilityChanged(changedView, visibility);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        mProvider.getViewDelegate().onWindowFocusChanged(hasWindowFocus);
        super.onWindowFocusChanged(hasWindowFocus);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
        mProvider.getViewDelegate().onFocusChanged(focused, direction, previouslyFocusedRect);
        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

    /** @hide */
    @Override
    protected boolean setFrame(int left, int top, int right, int bottom) {
        return mProvider.getViewDelegate().setFrame(left, top, right, bottom);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        mProvider.getViewDelegate().onSizeChanged(w, h, ow, oh);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        mProvider.getViewDelegate().onScrollChanged(l, t, oldl, oldt);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mProvider.getViewDelegate().dispatchKeyEvent(event);
    }

    @Override
    public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
        return mProvider.getViewDelegate().requestFocus(direction, previouslyFocusedRect);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        mProvider.getViewDelegate().onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rect, boolean immediate) {
        return mProvider.getViewDelegate().requestChildRectangleOnScreen(child, rect, immediate);
    }

    @Override
    public void setBackgroundColor(int color) {
        mProvider.getViewDelegate().setBackgroundColor(color);
    }

    @Override
    public void setLayerType(int layerType, Paint paint) {
        super.setLayerType(layerType, paint);
        mProvider.getViewDelegate().setLayerType(layerType, paint);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        mProvider.getViewDelegate().preDispatchDraw(canvas);
        super.dispatchDraw(canvas);
    }

    @Override
    public void onStartTemporaryDetach() {
        super.onStartTemporaryDetach();
        mProvider.getViewDelegate().onStartTemporaryDetach();
    }

    @Override
    public void onFinishTemporaryDetach() {
        super.onFinishTemporaryDetach();
        mProvider.getViewDelegate().onFinishTemporaryDetach();
    }

    @Override
    public Handler getHandler() {
        return mProvider.getViewDelegate().getHandler(super.getHandler());
    }

    @Override
    public View findFocus() {
        return mProvider.getViewDelegate().findFocus(super.findFocus());
    }

    /**
     * If WebView has already been loaded into the current process this method will return the
     * package that was used to load it. Otherwise, the package that would be used if the WebView
     * was loaded right now will be returned; this does not cause WebView to be loaded, so this
     * information may become outdated at any time.
     * The WebView package changes either when the current WebView package is updated, disabled, or
     * uninstalled. It can also be changed through a Developer Setting.
     * If the WebView package changes, any app process that has loaded WebView will be killed. The
     * next time the app starts and loads WebView it will use the new WebView package instead.
     * @return the current WebView package, or {@code null} if there is none.
     */
    @Nullable
    public static PackageInfo getCurrentWebViewPackage() {
        PackageInfo webviewPackage = WebViewFactory.getLoadedPackageInfo();
        if (webviewPackage != null) {
            return webviewPackage;
        }

        IWebViewUpdateService service = WebViewFactory.getUpdateService();
        if (service == null) {
            return null;
        }
        try {
            return service.getCurrentWebViewPackage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Receive the result from a previous call to {@link #startActivityForResult(Intent, int)}.
     *
     * @param requestCode The integer request code originally supplied to
     *                    startActivityForResult(), allowing you to identify who this
     *                    result came from.
     * @param resultCode The integer result code returned by the child activity
     *                   through its setResult().
     * @param data An Intent, which can return result data to the caller
     *               (various data can be attached to Intent "extras").
     * @hide
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mProvider.getViewDelegate().onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return mProvider.getViewDelegate().onCheckIsTextEditor();
    }

    /** @hide */
    @Override
    protected void encodeProperties(@NonNull ViewHierarchyEncoder encoder) {
        super.encodeProperties(encoder);

        checkThread();
        encoder.addProperty("webview:contentHeight", mProvider.getContentHeight());
        encoder.addProperty("webview:contentWidth", mProvider.getContentWidth());
        encoder.addProperty("webview:scale", mProvider.getScale());
        encoder.addProperty("webview:title", mProvider.getTitle());
        encoder.addProperty("webview:url", mProvider.getUrl());
        encoder.addProperty("webview:originalUrl", mProvider.getOriginalUrl());
    }
}
