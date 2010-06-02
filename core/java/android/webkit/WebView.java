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

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnCancelListener;
import android.content.pm.PackageManager;
import android.database.DataSetObserver;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Picture;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.net.http.SslCertificate;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Checkin;
import android.text.IClipboard;
import android.text.Selection;
import android.text.Spannable;
import android.util.AttributeSet;
import android.util.EventLog;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.SoundEffectConstants;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AlphaAnimation;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebTextView.AutoCompleteAdapter;
import android.webkit.WebViewCore.EventHub;
import android.widget.AbsoluteLayout;
import android.widget.Adapter;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.Scroller;
import android.widget.Toast;
import android.widget.ZoomButtonsController;
import android.widget.ZoomControls;
import android.widget.AdapterView.OnItemClickListener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <p>A View that displays web pages. This class is the basis upon which you
 * can roll your own web browser or simply display some online content within your Activity.
 * It uses the WebKit rendering engine to display
 * web pages and includes methods to navigate forward and backward
 * through a history, zoom in and out, perform text searches and more.</p>
 * <p>To enable the built-in zoom, set
 * {@link #getSettings() WebSettings}.{@link WebSettings#setBuiltInZoomControls(boolean)}
 * (introduced in API version 3).
 * <p>Note that, in order for your Activity to access the Internet and load web pages
 * in a WebView, you must add the <var>INTERNET</var> permissions to your
 * Android Manifest file:</p>
 * <pre>&lt;uses-permission android:name="android.permission.INTERNET" /></pre>
 *
 * <p>This must be a child of the <code>&lt;manifest></code> element.</p>
 *
 * <h3>Basic usage</h3>
 *
 * <p>By default, a WebView provides no browser-like widgets, does not
 * enable JavaScript and errors will be ignored. If your goal is only
 * to display some HTML as a part of your UI, this is probably fine;
 * the user won't need to interact with the web page beyond reading
 * it, and the web page won't need to interact with the user. If you
 * actually want a fully blown web browser, then you probably want to
 * invoke the Browser application with your URL rather than show it
 * with a WebView. See {@link android.content.Intent} for more information.</p>
 *
 * <pre class="prettyprint">
 * WebView webview = new WebView(this);
 * setContentView(webview);
 *
 * // Simplest usage: note that an exception will NOT be thrown
 * // if there is an error loading this page (see below).
 * webview.loadUrl("http://slashdot.org/");
 *
 * // Of course you can also load from any string:
 * String summary = "&lt;html>&lt;body>You scored &lt;b>192</b> points.&lt;/body>&lt;/html>";
 * webview.loadData(summary, "text/html", "utf-8");
 * // ... although note that there are restrictions on what this HTML can do.
 * // See the JavaDocs for loadData and loadDataWithBaseUrl for more info.
 * </pre>
 *
 * <p>A WebView has several customization points where you can add your
 * own behavior. These are:</p>
 *
 * <ul>
 *   <li>Creating and setting a {@link android.webkit.WebChromeClient} subclass.
 *       This class is called when something that might impact a
 *       browser UI happens, for instance, progress updates and
 *       JavaScript alerts are sent here.
 *   </li>
 *   <li>Creating and setting a {@link android.webkit.WebViewClient} subclass.
 *       It will be called when things happen that impact the
 *       rendering of the content, eg, errors or form submissions. You
 *       can also intercept URL loading here.</li>
 *   <li>Via the {@link android.webkit.WebSettings} class, which contains
 *       miscellaneous configuration. </li>
 *   <li>With the {@link android.webkit.WebView#addJavascriptInterface} method.
 *       This lets you bind Java objects into the WebView so they can be
 *       controlled from the web pages JavaScript.</li>
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
 * webview.loadUrl("http://slashdot.org/");
 * </pre>
 *
 * <h3>Cookie and window management</h3>
 *
 * <p>For obvious security reasons, your application has its own
 * cache, cookie store etc - it does not share the Browser
 * applications data. Cookies are managed on a separate thread, so
 * operations like index building don't block the UI
 * thread. Follow the instructions in {@link android.webkit.CookieSyncManager}
 * if you want to use cookies in your application.
 * </p>
 *
 * <p>By default, requests by the HTML to open new windows are
 * ignored. This is true whether they be opened by JavaScript or by
 * the target attribute on a link. You can customize your
 * WebChromeClient to provide your own behaviour for opening multiple windows,
 * and render them in whatever manner you want.</p>
 *
 * <p>Standard behavior for an Activity is to be destroyed and
 * recreated when the devices orientation is changed. This will cause
 * the WebView to reload the current page. If you don't want that, you
 * can set your Activity to handle the orientation and keyboardHidden
 * changes, and then just leave the WebView alone. It'll automatically
 * re-orient itself as appropriate.</p>
 */
public class WebView extends AbsoluteLayout
        implements ViewTreeObserver.OnGlobalFocusChangeListener,
        ViewGroup.OnHierarchyChangeListener {

    // enable debug output for drag trackers
    private static final boolean DEBUG_DRAG_TRACKER = false;
    // if AUTO_REDRAW_HACK is true, then the CALL key will toggle redrawing
    // the screen all-the-time. Good for profiling our drawing code
    static private final boolean AUTO_REDRAW_HACK = false;
    // true means redraw the screen all-the-time. Only with AUTO_REDRAW_HACK
    private boolean mAutoRedraw;

    static final String LOGTAG = "webview";

    private static class ExtendedZoomControls extends FrameLayout {
        public ExtendedZoomControls(Context context, AttributeSet attrs) {
            super(context, attrs);
            LayoutInflater inflater = (LayoutInflater)
                    context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            inflater.inflate(com.android.internal.R.layout.zoom_magnify, this, true);
            mPlusMinusZoomControls = (ZoomControls) findViewById(
                    com.android.internal.R.id.zoomControls);
            findViewById(com.android.internal.R.id.zoomMagnify).setVisibility(
                    View.GONE);
        }

        public void show(boolean showZoom, boolean canZoomOut) {
            mPlusMinusZoomControls.setVisibility(
                    showZoom ? View.VISIBLE : View.GONE);
            fade(View.VISIBLE, 0.0f, 1.0f);
        }

        public void hide() {
            fade(View.GONE, 1.0f, 0.0f);
        }

        private void fade(int visibility, float startAlpha, float endAlpha) {
            AlphaAnimation anim = new AlphaAnimation(startAlpha, endAlpha);
            anim.setDuration(500);
            startAnimation(anim);
            setVisibility(visibility);
        }

        public boolean hasFocus() {
            return mPlusMinusZoomControls.hasFocus();
        }

        public void setOnZoomInClickListener(OnClickListener listener) {
            mPlusMinusZoomControls.setOnZoomInClickListener(listener);
        }

        public void setOnZoomOutClickListener(OnClickListener listener) {
            mPlusMinusZoomControls.setOnZoomOutClickListener(listener);
        }

        ZoomControls    mPlusMinusZoomControls;
    }

    /**
     *  Transportation object for returning WebView across thread boundaries.
     */
    public class WebViewTransport {
        private WebView mWebview;

        /**
         * Set the WebView to the transportation object.
         * @param webview The WebView to transport.
         */
        public synchronized void setWebView(WebView webview) {
            mWebview = webview;
        }

        /**
         * Return the WebView object.
         * @return WebView The transported WebView object.
         */
        public synchronized WebView getWebView() {
            return mWebview;
        }
    }

    // A final CallbackProxy shared by WebViewCore and BrowserFrame.
    private final CallbackProxy mCallbackProxy;

    private final WebViewDatabase mDatabase;

    // SSL certificate for the main top-level page (if secure)
    private SslCertificate mCertificate;

    // Native WebView pointer that is 0 until the native object has been
    // created.
    private int mNativeClass;
    // This would be final but it needs to be set to null when the WebView is
    // destroyed.
    private WebViewCore mWebViewCore;
    // Handler for dispatching UI messages.
    /* package */ final Handler mPrivateHandler = new PrivateHandler();
    private WebTextView mWebTextView;
    // Used to ignore changes to webkit text that arrives to the UI side after
    // more key events.
    private int mTextGeneration;

    // Used by WebViewCore to create child views.
    /* package */ final ViewManager mViewManager;

    /**
     * Position of the last touch event.
     */
    private float mLastTouchX;
    private float mLastTouchY;

    /**
     * Time of the last touch event.
     */
    private long mLastTouchTime;

    /**
     * Time of the last time sending touch event to WebViewCore
     */
    private long mLastSentTouchTime;

    /**
     * The minimum elapsed time before sending another ACTION_MOVE event to
     * WebViewCore. This really should be tuned for each type of the devices.
     * For example in Google Map api test case, it takes Dream device at least
     * 150ms to do a full cycle in the WebViewCore by processing a touch event,
     * triggering the layout and drawing the picture. While the same process
     * takes 60+ms on the current high speed device. If we make
     * TOUCH_SENT_INTERVAL too small, there will be multiple touch events sent
     * to WebViewCore queue and the real layout and draw events will be pushed
     * to further, which slows down the refresh rate. Choose 50 to favor the
     * current high speed devices. For Dream like devices, 100 is a better
     * choice. Maybe make this in the buildspec later.
     */
    private static final int TOUCH_SENT_INTERVAL = 50;

    /**
     * Helper class to get velocity for fling
     */
    VelocityTracker mVelocityTracker;
    private int mMaximumFling;
    private float mLastVelocity;
    private float mLastVelX;
    private float mLastVelY;

    /**
     * Touch mode
     */
    private int mTouchMode = TOUCH_DONE_MODE;
    private static final int TOUCH_INIT_MODE = 1;
    private static final int TOUCH_DRAG_START_MODE = 2;
    private static final int TOUCH_DRAG_MODE = 3;
    private static final int TOUCH_SHORTPRESS_START_MODE = 4;
    private static final int TOUCH_SHORTPRESS_MODE = 5;
    private static final int TOUCH_DOUBLE_TAP_MODE = 6;
    private static final int TOUCH_DONE_MODE = 7;
    private static final int TOUCH_SELECT_MODE = 8;
    private static final int TOUCH_PINCH_DRAG = 9;

    // Whether to forward the touch events to WebCore
    private boolean mForwardTouchEvents = false;

    // Whether to prevent drag during touch. The initial value depends on
    // mForwardTouchEvents. If WebCore wants touch events, we assume it will
    // take control of touch events unless it says no for touch down event.
    private static final int PREVENT_DRAG_NO = 0;
    private static final int PREVENT_DRAG_MAYBE_YES = 1;
    private static final int PREVENT_DRAG_YES = 2;
    private int mPreventDrag = PREVENT_DRAG_NO;

    // To keep track of whether the current drag was initiated by a WebTextView,
    // so that we know not to hide the cursor
    boolean mDragFromTextInput;

    // Whether or not to draw the cursor ring.
    private boolean mDrawCursorRing = true;

    // true if onPause has been called (and not onResume)
    private boolean mIsPaused;

    /**
     * Customizable constant
     */
    // pre-computed square of ViewConfiguration.getScaledTouchSlop()
    private int mTouchSlopSquare;
    // pre-computed square of ViewConfiguration.getScaledDoubleTapSlop()
    private int mDoubleTapSlopSquare;
    // pre-computed density adjusted navigation slop
    private int mNavSlop;
    // This should be ViewConfiguration.getTapTimeout()
    // But system time out is 100ms, which is too short for the browser.
    // In the browser, if it switches out of tap too soon, jump tap won't work.
    private static final int TAP_TIMEOUT = 200;
    // This should be ViewConfiguration.getLongPressTimeout()
    // But system time out is 500ms, which is too short for the browser.
    // With a short timeout, it's difficult to treat trigger a short press.
    private static final int LONG_PRESS_TIMEOUT = 1000;
    // needed to avoid flinging after a pause of no movement
    private static final int MIN_FLING_TIME = 250;
    // The time that the Zoom Controls are visible before fading away
    private static final long ZOOM_CONTROLS_TIMEOUT =
            ViewConfiguration.getZoomControlsTimeout();
    // The amount of content to overlap between two screens when going through
    // pages with the space bar, in pixels.
    private static final int PAGE_SCROLL_OVERLAP = 24;

    /**
     * These prevent calling requestLayout if either dimension is fixed. This
     * depends on the layout parameters and the measure specs.
     */
    boolean mWidthCanMeasure;
    boolean mHeightCanMeasure;

    // Remember the last dimensions we sent to the native side so we can avoid
    // sending the same dimensions more than once.
    int mLastWidthSent;
    int mLastHeightSent;

    private int mContentWidth;   // cache of value from WebViewCore
    private int mContentHeight;  // cache of value from WebViewCore

    // Need to have the separate control for horizontal and vertical scrollbar
    // style than the View's single scrollbar style
    private boolean mOverlayHorizontalScrollbar = true;
    private boolean mOverlayVerticalScrollbar = false;

    // our standard speed. this way small distances will be traversed in less
    // time than large distances, but we cap the duration, so that very large
    // distances won't take too long to get there.
    private static final int STD_SPEED = 480;  // pixels per second
    // time for the longest scroll animation
    private static final int MAX_DURATION = 750;   // milliseconds
    private static final int SLIDE_TITLE_DURATION = 500;   // milliseconds
    private Scroller mScroller;

    private boolean mWrapContent;

    // whether support multi-touch
    private boolean mSupportMultiTouch;
    // use the framework's ScaleGestureDetector to handle multi-touch
    private ScaleGestureDetector mScaleDetector;
    // minimum scale change during multi-touch zoom
    private static float PREVIEW_SCALE_INCREMENT = 0.01f;

    // the anchor point in the document space where VIEW_SIZE_CHANGED should
    // apply to
    private int mAnchorX;
    private int mAnchorY;

    /**
     * Private message ids
     */
    private static final int REMEMBER_PASSWORD          = 1;
    private static final int NEVER_REMEMBER_PASSWORD    = 2;
    private static final int SWITCH_TO_SHORTPRESS       = 3;
    private static final int SWITCH_TO_LONGPRESS        = 4;
    private static final int RELEASE_SINGLE_TAP         = 5;
    private static final int REQUEST_FORM_DATA          = 6;
    private static final int RESUME_WEBCORE_UPDATE      = 7;

    //! arg1=x, arg2=y
    static final int SCROLL_TO_MSG_ID                   = 10;
    static final int SCROLL_BY_MSG_ID                   = 11;
    //! arg1=x, arg2=y
    static final int SPAWN_SCROLL_TO_MSG_ID             = 12;
    //! arg1=x, arg2=y
    static final int SYNC_SCROLL_TO_MSG_ID              = 13;
    static final int NEW_PICTURE_MSG_ID                 = 14;
    static final int UPDATE_TEXT_ENTRY_MSG_ID           = 15;
    static final int WEBCORE_INITIALIZED_MSG_ID         = 16;
    static final int UPDATE_TEXTFIELD_TEXT_MSG_ID       = 17;
    static final int UPDATE_ZOOM_RANGE                  = 18;
    static final int MOVE_OUT_OF_PLUGIN                 = 19;
    static final int CLEAR_TEXT_ENTRY                   = 20;
    static final int UPDATE_TEXT_SELECTION_MSG_ID       = 21;
    static final int UPDATE_CLIPBOARD                   = 22;
    static final int LONG_PRESS_CENTER                  = 23;
    static final int PREVENT_TOUCH_ID                   = 24;
    static final int WEBCORE_NEED_TOUCH_EVENTS          = 25;
    // obj=Rect in doc coordinates
    static final int INVAL_RECT_MSG_ID                  = 26;
    static final int REQUEST_KEYBOARD                   = 27;
    static final int SHOW_RECT_MSG_ID                   = 28;

    static final String[] HandlerDebugString = {
        "REMEMBER_PASSWORD", //              = 1;
        "NEVER_REMEMBER_PASSWORD", //        = 2;
        "SWITCH_TO_SHORTPRESS", //           = 3;
        "SWITCH_TO_LONGPRESS", //            = 4;
        "RELEASE_SINGLE_TAP", //             = 5;
        "REQUEST_FORM_DATA", //              = 6;
        "SWITCH_TO_CLICK", //                = 7;
        "RESUME_WEBCORE_UPDATE", //          = 8;
        "9",
        "SCROLL_TO_MSG_ID", //               = 10;
        "SCROLL_BY_MSG_ID", //               = 11;
        "SPAWN_SCROLL_TO_MSG_ID", //         = 12;
        "SYNC_SCROLL_TO_MSG_ID", //          = 13;
        "NEW_PICTURE_MSG_ID", //             = 14;
        "UPDATE_TEXT_ENTRY_MSG_ID", //       = 15;
        "WEBCORE_INITIALIZED_MSG_ID", //     = 16;
        "UPDATE_TEXTFIELD_TEXT_MSG_ID", //   = 17;
        "UPDATE_ZOOM_RANGE", //              = 18;
        "MOVE_OUT_OF_PLUGIN", //             = 19;
        "CLEAR_TEXT_ENTRY", //               = 20;
        "UPDATE_TEXT_SELECTION_MSG_ID", //   = 21;
        "UPDATE_CLIPBOARD", //               = 22;
        "LONG_PRESS_CENTER", //              = 23;
        "PREVENT_TOUCH_ID", //               = 24;
        "WEBCORE_NEED_TOUCH_EVENTS", //      = 25;
        "INVAL_RECT_MSG_ID", //              = 26;
        "REQUEST_KEYBOARD", //               = 27;
        "SHOW_RECT_MSG_ID" //                = 28;
    };

    // default scale limit. Depending on the display density
    private static float DEFAULT_MAX_ZOOM_SCALE;
    private static float DEFAULT_MIN_ZOOM_SCALE;
    // scale limit, which can be set through viewport meta tag in the web page
    private float mMaxZoomScale;
    private float mMinZoomScale;
    private boolean mMinZoomScaleFixed = true;

    // initial scale in percent. 0 means using default.
    private int mInitialScaleInPercent = 0;

    // while in the zoom overview mode, the page's width is fully fit to the
    // current window. The page is alive, in another words, you can click to
    // follow the links. Double tap will toggle between zoom overview mode and
    // the last zoom scale.
    boolean mInZoomOverview = false;

    // ideally mZoomOverviewWidth should be mContentWidth. But sites like espn,
    // engadget always have wider mContentWidth no matter what viewport size is.
    int mZoomOverviewWidth = WebViewCore.DEFAULT_VIEWPORT_WIDTH;
    float mTextWrapScale;

    // default scale. Depending on the display density.
    static int DEFAULT_SCALE_PERCENT;
    private float mDefaultScale;

    // set to true temporarily while the zoom control is being dragged
    private boolean mPreviewZoomOnly = false;

    // computed scale and inverse, from mZoomWidth.
    private float mActualScale;
    private float mInvActualScale;
    // if this is non-zero, it is used on drawing rather than mActualScale
    private float mZoomScale;
    private float mInvInitialZoomScale;
    private float mInvFinalZoomScale;
    private int mInitialScrollX;
    private int mInitialScrollY;
    private long mZoomStart;
    private static final int ZOOM_ANIMATION_LENGTH = 500;

    private boolean mUserScroll = false;

    private int mSnapScrollMode = SNAP_NONE;
    private static final int SNAP_NONE = 1;
    private static final int SNAP_X = 2;
    private static final int SNAP_Y = 3;
    private static final int SNAP_X_LOCK = 4;
    private static final int SNAP_Y_LOCK = 5;
    private boolean mSnapPositive;

    // Used to match key downs and key ups
    private boolean mGotKeyDown;

    /* package */ static boolean mLogEvent = true;
    private static final int EVENT_LOG_ZOOM_LEVEL_CHANGE = 70101;
    private static final int EVENT_LOG_DOUBLE_TAP_DURATION = 70102;

    // for event log
    private long mLastTouchUpTime = 0;

    /**
     * URI scheme for telephone number
     */
    public static final String SCHEME_TEL = "tel:";
    /**
     * URI scheme for email address
     */
    public static final String SCHEME_MAILTO = "mailto:";
    /**
     * URI scheme for map address
     */
    public static final String SCHEME_GEO = "geo:0,0?q=";

    private int mBackgroundColor = Color.WHITE;

    // Used to notify listeners of a new picture.
    private PictureListener mPictureListener;
    /**
     * Interface to listen for new pictures as they change.
     */
    public interface PictureListener {
        /**
         * Notify the listener that the picture has changed.
         * @param view The WebView that owns the picture.
         * @param picture The new picture.
         */
        public void onNewPicture(WebView view, Picture picture);
    }

    // FIXME: Want to make this public, but need to change the API file.
    public /*static*/ class HitTestResult {
        /**
         * Default HitTestResult, where the target is unknown
         */
        public static final int UNKNOWN_TYPE = 0;
        /**
         * HitTestResult for hitting a HTML::a tag
         */
        public static final int ANCHOR_TYPE = 1;
        /**
         * HitTestResult for hitting a phone number
         */
        public static final int PHONE_TYPE = 2;
        /**
         * HitTestResult for hitting a map address
         */
        public static final int GEO_TYPE = 3;
        /**
         * HitTestResult for hitting an email address
         */
        public static final int EMAIL_TYPE = 4;
        /**
         * HitTestResult for hitting an HTML::img tag
         */
        public static final int IMAGE_TYPE = 5;
        /**
         * HitTestResult for hitting a HTML::a tag which contains HTML::img
         */
        public static final int IMAGE_ANCHOR_TYPE = 6;
        /**
         * HitTestResult for hitting a HTML::a tag with src=http
         */
        public static final int SRC_ANCHOR_TYPE = 7;
        /**
         * HitTestResult for hitting a HTML::a tag with src=http + HTML::img
         */
        public static final int SRC_IMAGE_ANCHOR_TYPE = 8;
        /**
         * HitTestResult for hitting an edit text area
         */
        public static final int EDIT_TEXT_TYPE = 9;

        private int mType;
        private String mExtra;

        HitTestResult() {
            mType = UNKNOWN_TYPE;
        }

        private void setType(int type) {
            mType = type;
        }

        private void setExtra(String extra) {
            mExtra = extra;
        }

        public int getType() {
            return mType;
        }

        public String getExtra() {
            return mExtra;
        }
    }

    // The View containing the zoom controls
    private ExtendedZoomControls mZoomControls;
    private Runnable mZoomControlRunnable;

    private ZoomButtonsController mZoomButtonsController;

    // These keep track of the center point of the zoom.  They are used to
    // determine the point around which we should zoom.
    private float mZoomCenterX;
    private float mZoomCenterY;

    private ZoomButtonsController.OnZoomListener mZoomListener =
            new ZoomButtonsController.OnZoomListener() {

        public void onVisibilityChanged(boolean visible) {
            if (visible) {
                switchOutDrawHistory();
                updateZoomButtonsEnabled();
            }
        }

        public void onZoom(boolean zoomIn) {
            if (zoomIn) {
                zoomIn();
            } else {
                zoomOut();
            }

            updateZoomButtonsEnabled();
        }
    };

    /**
     * Construct a new WebView with a Context object.
     * @param context A Context object used to access application assets.
     */
    public WebView(Context context) {
        this(context, null);
    }

    /**
     * Construct a new WebView with layout parameters.
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     */
    public WebView(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.webViewStyle);
    }

    /**
     * Construct a new WebView with layout parameters and a default style.
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     * @param defStyle The default style resource ID.
     */
    public WebView(Context context, AttributeSet attrs, int defStyle) {
        this(context, attrs, defStyle, null);
    }

    /**
     * Construct a new WebView with layout parameters, a default style and a set
     * of custom Javscript interfaces to be added to the WebView at initialization
     * time. This guraratees that these interfaces will be available when the JS
     * context is initialized.
     * @param context A Context object used to access application assets.
     * @param attrs An AttributeSet passed to our parent.
     * @param defStyle The default style resource ID.
     * @param javascriptInterfaces is a Map of intareface names, as keys, and
     * object implementing those interfaces, as values.
     * @hide pending API council approval.
     */
    protected WebView(Context context, AttributeSet attrs, int defStyle,
            Map<String, Object> javascriptInterfaces) {
        super(context, attrs, defStyle);
        init();

        mCallbackProxy = new CallbackProxy(context, this);
        mWebViewCore = new WebViewCore(context, this, mCallbackProxy, javascriptInterfaces);
        mDatabase = WebViewDatabase.getInstance(context);
        mScroller = new Scroller(context);

        mViewManager = new ViewManager(this);

        mZoomButtonsController = new ZoomButtonsController(this);
        mZoomButtonsController.setOnZoomListener(mZoomListener);
        // ZoomButtonsController positions the buttons at the bottom, but in
        // the middle.  Change their layout parameters so they appear on the
        // right.
        View controls = mZoomButtonsController.getZoomControls();
        ViewGroup.LayoutParams params = controls.getLayoutParams();
        if (params instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams frameParams = (FrameLayout.LayoutParams)
                    params;
            frameParams.gravity = Gravity.RIGHT;
        }
        updateMultiTouchSupport(context);
    }

    void updateMultiTouchSupport(Context context) {
        WebSettings settings = getSettings();
        mSupportMultiTouch = context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH)
                && settings.supportZoom() && settings.getBuiltInZoomControls();
        if (mSupportMultiTouch && (mScaleDetector == null)) {
            mScaleDetector = new ScaleGestureDetector(context,
                    new ScaleDetectorListener());
        } else if (!mSupportMultiTouch && (mScaleDetector != null)) {
            mScaleDetector = null;
        }
    }

    private void updateZoomButtonsEnabled() {
        boolean canZoomIn = mActualScale < mMaxZoomScale;
        boolean canZoomOut = mActualScale > mMinZoomScale && !mInZoomOverview;
        if (!canZoomIn && !canZoomOut) {
            // Hide the zoom in and out buttons, as well as the fit to page
            // button, if the page cannot zoom
            mZoomButtonsController.getZoomControls().setVisibility(View.GONE);
        } else {
            // Bring back the hidden zoom controls.
            mZoomButtonsController.getZoomControls()
                    .setVisibility(View.VISIBLE);
            // Set each one individually, as a page may be able to zoom in
            // or out.
            mZoomButtonsController.setZoomInEnabled(canZoomIn);
            mZoomButtonsController.setZoomOutEnabled(canZoomOut);
        }
    }

    private void init() {
        setWillNotDraw(false);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);

        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        int slop = configuration.getScaledTouchSlop();
        mTouchSlopSquare = slop * slop;
        mMinLockSnapReverseDistance = slop;
        slop = configuration.getScaledDoubleTapSlop();
        mDoubleTapSlopSquare = slop * slop;
        final float density = getContext().getResources().getDisplayMetrics().density;
        // use one line height, 16 based on our current default font, for how
        // far we allow a touch be away from the edge of a link
        mNavSlop = (int) (16 * density);
        // density adjusted scale factors
        DEFAULT_SCALE_PERCENT = (int) (100 * density);
        mDefaultScale = density;
        mActualScale = density;
        mInvActualScale = 1 / density;
        mTextWrapScale = density;
        DEFAULT_MAX_ZOOM_SCALE = 4.0f * density;
        DEFAULT_MIN_ZOOM_SCALE = 0.25f * density;
        mMaxZoomScale = DEFAULT_MAX_ZOOM_SCALE;
        mMinZoomScale = DEFAULT_MIN_ZOOM_SCALE;
        mMaximumFling = configuration.getScaledMaximumFlingVelocity();
    }

    /* package */void updateDefaultZoomDensity(int zoomDensity) {
        final float density = getContext().getResources().getDisplayMetrics().density
                * 100 / zoomDensity;
        if (Math.abs(density - mDefaultScale) > 0.01) {
            float scaleFactor = density / mDefaultScale;
            // adjust the limits
            mNavSlop = (int) (16 * density);
            DEFAULT_SCALE_PERCENT = (int) (100 * density);
            DEFAULT_MAX_ZOOM_SCALE = 4.0f * density;
            DEFAULT_MIN_ZOOM_SCALE = 0.25f * density;
            mDefaultScale = density;
            mMaxZoomScale *= scaleFactor;
            mMinZoomScale *= scaleFactor;
            setNewZoomScale(mActualScale * scaleFactor, true, false);
        }
    }

    /* package */ boolean onSavePassword(String schemePlusHost, String username,
            String password, final Message resumeMsg) {
       boolean rVal = false;
       if (resumeMsg == null) {
           // null resumeMsg implies saving password silently
           mDatabase.setUsernamePassword(schemePlusHost, username, password);
       } else {
            final Message remember = mPrivateHandler.obtainMessage(
                    REMEMBER_PASSWORD);
            remember.getData().putString("host", schemePlusHost);
            remember.getData().putString("username", username);
            remember.getData().putString("password", password);
            remember.obj = resumeMsg;

            final Message neverRemember = mPrivateHandler.obtainMessage(
                    NEVER_REMEMBER_PASSWORD);
            neverRemember.getData().putString("host", schemePlusHost);
            neverRemember.getData().putString("username", username);
            neverRemember.getData().putString("password", password);
            neverRemember.obj = resumeMsg;

            new AlertDialog.Builder(getContext())
                    .setTitle(com.android.internal.R.string.save_password_label)
                    .setMessage(com.android.internal.R.string.save_password_message)
                    .setPositiveButton(com.android.internal.R.string.save_password_notnow,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            resumeMsg.sendToTarget();
                        }
                    })
                    .setNeutralButton(com.android.internal.R.string.save_password_remember,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            remember.sendToTarget();
                        }
                    })
                    .setNegativeButton(com.android.internal.R.string.save_password_never,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            neverRemember.sendToTarget();
                        }
                    })
                    .setOnCancelListener(new OnCancelListener() {
                        public void onCancel(DialogInterface dialog) {
                            resumeMsg.sendToTarget();
                        }
                    }).show();
            // Return true so that WebViewCore will pause while the dialog is
            // up.
            rVal = true;
        }
       return rVal;
    }

    @Override
    public void setScrollBarStyle(int style) {
        if (style == View.SCROLLBARS_INSIDE_INSET
                || style == View.SCROLLBARS_OUTSIDE_INSET) {
            mOverlayHorizontalScrollbar = mOverlayVerticalScrollbar = false;
        } else {
            mOverlayHorizontalScrollbar = mOverlayVerticalScrollbar = true;
        }
        super.setScrollBarStyle(style);
    }

    /**
     * Specify whether the horizontal scrollbar has overlay style.
     * @param overlay TRUE if horizontal scrollbar should have overlay style.
     */
    public void setHorizontalScrollbarOverlay(boolean overlay) {
        mOverlayHorizontalScrollbar = overlay;
    }

    /**
     * Specify whether the vertical scrollbar has overlay style.
     * @param overlay TRUE if vertical scrollbar should have overlay style.
     */
    public void setVerticalScrollbarOverlay(boolean overlay) {
        mOverlayVerticalScrollbar = overlay;
    }

    /**
     * Return whether horizontal scrollbar has overlay style
     * @return TRUE if horizontal scrollbar has overlay style.
     */
    public boolean overlayHorizontalScrollbar() {
        return mOverlayHorizontalScrollbar;
    }

    /**
     * Return whether vertical scrollbar has overlay style
     * @return TRUE if vertical scrollbar has overlay style.
     */
    public boolean overlayVerticalScrollbar() {
        return mOverlayVerticalScrollbar;
    }

    /*
     * Return the width of the view where the content of WebView should render
     * to.
     * Note: this can be called from WebCoreThread.
     */
    /* package */ int getViewWidth() {
        if (!isVerticalScrollBarEnabled() || mOverlayVerticalScrollbar) {
            return getWidth();
        } else {
            return getWidth() - getVerticalScrollbarWidth();
        }
    }

    /*
     * returns the height of the titlebarview (if any). Does not care about
     * scrolling
     */
    private int getTitleHeight() {
        return mTitleBar != null ? mTitleBar.getHeight() : 0;
    }

    /*
     * Return the amount of the titlebarview (if any) that is visible
     */
    private int getVisibleTitleHeight() {
        return Math.max(getTitleHeight() - mScrollY, 0);
    }

    /*
     * Return the height of the view where the content of WebView should render
     * to.  Note that this excludes mTitleBar, if there is one.
     * Note: this can be called from WebCoreThread.
     */
    /* package */ int getViewHeight() {
        return getViewHeightWithTitle() - getVisibleTitleHeight();
    }

    private int getViewHeightWithTitle() {
        int height = getHeight();
        if (isHorizontalScrollBarEnabled() && !mOverlayHorizontalScrollbar) {
            height -= getHorizontalScrollbarHeight();
        }
        return height;
    }

    /**
     * @return The SSL certificate for the main top-level page or null if
     * there is no certificate (the site is not secure).
     */
    public SslCertificate getCertificate() {
        return mCertificate;
    }

    /**
     * Sets the SSL certificate for the main top-level page.
     */
    public void setCertificate(SslCertificate certificate) {
        // here, the certificate can be null (if the site is not secure)
        mCertificate = certificate;
    }

    //-------------------------------------------------------------------------
    // Methods called by activity
    //-------------------------------------------------------------------------

    /**
     * Save the username and password for a particular host in the WebView's
     * internal database.
     * @param host The host that required the credentials.
     * @param username The username for the given host.
     * @param password The password for the given host.
     */
    public void savePassword(String host, String username, String password) {
        mDatabase.setUsernamePassword(host, username, password);
    }

    /**
     * Set the HTTP authentication credentials for a given host and realm.
     *
     * @param host The host for the credentials.
     * @param realm The realm for the credentials.
     * @param username The username for the password. If it is null, it means
     *                 password can't be saved.
     * @param password The password
     */
    public void setHttpAuthUsernamePassword(String host, String realm,
            String username, String password) {
        mDatabase.setHttpAuthUsernamePassword(host, realm, username, password);
    }

    /**
     * Retrieve the HTTP authentication username and password for a given
     * host & realm pair
     *
     * @param host The host for which the credentials apply.
     * @param realm The realm for which the credentials apply.
     * @return String[] if found, String[0] is username, which can be null and
     *         String[1] is password. Return null if it can't find anything.
     */
    public String[] getHttpAuthUsernamePassword(String host, String realm) {
        return mDatabase.getHttpAuthUsernamePassword(host, realm);
    }

    /**
     * Destroy the internal state of the WebView. This method should be called
     * after the WebView has been removed from the view system. No other
     * methods may be called on a WebView after destroy.
     */
    public void destroy() {
        clearTextEntry();
        if (mWebViewCore != null) {
            // Set the handlers to null before destroying WebViewCore so no
            // more messages will be posted.
            mCallbackProxy.setWebViewClient(null);
            mCallbackProxy.setWebChromeClient(null);
            // Tell WebViewCore to destroy itself
            WebViewCore webViewCore = mWebViewCore;
            mWebViewCore = null; // prevent using partial webViewCore
            webViewCore.destroy();
            // Remove any pending messages that might not be serviced yet.
            mPrivateHandler.removeCallbacksAndMessages(null);
            mCallbackProxy.removeCallbacksAndMessages(null);
            // Wake up the WebCore thread just in case it is waiting for a
            // javascript dialog.
            synchronized (mCallbackProxy) {
                mCallbackProxy.notify();
            }
        }
        if (mNativeClass != 0) {
            nativeDestroy();
            mNativeClass = 0;
        }
    }

    /**
     * Enables platform notifications of data state and proxy changes.
     */
    public static void enablePlatformNotifications() {
        Network.enablePlatformNotifications();
    }

    /**
     * If platform notifications are enabled, this should be called
     * from the Activity's onPause() or onStop().
     */
    public static void disablePlatformNotifications() {
        Network.disablePlatformNotifications();
    }

    /**
     * Sets JavaScript engine flags.
     *
     * @param flags JS engine flags in a String
     *
     * @hide pending API solidification
     */
    public void setJsFlags(String flags) {
        mWebViewCore.sendMessage(EventHub.SET_JS_FLAGS, flags);
    }

    /**
     * Inform WebView of the network state. This is used to set
     * the javascript property window.navigator.isOnline and
     * generates the online/offline event as specified in HTML5, sec. 5.7.7
     * @param networkUp boolean indicating if network is available
     */
    public void setNetworkAvailable(boolean networkUp) {
        mWebViewCore.sendMessage(EventHub.SET_NETWORK_STATE,
                networkUp ? 1 : 0, 0);
    }

    /**
     * Save the state of this WebView used in
     * {@link android.app.Activity#onSaveInstanceState}. Please note that this
     * method no longer stores the display data for this WebView. The previous
     * behavior could potentially leak files if {@link #restoreState} was never
     * called. See {@link #savePicture} and {@link #restorePicture} for saving
     * and restoring the display data.
     * @param outState The Bundle to store the WebView state.
     * @return The same copy of the back/forward list used to save the state. If
     *         saveState fails, the returned list will be null.
     * @see #savePicture
     * @see #restorePicture
     */
    public WebBackForwardList saveState(Bundle outState) {
        if (outState == null) {
            return null;
        }
        // We grab a copy of the back/forward list because a client of WebView
        // may have invalidated the history list by calling clearHistory.
        WebBackForwardList list = copyBackForwardList();
        final int currentIndex = list.getCurrentIndex();
        final int size = list.getSize();
        // We should fail saving the state if the list is empty or the index is
        // not in a valid range.
        if (currentIndex < 0 || currentIndex >= size || size == 0) {
            return null;
        }
        outState.putInt("index", currentIndex);
        // FIXME: This should just be a byte[][] instead of ArrayList but
        // Parcel.java does not have the code to handle multi-dimensional
        // arrays.
        ArrayList<byte[]> history = new ArrayList<byte[]>(size);
        for (int i = 0; i < size; i++) {
            WebHistoryItem item = list.getItemAtIndex(i);
            if (null == item) {
                // FIXME: this shouldn't happen
                // need to determine how item got set to null
                Log.w(LOGTAG, "saveState: Unexpected null history item.");
                return null;
            }
            byte[] data = item.getFlattenedData();
            if (data == null) {
                // It would be very odd to not have any data for a given history
                // item. And we will fail to rebuild the history list without
                // flattened data.
                return null;
            }
            history.add(data);
        }
        outState.putSerializable("history", history);
        if (mCertificate != null) {
            outState.putBundle("certificate",
                               SslCertificate.saveState(mCertificate));
        }
        return list;
    }

    /**
     * Save the current display data to the Bundle given. Used in conjunction
     * with {@link #saveState}.
     * @param b A Bundle to store the display data.
     * @param dest The file to store the serialized picture data. Will be
     *             overwritten with this WebView's picture data.
     * @return True if the picture was successfully saved.
     */
    public boolean savePicture(Bundle b, File dest) {
        if (dest == null || b == null) {
            return false;
        }
        final Picture p = capturePicture();

        FileOutputStream out = null;
        boolean success = false;
        try {
            out = new FileOutputStream(dest);
            p.writeToStream(out);
            success = true;
        } catch (FileNotFoundException e){
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (RuntimeException e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable t) {
                }
            }
        }

        if (success && dest.length() > 0) {
            b.putInt("scrollX", mScrollX);
            b.putInt("scrollY", mScrollY);
            b.putFloat("scale", mActualScale);
            b.putFloat("textwrapScale", mTextWrapScale);
            b.putBoolean("overview", mInZoomOverview);
            return true;
        }
        return false;
    }

    /**
     * Restore the display data that was save in {@link #savePicture}. Used in
     * conjunction with {@link #restoreState}.
     * @param b A Bundle containing the saved display data.
     * @param src The file where the picture data was stored.
     * @return True if the picture was successfully restored.
     */
    public boolean restorePicture(Bundle b, File src) {
        if (src == null || b == null) {
            return false;
        }
        if (src.exists()) {
            Picture p = null;
            FileInputStream in = null;
            try {
                in = new FileInputStream(src);
                p = Picture.createFromStream(in);
            } catch (FileNotFoundException e){
                e.printStackTrace();
            } catch (RuntimeException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable t) {
                    }
                }
            }
            if (p != null) {
                int sx = b.getInt("scrollX", 0);
                int sy = b.getInt("scrollY", 0);
                float scale = b.getFloat("scale", 1.0f);
                mDrawHistory = true;
                mHistoryPicture = p;
                mScrollX = sx;
                mScrollY = sy;
                mHistoryWidth = Math.round(p.getWidth() * scale);
                mHistoryHeight = Math.round(p.getHeight() * scale);
                // as getWidth() / getHeight() of the view are not
                // available yet, set up mActualScale, so that when
                // onSizeChanged() is called, the rest will be set
                // correctly
                mActualScale = scale;
                mTextWrapScale = b.getFloat("textwrapScale", scale);
                mInZoomOverview = b.getBoolean("overview");
                invalidate();
                return true;
            }
        }
        return false;
    }

    /**
     * Restore the state of this WebView from the given map used in
     * {@link android.app.Activity#onRestoreInstanceState}. This method should
     * be called to restore the state of the WebView before using the object. If
     * it is called after the WebView has had a chance to build state (load
     * pages, create a back/forward list, etc.) there may be undesirable
     * side-effects. Please note that this method no longer restores the
     * display data for this WebView. See {@link #savePicture} and {@link
     * #restorePicture} for saving and restoring the display data.
     * @param inState The incoming Bundle of state.
     * @return The restored back/forward list or null if restoreState failed.
     * @see #savePicture
     * @see #restorePicture
     */
    public WebBackForwardList restoreState(Bundle inState) {
        WebBackForwardList returnList = null;
        if (inState == null) {
            return returnList;
        }
        if (inState.containsKey("index") && inState.containsKey("history")) {
            mCertificate = SslCertificate.restoreState(
                inState.getBundle("certificate"));

            final WebBackForwardList list = mCallbackProxy.getBackForwardList();
            final int index = inState.getInt("index");
            // We can't use a clone of the list because we need to modify the
            // shared copy, so synchronize instead to prevent concurrent
            // modifications.
            synchronized (list) {
                final List<byte[]> history =
                        (List<byte[]>) inState.getSerializable("history");
                final int size = history.size();
                // Check the index bounds so we don't crash in native code while
                // restoring the history index.
                if (index < 0 || index >= size) {
                    return null;
                }
                for (int i = 0; i < size; i++) {
                    byte[] data = history.remove(0);
                    if (data == null) {
                        // If we somehow have null data, we cannot reconstruct
                        // the item and thus our history list cannot be rebuilt.
                        return null;
                    }
                    WebHistoryItem item = new WebHistoryItem(data);
                    list.addHistoryItem(item);
                }
                // Grab the most recent copy to return to the caller.
                returnList = copyBackForwardList();
                // Update the copy to have the correct index.
                returnList.setCurrentIndex(index);
            }
            // Remove all pending messages because we are restoring previous
            // state.
            mWebViewCore.removeMessages();
            // Send a restore state message.
            mWebViewCore.sendMessage(EventHub.RESTORE_STATE, index);
        }
        return returnList;
    }

    /**
     * Load the given url.
     * @param url The url of the resource to load.
     */
    public void loadUrl(String url) {
        if (url == null) {
            return;
        }
        switchOutDrawHistory();
        mWebViewCore.sendMessage(EventHub.LOAD_URL, url);
        clearTextEntry();
    }

    /**
     * Load the url with postData using "POST" method into the WebView. If url
     * is not a network url, it will be loaded with {link
     * {@link #loadUrl(String)} instead.
     *
     * @param url The url of the resource to load.
     * @param postData The data will be passed to "POST" request.
     */
    public void postUrl(String url, byte[] postData) {
        if (URLUtil.isNetworkUrl(url)) {
            switchOutDrawHistory();
            WebViewCore.PostUrlData arg = new WebViewCore.PostUrlData();
            arg.mUrl = url;
            arg.mPostData = postData;
            mWebViewCore.sendMessage(EventHub.POST_URL, arg);
            clearTextEntry();
        } else {
            loadUrl(url);
        }
    }

    /**
     * Load the given data into the WebView. This will load the data into
     * WebView using the data: scheme. Content loaded through this mechanism
     * does not have the ability to load content from the network.
     * @param data A String of data in the given encoding.
     * @param mimeType The MIMEType of the data. i.e. text/html, image/jpeg
     * @param encoding The encoding of the data. i.e. utf-8, base64
     */
    public void loadData(String data, String mimeType, String encoding) {
        loadUrl("data:" + mimeType + ";" + encoding + "," + data);
    }

    /**
     * Load the given data into the WebView, use the provided URL as the base
     * URL for the content. The base URL is the URL that represents the page
     * that is loaded through this interface. As such, it is used for the
     * history entry and to resolve any relative URLs. The failUrl is used if
     * browser fails to load the data provided. If it is empty or null, and the
     * load fails, then no history entry is created.
     * <p>
     * Note for post 1.0. Due to the change in the WebKit, the access to asset
     * files through "file:///android_asset/" for the sub resources is more
     * restricted. If you provide null or empty string as baseUrl, you won't be
     * able to access asset files. If the baseUrl is anything other than
     * http(s)/ftp(s)/about/javascript as scheme, you can access asset files for
     * sub resources.
     *
     * @param baseUrl Url to resolve relative paths with, if null defaults to
     *            "about:blank"
     * @param data A String of data in the given encoding.
     * @param mimeType The MIMEType of the data. i.e. text/html. If null,
     *            defaults to "text/html"
     * @param encoding The encoding of the data. i.e. utf-8, us-ascii
     * @param failUrl URL to use if the content fails to load or null.
     */
    public void loadDataWithBaseURL(String baseUrl, String data,
            String mimeType, String encoding, String failUrl) {

        if (baseUrl != null && baseUrl.toLowerCase().startsWith("data:")) {
            loadData(data, mimeType, encoding);
            return;
        }
        switchOutDrawHistory();
        WebViewCore.BaseUrlData arg = new WebViewCore.BaseUrlData();
        arg.mBaseUrl = baseUrl;
        arg.mData = data;
        arg.mMimeType = mimeType;
        arg.mEncoding = encoding;
        arg.mFailUrl = failUrl;
        mWebViewCore.sendMessage(EventHub.LOAD_DATA, arg);
        clearTextEntry();
    }

    /**
     * Stop the current load.
     */
    public void stopLoading() {
        // TODO: should we clear all the messages in the queue before sending
        // STOP_LOADING?
        switchOutDrawHistory();
        mWebViewCore.sendMessage(EventHub.STOP_LOADING);
    }

    /**
     * Reload the current url.
     */
    public void reload() {
        clearTextEntry();
        switchOutDrawHistory();
        mWebViewCore.sendMessage(EventHub.RELOAD);
    }

    /**
     * Return true if this WebView has a back history item.
     * @return True iff this WebView has a back history item.
     */
    public boolean canGoBack() {
        WebBackForwardList l = mCallbackProxy.getBackForwardList();
        synchronized (l) {
            if (l.getClearPending()) {
                return false;
            } else {
                return l.getCurrentIndex() > 0;
            }
        }
    }

    /**
     * Go back in the history of this WebView.
     */
    public void goBack() {
        goBackOrForward(-1);
    }

    /**
     * Return true if this WebView has a forward history item.
     * @return True iff this Webview has a forward history item.
     */
    public boolean canGoForward() {
        WebBackForwardList l = mCallbackProxy.getBackForwardList();
        synchronized (l) {
            if (l.getClearPending()) {
                return false;
            } else {
                return l.getCurrentIndex() < l.getSize() - 1;
            }
        }
    }

    /**
     * Go forward in the history of this WebView.
     */
    public void goForward() {
        goBackOrForward(1);
    }

    /**
     * Return true if the page can go back or forward the given
     * number of steps.
     * @param steps The negative or positive number of steps to move the
     *              history.
     */
    public boolean canGoBackOrForward(int steps) {
        WebBackForwardList l = mCallbackProxy.getBackForwardList();
        synchronized (l) {
            if (l.getClearPending()) {
                return false;
            } else {
                int newIndex = l.getCurrentIndex() + steps;
                return newIndex >= 0 && newIndex < l.getSize();
            }
        }
    }

    /**
     * Go to the history item that is the number of steps away from
     * the current item. Steps is negative if backward and positive
     * if forward.
     * @param steps The number of steps to take back or forward in the back
     *              forward list.
     */
    public void goBackOrForward(int steps) {
        goBackOrForward(steps, false);
    }

    private void goBackOrForward(int steps, boolean ignoreSnapshot) {
        // every time we go back or forward, we want to reset the
        // WebView certificate:
        // if the new site is secure, we will reload it and get a
        // new certificate set;
        // if the new site is not secure, the certificate must be
        // null, and that will be the case
        mCertificate = null;
        if (steps != 0) {
            clearTextEntry();
            mWebViewCore.sendMessage(EventHub.GO_BACK_FORWARD, steps,
                    ignoreSnapshot ? 1 : 0);
        }
    }

    private boolean extendScroll(int y) {
        int finalY = mScroller.getFinalY();
        int newY = pinLocY(finalY + y);
        if (newY == finalY) return false;
        mScroller.setFinalY(newY);
        mScroller.extendDuration(computeDuration(0, y));
        return true;
    }

    /**
     * Scroll the contents of the view up by half the view size
     * @param top true to jump to the top of the page
     * @return true if the page was scrolled
     */
    public boolean pageUp(boolean top) {
        if (mNativeClass == 0) {
            return false;
        }
        nativeClearCursor(); // start next trackball movement from page edge
        if (top) {
            // go to the top of the document
            return pinScrollTo(mScrollX, 0, true, 0);
        }
        // Page up
        int h = getHeight();
        int y;
        if (h > 2 * PAGE_SCROLL_OVERLAP) {
            y = -h + PAGE_SCROLL_OVERLAP;
        } else {
            y = -h / 2;
        }
        mUserScroll = true;
        return mScroller.isFinished() ? pinScrollBy(0, y, true, 0)
                : extendScroll(y);
    }

    /**
     * Scroll the contents of the view down by half the page size
     * @param bottom true to jump to bottom of page
     * @return true if the page was scrolled
     */
    public boolean pageDown(boolean bottom) {
        if (mNativeClass == 0) {
            return false;
        }
        nativeClearCursor(); // start next trackball movement from page edge
        if (bottom) {
            return pinScrollTo(mScrollX, computeVerticalScrollRange(), true, 0);
        }
        // Page down.
        int h = getHeight();
        int y;
        if (h > 2 * PAGE_SCROLL_OVERLAP) {
            y = h - PAGE_SCROLL_OVERLAP;
        } else {
            y = h / 2;
        }
        mUserScroll = true;
        return mScroller.isFinished() ? pinScrollBy(0, y, true, 0)
                : extendScroll(y);
    }

    /**
     * Clear the view so that onDraw() will draw nothing but white background,
     * and onMeasure() will return 0 if MeasureSpec is not MeasureSpec.EXACTLY
     */
    public void clearView() {
        mContentWidth = 0;
        mContentHeight = 0;
        mWebViewCore.sendMessage(EventHub.CLEAR_CONTENT);
    }

    /**
     * Return a new picture that captures the current display of the webview.
     * This is a copy of the display, and will be unaffected if the webview
     * later loads a different URL.
     *
     * @return a picture containing the current contents of the view. Note this
     *         picture is of the entire document, and is not restricted to the
     *         bounds of the view.
     */
    public Picture capturePicture() {
        if (null == mWebViewCore) return null; // check for out of memory tab
        return mWebViewCore.copyContentPicture();
    }

    /**
     *  Return true if the browser is displaying a TextView for text input.
     */
    private boolean inEditingMode() {
        return mWebTextView != null && mWebTextView.getParent() != null
                && mWebTextView.hasFocus();
    }

    private void clearTextEntry() {
        if (inEditingMode()) {
            mWebTextView.remove();
        }
    }

    /**
     * Return the current scale of the WebView
     * @return The current scale.
     */
    public float getScale() {
        return mActualScale;
    }

    /**
     * Set the initial scale for the WebView. 0 means default. If
     * {@link WebSettings#getUseWideViewPort()} is true, it zooms out all the
     * way. Otherwise it starts with 100%. If initial scale is greater than 0,
     * WebView starts will this value as initial scale.
     *
     * @param scaleInPercent The initial scale in percent.
     */
    public void setInitialScale(int scaleInPercent) {
        mInitialScaleInPercent = scaleInPercent;
    }

    /**
     * Invoke the graphical zoom picker widget for this WebView. This will
     * result in the zoom widget appearing on the screen to control the zoom
     * level of this WebView.
     */
    public void invokeZoomPicker() {
        if (!getSettings().supportZoom()) {
            Log.w(LOGTAG, "This WebView doesn't support zoom.");
            return;
        }
        clearTextEntry();
        if (getSettings().getBuiltInZoomControls()) {
            mZoomButtonsController.setVisible(true);
        } else {
            mPrivateHandler.removeCallbacks(mZoomControlRunnable);
            mPrivateHandler.postDelayed(mZoomControlRunnable,
                    ZOOM_CONTROLS_TIMEOUT);
        }
    }

    /**
     * Return a HitTestResult based on the current cursor node. If a HTML::a tag
     * is found and the anchor has a non-javascript url, the HitTestResult type
     * is set to SRC_ANCHOR_TYPE and the url is set in the "extra" field. If the
     * anchor does not have a url or if it is a javascript url, the type will
     * be UNKNOWN_TYPE and the url has to be retrieved through
     * {@link #requestFocusNodeHref} asynchronously. If a HTML::img tag is
     * found, the HitTestResult type is set to IMAGE_TYPE and the url is set in
     * the "extra" field. A type of
     * SRC_IMAGE_ANCHOR_TYPE indicates an anchor with a url that has an image as
     * a child node. If a phone number is found, the HitTestResult type is set
     * to PHONE_TYPE and the phone number is set in the "extra" field of
     * HitTestResult. If a map address is found, the HitTestResult type is set
     * to GEO_TYPE and the address is set in the "extra" field of HitTestResult.
     * If an email address is found, the HitTestResult type is set to EMAIL_TYPE
     * and the email is set in the "extra" field of HitTestResult. Otherwise,
     * HitTestResult type is set to UNKNOWN_TYPE.
     */
    public HitTestResult getHitTestResult() {
        if (mNativeClass == 0) {
            return null;
        }

        HitTestResult result = new HitTestResult();
        if (nativeHasCursorNode()) {
            if (nativeCursorIsTextInput()) {
                result.setType(HitTestResult.EDIT_TEXT_TYPE);
            } else {
                String text = nativeCursorText();
                if (text != null) {
                    if (text.startsWith(SCHEME_TEL)) {
                        result.setType(HitTestResult.PHONE_TYPE);
                        result.setExtra(text.substring(SCHEME_TEL.length()));
                    } else if (text.startsWith(SCHEME_MAILTO)) {
                        result.setType(HitTestResult.EMAIL_TYPE);
                        result.setExtra(text.substring(SCHEME_MAILTO.length()));
                    } else if (text.startsWith(SCHEME_GEO)) {
                        result.setType(HitTestResult.GEO_TYPE);
                        result.setExtra(URLDecoder.decode(text
                                .substring(SCHEME_GEO.length())));
                    } else if (nativeCursorIsAnchor()) {
                        result.setType(HitTestResult.SRC_ANCHOR_TYPE);
                        result.setExtra(text);
                    }
                }
            }
        }
        int type = result.getType();
        if (type == HitTestResult.UNKNOWN_TYPE
                || type == HitTestResult.SRC_ANCHOR_TYPE) {
            // Now check to see if it is an image.
            int contentX = viewToContentX((int) mLastTouchX + mScrollX);
            int contentY = viewToContentY((int) mLastTouchY + mScrollY);
            String text = nativeImageURI(contentX, contentY);
            if (text != null) {
                result.setType(type == HitTestResult.UNKNOWN_TYPE ?
                        HitTestResult.IMAGE_TYPE :
                        HitTestResult.SRC_IMAGE_ANCHOR_TYPE);
                result.setExtra(text);
            }
        }
        return result;
    }

    /**
     * Request the href of an anchor element due to getFocusNodePath returning
     * "href." If hrefMsg is null, this method returns immediately and does not
     * dispatch hrefMsg to its target.
     *
     * @param hrefMsg This message will be dispatched with the result of the
     *            request as the data member with "url" as key. The result can
     *            be null.
     */
    // FIXME: API change required to change the name of this function.  We now
    // look at the cursor node, and not the focus node.  Also, what is
    // getFocusNodePath?
    public void requestFocusNodeHref(Message hrefMsg) {
        if (hrefMsg == null || mNativeClass == 0) {
            return;
        }
        if (nativeCursorIsAnchor()) {
            mWebViewCore.sendMessage(EventHub.REQUEST_CURSOR_HREF,
                    nativeCursorFramePointer(), nativeCursorNodePointer(),
                    hrefMsg);
        }
    }

    /**
     * Request the url of the image last touched by the user. msg will be sent
     * to its target with a String representing the url as its object.
     *
     * @param msg This message will be dispatched with the result of the request
     *            as the data member with "url" as key. The result can be null.
     */
    public void requestImageRef(Message msg) {
        if (0 == mNativeClass) return; // client isn't initialized
        int contentX = viewToContentX((int) mLastTouchX + mScrollX);
        int contentY = viewToContentY((int) mLastTouchY + mScrollY);
        String ref = nativeImageURI(contentX, contentY);
        Bundle data = msg.getData();
        data.putString("url", ref);
        msg.setData(data);
        msg.sendToTarget();
    }

    private static int pinLoc(int x, int viewMax, int docMax) {
//        Log.d(LOGTAG, "-- pinLoc " + x + " " + viewMax + " " + docMax);
        if (docMax < viewMax) {   // the doc has room on the sides for "blank"
            // pin the short document to the top/left of the screen
            x = 0;
//            Log.d(LOGTAG, "--- center " + x);
        } else if (x < 0) {
            x = 0;
//            Log.d(LOGTAG, "--- zero");
        } else if (x + viewMax > docMax) {
            x = docMax - viewMax;
//            Log.d(LOGTAG, "--- pin " + x);
        }
        return x;
    }

    // Expects x in view coordinates
    private int pinLocX(int x) {
        return pinLoc(x, getViewWidth(), computeHorizontalScrollRange());
    }

    // Expects y in view coordinates
    private int pinLocY(int y) {
        int titleH = getTitleHeight();
        // if the titlebar is still visible, just pin against 0
        if (y <= titleH) {
            return Math.max(y, 0);
        }
        // convert to 0-based coordinate (subtract the title height)
        // pin(), and then add the title height back in
        return pinLoc(y - titleH, getViewHeight(),
                      computeVerticalScrollRange()) + titleH;
    }

    /**
     * A title bar which is embedded in this WebView, and scrolls along with it
     * vertically, but not horizontally.
     */
    private View mTitleBar;

    /**
     * Since we draw the title bar ourselves, we removed the shadow from the
     * browser's activity.  We do want a shadow at the bottom of the title bar,
     * or at the top of the screen if the title bar is not visible.  This
     * drawable serves that purpose.
     */
    private Drawable mTitleShadow;

    /**
     * Add or remove a title bar to be embedded into the WebView, and scroll
     * along with it vertically, while remaining in view horizontally. Pass
     * null to remove the title bar from the WebView, and return to drawing
     * the WebView normally without translating to account for the title bar.
     * @hide
     */
    public void setEmbeddedTitleBar(View v) {
        if (mTitleBar == v) return;
        if (mTitleBar != null) {
            removeView(mTitleBar);
        }
        if (null != v) {
            addView(v, new AbsoluteLayout.LayoutParams(
                    ViewGroup.LayoutParams.FILL_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));
            if (mTitleShadow == null) {
                mTitleShadow = (Drawable) mContext.getResources().getDrawable(
                        com.android.internal.R.drawable.title_bar_shadow);
            }
        }
        mTitleBar = v;
    }

    /**
     * Given a distance in view space, convert it to content space. Note: this
     * does not reflect translation, just scaling, so this should not be called
     * with coordinates, but should be called for dimensions like width or
     * height.
     */
    private int viewToContentDimension(int d) {
        return Math.round(d * mInvActualScale);
    }

    /**
     * Given an x coordinate in view space, convert it to content space.  Also
     * may be used for absolute heights (such as for the WebTextView's
     * textSize, which is unaffected by the height of the title bar).
     */
    /*package*/ int viewToContentX(int x) {
        return viewToContentDimension(x);
    }

    /**
     * Given a y coordinate in view space, convert it to content space.
     * Takes into account the height of the title bar if there is one
     * embedded into the WebView.
     */
    /*package*/ int viewToContentY(int y) {
        return viewToContentDimension(y - getTitleHeight());
    }

    /**
     * Given a distance in content space, convert it to view space. Note: this
     * does not reflect translation, just scaling, so this should not be called
     * with coordinates, but should be called for dimensions like width or
     * height.
     */
    /*package*/ int contentToViewDimension(int d) {
        return Math.round(d * mActualScale);
    }

    /**
     * Given an x coordinate in content space, convert it to view
     * space.
     */
    /*package*/ int contentToViewX(int x) {
        return contentToViewDimension(x);
    }

    /**
     * Given a y coordinate in content space, convert it to view
     * space.  Takes into account the height of the title bar.
     */
    /*package*/ int contentToViewY(int y) {
        return contentToViewDimension(y) + getTitleHeight();
    }

    private Rect contentToViewRect(Rect x) {
        return new Rect(contentToViewX(x.left), contentToViewY(x.top),
                        contentToViewX(x.right), contentToViewY(x.bottom));
    }

    /*  To invalidate a rectangle in content coordinates, we need to transform
        the rect into view coordinates, so we can then call invalidate(...).

        Normally, we would just call contentToView[XY](...), which eventually
        calls Math.round(coordinate * mActualScale). However, for invalidates,
        we need to account for the slop that occurs with antialiasing. To
        address that, we are a little more liberal in the size of the rect that
        we invalidate.

        This liberal calculation calls floor() for the top/left, and ceil() for
        the bottom/right coordinates. This catches the possible extra pixels of
        antialiasing that we might have missed with just round().
     */

    // Called by JNI to invalidate the View, given rectangle coordinates in
    // content space
    private void viewInvalidate(int l, int t, int r, int b) {
        final float scale = mActualScale;
        final int dy = getTitleHeight();
        invalidate((int)Math.floor(l * scale),
                   (int)Math.floor(t * scale) + dy,
                   (int)Math.ceil(r * scale),
                   (int)Math.ceil(b * scale) + dy);
    }

    // Called by JNI to invalidate the View after a delay, given rectangle
    // coordinates in content space
    private void viewInvalidateDelayed(long delay, int l, int t, int r, int b) {
        final float scale = mActualScale;
        final int dy = getTitleHeight();
        postInvalidateDelayed(delay,
                              (int)Math.floor(l * scale),
                              (int)Math.floor(t * scale) + dy,
                              (int)Math.ceil(r * scale),
                              (int)Math.ceil(b * scale) + dy);
    }

    private void invalidateContentRect(Rect r) {
        viewInvalidate(r.left, r.top, r.right, r.bottom);
    }

    // stop the scroll animation, and don't let a subsequent fling add
    // to the existing velocity
    private void abortAnimation() {
        mScroller.abortAnimation();
        mLastVelocity = 0;
    }

    /* call from webcoreview.draw(), so we're still executing in the UI thread
    */
    private void recordNewContentSize(int w, int h, boolean updateLayout) {

        // premature data from webkit, ignore
        if ((w | h) == 0) {
            return;
        }

        // don't abort a scroll animation if we didn't change anything
        if (mContentWidth != w || mContentHeight != h) {
            // record new dimensions
            mContentWidth = w;
            mContentHeight = h;
            // If history Picture is drawn, don't update scroll. They will be
            // updated when we get out of that mode.
            if (!mDrawHistory) {
                // repin our scroll, taking into account the new content size
                int oldX = mScrollX;
                int oldY = mScrollY;
                mScrollX = pinLocX(mScrollX);
                mScrollY = pinLocY(mScrollY);
                if (oldX != mScrollX || oldY != mScrollY) {
                    sendOurVisibleRect();
                }
                if (!mScroller.isFinished()) {
                    // We are in the middle of a scroll.  Repin the final scroll
                    // position.
                    mScroller.setFinalX(pinLocX(mScroller.getFinalX()));
                    mScroller.setFinalY(pinLocY(mScroller.getFinalY()));
                }
            }
        }
        contentSizeChanged(updateLayout);
    }

    private void setNewZoomScale(float scale, boolean updateTextWrapScale,
            boolean force) {
        if (scale < mMinZoomScale) {
            scale = mMinZoomScale;
        } else if (scale > mMaxZoomScale) {
            scale = mMaxZoomScale;
        }
        if (updateTextWrapScale) {
            mTextWrapScale = scale;
            // reset mLastHeightSent to force VIEW_SIZE_CHANGED sent to WebKit
            mLastHeightSent = 0;
        }
        if (scale != mActualScale || force) {
            if (mDrawHistory) {
                // If history Picture is drawn, don't update scroll. They will
                // be updated when we get out of that mode.
                if (scale != mActualScale && !mPreviewZoomOnly) {
                    mCallbackProxy.onScaleChanged(mActualScale, scale);
                }
                mActualScale = scale;
                mInvActualScale = 1 / scale;
                sendViewSizeZoom();
            } else {
                // update our scroll so we don't appear to jump
                // i.e. keep the center of the doc in the center of the view

                int oldX = mScrollX;
                int oldY = mScrollY;
                float ratio = scale * mInvActualScale;   // old inverse
                float sx = ratio * oldX + (ratio - 1) * mZoomCenterX;
                float sy = ratio * oldY + (ratio - 1)
                        * (mZoomCenterY - getTitleHeight());

                // now update our new scale and inverse
                if (scale != mActualScale && !mPreviewZoomOnly) {
                    mCallbackProxy.onScaleChanged(mActualScale, scale);
                }
                mActualScale = scale;
                mInvActualScale = 1 / scale;

                // Scale all the child views
                mViewManager.scaleAll();

                // as we don't have animation for scaling, don't do animation
                // for scrolling, as it causes weird intermediate state
                //        pinScrollTo(Math.round(sx), Math.round(sy));
                mScrollX = pinLocX(Math.round(sx));
                mScrollY = pinLocY(Math.round(sy));

                // update webkit
                sendViewSizeZoom();
                sendOurVisibleRect();
            }
        }
    }

    // Used to avoid sending many visible rect messages.
    private Rect mLastVisibleRectSent;
    private Rect mLastGlobalRect;

    private Rect sendOurVisibleRect() {
        if (mPreviewZoomOnly) return mLastVisibleRectSent;

        Rect rect = new Rect();
        calcOurContentVisibleRect(rect);
        // Rect.equals() checks for null input.
        if (!rect.equals(mLastVisibleRectSent)) {
            Point pos = new Point(rect.left, rect.top);
            mWebViewCore.sendMessage(EventHub.SET_SCROLL_OFFSET,
                    nativeMoveGeneration(), 0, pos);
            mLastVisibleRectSent = rect;
        }
        Rect globalRect = new Rect();
        if (getGlobalVisibleRect(globalRect)
                && !globalRect.equals(mLastGlobalRect)) {
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "sendOurVisibleRect=(" + globalRect.left + ","
                        + globalRect.top + ",r=" + globalRect.right + ",b="
                        + globalRect.bottom);
            }
            // TODO: the global offset is only used by windowRect()
            // in ChromeClientAndroid ; other clients such as touch
            // and mouse events could return view + screen relative points.
            mWebViewCore.sendMessage(EventHub.SET_GLOBAL_BOUNDS, globalRect);
            mLastGlobalRect = globalRect;
        }
        return rect;
    }

    // Sets r to be the visible rectangle of our webview in view coordinates
    private void calcOurVisibleRect(Rect r) {
        Point p = new Point();
        getGlobalVisibleRect(r, p);
        r.offset(-p.x, -p.y);
        if (mFindIsUp) {
            r.bottom -= mFindHeight;
        }
    }

    // Sets r to be our visible rectangle in content coordinates
    private void calcOurContentVisibleRect(Rect r) {
        calcOurVisibleRect(r);
        r.left = viewToContentX(r.left);
        // viewToContentY will remove the total height of the title bar.  Add
        // the visible height back in to account for the fact that if the title
        // bar is partially visible, the part of the visible rect which is
        // displaying our content is displaced by that amount.
        r.top = viewToContentY(r.top + getVisibleTitleHeight());
        r.right = viewToContentX(r.right);
        r.bottom = viewToContentY(r.bottom);
    }

    static class ViewSizeData {
        int mWidth;
        int mHeight;
        int mTextWrapWidth;
        int mAnchorX;
        int mAnchorY;
        float mScale;
        boolean mIgnoreHeight;
    }

    /**
     * Compute unzoomed width and height, and if they differ from the last
     * values we sent, send them to webkit (to be used has new viewport)
     *
     * @return true if new values were sent
     */
    private boolean sendViewSizeZoom() {
        if (mPreviewZoomOnly) return false;

        int viewWidth = getViewWidth();
        int newWidth = Math.round(viewWidth * mInvActualScale);
        int newHeight = Math.round(getViewHeight() * mInvActualScale);
        /*
         * Because the native side may have already done a layout before the
         * View system was able to measure us, we have to send a height of 0 to
         * remove excess whitespace when we grow our width. This will trigger a
         * layout and a change in content size. This content size change will
         * mean that contentSizeChanged will either call this method directly or
         * indirectly from onSizeChanged.
         */
        if (newWidth > mLastWidthSent && mWrapContent) {
            newHeight = 0;
        }
        // Avoid sending another message if the dimensions have not changed.
        if (newWidth != mLastWidthSent || newHeight != mLastHeightSent) {
            ViewSizeData data = new ViewSizeData();
            data.mWidth = newWidth;
            data.mHeight = newHeight;
            data.mTextWrapWidth = Math.round(viewWidth / mTextWrapScale);;
            data.mScale = mActualScale;
            data.mIgnoreHeight = mZoomScale != 0 && !mHeightCanMeasure;
            data.mAnchorX = mAnchorX;
            data.mAnchorY = mAnchorY;
            mWebViewCore.sendMessage(EventHub.VIEW_SIZE_CHANGED, data);
            mLastWidthSent = newWidth;
            mLastHeightSent = newHeight;
            mAnchorX = mAnchorY = 0;
            return true;
        }
        return false;
    }

    @Override
    protected int computeHorizontalScrollRange() {
        if (mDrawHistory) {
            return mHistoryWidth;
        } else {
            // to avoid rounding error caused unnecessary scrollbar, use floor
            return (int) Math.floor(mContentWidth * mActualScale);
        }
    }

    @Override
    protected int computeVerticalScrollRange() {
        if (mDrawHistory) {
            return mHistoryHeight;
        } else {
            // to avoid rounding error caused unnecessary scrollbar, use floor
            return (int) Math.floor(mContentHeight * mActualScale);
        }
    }

    @Override
    protected int computeVerticalScrollOffset() {
        return Math.max(mScrollY - getTitleHeight(), 0);
    }

    @Override
    protected int computeVerticalScrollExtent() {
        return getViewHeight();
    }

    /** @hide */
    @Override
    protected void onDrawVerticalScrollBar(Canvas canvas,
                                           Drawable scrollBar,
                                           int l, int t, int r, int b) {
        scrollBar.setBounds(l, t + getVisibleTitleHeight(), r, b);
        scrollBar.draw(canvas);
    }

    /**
     * Get the url for the current page. This is not always the same as the url
     * passed to WebViewClient.onPageStarted because although the load for
     * that url has begun, the current page may not have changed.
     * @return The url for the current page.
     */
    public String getUrl() {
        WebHistoryItem h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getUrl() : null;
    }

    /**
     * Get the original url for the current page. This is not always the same
     * as the url passed to WebViewClient.onPageStarted because although the
     * load for that url has begun, the current page may not have changed.
     * Also, there may have been redirects resulting in a different url to that
     * originally requested.
     * @return The url that was originally requested for the current page.
     */
    public String getOriginalUrl() {
        WebHistoryItem h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getOriginalUrl() : null;
    }

    /**
     * Get the title for the current page. This is the title of the current page
     * until WebViewClient.onReceivedTitle is called.
     * @return The title for the current page.
     */
    public String getTitle() {
        WebHistoryItem h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getTitle() : null;
    }

    /**
     * Get the favicon for the current page. This is the favicon of the current
     * page until WebViewClient.onReceivedIcon is called.
     * @return The favicon for the current page.
     */
    public Bitmap getFavicon() {
        WebHistoryItem h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getFavicon() : null;
    }

    /**
     * Get the touch icon url for the apple-touch-icon <link> element.
     * @hide
     */
    public String getTouchIconUrl() {
        WebHistoryItem h = mCallbackProxy.getBackForwardList().getCurrentItem();
        return h != null ? h.getTouchIconUrl() : null;
    }

    /**
     * Get the progress for the current page.
     * @return The progress for the current page between 0 and 100.
     */
    public int getProgress() {
        return mCallbackProxy.getProgress();
    }

    /**
     * @return the height of the HTML content.
     */
    public int getContentHeight() {
        return mContentHeight;
    }

    /**
     * @return the width of the HTML content.
     * @hide
     */
    public int getContentWidth() {
        return mContentWidth;
    }

    /**
     * Pause all layout, parsing, and javascript timers for all webviews. This
     * is a global requests, not restricted to just this webview. This can be
     * useful if the application has been paused.
     */
    public void pauseTimers() {
        mWebViewCore.sendMessage(EventHub.PAUSE_TIMERS);
    }

    /**
     * Resume all layout, parsing, and javascript timers for all webviews.
     * This will resume dispatching all timers.
     */
    public void resumeTimers() {
        mWebViewCore.sendMessage(EventHub.RESUME_TIMERS);
    }

    /**
     * Call this to pause any extra processing associated with this view and
     * its associated DOM/plugins/javascript/etc. For example, if the view is
     * taken offscreen, this could be called to reduce unnecessary CPU and/or
     * network traffic. When the view is again "active", call onResume().
     *
     * Note that this differs from pauseTimers(), which affects all views/DOMs
     * @hide
     */
    public void onPause() {
        if (!mIsPaused) {
            mIsPaused = true;
            mWebViewCore.sendMessage(EventHub.ON_PAUSE);
        }
    }

    /**
     * Call this to balanace a previous call to onPause()
     * @hide
     */
    public void onResume() {
        if (mIsPaused) {
            mIsPaused = false;
            mWebViewCore.sendMessage(EventHub.ON_RESUME);
        }
    }

    /**
     * Returns true if the view is paused, meaning onPause() was called. Calling
     * onResume() sets the paused state back to false.
     * @hide
     */
    public boolean isPaused() {
        return mIsPaused;
    }

    /**
     * Call this to inform the view that memory is low so that it can
     * free any available memory.
     */
    public void freeMemory() {
        mWebViewCore.sendMessage(EventHub.FREE_MEMORY);
    }

    /**
     * Clear the resource cache. Note that the cache is per-application, so
     * this will clear the cache for all WebViews used.
     *
     * @param includeDiskFiles If false, only the RAM cache is cleared.
     */
    public void clearCache(boolean includeDiskFiles) {
        // Note: this really needs to be a static method as it clears cache for all
        // WebView. But we need mWebViewCore to send message to WebCore thread, so
        // we can't make this static.
        mWebViewCore.sendMessage(EventHub.CLEAR_CACHE,
                includeDiskFiles ? 1 : 0, 0);
    }

    /**
     * Make sure that clearing the form data removes the adapter from the
     * currently focused textfield if there is one.
     */
    public void clearFormData() {
        if (inEditingMode()) {
            AutoCompleteAdapter adapter = null;
            mWebTextView.setAdapterCustom(adapter);
        }
    }

    /**
     * Tell the WebView to clear its internal back/forward list.
     */
    public void clearHistory() {
        mCallbackProxy.getBackForwardList().setClearPending();
        mWebViewCore.sendMessage(EventHub.CLEAR_HISTORY);
    }

    /**
     * Clear the SSL preferences table stored in response to proceeding with SSL
     * certificate errors.
     */
    public void clearSslPreferences() {
        mWebViewCore.sendMessage(EventHub.CLEAR_SSL_PREF_TABLE);
    }

    /**
     * Return the WebBackForwardList for this WebView. This contains the
     * back/forward list for use in querying each item in the history stack.
     * This is a copy of the private WebBackForwardList so it contains only a
     * snapshot of the current state. Multiple calls to this method may return
     * different objects. The object returned from this method will not be
     * updated to reflect any new state.
     */
    public WebBackForwardList copyBackForwardList() {
        return mCallbackProxy.getBackForwardList().clone();
    }

    /*
     * Highlight and scroll to the next occurance of String in findAll.
     * Wraps the page infinitely, and scrolls.  Must be called after
     * calling findAll.
     *
     * @param forward Direction to search.
     */
    public void findNext(boolean forward) {
        if (0 == mNativeClass) return; // client isn't initialized
        nativeFindNext(forward);
    }

    /*
     * Find all instances of find on the page and highlight them.
     * @param find  String to find.
     * @return int  The number of occurances of the String "find"
     *              that were found.
     */
    public int findAll(String find) {
        if (0 == mNativeClass) return 0; // client isn't initialized
        if (mFindIsUp == false) {
            recordNewContentSize(mContentWidth, mContentHeight + mFindHeight,
                    false);
            mFindIsUp = true;
        }
        int result = nativeFindAll(find.toLowerCase(), find.toUpperCase());
        invalidate();
        return result;
    }

    // Used to know whether the find dialog is open.  Affects whether
    // or not we draw the highlights for matches.
    private boolean mFindIsUp;
    private int mFindHeight;

    /**
     * Return the first substring consisting of the address of a physical
     * location. Currently, only addresses in the United States are detected,
     * and consist of:
     * - a house number
     * - a street name
     * - a street type (Road, Circle, etc), either spelled out or abbreviated
     * - a city name
     * - a state or territory, either spelled out or two-letter abbr.
     * - an optional 5 digit or 9 digit zip code.
     *
     * All names must be correctly capitalized, and the zip code, if present,
     * must be valid for the state. The street type must be a standard USPS
     * spelling or abbreviation. The state or territory must also be spelled
     * or abbreviated using USPS standards. The house number may not exceed
     * five digits.
     * @param addr The string to search for addresses.
     *
     * @return the address, or if no address is found, return null.
     */
    public static String findAddress(String addr) {
        return findAddress(addr, false);
    }

    /**
     * @hide
     * Return the first substring consisting of the address of a physical
     * location. Currently, only addresses in the United States are detected,
     * and consist of:
     * - a house number
     * - a street name
     * - a street type (Road, Circle, etc), either spelled out or abbreviated
     * - a city name
     * - a state or territory, either spelled out or two-letter abbr.
     * - an optional 5 digit or 9 digit zip code.
     *
     * Names are optionally capitalized, and the zip code, if present,
     * must be valid for the state. The street type must be a standard USPS
     * spelling or abbreviation. The state or territory must also be spelled
     * or abbreviated using USPS standards. The house number may not exceed
     * five digits.
     * @param addr The string to search for addresses.
     * @param caseInsensitive addr Set to true to make search ignore case.
     *
     * @return the address, or if no address is found, return null.
     */
    public static String findAddress(String addr, boolean caseInsensitive) {
        return WebViewCore.nativeFindAddress(addr, caseInsensitive);
    }

    /*
     * Clear the highlighting surrounding text matches created by findAll.
     */
    public void clearMatches() {
        if (mFindIsUp) {
            recordNewContentSize(mContentWidth, mContentHeight - mFindHeight,
                    false);
            mFindIsUp = false;
        }
        nativeSetFindIsDown();
        // Now that the dialog has been removed, ensure that we scroll to a
        // location that is not beyond the end of the page.
        pinScrollTo(mScrollX, mScrollY, false, 0);
        invalidate();
    }

    /**
     * @hide
     */
    public void setFindDialogHeight(int height) {
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "setFindDialogHeight height=" + height);
        }
        mFindHeight = height;
    }

    /**
     * Query the document to see if it contains any image references. The
     * message object will be dispatched with arg1 being set to 1 if images
     * were found and 0 if the document does not reference any images.
     * @param response The message that will be dispatched with the result.
     */
    public void documentHasImages(Message response) {
        if (response == null) {
            return;
        }
        mWebViewCore.sendMessage(EventHub.DOC_HAS_IMAGES, response);
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            int oldX = mScrollX;
            int oldY = mScrollY;
            mScrollX = mScroller.getCurrX();
            mScrollY = mScroller.getCurrY();
            postInvalidate();  // So we draw again
            if (oldX != mScrollX || oldY != mScrollY) {
                // as onScrollChanged() is not called, sendOurVisibleRect()
                // needs to be call explicitly
                sendOurVisibleRect();
            }
        } else {
            super.computeScroll();
        }
    }

    private static int computeDuration(int dx, int dy) {
        int distance = Math.max(Math.abs(dx), Math.abs(dy));
        int duration = distance * 1000 / STD_SPEED;
        return Math.min(duration, MAX_DURATION);
    }

    // helper to pin the scrollBy parameters (already in view coordinates)
    // returns true if the scroll was changed
    private boolean pinScrollBy(int dx, int dy, boolean animate, int animationDuration) {
        return pinScrollTo(mScrollX + dx, mScrollY + dy, animate, animationDuration);
    }
    // helper to pin the scrollTo parameters (already in view coordinates)
    // returns true if the scroll was changed
    private boolean pinScrollTo(int x, int y, boolean animate, int animationDuration) {
        x = pinLocX(x);
        y = pinLocY(y);
        int dx = x - mScrollX;
        int dy = y - mScrollY;

        if ((dx | dy) == 0) {
            return false;
        }
        if (animate) {
            //        Log.d(LOGTAG, "startScroll: " + dx + " " + dy);
            mScroller.startScroll(mScrollX, mScrollY, dx, dy,
                    animationDuration > 0 ? animationDuration : computeDuration(dx, dy));
            awakenScrollBars(mScroller.getDuration());
            invalidate();
        } else {
            abortAnimation(); // just in case
            scrollTo(x, y);
        }
        return true;
    }

    // Scale from content to view coordinates, and pin.
    // Also called by jni webview.cpp
    private boolean setContentScrollBy(int cx, int cy, boolean animate) {
        if (mDrawHistory) {
            // disallow WebView to change the scroll position as History Picture
            // is used in the view system.
            // TODO: as we switchOutDrawHistory when trackball or navigation
            // keys are hit, this should be safe. Right?
            return false;
        }
        cx = contentToViewDimension(cx);
        cy = contentToViewDimension(cy);
        if (mHeightCanMeasure) {
            // move our visible rect according to scroll request
            if (cy != 0) {
                Rect tempRect = new Rect();
                calcOurVisibleRect(tempRect);
                tempRect.offset(cx, cy);
                requestRectangleOnScreen(tempRect);
            }
            // FIXME: We scroll horizontally no matter what because currently
            // ScrollView and ListView will not scroll horizontally.
            // FIXME: Why do we only scroll horizontally if there is no
            // vertical scroll?
//                Log.d(LOGTAG, "setContentScrollBy cy=" + cy);
            return cy == 0 && cx != 0 && pinScrollBy(cx, 0, animate, 0);
        } else {
            return pinScrollBy(cx, cy, animate, 0);
        }
    }

    // scale from content to view coordinates, and pin
    // return true if pin caused the final x/y different than the request cx/cy,
    // and a future scroll may reach the request cx/cy after our size has
    // changed
    // return false if the view scroll to the exact position as it is requested,
    // where negative numbers are taken to mean 0
    private boolean setContentScrollTo(int cx, int cy) {
        if (mDrawHistory) {
            // disallow WebView to change the scroll position as History Picture
            // is used in the view system.
            // One known case where this is called is that WebCore tries to
            // restore the scroll position. As history Picture already uses the
            // saved scroll position, it is ok to skip this.
            return false;
        }
        int vx;
        int vy;
        if ((cx | cy) == 0) {
            // If the page is being scrolled to (0,0), do not add in the title
            // bar's height, and simply scroll to (0,0). (The only other work
            // in contentToView_ is to multiply, so this would not change 0.)
            vx = 0;
            vy = 0;
        } else {
            vx = contentToViewX(cx);
            vy = contentToViewY(cy);
        }
//        Log.d(LOGTAG, "content scrollTo [" + cx + " " + cy + "] view=[" +
//                      vx + " " + vy + "]");
        // Some mobile sites attempt to scroll the title bar off the page by
        // scrolling to (0,1).  If we are at the top left corner of the
        // page, assume this is an attempt to scroll off the title bar, and
        // animate the title bar off screen slowly enough that the user can see
        // it.
        if (cx == 0 && cy == 1 && mScrollX == 0 && mScrollY == 0) {
            pinScrollTo(vx, vy, true, SLIDE_TITLE_DURATION);
            // Since we are animating, we have not yet reached the desired
            // scroll position.  Do not return true to request another attempt
            return false;
        }
        pinScrollTo(vx, vy, false, 0);
        // If the request was to scroll to a negative coordinate, treat it as if
        // it was a request to scroll to 0
        if ((mScrollX != vx && cx >= 0) || (mScrollY != vy && cy >= 0)) {
            return true;
        } else {
            return false;
        }
    }

    // scale from content to view coordinates, and pin
    private void spawnContentScrollTo(int cx, int cy) {
        if (mDrawHistory) {
            // disallow WebView to change the scroll position as History Picture
            // is used in the view system.
            return;
        }
        int vx = contentToViewX(cx);
        int vy = contentToViewY(cy);
        pinScrollTo(vx, vy, true, 0);
    }

    /**
     * These are from webkit, and are in content coordinate system (unzoomed)
     */
    private void contentSizeChanged(boolean updateLayout) {
        // suppress 0,0 since we usually see real dimensions soon after
        // this avoids drawing the prev content in a funny place. If we find a
        // way to consolidate these notifications, this check may become
        // obsolete
        if ((mContentWidth | mContentHeight) == 0) {
            return;
        }

        if (mHeightCanMeasure) {
            if (getMeasuredHeight() != contentToViewDimension(mContentHeight)
                    && updateLayout) {
                requestLayout();
            }
        } else if (mWidthCanMeasure) {
            if (getMeasuredWidth() != contentToViewDimension(mContentWidth)
                    && updateLayout) {
                requestLayout();
            }
        } else {
            // If we don't request a layout, try to send our view size to the
            // native side to ensure that WebCore has the correct dimensions.
            sendViewSizeZoom();
        }
    }

    /**
     * Set the WebViewClient that will receive various notifications and
     * requests. This will replace the current handler.
     * @param client An implementation of WebViewClient.
     */
    public void setWebViewClient(WebViewClient client) {
        mCallbackProxy.setWebViewClient(client);
    }

    /**
     * Register the interface to be used when content can not be handled by
     * the rendering engine, and should be downloaded instead. This will replace
     * the current handler.
     * @param listener An implementation of DownloadListener.
     */
    public void setDownloadListener(DownloadListener listener) {
        mCallbackProxy.setDownloadListener(listener);
    }

    /**
     * Set the chrome handler. This is an implementation of WebChromeClient for
     * use in handling Javascript dialogs, favicons, titles, and the progress.
     * This will replace the current handler.
     * @param client An implementation of WebChromeClient.
     */
    public void setWebChromeClient(WebChromeClient client) {
        mCallbackProxy.setWebChromeClient(client);
    }

    /**
     * Gets the chrome handler.
     * @return the current WebChromeClient instance.
     *
     * @hide API council approval.
     */
    public WebChromeClient getWebChromeClient() {
        return mCallbackProxy.getWebChromeClient();
    }

    /**
     * Set the Picture listener. This is an interface used to receive
     * notifications of a new Picture.
     * @param listener An implementation of WebView.PictureListener.
     */
    public void setPictureListener(PictureListener listener) {
        mPictureListener = listener;
    }

    /**
     * {@hide}
     */
    /* FIXME: Debug only! Remove for SDK! */
    public void externalRepresentation(Message callback) {
        mWebViewCore.sendMessage(EventHub.REQUEST_EXT_REPRESENTATION, callback);
    }

    /**
     * {@hide}
     */
    /* FIXME: Debug only! Remove for SDK! */
    public void documentAsText(Message callback) {
        mWebViewCore.sendMessage(EventHub.REQUEST_DOC_AS_TEXT, callback);
    }

    /**
     * Use this function to bind an object to Javascript so that the
     * methods can be accessed from Javascript.
     * <p><strong>IMPORTANT:</strong>
     * <ul>
     * <li> Using addJavascriptInterface() allows JavaScript to control your
     * application. This can be a very useful feature or a dangerous security
     * issue. When the HTML in the WebView is untrustworthy (for example, part
     * or all of the HTML is provided by some person or process), then an
     * attacker could inject HTML that will execute your code and possibly any
     * code of the attacker's choosing.<br>
     * Do not use addJavascriptInterface() unless all of the HTML in this
     * WebView was written by you.</li>
     * <li> The Java object that is bound runs in another thread and not in
     * the thread that it was constructed in.</li>
     * </ul></p>
     * @param obj The class instance to bind to Javascript
     * @param interfaceName The name to used to expose the class in Javascript
     */
    public void addJavascriptInterface(Object obj, String interfaceName) {
        WebViewCore.JSInterfaceData arg = new WebViewCore.JSInterfaceData();
        arg.mObject = obj;
        arg.mInterfaceName = interfaceName;
        mWebViewCore.sendMessage(EventHub.ADD_JS_INTERFACE, arg);
    }

    /**
     * Return the WebSettings object used to control the settings for this
     * WebView.
     * @return A WebSettings object that can be used to control this WebView's
     *         settings.
     */
    public WebSettings getSettings() {
        return mWebViewCore.getSettings();
    }

   /**
    * Return the list of currently loaded plugins.
    * @return The list of currently loaded plugins.
    *
    * @deprecated This was used for Gears, which has been deprecated.
    */
    @Deprecated
    public static synchronized PluginList getPluginList() {
        return new PluginList();
    }

   /**
    * @deprecated This was used for Gears, which has been deprecated.
    */
    @Deprecated
    public void refreshPlugins(boolean reloadOpenPages) { }

    //-------------------------------------------------------------------------
    // Override View methods
    //-------------------------------------------------------------------------

    @Override
    protected void finalize() throws Throwable {
        try {
            destroy();
        } finally {
            super.finalize();
        }
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (child == mTitleBar) {
            // When drawing the title bar, move it horizontally to always show
            // at the top of the WebView.
            mTitleBar.offsetLeftAndRight(mScrollX - mTitleBar.getLeft());
        }
        return super.drawChild(canvas, child, drawingTime);
    }

    private void drawContent(Canvas canvas) {
        // Update the buttons in the picture, so when we draw the picture
        // to the screen, they are in the correct state.
        // Tell the native side if user is a) touching the screen,
        // b) pressing the trackball down, or c) pressing the enter key
        // If the cursor is on a button, we need to draw it in the pressed
        // state.
        // If mNativeClass is 0, we should not reach here, so we do not
        // need to check it again.
        nativeRecordButtons(hasFocus() && hasWindowFocus(),
                mTouchMode == TOUCH_SHORTPRESS_START_MODE
                || mTrackballDown || mGotCenterDown, false);
        drawCoreAndCursorRing(canvas, mBackgroundColor, mDrawCursorRing);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // if mNativeClass is 0, the WebView has been destroyed. Do nothing.
        if (mNativeClass == 0) {
            return;
        }
        int saveCount = canvas.save();
        if (mTitleBar != null) {
            canvas.translate(0, (int) mTitleBar.getHeight());
        }
        if (mDragTrackerHandler == null || !mDragTrackerHandler.draw(canvas)) {
            drawContent(canvas);
        }
        canvas.restoreToCount(saveCount);

        // Now draw the shadow.
        if (mTitleBar != null) {
            int y = mScrollY + getVisibleTitleHeight();
            int height = (int) (5f * getContext().getResources()
                    .getDisplayMetrics().density);
            mTitleShadow.setBounds(mScrollX, y, mScrollX + getWidth(),
                    y + height);
            mTitleShadow.draw(canvas);
        }
        if (AUTO_REDRAW_HACK && mAutoRedraw) {
            invalidate();
        }
    }

    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        if (params.height == LayoutParams.WRAP_CONTENT) {
            mWrapContent = true;
        }
        super.setLayoutParams(params);
    }

    @Override
    public boolean performLongClick() {
        if (mNativeClass != 0 && nativeCursorIsTextInput()) {
            // Send the click so that the textfield is in focus
            // FIXME: When we start respecting changes to the native textfield's
            // selection, need to make sure that this does not change it.
            mWebViewCore.sendMessage(EventHub.CLICK, nativeCursorFramePointer(),
                    nativeCursorNodePointer());
            rebuildWebTextView();
        }
        if (inEditingMode()) {
            return mWebTextView.performLongClick();
        } else {
            return super.performLongClick();
        }
    }

    boolean inAnimateZoom() {
        return mZoomScale != 0;
    }

    /**
     * Need to adjust the WebTextView after a change in zoom, since mActualScale
     * has changed.  This is especially important for password fields, which are
     * drawn by the WebTextView, since it conveys more information than what
     * webkit draws.  Thus we need to reposition it to show in the correct
     * place.
     */
    private boolean mNeedToAdjustWebTextView;

    // if checkVisibility is false, the WebTextView may trigger a move of
    // WebView to bring itself into the view.
    private void adjustTextView(boolean checkVisibility) {
        Rect contentBounds = nativeFocusCandidateNodeBounds();
        Rect vBox = contentToViewRect(contentBounds);
        Rect visibleRect = new Rect();
        calcOurVisibleRect(visibleRect);
        if (!checkVisibility || visibleRect.contains(vBox)) {
            // As a result of the zoom, the textfield is now on
            // screen. Place the WebTextView in its new place,
            // accounting for our new scroll/zoom values.
            mWebTextView
                    .setTextSize(
                            TypedValue.COMPLEX_UNIT_PX,
                            contentToViewDimension(nativeFocusCandidateTextSize()));
            mWebTextView.setRect(vBox.left, vBox.top, vBox.width(),
                    vBox.height());
            // If it is a password field, start drawing the
            // WebTextView once again.
            if (nativeFocusCandidateIsPassword()) {
                mWebTextView.setInPassword(true);
            }
        } else {
            // The textfield is now off screen. The user probably
            // was not zooming to see the textfield better. Remove
            // the WebTextView. If the user types a key, and the
            // textfield is still in focus, we will reconstruct
            // the WebTextView and scroll it back on screen.
            mWebTextView.remove();
        }
    }

    private void drawCoreAndCursorRing(Canvas canvas, int color,
        boolean drawCursorRing) {
        if (mDrawHistory) {
            canvas.scale(mActualScale, mActualScale);
            canvas.drawPicture(mHistoryPicture);
            return;
        }

        boolean animateZoom = mZoomScale != 0;
        boolean animateScroll = !mScroller.isFinished()
                || mVelocityTracker != null;
        if (animateZoom) {
            float zoomScale;
            int interval = (int) (SystemClock.uptimeMillis() - mZoomStart);
            if (interval < ZOOM_ANIMATION_LENGTH) {
                float ratio = (float) interval / ZOOM_ANIMATION_LENGTH;
                zoomScale = 1.0f / (mInvInitialZoomScale
                        + (mInvFinalZoomScale - mInvInitialZoomScale) * ratio);
                invalidate();
            } else {
                zoomScale = mZoomScale;
                // set mZoomScale to be 0 as we have done animation
                mZoomScale = 0;
                // call invalidate() again to draw with the final filters
                invalidate();
                if (mNeedToAdjustWebTextView) {
                    mNeedToAdjustWebTextView = false;
                    adjustTextView(true);
                }
            }
            // calculate the intermediate scroll position. As we need to use
            // zoomScale, we can't use pinLocX/Y directly. Copy the logic here.
            float scale = zoomScale * mInvInitialZoomScale;
            int tx = Math.round(scale * (mInitialScrollX + mZoomCenterX)
                    - mZoomCenterX);
            tx = -pinLoc(tx, getViewWidth(), Math.round(mContentWidth
                    * zoomScale)) + mScrollX;
            int titleHeight = getTitleHeight();
            int ty = Math.round(scale
                    * (mInitialScrollY + mZoomCenterY - titleHeight)
                    - (mZoomCenterY - titleHeight));
            ty = -(ty <= titleHeight ? Math.max(ty, 0) : pinLoc(ty
                    - titleHeight, getViewHeight(), Math.round(mContentHeight
                    * zoomScale)) + titleHeight) + mScrollY;
            canvas.translate(tx, ty);
            canvas.scale(zoomScale, zoomScale);
            if (inEditingMode() && !mNeedToAdjustWebTextView
                    && mZoomScale != 0) {
                // The WebTextView is up.  Keep track of this so we can adjust
                // its size and placement when we finish zooming
                mNeedToAdjustWebTextView = true;
                // If it is in password mode, turn it off so it does not draw
                // misplaced.
                if (nativeFocusCandidateIsPassword()) {
                    mWebTextView.setInPassword(false);
                }
            }
        } else {
            canvas.scale(mActualScale, mActualScale);
        }

        mWebViewCore.drawContentPicture(canvas, color,
                (animateZoom || mPreviewZoomOnly), animateScroll);

        if (mNativeClass == 0) return;
        if (mShiftIsPressed && !(animateZoom || mPreviewZoomOnly)) {
            if (mTouchSelection) {
                nativeDrawSelectionRegion(canvas);
            } else {
                nativeDrawSelection(canvas, mInvActualScale, getTitleHeight(),
                        mSelectX, mSelectY, mExtendSelection);
            }
        } else if (drawCursorRing) {
            if (mTouchMode == TOUCH_SHORTPRESS_START_MODE) {
                mTouchMode = TOUCH_SHORTPRESS_MODE;
                HitTestResult hitTest = getHitTestResult();
                if (hitTest != null &&
                        hitTest.mType != HitTestResult.UNKNOWN_TYPE) {
                    mPrivateHandler.sendMessageDelayed(mPrivateHandler
                            .obtainMessage(SWITCH_TO_LONGPRESS),
                            LONG_PRESS_TIMEOUT);
                }
            }
            nativeDrawCursorRing(canvas);
        }
        // When the FindDialog is up, only draw the matches if we are not in
        // the process of scrolling them into view.
        if (mFindIsUp && !animateScroll) {
            nativeDrawMatches(canvas);
        }
    }

    // draw history
    private boolean mDrawHistory = false;
    private Picture mHistoryPicture = null;
    private int mHistoryWidth = 0;
    private int mHistoryHeight = 0;

    // Only check the flag, can be called from WebCore thread
    boolean drawHistory() {
        return mDrawHistory;
    }

    // Should only be called in UI thread
    void switchOutDrawHistory() {
        if (null == mWebViewCore) return; // CallbackProxy may trigger this
        if (mDrawHistory && mWebViewCore.pictureReady()) {
            mDrawHistory = false;
            invalidate();
            int oldScrollX = mScrollX;
            int oldScrollY = mScrollY;
            mScrollX = pinLocX(mScrollX);
            mScrollY = pinLocY(mScrollY);
            if (oldScrollX != mScrollX || oldScrollY != mScrollY) {
                mUserScroll = false;
                mWebViewCore.sendMessage(EventHub.SYNC_SCROLL, oldScrollX,
                        oldScrollY);
            }
            sendOurVisibleRect();
        }
    }

    WebViewCore.CursorData cursorData() {
        WebViewCore.CursorData result = new WebViewCore.CursorData();
        result.mMoveGeneration = nativeMoveGeneration();
        result.mFrame = nativeCursorFramePointer();
        Point position = nativeCursorPosition();
        result.mX = position.x;
        result.mY = position.y;
        return result;
    }

    /**
     *  Delete text from start to end in the focused textfield. If there is no
     *  focus, or if start == end, silently fail.  If start and end are out of
     *  order, swap them.
     *  @param  start   Beginning of selection to delete.
     *  @param  end     End of selection to delete.
     */
    /* package */ void deleteSelection(int start, int end) {
        mTextGeneration++;
        WebViewCore.TextSelectionData data
                = new WebViewCore.TextSelectionData(start, end);
        mWebViewCore.sendMessage(EventHub.DELETE_SELECTION, mTextGeneration, 0,
                data);
    }

    /**
     *  Set the selection to (start, end) in the focused textfield. If start and
     *  end are out of order, swap them.
     *  @param  start   Beginning of selection.
     *  @param  end     End of selection.
     */
    /* package */ void setSelection(int start, int end) {
        mWebViewCore.sendMessage(EventHub.SET_SELECTION, start, end);
    }

    // Called by JNI when a touch event puts a textfield into focus.
    private void displaySoftKeyboard(boolean isTextView) {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);

        if (isTextView) {
            if (mWebTextView == null) return;

            imm.showSoftInput(mWebTextView, 0);
            if (mActualScale < mDefaultScale) {
                // bring it back to the default scale so that user can enter
                // text.
                mInZoomOverview = false;
                mZoomCenterX = mLastTouchX;
                mZoomCenterY = mLastTouchY;
                // do not change text wrap scale so that there is no reflow
                setNewZoomScale(mDefaultScale, false, false);
                adjustTextView(false);
            }
        }
        else { // used by plugins
            imm.showSoftInput(this, 0);
        }
    }

    // Called by WebKit to instruct the UI to hide the keyboard
    private void hideSoftKeyboard() {
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);

        imm.hideSoftInputFromWindow(this.getWindowToken(), 0);
    }

    /*
     * This method checks the current focus and cursor and potentially rebuilds
     * mWebTextView to have the appropriate properties, such as password,
     * multiline, and what text it contains.  It also removes it if necessary.
     */
    /* package */ void rebuildWebTextView() {
        // If the WebView does not have focus, do nothing until it gains focus.
        if (!hasFocus() && (null == mWebTextView || !mWebTextView.hasFocus())) {
            return;
        }
        boolean alreadyThere = inEditingMode();
        // inEditingMode can only return true if mWebTextView is non-null,
        // so we can safely call remove() if (alreadyThere)
        if (0 == mNativeClass || !nativeFocusCandidateIsTextInput()) {
            if (alreadyThere) {
                mWebTextView.remove();
            }
            return;
        }
        // At this point, we know we have found an input field, so go ahead
        // and create the WebTextView if necessary.
        if (mWebTextView == null) {
            mWebTextView = new WebTextView(mContext, WebView.this);
            // Initialize our generation number.
            mTextGeneration = 0;
        }
        mWebTextView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                contentToViewDimension(nativeFocusCandidateTextSize()));
        Rect visibleRect = new Rect();
        calcOurContentVisibleRect(visibleRect);
        // Note that sendOurVisibleRect calls viewToContent, so the coordinates
        // should be in content coordinates.
        Rect bounds = nativeFocusCandidateNodeBounds();
        if (!Rect.intersects(bounds, visibleRect)) {
            mWebTextView.bringIntoView();
        }
        String text = nativeFocusCandidateText();
        int nodePointer = nativeFocusCandidatePointer();
        if (alreadyThere && mWebTextView.isSameTextField(nodePointer)) {
            // It is possible that we have the same textfield, but it has moved,
            // i.e. In the case of opening/closing the screen.
            // In that case, we need to set the dimensions, but not the other
            // aspects.
            // We also need to restore the selection, which gets wrecked by
            // calling setTextEntryRect.
            Spannable spannable = (Spannable) mWebTextView.getText();
            int start = Selection.getSelectionStart(spannable);
            int end = Selection.getSelectionEnd(spannable);
            // If the text has been changed by webkit, update it.  However, if
            // there has been more UI text input, ignore it.  We will receive
            // another update when that text is recognized.
            if (text != null && !text.equals(spannable.toString())
                    && nativeTextGeneration() == mTextGeneration) {
                mWebTextView.setTextAndKeepSelection(text);
            } else {
                Selection.setSelection(spannable, start, end);
            }
        } else {
            Rect vBox = contentToViewRect(bounds);
            mWebTextView.setRect(vBox.left, vBox.top, vBox.width(),
                    vBox.height());
            mWebTextView.setGravity(nativeFocusCandidateIsRtlText() ?
                    Gravity.RIGHT : Gravity.NO_GRAVITY);
            // this needs to be called before update adapter thread starts to
            // ensure the mWebTextView has the same node pointer
            mWebTextView.setNodePointer(nodePointer);
            int maxLength = -1;
            boolean isTextField = nativeFocusCandidateIsTextField();
            if (isTextField) {
                maxLength = nativeFocusCandidateMaxLength();
                String name = nativeFocusCandidateName();
                if (mWebViewCore.getSettings().getSaveFormData()
                        && name != null) {
                    Message update = mPrivateHandler.obtainMessage(
                            REQUEST_FORM_DATA, nodePointer);
                    RequestFormData updater = new RequestFormData(name,
                            getUrl(), update);
                    Thread t = new Thread(updater);
                    t.start();
                }
            }
            mWebTextView.setMaxLength(maxLength);
            AutoCompleteAdapter adapter = null;
            mWebTextView.setAdapterCustom(adapter);
            mWebTextView.setSingleLine(isTextField);
            mWebTextView.setInPassword(nativeFocusCandidateIsPassword());
            if (null == text) {
                mWebTextView.setText("", 0, 0);
                if (DebugFlags.WEB_VIEW) {
                    Log.v(LOGTAG, "rebuildWebTextView null == text");
                }
            } else {
                // Change to true to enable the old style behavior, where
                // entering a textfield/textarea always set the selection to the
                // whole field.  This was desirable for the case where the user
                // intends to scroll past the field using the trackball.
                // However, it causes a problem when replying to emails - the
                // user expects the cursor to be at the beginning of the
                // textarea.  Testing out a new behavior, where textfields set
                // selection at the end, and textareas at the beginning.
                if (false) {
                    mWebTextView.setText(text, 0, text.length());
                } else if (isTextField) {
                    int length = text.length();
                    mWebTextView.setText(text, length, length);
                    if (DebugFlags.WEB_VIEW) {
                        Log.v(LOGTAG, "rebuildWebTextView length=" + length);
                    }
                } else {
                    mWebTextView.setText(text, 0, 0);
                    if (DebugFlags.WEB_VIEW) {
                        Log.v(LOGTAG, "rebuildWebTextView !isTextField");
                    }
                }
            }
            mWebTextView.requestFocus();
        }
    }

    /*
     * This class requests an Adapter for the WebTextView which shows past
     * entries stored in the database.  It is a Runnable so that it can be done
     * in its own thread, without slowing down the UI.
     */
    private class RequestFormData implements Runnable {
        private String mName;
        private String mUrl;
        private Message mUpdateMessage;

        public RequestFormData(String name, String url, Message msg) {
            mName = name;
            mUrl = url;
            mUpdateMessage = msg;
        }

        public void run() {
            ArrayList<String> pastEntries = mDatabase.getFormData(mUrl, mName);
            if (pastEntries.size() > 0) {
                AutoCompleteAdapter adapter = new
                        AutoCompleteAdapter(mContext, pastEntries);
                mUpdateMessage.obj = adapter;
                mUpdateMessage.sendToTarget();
            }
        }
    }

    // This is used to determine long press with the center key.  Does not
    // affect long press with the trackball/touch.
    private boolean mGotCenterDown = false;

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "keyDown at " + System.currentTimeMillis()
                    + ", " + event + ", unicode=" + event.getUnicodeChar());
        }

        if (mNativeClass == 0) {
            return false;
        }

        // do this hack up front, so it always works, regardless of touch-mode
        if (AUTO_REDRAW_HACK && (keyCode == KeyEvent.KEYCODE_CALL)) {
            mAutoRedraw = !mAutoRedraw;
            if (mAutoRedraw) {
                invalidate();
            }
            return true;
        }

        // Bubble up the key event if
        // 1. it is a system key; or
        // 2. the host application wants to handle it;
        if (event.isSystem()
                || mCallbackProxy.uiOverrideKeyEvent(event)) {
            return false;
        }

        if (mShiftIsPressed == false && nativeCursorWantsKeyEvents() == false
                && (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT)) {
            mExtendSelection = false;
            mShiftIsPressed = true;
            if (nativeHasCursorNode()) {
                Rect rect = nativeCursorNodeBounds();
                mSelectX = contentToViewX(rect.left);
                mSelectY = contentToViewY(rect.top);
            } else {
                mSelectX = mScrollX + (int) mLastTouchX;
                mSelectY = mScrollY + (int) mLastTouchY;
            }
            nativeHideCursor();
       }

        if (keyCode >= KeyEvent.KEYCODE_DPAD_UP
                && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            // always handle the navigation keys in the UI thread
            switchOutDrawHistory();
            if (navHandledKey(keyCode, 1, false, event.getEventTime(), false)) {
                playSoundEffect(keyCodeToSoundsEffect(keyCode));
                return true;
            }
            // Bubble up the key event as WebView doesn't handle it
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            switchOutDrawHistory();
            if (event.getRepeatCount() == 0) {
                mGotCenterDown = true;
                mPrivateHandler.sendMessageDelayed(mPrivateHandler
                        .obtainMessage(LONG_PRESS_CENTER), LONG_PRESS_TIMEOUT);
                // Already checked mNativeClass, so we do not need to check it
                // again.
                nativeRecordButtons(hasFocus() && hasWindowFocus(), true, true);
                return true;
            }
            // Bubble up the key event as WebView doesn't handle it
            return false;
        }

        if (keyCode != KeyEvent.KEYCODE_SHIFT_LEFT
                && keyCode != KeyEvent.KEYCODE_SHIFT_RIGHT) {
            // turn off copy select if a shift-key combo is pressed
            mExtendSelection = mShiftIsPressed = false;
            if (mTouchMode == TOUCH_SELECT_MODE) {
                mTouchMode = TOUCH_INIT_MODE;
            }
        }

        if (getSettings().getNavDump()) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_4:
                    // "/data/data/com.android.browser/displayTree.txt"
                    nativeDumpDisplayTree(getUrl());
                    break;
                case KeyEvent.KEYCODE_5:
                case KeyEvent.KEYCODE_6:
                    // 5: dump the dom tree to the file
                    // "/data/data/com.android.browser/domTree.txt"
                    // 6: dump the dom tree to the adb log
                    mWebViewCore.sendMessage(EventHub.DUMP_DOMTREE,
                            (keyCode == KeyEvent.KEYCODE_5) ? 1 : 0, 0);
                    break;
                case KeyEvent.KEYCODE_7:
                case KeyEvent.KEYCODE_8:
                    // 7: dump the render tree to the file
                    // "/data/data/com.android.browser/renderTree.txt"
                    // 8: dump the render tree to the adb log
                    mWebViewCore.sendMessage(EventHub.DUMP_RENDERTREE,
                            (keyCode == KeyEvent.KEYCODE_7) ? 1 : 0, 0);
                    break;
                case KeyEvent.KEYCODE_9:
                    nativeInstrumentReport();
                    return true;
            }
        }

        if (nativeCursorIsPlugin()) {
            nativeUpdatePluginReceivesEvents();
            invalidate();
        } else if (nativeCursorIsTextInput()) {
            // This message will put the node in focus, for the DOM's notion
            // of focus, and make the focuscontroller active
            mWebViewCore.sendMessage(EventHub.CLICK, nativeCursorFramePointer(),
                    nativeCursorNodePointer());
            // This will bring up the WebTextView and put it in focus, for
            // our view system's notion of focus
            rebuildWebTextView();
            // Now we need to pass the event to it
            return mWebTextView.onKeyDown(keyCode, event);
        } else if (nativeHasFocusNode()) {
            // In this case, the cursor is not on a text input, but the focus
            // might be.  Check it, and if so, hand over to the WebTextView.
            rebuildWebTextView();
            if (inEditingMode()) {
                return mWebTextView.onKeyDown(keyCode, event);
            }
        }

        // TODO: should we pass all the keys to DOM or check the meta tag
        if (nativeCursorWantsKeyEvents() || true) {
            // pass the key to DOM
            mWebViewCore.sendMessage(EventHub.KEY_DOWN, event);
            // return true as DOM handles the key
            return true;
        }

        // Bubble up the key event as WebView doesn't handle it
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "keyUp at " + System.currentTimeMillis()
                    + ", " + event + ", unicode=" + event.getUnicodeChar());
        }

        if (mNativeClass == 0) {
            return false;
        }

        // special CALL handling when cursor node's href is "tel:XXX"
        if (keyCode == KeyEvent.KEYCODE_CALL && nativeHasCursorNode()) {
            String text = nativeCursorText();
            if (!nativeCursorIsTextInput() && text != null
                    && text.startsWith(SCHEME_TEL)) {
                Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse(text));
                getContext().startActivity(intent);
                return true;
            }
        }

        // Bubble up the key event if
        // 1. it is a system key; or
        // 2. the host application wants to handle it;
        if (event.isSystem() || mCallbackProxy.uiOverrideKeyEvent(event)) {
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT
                || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (commitCopy()) {
                return true;
            }
        }

        if (keyCode >= KeyEvent.KEYCODE_DPAD_UP
                && keyCode <= KeyEvent.KEYCODE_DPAD_RIGHT) {
            // always handle the navigation keys in the UI thread
            // Bubble up the key event as WebView doesn't handle it
            return false;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            // remove the long press message first
            mPrivateHandler.removeMessages(LONG_PRESS_CENTER);
            mGotCenterDown = false;

            if (mShiftIsPressed) {
                return false;
            }

            // perform the single click
            Rect visibleRect = sendOurVisibleRect();
            // Note that sendOurVisibleRect calls viewToContent, so the
            // coordinates should be in content coordinates.
            if (!nativeCursorIntersects(visibleRect)) {
                return false;
            }
            nativeSetFollowedLink(true);
            nativeUpdatePluginReceivesEvents();
            WebViewCore.CursorData data = cursorData();
            mWebViewCore.sendMessage(EventHub.SET_MOVE_MOUSE, data);
            playSoundEffect(SoundEffectConstants.CLICK);
            boolean isTextInput = nativeCursorIsTextInput();
            if (isTextInput || !mCallbackProxy.uiOverrideUrlLoading(
                        nativeCursorText())) {
                mWebViewCore.sendMessage(EventHub.CLICK, data.mFrame,
                        nativeCursorNodePointer());
            }
            if (isTextInput) {
                rebuildWebTextView();
                displaySoftKeyboard(true);
            }
            return true;
        }

        // TODO: should we pass all the keys to DOM or check the meta tag
        if (nativeCursorWantsKeyEvents() || true) {
            // pass the key to DOM
            mWebViewCore.sendMessage(EventHub.KEY_UP, event);
            // return true as DOM handles the key
            return true;
        }

        // Bubble up the key event as WebView doesn't handle it
        return false;
    }

    /**
     * @hide
     */
    public void emulateShiftHeld() {
        if (0 == mNativeClass) return; // client isn't initialized
        mExtendSelection = false;
        mShiftIsPressed = true;
        nativeHideCursor();
    }

    private boolean commitCopy() {
        boolean copiedSomething = false;
        if (mExtendSelection) {
            // copy region so core operates on copy without touching orig.
            Region selection = new Region(nativeGetSelection());
            if (selection.isEmpty() == false) {
                Toast.makeText(mContext
                        , com.android.internal.R.string.text_copied
                        , Toast.LENGTH_SHORT).show();
                mWebViewCore.sendMessage(EventHub.GET_SELECTION, selection);
                copiedSomething = true;
            }
            mExtendSelection = false;
        }
        mShiftIsPressed = false;
        if (mTouchMode == TOUCH_SELECT_MODE) {
            mTouchMode = TOUCH_INIT_MODE;
        }
        return copiedSomething;
    }

    // Set this as a hierarchy change listener so we can know when this view
    // is removed and still have access to our parent.
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) parent;
            p.setOnHierarchyChangeListener(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        ViewParent parent = getParent();
        if (parent instanceof ViewGroup) {
            ViewGroup p = (ViewGroup) parent;
            p.setOnHierarchyChangeListener(null);
        }

        // Clean up the zoom controller
        mZoomButtonsController.setVisible(false);
    }

    // Implementation for OnHierarchyChangeListener
    public void onChildViewAdded(View parent, View child) {}

    public void onChildViewRemoved(View p, View child) {
        if (child == this) {
            clearTextEntry();
        }
    }

    /**
     * @deprecated WebView should not have implemented
     * ViewTreeObserver.OnGlobalFocusChangeListener.  This method
     * does nothing now.
     */
    @Deprecated
    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
    }

    // To avoid drawing the cursor ring, and remove the TextView when our window
    // loses focus.
    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        if (hasWindowFocus) {
            if (hasFocus()) {
                // If our window regained focus, and we have focus, then begin
                // drawing the cursor ring
                mDrawCursorRing = true;
                if (mNativeClass != 0) {
                    nativeRecordButtons(true, false, true);
                    if (inEditingMode()) {
                        mWebViewCore.sendMessage(EventHub.SET_ACTIVE, 1, 0);
                    }
                }
            } else {
                // If our window gained focus, but we do not have it, do not
                // draw the cursor ring.
                mDrawCursorRing = false;
                // We do not call nativeRecordButtons here because we assume
                // that when we lost focus, or window focus, it got called with
                // false for the first parameter
            }
        } else {
            if (getSettings().getBuiltInZoomControls() && !mZoomButtonsController.isVisible()) {
                /*
                 * The zoom controls come in their own window, so our window
                 * loses focus. Our policy is to not draw the cursor ring if
                 * our window is not focused, but this is an exception since
                 * the user can still navigate the web page with the zoom
                 * controls showing.
                 */
                // If our window has lost focus, stop drawing the cursor ring
                mDrawCursorRing = false;
            }
            mGotKeyDown = false;
            mShiftIsPressed = false;
            if (mNativeClass != 0) {
                nativeRecordButtons(false, false, true);
            }
            setFocusControllerInactive();
        }
        invalidate();
        super.onWindowFocusChanged(hasWindowFocus);
    }

    /*
     * Pass a message to WebCore Thread, telling the WebCore::Page's
     * FocusController to be  "inactive" so that it will
     * not draw the blinking cursor.  It gets set to "active" to draw the cursor
     * in WebViewCore.cpp, when the WebCore thread receives key events/clicks.
     */
    /* package */ void setFocusControllerInactive() {
        // Do not need to also check whether mWebViewCore is null, because
        // mNativeClass is only set if mWebViewCore is non null
        if (mNativeClass == 0) return;
        mWebViewCore.sendMessage(EventHub.SET_ACTIVE, 0, 0);
    }

    @Override
    protected void onFocusChanged(boolean focused, int direction,
            Rect previouslyFocusedRect) {
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "MT focusChanged " + focused + ", " + direction);
        }
        if (focused) {
            // When we regain focus, if we have window focus, resume drawing
            // the cursor ring
            if (hasWindowFocus()) {
                mDrawCursorRing = true;
                if (mNativeClass != 0) {
                    nativeRecordButtons(true, false, true);
                }
            //} else {
                // The WebView has gained focus while we do not have
                // windowfocus.  When our window lost focus, we should have
                // called nativeRecordButtons(false...)
            }
        } else {
            // When we lost focus, unless focus went to the TextView (which is
            // true if we are in editing mode), stop drawing the cursor ring.
            if (!inEditingMode()) {
                mDrawCursorRing = false;
                if (mNativeClass != 0) {
                    nativeRecordButtons(false, false, true);
                }
                setFocusControllerInactive();
            }
            mGotKeyDown = false;
        }

        super.onFocusChanged(focused, direction, previouslyFocusedRect);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        // Center zooming to the center of the screen.
        if (mZoomScale == 0) { // unless we're already zooming
            mZoomCenterX = getViewWidth() * .5f;
            mZoomCenterY = getViewHeight() * .5f;
            mAnchorX = viewToContentX((int) mZoomCenterX + mScrollX);
            mAnchorY = viewToContentY((int) mZoomCenterY + mScrollY);
        }

        // update mMinZoomScale if the minimum zoom scale is not fixed
        if (!mMinZoomScaleFixed) {
            // when change from narrow screen to wide screen, the new viewWidth
            // can be wider than the old content width. We limit the minimum
            // scale to 1.0f. The proper minimum scale will be calculated when
            // the new picture shows up.
            mMinZoomScale = Math.min(1.0f, (float) getViewWidth()
                    / (mDrawHistory ? mHistoryPicture.getWidth()
                            : mZoomOverviewWidth));
            if (mInitialScaleInPercent > 0) {
                // limit the minZoomScale to the initialScale if it is set
                float initialScale = mInitialScaleInPercent / 100.0f;
                if (mMinZoomScale > initialScale) {
                    mMinZoomScale = initialScale;
                }
            }
        }

        // we always force, in case our height changed, in which case we still
        // want to send the notification over to webkit
        // only update the text wrap scale if width changed.
        setNewZoomScale(mActualScale, w != ow, true);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);

        sendOurVisibleRect();
    }


    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean dispatch = true;

        if (!inEditingMode()) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                mGotKeyDown = true;
            } else {
                if (!mGotKeyDown) {
                    /*
                     * We got a key up for which we were not the recipient of
                     * the original key down. Don't give it to the view.
                     */
                    dispatch = false;
                }
                mGotKeyDown = false;
            }
        }

        if (dispatch) {
            return super.dispatchKeyEvent(event);
        } else {
            // We didn't dispatch, so let something else handle the key
            return false;
        }
    }

    // Here are the snap align logic:
    // 1. If it starts nearly horizontally or vertically, snap align;
    // 2. If there is a dramitic direction change, let it go;
    // 3. If there is a same direction back and forth, lock it.

    // adjustable parameters
    private int mMinLockSnapReverseDistance;
    private static final float MAX_SLOPE_FOR_DIAG = 1.5f;
    private static final int MIN_BREAK_SNAP_CROSS_DISTANCE = 80;

    private class ScaleDetectorListener implements
            ScaleGestureDetector.OnScaleGestureListener {

        public boolean onScaleBegin(ScaleGestureDetector detector) {
            // cancel the single touch handling
            cancelTouch();
            if (mZoomButtonsController.isVisible()) {
                mZoomButtonsController.setVisible(false);
            }
            // reset the zoom overview mode so that the page won't auto grow
            mInZoomOverview = false;
            // If it is in password mode, turn it off so it does not draw
            // misplaced.
            if (inEditingMode() && nativeFocusCandidateIsPassword()) {
                mWebTextView.setInPassword(false);
            }
            return true;
        }

        public void onScaleEnd(ScaleGestureDetector detector) {
            if (mPreviewZoomOnly) {
                mPreviewZoomOnly = false;
                mAnchorX = viewToContentX((int) mZoomCenterX + mScrollX);
                mAnchorY = viewToContentY((int) mZoomCenterY + mScrollY);
                // don't reflow when zoom in; when zoom out, do reflow if the
                // new scale is almost minimum scale;
                boolean reflowNow = (mActualScale - mMinZoomScale <= 0.01f)
                        || ((mActualScale <= 0.8 * mTextWrapScale));
                // force zoom after mPreviewZoomOnly is set to false so that the
                // new view size will be passed to the WebKit
                setNewZoomScale(mActualScale, reflowNow, true);
                // call invalidate() to draw without zoom filter
                invalidate();
            }
            // adjust the edit text view if needed
            if (inEditingMode()) {
                adjustTextView(true);
            }
            // start a drag, TOUCH_PINCH_DRAG, can't use TOUCH_INIT_MODE as it
            // may trigger the unwanted click, can't use TOUCH_DRAG_MODE as it
            // may trigger the unwanted fling.
            mTouchMode = TOUCH_PINCH_DRAG;
            startTouch(detector.getFocusX(), detector.getFocusY(),
                    mLastTouchTime);
        }

        public boolean onScale(ScaleGestureDetector detector) {
            float scale = (float) (Math.round(detector.getScaleFactor()
                    * mActualScale * 100) / 100.0);
            if (Math.abs(scale - mActualScale) >= PREVIEW_SCALE_INCREMENT) {
                mPreviewZoomOnly = true;
                // limit the scale change per step
                if (scale > mActualScale) {
                    scale = Math.min(scale, mActualScale * 1.25f);
                } else {
                    scale = Math.max(scale, mActualScale * 0.8f);
                }
                mZoomCenterX = detector.getFocusX();
                mZoomCenterY = detector.getFocusY();
                setNewZoomScale(scale, false, false);
                invalidate();
                return true;
            }
            return false;
        }
    }

    // if the page can scroll <= this value, we won't allow the drag tracker
    // to have any effect.
    private static final int MIN_SCROLL_AMOUNT_TO_DISABLE_DRAG_TRACKER = 4;

    private class DragTrackerHandler {
        private final DragTracker mProxy;
        private final float mStartY, mStartX;
        private final float mMinDY, mMinDX;
        private final float mMaxDY, mMaxDX;
        private float mCurrStretchY, mCurrStretchX;
        private int mSX, mSY;

        public DragTrackerHandler(float x, float y, DragTracker proxy) {
            mProxy = proxy;

            int docBottom = computeVerticalScrollRange() + getTitleHeight();
            int viewTop = getScrollY();
            int viewBottom = viewTop + getHeight();

            mStartY = y;
            mMinDY = -viewTop;
            mMaxDY = docBottom - viewBottom;

            if (DebugFlags.DRAG_TRACKER || DEBUG_DRAG_TRACKER) {
                Log.d(DebugFlags.DRAG_TRACKER_LOGTAG, " dragtracker y= " + y +
                      " up/down= " + mMinDY + " " + mMaxDY);
            }

            int docRight = computeHorizontalScrollRange();
            int viewLeft = getScrollX();
            int viewRight = viewLeft + getWidth();
            mStartX = x;
            mMinDX = -viewLeft;
            mMaxDX = docRight - viewRight;

            mProxy.onStartDrag(x, y);

            // ensure we buildBitmap at least once
            mSX = -99999;
        }

        private float computeStretch(float delta, float min, float max) {
            float stretch = 0;
            if (max - min > MIN_SCROLL_AMOUNT_TO_DISABLE_DRAG_TRACKER) {
                if (delta < min) {
                    stretch = delta - min;
                } else if (delta > max) {
                    stretch = delta - max;
                }
            }
            return stretch;
        }

        public void dragTo(float x, float y) {
            float sy = computeStretch(mStartY - y, mMinDY, mMaxDY);
            float sx = computeStretch(mStartX - x, mMinDX, mMaxDX);

            if (mCurrStretchX != sx || mCurrStretchY != sy) {
                mCurrStretchX = sx;
                mCurrStretchY = sy;
                if (DebugFlags.DRAG_TRACKER || DEBUG_DRAG_TRACKER) {
                    Log.d(DebugFlags.DRAG_TRACKER_LOGTAG, "---- stretch " + sx +
                          " " + sy);
                }
                if (mProxy.onStretchChange(sx, sy)) {
                    invalidate();
                }
            }
        }

        public void stopDrag() {
            if (DebugFlags.DRAG_TRACKER || DEBUG_DRAG_TRACKER) {
                Log.d(DebugFlags.DRAG_TRACKER_LOGTAG, "----- stopDrag");
            }
            mProxy.onStopDrag();
        }

        private int hiddenHeightOfTitleBar() {
            return getTitleHeight() - getVisibleTitleHeight();
        }

        // need a way to know if 565 or 8888 is the right config for
        // capturing the display and giving it to the drag proxy
        private Bitmap.Config offscreenBitmapConfig() {
            // hard code 565 for now
            return Bitmap.Config.RGB_565;
        }

        /*  If the tracker draws, then this returns true, otherwise it will
         return false, and draw nothing.
         */
        public boolean draw(Canvas canvas) {
            if (mCurrStretchX != 0 || mCurrStretchY != 0) {
                int sx = getScrollX();
                int sy = getScrollY() - hiddenHeightOfTitleBar();

                if (mSX != sx || mSY != sy) {
                    buildBitmap(sx, sy);
                    mSX = sx;
                    mSY = sy;
                }

                int count = canvas.save(Canvas.MATRIX_SAVE_FLAG);
                canvas.translate(sx, sy);
                mProxy.onDraw(canvas);
                canvas.restoreToCount(count);
                return true;
            }
            if (DebugFlags.DRAG_TRACKER || DEBUG_DRAG_TRACKER) {
                Log.d(DebugFlags.DRAG_TRACKER_LOGTAG, " -- draw false " +
                      mCurrStretchX + " " + mCurrStretchY);
            }
            return false;
        }

        private void buildBitmap(int sx, int sy) {
            int w = getWidth();
            int h = getViewHeight();
            Bitmap bm = Bitmap.createBitmap(w, h, offscreenBitmapConfig());
            Canvas canvas = new Canvas(bm);
            canvas.translate(-sx, -sy);
            drawContent(canvas);

            if (DebugFlags.DRAG_TRACKER || DEBUG_DRAG_TRACKER) {
                Log.d(DebugFlags.DRAG_TRACKER_LOGTAG, "--- buildBitmap " + sx +
                      " " + sy + " " + w + " " + h);
            }
            mProxy.onBitmapChange(bm);
        }
    }

    /** @hide */
    public static class DragTracker {
        public void onStartDrag(float x, float y) {}
        public boolean onStretchChange(float sx, float sy) {
            // return true to have us inval the view
            return false;
        }
        public void onStopDrag() {}
        public void onBitmapChange(Bitmap bm) {}
        public void onDraw(Canvas canvas) {}
    }

    /** @hide */
    public DragTracker getDragTracker() {
        return mDragTracker;
    }

    /** @hide */
    public void setDragTracker(DragTracker tracker) {
        mDragTracker = tracker;
    }

    private DragTracker mDragTracker;
    private DragTrackerHandler mDragTrackerHandler;

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (mNativeClass == 0 || !isClickable() || !isLongClickable()) {
            return false;
        }

        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, ev + " at " + ev.getEventTime() + " mTouchMode="
                    + mTouchMode);
        }

        int action;
        float x, y;
        long eventTime = ev.getEventTime();

        // FIXME: we may consider to give WebKit an option to handle multi-touch
        // events later.
        if (mSupportMultiTouch && mMinZoomScale < mMaxZoomScale
                && ev.getPointerCount() > 1) {
            mScaleDetector.onTouchEvent(ev);
            if (mScaleDetector.isInProgress()) {
                mLastTouchTime = eventTime;
                return true;
            }
            x = mScaleDetector.getFocusX();
            y = mScaleDetector.getFocusY();
            action = ev.getAction() & MotionEvent.ACTION_MASK;
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                cancelTouch();
                action = MotionEvent.ACTION_DOWN;
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                // set mLastTouchX/Y to the remaining point
                mLastTouchX = x;
                mLastTouchY = y;
            } else if (action == MotionEvent.ACTION_MOVE) {
                // negative x or y indicate it is on the edge, skip it.
                if (x < 0 || y < 0) {
                    return true;
                }
            }
        } else {
            action = ev.getAction();
            x = ev.getX();
            y = ev.getY();
        }

        // Due to the touch screen edge effect, a touch closer to the edge
        // always snapped to the edge. As getViewWidth() can be different from
        // getWidth() due to the scrollbar, adjusting the point to match
        // getViewWidth(). Same applied to the height.
        if (x > getViewWidth() - 1) {
            x = getViewWidth() - 1;
        }
        if (y > getViewHeightWithTitle() - 1) {
            y = getViewHeightWithTitle() - 1;
        }

        // pass the touch events from UI thread to WebCore thread
        if (mForwardTouchEvents && (action != MotionEvent.ACTION_MOVE
                || eventTime - mLastSentTouchTime > TOUCH_SENT_INTERVAL)) {
            WebViewCore.TouchEventData ted = new WebViewCore.TouchEventData();
            ted.mAction = action;
            ted.mX = viewToContentX((int) x + mScrollX);
            ted.mY = viewToContentY((int) y + mScrollY);
            mWebViewCore.sendMessage(EventHub.TOUCH_EVENT, ted);
            mLastSentTouchTime = eventTime;
        }

        int deltaX = (int) (mLastTouchX - x);
        int deltaY = (int) (mLastTouchY - y);

        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                mPreventDrag = PREVENT_DRAG_NO;
                if (!mScroller.isFinished()) {
                    // stop the current scroll animation, but if this is
                    // the start of a fling, allow it to add to the current
                    // fling's velocity
                    mScroller.abortAnimation();
                    mTouchMode = TOUCH_DRAG_START_MODE;
                    mPrivateHandler.removeMessages(RESUME_WEBCORE_UPDATE);
                } else if (mShiftIsPressed) {
                    mSelectX = mScrollX + (int) x;
                    mSelectY = mScrollY + (int) y;
                    mTouchMode = TOUCH_SELECT_MODE;
                    if (DebugFlags.WEB_VIEW) {
                        Log.v(LOGTAG, "select=" + mSelectX + "," + mSelectY);
                    }
                    nativeMoveSelection(viewToContentX(mSelectX),
                            viewToContentY(mSelectY), false);
                    mTouchSelection = mExtendSelection = true;
                } else if (mPrivateHandler.hasMessages(RELEASE_SINGLE_TAP)) {
                    mPrivateHandler.removeMessages(RELEASE_SINGLE_TAP);
                    if (deltaX * deltaX + deltaY * deltaY < mDoubleTapSlopSquare) {
                        mTouchMode = TOUCH_DOUBLE_TAP_MODE;
                    } else {
                        // commit the short press action for the previous tap
                        doShortPress();
                        // continue, mTouchMode should be still TOUCH_INIT_MODE
                    }
                } else {
                    mPreviewZoomOnly = false;
                    mTouchMode = TOUCH_INIT_MODE;
                    mPreventDrag = mForwardTouchEvents ? PREVENT_DRAG_MAYBE_YES
                            : PREVENT_DRAG_NO;
                    mWebViewCore.sendMessage(
                            EventHub.UPDATE_FRAME_CACHE_IF_LOADING);
                    if (mLogEvent && eventTime - mLastTouchUpTime < 1000) {
                        EventLog.writeEvent(EVENT_LOG_DOUBLE_TAP_DURATION,
                                (eventTime - mLastTouchUpTime), eventTime);
                    }
                }
                // Trigger the link
                if (mTouchMode == TOUCH_INIT_MODE
                        || mTouchMode == TOUCH_DOUBLE_TAP_MODE) {
                    mPrivateHandler.sendMessageDelayed(mPrivateHandler
                            .obtainMessage(SWITCH_TO_SHORTPRESS), TAP_TIMEOUT);
                }
                // Remember where the motion event started
                startTouch(x, y, eventTime);
                if (mDragTracker != null) {
                    mDragTrackerHandler = new DragTrackerHandler(x, y,
                                                                 mDragTracker);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (mTouchMode == TOUCH_DONE_MODE) {
                    // no dragging during scroll zoom animation
                    break;
                }
                mVelocityTracker.addMovement(ev);

                if (mTouchMode != TOUCH_DRAG_MODE) {
                    if (mTouchMode == TOUCH_SELECT_MODE) {
                        mSelectX = mScrollX + (int) x;
                        mSelectY = mScrollY + (int) y;
                        if (DebugFlags.WEB_VIEW) {
                            Log.v(LOGTAG, "xtend=" + mSelectX + "," + mSelectY);
                        }
                        nativeMoveSelection(viewToContentX(mSelectX),
                               viewToContentY(mSelectY), true);
                        invalidate();
                        break;
                    }
                    if ((deltaX * deltaX + deltaY * deltaY) < mTouchSlopSquare) {
                        break;
                    }
                    if (mPreventDrag == PREVENT_DRAG_MAYBE_YES) {
                        // track mLastTouchTime as we may need to do fling at
                        // ACTION_UP
                        mLastTouchTime = eventTime;
                        break;
                    }
                    if (mTouchMode == TOUCH_SHORTPRESS_MODE
                            || mTouchMode == TOUCH_SHORTPRESS_START_MODE) {
                        mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
                    } else if (mTouchMode == TOUCH_INIT_MODE
                            || mTouchMode == TOUCH_DOUBLE_TAP_MODE) {
                        mPrivateHandler.removeMessages(SWITCH_TO_SHORTPRESS);
                    }

                    // if it starts nearly horizontal or vertical, enforce it
                    int ax = Math.abs(deltaX);
                    int ay = Math.abs(deltaY);
                    if (ax > MAX_SLOPE_FOR_DIAG * ay) {
                        mSnapScrollMode = SNAP_X;
                        mSnapPositive = deltaX > 0;
                    } else if (ay > MAX_SLOPE_FOR_DIAG * ax) {
                        mSnapScrollMode = SNAP_Y;
                        mSnapPositive = deltaY > 0;
                    }

                    mTouchMode = TOUCH_DRAG_MODE;
                    WebViewCore.pauseUpdate(mWebViewCore);
                    if (!mDragFromTextInput) {
                        nativeHideCursor();
                    }
                    WebSettings settings = getSettings();
                    if (settings.supportZoom()
                            && settings.getBuiltInZoomControls()
                            && !mZoomButtonsController.isVisible()
                            && mMinZoomScale < mMaxZoomScale) {
                        mZoomButtonsController.setVisible(true);
                        int count = settings.getDoubleTapToastCount();
                        if (mInZoomOverview && count > 0) {
                            settings.setDoubleTapToastCount(--count);
                            Toast.makeText(mContext,
                                    com.android.internal.R.string.double_tap_toast,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                }

                // do pan
                int newScrollX = pinLocX(mScrollX + deltaX);
                deltaX = newScrollX - mScrollX;
                int newScrollY = pinLocY(mScrollY + deltaY);
                deltaY = newScrollY - mScrollY;
                boolean done = false;
                if (deltaX == 0 && deltaY == 0) {
                    done = true;
                } else {
                    if (mSnapScrollMode == SNAP_X || mSnapScrollMode == SNAP_Y) {
                        int ax = Math.abs(deltaX);
                        int ay = Math.abs(deltaY);
                        if (mSnapScrollMode == SNAP_X) {
                            // radical change means getting out of snap mode
                            if (ay > MAX_SLOPE_FOR_DIAG * ax
                                    && ay > MIN_BREAK_SNAP_CROSS_DISTANCE) {
                                mSnapScrollMode = SNAP_NONE;
                            }
                            // reverse direction means lock in the snap mode
                            if ((ax > MAX_SLOPE_FOR_DIAG * ay) &&
                                    ((mSnapPositive &&
                                    deltaX < -mMinLockSnapReverseDistance)
                                    || (!mSnapPositive &&
                                    deltaX > mMinLockSnapReverseDistance))) {
                                mSnapScrollMode = SNAP_X_LOCK;
                            }
                        } else {
                            // radical change means getting out of snap mode
                            if ((ax > MAX_SLOPE_FOR_DIAG * ay)
                                    && ax > MIN_BREAK_SNAP_CROSS_DISTANCE) {
                                mSnapScrollMode = SNAP_NONE;
                            }
                            // reverse direction means lock in the snap mode
                            if ((ay > MAX_SLOPE_FOR_DIAG * ax) &&
                                    ((mSnapPositive &&
                                    deltaY < -mMinLockSnapReverseDistance)
                                    || (!mSnapPositive &&
                                    deltaY > mMinLockSnapReverseDistance))) {
                                mSnapScrollMode = SNAP_Y_LOCK;
                            }
                        }
                    }

                    if (mSnapScrollMode == SNAP_X
                            || mSnapScrollMode == SNAP_X_LOCK) {
                        if (deltaX == 0) {
                            // keep the scrollbar on the screen even there is no
                            // scroll
                            awakenScrollBars(ViewConfiguration
                                    .getScrollDefaultDelay(), false);
                        } else {
                            scrollBy(deltaX, 0);
                        }
                        mLastTouchX = x;
                    } else if (mSnapScrollMode == SNAP_Y
                            || mSnapScrollMode == SNAP_Y_LOCK) {
                        if (deltaY == 0) {
                            // keep the scrollbar on the screen even there is no
                            // scroll
                            awakenScrollBars(ViewConfiguration
                                    .getScrollDefaultDelay(), false);
                        } else {
                            scrollBy(0, deltaY);
                        }
                        mLastTouchY = y;
                    } else {
                        scrollBy(deltaX, deltaY);
                        mLastTouchX = x;
                        mLastTouchY = y;
                    }
                    mLastTouchTime = eventTime;
                    mUserScroll = true;
                }

                if (!getSettings().getBuiltInZoomControls()) {
                    boolean showPlusMinus = mMinZoomScale < mMaxZoomScale;
                    if (mZoomControls != null && showPlusMinus) {
                        if (mZoomControls.getVisibility() == View.VISIBLE) {
                            mPrivateHandler.removeCallbacks(mZoomControlRunnable);
                        } else {
                            mZoomControls.show(showPlusMinus, false);
                        }
                        mPrivateHandler.postDelayed(mZoomControlRunnable,
                                ZOOM_CONTROLS_TIMEOUT);
                    }
                }

                if (mDragTrackerHandler != null) {
                    mDragTrackerHandler.dragTo(x, y);
                }

                if (done) {
                    // keep the scrollbar on the screen even there is no scroll
                    awakenScrollBars(ViewConfiguration.getScrollDefaultDelay(),
                            false);
                    // return false to indicate that we can't pan out of the
                    // view space
                    return false;
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                if (mDragTrackerHandler != null) {
                    mDragTrackerHandler.stopDrag();
                    mDragTrackerHandler = null;
                }
                mLastTouchUpTime = eventTime;
                switch (mTouchMode) {
                    case TOUCH_DOUBLE_TAP_MODE: // double tap
                        mPrivateHandler.removeMessages(SWITCH_TO_SHORTPRESS);
                        mTouchMode = TOUCH_DONE_MODE;
                        doDoubleTap();
                        break;
                    case TOUCH_SELECT_MODE:
                        commitCopy();
                        mTouchSelection = false;
                        break;
                    case TOUCH_INIT_MODE: // tap
                    case TOUCH_SHORTPRESS_START_MODE:
                    case TOUCH_SHORTPRESS_MODE:
                        mPrivateHandler.removeMessages(SWITCH_TO_SHORTPRESS);
                        mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
                        if ((deltaX * deltaX + deltaY * deltaY) > mTouchSlopSquare) {
                            Log.w(LOGTAG, "Miss a drag as we are waiting for" +
                                    " WebCore's response for touch down.");
                            if (computeHorizontalScrollExtent() < computeHorizontalScrollRange()
                                    || computeVerticalScrollExtent() < computeVerticalScrollRange()) {
                                // we will not rewrite drag code here, but we
                                // will try fling if it applies.
                                WebViewCore.pauseUpdate(mWebViewCore);
                                // fall through to TOUCH_DRAG_MODE
                            } else {
                                break;
                            }
                        } else {
                            if (mPreventDrag == PREVENT_DRAG_MAYBE_YES) {
                                // if mPreventDrag is not confirmed, treat it as
                                // no so that it won't block tap or double tap.
                                mPreventDrag = PREVENT_DRAG_NO;
                            }
                            if (mPreventDrag == PREVENT_DRAG_NO) {
                                if (mTouchMode == TOUCH_INIT_MODE) {
                                    mPrivateHandler.sendMessageDelayed(
                                            mPrivateHandler.obtainMessage(
                                            RELEASE_SINGLE_TAP),
                                            ViewConfiguration.getDoubleTapTimeout());
                                } else {
                                    mTouchMode = TOUCH_DONE_MODE;
                                    doShortPress();
                                }
                            }
                            break;
                        }
                    case TOUCH_DRAG_MODE:
                        // redraw in high-quality, as we're done dragging
                        invalidate();
                        // if the user waits a while w/o moving before the
                        // up, we don't want to do a fling
                        if (eventTime - mLastTouchTime <= MIN_FLING_TIME) {
                            mVelocityTracker.addMovement(ev);
                            doFling();
                            break;
                        }
                        mLastVelocity = 0;
                        WebViewCore.resumeUpdate(mWebViewCore);
                        break;
                    case TOUCH_DRAG_START_MODE:
                    case TOUCH_DONE_MODE:
                        // do nothing
                        break;
                }
                // we also use mVelocityTracker == null to tell us that we are
                // not "moving around", so we can take the slower/prettier
                // mode in the drawing code
                if (mVelocityTracker != null) {
                    mVelocityTracker.recycle();
                    mVelocityTracker = null;
                }
                break;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (mDragTrackerHandler != null) {
                    mDragTrackerHandler.stopDrag();
                    mDragTrackerHandler = null;
                }
                cancelTouch();
                break;
            }
        }
        return true;
    }

    private void startTouch(float x, float y, long eventTime) {
        mLastTouchX = x;
        mLastTouchY = y;
        mLastTouchTime = eventTime;
        mVelocityTracker = VelocityTracker.obtain();
        mSnapScrollMode = SNAP_NONE;
    }

    private void cancelTouch() {
        // we also use mVelocityTracker == null to tell us that we are not
        // "moving around", so we can take the slower/prettier mode in the
        // drawing code
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
        if (mTouchMode == TOUCH_DRAG_MODE) {
            WebViewCore.resumeUpdate(mWebViewCore);
        }
        mPrivateHandler.removeMessages(SWITCH_TO_SHORTPRESS);
        mPrivateHandler.removeMessages(SWITCH_TO_LONGPRESS);
        mTouchMode = TOUCH_DONE_MODE;
        nativeHideCursor();
    }

    private long mTrackballFirstTime = 0;
    private long mTrackballLastTime = 0;
    private float mTrackballRemainsX = 0.0f;
    private float mTrackballRemainsY = 0.0f;
    private int mTrackballXMove = 0;
    private int mTrackballYMove = 0;
    private boolean mExtendSelection = false;
    private boolean mTouchSelection = false;
    private static final int TRACKBALL_KEY_TIMEOUT = 1000;
    private static final int TRACKBALL_TIMEOUT = 200;
    private static final int TRACKBALL_WAIT = 100;
    private static final int TRACKBALL_SCALE = 400;
    private static final int TRACKBALL_SCROLL_COUNT = 5;
    private static final int TRACKBALL_MOVE_COUNT = 10;
    private static final int TRACKBALL_MULTIPLIER = 3;
    private static final int SELECT_CURSOR_OFFSET = 16;
    private int mSelectX = 0;
    private int mSelectY = 0;
    private boolean mShiftIsPressed = false;
    private boolean mTrackballDown = false;
    private long mTrackballUpTime = 0;
    private long mLastCursorTime = 0;
    private Rect mLastCursorBounds;

    // Set by default; BrowserActivity clears to interpret trackball data
    // directly for movement. Currently, the framework only passes
    // arrow key events, not trackball events, from one child to the next
    private boolean mMapTrackballToArrowKeys = true;

    public void setMapTrackballToArrowKeys(boolean setMap) {
        mMapTrackballToArrowKeys = setMap;
    }

    void resetTrackballTime() {
        mTrackballLastTime = 0;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent ev) {
        long time = ev.getEventTime();
        if ((ev.getMetaState() & KeyEvent.META_ALT_ON) != 0) {
            if (ev.getY() > 0) pageDown(true);
            if (ev.getY() < 0) pageUp(true);
            return true;
        }
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            if (mShiftIsPressed) {
                return true; // discard press if copy in progress
            }
            mTrackballDown = true;
            if (mNativeClass == 0) {
                return false;
            }
            nativeRecordButtons(hasFocus() && hasWindowFocus(), true, true);
            if (time - mLastCursorTime <= TRACKBALL_TIMEOUT
                    && !mLastCursorBounds.equals(nativeGetCursorRingBounds())) {
                nativeSelectBestAt(mLastCursorBounds);
            }
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "onTrackballEvent down ev=" + ev
                        + " time=" + time
                        + " mLastCursorTime=" + mLastCursorTime);
            }
            if (isInTouchMode()) requestFocusFromTouch();
            return false; // let common code in onKeyDown at it
        }
        if (ev.getAction() == MotionEvent.ACTION_UP) {
            // LONG_PRESS_CENTER is set in common onKeyDown
            mPrivateHandler.removeMessages(LONG_PRESS_CENTER);
            mTrackballDown = false;
            mTrackballUpTime = time;
            if (mShiftIsPressed) {
                if (mExtendSelection) {
                    commitCopy();
                } else {
                    mExtendSelection = true;
                }
                return true; // discard press if copy in progress
            }
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "onTrackballEvent up ev=" + ev
                        + " time=" + time
                );
            }
            return false; // let common code in onKeyUp at it
        }
        if (mMapTrackballToArrowKeys && mShiftIsPressed == false) {
            if (DebugFlags.WEB_VIEW) Log.v(LOGTAG, "onTrackballEvent gmail quit");
            return false;
        }
        if (mTrackballDown) {
            if (DebugFlags.WEB_VIEW) Log.v(LOGTAG, "onTrackballEvent down quit");
            return true; // discard move if trackball is down
        }
        if (time - mTrackballUpTime < TRACKBALL_TIMEOUT) {
            if (DebugFlags.WEB_VIEW) Log.v(LOGTAG, "onTrackballEvent up timeout quit");
            return true;
        }
        // TODO: alternatively we can do panning as touch does
        switchOutDrawHistory();
        if (time - mTrackballLastTime > TRACKBALL_TIMEOUT) {
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "onTrackballEvent time="
                        + time + " last=" + mTrackballLastTime);
            }
            mTrackballFirstTime = time;
            mTrackballXMove = mTrackballYMove = 0;
        }
        mTrackballLastTime = time;
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "onTrackballEvent ev=" + ev + " time=" + time);
        }
        mTrackballRemainsX += ev.getX();
        mTrackballRemainsY += ev.getY();
        doTrackball(time);
        return true;
    }

    void moveSelection(float xRate, float yRate) {
        if (mNativeClass == 0)
            return;
        int width = getViewWidth();
        int height = getViewHeight();
        mSelectX += scaleTrackballX(xRate, width);
        mSelectY += scaleTrackballY(yRate, height);
        int maxX = width + mScrollX;
        int maxY = height + mScrollY;
        mSelectX = Math.min(maxX, Math.max(mScrollX - SELECT_CURSOR_OFFSET
                , mSelectX));
        mSelectY = Math.min(maxY, Math.max(mScrollY - SELECT_CURSOR_OFFSET
                , mSelectY));
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "moveSelection"
                    + " mSelectX=" + mSelectX
                    + " mSelectY=" + mSelectY
                    + " mScrollX=" + mScrollX
                    + " mScrollY=" + mScrollY
                    + " xRate=" + xRate
                    + " yRate=" + yRate
                    );
        }
        nativeMoveSelection(viewToContentX(mSelectX),
                viewToContentY(mSelectY), mExtendSelection);
        int scrollX = mSelectX < mScrollX ? -SELECT_CURSOR_OFFSET
                : mSelectX > maxX - SELECT_CURSOR_OFFSET ? SELECT_CURSOR_OFFSET
                : 0;
        int scrollY = mSelectY < mScrollY ? -SELECT_CURSOR_OFFSET
                : mSelectY > maxY - SELECT_CURSOR_OFFSET ? SELECT_CURSOR_OFFSET
                : 0;
        pinScrollBy(scrollX, scrollY, true, 0);
        Rect select = new Rect(mSelectX, mSelectY, mSelectX + 1, mSelectY + 1);
        requestRectangleOnScreen(select);
        invalidate();
   }

    private int scaleTrackballX(float xRate, int width) {
        int xMove = (int) (xRate / TRACKBALL_SCALE * width);
        int nextXMove = xMove;
        if (xMove > 0) {
            if (xMove > mTrackballXMove) {
                xMove -= mTrackballXMove;
            }
        } else if (xMove < mTrackballXMove) {
            xMove -= mTrackballXMove;
        }
        mTrackballXMove = nextXMove;
        return xMove;
    }

    private int scaleTrackballY(float yRate, int height) {
        int yMove = (int) (yRate / TRACKBALL_SCALE * height);
        int nextYMove = yMove;
        if (yMove > 0) {
            if (yMove > mTrackballYMove) {
                yMove -= mTrackballYMove;
            }
        } else if (yMove < mTrackballYMove) {
            yMove -= mTrackballYMove;
        }
        mTrackballYMove = nextYMove;
        return yMove;
    }

    private int keyCodeToSoundsEffect(int keyCode) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return SoundEffectConstants.NAVIGATION_UP;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return SoundEffectConstants.NAVIGATION_RIGHT;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return SoundEffectConstants.NAVIGATION_DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return SoundEffectConstants.NAVIGATION_LEFT;
        }
        throw new IllegalArgumentException("keyCode must be one of " +
                "{KEYCODE_DPAD_UP, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_DOWN, " +
                "KEYCODE_DPAD_LEFT}.");
    }

    private void doTrackball(long time) {
        int elapsed = (int) (mTrackballLastTime - mTrackballFirstTime);
        if (elapsed == 0) {
            elapsed = TRACKBALL_TIMEOUT;
        }
        float xRate = mTrackballRemainsX * 1000 / elapsed;
        float yRate = mTrackballRemainsY * 1000 / elapsed;
        if (mShiftIsPressed) {
            moveSelection(xRate, yRate);
            mTrackballRemainsX = mTrackballRemainsY = 0;
            return;
        }
        float ax = Math.abs(xRate);
        float ay = Math.abs(yRate);
        float maxA = Math.max(ax, ay);
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "doTrackball elapsed=" + elapsed
                    + " xRate=" + xRate
                    + " yRate=" + yRate
                    + " mTrackballRemainsX=" + mTrackballRemainsX
                    + " mTrackballRemainsY=" + mTrackballRemainsY);
        }
        int width = mContentWidth - getViewWidth();
        int height = mContentHeight - getViewHeight();
        if (width < 0) width = 0;
        if (height < 0) height = 0;
        ax = Math.abs(mTrackballRemainsX * TRACKBALL_MULTIPLIER);
        ay = Math.abs(mTrackballRemainsY * TRACKBALL_MULTIPLIER);
        maxA = Math.max(ax, ay);
        int count = Math.max(0, (int) maxA);
        int oldScrollX = mScrollX;
        int oldScrollY = mScrollY;
        if (count > 0) {
            int selectKeyCode = ax < ay ? mTrackballRemainsY < 0 ?
                    KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN :
                    mTrackballRemainsX < 0 ? KeyEvent.KEYCODE_DPAD_LEFT :
                    KeyEvent.KEYCODE_DPAD_RIGHT;
            count = Math.min(count, TRACKBALL_MOVE_COUNT);
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "doTrackball keyCode=" + selectKeyCode
                        + " count=" + count
                        + " mTrackballRemainsX=" + mTrackballRemainsX
                        + " mTrackballRemainsY=" + mTrackballRemainsY);
            }
            if (navHandledKey(selectKeyCode, count, false, time, false)) {
                playSoundEffect(keyCodeToSoundsEffect(selectKeyCode));
            }
            mTrackballRemainsX = mTrackballRemainsY = 0;
        }
        if (count >= TRACKBALL_SCROLL_COUNT) {
            int xMove = scaleTrackballX(xRate, width);
            int yMove = scaleTrackballY(yRate, height);
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "doTrackball pinScrollBy"
                        + " count=" + count
                        + " xMove=" + xMove + " yMove=" + yMove
                        + " mScrollX-oldScrollX=" + (mScrollX-oldScrollX)
                        + " mScrollY-oldScrollY=" + (mScrollY-oldScrollY)
                        );
            }
            if (Math.abs(mScrollX - oldScrollX) > Math.abs(xMove)) {
                xMove = 0;
            }
            if (Math.abs(mScrollY - oldScrollY) > Math.abs(yMove)) {
                yMove = 0;
            }
            if (xMove != 0 || yMove != 0) {
                pinScrollBy(xMove, yMove, true, 0);
            }
            mUserScroll = true;
        }
    }

    private int computeMaxScrollY() {
        int maxContentH = computeVerticalScrollRange() + getTitleHeight();
        return Math.max(maxContentH - getViewHeightWithTitle(), getTitleHeight());
    }

    public void flingScroll(int vx, int vy) {
        int maxX = Math.max(computeHorizontalScrollRange() - getViewWidth(), 0);
        int maxY = computeMaxScrollY();

        mScroller.fling(mScrollX, mScrollY, vx, vy, 0, maxX, 0, maxY);
        invalidate();
    }

    private void doFling() {
        if (mVelocityTracker == null) {
            return;
        }
        int maxX = Math.max(computeHorizontalScrollRange() - getViewWidth(), 0);
        int maxY = computeMaxScrollY();

        mVelocityTracker.computeCurrentVelocity(1000, mMaximumFling);
        int vx = (int) mVelocityTracker.getXVelocity();
        int vy = (int) mVelocityTracker.getYVelocity();

        if (mSnapScrollMode != SNAP_NONE) {
            if (mSnapScrollMode == SNAP_X || mSnapScrollMode == SNAP_X_LOCK) {
                vy = 0;
            } else {
                vx = 0;
            }
        }

        if (true /* EMG release: make our fling more like Maps' */) {
            // maps cuts their velocity in half
            vx = vx * 3 / 4;
            vy = vy * 3 / 4;
        }
        if ((maxX == 0 && vy == 0) || (maxY == 0 && vx == 0)) {
            WebViewCore.resumeUpdate(mWebViewCore);
            return;
        }
        float currentVelocity = mScroller.getCurrVelocity();
        if (mLastVelocity > 0 && currentVelocity > 0) {
            float deltaR = (float) (Math.abs(Math.atan2(mLastVelY, mLastVelX)
                    - Math.atan2(vy, vx)));
            final float circle = (float) (Math.PI) * 2.0f;
            if (deltaR > circle * 0.9f || deltaR < circle * 0.1f) {
                vx += currentVelocity * mLastVelX / mLastVelocity;
                vy += currentVelocity * mLastVelY / mLastVelocity;
                if (DebugFlags.WEB_VIEW) {
                    Log.v(LOGTAG, "doFling vx= " + vx + " vy=" + vy);
                }
            } else if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, "doFling missed " + deltaR / circle);
            }
        } else if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "doFling start last=" + mLastVelocity
                    + " current=" + currentVelocity
                    + " vx=" + vx + " vy=" + vy
                    + " maxX=" + maxX + " maxY=" + maxY
                    + " mScrollX=" + mScrollX + " mScrollY=" + mScrollY);
        }
        mLastVelX = vx;
        mLastVelY = vy;
        mLastVelocity = (float) Math.hypot(vx, vy);

        mScroller.fling(mScrollX, mScrollY, -vx, -vy, 0, maxX, 0, maxY);
        // TODO: duration is calculated based on velocity, if the range is
        // small, the animation will stop before duration is up. We may
        // want to calculate how long the animation is going to run to precisely
        // resume the webcore update.
        final int time = mScroller.getDuration();
        mPrivateHandler.sendEmptyMessageDelayed(RESUME_WEBCORE_UPDATE, time);
        awakenScrollBars(time);
        invalidate();
    }

    private boolean zoomWithPreview(float scale) {
        float oldScale = mActualScale;
        mInitialScrollX = mScrollX;
        mInitialScrollY = mScrollY;

        // snap to DEFAULT_SCALE if it is close
        if (scale > (mDefaultScale - 0.05) && scale < (mDefaultScale + 0.05)) {
            scale = mDefaultScale;
        }

        setNewZoomScale(scale, true, false);

        if (oldScale != mActualScale) {
            // use mZoomPickerScale to see zoom preview first
            mZoomStart = SystemClock.uptimeMillis();
            mInvInitialZoomScale = 1.0f / oldScale;
            mInvFinalZoomScale = 1.0f / mActualScale;
            mZoomScale = mActualScale;
            invalidate();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Returns a view containing zoom controls i.e. +/- buttons. The caller is
     * in charge of installing this view to the view hierarchy. This view will
     * become visible when the user starts scrolling via touch and fade away if
     * the user does not interact with it.
     * <p/>
     * API version 3 introduces a built-in zoom mechanism that is shown
     * automatically by the MapView. This is the preferred approach for
     * showing the zoom UI.
     *
     * @deprecated The built-in zoom mechanism is preferred, see
     *             {@link WebSettings#setBuiltInZoomControls(boolean)}.
     */
    @Deprecated
    public View getZoomControls() {
        if (!getSettings().supportZoom()) {
            Log.w(LOGTAG, "This WebView doesn't support zoom.");
            return null;
        }
        if (mZoomControls == null) {
            mZoomControls = createZoomControls();

            /*
             * need to be set to VISIBLE first so that getMeasuredHeight() in
             * {@link #onSizeChanged()} can return the measured value for proper
             * layout.
             */
            mZoomControls.setVisibility(View.VISIBLE);
            mZoomControlRunnable = new Runnable() {
                public void run() {

                    /* Don't dismiss the controls if the user has
                     * focus on them. Wait and check again later.
                     */
                    if (!mZoomControls.hasFocus()) {
                        mZoomControls.hide();
                    } else {
                        mPrivateHandler.removeCallbacks(mZoomControlRunnable);
                        mPrivateHandler.postDelayed(mZoomControlRunnable,
                                ZOOM_CONTROLS_TIMEOUT);
                    }
                }
            };
        }
        return mZoomControls;
    }

    private ExtendedZoomControls createZoomControls() {
        ExtendedZoomControls zoomControls = new ExtendedZoomControls(mContext
            , null);
        zoomControls.setOnZoomInClickListener(new OnClickListener() {
            public void onClick(View v) {
                // reset time out
                mPrivateHandler.removeCallbacks(mZoomControlRunnable);
                mPrivateHandler.postDelayed(mZoomControlRunnable,
                        ZOOM_CONTROLS_TIMEOUT);
                zoomIn();
            }
        });
        zoomControls.setOnZoomOutClickListener(new OnClickListener() {
            public void onClick(View v) {
                // reset time out
                mPrivateHandler.removeCallbacks(mZoomControlRunnable);
                mPrivateHandler.postDelayed(mZoomControlRunnable,
                        ZOOM_CONTROLS_TIMEOUT);
                zoomOut();
            }
        });
        return zoomControls;
    }

    /**
     * Gets the {@link ZoomButtonsController} which can be used to add
     * additional buttons to the zoom controls window.
     *
     * @return The instance of {@link ZoomButtonsController} used by this class,
     *         or null if it is unavailable.
     * @hide
     */
    public ZoomButtonsController getZoomButtonsController() {
        return mZoomButtonsController;
    }

    /**
     * Perform zoom in in the webview
     * @return TRUE if zoom in succeeds. FALSE if no zoom changes.
     */
    public boolean zoomIn() {
        // TODO: alternatively we can disallow this during draw history mode
        switchOutDrawHistory();
        mInZoomOverview = false;
        // Center zooming to the center of the screen.
        mZoomCenterX = getViewWidth() * .5f;
        mZoomCenterY = getViewHeight() * .5f;
        mAnchorX = viewToContentX((int) mZoomCenterX + mScrollX);
        mAnchorY = viewToContentY((int) mZoomCenterY + mScrollY);
        return zoomWithPreview(mActualScale * 1.25f);
    }

    /**
     * Perform zoom out in the webview
     * @return TRUE if zoom out succeeds. FALSE if no zoom changes.
     */
    public boolean zoomOut() {
        // TODO: alternatively we can disallow this during draw history mode
        switchOutDrawHistory();
        // Center zooming to the center of the screen.
        mZoomCenterX = getViewWidth() * .5f;
        mZoomCenterY = getViewHeight() * .5f;
        mAnchorX = viewToContentX((int) mZoomCenterX + mScrollX);
        mAnchorY = viewToContentY((int) mZoomCenterY + mScrollY);
        return zoomWithPreview(mActualScale * 0.8f);
    }

    private void updateSelection() {
        if (mNativeClass == 0) {
            return;
        }
        // mLastTouchX and mLastTouchY are the point in the current viewport
        int contentX = viewToContentX((int) mLastTouchX + mScrollX);
        int contentY = viewToContentY((int) mLastTouchY + mScrollY);
        Rect rect = new Rect(contentX - mNavSlop, contentY - mNavSlop,
                contentX + mNavSlop, contentY + mNavSlop);
        nativeSelectBestAt(rect);
    }

    /**
     * Scroll the focused text field/area to match the WebTextView
     * @param xPercent New x position of the WebTextView from 0 to 1.
     * @param y New y position of the WebTextView in view coordinates
     */
    /*package*/ void scrollFocusedTextInput(float xPercent, int y) {
        if (!inEditingMode() || mWebViewCore == null) {
            return;
        }
        mWebViewCore.sendMessage(EventHub.SCROLL_TEXT_INPUT,
                // Since this position is relative to the top of the text input
                // field, we do not need to take the title bar's height into
                // consideration.
                viewToContentDimension(y),
                new Float(xPercent));
    }

    /**
     * Set our starting point and time for a drag from the WebTextView.
     */
    /*package*/ void initiateTextFieldDrag(float x, float y, long eventTime) {
        if (!inEditingMode()) {
            return;
        }
        mLastTouchX = x + (float) (mWebTextView.getLeft() - mScrollX);
        mLastTouchY = y + (float) (mWebTextView.getTop() - mScrollY);
        mLastTouchTime = eventTime;
        if (!mScroller.isFinished()) {
            abortAnimation();
            mPrivateHandler.removeMessages(RESUME_WEBCORE_UPDATE);
        }
        mSnapScrollMode = SNAP_NONE;
        mVelocityTracker = VelocityTracker.obtain();
        mTouchMode = TOUCH_DRAG_START_MODE;
    }

    /**
     * Given a motion event from the WebTextView, set its location to our
     * coordinates, and handle the event.
     */
    /*package*/ boolean textFieldDrag(MotionEvent event) {
        if (!inEditingMode()) {
            return false;
        }
        mDragFromTextInput = true;
        event.offsetLocation((float) (mWebTextView.getLeft() - mScrollX),
                (float) (mWebTextView.getTop() - mScrollY));
        boolean result = onTouchEvent(event);
        mDragFromTextInput = false;
        return result;
    }

    /**
     * Do a touch up from a WebTextView.  This will be handled by webkit to
     * change the selection.
     * @param event MotionEvent in the WebTextView's coordinates.
     */
    /*package*/ void touchUpOnTextField(MotionEvent event) {
        if (!inEditingMode()) {
            return;
        }
        int x = viewToContentX((int) event.getX() + mWebTextView.getLeft());
        int y = viewToContentY((int) event.getY() + mWebTextView.getTop());
        // In case the soft keyboard has been dismissed, bring it back up.
        InputMethodManager.getInstance(getContext()).showSoftInput(mWebTextView,
                0);
        if (nativeFocusNodePointer() != nativeCursorNodePointer()) {
            nativeMotionUp(x, y, mNavSlop);
        }
        nativeTextInputMotionUp(x, y);
    }

    /*package*/ void shortPressOnTextField() {
        if (inEditingMode()) {
            View v = mWebTextView;
            int x = viewToContentX((v.getLeft() + v.getRight()) >> 1);
            int y = viewToContentY((v.getTop() + v.getBottom()) >> 1);
            displaySoftKeyboard(true);
            nativeTextInputMotionUp(x, y);
        }
    }

    private void doShortPress() {
        if (mNativeClass == 0) {
            return;
        }
        switchOutDrawHistory();
        // mLastTouchX and mLastTouchY are the point in the current viewport
        int contentX = viewToContentX((int) mLastTouchX + mScrollX);
        int contentY = viewToContentY((int) mLastTouchY + mScrollY);
        if (nativeMotionUp(contentX, contentY, mNavSlop)) {
            if (mLogEvent) {
                Checkin.updateStats(mContext.getContentResolver(),
                        Checkin.Stats.Tag.BROWSER_SNAP_CENTER, 1, 0.0);
            }
        }
        if (nativeHasCursorNode() && !nativeCursorIsTextInput()) {
            playSoundEffect(SoundEffectConstants.CLICK);
        }
    }

    // Rule for double tap:
    // 1. if the current scale is not same as the text wrap scale and layout
    //    algorithm is NARROW_COLUMNS, fit to column;
    // 2. if the current state is not overview mode, change to overview mode;
    // 3. if the current state is overview mode, change to default scale.
    private void doDoubleTap() {
        if (mWebViewCore.getSettings().getUseWideViewPort() == false) {
            return;
        }
        mZoomCenterX = mLastTouchX;
        mZoomCenterY = mLastTouchY;
        mAnchorX = viewToContentX((int) mZoomCenterX + mScrollX);
        mAnchorY = viewToContentY((int) mZoomCenterY + mScrollY);
        WebSettings settings = getSettings();
        // remove the zoom control after double tap
        if (settings.getBuiltInZoomControls()) {
            if (mZoomButtonsController.isVisible()) {
                mZoomButtonsController.setVisible(false);
            }
        } else {
            if (mZoomControlRunnable != null) {
                mPrivateHandler.removeCallbacks(mZoomControlRunnable);
            }
            if (mZoomControls != null) {
                mZoomControls.hide();
            }
        }
        settings.setDoubleTapToastCount(0);
        boolean zoomToDefault = false;
        if ((settings.getLayoutAlgorithm() == WebSettings.LayoutAlgorithm.NARROW_COLUMNS)
                && (Math.abs(mActualScale - mTextWrapScale) >= 0.01f)) {
            setNewZoomScale(mActualScale, true, true);
            float overviewScale = (float) getViewWidth() / mZoomOverviewWidth;
            if (Math.abs(mActualScale - overviewScale) < 0.01f) {
                mInZoomOverview = true;
            }
        } else if (!mInZoomOverview) {
            float newScale = (float) getViewWidth() / mZoomOverviewWidth;
            if (Math.abs(mActualScale - newScale) >= 0.01f) {
                mInZoomOverview = true;
                // Force the titlebar fully reveal in overview mode
                if (mScrollY < getTitleHeight()) mScrollY = 0;
                zoomWithPreview(newScale);
            } else if (Math.abs(mActualScale - mDefaultScale) >= 0.01f) {
                zoomToDefault = true;
            }
        } else {
            zoomToDefault = true;
        }
        if (zoomToDefault) {
            mInZoomOverview = false;
            int left = nativeGetBlockLeftEdge(mAnchorX, mAnchorY, mActualScale);
            if (left != NO_LEFTEDGE) {
                // add a 5pt padding to the left edge.
                int viewLeft = contentToViewX(left < 5 ? 0 : (left - 5))
                        - mScrollX;
                // Re-calculate the zoom center so that the new scroll x will be
                // on the left edge.
                if (viewLeft > 0) {
                    mZoomCenterX = viewLeft * mDefaultScale
                            / (mDefaultScale - mActualScale);
                } else {
                    scrollBy(viewLeft, 0);
                    mZoomCenterX = 0;
                }
            }
            zoomWithPreview(mDefaultScale);
        }
    }

    // Called by JNI to handle a touch on a node representing an email address,
    // address, or phone number
    private void overrideLoading(String url) {
        mCallbackProxy.uiOverrideUrlLoading(url);
    }

    // called by JNI
    private void sendPluginState(int state) {
        WebViewCore.PluginStateData psd = new WebViewCore.PluginStateData();
        psd.mFrame = nativeCursorFramePointer();
        psd.mNode = nativeCursorNodePointer();
        psd.mState = state;
        mWebViewCore.sendMessage(EventHub.PLUGIN_STATE, psd);
    }

    @Override
    public boolean requestFocus(int direction, Rect previouslyFocusedRect) {
        boolean result = false;
        if (inEditingMode()) {
            result = mWebTextView.requestFocus(direction,
                    previouslyFocusedRect);
        } else {
            result = super.requestFocus(direction, previouslyFocusedRect);
            if (mWebViewCore.getSettings().getNeedInitialFocus()) {
                // For cases such as GMail, where we gain focus from a direction,
                // we want to move to the first available link.
                // FIXME: If there are no visible links, we may not want to
                int fakeKeyDirection = 0;
                switch(direction) {
                    case View.FOCUS_UP:
                        fakeKeyDirection = KeyEvent.KEYCODE_DPAD_UP;
                        break;
                    case View.FOCUS_DOWN:
                        fakeKeyDirection = KeyEvent.KEYCODE_DPAD_DOWN;
                        break;
                    case View.FOCUS_LEFT:
                        fakeKeyDirection = KeyEvent.KEYCODE_DPAD_LEFT;
                        break;
                    case View.FOCUS_RIGHT:
                        fakeKeyDirection = KeyEvent.KEYCODE_DPAD_RIGHT;
                        break;
                    default:
                        return result;
                }
                if (mNativeClass != 0 && !nativeHasCursorNode()) {
                    navHandledKey(fakeKeyDirection, 1, true, 0, true);
                }
            }
        }
        return result;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);

        int measuredHeight = heightSize;
        int measuredWidth = widthSize;

        // Grab the content size from WebViewCore.
        int contentHeight = contentToViewDimension(mContentHeight);
        int contentWidth = contentToViewDimension(mContentWidth);

//        Log.d(LOGTAG, "------- measure " + heightMode);

        if (heightMode != MeasureSpec.EXACTLY) {
            mHeightCanMeasure = true;
            measuredHeight = contentHeight;
            if (heightMode == MeasureSpec.AT_MOST) {
                // If we are larger than the AT_MOST height, then our height can
                // no longer be measured and we should scroll internally.
                if (measuredHeight > heightSize) {
                    measuredHeight = heightSize;
                    mHeightCanMeasure = false;
                }
            }
        } else {
            mHeightCanMeasure = false;
        }
        if (mNativeClass != 0) {
            nativeSetHeightCanMeasure(mHeightCanMeasure);
        }
        // For the width, always use the given size unless unspecified.
        if (widthMode == MeasureSpec.UNSPECIFIED) {
            mWidthCanMeasure = true;
            measuredWidth = contentWidth;
        } else {
            mWidthCanMeasure = false;
        }

        synchronized (this) {
            setMeasuredDimension(measuredWidth, measuredHeight);
        }
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child,
                                                 Rect rect,
                                                 boolean immediate) {
        rect.offset(child.getLeft() - child.getScrollX(),
                child.getTop() - child.getScrollY());

        int height = getViewHeightWithTitle();
        int screenTop = mScrollY;
        int screenBottom = screenTop + height;

        int scrollYDelta = 0;

        if (rect.bottom > screenBottom) {
            int oneThirdOfScreenHeight = height / 3;
            if (rect.height() > 2 * oneThirdOfScreenHeight) {
                // If the rectangle is too tall to fit in the bottom two thirds
                // of the screen, place it at the top.
                scrollYDelta = rect.top - screenTop;
            } else {
                // If the rectangle will still fit on screen, we want its
                // top to be in the top third of the screen.
                scrollYDelta = rect.top - (screenTop + oneThirdOfScreenHeight);
            }
        } else if (rect.top < screenTop) {
            scrollYDelta = rect.top - screenTop;
        }

        int width = getWidth() - getVerticalScrollbarWidth();
        int screenLeft = mScrollX;
        int screenRight = screenLeft + width;

        int scrollXDelta = 0;

        if (rect.right > screenRight && rect.left > screenLeft) {
            if (rect.width() > width) {
                scrollXDelta += (rect.left - screenLeft);
            } else {
                scrollXDelta += (rect.right - screenRight);
            }
        } else if (rect.left < screenLeft) {
            scrollXDelta -= (screenLeft - rect.left);
        }

        if ((scrollYDelta | scrollXDelta) != 0) {
            return pinScrollBy(scrollXDelta, scrollYDelta, !immediate, 0);
        }

        return false;
    }

    /* package */ void replaceTextfieldText(int oldStart, int oldEnd,
            String replace, int newStart, int newEnd) {
        WebViewCore.ReplaceTextData arg = new WebViewCore.ReplaceTextData();
        arg.mReplace = replace;
        arg.mNewStart = newStart;
        arg.mNewEnd = newEnd;
        mTextGeneration++;
        arg.mTextGeneration = mTextGeneration;
        mWebViewCore.sendMessage(EventHub.REPLACE_TEXT, oldStart, oldEnd, arg);
    }

    /* package */ void passToJavaScript(String currentText, KeyEvent event) {
        if (nativeCursorWantsKeyEvents() && !nativeCursorMatchesFocus()) {
            mWebViewCore.sendMessage(EventHub.CLICK);
            if (mWebTextView.mOkayForFocusNotToMatch) {
                int select = nativeFocusCandidateIsTextField() ?
                        nativeFocusCandidateMaxLength() : 0;
                setSelection(select, select);
            }
        }
        WebViewCore.JSKeyData arg = new WebViewCore.JSKeyData();
        arg.mEvent = event;
        arg.mCurrentText = currentText;
        // Increase our text generation number, and pass it to webcore thread
        mTextGeneration++;
        mWebViewCore.sendMessage(EventHub.PASS_TO_JS, mTextGeneration, 0, arg);
        // WebKit's document state is not saved until about to leave the page.
        // To make sure the host application, like Browser, has the up to date
        // document state when it goes to background, we force to save the
        // document state.
        mWebViewCore.removeMessages(EventHub.SAVE_DOCUMENT_STATE);
        mWebViewCore.sendMessageDelayed(EventHub.SAVE_DOCUMENT_STATE,
                cursorData(), 1000);
    }

    /* package */ WebViewCore getWebViewCore() {
        return mWebViewCore;
    }

    //-------------------------------------------------------------------------
    // Methods can be called from a separate thread, like WebViewCore
    // If it needs to call the View system, it has to send message.
    //-------------------------------------------------------------------------

    /**
     * General handler to receive message coming from webkit thread
     */
    class PrivateHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            if (DebugFlags.WEB_VIEW) {
                Log.v(LOGTAG, msg.what < REMEMBER_PASSWORD
                        || msg.what > SHOW_RECT_MSG_ID ? Integer
                        .toString(msg.what) : HandlerDebugString[msg.what
                        - REMEMBER_PASSWORD]);
            }
            if (mWebViewCore == null) {
                // after WebView's destroy() is called, skip handling messages.
                return;
            }
            switch (msg.what) {
                case REMEMBER_PASSWORD: {
                    mDatabase.setUsernamePassword(
                            msg.getData().getString("host"),
                            msg.getData().getString("username"),
                            msg.getData().getString("password"));
                    ((Message) msg.obj).sendToTarget();
                    break;
                }
                case NEVER_REMEMBER_PASSWORD: {
                    mDatabase.setUsernamePassword(
                            msg.getData().getString("host"), null, null);
                    ((Message) msg.obj).sendToTarget();
                    break;
                }
                case SWITCH_TO_SHORTPRESS: {
                    // if mPreventDrag is not confirmed, treat it as no so that
                    // it won't block panning the page.
                    if (mPreventDrag == PREVENT_DRAG_MAYBE_YES) {
                        mPreventDrag = PREVENT_DRAG_NO;
                    }
                    if (mTouchMode == TOUCH_INIT_MODE) {
                        mTouchMode = TOUCH_SHORTPRESS_START_MODE;
                        updateSelection();
                    } else if (mTouchMode == TOUCH_DOUBLE_TAP_MODE) {
                        mTouchMode = TOUCH_DONE_MODE;
                    }
                    break;
                }
                case SWITCH_TO_LONGPRESS: {
                    if (mPreventDrag == PREVENT_DRAG_NO) {
                        mTouchMode = TOUCH_DONE_MODE;
                        performLongClick();
                        rebuildWebTextView();
                    }
                    break;
                }
                case RELEASE_SINGLE_TAP: {
                    if (mPreventDrag == PREVENT_DRAG_NO) {
                        mTouchMode = TOUCH_DONE_MODE;
                        doShortPress();
                    }
                    break;
                }
                case SCROLL_BY_MSG_ID:
                    setContentScrollBy(msg.arg1, msg.arg2, (Boolean) msg.obj);
                    break;
                case SYNC_SCROLL_TO_MSG_ID:
                    if (mUserScroll) {
                        // if user has scrolled explicitly, don't sync the
                        // scroll position any more
                        mUserScroll = false;
                        break;
                    }
                    // fall through
                case SCROLL_TO_MSG_ID:
                    if (setContentScrollTo(msg.arg1, msg.arg2)) {
                        // if we can't scroll to the exact position due to pin,
                        // send a message to WebCore to re-scroll when we get a
                        // new picture
                        mUserScroll = false;
                        mWebViewCore.sendMessage(EventHub.SYNC_SCROLL,
                                msg.arg1, msg.arg2);
                    }
                    break;
                case SPAWN_SCROLL_TO_MSG_ID:
                    spawnContentScrollTo(msg.arg1, msg.arg2);
                    break;
                case UPDATE_ZOOM_RANGE: {
                    WebViewCore.RestoreState restoreState
                            = (WebViewCore.RestoreState) msg.obj;
                    // mScrollX contains the new minPrefWidth
                    updateZoomRange(restoreState, getViewWidth(),
                            restoreState.mScrollX, false);
                    break;
                }
                case NEW_PICTURE_MSG_ID: {
                    WebSettings settings = mWebViewCore.getSettings();
                    // called for new content
                    final int viewWidth = getViewWidth();
                    final WebViewCore.DrawData draw =
                            (WebViewCore.DrawData) msg.obj;
                    final Point viewSize = draw.mViewPoint;
                    boolean useWideViewport = settings.getUseWideViewPort();
                    WebViewCore.RestoreState restoreState = draw.mRestoreState;
                    if (restoreState != null) {
                        mInZoomOverview = false;
                        updateZoomRange(restoreState, viewSize.x,
                                draw.mMinPrefWidth, true);
                        if (mInitialScaleInPercent > 0) {
                            setNewZoomScale(mInitialScaleInPercent / 100.0f,
                                    mInitialScaleInPercent != mTextWrapScale * 100,
                                    false);
                        } else if (restoreState.mViewScale > 0) {
                            mTextWrapScale = restoreState.mTextWrapScale;
                            setNewZoomScale(restoreState.mViewScale, false,
                                    false);
                        } else {
                            mInZoomOverview = useWideViewport
                                    && settings.getLoadWithOverviewMode();
                            float scale;
                            if (mInZoomOverview) {
                                scale = (float) viewWidth
                                        / WebViewCore.DEFAULT_VIEWPORT_WIDTH;
                            } else {
                                scale = restoreState.mTextWrapScale;
                            }
                            setNewZoomScale(scale, Math.abs(scale
                                    - mTextWrapScale) >= 0.01f, false);
                        }
                        setContentScrollTo(restoreState.mScrollX,
                                restoreState.mScrollY);
                        // As we are on a new page, remove the WebTextView. This
                        // is necessary for page loads driven by webkit, and in
                        // particular when the user was on a password field, so
                        // the WebTextView was visible.
                        clearTextEntry();
                    }
                    // We update the layout (i.e. request a layout from the
                    // view system) if the last view size that we sent to
                    // WebCore matches the view size of the picture we just
                    // received in the fixed dimension.
                    final boolean updateLayout = viewSize.x == mLastWidthSent
                            && viewSize.y == mLastHeightSent;
                    recordNewContentSize(draw.mWidthHeight.x,
                            draw.mWidthHeight.y
                            + (mFindIsUp ? mFindHeight : 0), updateLayout);
                    if (DebugFlags.WEB_VIEW) {
                        Rect b = draw.mInvalRegion.getBounds();
                        Log.v(LOGTAG, "NEW_PICTURE_MSG_ID {" +
                                b.left+","+b.top+","+b.right+","+b.bottom+"}");
                    }
                    invalidateContentRect(draw.mInvalRegion.getBounds());
                    if (mPictureListener != null) {
                        mPictureListener.onNewPicture(WebView.this, capturePicture());
                    }
                    if (useWideViewport) {
                        mZoomOverviewWidth = Math.max(
                                (int) (viewWidth / mDefaultScale), Math.max(
                                        draw.mMinPrefWidth, draw.mViewPoint.x));
                    }
                    if (!mMinZoomScaleFixed) {
                        mMinZoomScale = (float) viewWidth / mZoomOverviewWidth;
                    }
                    if (!mDrawHistory && mInZoomOverview) {
                        // fit the content width to the current view. Ignore
                        // the rounding error case.
                        if (Math.abs((viewWidth * mInvActualScale)
                                - mZoomOverviewWidth) > 1) {
                            setNewZoomScale((float) viewWidth
                                    / mZoomOverviewWidth, Math.abs(mActualScale
                                            - mTextWrapScale) < 0.01f, false);
                        }
                    }
                    break;
                }
                case WEBCORE_INITIALIZED_MSG_ID:
                    // nativeCreate sets mNativeClass to a non-zero value
                    nativeCreate(msg.arg1);
                    break;
                case UPDATE_TEXTFIELD_TEXT_MSG_ID:
                    // Make sure that the textfield is currently focused
                    // and representing the same node as the pointer.
                    if (inEditingMode() &&
                            mWebTextView.isSameTextField(msg.arg1)) {
                        if (msg.getData().getBoolean("password")) {
                            Spannable text = (Spannable) mWebTextView.getText();
                            int start = Selection.getSelectionStart(text);
                            int end = Selection.getSelectionEnd(text);
                            mWebTextView.setInPassword(true);
                            // Restore the selection, which may have been
                            // ruined by setInPassword.
                            Spannable pword =
                                    (Spannable) mWebTextView.getText();
                            Selection.setSelection(pword, start, end);
                        // If the text entry has created more events, ignore
                        // this one.
                        } else if (msg.arg2 == mTextGeneration) {
                            mWebTextView.setTextAndKeepSelection(
                                    (String) msg.obj);
                        }
                    }
                    break;
                case UPDATE_TEXT_SELECTION_MSG_ID:
                    if (inEditingMode()
                            && mWebTextView.isSameTextField(msg.arg1)
                            && msg.arg2 == mTextGeneration) {
                        WebViewCore.TextSelectionData tData
                                = (WebViewCore.TextSelectionData) msg.obj;
                        mWebTextView.setSelectionFromWebKit(tData.mStart,
                                tData.mEnd);
                    }
                    break;
                case MOVE_OUT_OF_PLUGIN:
                    if (nativePluginEatsNavKey()) {
                        navHandledKey(msg.arg1, 1, false, 0, true);
                    }
                    break;
                case UPDATE_TEXT_ENTRY_MSG_ID:
                    // this is sent after finishing resize in WebViewCore. Make
                    // sure the text edit box is still on the  screen.
                    if (inEditingMode() && nativeCursorIsTextInput()) {
                        mWebTextView.bringIntoView();
                        rebuildWebTextView();
                    }
                    break;
                case CLEAR_TEXT_ENTRY:
                    clearTextEntry();
                    break;
                case INVAL_RECT_MSG_ID: {
                    Rect r = (Rect)msg.obj;
                    if (r == null) {
                        invalidate();
                    } else {
                        // we need to scale r from content into view coords,
                        // which viewInvalidate() does for us
                        viewInvalidate(r.left, r.top, r.right, r.bottom);
                    }
                    break;
                }
                case REQUEST_FORM_DATA:
                    AutoCompleteAdapter adapter = (AutoCompleteAdapter) msg.obj;
                    if (mWebTextView.isSameTextField(msg.arg1)) {
                        mWebTextView.setAdapterCustom(adapter);
                    }
                    break;
                case UPDATE_CLIPBOARD:
                    String str = (String) msg.obj;
                    if (DebugFlags.WEB_VIEW) {
                        Log.v(LOGTAG, "UPDATE_CLIPBOARD " + str);
                    }
                    try {
                        IClipboard clip = IClipboard.Stub.asInterface(
                                ServiceManager.getService("clipboard"));
                                clip.setClipboardText(str);
                    } catch (android.os.RemoteException e) {
                        Log.e(LOGTAG, "Clipboard failed", e);
                    }
                    break;
                case RESUME_WEBCORE_UPDATE:
                    WebViewCore.resumeUpdate(mWebViewCore);
                    break;

                case LONG_PRESS_CENTER:
                    // as this is shared by keydown and trackballdown, reset all
                    // the states
                    mGotCenterDown = false;
                    mTrackballDown = false;
                    // LONG_PRESS_CENTER is sent as a delayed message. If we
                    // switch to windows overview, the WebView will be
                    // temporarily removed from the view system. In that case,
                    // do nothing.
                    if (getParent() != null) {
                        performLongClick();
                    }
                    break;

                case WEBCORE_NEED_TOUCH_EVENTS:
                    mForwardTouchEvents = (msg.arg1 != 0);
                    break;

                case PREVENT_TOUCH_ID:
                    if (msg.arg1 == MotionEvent.ACTION_DOWN) {
                        // dont override if mPreventDrag has been set to no due
                        // to time out
                        if (mPreventDrag == PREVENT_DRAG_MAYBE_YES) {
                            mPreventDrag = msg.arg2 == 1 ? PREVENT_DRAG_YES
                                    : PREVENT_DRAG_NO;
                            if (mPreventDrag == PREVENT_DRAG_YES) {
                                mTouchMode = TOUCH_DONE_MODE;
                            }
                        }
                    }
                    break;

                case REQUEST_KEYBOARD:
                    if (msg.arg1 == 0) {
                        hideSoftKeyboard();
                    } else {
                        displaySoftKeyboard(false);
                    }
                    break;

                case SHOW_RECT_MSG_ID:
                    WebViewCore.ShowRectData data = (WebViewCore.ShowRectData) msg.obj;
                    int x = mScrollX;
                    int left = contentToViewDimension(data.mLeft);
                    int width = contentToViewDimension(data.mWidth);
                    int maxWidth = contentToViewDimension(data.mContentWidth);
                    int viewWidth = getViewWidth();
                    if (width < viewWidth) {
                        // center align
                        x += left + width / 2 - mScrollX - viewWidth / 2;
                    } else {
                        x += (int) (left + data.mXPercentInDoc * width
                                - mScrollX - data.mXPercentInView * viewWidth);
                    }
                    // use the passing content width to cap x as the current
                    // mContentWidth may not be updated yet
                    x = Math.max(0,
                            (Math.min(maxWidth, x + viewWidth)) - viewWidth);
                    int y = mScrollY;
                    int top = contentToViewDimension(data.mTop);
                    int height = contentToViewDimension(data.mHeight);
                    int maxHeight = contentToViewDimension(data.mContentHeight);
                    int viewHeight = getViewHeight();
                    if (height < viewHeight) {
                        // middle align
                        y += top + height / 2 - mScrollY - viewHeight / 2;
                    } else {
                        y += (int) (top + data.mYPercentInDoc * height
                                - mScrollY - data.mYPercentInView * viewHeight);
                    }
                    // use the passing content height to cap y as the current
                    // mContentHeight may not be updated yet
                    y = Math.max(0,
                            (Math.min(maxHeight, y + viewHeight) - viewHeight));
                    scrollTo(x, y);
                    break;

                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }

    // Class used to use a dropdown for a <select> element
    private class InvokeListBox implements Runnable {
        // Whether the listbox allows multiple selection.
        private boolean     mMultiple;
        // Passed in to a list with multiple selection to tell
        // which items are selected.
        private int[]       mSelectedArray;
        // Passed in to a list with single selection to tell
        // where the initial selection is.
        private int         mSelection;

        private Container[] mContainers;

        // Need these to provide stable ids to my ArrayAdapter,
        // which normally does not have stable ids. (Bug 1250098)
        private class Container extends Object {
            String  mString;
            boolean mEnabled;
            int     mId;

            public String toString() {
                return mString;
            }
        }

        /**
         *  Subclass ArrayAdapter so we can disable OptionGroupLabels,
         *  and allow filtering.
         */
        private class MyArrayListAdapter extends ArrayAdapter<Container> {
            public MyArrayListAdapter(Context context, Container[] objects, boolean multiple) {
                super(context,
                            multiple ? com.android.internal.R.layout.select_dialog_multichoice :
                            com.android.internal.R.layout.select_dialog_singlechoice,
                            objects);
            }

            @Override
            public boolean hasStableIds() {
                // AdapterView's onChanged method uses this to determine whether
                // to restore the old state.  Return false so that the old (out
                // of date) state does not replace the new, valid state.
                return false;
            }

            private Container item(int position) {
                if (position < 0 || position >= getCount()) {
                    return null;
                }
                return (Container) getItem(position);
            }

            @Override
            public long getItemId(int position) {
                Container item = item(position);
                if (item == null) {
                    return -1;
                }
                return item.mId;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                Container item = item(position);
                if (item == null) {
                    return false;
                }
                return item.mEnabled;
            }
        }

        private InvokeListBox(String[] array,
                boolean[] enabled, int[] selected) {
            mMultiple = true;
            mSelectedArray = selected;

            int length = array.length;
            mContainers = new Container[length];
            for (int i = 0; i < length; i++) {
                mContainers[i] = new Container();
                mContainers[i].mString = array[i];
                mContainers[i].mEnabled = enabled[i];
                mContainers[i].mId = i;
            }
        }

        private InvokeListBox(String[] array, boolean[] enabled, int
                selection) {
            mSelection = selection;
            mMultiple = false;

            int length = array.length;
            mContainers = new Container[length];
            for (int i = 0; i < length; i++) {
                mContainers[i] = new Container();
                mContainers[i].mString = array[i];
                mContainers[i].mEnabled = enabled[i];
                mContainers[i].mId = i;
            }
        }

        /*
         * Whenever the data set changes due to filtering, this class ensures
         * that the checked item remains checked.
         */
        private class SingleDataSetObserver extends DataSetObserver {
            private long        mCheckedId;
            private ListView    mListView;
            private Adapter     mAdapter;

            /*
             * Create a new observer.
             * @param id The ID of the item to keep checked.
             * @param l ListView for getting and clearing the checked states
             * @param a Adapter for getting the IDs
             */
            public SingleDataSetObserver(long id, ListView l, Adapter a) {
                mCheckedId = id;
                mListView = l;
                mAdapter = a;
            }

            public void onChanged() {
                // The filter may have changed which item is checked.  Find the
                // item that the ListView thinks is checked.
                int position = mListView.getCheckedItemPosition();
                long id = mAdapter.getItemId(position);
                if (mCheckedId != id) {
                    // Clear the ListView's idea of the checked item, since
                    // it is incorrect
                    mListView.clearChoices();
                    // Search for mCheckedId.  If it is in the filtered list,
                    // mark it as checked
                    int count = mAdapter.getCount();
                    for (int i = 0; i < count; i++) {
                        if (mAdapter.getItemId(i) == mCheckedId) {
                            mListView.setItemChecked(i, true);
                            break;
                        }
                    }
                }
            }

            public void onInvalidate() {}
        }

        public void run() {
            final ListView listView = (ListView) LayoutInflater.from(mContext)
                    .inflate(com.android.internal.R.layout.select_dialog, null);
            final MyArrayListAdapter adapter = new
                    MyArrayListAdapter(mContext, mContainers, mMultiple);
            AlertDialog.Builder b = new AlertDialog.Builder(mContext)
                    .setView(listView).setCancelable(true)
                    .setInverseBackgroundForced(true);

            if (mMultiple) {
                b.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mWebViewCore.sendMessage(
                                EventHub.LISTBOX_CHOICES,
                                adapter.getCount(), 0,
                                listView.getCheckedItemPositions());
                    }});
                b.setNegativeButton(android.R.string.cancel,
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mWebViewCore.sendMessage(
                                EventHub.SINGLE_LISTBOX_CHOICE, -2, 0);
                }});
            }
            final AlertDialog dialog = b.create();
            listView.setAdapter(adapter);
            listView.setFocusableInTouchMode(true);
            // There is a bug (1250103) where the checks in a ListView with
            // multiple items selected are associated with the positions, not
            // the ids, so the items do not properly retain their checks when
            // filtered.  Do not allow filtering on multiple lists until
            // that bug is fixed.

            listView.setTextFilterEnabled(!mMultiple);
            if (mMultiple) {
                listView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
                int length = mSelectedArray.length;
                for (int i = 0; i < length; i++) {
                    listView.setItemChecked(mSelectedArray[i], true);
                }
            } else {
                listView.setOnItemClickListener(new OnItemClickListener() {
                    public void onItemClick(AdapterView parent, View v,
                            int position, long id) {
                        mWebViewCore.sendMessage(
                                EventHub.SINGLE_LISTBOX_CHOICE, (int)id, 0);
                        dialog.dismiss();
                    }
                });
                if (mSelection != -1) {
                    listView.setSelection(mSelection);
                    listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
                    listView.setItemChecked(mSelection, true);
                    DataSetObserver observer = new SingleDataSetObserver(
                            adapter.getItemId(mSelection), listView, adapter);
                    adapter.registerDataSetObserver(observer);
                }
            }
            dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                public void onCancel(DialogInterface dialog) {
                    mWebViewCore.sendMessage(
                                EventHub.SINGLE_LISTBOX_CHOICE, -2, 0);
                }
            });
            dialog.show();
        }
    }

    /*
     * Request a dropdown menu for a listbox with multiple selection.
     *
     * @param array Labels for the listbox.
     * @param enabledArray  Which positions are enabled.
     * @param selectedArray Which positions are initally selected.
     */
    void requestListBox(String[] array, boolean[]enabledArray, int[]
            selectedArray) {
        mPrivateHandler.post(
                new InvokeListBox(array, enabledArray, selectedArray));
    }

    private void updateZoomRange(WebViewCore.RestoreState restoreState,
            int viewWidth, int minPrefWidth, boolean updateZoomOverview) {
        if (restoreState.mMinScale == 0) {
            if (restoreState.mMobileSite) {
                if (minPrefWidth > Math.max(0, viewWidth)) {
                    mMinZoomScale = (float) viewWidth / minPrefWidth;
                    mMinZoomScaleFixed = false;
                    if (updateZoomOverview) {
                        WebSettings settings = getSettings();
                        mInZoomOverview = settings.getUseWideViewPort() &&
                                settings.getLoadWithOverviewMode();
                    }
                } else {
                    mMinZoomScale = restoreState.mDefaultScale;
                    mMinZoomScaleFixed = true;
                }
            } else {
                mMinZoomScale = DEFAULT_MIN_ZOOM_SCALE;
                mMinZoomScaleFixed = false;
            }
        } else {
            mMinZoomScale = restoreState.mMinScale;
            mMinZoomScaleFixed = true;
        }
        if (restoreState.mMaxScale == 0) {
            mMaxZoomScale = DEFAULT_MAX_ZOOM_SCALE;
        } else {
            mMaxZoomScale = restoreState.mMaxScale;
        }
    }

    /*
     * Request a dropdown menu for a listbox with single selection or a single
     * <select> element.
     *
     * @param array Labels for the listbox.
     * @param enabledArray  Which positions are enabled.
     * @param selection Which position is initally selected.
     */
    void requestListBox(String[] array, boolean[]enabledArray, int selection) {
        mPrivateHandler.post(
                new InvokeListBox(array, enabledArray, selection));
    }

    // called by JNI
    private void sendMoveMouse(int frame, int node, int x, int y) {
        mWebViewCore.sendMessage(EventHub.SET_MOVE_MOUSE,
                new WebViewCore.CursorData(frame, node, x, y));
    }

    /*
     * Send a mouse move event to the webcore thread.
     *
     * @param removeFocus Pass true if the "mouse" cursor is now over a node
     *                    which wants key events, but it is not the focus. This
     *                    will make the visual appear as though nothing is in
     *                    focus.  Remove the WebTextView, if present, and stop
     *                    drawing the blinking caret.
     * called by JNI
     */
    private void sendMoveMouseIfLatest(boolean removeFocus) {
        if (removeFocus) {
            clearTextEntry();
            setFocusControllerInactive();
        }
        mWebViewCore.sendMessage(EventHub.SET_MOVE_MOUSE_IF_LATEST,
                cursorData());
    }

    // called by JNI
    private void sendMotionUp(int touchGeneration,
            int frame, int node, int x, int y) {
        WebViewCore.TouchUpData touchUpData = new WebViewCore.TouchUpData();
        touchUpData.mMoveGeneration = touchGeneration;
        touchUpData.mFrame = frame;
        touchUpData.mNode = node;
        touchUpData.mX = x;
        touchUpData.mY = y;
        mWebViewCore.sendMessage(EventHub.TOUCH_UP, touchUpData);
    }


    private int getScaledMaxXScroll() {
        int width;
        if (mHeightCanMeasure == false) {
            width = getViewWidth() / 4;
        } else {
            Rect visRect = new Rect();
            calcOurVisibleRect(visRect);
            width = visRect.width() / 2;
        }
        // FIXME the divisor should be retrieved from somewhere
        return viewToContentX(width);
    }

    private int getScaledMaxYScroll() {
        int height;
        if (mHeightCanMeasure == false) {
            height = getViewHeight() / 4;
        } else {
            Rect visRect = new Rect();
            calcOurVisibleRect(visRect);
            height = visRect.height() / 2;
        }
        // FIXME the divisor should be retrieved from somewhere
        // the closest thing today is hard-coded into ScrollView.java
        // (from ScrollView.java, line 363)   int maxJump = height/2;
        return Math.round(height * mInvActualScale);
    }

    /**
     * Called by JNI to invalidate view
     */
    private void viewInvalidate() {
        invalidate();
    }

    // return true if the key was handled
    private boolean navHandledKey(int keyCode, int count, boolean noScroll,
            long time, boolean ignorePlugin) {
        if (mNativeClass == 0) {
            return false;
        }
        if (ignorePlugin == false && nativePluginEatsNavKey()) {
            KeyEvent event = new KeyEvent(time, time, KeyEvent.ACTION_DOWN
                , keyCode, count, (mShiftIsPressed ? KeyEvent.META_SHIFT_ON : 0)
                | (false ? KeyEvent.META_ALT_ON : 0) // FIXME
                | (false ? KeyEvent.META_SYM_ON : 0) // FIXME
                , 0, 0, 0);
            mWebViewCore.sendMessage(EventHub.KEY_DOWN, event);
            mWebViewCore.sendMessage(EventHub.KEY_UP, event);
            return true;
        }
        mLastCursorTime = time;
        mLastCursorBounds = nativeGetCursorRingBounds();
        boolean keyHandled
                = nativeMoveCursor(keyCode, count, noScroll) == false;
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "navHandledKey mLastCursorBounds=" + mLastCursorBounds
                    + " mLastCursorTime=" + mLastCursorTime
                    + " handled=" + keyHandled);
        }
        if (keyHandled == false || mHeightCanMeasure == false) {
            return keyHandled;
        }
        Rect contentCursorRingBounds = nativeGetCursorRingBounds();
        if (contentCursorRingBounds.isEmpty()) return keyHandled;
        Rect viewCursorRingBounds = contentToViewRect(contentCursorRingBounds);
        Rect visRect = new Rect();
        calcOurVisibleRect(visRect);
        Rect outset = new Rect(visRect);
        int maxXScroll = visRect.width() / 2;
        int maxYScroll = visRect.height() / 2;
        outset.inset(-maxXScroll, -maxYScroll);
        if (Rect.intersects(outset, viewCursorRingBounds) == false) {
            return keyHandled;
        }
        // FIXME: Necessary because ScrollView/ListView do not scroll left/right
        int maxH = Math.min(viewCursorRingBounds.right - visRect.right,
                maxXScroll);
        if (maxH > 0) {
            pinScrollBy(maxH, 0, true, 0);
        } else {
            maxH = Math.max(viewCursorRingBounds.left - visRect.left,
                    -maxXScroll);
            if (maxH < 0) {
                pinScrollBy(maxH, 0, true, 0);
            }
        }
        if (mLastCursorBounds.isEmpty()) return keyHandled;
        if (mLastCursorBounds.equals(contentCursorRingBounds)) {
            return keyHandled;
        }
        if (DebugFlags.WEB_VIEW) {
            Log.v(LOGTAG, "navHandledKey contentCursorRingBounds="
                    + contentCursorRingBounds);
        }
        requestRectangleOnScreen(viewCursorRingBounds);
        mUserScroll = true;
        return keyHandled;
    }

    /**
     * Set the background color. It's white by default. Pass
     * zero to make the view transparent.
     * @param color   the ARGB color described by Color.java
     */
    public void setBackgroundColor(int color) {
        mBackgroundColor = color;
        mWebViewCore.sendMessage(EventHub.SET_BACKGROUND_COLOR, color);
    }

    public void debugDump() {
        nativeDebugDump();
        mWebViewCore.sendMessage(EventHub.DUMP_NAVTREE);
    }

    /**
     *  Update our cache with updatedText.
     *  @param updatedText  The new text to put in our cache.
     */
    /* package */ void updateCachedTextfield(String updatedText) {
        // Also place our generation number so that when we look at the cache
        // we recognize that it is up to date.
        nativeUpdateCachedTextfield(updatedText, mTextGeneration);
    }

    /* package */ native void nativeClearCursor();
    private native void     nativeCreate(int ptr);
    private native int      nativeCursorFramePointer();
    private native Rect     nativeCursorNodeBounds();
    /* package */ native int nativeCursorNodePointer();
    /* package */ native boolean nativeCursorMatchesFocus();
    private native boolean  nativeCursorIntersects(Rect visibleRect);
    private native boolean  nativeCursorIsAnchor();
    private native boolean  nativeCursorIsPlugin();
    private native boolean  nativeCursorIsTextInput();
    private native Point    nativeCursorPosition();
    private native String   nativeCursorText();
    /**
     * Returns true if the native cursor node says it wants to handle key events
     * (ala plugins). This can only be called if mNativeClass is non-zero!
     */
    private native boolean  nativeCursorWantsKeyEvents();
    private native void     nativeDebugDump();
    private native void     nativeDestroy();
    private native void     nativeDrawCursorRing(Canvas content);
    private native void     nativeDrawMatches(Canvas canvas);
    private native void     nativeDrawSelection(Canvas content, float scale,
            int offset, int x, int y, boolean extendSelection);
    private native void     nativeDrawSelectionRegion(Canvas content);
    private native void     nativeDumpDisplayTree(String urlOrNull);
    private native int      nativeFindAll(String findLower, String findUpper);
    private native void     nativeFindNext(boolean forward);
    private native boolean  nativeFocusCandidateIsPassword();
    private native boolean  nativeFocusCandidateIsRtlText();
    private native boolean  nativeFocusCandidateIsTextField();
    private native boolean  nativeFocusCandidateIsTextInput();
    private native int      nativeFocusCandidateMaxLength();
    /* package */ native String   nativeFocusCandidateName();
    private native Rect     nativeFocusCandidateNodeBounds();
    /* package */ native int nativeFocusCandidatePointer();
    private native String   nativeFocusCandidateText();
    private native int      nativeFocusCandidateTextSize();
    /* package */ native int nativeFocusNodePointer();
    private native Rect     nativeGetCursorRingBounds();
    private native Region   nativeGetSelection();
    private native boolean  nativeHasCursorNode();
    private native boolean  nativeHasFocusNode();
    private native void     nativeHideCursor();
    private native String   nativeImageURI(int x, int y);
    private native void     nativeInstrumentReport();
    /* package */ native void nativeMoveCursorToNextTextInput();
    // return true if the page has been scrolled
    private native boolean  nativeMotionUp(int x, int y, int slop);
    // returns false if it handled the key
    private native boolean  nativeMoveCursor(int keyCode, int count,
            boolean noScroll);
    private native int      nativeMoveGeneration();
    private native void     nativeMoveSelection(int x, int y,
            boolean extendSelection);
    private native boolean  nativePluginEatsNavKey();
    // Like many other of our native methods, you must make sure that
    // mNativeClass is not null before calling this method.
    private native void     nativeRecordButtons(boolean focused,
            boolean pressed, boolean invalidate);
    private native void     nativeSelectBestAt(Rect rect);
    private native void     nativeSetFindIsDown();
    private native void     nativeSetFollowedLink(boolean followed);
    private native void     nativeSetHeightCanMeasure(boolean measure);
    // Returns a value corresponding to CachedFrame::ImeAction
    /* package */ native int  nativeTextFieldAction();
    /**
     * Perform a click on a currently focused text input.  Since it is already
     * focused, there is no need to go through the nativeMotionUp code, which
     * may change the Cursor.
     */
    private native void     nativeTextInputMotionUp(int x, int y);
    private native int      nativeTextGeneration();
    // Never call this version except by updateCachedTextfield(String) -
    // we always want to pass in our generation number.
    private native void     nativeUpdateCachedTextfield(String updatedText,
            int generation);
    private native void     nativeUpdatePluginReceivesEvents();
    // return NO_LEFTEDGE means failure.
    private static final int NO_LEFTEDGE = -1;
    private native int      nativeGetBlockLeftEdge(int x, int y, float scale);
}
