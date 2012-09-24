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

/**
 * Manages settings state for a WebView. When a WebView is first created, it
 * obtains a set of default settings. These default settings will be returned
 * from any getter call. A WebSettings object obtained from
 * WebView.getSettings() is tied to the life of the WebView. If a WebView has
 * been destroyed, any method call on WebSettings will throw an
 * IllegalStateException.
 */
// This is an abstract base class: concrete WebViewProviders must
// create a class derived from this, and return an instance of it in the
// WebViewProvider.getWebSettingsProvider() method implementation.
public abstract class WebSettings {
    /**
     * Enum for controlling the layout of html.
     * <ul>
     *   <li>NORMAL means no rendering changes.</li>
     *   <li>SINGLE_COLUMN moves all content into one column that is the width of the
     *       view.</li>
     *   <li>NARROW_COLUMNS makes all columns no wider than the screen if possible.</li>
     * </ul>
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
     * <ul>
     *   <li>SMALLEST is 50%</li>
     *   <li>SMALLER is 75%</li>
     *   <li>NORMAL is 100%</li>
     *   <li>LARGER is 150%</li>
     *   <li>LARGEST is 200%</li>
     * </ul>
     *
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
     * <ul>
     *   <li>FAR makes 100% looking like in 240dpi</li>
     *   <li>MEDIUM makes 100% looking like in 160dpi</li>
     *   <li>CLOSE makes 100% looking like in 120dpi</li>
     * </ul>
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
     * Default cache usage mode. If the navigation type doesn't impose any
     * specific behavior, use cached resources when they are available
     * and not expired, otherwise load resources from the network.
     * Use with {@link #setCacheMode}.
     */
    public static final int LOAD_DEFAULT = -1;

    /**
     * Normal cache usage mode. Use with {@link #setCacheMode}.
     *
     * @deprecated This value is obsolete, as from API level
     * {@link android.os.Build.VERSION_CODES#HONEYCOMB} and onwards it has the
     * same effect as {@link #LOAD_DEFAULT}.
     */
    @Deprecated
    public static final int LOAD_NORMAL = 0;

    /**
     * Use cached resources when they are available, even if they have expired.
     * Otherwise load resources from the network.
     * Use with {@link #setCacheMode}.
     */
    public static final int LOAD_CACHE_ELSE_NETWORK = 1;

    /**
     * Don't use the cache, load from the network.
     * Use with {@link #setCacheMode}.
     */
    public static final int LOAD_NO_CACHE = 2;

    /**
     * Don't use the network, load from the cache.
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

    /**
     * Hidden constructor to prevent clients from creating a new settings
     * instance or deriving the class.
     *
     * @hide
     */
    protected WebSettings() {
    }

    /**
     * Enables dumping the pages navigation cache to a text file. The default
     * is false.
     *
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public void setNavDump(boolean enabled) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether dumping the navigation cache is enabled.
     *
     * @return whether dumping the navigation cache is enabled
     * @see #setNavDump
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public boolean getNavDump() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView should support zooming using its on-screen zoom
     * controls and gestures. The particular zoom mechanisms that should be used
     * can be set with {@link #setBuiltInZoomControls}. This setting does not
     * affect zooming performed using the {@link WebView#zoomIn()} and
     * {@link WebView#zoomOut()} methods. The default is true.
     *
     * @param support whether the WebView should support zoom
     */
    public void setSupportZoom(boolean support) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView supports zoom.
     *
     * @return true if the WebView supports zoom
     * @see #setSupportZoom
     */
    public boolean supportZoom() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView requires a user gesture to play media.
     * The default is true.
     *
     * @param require whether the WebView requires a user gesture to play media
     */
    public void setMediaPlaybackRequiresUserGesture(boolean require) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView requires a user gesture to play media.
     *
     * @return true if the WebView requires a user gesture to play media
     * @see #setMediaPlaybackRequiresUserGesture
     */
    public boolean getMediaPlaybackRequiresUserGesture() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView should use its built-in zoom mechanisms. The
     * built-in zoom mechanisms comprise on-screen zoom controls, which are
     * displayed over the WebView's content, and the use of a pinch gesture to
     * control zooming. Whether or not these on-screen controls are displayed
     * can be set with {@link #setDisplayZoomControls}. The default is false.
     * <p>
     * The built-in mechanisms are the only currently supported zoom
     * mechanisms, so it is recommended that this setting is always enabled.
     *
     * @param enabled whether the WebView should use its built-in zoom mechanisms
     */
    // This method was intended to select between the built-in zoom mechanisms
    // and the separate zoom controls. The latter were obtained using
    // {@link WebView#getZoomControls}, which is now hidden.
    public void setBuiltInZoomControls(boolean enabled) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the zoom mechanisms built into WebView are being used.
     *
     * @return true if the zoom mechanisms built into WebView are being used
     * @see #setBuiltInZoomControls
     */
    public boolean getBuiltInZoomControls() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView should display on-screen zoom controls when
     * using the built-in zoom mechanisms. See {@link #setBuiltInZoomControls}.
     * The default is true.
     *
     * @param enabled whether the WebView should display on-screen zoom controls
     */
    public void setDisplayZoomControls(boolean enabled) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView displays on-screen zoom controls when using
     * the built-in zoom mechanisms.
     *
     * @return true if the WebView displays on-screen zoom controls when using
     *         the built-in zoom mechanisms
     * @see #setDisplayZoomControls
     */
    public boolean getDisplayZoomControls() {
        throw new MustOverrideException();
    }

    /**
     * Enables or disables file access within WebView. File access is enabled by
     * default.  Note that this enables or disables file system access only.
     * Assets and resources are still accessible using file:///android_asset and
     * file:///android_res.
     */
    public void setAllowFileAccess(boolean allow) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether this WebView supports file access.
     *
     * @see #setAllowFileAccess
     */
    public boolean getAllowFileAccess() {
        throw new MustOverrideException();
    }

    /**
     * Enables or disables content URL access within WebView.  Content URL
     * access allows WebView to load content from a content provider installed
     * in the system. The default is enabled.
     */
    public void setAllowContentAccess(boolean allow) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether this WebView supports content URL access.
     *
     * @see #setAllowContentAccess
     */
    public boolean getAllowContentAccess() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView loads pages in overview mode. The default is
     * false.
     */
    public void setLoadWithOverviewMode(boolean overview) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether this WebView loads pages in overview mode.
     *
     * @return whether this WebView loads pages in overview mode
     * @see #setLoadWithOverviewMode
     */
    public boolean getLoadWithOverviewMode() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView will enable smooth transition while panning or
     * zooming or while the window hosting the WebView does not have focus.
     * If it is true, WebView will choose a solution to maximize the performance.
     * e.g. the WebView's content may not be updated during the transition.
     * If it is false, WebView will keep its fidelity. The default value is false.
     *
     * @deprecated This method is now obsolete, and will become a no-op in future.
     */
    @Deprecated
    public void setEnableSmoothTransition(boolean enable) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView enables smooth transition while panning or
     * zooming.
     *
     * @see #setEnableSmoothTransition
     *
     * @deprecated This method is now obsolete, and will become a no-op in future.
     */
    @Deprecated
    public boolean enableSmoothTransition() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView uses its background for over scroll background.
     * If true, it will use the WebView's background. If false, it will use an
     * internal pattern. Default is true.
     *
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public void setUseWebViewBackgroundForOverscrollBackground(boolean view) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether this WebView uses WebView's background instead of
     * internal pattern for over scroll background.
     *
     * @see #setUseWebViewBackgroundForOverscrollBackground
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public boolean getUseWebViewBackgroundForOverscrollBackground() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView should save form data. The default is true,
     * unless in private browsing mode, when the value is always false.
     */
    public void setSaveFormData(boolean save) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView saves form data. Always false in private
     * browsing mode.
     *
     * @return whether the WebView saves form data
     * @see #setSaveFormData
     */
    public boolean getSaveFormData() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView should save passwords. The default is true.
     */
    public void setSavePassword(boolean save) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView saves passwords.
     *
     * @return whether the WebView saves passwords
     * @see #setSavePassword
     */
    public boolean getSavePassword() {
        throw new MustOverrideException();
    }

    /**
     * Sets the text zoom of the page in percent. The default is 100.
     *
     * @param textZoom the text zoom in percent
     */
    public synchronized void setTextZoom(int textZoom) {
        throw new MustOverrideException();
    }

    /**
     * Gets the text zoom of the page in percent.
     *
     * @return the text zoom of the page in percent
     * @see #setTextZoom
     */
    public synchronized int getTextZoom() {
        throw new MustOverrideException();
    }

    /**
     * Sets the text size of the page. The default is {@link TextSize#NORMAL}.
     *
     * @param t the text size as a {@link TextSize} value
     * @deprecated Use {@link #setTextZoom} instead.
     */
    public synchronized void setTextSize(TextSize t) {
        setTextZoom(t.value);
    }

    /**
     * Gets the text size of the page. If the text size was previously specified
     * in percent using {@link #setTextZoom}, this will return the closest
     * matching {@link TextSize}.
     *
     * @return the text size as a {@link TextSize} value
     * @see #setTextSize
     * @deprecated Use {@link #getTextZoom} instead.
     */
    public synchronized TextSize getTextSize() {
        TextSize closestSize = null;
        int smallestDelta = Integer.MAX_VALUE;
        int textSize = getTextZoom();
        for (TextSize size : TextSize.values()) {
            int delta = Math.abs(textSize - size.value);
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
     * Sets the default zoom density of the page. This must be called from the UI
     * thread. The default is {@link ZoomDensity#MEDIUM}.
     *
     * @param zoom the zoom density
     */
    public void setDefaultZoom(ZoomDensity zoom) {
        throw new MustOverrideException();
    }

    /**
     * Gets the default zoom density of the page. This should be called from
     * the UI thread.
     *
     * @return the zoom density
     * @see #setDefaultZoom
     */
    public ZoomDensity getDefaultZoom() {
        throw new MustOverrideException();
    }

    /**
     * Enables using light touches to make a selection and activate mouseovers.
     * The default is false.
     */
    public void setLightTouchEnabled(boolean enabled) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether light touches are enabled.
     *
     * @return whether light touches are enabled
     * @see #setLightTouchEnabled
     */
    public boolean getLightTouchEnabled() {
        throw new MustOverrideException();
    }

    /**
     * Controlled a rendering optimization that is no longer present. Setting
     * it now has no effect.
     *
     * @deprecated This setting now has no effect.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public synchronized void setUseDoubleTree(boolean use) {
        // Specified to do nothing, so no need for derived classes to override.
    }

    /**
     * Controlled a rendering optimization that is no longer present. Setting
     * it now has no effect.
     *
     * @deprecated This setting now has no effect.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public synchronized boolean getUseDoubleTree() {
        // Returns false unconditionally, so no need for derived classes to override.
        return false;
    }

    /**
     * Sets the user-agent string using an integer code.
     * <ul>
     *   <li>0 means the WebView should use an Android user-agent string</li>
     *   <li>1 means the WebView should use a desktop user-agent string</li>
     * </ul>
     * Other values are ignored. The default is an Android user-agent string,
     * i.e. code value 0.
     *
     * @param ua the integer code for the user-agent string
     * @deprecated Please use {@link #setUserAgentString} instead.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public synchronized void setUserAgent(int ua) {
        throw new MustOverrideException();
    }

    /**
     * Gets the user-agent as an integer code.
     * <ul>
     *   <li>-1 means the WebView is using a custom user-agent string set with
     *   {@link #setUserAgentString}</li>
     *   <li>0 means the WebView should use an Android user-agent string</li>
     *   <li>1 means the WebView should use a desktop user-agent string</li>
     * </ul>
     *
     * @return the integer code for the user-agent string
     * @see #setUserAgent
     * @deprecated Please use {@link #getUserAgentString} instead.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    public synchronized int getUserAgent() {
        throw new MustOverrideException();
    }

    /**
     * Tells the WebView to use a wide viewport. The default is false.
     *
     * @param use whether to use a wide viewport
     */
    public synchronized void setUseWideViewPort(boolean use) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView is using a wide viewport.
     *
     * @return true if the WebView is using a wide viewport
     * @see #setUseWideViewPort
     */
    public synchronized boolean getUseWideViewPort() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView whether supports multiple windows. If set to
     * true, {@link WebChromeClient#onCreateWindow} must be implemented by the
     * host application. The default is false.
     *
     * @param support whether to suport multiple windows
     */
    public synchronized void setSupportMultipleWindows(boolean support) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView supports multiple windows.
     *
     * @return true if the WebView supports multiple windows
     * @see #setSupportMultipleWindows
     */
    public synchronized boolean supportMultipleWindows() {
        throw new MustOverrideException();
    }

    /**
     * Sets the underlying layout algorithm. This will cause a relayout of the
     * WebView. The default is {@link LayoutAlgorithm#NARROW_COLUMNS}.
     *
     * @param l the layout algorithm to use, as a {@link LayoutAlgorithm} value
     */
    public synchronized void setLayoutAlgorithm(LayoutAlgorithm l) {
        throw new MustOverrideException();
    }

    /**
     * Gets the current layout algorithm.
     *
     * @return the layout algorithm in use, as a {@link LayoutAlgorithm} value
     * @see #setLayoutAlgorithm
     */
    public synchronized LayoutAlgorithm getLayoutAlgorithm() {
        throw new MustOverrideException();
    }

    /**
     * Sets the standard font family name. The default is "sans-serif".
     *
     * @param font a font family name
     */
    public synchronized void setStandardFontFamily(String font) {
        throw new MustOverrideException();
    }

    /**
     * Gets the standard font family name.
     *
     * @return the standard font family name as a string
     * @see #setStandardFontFamily
     */
    public synchronized String getStandardFontFamily() {
        throw new MustOverrideException();
    }

    /**
     * Sets the fixed font family name. The default is "monospace".
     *
     * @param font a font family name
     */
    public synchronized void setFixedFontFamily(String font) {
        throw new MustOverrideException();
    }

    /**
     * Gets the fixed font family name.
     *
     * @return the fixed font family name as a string
     * @see #setFixedFontFamily
     */
    public synchronized String getFixedFontFamily() {
        throw new MustOverrideException();
    }

    /**
     * Sets the sans-serif font family name. The default is "sans-serif".
     *
     * @param font a font family name
     */
    public synchronized void setSansSerifFontFamily(String font) {
        throw new MustOverrideException();
    }

    /**
     * Gets the sans-serif font family name.
     *
     * @return the sans-serif font family name as a string
     * @see #setSansSerifFontFamily
     */
    public synchronized String getSansSerifFontFamily() {
        throw new MustOverrideException();
    }

    /**
     * Sets the serif font family name. The default is "sans-serif".
     *
     * @param font a font family name
     */
    public synchronized void setSerifFontFamily(String font) {
        throw new MustOverrideException();
    }

    /**
     * Gets the serif font family name. The default is "serif".
     *
     * @return the serif font family name as a string
     * @see #setSerifFontFamily
     */
    public synchronized String getSerifFontFamily() {
        throw new MustOverrideException();
    }

    /**
     * Sets the cursive font family name. The default is "cursive".
     *
     * @param font a font family name
     */
    public synchronized void setCursiveFontFamily(String font) {
        throw new MustOverrideException();
    }

    /**
     * Gets the cursive font family name.
     *
     * @return the cursive font family name as a string
     * @see #setCursiveFontFamily
     */
    public synchronized String getCursiveFontFamily() {
        throw new MustOverrideException();
    }

    /**
     * Sets the fantasy font family name. The default is "fantasy".
     *
     * @param font a font family name
     */
    public synchronized void setFantasyFontFamily(String font) {
        throw new MustOverrideException();
    }

    /**
     * Gets the fantasy font family name.
     *
     * @return the fantasy font family name as a string
     * @see #setFantasyFontFamily
     */
    public synchronized String getFantasyFontFamily() {
        throw new MustOverrideException();
    }

    /**
     * Sets the minimum font size. The default is 8.
     *
     * @param size a non-negative integer between 1 and 72. Any number outside
     *             the specified range will be pinned.
     */
    public synchronized void setMinimumFontSize(int size) {
        throw new MustOverrideException();
    }

    /**
     * Gets the minimum font size.
     *
     * @return a non-negative integer between 1 and 72
     * @see #setMinimumFontSize
     */
    public synchronized int getMinimumFontSize() {
        throw new MustOverrideException();
    }

    /**
     * Sets the minimum logical font size. The default is 8.
     *
     * @param size a non-negative integer between 1 and 72. Any number outside
     *             the specified range will be pinned.
     */
    public synchronized void setMinimumLogicalFontSize(int size) {
        throw new MustOverrideException();
    }

    /**
     * Gets the minimum logical font size.
     *
     * @return a non-negative integer between 1 and 72
     * @see #setMinimumLogicalFontSize
     */
    public synchronized int getMinimumLogicalFontSize() {
        throw new MustOverrideException();
    }

    /**
     * Sets the default font size. The default is 16.
     *
     * @param size a non-negative integer between 1 and 72. Any number outside
     *             the specified range will be pinned.
     */
    public synchronized void setDefaultFontSize(int size) {
        throw new MustOverrideException();
    }

    /**
     * Gets the default font size.
     *
     * @return a non-negative integer between 1 and 72
     * @see #setDefaultFontSize
     */
    public synchronized int getDefaultFontSize() {
        throw new MustOverrideException();
    }

    /**
     * Sets the default fixed font size. The default is 16.
     *
     * @param size a non-negative integer between 1 and 72. Any number outside
     *             the specified range will be pinned.
     */
    public synchronized void setDefaultFixedFontSize(int size) {
        throw new MustOverrideException();
    }

    /**
     * Gets the default fixed font size.
     *
     * @return a non-negative integer between 1 and 72
     * @see #setDefaultFixedFontSize
     */
    public synchronized int getDefaultFixedFontSize() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView should load image resources. Note that this method
     * controls loading of all images, including those embedded using the data
     * URI scheme. Use {@link #setBlockNetworkImage} to control loading only
     * of images specified using network URI schemes. Note that if the value of this
     * setting is changed from false to true, all images resources referenced
     * by content currently displayed by the WebView are loaded automatically.
     * The default is true.
     *
     * @param flag whether the WebView should load image resources
     */
    public synchronized void setLoadsImagesAutomatically(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView loads image resources. This includes
     * images embedded using the data URI scheme.
     *
     * @return true if the WebView loads image resources
     * @see #setLoadsImagesAutomatically
     */
    public synchronized boolean getLoadsImagesAutomatically() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView should not load image resources from the
     * network (resources accessed via http and https URI schemes).  Note
     * that this method has no effect unless
     * {@link #getLoadsImagesAutomatically} returns true. Also note that
     * disabling all network loads using {@link #setBlockNetworkLoads}
     * will also prevent network images from loading, even if this flag is set
     * to false. When the value of this setting is changed from true to false,
     * network images resources referenced by content currently displayed by
     * the WebView are fetched automatically. The default is false.
     *
     * @param flag whether the WebView should not load image resources from the
     *             network
     * @see #setBlockNetworkLoads
     */
    public synchronized void setBlockNetworkImage(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView does not load image resources from the network.
     *
     * @return true if the WebView does not load image resources from the network
     * @see #setBlockNetworkImage
     */
    public synchronized boolean getBlockNetworkImage() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the WebView should not load resources from the network.
     * Use {@link #setBlockNetworkImage} to only avoid loading
     * image resources. Note that if the value of this setting is
     * changed from true to false, network resources referenced by content
     * currently displayed by the WebView are not fetched until
     * {@link android.webkit.WebView#reload} is called.
     * If the application does not have the
     * {@link android.Manifest.permission#INTERNET} permission, attempts to set
     * a value of false will cause a {@link java.lang.SecurityException}
     * to be thrown. The default value is false if the application has the
     * {@link android.Manifest.permission#INTERNET} permission, otherwise it is
     * true.
     *
     * @param flag whether the WebView should not load any resources from the
     *             network
     * @see android.webkit.WebView#reload
     */
    public synchronized void setBlockNetworkLoads(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the WebView does not load any resources from the network.
     *
     * @return true if the WebView does not load any resources from the network
     * @see #setBlockNetworkLoads
     */
    public synchronized boolean getBlockNetworkLoads() {
        throw new MustOverrideException();
    }

    /**
     * Tells the WebView to enable JavaScript execution.
     * <b>The default is false.</b>
     *
     * @param flag true if the WebView should execute JavaScript
     */
    public synchronized void setJavaScriptEnabled(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Sets whether JavaScript running in the context of a file scheme URL
     * should be allowed to access content from any origin. This includes
     * access to content from other file scheme URLs. See
     * {@link #setAllowFileAccessFromFileURLs}. To enable the most restrictive,
     * and therefore secure policy, this setting should be disabled.
     * <p>
     * The default value is true for API level
     * {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH_MR1} and below,
     * and false for API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN}
     * and above.
     *
     * @param flag whether JavaScript running in the context of a file scheme
     *             URL should be allowed to access content from any origin
     */
    public abstract void setAllowUniversalAccessFromFileURLs(boolean flag);

    /**
     * Sets whether JavaScript running in the context of a file scheme URL
     * should be allowed to access content from other file scheme URLs. To
     * enable the most restrictive, and therefore secure policy, this setting
     * should be disabled. Note that the value of this setting is ignored if
     * the value of {@link #getAllowUniversalAccessFromFileURLs} is true.
     * <p>
     * The default value is true for API level
     * {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH_MR1} and below,
     * and false for API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN}
     * and above.
     *
     * @param flag whether JavaScript running in the context of a file scheme
     *             URL should be allowed to access content from other file
     *             scheme URLs
     */
    public abstract void setAllowFileAccessFromFileURLs(boolean flag);

    /**
     * Sets whether the WebView should enable plugins. The default is false.
     *
     * @param flag true if plugins should be enabled
     * @deprecated This method has been deprecated in favor of
     *             {@link #setPluginState}
     */
    @Deprecated
    public synchronized void setPluginsEnabled(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Tells the WebView to enable, disable, or have plugins on demand. On
     * demand mode means that if a plugin exists that can handle the embedded
     * content, a placeholder icon will be shown instead of the plugin. When
     * the placeholder is clicked, the plugin will be enabled. The default is
     * {@link PluginState#OFF}.
     *
     * @param state a PluginState value
     */
    public synchronized void setPluginState(PluginState state) {
        throw new MustOverrideException();
    }

    /**
     * Sets a custom path to plugins used by the WebView. This method is
     * obsolete since each plugin is now loaded from its own package.
     *
     * @param pluginsPath a String path to the directory containing plugins
     * @deprecated This method is no longer used as plugins are loaded from
     *             their own APK via the system's package manager.
     */
    @Deprecated
    public synchronized void setPluginsPath(String pluginsPath) {
        // Specified to do nothing, so no need for derived classes to override.
    }

    /**
     * Sets the path to where database storage API databases should be saved.
     * In order for the database storage API to function correctly, this method
     * must be called with a path to which the application can write. This
     * method should only be called once: repeated calls are ignored.
     *
     * @param databasePath a path to the directory where databases should be
     *                     saved.
     */
    // This will update WebCore when the Sync runs in the C++ side.
    // Note that the WebCore Database Tracker only allows the path to be set
    // once.
    public synchronized void setDatabasePath(String databasePath) {
        throw new MustOverrideException();
    }

    /**
     * Sets the path where the Geolocation databases should be saved. In order
     * for Geolocation permissions and cached positions to be persisted, this
     * method must be called with a path to which the application can write.
     *
     * @param databasePath a path to the directory where databases should be
     *                     saved.
     */
    // This will update WebCore when the Sync runs in the C++ side.
    public synchronized void setGeolocationDatabasePath(String databasePath) {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the Application Caches API should be enabled. The default
     * is false. Note that in order for the Application Caches API to be
     * enabled, a valid database path must also be supplied to
     * {@link #setAppCachePath}.
     *
     * @param flag true if the WebView should enable Application Caches
     */
    public synchronized void setAppCacheEnabled(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Sets the path to the Application Caches files. In order for the
     * Application Caches API to be enabled, this method must be called with a
     * path to which the application can write. This method should only be
     * called once: repeated calls are ignored.
     *
     * @param appCachePath a String path to the directory containing
     *                     Application Caches files.
     * @see setAppCacheEnabled
     */
    public synchronized void setAppCachePath(String appCachePath) {
        throw new MustOverrideException();
    }

    /**
     * Sets the maximum size for the Application Cache content. The passed size
     * will be rounded to the nearest value that the database can support, so
     * this should be viewed as a guide, not a hard limit. Setting the
     * size to a value less than current database size does not cause the
     * database to be trimmed. The default size is {@link Long#MAX_VALUE}.
     *
     * @param appCacheMaxSize the maximum size in bytes
     */
    public synchronized void setAppCacheMaxSize(long appCacheMaxSize) {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the database storage API is enabled. The default value is
     * false. See also {@link #setDatabasePath} for how to correctly set up the
     * database storage API.
     *
     * @param flag true if the WebView should use the database storage API
     */
    public synchronized void setDatabaseEnabled(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Sets whether the DOM storage API is enabled. The default value is false.
     *
     * @param flag true if the WebView should use the DOM storage API
     */
    public synchronized void setDomStorageEnabled(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the DOM Storage APIs are enabled.
     *
     * @return true if the DOM Storage APIs are enabled
     * @see #setDomStorageEnabled
     */
    public synchronized boolean getDomStorageEnabled() {
        throw new MustOverrideException();
    }
    /**
     * Gets the path to where database storage API databases are saved.
     *
     * @return the String path to the database storage API databases
     * @see #setDatabasePath
     */
    public synchronized String getDatabasePath() {
        throw new MustOverrideException();
    }

    /**
     * Gets whether the database storage API is enabled.
     *
     * @return true if the database storage API is enabled
     * @see #setDatabaseEnabled
     */
    public synchronized boolean getDatabaseEnabled() {
        throw new MustOverrideException();
    }

    /**
     * Sets whether Geolocation is enabled. The default is true. See also
     * {@link #setGeolocationDatabasePath} for how to correctly set up
     * Geolocation.
     *
     * @param flag whether Geolocation should be enabled
     */
    public synchronized void setGeolocationEnabled(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether JavaScript is enabled.
     *
     * @return true if JavaScript is enabled
     * @see #setJavaScriptEnabled
     */
    public synchronized boolean getJavaScriptEnabled() {
        throw new MustOverrideException();
    }

    /**
     * Gets whether JavaScript running in the context of a file scheme URL can
     * access content from any origin. This includes access to content from
     * other file scheme URLs.
     *
     * @return whether JavaScript running in the context of a file scheme URL
     *         can access content from any origin
     * @see #setAllowUniversalAccessFromFileURLs
     */
    public abstract boolean getAllowUniversalAccessFromFileURLs();

    /**
     * Gets whether JavaScript running in the context of a file scheme URL can
     * access content from other file scheme URLs.
     *
     * @return whether JavaScript running in the context of a file scheme URL
     *         can access content from other file scheme URLs
     * @see #setAllowFileAccessFromFileURLs
     */
    public abstract boolean getAllowFileAccessFromFileURLs();

    /**
     * Gets whether plugins are enabled.
     *
     * @return true if plugins are enabled
     * @see #setPluginsEnabled
     * @deprecated This method has been replaced by {@link #getPluginState}
     */
    @Deprecated
    public synchronized boolean getPluginsEnabled() {
        throw new MustOverrideException();
    }

    /**
     * Gets the current state regarding whether plugins are enabled.
     *
     * @return the plugin state as a {@link PluginState} value
     * @see #setPluginState
     */
    public synchronized PluginState getPluginState() {
        throw new MustOverrideException();
    }

    /**
     * Gets the directory that contains the plugin libraries. This method is
     * obsolete since each plugin is now loaded from its own package.
     *
     * @return an empty string
     * @deprecated This method is no longer used as plugins are loaded from
     * their own APK via the system's package manager.
     */
    @Deprecated
    public synchronized String getPluginsPath() {
        // Unconditionally returns empty string, so no need for derived classes to override.
        return "";
    }

    /**
     * Tells JavaScript to open windows automatically. This applies to the
     * JavaScript function window.open(). The default is false.
     *
     * @param flag true if JavaScript can open windows automatically
     */
    public synchronized void setJavaScriptCanOpenWindowsAutomatically(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Gets whether JavaScript can open windows automatically.
     *
     * @return true if JavaScript can open windows automatically during
     *         window.open()
     * @see #setJavaScriptCanOpenWindowsAutomatically
     */
    public synchronized boolean getJavaScriptCanOpenWindowsAutomatically() {
        throw new MustOverrideException();
    }
    /**
     * Sets the default text encoding name to use when decoding html pages.
     * The default is "Latin-1".
     *
     * @param encoding the text encoding name
     */
    public synchronized void setDefaultTextEncodingName(String encoding) {
        throw new MustOverrideException();
    }

    /**
     * Gets the default text encoding name.
     *
     * @return the default text encoding name as a string
     * @see #setDefaultTextEncodingName
     */
    public synchronized String getDefaultTextEncodingName() {
        throw new MustOverrideException();
    }

    /**
     * Sets the WebView's user-agent string. If the string is null or empty,
     * the system default value will be used.
     */
    public synchronized void setUserAgentString(String ua) {
        throw new MustOverrideException();
    }

    /**
     * Gets the WebView's user-agent string.
     *
     * @return the WebView's user-agent string
     * @see #setUserAgentString
     */
    public synchronized String getUserAgentString() {
        throw new MustOverrideException();
    }

    /**
     * Returns the default User-Agent used by a WebView.
     * An instance of WebView could use a different User-Agent if a call
     * is made to {@link WebSettings#setUserAgentString(String)}.
     *
     * @param context a Context object used to access application assets
     */
    public static String getDefaultUserAgent(Context context) {
        return WebViewFactory.getProvider().getStatics().getDefaultUserAgent(context);
    }

    /**
     * Tells the WebView whether it needs to set a node to have focus when
     * {@link WebView#requestFocus(int, android.graphics.Rect)} is called. The
     * default value is true.
     *
     * @param flag whether the WebView needs to set a node
     */
    public void setNeedInitialFocus(boolean flag) {
        throw new MustOverrideException();
    }

    /**
     * Sets the priority of the Render thread. Unlike the other settings, this
     * one only needs to be called once per process. The default value is
     * {@link RenderPriority#NORMAL}.
     *
     * @param priority the priority
     */
    public synchronized void setRenderPriority(RenderPriority priority) {
        throw new MustOverrideException();
    }

    /**
     * Overrides the way the cache is used. The way the cache is used is based
     * on the navigation type. For a normal page load, the cache is checked
     * and content is re-validated as needed. When navigating back, content is
     * not revalidated, instead the content is just retrieved from the cache.
     * This method allows the client to override this behavior by specifying
     * one of {@link #LOAD_DEFAULT}, {@link #LOAD_NORMAL},
     * {@link #LOAD_CACHE_ELSE_NETWORK}, {@link #LOAD_NO_CACHE} or
     * {@link #LOAD_CACHE_ONLY}. The default value is {@link #LOAD_DEFAULT}.
     *
     * @param mode the mode to use
     */
    public void setCacheMode(int mode) {
        throw new MustOverrideException();
    }

    /**
     * Gets the current setting for overriding the cache mode.
     *
     * @return the current setting for overriding the cache mode
     * @see #setCacheMode
     */
    public int getCacheMode() {
        throw new MustOverrideException();
    }
}
