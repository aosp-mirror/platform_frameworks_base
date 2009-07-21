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
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.provider.Checkin;

import java.lang.SecurityException;
import android.content.pm.PackageManager;

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
     * @hide Pending API council approval
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
    // XXX: The default values need to match those in WebSettings.cpp
    private LayoutAlgorithm mLayoutAlgorithm = LayoutAlgorithm.NARROW_COLUMNS;
    private Context         mContext;
    private TextSize        mTextSize = TextSize.NORMAL;
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
    private String          mPluginsPath = "";
    private int             mMinimumFontSize = 8;
    private int             mMinimumLogicalFontSize = 8;
    private int             mDefaultFontSize = 16;
    private int             mDefaultFixedFontSize = 13;
    private boolean         mLoadsImagesAutomatically = true;
    private boolean         mBlockNetworkImage = false;
    private boolean         mBlockNetworkLoads;
    private boolean         mJavaScriptEnabled = false;
    private boolean         mPluginsEnabled = false;
    private boolean         mJavaScriptCanOpenWindowsAutomatically = false;
    private boolean         mUseDoubleTree = false;
    private boolean         mUseWideViewport = false;
    private boolean         mSupportMultipleWindows = false;
    private boolean         mShrinksStandaloneImagesToFit = false;
    // Don't need to synchronize the get/set methods as they
    // are basic types, also none of these values are used in
    // native WebCore code.
    private ZoomDensity     mDefaultZoom = ZoomDensity.MEDIUM;
    private RenderPriority  mRenderPriority = RenderPriority.NORMAL;
    private int             mOverrideCacheMode = LOAD_DEFAULT;
    private boolean         mSaveFormData = true;
    private boolean         mSavePassword = true;
    private boolean         mLightTouchEnabled = false;
    private boolean         mNeedInitialFocus = true;
    private boolean         mNavDump = false;
    private boolean         mSupportZoom = true;
    private boolean         mBuiltInZoomControls = false;
    private boolean         mAllowFileAccess = true;

    // Class to handle messages before WebCore is ready.
    private class EventHandler {
        // Message id for syncing
        static final int SYNC = 0;
        // Message id for setting priority
        static final int PRIORITY = 1;
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
    private static final String DESKTOP_USERAGENT =
            "Mozilla/5.0 (Macintosh; U; Intel Mac OS X; en)"
            + " AppleWebKit/528.5+ (KHTML, like Gecko) Version/3.1.2"
            + " Safari/525.20.1";
    private static final String IPHONE_USERAGENT = 
            "Mozilla/5.0 (iPhone; U; CPU iPhone 2_1 like Mac OS X; en)"
            + " AppleWebKit/528.5+ (KHTML, like Gecko) Version/3.1.2"
            + " Mobile/5F136 Safari/525.20.1";
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

    /**
     * Looks at sLocale and returns current AcceptLanguage String.
     * @return Current AcceptLanguage String.
     */
    private String getCurrentAcceptLanguage() {
        Locale locale;
        synchronized(sLockForLocaleSettings) {
            locale = sLocale;
        }
        StringBuffer buffer = new StringBuffer();
        final String language = locale.getLanguage();
        if (language != null) {
            buffer.append(language);
            final String country = locale.getCountry();
            if (country != null) {
                buffer.append("-");
                buffer.append(country);
            }
        }
        if (!locale.equals(Locale.US)) {
            buffer.append(", ");
            java.util.Locale us = Locale.US;
            if (us.getLanguage() != null) {
                buffer.append(us.getLanguage());
                final String country = us.getCountry();
                if (country != null) {
                    buffer.append("-");
                    buffer.append(country);
                }
            }
        }

        return buffer.toString();
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
            buffer.append(version);
        } else {
            // default to "1.0"
            buffer.append("1.0");
        }  
        buffer.append("; ");
        final String language = locale.getLanguage();
        if (language != null) {
            buffer.append(language.toLowerCase());
            final String country = locale.getCountry();
            if (country != null) {
                buffer.append("-");
                buffer.append(country.toLowerCase());
            }
        } else {
            // default to "en"
            buffer.append("en");
        }
        
        final String model = Build.MODEL;
        if (model.length() > 0) {
            buffer.append("; ");
            buffer.append(model);
        }
        final String id = Build.ID;
        if (id.length() > 0) {
            buffer.append(" Build/");
            buffer.append(id);
        }
        final String base = mContext.getResources().getText(
                com.android.internal.R.string.web_user_agent).toString();
        return String.format(base, buffer);
    }
    
    /**
     * Enables dumping the pages navigation cache to a text file.
     */
    public void setNavDump(boolean enabled) {
        mNavDump = enabled;
    }

    /**
     * Returns true if dumping the navigation cache is enabled.
     */
    public boolean getNavDump() {
        return mNavDump;
    }

    /**
     * Set whether the WebView supports zoom
     */
    public void setSupportZoom(boolean support) {
        mSupportZoom = support;
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
    }
    
    /**
     * Returns true if the zoom mechanism built into WebView is being used.
     */
    public boolean getBuiltInZoomControls() {
        return mBuiltInZoomControls;
    }
    
    /**
     * Enable or disable file access within WebView. File access is enabled by
     * default.
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
     * Store whether the WebView is saving form data.
     */
    public void setSaveFormData(boolean save) {
        mSaveFormData = save;
    }

    /**
     *  Return whether the WebView is saving form data.
     */
    public boolean getSaveFormData() {
        return mSaveFormData;
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
     * Set the text size of the page.
     * @param t A TextSize value for increasing or decreasing the text.
     * @see WebSettings.TextSize
     */
    public synchronized void setTextSize(TextSize t) {
        if (WebView.mLogEvent && mTextSize != t ) {
            Checkin.updateStats(mContext.getContentResolver(),
                    Checkin.Stats.Tag.BROWSER_TEXT_SIZE_CHANGE, 1, 0.0);
        }
        mTextSize = t;
        postSync();
    }

    /**
     * Get the text size of the page.
     * @return A TextSize enum value describing the text size.
     * @see WebSettings.TextSize
     */
    public synchronized TextSize getTextSize() {
        return mTextSize;
    }

    /**
     * Set the default zoom density of the page. This should be called from UI
     * thread.
     * @param zoom A ZoomDensity value
     * @see WebSettings.ZoomDensity
     * @hide Pending API council approval
     */
    public void setDefaultZoom(ZoomDensity zoom) {
        if (mDefaultZoom != zoom) {
            mDefaultZoom = zoom;
            mWebView.updateDefaultZoomDensity(zoom.value);
        }
    }

    /**
     * Get the default zoom density of the page. This should be called from UI
     * thread.
     * @return A ZoomDensity value
     * @see WebSettings.ZoomDensity
     * @hide Pending API council approval
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
     * Tell the WebView to use the double tree rendering algorithm.
     * @param use True if the WebView is to use double tree rendering, false
     *            otherwise.
     */
    public synchronized void setUseDoubleTree(boolean use) {
        if (mUseDoubleTree != use) {
            mUseDoubleTree = use;
            postSync();
        }
    }

    /**
     * Return true if the WebView is using the double tree rendering algorithm.
     * @return True if the WebView is using the double tree rendering
     *         algorithm.
     */
    public synchronized boolean getUseDoubleTree() {
        return mUseDoubleTree;
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
     * Return the current layout algorithm.
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
     * Get the standard font family name.
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
     * Get the fixed font family name.
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
     * Set the serif font family name.
     * @param font A font family name.
     */
    public synchronized void setSerifFontFamily(String font) {
        if (font != null && !font.equals(mSerifFontFamily)) {
            mSerifFontFamily = font;
            postSync();
        }
    }

    /**
     * Get the serif font family name.
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
     * Get the cursive font family name.
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
     * Get the fantasy font family name.
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
     * Get the minimum font size.
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
     * Get the minimum logical font size.
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
     * Get the default font size.
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
     * Get the default fixed font size.
     * @return A non-negative integer between 1 and 72.
     */
    public synchronized int getDefaultFixedFontSize() {
        return mDefaultFixedFontSize;
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
     * @return True if the WebView loads images automatically.
     */
    public synchronized boolean getLoadsImagesAutomatically() {
        return mLoadsImagesAutomatically;
    }

    /**
     * Tell the WebView to block network image. This is only checked when
     * getLoadsImagesAutomatically() is true.
     * @param flag True if the WebView should block network image
     */
    public synchronized void setBlockNetworkImage(boolean flag) {
        if (mBlockNetworkImage != flag) {
            mBlockNetworkImage = flag;
            postSync();
        }
    }

    /**
     * Return true if the WebView will block network image.
     * @return True if the WebView blocks network image.
     */
    public synchronized boolean getBlockNetworkImage() {
        return mBlockNetworkImage;
    }
    
    /**
     * @hide
     * Tell the WebView to block all network load requests. 
     * @param flag True if the WebView should block all network loads
     */
    public synchronized void setBlockNetworkLoads(boolean flag) {
        if (mBlockNetworkLoads != flag) {
            mBlockNetworkLoads = flag;
            verifyNetworkAccess();
        }
    }

    /**
     * @hide
     * Return true if the WebView will block all network loads.
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
     * Tell the WebView to enable plugins.
     * @param flag True if the WebView should load plugins.
     */
    public synchronized void setPluginsEnabled(boolean flag) {
        if (mPluginsEnabled != flag) {
            mPluginsEnabled = flag;
            postSync();
        }
    }

    /**
     * Set a custom path to plugins used by the WebView. The client
     * must ensure it exists before this call.
     * @param pluginsPath String path to the directory containing plugins.
     */
    public synchronized void setPluginsPath(String pluginsPath) {
        if (pluginsPath != null && !pluginsPath.equals(mPluginsPath)) {
            mPluginsPath = pluginsPath;
            postSync();
        }
    }

    /**
     * Return true if javascript is enabled.
     * @return True if javascript is enabled.
     */
    public synchronized boolean getJavaScriptEnabled() {
        return mJavaScriptEnabled;
    }

    /**
     * Return true if plugins are enabled.
     * @return True if plugins are enabled.
     */
    public synchronized boolean getPluginsEnabled() {
        return mPluginsEnabled;
    }

    /**
     * Return the current path used for plugins in the WebView.
     * @return The string path to the WebView plugins.
     */
    public synchronized String getPluginsPath() {
        return mPluginsPath;
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
     * Return true if javascript can open windows automatically.
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
     * Get the default text encoding name.
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
     * one only needs to be called once per process.
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
     * Transfer messages from the queue to the new WebCoreThread. Called from
     * WebCore thread.
     */
    /*package*/
    synchronized void syncSettingsAndCreateHandler(BrowserFrame frame) {
        mBrowserFrame = frame;
        if (WebView.DEBUG) {
            junit.framework.Assert.assertTrue(frame.mNativeFrame != 0);
        }
        nativeSync(frame.mNativeFrame);
        mSyncPending = false;
        mEventHandler.createHandler();
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
