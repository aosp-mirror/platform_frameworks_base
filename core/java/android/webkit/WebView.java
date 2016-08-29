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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.annotation.Widget;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.http.SslCertificate;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StrictMode;
import android.print.PrintDocumentAdapter;
import android.security.KeyChain;
import android.util.AttributeSet;
import android.util.Log;
import android.view.DragEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewStructure;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.view.ViewHierarchyEncoder;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.AbsoluteLayout;

import java.io.BufferedWriter;
import java.io.File;
import java.util.Map;

/**
 * <p>A View that displays web pages. This class is the basis upon which you
 * can roll your own web browser or simply display some online content within your Activity.
 * It uses the WebKit rendering engine to display
 * web pages and includes methods to navigate forward and backward
 * through a history, zoom in and out, perform text searches and more.</p>
 * <p>Note that, in order for your Activity to access the Internet and load web pages
 * in a WebView, you must add the {@code INTERNET} permissions to your
 * Android Manifest file:</p>
 * <pre>&lt;uses-permission android:name="android.permission.INTERNET" /></pre>
 *
 * <p>This must be a child of the <a
 * href="{@docRoot}guide/topics/manifest/manifest-element.html">{@code <manifest>}</a>
 * element.</p>
 *
 * <p>For more information, read
 * <a href="{@docRoot}guide/webapps/webview.html">Building Web Apps in WebView</a>.</p>
 *
 * <h3>Basic usage</h3>
 *
 * <p>By default, a WebView provides no browser-like widgets, does not
 * enable JavaScript and web page errors are ignored. If your goal is only
 * to display some HTML as a part of your UI, this is probably fine;
 * the user won't need to interact with the web page beyond reading
 * it, and the web page won't need to interact with the user. If you
 * actually want a full-blown web browser, then you probably want to
 * invoke the Browser application with a URL Intent rather than show it
 * with a WebView. For example:
 * <pre>
 * Uri uri = Uri.parse("http://www.example.com");
 * Intent intent = new Intent(Intent.ACTION_VIEW, uri);
 * startActivity(intent);
 * </pre>
 * <p>See {@link android.content.Intent} for more information.</p>
 *
 * <p>To provide a WebView in your own Activity, include a {@code <WebView>} in your layout,
 * or set the entire Activity window as a WebView during {@link
 * android.app.Activity#onCreate(Bundle) onCreate()}:</p>
 * <pre class="prettyprint">
 * WebView webview = new WebView(this);
 * setContentView(webview);
 * </pre>
 *
 * <p>Then load the desired web page:</p>
 * <pre>
 * // Simplest usage: note that an exception will NOT be thrown
 * // if there is an error loading this page (see below).
 * webview.loadUrl("http://slashdot.org/");
 *
 * // OR, you can also load from an HTML string:
 * String summary = "&lt;html>&lt;body>You scored &lt;b>192&lt;/b> points.&lt;/body>&lt;/html>";
 * webview.loadData(summary, "text/html", null);
 * // ... although note that there are restrictions on what this HTML can do.
 * // See the JavaDocs for {@link #loadData(String,String,String) loadData()} and {@link
 * #loadDataWithBaseURL(String,String,String,String,String) loadDataWithBaseURL()} for more info.
 * </pre>
 *
 * <p>A WebView has several customization points where you can add your
 * own behavior. These are:</p>
 *
 * <ul>
 *   <li>Creating and setting a {@link android.webkit.WebChromeClient} subclass.
 *       This class is called when something that might impact a
 *       browser UI happens, for instance, progress updates and
 *       JavaScript alerts are sent here (see <a
 * href="{@docRoot}guide/developing/debug-tasks.html#DebuggingWebPages">Debugging Tasks</a>).
 *   </li>
 *   <li>Creating and setting a {@link android.webkit.WebViewClient} subclass.
 *       It will be called when things happen that impact the
 *       rendering of the content, eg, errors or form submissions. You
 *       can also intercept URL loading here (via {@link
 * android.webkit.WebViewClient#shouldOverrideUrlLoading(WebView,String)
 * shouldOverrideUrlLoading()}).</li>
 *   <li>Modifying the {@link android.webkit.WebSettings}, such as
 * enabling JavaScript with {@link android.webkit.WebSettings#setJavaScriptEnabled(boolean)
 * setJavaScriptEnabled()}. </li>
 *   <li>Injecting Java objects into the WebView using the
 *       {@link android.webkit.WebView#addJavascriptInterface} method. This
 *       method allows you to inject Java objects into a page's JavaScript
 *       context, so that they can be accessed by JavaScript in the page.</li>
 * </ul>
 *
 * <p>Here's a more complicated example, showing error handling,
 *    settings, and progress notification:</p>
 *
 * <pre class="prettyprint">
 * // Let's display the progress in the activity title bar, like the
 * // browser app does.
 * getWindow().requestFeature(Window.FEATURE_PROGRESS);
 *
 * webview.getSettings().setJavaScriptEnabled(true);
 *
 * final Activity activity = this;
 * webview.setWebChromeClient(new WebChromeClient() {
 *   public void onProgressChanged(WebView view, int progress) {
 *     // Activities and WebViews measure progress with different scales.
 *     // The progress meter will automatically disappear when we reach 100%
 *     activity.setProgress(progress * 1000);
 *   }
 * });
 * webview.setWebViewClient(new WebViewClient() {
 *   public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
 *     Toast.makeText(activity, "Oh no! " + description, Toast.LENGTH_SHORT).show();
 *   }
 * });
 *
 * webview.loadUrl("http://developer.android.com/");
 * </pre>
 *
 * <h3>Zoom</h3>
 *
 * <p>To enable the built-in zoom, set
 * {@link #getSettings() WebSettings}.{@link WebSettings#setBuiltInZoomControls(boolean)}
 * (introduced in API level {@link android.os.Build.VERSION_CODES#CUPCAKE}).</p>
 * <p>NOTE: Using zoom if either the height or width is set to
 * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} may lead to undefined behavior
 * and should be avoided.</p>
 *
 * <h3>Cookie and window management</h3>
 *
 * <p>For obvious security reasons, your application has its own
 * cache, cookie store etc.&mdash;it does not share the Browser
 * application's data.
 * </p>
 *
 * <p>By default, requests by the HTML to open new windows are
 * ignored. This is true whether they be opened by JavaScript or by
 * the target attribute on a link. You can customize your
 * {@link WebChromeClient} to provide your own behaviour for opening multiple windows,
 * and render them in whatever manner you want.</p>
 *
 * <p>The standard behavior for an Activity is to be destroyed and
 * recreated when the device orientation or any other configuration changes. This will cause
 * the WebView to reload the current page. If you don't want that, you
 * can set your Activity to handle the {@code orientation} and {@code keyboardHidden}
 * changes, and then just leave the WebView alone. It'll automatically
 * re-orient itself as appropriate. Read <a
 * href="{@docRoot}guide/topics/resources/runtime-changes.html">Handling Runtime Changes</a> for
 * more information about how to handle configuration changes during runtime.</p>
 *
 *
 * <h3>Building web pages to support different screen densities</h3>
 *
 * <p>The screen density of a device is based on the screen resolution. A screen with low density
 * has fewer available pixels per inch, where a screen with high density
 * has more &mdash; sometimes significantly more &mdash; pixels per inch. The density of a
 * screen is important because, other things being equal, a UI element (such as a button) whose
 * height and width are defined in terms of screen pixels will appear larger on the lower density
 * screen and smaller on the higher density screen.
 * For simplicity, Android collapses all actual screen densities into three generalized densities:
 * high, medium, and low.</p>
 * <p>By default, WebView scales a web page so that it is drawn at a size that matches the default
 * appearance on a medium density screen. So, it applies 1.5x scaling on a high density screen
 * (because its pixels are smaller) and 0.75x scaling on a low density screen (because its pixels
 * are bigger).
 * Starting with API level {@link android.os.Build.VERSION_CODES#ECLAIR}, WebView supports DOM, CSS,
 * and meta tag features to help you (as a web developer) target screens with different screen
 * densities.</p>
 * <p>Here's a summary of the features you can use to handle different screen densities:</p>
 * <ul>
 * <li>The {@code window.devicePixelRatio} DOM property. The value of this property specifies the
 * default scaling factor used for the current device. For example, if the value of {@code
 * window.devicePixelRatio} is "1.0", then the device is considered a medium density (mdpi) device
 * and default scaling is not applied to the web page; if the value is "1.5", then the device is
 * considered a high density device (hdpi) and the page content is scaled 1.5x; if the
 * value is "0.75", then the device is considered a low density device (ldpi) and the content is
 * scaled 0.75x.</li>
 * <li>The {@code -webkit-device-pixel-ratio} CSS media query. Use this to specify the screen
 * densities for which this style sheet is to be used. The corresponding value should be either
 * "0.75", "1", or "1.5", to indicate that the styles are for devices with low density, medium
 * density, or high density screens, respectively. For example:
 * <pre>
 * &lt;link rel="stylesheet" media="screen and (-webkit-device-pixel-ratio:1.5)" href="hdpi.css" /&gt;</pre>
 * <p>The {@code hdpi.css} stylesheet is only used for devices with a screen pixel ration of 1.5,
 * which is the high density pixel ratio.</p>
 * </li>
 * </ul>
 *
 * <h3>HTML5 Video support</h3>
 *
 * <p>In order to support inline HTML5 video in your application you need to have hardware
 * acceleration turned on.
 * </p>
 *
 * <h3>Full screen support</h3>
 *
 * <p>In order to support full screen &mdash; for video or other HTML content &mdash; you need to set a
 * {@link android.webkit.WebChromeClient} and implement both
 * {@link WebChromeClient#onShowCustomView(View, WebChromeClient.CustomViewCallback)}
 * and {@link WebChromeClient#onHideCustomView()}. If the implementation of either of these two methods is
 * missing then the web contents will not be allowed to enter full screen. Optionally you can implement
 * {@link WebChromeClient#getVideoLoadingProgressView()} to customize the View displayed whilst a video
 * is loading.
 * </p>
 *
 * <h3>HTML5 Geolocation API support</h3>
 *
 * <p>For applications targeting Android N and later releases
 * (API level > {@link android.os.Build.VERSION_CODES#M}) the geolocation api is only supported on
 * secure origins such as https. For such applications requests to geolocation api on non-secure
 * origins are automatically denied without invoking the corresponding
 * {@link WebChromeClient#onGeolocationPermissionsShowPrompt(String, GeolocationPermissions.Callback)}
 * method.
 * </p>
 *
 * <h3>Layout size</h3>
 * <p>
 * It is recommended to set the WebView layout height to a fixed value or to
 * {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT} instead of using
 * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}.
 * When using {@link android.view.ViewGroup.LayoutParams#MATCH_PARENT}
 * for the height none of the WebView's parents should use a
 * {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} layout height since that could result in
 * incorrect sizing of the views.
 * </p>
 *
 * <p>Setting the WebView's height to {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT}
 * enables the following behaviors:
 * <ul>
 * <li>The HTML body layout height is set to a fixed value. This means that elements with a height
 * relative to the HTML body may not be sized correctly. </li>
 * <li>For applications targetting {@link android.os.Build.VERSION_CODES#KITKAT} and earlier SDKs the
 * HTML viewport meta tag will be ignored in order to preserve backwards compatibility. </li>
 * </ul>
 * </p>
 *
 * <p>
 * Using a layout width of {@link android.view.ViewGroup.LayoutParams#WRAP_CONTENT} is not
 * supported. If such a width is used the WebView will attempt to use the width of the parent
 * instead.
 * </p>
 *
 * <h3>Metrics</h3>
 *
 * <p>
 * WebView may upload anonymous diagnostic data to Google when the user has consented. This data
 * helps Google improve WebView. Data is collected on a per-app basis for each app which has
 * instantiated a WebView. An individual app can opt out of this feature by putting the following
 * tag in its manifest:
 * <pre>
 * &lt;meta-data android:name="android.webkit.WebView.MetricsOptOut"
 *            android:value="true" /&gt;
 * </pre>
 * </p>
 * <p>
 * Data will only be uploaded for a given app if the user has consented AND the app has not opted
 * out.
 * </p>
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

    /**
     * Broadcast Action: Indicates the data reduction proxy setting changed.
     * Sent by the settings app when user changes the data reduction proxy value. This intent will
     * always stay as a hidden API.
     * @hide
     */
    @SystemApi
    public static final String DATA_REDUCTION_PROXY_SETTING_CHANGED =
            "android.webkit.DATA_REDUCTION_PROXY_SETTING_CHANGED";

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
         *                       isDoneCounting is true.
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
         *     will always receive a null Picture.
         * @deprecated Deprecated due to internal changes.
         */
        @Deprecated
        public void onNewPicture(WebView view, Picture picture);
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
         * {@link WebView#getHitTestResult()} for details. May either be null
         * or contain extra information about this result.
         *
         * @return additional type-dependant information about the result
         */
        public String getExtra() {
            return mExtra;
        }
    }

    /**
     * Constructs a new WebView with a Context object.
     *
     * @param context a Context object used to access application assets
     */
    public WebView(Context context) {
        this(context, null);
    }

    /**
     * Constructs a new WebView with layout parameters.
     *
     * @param context a Context object used to access application assets
     * @param attrs an AttributeSet passed to our parent
     */
    public WebView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.webViewStyle);
    }

    /**
     * Constructs a new WebView with layout parameters and a default style.
     *
     * @param context a Context object used to access application assets
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
     * @param context a Context object used to access application assets
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
     * @param context a Context object used to access application assets
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
     * of custom Javscript interfaces to be added to this WebView at initialization
     * time. This guarantees that these interfaces will be available when the JS
     * context is initialized.
     *
     * @param context a Context object used to access application assets
     * @param attrs an AttributeSet passed to our parent
     * @param defStyleAttr an attribute in the current theme that contains a
     *        reference to a style resource that supplies default values for
     *        the view. Can be 0 to not look for defaults.
     * @param javaScriptInterfaces a Map of interface names, as keys, and
     *                             object implementing those interfaces, as
     *                             values
     * @param privateBrowsing whether this WebView will be initialized in
     *                        private mode
     * @hide This is used internally by dumprendertree, as it requires the javaScript interfaces to
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
        if (context == null) {
            throw new IllegalArgumentException("Invalid context argument");
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
     * @param overlay true if horizontal scrollbar should have overlay style
     */
    @Deprecated
    public void setHorizontalScrollbarOverlay(boolean overlay) {
    }

    /**
     * Specifies whether the vertical scrollbar has overlay style.
     *
     * @deprecated This method has no effect.
     * @param overlay true if vertical scrollbar should have overlay style
     */
    @Deprecated
    public void setVerticalScrollbarOverlay(boolean overlay) {
    }

    /**
     * Gets whether horizontal scrollbar has overlay style.
     *
     * @deprecated This method is now obsolete.
     * @return true
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
     * @return false
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
    public int getVisibleTitleHeight() {
        checkThread();
        return mProvider.getVisibleTitleHeight();
    }

    /**
     * Gets the SSL certificate for the main top-level page or null if there is
     * no certificate (the site is not secure).
     *
     * @return the SSL certificate for the main top-level page
     */
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
     * used by the Webview to autocomplete username and password fields in web
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
     * <p>
     * To use HTTP authentication, the embedder application has to implement
     * {@link WebViewClient#onReceivedHttpAuthRequest}, and call {@link HttpAuthHandler#proceed}
     * with the correct username and password.
     * <p>
     * The embedder app can get the username and password any way it chooses, and does not have to
     * use {@link WebViewDatabase}.
     * <p>
     * Notes:
     * <li>
     * {@link WebViewDatabase} is provided only as a convenience to store and retrieve http
     * authentication credentials. WebView does not read from it during HTTP authentication.
     * </li>
     * <li>
     * WebView does not provide a special mechanism to clear HTTP authentication credentials for
     * implementing client logout. The client logout mechanism should be implemented by the Web site
     * designer (such as server sending a HTTP 401 for invalidating credentials).
     * </li>
     *
     * @param host the host to which the credentials apply
     * @param realm the realm to which the credentials apply
     * @param username the username
     * @param password the password
     * @see #getHttpAuthUsernamePassword
     * @see WebViewDatabase#hasHttpAuthUsernamePassword
     * @see WebViewDatabase#clearHttpAuthUsernamePassword
     */
    public void setHttpAuthUsernamePassword(String host, String realm,
            String username, String password) {
        checkThread();
        mProvider.setHttpAuthUsernamePassword(host, realm, username, password);
    }

    /**
     * Retrieves HTTP authentication credentials for a given host and realm from the {@link
     * WebViewDatabase} instance.
     *
     * @param host the host to which the credentials apply
     * @param realm the realm to which the credentials apply
     * @return the credentials as a String array, if found. The first element
     *         is the username and the second element is the password. Null if
     *         no credentials are found.
     * @see #setHttpAuthUsernamePassword
     * @see WebViewDatabase#hasHttpAuthUsernamePassword
     * @see WebViewDatabase#clearHttpAuthUsernamePassword
     */
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
     * @return the same copy of the back/forward list used to save the state. If
     *         saveState fails, the returned list will be null.
     */
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
     * @return true if the picture was successfully saved
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
     * @return true if the picture was successfully restored
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
     * @return the restored back/forward list or null if restoreState failed
     */
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
     *            values may be overriden by this WebView's defaults.
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
     * The encoding parameter specifies whether the data is base64 or URL
     * encoded. If the data is base64 encoded, the value of the encoding
     * parameter must be 'base64'. For all other values of the parameter,
     * including null, it is assumed that the data uses ASCII encoding for
     * octets inside the range of safe URL characters and use the standard %xx
     * hex encoding of URLs for octets outside that range. For example, '#',
     * '%', '\', '?' should be replaced by %23, %25, %27, %3f respectively.
     * <p>
     * The 'data' scheme URL formed by this method uses the default US-ASCII
     * charset. If you need need to set a different charset, you should form a
     * 'data' scheme URL which explicitly specifies a charset parameter in the
     * mediatype portion of the URL and call {@link #loadUrl(String)} instead.
     * Note that the charset obtained from the mediatype portion of a data URL
     * always overrides that specified in the HTML or XML document itself.
     *
     * @param data a String of data in the given encoding
     * @param mimeType the MIME type of the data, e.g. 'text/html'
     * @param encoding the encoding of the data
     */
    public void loadData(String data, String mimeType, String encoding) {
        checkThread();
        mProvider.loadData(data, mimeType, encoding);
    }

    /**
     * Loads the given data into this WebView, using baseUrl as the base URL for
     * the content. The base URL is used both to resolve relative URLs and when
     * applying JavaScript's same origin policy. The historyUrl is used for the
     * history entry.
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
     *
     * @param baseUrl the URL to use as the page's base URL. If null defaults to
     *                'about:blank'.
     * @param data a String of data in the given encoding
     * @param mimeType the MIMEType of the data, e.g. 'text/html'. If null,
     *                 defaults to 'text/html'.
     * @param encoding the encoding of the data
     * @param historyUrl the URL to use as the history entry. If null defaults
     *                   to 'about:blank'. If non-null, this must be a valid URL.
     */
    public void loadDataWithBaseURL(String baseUrl, String data,
            String mimeType, String encoding, String historyUrl) {
        checkThread();
        mProvider.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl);
    }

    /**
     * Asynchronously evaluates JavaScript in the context of the currently displayed page.
     * If non-null, |resultCallback| will be invoked with any result returned from that
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
     *                       May be null if no notificaion of the result is required.
     */
    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
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
     * @param autoname if false, takes basename to be a file. If true, basename
     *                 is assumed to be a directory in which a filename will be
     *                 chosen according to the URL of the current page.
     * @param callback called after the web archive has been saved. The
     *                 parameter for onReceiveValue will either be the filename
     *                 under which the file was saved, or null if saving the
     *                 file failed.
     */
    public void saveWebArchive(String basename, boolean autoname, ValueCallback<String> callback) {
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
     * @return true iff this WebView has a back history item
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
     * @return true iff this Webview has a forward history item
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
     * @param top true to jump to the top of the page
     * @return true if the page was scrolled
     */
    public boolean pageUp(boolean top) {
        checkThread();
        return mProvider.pageUp(top);
    }

    /**
     * Scrolls the contents of this WebView down by half the page size.
     *
     * @param bottom true to jump to bottom of page
     * @return true if the page was scrolled
     */
    public boolean pageDown(boolean bottom) {
        checkThread();
        return mProvider.pageDown(bottom);
    }

    /**
     * Posts a {@link VisualStateCallback}, which will be called when
     * the current state of the WebView is ready to be drawn.
     *
     * <p>Because updates to the the DOM are processed asynchronously, updates to the DOM may not
     * immediately be reflected visually by subsequent {@link WebView#onDraw} invocations. The
     * {@link VisualStateCallback} provides a mechanism to notify the caller when the contents of
     * the DOM at the current time are ready to be drawn the next time the {@link WebView}
     * draws.</p>
     *
     * <p>The next draw after the callback completes is guaranteed to reflect all the updates to the
     * DOM up to the the point at which the {@link VisualStateCallback} was posted, but it may also
     * contain updates applied after the callback was posted.</p>
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
     * </ul></p>
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
     * </ul></p>
     *
     * <p>When using this API it is also recommended to enable pre-rasterization if the {@link
     * WebView} is offscreen to avoid flickering. See {@link WebSettings#setOffscreenPreRaster} for
     * more details and do consider its caveats.</p>
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
     * Creates a PrintDocumentAdapter that provides the content of this Webview for printing.
     *
     * The adapter works by converting the Webview contents to a PDF stream. The Webview cannot
     * be drawn during the conversion process - any such draws are undefined. It is recommended
     * to use a dedicated off screen Webview for the printing. If necessary, an application may
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
     * the zoom is set to 100%. For wide content, the behavor
     * depends on the state of {@link WebSettings#getLoadWithOverviewMode()}.
     * If its value is true, the content will be zoomed out to be fit
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
     * If hrefMsg is null, this method returns immediately and does not
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
    public void requestFocusNodeHref(Message hrefMsg) {
        checkThread();
        mProvider.requestFocusNodeHref(hrefMsg);
    }

    /**
     * Requests the URL of the image last touched by the user. msg will be sent
     * to its target with a String representing the URL as its object.
     *
     * @param msg the message to be dispatched with the result of the request
     *            as the data member with "url" as key. The result can be null.
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
     * Calling onResume() sets the paused state back to false.
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
     * @param includeDiskFiles if false, only the RAM cache is cleared
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
     * to proceeding/cancelling client cert requests. Note that Webview
     * automatically clears these preferences when it receives a
     * {@link KeyChain#ACTION_STORAGE_CHANGED} intent. The preferences are
     * shared by all the webviews that are created by the embedder application.
     *
     * @param onCleared  A runnable to be invoked when client certs are cleared.
     *                   The embedder can pass null if not interested in the
     *                   callback. The runnable will be called in UI thread.
     */
    public static void clearClientCertPreferences(Runnable onCleared) {
        getFactory().getStatics().clearClientCertPreferences(onCleared);
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
     * @return the number of occurances of the String "find" that were found
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
     * @param showIme if true, show the IME, assuming the user will begin typing.
     *                If false and text is non-null, perform a find all.
     * @return true if the find dialog is shown, false otherwise
     * @deprecated This method does not work reliably on all Android versions;
     *             implementing a custom find dialog using WebView.findAllAsync()
     *             provides a more robust solution.
     */
    @Deprecated
    public boolean showFindDialog(String text, boolean showIme) {
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
     * @return the address, or if no address is found, null
     */
    public static String findAddress(String addr) {
        // TODO: Rewrite this in Java so it is not needed to start up chromium
        // Could also be deprecated
        return getFactory().getStatics().findAddress(addr);
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
     */
    public void setWebViewClient(WebViewClient client) {
        checkThread();
        mProvider.setWebViewClient(client);
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
     */
    public void setWebChromeClient(WebChromeClient client) {
        checkThread();
        mProvider.setWebChromeClient(client);
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
     * <p> Note that injected objects will not
     * appear in JavaScript until the page is next (re)loaded. For example:
     * <pre>
     * class JsObject {
     *    {@literal @}JavascriptInterface
     *    public String toString() { return "injectedObject"; }
     * }
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
     *               context. Null values are ignored.
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
    public void removeJavascriptInterface(String name) {
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
     * <p>The returned message channels are entangled and already in started state.</p>
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
     *
     * @param message the WebMessage
     * @param targetOrigin the target origin. This is the origin of the page
     *          that is intended to receive the message. For best security
     *          practices, the user should not specify a wildcard (*) when
     *          specifying the origin.
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
     * The default is false.
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
     * @deprecated Only the default case, true, will be supported in a future version.
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
     * @return true if this WebView can be zoomed in
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
     * @return true if this WebView can be zoomed out
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
     * @param zoomFactor the zoom factor to apply. The zoom factor will be clamped to the Webview's
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
     * @return true if zoom in succeeds, false if no zoom changes
     */
    public boolean zoomIn() {
        checkThread();
        return mProvider.zoomIn();
    }

    /**
     * Performs zoom out in this WebView.
     *
     * @return true if zoom out succeeds, false if no zoom changes
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

    //-------------------------------------------------------------------------
    // Interface for WebView providers
    //-------------------------------------------------------------------------

    /**
     * Gets the WebViewProvider. Used by providers to obtain the underlying
     * implementation, e.g. when the appliction responds to
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

    private static synchronized WebViewFactoryProvider getFactory() {
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

    public CharSequence getAccessibilityClassName() {
        return WebView.class.getName();
    }

    @Override
    public void onProvideVirtualStructure(ViewStructure structure) {
        mProvider.getViewDelegate().onProvideVirtualStructure(structure);
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
