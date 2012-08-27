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
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.EventLog;

import java.util.Locale;

/**
 * WebSettings implementation for the WebViewClassic implementation of WebView.
 * @hide
 */
public class WebSettingsClassic extends WebSettings {
    // TODO: Keep this up to date
    private static final String PREVIOUS_VERSION = "4.1.1";

    // WebView associated with this WebSettings.
    private WebViewClassic mWebView;
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
    private boolean         mAllowUniversalAccessFromFileURLs = false;
    private boolean         mAllowFileAccessFromFileURLs = false;
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
    private boolean         mLinkPrefetchEnabled = false;
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
    private boolean         mMediaPlaybackRequiresUserGesture = true;
    private boolean         mBuiltInZoomControls = false;
    private boolean         mDisplayZoomControls = true;
    private boolean         mAllowFileAccess = true;
    private boolean         mAllowContentAccess = true;
    private boolean         mLoadWithOverviewMode = false;
    private boolean         mEnableSmoothTransition = false;
    private boolean         mForceUserScalable = false;
    private boolean         mPasswordEchoEnabled = true;

    // AutoFill Profile data
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
                            synchronized (WebSettingsClassic.this) {
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
            synchronized (WebSettingsClassic.this) {
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
    WebSettingsClassic(Context context, WebViewClassic webview) {
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

        // SDK specific settings. See issue 6212665
        if (mContext.getApplicationInfo().targetSdkVersion <
                Build.VERSION_CODES.JELLY_BEAN) {
            mAllowUniversalAccessFromFileURLs = true;
            mAllowFileAccessFromFileURLs = true;
        }
        try {
            mPasswordEchoEnabled =
                    Settings.System.getInt(context.getContentResolver(),
                        Settings.System.TEXT_SHOW_PASSWORD) != 0;
        } catch (SettingNotFoundException e) {
            mPasswordEchoEnabled = true;
        }
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
        return getDefaultUserAgentForLocale(mContext, locale);
    }

    /**
     * Returns the default User-Agent used by a WebView.
     * An instance of WebView could use a different User-Agent if a call
     * is made to {@link WebSettings#setUserAgent(int)} or
     * {@link WebSettings#setUserAgentString(String)}.
     *
     * @param context a Context object used to access application assets
     * @param locale The Locale to use in the User-Agent string.
     * @see WebViewFactoryProvider#getDefaultUserAgent(Context)
     * @see WebView#getDefaultUserAgent(Context)
     */
    public static String getDefaultUserAgentForLocale(Context context, Locale locale) {
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
        String mobile = context.getResources().getText(
            com.android.internal.R.string.web_user_agent_target_content).toString();
        final String base = context.getResources().getText(
                com.android.internal.R.string.web_user_agent).toString();
        return String.format(base, buffer, mobile);
    }

    /**
     * @see android.webkit.WebSettings#setNavDump(boolean)
     */
    @Override
    @Deprecated
    public void setNavDump(boolean enabled) {
        mNavDump = enabled;
    }

    /**
     * @see android.webkit.WebSettings#getNavDump()
     */
    @Override
    @Deprecated
    public boolean getNavDump() {
        return mNavDump;
    }

    /**
     * @see android.webkit.WebSettings#setSupportZoom(boolean)
     */
    @Override
    public void setSupportZoom(boolean support) {
        mSupportZoom = support;
        mWebView.updateMultiTouchSupport(mContext);
    }

    /**
     * @see android.webkit.WebSettings#supportZoom()
     */
    @Override
    public boolean supportZoom() {
        return mSupportZoom;
    }

    /**
     * @see android.webkit.WebSettings#setMediaPlaybackRequiresUserGesture(boolean)
     */
    @Override
    public void setMediaPlaybackRequiresUserGesture(boolean support) {
        if (mMediaPlaybackRequiresUserGesture != support) {
            mMediaPlaybackRequiresUserGesture = support;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getMediaPlaybackRequiresUserGesture()
     */
    @Override
    public boolean getMediaPlaybackRequiresUserGesture() {
        return mMediaPlaybackRequiresUserGesture;
    }

    /**
     * @see android.webkit.WebSettings#setBuiltInZoomControls(boolean)
     */
    @Override
    public void setBuiltInZoomControls(boolean enabled) {
        mBuiltInZoomControls = enabled;
        mWebView.updateMultiTouchSupport(mContext);
    }

    /**
     * @see android.webkit.WebSettings#getBuiltInZoomControls()
     */
    @Override
    public boolean getBuiltInZoomControls() {
        return mBuiltInZoomControls;
    }

    /**
     * @see android.webkit.WebSettings#setDisplayZoomControls(boolean)
     */
    @Override
    public void setDisplayZoomControls(boolean enabled) {
        mDisplayZoomControls = enabled;
        mWebView.updateMultiTouchSupport(mContext);
    }

    /**
     * @see android.webkit.WebSettings#getDisplayZoomControls()
     */
    @Override
    public boolean getDisplayZoomControls() {
        return mDisplayZoomControls;
    }

    /**
     * @see android.webkit.WebSettings#setAllowFileAccess(boolean)
     */
    @Override
    public void setAllowFileAccess(boolean allow) {
        mAllowFileAccess = allow;
    }

    /**
     * @see android.webkit.WebSettings#getAllowFileAccess()
     */
    @Override
    public boolean getAllowFileAccess() {
        return mAllowFileAccess;
    }

    /**
     * @see android.webkit.WebSettings#setAllowContentAccess(boolean)
     */
    @Override
    public void setAllowContentAccess(boolean allow) {
        mAllowContentAccess = allow;
    }

    /**
     * @see android.webkit.WebSettings#getAllowContentAccess()
     */
    @Override
    public boolean getAllowContentAccess() {
        return mAllowContentAccess;
    }

    /**
     * @see android.webkit.WebSettings#setLoadWithOverviewMode(boolean)
     */
    @Override
    public void setLoadWithOverviewMode(boolean overview) {
        mLoadWithOverviewMode = overview;
    }

    /**
     * @see android.webkit.WebSettings#getLoadWithOverviewMode()
     */
    @Override
    public boolean getLoadWithOverviewMode() {
        return mLoadWithOverviewMode;
    }

    /**
     * @see android.webkit.WebSettings#setEnableSmoothTransition(boolean)
     */
    @Override
    public void setEnableSmoothTransition(boolean enable) {
        mEnableSmoothTransition = enable;
    }

    /**
     * @see android.webkit.WebSettings#enableSmoothTransition()
     */
    @Override
    public boolean enableSmoothTransition() {
        return mEnableSmoothTransition;
    }

    /**
     * @see android.webkit.WebSettings#setUseWebViewBackgroundForOverscrollBackground(boolean)
     */
    @Override
    @Deprecated
    public void setUseWebViewBackgroundForOverscrollBackground(boolean view) {
        mUseWebViewBackgroundForOverscroll = view;
    }

    /**
     * @see android.webkit.WebSettings#getUseWebViewBackgroundForOverscrollBackground()
     */
    @Override
    @Deprecated
    public boolean getUseWebViewBackgroundForOverscrollBackground() {
        return mUseWebViewBackgroundForOverscroll;
    }

    /**
     * @see android.webkit.WebSettings#setSaveFormData(boolean)
     */
    @Override
    public void setSaveFormData(boolean save) {
        mSaveFormData = save;
    }

    /**
     * @see android.webkit.WebSettings#getSaveFormData()
     */
    @Override
    public boolean getSaveFormData() {
        return mSaveFormData && !mPrivateBrowsingEnabled;
    }

    /**
     * @see android.webkit.WebSettings#setSavePassword(boolean)
     */
    @Override
    public void setSavePassword(boolean save) {
        mSavePassword = save;
    }

    /**
     * @see android.webkit.WebSettings#getSavePassword()
     */
    @Override
    public boolean getSavePassword() {
        return mSavePassword;
    }

    /**
     * @see android.webkit.WebSettings#setTextZoom(int)
     */
    @Override
    public synchronized void setTextZoom(int textZoom) {
        if (mTextSize != textZoom) {
            if (WebViewClassic.mLogEvent) {
                EventLog.writeEvent(EventLogTags.BROWSER_TEXT_SIZE_CHANGE,
                        mTextSize, textZoom);
            }
            mTextSize = textZoom;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getTextZoom()
     */
    @Override
    public synchronized int getTextZoom() {
        return mTextSize;
    }

    /**
     * Set the double-tap zoom of the page in percent. Default is 100.
     * @param doubleTapZoom A percent value for increasing or decreasing the double-tap zoom.
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
     */
    public int getDoubleTapZoom() {
        return mDoubleTapZoom;
    }

    /**
     * @see android.webkit.WebSettings#setDefaultZoom(android.webkit.WebSettingsClassic.ZoomDensity)
     */
    @Override
    public void setDefaultZoom(ZoomDensity zoom) {
        if (mDefaultZoom != zoom) {
            mDefaultZoom = zoom;
            mWebView.adjustDefaultZoomDensity(zoom.value);
        }
    }

    /**
     * @see android.webkit.WebSettings#getDefaultZoom()
     */
    @Override
    public ZoomDensity getDefaultZoom() {
        return mDefaultZoom;
    }

    /**
     * @see android.webkit.WebSettings#setLightTouchEnabled(boolean)
     */
    @Override
    public void setLightTouchEnabled(boolean enabled) {
        mLightTouchEnabled = enabled;
    }

    /**
     * @see android.webkit.WebSettings#getLightTouchEnabled()
     */
    @Override
    public boolean getLightTouchEnabled() {
        return mLightTouchEnabled;
    }

    /**
     * @see android.webkit.WebSettings#setUseDoubleTree(boolean)
     */
    @Override
    @Deprecated
    public synchronized void setUseDoubleTree(boolean use) {
        return;
    }

    /**
     * @see android.webkit.WebSettings#getUseDoubleTree()
     */
    @Override
    @Deprecated
    public synchronized boolean getUseDoubleTree() {
        return false;
    }

    /**
     * @see android.webkit.WebSettings#setUserAgent(int)
     */
    @Override
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
     * @see android.webkit.WebSettings#getUserAgent()
     */
    @Override
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
     * @see android.webkit.WebSettings#setUseWideViewPort(boolean)
     */
    @Override
    public synchronized void setUseWideViewPort(boolean use) {
        if (mUseWideViewport != use) {
            mUseWideViewport = use;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getUseWideViewPort()
     */
    @Override
    public synchronized boolean getUseWideViewPort() {
        return mUseWideViewport;
    }

    /**
     * @see android.webkit.WebSettings#setSupportMultipleWindows(boolean)
     */
    @Override
    public synchronized void setSupportMultipleWindows(boolean support) {
        if (mSupportMultipleWindows != support) {
            mSupportMultipleWindows = support;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#supportMultipleWindows()
     */
    @Override
    public synchronized boolean supportMultipleWindows() {
        return mSupportMultipleWindows;
    }

    /**
     * @see android.webkit.WebSettings#setLayoutAlgorithm(android.webkit.WebSettingsClassic.LayoutAlgorithm)
     */
    @Override
    public synchronized void setLayoutAlgorithm(LayoutAlgorithm l) {
        // XXX: This will only be affective if libwebcore was built with
        // ANDROID_LAYOUT defined.
        if (mLayoutAlgorithm != l) {
            mLayoutAlgorithm = l;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getLayoutAlgorithm()
     */
    @Override
    public synchronized LayoutAlgorithm getLayoutAlgorithm() {
        return mLayoutAlgorithm;
    }

    /**
     * @see android.webkit.WebSettings#setStandardFontFamily(java.lang.String)
     */
    @Override
    public synchronized void setStandardFontFamily(String font) {
        if (font != null && !font.equals(mStandardFontFamily)) {
            mStandardFontFamily = font;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getStandardFontFamily()
     */
    @Override
    public synchronized String getStandardFontFamily() {
        return mStandardFontFamily;
    }

    /**
     * @see android.webkit.WebSettings#setFixedFontFamily(java.lang.String)
     */
    @Override
    public synchronized void setFixedFontFamily(String font) {
        if (font != null && !font.equals(mFixedFontFamily)) {
            mFixedFontFamily = font;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getFixedFontFamily()
     */
    @Override
    public synchronized String getFixedFontFamily() {
        return mFixedFontFamily;
    }

    /**
     * @see android.webkit.WebSettings#setSansSerifFontFamily(java.lang.String)
     */
    @Override
    public synchronized void setSansSerifFontFamily(String font) {
        if (font != null && !font.equals(mSansSerifFontFamily)) {
            mSansSerifFontFamily = font;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getSansSerifFontFamily()
     */
    @Override
    public synchronized String getSansSerifFontFamily() {
        return mSansSerifFontFamily;
    }

    /**
     * @see android.webkit.WebSettings#setSerifFontFamily(java.lang.String)
     */
    @Override
    public synchronized void setSerifFontFamily(String font) {
        if (font != null && !font.equals(mSerifFontFamily)) {
            mSerifFontFamily = font;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getSerifFontFamily()
     */
    @Override
    public synchronized String getSerifFontFamily() {
        return mSerifFontFamily;
    }

    /**
     * @see android.webkit.WebSettings#setCursiveFontFamily(java.lang.String)
     */
    @Override
    public synchronized void setCursiveFontFamily(String font) {
        if (font != null && !font.equals(mCursiveFontFamily)) {
            mCursiveFontFamily = font;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getCursiveFontFamily()
     */
    @Override
    public synchronized String getCursiveFontFamily() {
        return mCursiveFontFamily;
    }

    /**
     * @see android.webkit.WebSettings#setFantasyFontFamily(java.lang.String)
     */
    @Override
    public synchronized void setFantasyFontFamily(String font) {
        if (font != null && !font.equals(mFantasyFontFamily)) {
            mFantasyFontFamily = font;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getFantasyFontFamily()
     */
    @Override
    public synchronized String getFantasyFontFamily() {
        return mFantasyFontFamily;
    }

    /**
     * @see android.webkit.WebSettings#setMinimumFontSize(int)
     */
    @Override
    public synchronized void setMinimumFontSize(int size) {
        size = pin(size);
        if (mMinimumFontSize != size) {
            mMinimumFontSize = size;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getMinimumFontSize()
     */
    @Override
    public synchronized int getMinimumFontSize() {
        return mMinimumFontSize;
    }

    /**
     * @see android.webkit.WebSettings#setMinimumLogicalFontSize(int)
     */
    @Override
    public synchronized void setMinimumLogicalFontSize(int size) {
        size = pin(size);
        if (mMinimumLogicalFontSize != size) {
            mMinimumLogicalFontSize = size;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getMinimumLogicalFontSize()
     */
    @Override
    public synchronized int getMinimumLogicalFontSize() {
        return mMinimumLogicalFontSize;
    }

    /**
     * @see android.webkit.WebSettings#setDefaultFontSize(int)
     */
    @Override
    public synchronized void setDefaultFontSize(int size) {
        size = pin(size);
        if (mDefaultFontSize != size) {
            mDefaultFontSize = size;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getDefaultFontSize()
     */
    @Override
    public synchronized int getDefaultFontSize() {
        return mDefaultFontSize;
    }

    /**
     * @see android.webkit.WebSettings#setDefaultFixedFontSize(int)
     */
    @Override
    public synchronized void setDefaultFixedFontSize(int size) {
        size = pin(size);
        if (mDefaultFixedFontSize != size) {
            mDefaultFixedFontSize = size;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getDefaultFixedFontSize()
     */
    @Override
    public synchronized int getDefaultFixedFontSize() {
        return mDefaultFixedFontSize;
    }

    /**
     * Set the number of pages cached by the WebKit for the history navigation.
     * @param size A non-negative integer between 0 (no cache) and 20 (max).
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
     * @see android.webkit.WebSettings#setLoadsImagesAutomatically(boolean)
     */
    @Override
    public synchronized void setLoadsImagesAutomatically(boolean flag) {
        if (mLoadsImagesAutomatically != flag) {
            mLoadsImagesAutomatically = flag;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getLoadsImagesAutomatically()
     */
    @Override
    public synchronized boolean getLoadsImagesAutomatically() {
        return mLoadsImagesAutomatically;
    }

    /**
     * @see android.webkit.WebSettings#setBlockNetworkImage(boolean)
     */
    @Override
    public synchronized void setBlockNetworkImage(boolean flag) {
        if (mBlockNetworkImage != flag) {
            mBlockNetworkImage = flag;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getBlockNetworkImage()
     */
    @Override
    public synchronized boolean getBlockNetworkImage() {
        return mBlockNetworkImage;
    }

    /**
     * @see android.webkit.WebSettings#setBlockNetworkLoads(boolean)
     */
    @Override
    public synchronized void setBlockNetworkLoads(boolean flag) {
        if (mBlockNetworkLoads != flag) {
            mBlockNetworkLoads = flag;
            verifyNetworkAccess();
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getBlockNetworkLoads()
     */
    @Override
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
     * @see android.webkit.WebSettings#setJavaScriptEnabled(boolean)
     */
    @Override
    public synchronized void setJavaScriptEnabled(boolean flag) {
        if (mJavaScriptEnabled != flag) {
            mJavaScriptEnabled = flag;
            postSync();
            mWebView.updateJavaScriptEnabled(flag);
        }
    }

    /**
     * @see android.webkit.WebSettings#setAllowUniversalAccessFromFileURLs
     */
    @Override
    public synchronized void setAllowUniversalAccessFromFileURLs(boolean flag) {
        if (mAllowUniversalAccessFromFileURLs != flag) {
            mAllowUniversalAccessFromFileURLs = flag;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#setAllowFileAccessFromFileURLs
     */
    @Override
    public synchronized void setAllowFileAccessFromFileURLs(boolean flag) {
        if (mAllowFileAccessFromFileURLs != flag) {
            mAllowFileAccessFromFileURLs = flag;
            postSync();
        }
    }

    /**
     * Tell the WebView to use Skia's hardware accelerated rendering path
     * @param flag True if the WebView should use Skia's hw-accel path
     */
    public synchronized void setHardwareAccelSkiaEnabled(boolean flag) {
        if (mHardwareAccelSkia != flag) {
            mHardwareAccelSkia = flag;
            postSync();
        }
    }

    /**
     * @return True if the WebView is using hardware accelerated skia
     */
    public synchronized boolean getHardwareAccelSkiaEnabled() {
        return mHardwareAccelSkia;
    }

    /**
     * Tell the WebView to show the visual indicator
     * @param flag True if the WebView should show the visual indicator
     */
    public synchronized void setShowVisualIndicator(boolean flag) {
        if (mShowVisualIndicator != flag) {
            mShowVisualIndicator = flag;
            postSync();
        }
    }

    /**
     * @return True if the WebView is showing the visual indicator
     */
    public synchronized boolean getShowVisualIndicator() {
        return mShowVisualIndicator;
    }

    /**
     * @see android.webkit.WebSettings#setPluginsEnabled(boolean)
     */
    @Override
    @Deprecated
    public synchronized void setPluginsEnabled(boolean flag) {
        setPluginState(flag ? PluginState.ON : PluginState.OFF);
    }

    /**
     * @see android.webkit.WebSettings#setPluginState(android.webkit.WebSettingsClassic.PluginState)
     */
    @Override
    public synchronized void setPluginState(PluginState state) {
        if (mPluginState != state) {
            mPluginState = state;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#setPluginsPath(java.lang.String)
     */
    @Override
    @Deprecated
    public synchronized void setPluginsPath(String pluginsPath) {
    }

    /**
     * @see android.webkit.WebSettings#setDatabasePath(java.lang.String)
     */
    @Override
    public synchronized void setDatabasePath(String databasePath) {
        if (databasePath != null && !mDatabasePathHasBeenSet) {
            mDatabasePath = databasePath;
            mDatabasePathHasBeenSet = true;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#setGeolocationDatabasePath(java.lang.String)
     */
    @Override
    public synchronized void setGeolocationDatabasePath(String databasePath) {
        if (databasePath != null
                && !databasePath.equals(mGeolocationDatabasePath)) {
            mGeolocationDatabasePath = databasePath;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#setAppCacheEnabled(boolean)
     */
    @Override
    public synchronized void setAppCacheEnabled(boolean flag) {
        if (mAppCacheEnabled != flag) {
            mAppCacheEnabled = flag;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#setAppCachePath(java.lang.String)
     */
    @Override
    public synchronized void setAppCachePath(String path) {
        // We test for a valid path and for repeated setting on the native
        // side, but we can avoid syncing in some simple cases. 
        if (mAppCachePath == null && path != null && !path.isEmpty()) {
            mAppCachePath = path;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#setAppCacheMaxSize(long)
     */
    @Override
    public synchronized void setAppCacheMaxSize(long appCacheMaxSize) {
        if (appCacheMaxSize != mAppCacheMaxSize) {
            mAppCacheMaxSize = appCacheMaxSize;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#setDatabaseEnabled(boolean)
     */
    @Override
    public synchronized void setDatabaseEnabled(boolean flag) {
       if (mDatabaseEnabled != flag) {
           mDatabaseEnabled = flag;
           postSync();
       }
    }

    /**
     * @see android.webkit.WebSettings#setDomStorageEnabled(boolean)
     */
    @Override
    public synchronized void setDomStorageEnabled(boolean flag) {
       if (mDomStorageEnabled != flag) {
           mDomStorageEnabled = flag;
           postSync();
       }
    }

    /**
     * @see android.webkit.WebSettings#getDomStorageEnabled()
     */
    @Override
    public synchronized boolean getDomStorageEnabled() {
       return mDomStorageEnabled;
    }

    /**
     * @see android.webkit.WebSettings#getDatabasePath()
     */
    @Override
    public synchronized String getDatabasePath() {
        return mDatabasePath;
    }

    /**
     * @see android.webkit.WebSettings#getDatabaseEnabled()
     */
    @Override
    public synchronized boolean getDatabaseEnabled() {
        return mDatabaseEnabled;
    }

    /**
     * Tell the WebView to enable WebWorkers API.
     * @param flag True if the WebView should enable WebWorkers.
     * Note that this flag only affects V8. JSC does not have
     * an equivalent setting.
     */
    public synchronized void setWorkersEnabled(boolean flag) {
        if (mWorkersEnabled != flag) {
            mWorkersEnabled = flag;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#setGeolocationEnabled(boolean)
     */
    @Override
    public synchronized void setGeolocationEnabled(boolean flag) {
        if (mGeolocationEnabled != flag) {
            mGeolocationEnabled = flag;
            postSync();
        }
    }

    /**
     * Sets whether XSS Auditor is enabled.
     * Only used by LayoutTestController.
     * @param flag Whether XSS Auditor should be enabled.
     */
    public synchronized void setXSSAuditorEnabled(boolean flag) {
        if (mXSSAuditorEnabled != flag) {
            mXSSAuditorEnabled = flag;
            postSync();
        }
    }

    /**
     * Enables/disables HTML5 link "prefetch" parameter.
     */
    public synchronized void setLinkPrefetchEnabled(boolean flag) {
        if (mLinkPrefetchEnabled != flag) {
            mLinkPrefetchEnabled = flag;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getJavaScriptEnabled()
     */
    @Override
    public synchronized boolean getJavaScriptEnabled() {
        return mJavaScriptEnabled;
    }

    /**
     * @see android.webkit.WebSettings#getAllowUniversalFileAccessFromFileURLs
     */
    @Override
    public synchronized boolean getAllowUniversalAccessFromFileURLs() {
        return mAllowUniversalAccessFromFileURLs;
    }

    /**
     * @see android.webkit.WebSettings#getAllowFileAccessFromFileURLs
     */
    @Override
    public synchronized boolean getAllowFileAccessFromFileURLs() {
        return mAllowFileAccessFromFileURLs;
    }

    /**
     * @see android.webkit.WebSettings#getPluginsEnabled()
     */
    @Override
    @Deprecated
    public synchronized boolean getPluginsEnabled() {
        return mPluginState == PluginState.ON;
    }

    /**
     * @see android.webkit.WebSettings#getPluginState()
     */
    @Override
    public synchronized PluginState getPluginState() {
        return mPluginState;
    }

    /**
     * @see android.webkit.WebSettings#getPluginsPath()
     */
    @Override
    @Deprecated
    public synchronized String getPluginsPath() {
        return "";
    }

    /**
     * @see android.webkit.WebSettings#setJavaScriptCanOpenWindowsAutomatically(boolean)
     */
    @Override
    public synchronized void setJavaScriptCanOpenWindowsAutomatically(
            boolean flag) {
        if (mJavaScriptCanOpenWindowsAutomatically != flag) {
            mJavaScriptCanOpenWindowsAutomatically = flag;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getJavaScriptCanOpenWindowsAutomatically()
     */
    @Override
    public synchronized boolean getJavaScriptCanOpenWindowsAutomatically() {
        return mJavaScriptCanOpenWindowsAutomatically;
    }

    /**
     * @see android.webkit.WebSettings#setDefaultTextEncodingName(java.lang.String)
     */
    @Override
    public synchronized void setDefaultTextEncodingName(String encoding) {
        if (encoding != null && !encoding.equals(mDefaultTextEncoding)) {
            mDefaultTextEncoding = encoding;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getDefaultTextEncodingName()
     */
    @Override
    public synchronized String getDefaultTextEncodingName() {
        return mDefaultTextEncoding;
    }

    /**
     * @see android.webkit.WebSettings#setUserAgentString(java.lang.String)
     */
    @Override
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
     * @see android.webkit.WebSettings#getUserAgentString()
     */
    @Override
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
     * @see android.webkit.WebSettings#setNeedInitialFocus(boolean)
     */
    @Override
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
     * @see android.webkit.WebSettings#setRenderPriority(android.webkit.WebSettingsClassic.RenderPriority)
     */
    @Override
    public synchronized void setRenderPriority(RenderPriority priority) {
        if (mRenderPriority != priority) {
            mRenderPriority = priority;
            mEventHandler.sendMessage(Message.obtain(null,
                    EventHandler.PRIORITY));
        }
    }

    /**
     * @see android.webkit.WebSettings#setCacheMode(int)
     */
    @Override
    public void setCacheMode(int mode) {
        if (mode != mOverrideCacheMode) {
            mOverrideCacheMode = mode;
            postSync();
        }
    }

    /**
     * @see android.webkit.WebSettings#getCacheMode()
     */
    @Override
    public int getCacheMode() {
        return mOverrideCacheMode;
    }

    /**
     * If set, webkit alternately shrinks and expands images viewed outside
     * of an HTML page to fit the screen. This conflicts with attempts by
     * the UI to zoom in and out of an image, so it is set false by default.
     * @param shrink Set true to let webkit shrink the standalone image to fit.
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
     */
    public boolean forceUserScalable() {
        return mForceUserScalable;
    }

    /**
     * Sets whether viewport metatag can disable zooming.
     * @param flag Whether or not to forceably enable user scalable.
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

    public synchronized void setAutoFillEnabled(boolean enabled) {
        // AutoFill is always disabled in private browsing mode.
        boolean autoFillEnabled = enabled && !mPrivateBrowsingEnabled;
        if (mAutoFillEnabled != autoFillEnabled) {
            mAutoFillEnabled = autoFillEnabled;
            postSync();
        }
    }

    public synchronized boolean getAutoFillEnabled() {
        return mAutoFillEnabled;
    }

    public synchronized void setAutoFillProfile(AutoFillProfile profile) {
        if (mAutoFillProfile != profile) {
            mAutoFillProfile = profile;
            postSync();
        }
    }

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

    public void setProperty(String key, String value) {
        if (mWebView.nativeSetProperty(key, value)) {
            mWebView.invalidate();
        }
    }

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
