/*
 * Copyright (C) 2007 The Android Open Source Project
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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.EventLog;

import java.util.Locale;

/**
 * Manages settings state for a WebView. When a WebView is first created, it
 * obtains a set of default settings. These default settings will be returned
 * from any getter call. A WebSettings object obtained from
 * WebView.getSettings() is tied to the life of the WebView. If a WebView has
 * been destroyed, any method call on WebSettings will throw an
 * IllegalStateException.
 */
public class WebSettings {
    /**
     * Enum for controlling the layout of html.
     * NORMAL means no rendering changes.
     * SINGLE_COLUMN moves all content into one column that is the width of the
     * view.
     * NARROW_COLUMNS makes all columns no wider than the screen if possible.
     */
    // XXX: These must match LayoutAlgorithm in Settings.h in WebCore.
    public enum LayoutAlgorithm {
        NORMAL,
        /**
         * @deprecated This algorithm is now obsolete.
         */
        @Deprecated
        SINGLE_COLUMN,
        NARROW_COLUMNS
    }

    /**
     * Enum for specifying the text size.
     * SMALLEST is 50%
     * SMALLER is 75%
     * NORMAL is 100%
     * LARGER is 150%
     * LARGEST is 200%
     * @deprecated Use {@link WebSettings#setTextZoom(int)} and {@link WebSettings#getTextZoom()} instead.
     */
    public enum TextSize {
        SMALLEST(50),
        SMALLER(75),
        NORMAL(100),
        LARGER(150),
        LARGEST(200);
        TextSize(int size) {
            value = size;
        }
        int value;
    }

    /**
     * Enum for specifying the WebView's desired density.
     * FAR makes 100% looking like in 240dpi
     * MEDIUM makes 100% looking like in 160dpi
     * CLOSE makes 100% looking like in 120dpi
     */
    public enum ZoomDensity {
        FAR(150),      // 240dpi
        MEDIUM(100),    // 160dpi
        CLOSE(75);     // 120dpi
        ZoomDensity(int size) {
            value = size;
        }
        int value;
    }

    /**
     * Default cache usage pattern  Use with {@link #setCacheMode}.
     */
    public static final int LOAD_DEFAULT = -1;

    /**
     * Normal cache usage pattern  Use with {@link #setCacheMode}.
     */
    public static final int LOAD_NORMAL = 0;

    /**
     * Use cache if content is there, even if expired (eg, history nav)
     * If it is not in the cache, load from network.
     * Use with {@link #setCacheMode}.
     */
    public static final int LOAD_CACHE_ELSE_NETWORK = 1;

    /**
     * Don't use the cache, load from network
     * Use with {@link #setCacheMode}.
     */
    public static final int LOAD_NO_CACHE = 2;

    /**
     * Don't use the network, load from cache only.
     * Use with {@link #setCacheMode}.
     */
    public static final int LOAD_CACHE_ONLY = 3;

    public enum RenderPriority {
        NORMAL,
        HIGH,
        LOW
    }

    /**
     * The plugin state effects how plugins are treated on a page. ON means
     * that any object will be loaded even if a plugin does not exist to handle
     * the content. ON_DEMAND means that if there is a plugin installed that
     * can handle the content, a placeholder is shown until the user clicks on
     * the placeholder. Once clicked, the plugin will be enabled on the page.
     * OFF means that all plugins will be turned off and any fallback content
     * will be used.
     */
    public enum PluginState {
        ON,
        ON_DEMAND,
        OFF
    }

    // TODO: Keep this up to date
    private static final String PREVIOUS_VERSION = "3.1";

    // WebView associated with this WebSettings.
    private WebView mWebView;
    // BrowserFrame used to access the native frame pointer.
    private BrowserFrame mBrowserFrame;
    // Flag to prevent multiple SYNC messages at one time.
    private boolean mSyncPending = false;
    // Custom handler that queues messages until the WebCore thread is active.
    private final EventHandler mEventHandler;

    // Private settings so we don't have to go into native code to
    // retrieve the values. After setXXX, postSync() needs to be called.
    //
    // The default values need to match those in WebSettings.cpp
    // If the defaults change, please also update the JavaDocs so developers
    // know what they are.
    private LayoutAlgorithm mLayoutAlgorithm = LayoutAlgorithm.NARROW_COLUMNS;
    private Context         mContext;
    private int             mTextSize = 100;
    private String          mStandardFontFamily = "sans-serif";
    private String          mFixedFontFamily = "monospace";
    private String          mSansSerifFontFamily = "sans-serif";
    private String          mSerifFontFamily = "serif";
    private String          mCursiveFontFamily = "cursive";
    private String          mFantasyFontFamily = "fantasy";
    private String          mDefaultTextEncoding;
    private String          mUserAgent;
    private boolean         mUseDefaultUserAgent;
    private String          mAcceptLanguage;
    private int             mMinimumFontSize = 8;
    private int             mMinimumLogicalFontSize = 8;
    private int             mDefaultFontSize = 16;
    private int             mDefaultFixedFontSize = 13;
    private int             mPageCacheCapacity = 0;
    private boolean         mLoadsImagesAutomatically = true;
    private boolean         mBlockNetworkImage = false;
    private boolean         mBlockNetworkLoads;
    private boolean         mJavaScriptEnabled = false;
    private boolean         mHardwareAccelSkia = false;
    private boolean         mShowVisualIndicator = false;
    private PluginState     mPluginState = PluginState.OFF;
    private boolean         mJavaScriptCanOpenWindowsAutomatically = false;
    private boolean         mUseDoubleTree = false;
    private boolean         mUseWideViewport = false;
    private boolean         mSupportMultipleWindows = false;
    private boolean         mShrinksStandaloneImagesToFit = false;
    private long            mMaximumDecodedImageSize = 0; // 0 means default
    private boolean         mPrivateBrowsingEnabled = false;
    private boolean         mSyntheticLinksEnabled = true;
    // HTML5 API flags
    private boolean         mAppCacheEnabled = false;
    private boolean         mDatabaseEnabled = false;
    private boolean         mDomStorageEnabled = false;
    private boolean         mWorkersEnabled = false;  // only affects V8.
    private boolean         mGeolocationEnabled = true;
    private boolean         mXSSAuditorEnabled = false;
    // HTML5 configuration parameters
    private long            mAppCacheMaxSize = Long.MAX_VALUE;
    private String          mAppCachePath = null;
    private String          mDatabasePath = "";
    // The WebCore DatabaseTracker only allows the database path to be set
    // once. Keep track of when the path has been set.
    private boolean         mDatabasePathHasBeenSet = false;
    private String          mGeolocationDatabasePath = "";
    // Don't need to synchronize the get/set methods as they
    // are basic types, also none of these values are used in
    // native WebCore code.
    private ZoomDensity     mDefaultZoom = ZoomDensity.MEDIUM;
    private RenderPriority  mRenderPriority = RenderPriority.NORMAL;
    private int             mOverrideCacheMode = LOAD_DEFAULT;
    private int             mDoubleTapZoom = 100;
    private boolean         mSaveFormData = true;
    private boolean         mAutoFillEnabled = false;
    private boolean         mSavePassword = true;
    private boolean         mLightTouchEnabled = false;
    private boolean         mNeedInitialFocus = true;
    private boolean         mNavDump = false;
    private boolean         mSupportZoom = true;
    private boolean         mBuiltInZoomControls = false;
    private boolean         mDisplayZoomControls = true;
    private boolean         mAllowFileAccess = true;
    private boolean         mAllowContentAccess = true;
    private boolean         mLoadWithOverviewMode = false;
    private boolean         mEnableSmoothTransition = false;
    private boolean         mForceUserScalable = false;

    // AutoFill Profile data
    /**
     * @hide for now, pending API council approval.
     */
    public static class AutoFillProfile {
        private int mUniqueId;
        private String mFullName;
        private String mEmailAddress;
        private String mCompanyName;
        private String mAddressLine1;
        private String mAddressLine2;
        private String mCity;
        private String mState;
        private String mZipCode;
        private String mCountry;
        private String mPhoneNumber;

        public AutoFillProfile(int uniqueId, String fullName, String email,
                String companyName, String addressLine1, String addressLine2,
                String city, String state, String zipCode, String country,
                String phoneNumber) {
            mUniqueId = uniqueId;
            mFullName = fullName;
            mEmailAddress = email;
            mCompanyName = companyName;
            mAddressLine1 = addressLine1;
            mAddressLine2 = addressLine2;
            mCity = city;
            mState = state;
            mZipCode = zipCode;
            mCountry = country;
            mPhoneNumber = phoneNumber;
        }

        public int getUniqueId() { return mUniqueId; }
        public String getFullName() { return mFullName; }
        public String getEmailAddress() { return mEmailAddress; }
        public String getCompanyName() { return mCompanyName; }
        public String getAddressLine1() { return mAddressLine1; }
        public String getAddressLine2() { return mAddressLine2; }
        public String getCity() { return mCity; }
        public String getState() { return mState; }
        public String getZipCode() { return mZipCode; }
        public String getCountry() { return mCountry; }
        public String getPhoneNumber() { return mPhoneNumber; }
    }


    private AutoFillProfile mAutoFillProfile;

    private boolean         mUseWebViewBackgroundForOverscroll = true;

    // private WebSettings, not accessible by the host activity
    static private int      mDoubleTapToastCount = 3;

    private static final String PREF_FILE = "WebViewSettings";
    private static final String DOUBLE_TAP_TOAST_COUNT = "double_tap_toast_count";

    // Class to handle messages before WebCore is ready.
    private class EventHandler {
        // Message id for syncing
        static final int SYNC = 0;
        // Message id for setting priority
        static final int PRIORITY = 1;
        // Message id for writing double-tap toast count
        static final int SET_DOUBLE_TAP_TOAST_COUNT = 2;
        // Actual WebCore thread handler
        private Handler mHandler;

        private synchronized void createHandler() {
            // as mRenderPriority can be set before thread is running, sync up
            setRenderPriority();

            // create a new handler
            mHandler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case SYNC:
                            synchronized (WebSettings.this) {
                                if (mBrowserFrame.mNativeFrame != 0) {
                                    nativeSync(mBrowserFrame.mNativeFrame);
                                }
                                mSyncPending = false;
                            }
                            break;

                        case PRIORITY: {
                            setRenderPriority();
                            break;
                        }

                        case SET_DOUBLE_TAP_TOAST_COUNT: {
                            SharedPreferences.Editor editor = mContext
                                    .getSharedPreferences(PREF_FILE,
                                            Context.MODE_PRIVATE).edit();
                            editor.putInt(DOUBLE_TAP_TOAST_COUNT,
                                    mDoubleTapToastCount);
                            editor.commit();
                            break;
                        }
                    }
                }
            };
        }

        private void setRenderPriority() {
            synchronized (WebSettings.this) {
                if (mRenderPriority == RenderPriority.NORMAL) {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_DEFAULT);
                } else if (mRenderPriority == RenderPriority.HIGH) {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_FOREGROUND +
                            android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
                } else if (mRenderPriority == RenderPriority.LOW) {
                    android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND);
                }
            }
        }

        /**
         * Send a message to the private queue or handler.
         */
        private synchronized boolean sendMessage(Message msg) {
            if (mHandler != null) {
                mHandler.sendMessage(msg);
                return true;
            } else {
                return false;
            }
        }
    }

    // User agent strings.
    private static final String DESKTOP_USERAGENT = "Mozilla/5.0 (X11; " +
        "Linux x86_64) AppleWebKit/534.24 (KHTML, like Gecko) " +
        "Chrome/11.0.696.34 Safari/534.24";
    private static final String IPHONE_USERAGENT =
            "Mozilla/5.0 (iPhone; U; CPU iPhone OS 3_0 like Mac OS X; en-us)"
            + " AppleWebKit/528.18 (KHTML, like Gecko) Version/4.0"
            + " Mobile/7A341 Safari/528.16";
    private static Locale sLocale;
    private static Object sLockForLocaleSettings;

    /**
     * Package constructor to prevent clients from creating a new settings
     * instance.
     */
    WebSettings(Context context, WebView webview) {
        mEventHandler = new EventHandler();
        mContext = context;
        mWebView = webview;
        mDefaultTextEncoding = context.getString(com.android.internal.
                                                 R.string.default_text_encoding);

        if (sLockForLocaleSettings == null) {
            sLockForLocaleSettings = new Object();
            sLocale = Locale.getDefault();
        }
        mAcceptLanguage = getCurrentAcceptLanguage();
        mUserAgent = getCurrentUserAgent();
        mUseDefaultUserAgent = true;

        mBlockNetworkLoads = mContext.checkPermission(
                "android.permission.INTERNET", android.os.Process.myPid(),
                android.os.Process.myUid()) != PackageManager.PERMISSION_GRANTED;
    }

    private static final String ACCEPT_LANG_FOR_US_LOCALE = "en-US";

    /**
     * Looks at sLocale and returns current AcceptLanguage String.
     * @return Current AcceptLanguage String.
     */
    private String getCurrentAcceptLanguage() {
        Locale locale;
        synchronized(sLockForLocaleSettings) {
            locale = sLocale;
        }
        StringBuilder buffer = new StringBuilder();
        addLocaleToHttpAcceptLanguage(buffer, locale);

        if (!Locale.US.equals(locale)) {
            if (buffer.length() > 0) {
                buffer.append(", ");
            }
            buffer.append(ACCEPT_LANG_FOR_US_LOCALE);
        }

        return buffer.toString();
    }

    /**
     * Convert obsolete language codes, including Hebrew/Indonesian/Yiddish,
     * to new standard.
     */
    private static String convertObsoleteLanguageCodeToNew(String langCode) {
        if (langCode == null) {
            return null;
        }
        if ("iw".equals(langCode)) {
            // Hebrew
            return "he";
        } else if ("in".equals(langCode)) {
            // Indonesian
            return "id";
        } else if ("ji".equals(langCode)) {
            // Yiddish
            return "yi";
        }
        return langCode;
    }

    private static void addLocaleToHttpAcceptLanguage(StringBuilder builder,
                                                      Locale locale) {
        String language = convertObsoleteLanguageCodeToNew(locale.getLanguage());
        if (language != null) {
            builder.append(language);
            String country = locale.getCountry();
            if (country != null) {
                builder.append("-");
                builder.append(country);
            }
        }
    }

    /**
     * Looks at sLocale and mContext and returns current UserAgent String.
     * @return Current UserAgent String.
     */
    private synchronized String getCurrentUserAgent() {
        Locale locale;
        synchronized(sLockForLocaleSettings) {
            locale = sLocale;
        }
        StringBuffer buffer = new StringBuffer();
        // Add version
        final String version = Build.VERSION.RELEASE;
        if (version.length() > 0) {
            if (Character.isDigit(version.charAt(0))) {
                // Release is a version, eg "3.1"
                buffer.append(version);
            } else {
                // Release is a codename, eg "Honeycomb"
                // In this case, use the previous release's version
                buffer.append(PREVIOUS_VERSION);
            }
        } else {
            // default to "1.0"
            buffer.append("1.0");
        }
        buffer.append("; ");
        final String language = locale.getLanguage();
        if (language != null) {
            buffer.append(convertObsoleteLanguageCodeToNew(language));
            final String country = locale.getCountry();
            if (country != null) {
                buffer.append("-");
                buffer.append(country.toLowerCase());
            }
        } else {
            // default to "en"
            buffer.append("en");
        }
        buffer.append(";");
        // add the model for the release build
        if ("REL".equals(Build.VERSION.CODENAME)) {
            final String model = Build.MODEL;
            if (model.length() > 0) {
                buffer.append(" ");
                buffer.append(model);
            }
        }
        final String id = Build.ID;
        if (id.length() > 0) {
            buffer.append(" Build/");
            buffer.append(id);
        }
        String mobile = mContext.getResources().getText(
            com.android.internal.R.string.web_user_agent_target_content).toString();
        final String base = mContext.getResources().getText(
                com.android.internal.R.string.web_user_agent).toString();
        return String.format(base, buffer, mobile);
    }

    /**
     * Enables dumping the pages navigation cache to a text file.
     * @deprecated This method is now obsolete.
     */
    @Deprecated
    public void setNavDump(boolean enabled) {
        mNavDump = enabled;
    }

    /**
     * Returns true if dumping the navigation cache is enabled.
     * @deprecated This method is now obsolete.
     */
    @Deprecated
    public boolean getNavDump() {
        return mNavDump;
    }

    /**
     * If WebView only supports touch, a different navigation model will be
     * applied. Otherwise, the navigation to support both touch and keyboard
     * will be used.
     * @hide
    public void setSupportTouchOnly(boolean touchOnly) {
        mSupportTounchOnly = touchOnly;
    }
     */

    boolean supportTouchOnly() {
        // for debug only, use mLightTouchEnabled for mSupportTounchOnly
        return mLightTouchEnabled;
    }

    /**
     * Set whether the WebView supports zoom
     */
    public void setSupportZoom(boolean support) {
        mSupportZoom = support;
        mWebView.updateMultiTouchSupport(mContext);
    }

    /**
     * Returns whether the WebView supports zoom
     */
    public boolean supportZoom() {
        return mSupportZoom;
    }

    /**
     * Sets whether the zoom mechanism built into WebView is used.
     */
    public void setBuiltInZoomControls(boolean enabled) {
        mBuiltInZoomControls = enabled;
        mWebView.updateMultiTouchSupport(mContext);
    }

    /**
     * Returns true if the zoom mechanism built into WebView is being used.
     */
    public boolean getBuiltInZoomControls() {
        return mBuiltInZoomControls;
    }

    /**
     * Sets whether the on screen zoom buttons are used.
     * A combination of built in zoom controls enabled
     * and on screen zoom controls disabled allows for pinch to zoom
     * to work without the on screen controls
     */
    public void setDisplayZoomControls(boolean enabled) {
        mDisplayZoomControls = enabled;
        mWebView.updateMultiTouchSupport(mContext);
    }

    /**
     * Returns true if the on screen zoom buttons are being used.
     */
    public boolean getDisplayZoomControls() {
        return mDisplayZoomControls;
    }

    /**
     * Enable or disable file access within WebView. File access is enabled by
     * default.  Note that this enables or disables file system access only.
     * Assets and resources are still accessible using file:///android_asset and
     * file:///android_res.
     */
    public void setAllowFileAccess(boolean allow) {
        mAllowFileAccess = allow;
    }

    /**
     * Returns true if this WebView supports file access.
     */
    public boolean getAllowFileAccess() {
        return mAllowFileAccess;
    }

    /**
     * Enable or disable content url access within WebView.  Content url access
     * allows WebView to load content from a content provider installed in the
     * system.  The default is enabled.
     */
    public void setAllowContentAccess(boolean allow) {
        mAllowContentAccess = allow;
    }

    /**
     * Returns true if this WebView supports content url access.
     */
    public boolean getAllowContentAccess() {
        return mAllowContentAccess;
    }

    /**
     * Set whether the WebView loads a page with overview mode.
     */
    public void setLoadWithOverviewMode(boolean overview) {
        mLoadWithOverviewMode = overview;
    }

    /**
     * Returns true if this WebView loads page with overview mode
     */
    public boolean getLoadWithOverviewMode() {
        return mLoadWithOverviewMode;
    }

    /**
     * Set whether the WebView will enable smooth transition while panning or
     * zooming or while the window hosting the WebView does not have focus.
     * If it is true, WebView will choose a solution to maximize the performance.
     * e.g. the WebView's content may not be updated during the transition.
     * If it is false, WebView will keep its fidelity. The default value is false.
     */
    public void setEnableSmoothTransition(boolean enable) {
        mEnableSmoothTransition = enable;
    }

    /**
     * Returns true if the WebView enables smooth transition while panning or
     * zooming.
     */
    public boolean enableSmoothTransition() {
        return mEnableSmoothTransition;
    }

    /**
     * Set whether the WebView uses its background for over scroll background.
     * If true, it will use the WebView's background. If false, it will use an
     * internal pattern. Default is true.
     * @deprecated This method is now obsolete.
     */
    @Deprecated
    public void setUseWebViewBackgroundForOverscrollBackground(boolean view) {
        mUseWebViewBackgroundForOverscroll = view;
    }

    /**
     * Returns true if this WebView uses WebView's background instead of
     * internal pattern for over scroll background.
     * @deprecated This method is now obsolete.
     */
    @Deprecated
    public boolean getUseWebViewBackgroundForOverscrollBackground() {
        return mUseWebViewBackgroundForOverscroll;
    }

    /**
     * Store whether the WebView is saving form data.
     */
    public void setSaveFormData(boolean save) {
        mSaveFormData = save;
    }

    /**
     *  Return whether the WebView is saving form data and displaying prior
     *  entries/autofill++.  Always false in private browsing mode.
     */
    public boolean getSaveFormData() {
        return mSaveFormData && !mPrivateBrowsingEnabled;
    }

    /**
     *  Store whether the WebView is saving password.
     */
    public void setSavePassword(boolean save) {
        mSavePassword = save;
    }

    /**
     *  Return whether the WebView is saving password.
     */
    public boolean getSavePassword() {
        return mSavePassword;
    }

    /**
     * Set the text zoom of the page in percent. Default is 100.
     * @param textZoom A percent value for increasing or decreasing the text.
     */
    public synchronized void setTextZoom(int textZoom) {
        if (mTextSize != textZoom) {
            if (WebView.mLogEvent) {
                EventLog.writeEvent(EventLogTags.BROWSER_TEXT_SIZE_CHANGE,
                        mTextSize, textZoom);
            }
            mTextSize = textZoom;
            postSync();
        }
    }

    /**
     * Get the text zoom of the page in percent.
     * @return A percent value describing the text zoom.
     * @see setTextSizeZoom
     */
    public synchronized int getTextZoom() {
        return mTextSize;
    }

    /**
     * Set the text size of the page.
     * @param t A TextSize value for increasing or decreasing the text.
     * @see WebSettings.TextSize
     * @deprecated Use {@link #setTextZoom(int)} instead
     */
    public synchronized void setTextSize(TextSize t) {
        setTextZoom(t.value);
    }

    /**
     * Get the text size of the page. If the text size was previously specified
     * in percent using {@link #setTextZoom(int)}, this will return
     * the closest matching {@link TextSize}.
     * @return A TextSize enum value describing the text size.
     * @see WebSettings.TextSize
     * @deprecated Use {@link #getTextZoom()} instead
     */
    public synchronized TextSize getTextSize() {
        TextSize closestSize = null;
        int smallestDelta = Integer.MAX_VALUE;
        for (TextSize size : TextSize.values()) {
            int delta = Math.abs(mTextSize - size.value);
            if (delta == 0) {
                return size;
            }
            if (delta < smallestDelta) {
                smallestDelta = delta;
                closestSize = size;
            }
        }
        return closestSize != null ? closestSize : TextSize.NORMAL;
    }

    /**
     * Set the double-tap zoom of the page in percent. Default is 100.
     * @param doubleTapZoom A percent value for increasing or decreasing the double-tap zoom.
     * @hide
     */
    public void setDoubleTapZoom(int doubleTapZoom) {
        if (mDoubleTapZoom != doubleTapZoom) {
            mDoubleTapZoom = doubleTapZoom;
            mWebView.updateDoubleTapZoom(doubleTapZoom);
        }
    }

    /**
     * Get the double-tap zoom of the page in percent.
     * @return A percent value describing the double-tap zoom.
     * @hide
     */
    public int getDoubleTapZoom() {
        return mDoubleTapZoom;
    }

    /**
     * Set the default zoom density of the page. This should be called from UI
     * thread.
     * @param zoom A ZoomDensity value
     * @see WebSettings.ZoomDensity
     */
    public void setDefaultZoom(ZoomDensity zoom) {
        if (mDefaultZoom != zoom) {
            mDefaultZoom = zoom;
            mWebView.adjustDefaultZoomDensity(zoom.value);
        }
    }

    /**
     * Get the default zoom density of the page. This should be called from UI
     * thread.
     * @return A ZoomDensity value
     * @see WebSettings.ZoomDensity
     */
    public ZoomDensity getDefaultZoom() {
        return mDefaultZoom;
    }

    /**
     * Enables using light touches to make a selection and activate mouseovers.
     */
    public void setLightTouchEnabled(boolean enabled) {
        mLightTouchEnabled = enabled;
    }

    /**
     * Returns true if light touches are enabled.
     */
    public boolean getLightTouchEnabled() {
        return mLightTouchEnabled;
    }

    /**
     * @deprecated This setting controlled a rendering optimization
     * that is no longer present. Setting it now has no effect.
     */
    @Deprecated
    public synchronized void setUseDoubleTree(boolean use) {
        return;
    }

    /**
     * @deprecated This setting controlled a rendering optimization
     * that is no longer present. Setting it now has no effect.
     */
    @Deprecated
    public synchronized boolean getUseDoubleTree() {
        return false;
    }

    /**
     * Tell the WebView about user-agent string.
     * @param ua 0 if the WebView should use an Android user-agent string,
     *           1 if the WebView should use a desktop user-agent string.
     *
     * @deprecated Please use setUserAgentString instead.
     */
    @Deprecated
    public synchronized void setUserAgent(int ua) {
        String uaString = null;
        if (ua == 1) {
            if (DESKTOP_USERAGENT.equals(mUserAgent)) {
                return; // do nothing
            } else {
                uaString = DESKTOP_USERAGENT;
            }
        } else if (ua == 2) {
            if (IPHONE_USERAGENT.equals(mUserAgent)) {
                return; // do nothing
            } else {
                uaString = IPHONE_USERAGENT;
            }
        } else if (ua != 0) {
            return; // do nothing
        }
        setUserAgentString(uaString);
    }

    /**
     * Return user-agent as int
     * @return int  0 if the WebView is using an Android user-agent string.
     *              1 if the WebView is using a desktop user-agent string.
     *             -1 if the WebView is using user defined user-agent string.
     *
     * @deprecated Please use getUserAgentString instead.
     */
    @Deprecated
    public synchronized int getUserAgent() {
        if (DESKTOP_USERAGENT.equals(mUserAgent)) {
            return 1;
        } else if (IPHONE_USERAGENT.equals(mUserAgent)) {
            return 2;
        } else if (mUseDefaultUserAgent) {
            return 0;
        }
        return -1;
    }

    /**
     * Tell the WebView to use the wide viewport
     */
    public synchronized void setUseWideViewPort(boolean use) {
        if (mUseWideViewport != use) {
            mUseWideViewport = use;
            postSync();
        }
    }

    /**
     * @return True if the WebView is using a wide viewport
     */
    public synchronized boolean getUseWideViewPort() {
        return mUseWideViewport;
    }

    /**
     * Tell the WebView whether it supports multiple windows. TRUE means
     *         that {@link WebChromeClient#onCreateWindow(WebView, boolean,
     *         boolean, Message)} is implemented by the host application.
     */
    public synchronized void setSupportMultipleWindows(boolean support) {
        if (mSupportMultipleWindows != support) {
            mSupportMultipleWindows = support;
            postSync();
        }
    }

    /**
     * @return True if the WebView is supporting multiple windows. This means
     *         that {@link WebChromeClient#onCreateWindow(WebView, boolean,
     *         boolean, Message)} is implemented by the host application.
     */
    public synchronized boolean supportMultipleWindows() {
        return mSupportMultipleWindows;
    }

    /**
     * Set the underlying layout algorithm. This will cause a relayout of the
     * WebView.
     * @param l A LayoutAlgorithm enum specifying the algorithm to use.
     * @see WebSettings.LayoutAlgorithm
     */
    public synchronized void setLayoutAlgorithm(LayoutAlgorithm l) {
        // XXX: This will only be affective if libwebcore was built with
        // ANDROID_LAYOUT defined.
        if (mLayoutAlgorithm != l) {
            mLayoutAlgorithm = l;
            postSync();
        }
    }

    /**
     * Return the current layout algorithm. The default is NARROW_COLUMNS.
     * @return LayoutAlgorithm enum value describing the layout algorithm
     *         being used.
     * @see WebSettings.LayoutAlgorithm
     */
    public synchronized LayoutAlgorithm getLayoutAlgorithm() {
        return mLayoutAlgorithm;
    }

    /**
     * Set the standard font family name.
     * @param font A font family name.
     */
    public synchronized void setStandardFontFamily(String font) {
        if (font != null && !font.equals(mStandardFontFamily)) {
            mStandardFontFamily = font;
            postSync();
        }
    }

    /**
     * Get the standard font family name. The default is "sans-serif".
     * @return The standard font family name as a string.
     */
    public synchronized String getStandardFontFamily() {
        return mStandardFontFamily;
    }

    /**
     * Set the fixed font family name.
     * @param font A font family name.
     */
    public synchronized void setFixedFontFamily(String font) {
        if (font != null && !font.equals(mFixedFontFamily)) {
            mFixedFontFamily = font;
            postSync();
        }
    }

    /**
     * Get the fixed font family name. The default is "monospace".
     * @return The fixed font family name as a string.
     */
    public synchronized String getFixedFontFamily() {
        return mFixedFontFamily;
    }

    /**
     * Set the sans-serif font family name.
     * @param font A font family name.
     */
    public synchronized void setSansSerifFontFamily(String font) {
        if (font != null && !font.equals(mSansSerifFontFamily)) {
            mSansSerifFontFamily = font;
            postSync();
        }
    }

    /**
     * Get the sans-serif font family name.
     * @return The sans-serif font family name as a string.
     */
    public synchronized String getSansSerifFontFamily() {
        return mSansSerifFontFamily;
    }

    /**
     * Set the serif font family name. The default is "sans-serif".
     * @param font A font family name.
     */
    public synchronized void setSerifFontFamily(String font) {
        if (font != null && !font.equals(mSerifFontFamily)) {
            mSerifFontFamily = font;
            postSync();
        }
    }

    /**
     * Get the serif font family name. The default is "serif".
     * @return The serif font family name as a string.
     */
    public synchronized String getSerifFontFamily() {
        return mSerifFontFamily;
    }

    /**
     * Set the cursive font family name.
     * @param font A font family name.
     */
    public synchronized void setCursiveFontFamily(String font) {
        if (font != null && !font.equals(mCursiveFontFamily)) {
            mCursiveFontFamily = font;
            postSync();
        }
    }

    /**
     * Get the cursive font family name. The default is "cursive".
     * @return The cursive font family name as a string.
     */
    public synchronized String getCursiveFontFamily() {
        return mCursiveFontFamily;
    }

    /**
     * Set the fantasy font family name.
     * @param font A font family name.
     */
    public synchronized void setFantasyFontFamily(String font) {
        if (font != null && !font.equals(mFantasyFontFamily)) {
            mFantasyFontFamily = font;
            postSync();
        }
    }

    /**
     * Get the fantasy font family name. The default is "fantasy".
     * @return The fantasy font family name as a string.
     */
    public synchronized String getFantasyFontFamily() {
        return mFantasyFontFamily;
    }

    /**
     * Set the minimum font size.
     * @param size A non-negative integer between 1 and 72.
     * Any number outside the specified range will be pinned.
     */
    public synchronized void setMinimumFontSize(int size) {
        size = pin(size);
        if (mMinimumFontSize != size) {
            mMinimumFontSize = size;
            postSync();
        }
    }

    /**
     * Get the minimum font size. The default is 8.
     * @return A non-negative integer between 1 and 72.
     */
    public synchronized int getMinimumFontSize() {
        return mMinimumFontSize;
    }

    /**
     * Set the minimum logical font size.
     * @param size A non-negative integer between 1 and 72.
     * Any number outside the specified range will be pinned.
     */
    public synchronized void setMinimumLogicalFontSize(int size) {
        size = pin(size);
        if (mMinimumLogicalFontSize != size) {
            mMinimumLogicalFontSize = size;
            postSync();
        }
    }

    /**
     * Get the minimum logical font size. The default is 8.
     * @return A non-negative integer between 1 and 72.
     */
    public synchronized int getMinimumLogicalFontSize() {
        return mMinimumLogicalFontSize;
    }

    /**
     * Set the default font size.
     * @param size A non-negative integer between 1 and 72.
     * Any number outside the specified range will be pinned.
     */
    public synchronized void setDefaultFontSize(int size) {
        size = pin(size);
        if (mDefaultFontSize != size) {
            mDefaultFontSize = size;
            postSync();
        }
    }

    /**
     * Get the default font size. The default is 16.
     * @return A non-negative integer between 1 and 72.
     */
    public synchronized int getDefaultFontSize() {
        return mDefaultFontSize;
    }

    /**
     * Set the default fixed font size.
     * @param size A non-negative integer between 1 and 72.
     * Any number outside the specified range will be pinned.
     */
    public synchronized void setDefaultFixedFontSize(int size) {
        size = pin(size);
        if (mDefaultFixedFontSize != size) {
            mDefaultFixedFontSize = size;
            postSync();
        }
    }

    /**
     * Get the default fixed font size. The default is 16.
     * @return A non-negative integer between 1 and 72.
     */
    public synchronized int getDefaultFixedFontSize() {
        return mDefaultFixedFontSize;
    }

    /**
     * Set the number of pages cached by the WebKit for the history navigation.
     * @param size A non-negative integer between 0 (no cache) and 20 (max).
     * @hide
     */
    public synchronized void setPageCacheCapacity(int size) {
        if (size < 0) size = 0;
        if (size > 20) size = 20;
        if (mPageCacheCapacity != size) {
            mPageCacheCapacity = size;
            postSync();
        }
    }

    /**
     * Tell the WebView to load image resources automatically.
     * @param flag True if the WebView should load images automatically.
     */
    public synchronized void setLoadsImagesAutomatically(boolean flag) {
        if (mLoadsImagesAutomatically != flag) {
            mLoadsImagesAutomatically = flag;
            postSync();
        }
    }

    /**
     * Return true if the WebView will load image resources automatically.
     * The default is true.
     * @return True if the WebView loads images automatically.
     */
    public synchronized boolean getLoadsImagesAutomatically() {
        return mLoadsImagesAutomatically;
    }

    /**
     * Tell the WebView to block network images. This is only checked when
     * {@link #getLoadsImagesAutomatically} is true. If you set the value to
     * false, images will automatically be loaded. Use this api to reduce
     * bandwidth only. Use {@link #setBlockNetworkLoads} if possible.
     * @param flag True if the WebView should block network images.
     * @see #setBlockNetworkLoads
     */
    public synchronized void setBlockNetworkImage(boolean flag) {
        if (mBlockNetworkImage != flag) {
            mBlockNetworkImage = flag;
            postSync();
        }
    }

    /**
     * Return true if the WebView will block network images. The default is
     * false.
     * @return True if the WebView blocks network images.
     */
    public synchronized boolean getBlockNetworkImage() {
        return mBlockNetworkImage;
    }

    /**
     * Tell the WebView to block all network load requests. If you set the
     * value to false, you must call {@link android.webkit.WebView#reload} to
     * fetch remote resources. This flag supercedes the value passed to
     * {@link #setBlockNetworkImage}.
     * @param flag True if the WebView should block all network loads.
     * @see android.webkit.WebView#reload
     */
    public synchronized void setBlockNetworkLoads(boolean flag) {
        if (mBlockNetworkLoads != flag) {
            mBlockNetworkLoads = flag;
            verifyNetworkAccess();
            postSync();
        }
    }

    /**
     * Return true if the WebView will block all network loads. The default is
     * false.
     * @return True if the WebView blocks all network loads.
     */
    public synchronized boolean getBlockNetworkLoads() {
        return mBlockNetworkLoads;
    }


    private void verifyNetworkAccess() {
        if (!mBlockNetworkLoads) {
            if (mContext.checkPermission("android.permission.INTERNET",
                    android.os.Process.myPid(), android.os.Process.myUid()) !=
                        PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException
                        ("Permission denied - " +
                                "application missing INTERNET permission");
            }
        }
    }

    /**
     * Tell the WebView to enable javascript execution.
     * @param flag True if the WebView should execute javascript.
     */
    public synchronized void setJavaScriptEnabled(boolean flag) {
        if (mJavaScriptEnabled != flag) {
            mJavaScriptEnabled = flag;
            postSync();
        }
    }

    /**
     * Tell the WebView to use Skia's hardware accelerated rendering path
     * @param flag True if the WebView should use Skia's hw-accel path
     * @hide
     */
    public synchronized void setHardwareAccelSkiaEnabled(boolean flag) {
        if (mHardwareAccelSkia != flag) {
            mHardwareAccelSkia = flag;
            postSync();
        }
    }

    /**
     * @return True if the WebView is using hardware accelerated skia
     * @hide
     */
    public synchronized boolean getHardwareAccelSkiaEnabled() {
        return mHardwareAccelSkia;
    }

    /**
     * Tell the WebView to show the visual indicator
     * @param flag True if the WebView should show the visual indicator
     * @hide
     */
    public synchronized void setShowVisualIndicator(boolean flag) {
        if (mShowVisualIndicator != flag) {
            mShowVisualIndicator = flag;
            postSync();
        }
    }

    /**
     * @return True if the WebView is showing the visual indicator
     * @hide
     */
    public synchronized boolean getShowVisualIndicator() {
        return mShowVisualIndicator;
    }

    /**
     * Tell the WebView to enable plugins.
     * @param flag True if the WebView should load plugins.
     * @deprecated This method has been deprecated in favor of
     *             {@link #setPluginState}
     */
    @Deprecated
    public synchronized void setPluginsEnabled(boolean flag) {
        setPluginState(flag ? PluginState.ON : PluginState.OFF);
    }

    /**
     * Tell the WebView to enable, disable, or have plugins on demand. On
     * demand mode means that if a plugin exists that can handle the embedded
     * content, a placeholder icon will be shown instead of the plugin. When
     * the placeholder is clicked, the plugin will be enabled.
     * @param state One of the PluginState values.
     */
    public synchronized void setPluginState(PluginState state) {
        if (mPluginState != state) {
            mPluginState = state;
            postSync();
        }
    }

    /**
     * Set a custom path to plugins used by the WebView. This method is
     * obsolete since each plugin is now loaded from its own package.
     * @param pluginsPath String path to the directory containing plugins.
     * @deprecated This method is no longer used as plugins are loaded from
     * their own APK via the system's package manager.
     */
    @Deprecated
    public synchronized void setPluginsPath(String pluginsPath) {
    }

    /**
     * Set the path to where database storage API databases should be saved.
     * Nota that the WebCore Database Tracker only allows the path to be set once.
     * This will update WebCore when the Sync runs in the C++ side.
     * @param databasePath String path to the directory where databases should
     *     be saved. May be the empty string but should never be null.
     */
    public synchronized void setDatabasePath(String databasePath) {
        if (databasePath != null && !mDatabasePathHasBeenSet) {
            mDatabasePath = databasePath;
            mDatabasePathHasBeenSet = true;
            postSync();
        }
    }

    /**
     * Set the path where the Geolocation permissions database should be saved.
     * This will update WebCore when the Sync runs in the C++ side.
     * @param databasePath String path to the directory where the Geolocation
     *     permissions database should be saved. May be the empty string but
     *     should never be null.
     */
    public synchronized void setGeolocationDatabasePath(String databasePath) {
        if (databasePath != null
                && !databasePath.equals(mGeolocationDatabasePath)) {
            mGeolocationDatabasePath = databasePath;
            postSync();
        }
    }

    /**
     * Enable or disable the Application Cache API.
     * @param flag Whether to enable the Application Cache API.
     */
    public synchronized void setAppCacheEnabled(boolean flag) {
        if (mAppCacheEnabled != flag) {
            mAppCacheEnabled = flag;
            postSync();
        }
    }

    /**
     * Set the path used by the Application Cache API to store files. This
     * setting is applied to all WebViews in the application. In order for the
     * Application Cache API to function, this method must be called with a
     * path which exists and is writable by the application. This method may
     * only be called once: repeated calls are ignored.
     * @param path Path to the directory that should be used to store Application
     * Cache files.
     */
    public synchronized void setAppCachePath(String path) {
        // We test for a valid path and for repeated setting on the native
        // side, but we can avoid syncing in some simple cases. 
        if (mAppCachePath == null && path != null && !path.isEmpty()) {
            mAppCachePath = path;
            postSync();
        }
    }

    /**
     * Set the maximum size for the Application Caches content.
     * @param appCacheMaxSize the maximum size in bytes.
     */
    public synchronized void setAppCacheMaxSize(long appCacheMaxSize) {
        if (appCacheMaxSize != mAppCacheMaxSize) {
            mAppCacheMaxSize = appCacheMaxSize;
            postSync();
        }
    }

    /**
     * Set whether the database storage API is enabled.
     * @param flag boolean True if the WebView should use the database storage
     *     API.
     */
    public synchronized void setDatabaseEnabled(boolean flag) {
       if (mDatabaseEnabled != flag) {
           mDatabaseEnabled = flag;
           postSync();
       }
    }

    /**
     * Set whether the DOM storage API is enabled.
     * @param flag boolean True if the WebView should use the DOM storage
     *     API.
     */
    public synchronized void setDomStorageEnabled(boolean flag) {
       if (mDomStorageEnabled != flag) {
           mDomStorageEnabled = flag;
           postSync();
       }
    }

    /**
     * Returns true if the DOM Storage API's are enabled.
     * @return True if the DOM Storage API's are enabled.
     */
    public synchronized boolean getDomStorageEnabled() {
       return mDomStorageEnabled;
    }

    /**
     * Return the path to where database storage API databases are saved for
     * the current WebView.
     * @return the String path to the database storage API databases.
     */
    public synchronized String getDatabasePath() {
        return mDatabasePath;
    }

    /**
     * Returns true if database storage API is enabled.
     * @return True if the database storage API is enabled.
     */
    public synchronized boolean getDatabaseEnabled() {
        return mDatabaseEnabled;
    }

    /**
     * Tell the WebView to enable WebWorkers API.
     * @param flag True if the WebView should enable WebWorkers.
     * Note that this flag only affects V8. JSC does not have
     * an equivalent setting.
     * @hide pending api council approval
     */
    public synchronized void setWorkersEnabled(boolean flag) {
        if (mWorkersEnabled != flag) {
            mWorkersEnabled = flag;
            postSync();
        }
    }

    /**
     * Sets whether Geolocation is enabled.
     * @param flag Whether Geolocation should be enabled.
     */
    public synchronized void setGeolocationEnabled(boolean flag) {
        if (mGeolocationEnabled != flag) {
            mGeolocationEnabled = flag;
            postSync();
        }
    }

    /**
     * Sets whether XSS Auditor is enabled.
     * @param flag Whether XSS Auditor should be enabled.
     * @hide Only used by LayoutTestController.
     */
    public synchronized void setXSSAuditorEnabled(boolean flag) {
        if (mXSSAuditorEnabled != flag) {
            mXSSAuditorEnabled = flag;
            postSync();
        }
    }

    /**
     * Return true if javascript is enabled. <b>Note: The default is false.</b>
     * @return True if javascript is enabled.
     */
    public synchronized boolean getJavaScriptEnabled() {
        return mJavaScriptEnabled;
    }

    /**
     * Return true if plugins are enabled.
     * @return True if plugins are enabled.
     * @deprecated This method has been replaced by {@link #getPluginState}
     */
    @Deprecated
    public synchronized boolean getPluginsEnabled() {
        return mPluginState == PluginState.ON;
    }

    /**
     * Return the current plugin state.
     * @return A value corresponding to the enum PluginState.
     */
    public synchronized PluginState getPluginState() {
        return mPluginState;
    }

    /**
     * Returns the directory that contains the plugin libraries. This method is
     * obsolete since each plugin is now loaded from its own package.
     * @return An empty string.
     * @deprecated This method is no longer used as plugins are loaded from
     * their own APK via the system's package manager.
     */
    @Deprecated
    public synchronized String getPluginsPath() {
        return "";
    }

    /**
     * Tell javascript to open windows automatically. This applies to the
     * javascript function window.open().
     * @param flag True if javascript can open windows automatically.
     */
    public synchronized void setJavaScriptCanOpenWindowsAutomatically(
            boolean flag) {
        if (mJavaScriptCanOpenWindowsAutomatically != flag) {
            mJavaScriptCanOpenWindowsAutomatically = flag;
            postSync();
        }
    }

    /**
     * Return true if javascript can open windows automatically. The default
     * is false.
     * @return True if javascript can open windows automatically during
     *         window.open().
     */
    public synchronized boolean getJavaScriptCanOpenWindowsAutomatically() {
        return mJavaScriptCanOpenWindowsAutomatically;
    }

    /**
     * Set the default text encoding name to use when decoding html pages.
     * @param encoding The text encoding name.
     */
    public synchronized void setDefaultTextEncodingName(String encoding) {
        if (encoding != null && !encoding.equals(mDefaultTextEncoding)) {
            mDefaultTextEncoding = encoding;
            postSync();
        }
    }

    /**
     * Get the default text encoding name. The default is "Latin-1".
     * @return The default text encoding name as a string.
     */
    public synchronized String getDefaultTextEncodingName() {
        return mDefaultTextEncoding;
    }

    /**
     * Set the WebView's user-agent string. If the string "ua" is null or empty,
     * it will use the system default user-agent string.
     */
    public synchronized void setUserAgentString(String ua) {
        if (ua == null || ua.length() == 0) {
            synchronized(sLockForLocaleSettings) {
                Locale currentLocale = Locale.getDefault();
                if (!sLocale.equals(currentLocale)) {
                    sLocale = currentLocale;
                    mAcceptLanguage = getCurrentAcceptLanguage();
                }
            }
            ua = getCurrentUserAgent();
            mUseDefaultUserAgent = true;
        } else  {
            mUseDefaultUserAgent = false;
        }

        if (!ua.equals(mUserAgent)) {
            mUserAgent = ua;
            postSync();
        }
    }

    /**
     * Return the WebView's user-agent string.
     */
    public synchronized String getUserAgentString() {
        if (DESKTOP_USERAGENT.equals(mUserAgent) ||
                IPHONE_USERAGENT.equals(mUserAgent) ||
                !mUseDefaultUserAgent) {
            return mUserAgent;
        }

        boolean doPostSync = false;
        synchronized(sLockForLocaleSettings) {
            Locale currentLocale = Locale.getDefault();
            if (!sLocale.equals(currentLocale)) {
                sLocale = currentLocale;
                mUserAgent = getCurrentUserAgent();
                mAcceptLanguage = getCurrentAcceptLanguage();
                doPostSync = true;
            }
        }
        if (doPostSync) {
            postSync();
        }
        return mUserAgent;
    }

    /* package api to grab the Accept Language string. */
    /*package*/ synchronized String getAcceptLanguage() {
        synchronized(sLockForLocaleSettings) {
            Locale currentLocale = Locale.getDefault();
            if (!sLocale.equals(currentLocale)) {
                sLocale = currentLocale;
                mAcceptLanguage = getCurrentAcceptLanguage();
            }
        }
        return mAcceptLanguage;
    }

    /* package */ boolean isNarrowColumnLayout() {
        return getLayoutAlgorithm() == LayoutAlgorithm.NARROW_COLUMNS;
    }

    /**
     * Tell the WebView whether it needs to set a node to have focus when
     * {@link WebView#requestFocus(int, android.graphics.Rect)} is called.
     *
     * @param flag
     */
    public void setNeedInitialFocus(boolean flag) {
        if (mNeedInitialFocus != flag) {
            mNeedInitialFocus = flag;
        }
    }

    /* Package api to get the choice whether it needs to set initial focus. */
    /* package */ boolean getNeedInitialFocus() {
        return mNeedInitialFocus;
    }

    /**
     * Set the priority of the Render thread. Unlike the other settings, this
     * one only needs to be called once per process. The default is NORMAL.
     *
     * @param priority RenderPriority, can be normal, high or low.
     */
    public synchronized void setRenderPriority(RenderPriority priority) {
        if (mRenderPriority != priority) {
            mRenderPriority = priority;
            mEventHandler.sendMessage(Message.obtain(null,
                    EventHandler.PRIORITY));
        }
    }

    /**
     * Override the way the cache is used. The way the cache is used is based
     * on the navigation option. For a normal page load, the cache is checked
     * and content is re-validated as needed. When navigating back, content is
     * not revalidated, instead the content is just pulled from the cache.
     * This function allows the client to override this behavior.
     * @param mode One of the LOAD_ values.
     */
    public void setCacheMode(int mode) {
        if (mode != mOverrideCacheMode) {
            mOverrideCacheMode = mode;
            postSync();
        }
    }

    /**
     * Return the current setting for overriding the cache mode. For a full
     * description, see the {@link #setCacheMode(int)} function.
     */
    public int getCacheMode() {
        return mOverrideCacheMode;
    }

    /**
     * If set, webkit alternately shrinks and expands images viewed outside
     * of an HTML page to fit the screen. This conflicts with attempts by
     * the UI to zoom in and out of an image, so it is set false by default.
     * @param shrink Set true to let webkit shrink the standalone image to fit.
     * {@hide}
     */
    public void setShrinksStandaloneImagesToFit(boolean shrink) {
        if (mShrinksStandaloneImagesToFit != shrink) {
            mShrinksStandaloneImagesToFit = shrink;
            postSync();
        }
     }

    /**
     * Specify the maximum decoded image size. The default is
     * 2 megs for small memory devices and 8 megs for large memory devices.
     * @param size The maximum decoded size, or zero to set to the default.
     * @hide pending api council approval
     */
    public void setMaximumDecodedImageSize(long size) {
        if (mMaximumDecodedImageSize != size) {
            mMaximumDecodedImageSize = size;
            postSync();
        }
    }

    /**
     * Returns whether to use fixed viewport.  Use fixed viewport
     * whenever wide viewport is on.
     */
    /* package */ boolean getUseFixedViewport() {
        return getUseWideViewPort();
    }

    /**
     * Returns whether private browsing is enabled.
     */
    /* package */ boolean isPrivateBrowsingEnabled() {
        return mPrivateBrowsingEnabled;
    }

    /**
     * Sets whether private browsing is enabled.
     * @param flag Whether private browsing should be enabled.
     */
    /* package */ synchronized void setPrivateBrowsingEnabled(boolean flag) {
        if (mPrivateBrowsingEnabled != flag) {
            mPrivateBrowsingEnabled = flag;

            // AutoFill is dependant on private browsing being enabled so
            // reset it to take account of the new value of mPrivateBrowsingEnabled.
            setAutoFillEnabled(mAutoFillEnabled);

            postSync();
        }
    }

    /**
     * Returns whether the viewport metatag can disable zooming
     * @hide
     */
    public boolean forceUserScalable() {
        return mForceUserScalable;
    }

    /**
     * Sets whether viewport metatag can disable zooming.
     * @param flag Whether or not to forceably enable user scalable.
     * @hide
     */
    public synchronized void setForceUserScalable(boolean flag) {
        mForceUserScalable = flag;
    }

    synchronized void setSyntheticLinksEnabled(boolean flag) {
        if (mSyntheticLinksEnabled != flag) {
            mSyntheticLinksEnabled = flag;
            postSync();
        }
    }

    /**
     * @hide
     */
    public synchronized void setAutoFillEnabled(boolean enabled) {
        // AutoFill is always disabled in private browsing mode.
        boolean autoFillEnabled = enabled && !mPrivateBrowsingEnabled;
        if (mAutoFillEnabled != autoFillEnabled) {
            mAutoFillEnabled = autoFillEnabled;
            postSync();
        }
    }

    /**
     * @hide
     */
    public synchronized boolean getAutoFillEnabled() {
        return mAutoFillEnabled;
    }

    /**
     * @hide
     */
    public synchronized void setAutoFillProfile(AutoFillProfile profile) {
        if (mAutoFillProfile != profile) {
            mAutoFillProfile = profile;
            postSync();
        }
    }

    /**
     * @hide
     */
    public synchronized AutoFillProfile getAutoFillProfile() {
        return mAutoFillProfile;
    }

    int getDoubleTapToastCount() {
        return mDoubleTapToastCount;
    }

    void setDoubleTapToastCount(int count) {
        if (mDoubleTapToastCount != count) {
            mDoubleTapToastCount = count;
            // write the settings in the non-UI thread
            mEventHandler.sendMessage(Message.obtain(null,
                    EventHandler.SET_DOUBLE_TAP_TOAST_COUNT));
        }
    }

    /**
     * @hide
     */
    public void setProperty(String key, String value) {
        if (mWebView.nativeSetProperty(key, value)) {
            mWebView.contentInvalidateAll();
        }
    }

    /**
     * @hide
     */
    public String getProperty(String key) {
        return mWebView.nativeGetProperty(key);
    }

    /**
     * Transfer messages from the queue to the new WebCoreThread. Called from
     * WebCore thread.
     */
    /*package*/
    synchronized void syncSettingsAndCreateHandler(BrowserFrame frame) {
        mBrowserFrame = frame;
        if (DebugFlags.WEB_SETTINGS) {
            junit.framework.Assert.assertTrue(frame.mNativeFrame != 0);
        }

        SharedPreferences sp = mContext.getSharedPreferences(PREF_FILE,
                Context.MODE_PRIVATE);
        if (mDoubleTapToastCount > 0) {
            mDoubleTapToastCount = sp.getInt(DOUBLE_TAP_TOAST_COUNT,
                    mDoubleTapToastCount);
        }
        nativeSync(frame.mNativeFrame);
        mSyncPending = false;
        mEventHandler.createHandler();
    }

    /**
     * Let the Settings object know that our owner is being destroyed.
     */
    /*package*/
    synchronized void onDestroyed() {
    }

    private int pin(int size) {
        // FIXME: 72 is just an arbitrary max text size value.
        if (size < 1) {
            return 1;
        } else if (size > 72) {
            return 72;
        }
        return size;
    }

    /* Post a SYNC message to handle syncing the native settings. */
    private synchronized void postSync() {
        // Only post if a sync is not pending
        if (!mSyncPending) {
            mSyncPending = mEventHandler.sendMessage(
                    Message.obtain(null, EventHandler.SYNC));
        }
    }

    // Synchronize the native and java settings.
    private native void nativeSync(int nativeFrame);
}
