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

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Manages settings state for a WebView. When a WebView is first created, it
 * obtains a set of default settings. These default settings will be returned
 * from any getter call. A {@code WebSettings} object obtained from
 * {@link WebView#getSettings()} is tied to the life of the WebView. If a WebView has
 * been destroyed, any method call on {@code WebSettings} will throw an
 * {@link IllegalStateException}.
 */
// This is an abstract base class: concrete WebViewProviders must
// create a class derived from this, and return an instance of it in the
// WebViewProvider.getWebSettingsProvider() method implementation.
public abstract class WebSettings {
    /**
     * Enum for controlling the layout of html.
     * <ul>
     *   <li>{@code NORMAL} means no rendering changes. This is the recommended choice for maximum
     *       compatibility across different platforms and Android versions.</li>
     *   <li>{@code SINGLE_COLUMN} moves all content into one column that is the width of the
     *       view.</li>
     *   <li>{@code NARROW_COLUMNS} makes all columns no wider than the screen if possible. Only use
     *       this for API levels prior to {@link android.os.Build.VERSION_CODES#KITKAT}.</li>
     *   <li>{@code TEXT_AUTOSIZING} boosts font size of paragraphs based on heuristics to make
     *       the text readable when viewing a wide-viewport layout in the overview mode.
     *       It is recommended to enable zoom support {@link #setSupportZoom} when
     *       using this mode. Supported from API level
     *       {@link android.os.Build.VERSION_CODES#KITKAT}</li>
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
        /**
         * @deprecated This algorithm is now obsolete.
         */
        @Deprecated
        NARROW_COLUMNS,
        TEXT_AUTOSIZING
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
    @Deprecated
    public enum TextSize {
        SMALLEST(50),
        SMALLER(75),
        NORMAL(100),
        LARGER(150),
        LARGEST(200);
        TextSize(int size) {
            value = size;
        }
        @UnsupportedAppUsage
        int value;
    }

    /**
     * Enum for specifying the WebView's desired density.
     * <ul>
     *   <li>{@code FAR} makes 100% looking like in 240dpi</li>
     *   <li>{@code MEDIUM} makes 100% looking like in 160dpi</li>
     *   <li>{@code CLOSE} makes 100% looking like in 120dpi</li>
     * </ul>
     */
    public enum ZoomDensity {
        FAR(150),      // 240dpi
        MEDIUM(100),    // 160dpi
        CLOSE(75);     // 120dpi
        ZoomDensity(int size) {
            value = size;
        }

        /**
         * @hide Only for use by WebViewProvider implementations
         */
        public int getValue() {
            return value;
        }

        int value;
    }

    /** @hide */
    @IntDef(prefix = { "LOAD_" }, value = {
            LOAD_DEFAULT,
            LOAD_NORMAL,
            LOAD_CACHE_ELSE_NETWORK,
            LOAD_NO_CACHE,
            LOAD_CACHE_ONLY
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CacheMode {}

    /**
     * Enable web content to apply light or dark style according to the app's theme
     * and WebView to attempt to darken web content by algorithmic darkening when
     * appropriate.
     *
     * Refer to {@link #setAlgorithmicDarkeningAllowed} for detail.
     *
     * @hide
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = android.os.Build.VERSION_CODES.TIRAMISU)
    @SystemApi
    public static final long ENABLE_SIMPLIFIED_DARK_MODE = 214741472L;

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
     * In this mode, the WebView will allow a secure origin to load content from any other origin,
     * even if that origin is insecure. This is the least secure mode of operation for the WebView,
     * and where possible apps should not set this mode.
     *
     * @see #setMixedContentMode
     */
    public static final int MIXED_CONTENT_ALWAYS_ALLOW = 0;

    /**
     * In this mode, the WebView will not allow a secure origin to load content from an insecure
     * origin. This is the preferred and most secure mode of operation for the WebView and apps are
     * strongly advised to use this mode.
     *
     * @see #setMixedContentMode
     */
    public static final int MIXED_CONTENT_NEVER_ALLOW = 1;

    /**
     * In this mode, the WebView will attempt to be compatible with the approach of a modern web
     * browser with regard to mixed content. Some insecure content may be allowed to be loaded by
     * a secure origin and other types of content will be blocked. The types of content are allowed
     * or blocked may change release to release and are not explicitly defined.
     *
     * This mode is intended to be used by apps that are not in control of the content that they
     * render but desire to operate in a reasonably secure environment. For highest security, apps
     * are recommended to use {@link #MIXED_CONTENT_NEVER_ALLOW}.
     *
     * @see #setMixedContentMode
     */
    public static final int MIXED_CONTENT_COMPATIBILITY_MODE = 2;

    /** @hide */
    @IntDef(prefix = { "FORCE_DARK_" }, value = {
            FORCE_DARK_OFF,
            FORCE_DARK_AUTO,
            FORCE_DARK_ON
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ForceDark {}

    /**
     * Disable force dark, irrespective of the force dark mode of the WebView parent. In this mode,
     * WebView content will always be rendered as-is, regardless of whether native views are being
     * automatically darkened.
     *
     * @see #setForceDark
     * @deprecated refer to {@link #setForceDark}
     */
    public static final int FORCE_DARK_OFF = 0;

    /**
     * Enable force dark dependent on the state of the WebView parent view. If the WebView parent
     * view is being automatically force darkened
     * (see: {@link android.view.View#setForceDarkAllowed}), then WebView content will be rendered
     * so as to emulate a dark theme. WebViews that are not attached to the view hierarchy will not
     * be inverted.
     *
     * @see #setForceDark
     * @deprecated refer to {@link #setForceDark}
     */
    public static final int FORCE_DARK_AUTO = 1;

    /**
     * Unconditionally enable force dark. In this mode WebView content will always be rendered so
     * as to emulate a dark theme.
     *
     * @see #setForceDark
     * @deprecated refer to {@link #setForceDark}
     */
    public static final int FORCE_DARK_ON = 2;

    /**
     * Enables dumping the pages navigation cache to a text file. The default
     * is {@code false}.
     *
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @Deprecated
    public abstract void setNavDump(boolean enabled);

    /**
     * Gets whether dumping the navigation cache is enabled.
     *
     * @return whether dumping the navigation cache is enabled
     * @see #setNavDump
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @Deprecated
    public abstract boolean getNavDump();

    /**
     * Sets whether the WebView should support zooming using its on-screen zoom
     * controls and gestures. The particular zoom mechanisms that should be used
     * can be set with {@link #setBuiltInZoomControls}. This setting does not
     * affect zooming performed using the {@link WebView#zoomIn()} and
     * {@link WebView#zoomOut()} methods. The default is {@code true}.
     *
     * @param support whether the WebView should support zoom
     */
    public abstract void setSupportZoom(boolean support);

    /**
     * Gets whether the WebView supports zoom.
     *
     * @return {@code true} if the WebView supports zoom
     * @see #setSupportZoom
     */
    public abstract boolean supportZoom();

    /**
     * Sets whether the WebView requires a user gesture to play media.
     * The default is {@code true}.
     *
     * @param require whether the WebView requires a user gesture to play media
     */
    public abstract void setMediaPlaybackRequiresUserGesture(boolean require);

    /**
     * Gets whether the WebView requires a user gesture to play media.
     *
     * @return {@code true} if the WebView requires a user gesture to play media
     * @see #setMediaPlaybackRequiresUserGesture
     */
    public abstract boolean getMediaPlaybackRequiresUserGesture();

    /**
     * Sets whether the WebView should use its built-in zoom mechanisms. The
     * built-in zoom mechanisms comprise on-screen zoom controls, which are
     * displayed over the WebView's content, and the use of a pinch gesture to
     * control zooming. Whether or not these on-screen controls are displayed
     * can be set with {@link #setDisplayZoomControls}. The default is {@code false}.
     * <p>
     * The built-in mechanisms are the only currently supported zoom
     * mechanisms, so it is recommended that this setting is always enabled.
     * However, on-screen zoom controls are deprecated in Android (see
     * {@link android.widget.ZoomButtonsController}) so it's recommended to
     * disable {@link #setDisplayZoomControls}.
     *
     * @param enabled whether the WebView should use its built-in zoom mechanisms
     */
    // This method was intended to select between the built-in zoom mechanisms
    // and the separate zoom controls. The latter were obtained using
    // {@link WebView#getZoomControls}, which is now hidden.
    public abstract void setBuiltInZoomControls(boolean enabled);

    /**
     * Gets whether the zoom mechanisms built into WebView are being used.
     *
     * @return {@code true} if the zoom mechanisms built into WebView are being used
     * @see #setBuiltInZoomControls
     */
    public abstract boolean getBuiltInZoomControls();

    /**
     * Sets whether the WebView should display on-screen zoom controls when
     * using the built-in zoom mechanisms. See {@link #setBuiltInZoomControls}.
     * The default is {@code true}. However, on-screen zoom controls are deprecated
     * in Android (see {@link android.widget.ZoomButtonsController}) so it's
     * recommended to set this to {@code false}.
     *
     * @param enabled whether the WebView should display on-screen zoom controls
     */
    public abstract void setDisplayZoomControls(boolean enabled);

    /**
     * Gets whether the WebView displays on-screen zoom controls when using
     * the built-in zoom mechanisms.
     *
     * @return {@code true} if the WebView displays on-screen zoom controls when using
     *         the built-in zoom mechanisms
     * @see #setDisplayZoomControls
     */
    public abstract boolean getDisplayZoomControls();

    /**
     * Enables or disables file access within WebView.
     * Note that this enables or disables file system access only. Assets and resources
     * are still accessible using file:///android_asset and file:///android_res.
     * <p class="note">
     * <b>Note:</b> Apps should not open {@code file://} URLs from any external source in
     * WebView, don't enable this if your app accepts arbitrary URLs from external sources.
     * It's recommended to always use
     * <a href="{@docRoot}reference/androidx/webkit/WebViewAssetLoader">
     * androidx.webkit.WebViewAssetLoader</a> to access files including assets and resources over
     * {@code http(s)://} schemes, instead of {@code file://} URLs. To prevent possible security
     * issues targeting {@link android.os.Build.VERSION_CODES#Q} and earlier, you should explicitly
     * set this value to {@code false}.
     * <p>
     * The default value is {@code true} for apps targeting
     * {@link android.os.Build.VERSION_CODES#Q} and below, and {@code false} when targeting
     * {@link android.os.Build.VERSION_CODES#R} and above.
     */
    public abstract void setAllowFileAccess(boolean allow);

    /**
     * Gets whether this WebView supports file access.
     *
     * @see #setAllowFileAccess
     */
    public abstract boolean getAllowFileAccess();

    /**
     * Enables or disables content URL access within WebView.  Content URL
     * access allows WebView to load content from a content provider installed
     * in the system. The default is enabled.
     */
    public abstract void setAllowContentAccess(boolean allow);

    /**
     * Gets whether this WebView supports content URL access.
     *
     * @see #setAllowContentAccess
     */
    public abstract boolean getAllowContentAccess();

    /**
     * Sets whether the WebView loads pages in overview mode, that is,
     * zooms out the content to fit on screen by width. This setting is
     * taken into account when the content width is greater than the width
     * of the WebView control, for example, when {@link #getUseWideViewPort}
     * is enabled. The default is {@code false}.
     */
    public abstract void setLoadWithOverviewMode(boolean overview);

    /**
     * Gets whether this WebView loads pages in overview mode.
     *
     * @return whether this WebView loads pages in overview mode
     * @see #setLoadWithOverviewMode
     */
    public abstract boolean getLoadWithOverviewMode();

    /**
     * Sets whether the WebView will enable smooth transition while panning or
     * zooming or while the window hosting the WebView does not have focus.
     * If it is {@code true}, WebView will choose a solution to maximize the performance.
     * e.g. the WebView's content may not be updated during the transition.
     * If it is false, WebView will keep its fidelity. The default value is {@code false}.
     *
     * @deprecated This method is now obsolete, and will become a no-op in future.
     */
    @Deprecated
    public abstract void setEnableSmoothTransition(boolean enable);

    /**
     * Gets whether the WebView enables smooth transition while panning or
     * zooming.
     *
     * @see #setEnableSmoothTransition
     *
     * @deprecated This method is now obsolete, and will become a no-op in future.
     */
    @Deprecated
    public abstract  boolean enableSmoothTransition();

    /**
     * Sets whether the WebView uses its background for over scroll background.
     * If {@code true}, it will use the WebView's background. If {@code false}, it will use an
     * internal pattern. Default is {@code true}.
     *
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @Deprecated
    public abstract  void setUseWebViewBackgroundForOverscrollBackground(boolean view);

    /**
     * Gets whether this WebView uses WebView's background instead of
     * internal pattern for over scroll background.
     *
     * @see #setUseWebViewBackgroundForOverscrollBackground
     * @deprecated This method is now obsolete.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @Deprecated
    public abstract  boolean getUseWebViewBackgroundForOverscrollBackground();

    /**
     * Sets whether the WebView should save form data. In {@link android.os.Build.VERSION_CODES#O},
     * the platform has implemented a fully functional Autofill feature to store form data.
     * Therefore, the Webview form data save feature is disabled.
     *
     * <p>Note that the feature will continue to be supported on older versions of
     * Android as before.
     *
     * @see #getSaveFormData
     * @deprecated In Android O and afterwards, this function does not have any effect. Form data
     * will be saved to platform's autofill service if applicable.
     */
    @Deprecated
    public abstract  void setSaveFormData(boolean save);

    /**
     * Gets whether the WebView saves form data. In {@link android.os.Build.VERSION_CODES#O}, the
     * platform has implemented a fully functional Autofill feature to store form data. Therefore,
     * the Webview form data save feature is disabled.
     *
     * <p>Note that the feature will continue to be supported on older versions of
     * Android as before.
     *
     * @return whether the WebView saves form data
     * @see #setSaveFormData
     * @deprecated In Android O and afterwards, this function does not have any effect. Form data
     * will be filled from the platform's autofill service if applicable.
     */
    @Deprecated
    public abstract boolean getSaveFormData();

    /**
     * Sets whether the WebView should save passwords. The default is {@code true}.
     * @deprecated Saving passwords in WebView will not be supported in future versions.
     */
    @Deprecated
    public abstract void setSavePassword(boolean save);

    /**
     * Gets whether the WebView saves passwords.
     *
     * @return whether the WebView saves passwords
     * @see #setSavePassword
     * @deprecated Saving passwords in WebView will not be supported in future versions.
     */
    @Deprecated
    public abstract boolean getSavePassword();

    /**
     * Sets the text zoom of the page in percent. The default is 100.
     *
     * @param textZoom the text zoom in percent
     */
    public abstract void setTextZoom(int textZoom);

    /**
     * Gets the text zoom of the page in percent.
     *
     * @return the text zoom of the page in percent
     * @see #setTextZoom
     */
    public abstract int getTextZoom();

    /**
     * Sets policy for third party cookies.
     * Developers should access this via {@link CookieManager#setShouldAcceptThirdPartyCookies}.
     * @hide Internal API.
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    public abstract void setAcceptThirdPartyCookies(boolean accept);

    /**
     * Gets policy for third party cookies.
     * Developers should access this via {@link CookieManager#getShouldAcceptThirdPartyCookies}.
     * @hide Internal API
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    public abstract boolean getAcceptThirdPartyCookies();

    /**
     * Sets the text size of the page. The default is {@link TextSize#NORMAL}.
     *
     * @param t the text size as a {@link TextSize} value
     * @deprecated Use {@link #setTextZoom} instead.
     */
    @Deprecated
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
    @Deprecated
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
     * This setting is not recommended for use in new applications.  If the WebView
     * is utilized to display mobile-oriented pages, the desired effect can be achieved by
     * adjusting 'width' and 'initial-scale' attributes of page's 'meta viewport'
     * tag. For pages lacking the tag, {@link android.webkit.WebView#setInitialScale}
     * and {@link #setUseWideViewPort} can be used.
     *
     * @param zoom the zoom density
     * @deprecated This method is no longer supported, see the function documentation for
     *             recommended alternatives.
     */
    @Deprecated
    public abstract void setDefaultZoom(ZoomDensity zoom);

    /**
     * Gets the default zoom density of the page. This should be called from
     * the UI thread.
     *
     * This setting is not recommended for use in new applications.
     *
     * @return the zoom density
     * @see #setDefaultZoom
     * @deprecated Will only return the default value.
     */
    @Deprecated
    public abstract ZoomDensity getDefaultZoom();

    /**
     * Enables using light touches to make a selection and activate mouseovers.
     * @deprecated From {@link android.os.Build.VERSION_CODES#JELLY_BEAN} this
     *             setting is obsolete and has no effect.
     */
    @Deprecated
    public abstract void setLightTouchEnabled(boolean enabled);

    /**
     * Gets whether light touches are enabled.
     * @see #setLightTouchEnabled
     * @deprecated This setting is obsolete.
     */
    @Deprecated
    public abstract boolean getLightTouchEnabled();

    /**
     * Controlled a rendering optimization that is no longer present. Setting
     * it now has no effect.
     *
     * @deprecated This setting now has no effect.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR1}
     */
    @Deprecated
    @UnsupportedAppUsage
    public void setUseDoubleTree(boolean use) {
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
    @UnsupportedAppUsage
    public boolean getUseDoubleTree() {
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
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @Deprecated
    public abstract void setUserAgent(int ua);

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
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @Deprecated
    public abstract int getUserAgent();

    /**
     * Sets whether the WebView should enable support for the &quot;viewport&quot;
     * HTML meta tag or should use a wide viewport.
     * When the value of the setting is {@code false}, the layout width is always set to the
     * width of the WebView control in device-independent (CSS) pixels.
     * When the value is {@code true} and the page contains the viewport meta tag, the value
     * of the width specified in the tag is used. If the page does not contain the tag or
     * does not provide a width, then a wide viewport will be used.
     *
     * @param use whether to enable support for the viewport meta tag
     */
    public abstract void setUseWideViewPort(boolean use);

    /**
     * Gets whether the WebView supports the &quot;viewport&quot;
     * HTML meta tag or will use a wide viewport.
     *
     * @return {@code true} if the WebView supports the viewport meta tag
     * @see #setUseWideViewPort
     */
    public abstract boolean getUseWideViewPort();

    /**
     * Sets whether the WebView should support multiple windows.
     *
     * <p>If set to {@code true}, the {@link WebChromeClient#onCreateWindow}
     * callback must be implemented by the application to handle the
     * creation of new windows.
     *
     * <p>The default is {@code false}. When multiple window support is disabled,
     * requests to open new windows (either from the {@code window.open()}
     * JavaScript API or from links with {@code target="_blank"}) will instead
     * be treated as top-level navigations, replacing the current page in the
     * same WebView.
     *
     * @param support whether to support multiple windows
     */
    public abstract void setSupportMultipleWindows(boolean support);

    /**
     * Gets whether the WebView supports multiple windows.
     *
     * @return {@code true} if the WebView supports multiple windows
     * @see #setSupportMultipleWindows
     */
    public abstract boolean supportMultipleWindows();

    /**
     * Sets the underlying layout algorithm. This will cause a re-layout of the
     * WebView. The default is {@link LayoutAlgorithm#NARROW_COLUMNS}.
     *
     * @param l the layout algorithm to use, as a {@link LayoutAlgorithm} value
     */
    public abstract void setLayoutAlgorithm(LayoutAlgorithm l);

    /**
     * Gets the current layout algorithm.
     *
     * @return the layout algorithm in use, as a {@link LayoutAlgorithm} value
     * @see #setLayoutAlgorithm
     */
    public abstract LayoutAlgorithm getLayoutAlgorithm();

    /**
     * Sets the standard font family name. The default is "sans-serif".
     *
     * @param font a font family name
     */
    public abstract void setStandardFontFamily(String font);

    /**
     * Gets the standard font family name.
     *
     * @return the standard font family name as a string
     * @see #setStandardFontFamily
     */
    public abstract String getStandardFontFamily();

    /**
     * Sets the fixed font family name. The default is "monospace".
     *
     * @param font a font family name
     */
    public abstract void setFixedFontFamily(String font);

    /**
     * Gets the fixed font family name.
     *
     * @return the fixed font family name as a string
     * @see #setFixedFontFamily
     */
    public abstract String getFixedFontFamily();

    /**
     * Sets the sans-serif font family name. The default is "sans-serif".
     *
     * @param font a font family name
     */
    public abstract void setSansSerifFontFamily(String font);

    /**
     * Gets the sans-serif font family name.
     *
     * @return the sans-serif font family name as a string
     * @see #setSansSerifFontFamily
     */
    public abstract String getSansSerifFontFamily();

    /**
     * Sets the serif font family name. The default is "sans-serif".
     *
     * @param font a font family name
     */
    public abstract void setSerifFontFamily(String font);

    /**
     * Gets the serif font family name. The default is "serif".
     *
     * @return the serif font family name as a string
     * @see #setSerifFontFamily
     */
    public abstract String getSerifFontFamily();

    /**
     * Sets the cursive font family name. The default is "cursive".
     *
     * @param font a font family name
     */
    public abstract void setCursiveFontFamily(String font);

    /**
     * Gets the cursive font family name.
     *
     * @return the cursive font family name as a string
     * @see #setCursiveFontFamily
     */
    public abstract String getCursiveFontFamily();

    /**
     * Sets the fantasy font family name. The default is "fantasy".
     *
     * @param font a font family name
     */
    public abstract void setFantasyFontFamily(String font);

    /**
     * Gets the fantasy font family name.
     *
     * @return the fantasy font family name as a string
     * @see #setFantasyFontFamily
     */
    public abstract String getFantasyFontFamily();

    /**
     * Sets the minimum font size. The default is 8.
     *
     * @param size a non-negative integer between 1 and 72. Any number outside
     *             the specified range will be pinned.
     */
    public abstract void setMinimumFontSize(int size);

    /**
     * Gets the minimum font size.
     *
     * @return a non-negative integer between 1 and 72
     * @see #setMinimumFontSize
     */
    public abstract int getMinimumFontSize();

    /**
     * Sets the minimum logical font size. The default is 8.
     *
     * @param size a non-negative integer between 1 and 72. Any number outside
     *             the specified range will be pinned.
     */
    public abstract void setMinimumLogicalFontSize(int size);

    /**
     * Gets the minimum logical font size.
     *
     * @return a non-negative integer between 1 and 72
     * @see #setMinimumLogicalFontSize
     */
    public abstract int getMinimumLogicalFontSize();

    /**
     * Sets the default font size. The default is 16.
     *
     * @param size a non-negative integer between 1 and 72. Any number outside
     *             the specified range will be pinned.
     */
    public abstract void setDefaultFontSize(int size);

    /**
     * Gets the default font size.
     *
     * @return a non-negative integer between 1 and 72
     * @see #setDefaultFontSize
     */
    public abstract int getDefaultFontSize();

    /**
     * Sets the default fixed font size. The default is 16.
     *
     * @param size a non-negative integer between 1 and 72. Any number outside
     *             the specified range will be pinned.
     */
    public abstract void setDefaultFixedFontSize(int size);

    /**
     * Gets the default fixed font size.
     *
     * @return a non-negative integer between 1 and 72
     * @see #setDefaultFixedFontSize
     */
    public abstract int getDefaultFixedFontSize();

    /**
     * Sets whether the WebView should load image resources. Note that this method
     * controls loading of all images, including those embedded using the data
     * URI scheme. Use {@link #setBlockNetworkImage} to control loading only
     * of images specified using network URI schemes. Note that if the value of this
     * setting is changed from {@code false} to {@code true}, all images resources referenced
     * by content currently displayed by the WebView are loaded automatically.
     * The default is {@code true}.
     *
     * @param flag whether the WebView should load image resources
     */
    public abstract void setLoadsImagesAutomatically(boolean flag);

    /**
     * Gets whether the WebView loads image resources. This includes
     * images embedded using the data URI scheme.
     *
     * @return {@code true} if the WebView loads image resources
     * @see #setLoadsImagesAutomatically
     */
    public abstract boolean getLoadsImagesAutomatically();

    /**
     * Sets whether the WebView should not load image resources from the
     * network (resources accessed via http and https URI schemes).  Note
     * that this method has no effect unless
     * {@link #getLoadsImagesAutomatically} returns {@code true}. Also note that
     * disabling all network loads using {@link #setBlockNetworkLoads}
     * will also prevent network images from loading, even if this flag is set
     * to false. When the value of this setting is changed from {@code true} to {@code false},
     * network images resources referenced by content currently displayed by
     * the WebView are fetched automatically. The default is {@code false}.
     *
     * @param flag whether the WebView should not load image resources from the
     *             network
     * @see #setBlockNetworkLoads
     */
    public abstract void setBlockNetworkImage(boolean flag);

    /**
     * Gets whether the WebView does not load image resources from the network.
     *
     * @return {@code true} if the WebView does not load image resources from the network
     * @see #setBlockNetworkImage
     */
    public abstract boolean getBlockNetworkImage();

    /**
     * Sets whether the WebView should not load resources from the network.
     * Use {@link #setBlockNetworkImage} to only avoid loading
     * image resources. Note that if the value of this setting is
     * changed from {@code true} to {@code false}, network resources referenced by content
     * currently displayed by the WebView are not fetched until
     * {@link android.webkit.WebView#reload} is called.
     * If the application does not have the
     * {@link android.Manifest.permission#INTERNET} permission, attempts to set
     * a value of {@code false} will cause a {@link java.lang.SecurityException}
     * to be thrown. The default value is {@code false} if the application has the
     * {@link android.Manifest.permission#INTERNET} permission, otherwise it is
     * {@code true}.
     *
     * @param flag {@code true} means block network loads by the WebView
     * @see android.webkit.WebView#reload
     */
    public abstract void setBlockNetworkLoads(boolean flag);

    /**
     * Gets whether the WebView does not load any resources from the network.
     *
     * @return {@code true} if the WebView does not load any resources from the network
     * @see #setBlockNetworkLoads
     */
    public abstract boolean getBlockNetworkLoads();

    /**
     * Tells the WebView to enable JavaScript execution.
     * <b>The default is {@code false}.</b>
     *
     * @param flag {@code true} if the WebView should execute JavaScript
     */
    public abstract void setJavaScriptEnabled(boolean flag);

    /**
     * Sets whether cross-origin requests in the context of a file scheme URL should be allowed to
     * access content from <i>any</i> origin. This includes access to content from other file
     * scheme URLs or web contexts. Note that some access such as image HTML elements doesn't
     * follow same-origin rules and isn't affected by this setting.
     * <p>
     * <b>Don't</b> enable this setting if you open files that may be created or altered by
     * external sources. Enabling this setting allows malicious scripts loaded in a {@code file://}
     * context to launch cross-site scripting attacks, either accessing arbitrary local files
     * including WebView cookies, app private data or even credentials used on arbitrary web sites.
     * <p class="note">
     * Loading content via {@code file://} URLs is generally discouraged. See the note in
     * {@link #setAllowFileAccess}.
     * <p>
     * The default value is {@code true} for apps targeting
     * {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH_MR1} and below, and {@code false}
     * when targeting {@link android.os.Build.VERSION_CODES#JELLY_BEAN} and above. To prevent
     * possible violation of same domain policy when targeting
     * {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH_MR1} and earlier, you should
     * explicitly set this value to {@code false}.
     *
     * @param flag whether JavaScript running in the context of a file scheme URL should be allowed
     *             to access content from any origin
     * @deprecated This setting is not secure, please use
     *             <a href="{@docRoot}reference/androidx/webkit/WebViewAssetLoader.html">
     *             androidx.webkit.WebViewAssetLoader</a> to load file content securely.
     */
    @Deprecated
    public abstract void setAllowUniversalAccessFromFileURLs(boolean flag);

    /**
     * Sets whether cross-origin requests in the context of a file scheme URL should be allowed to
     * access content from other file scheme URLs. Note that some accesses such as image HTML
     * elements don't follow same-origin rules and aren't affected by this setting.
     * <p>
     * <b>Don't</b> enable this setting if you open files that may be created or altered by
     * external sources. Enabling this setting allows malicious scripts loaded in a {@code file://}
     * context to access arbitrary local files including WebView cookies and app private data.
     * <p class="note">
     * Loading content via {@code file://} URLs is generally discouraged. See the note in
     * {@link #setAllowFileAccess}.
     * <p>
     * Note that the value of this setting is ignored if the value of
     * {@link #getAllowUniversalAccessFromFileURLs} is {@code true}. The default value is
     * {@code true} for apps targeting {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH_MR1}
     * and below, and {@code false} when targeting {@link android.os.Build.VERSION_CODES#JELLY_BEAN}
     * and above. To prevent possible violation of same domain policy when targeting
     * {@link android.os.Build.VERSION_CODES#ICE_CREAM_SANDWICH_MR1} and earlier, you should
     * explicitly set this value to {@code false}.
     *
     * @param flag whether JavaScript running in the context of a file scheme
     *             URL should be allowed to access content from other file
     *             scheme URLs
     * @deprecated This setting is not secure, please use
     *             <a href="{@docRoot}reference/androidx/webkit/WebViewAssetLoader.html">
     *             androidx.webkit.WebViewAssetLoader</a> to load file content securely.
     */
    @Deprecated
    public abstract void setAllowFileAccessFromFileURLs(boolean flag);

    /**
     * Sets whether the WebView should enable plugins. The default is {@code false}.
     *
     * @param flag {@code true} if plugins should be enabled
     * @deprecated This method has been deprecated in favor of
     *             {@link #setPluginState}
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @Deprecated
    public abstract void setPluginsEnabled(boolean flag);

    /**
     * Tells the WebView to enable, disable, or have plugins on demand. On
     * demand mode means that if a plugin exists that can handle the embedded
     * content, a placeholder icon will be shown instead of the plugin. When
     * the placeholder is clicked, the plugin will be enabled. The default is
     * {@link PluginState#OFF}.
     *
     * @param state a PluginState value
     * @deprecated Plugins are not supported in API level
     *             {@link android.os.Build.VERSION_CODES#KITKAT} or later;
     *             enabling plugins is a no-op.
     */
    @Deprecated
    public abstract void setPluginState(PluginState state);

    /**
     * Sets a custom path to plugins used by the WebView. This method is
     * obsolete since each plugin is now loaded from its own package.
     *
     * @param pluginsPath a String path to the directory containing plugins
     * @deprecated This method is no longer used as plugins are loaded from
     *             their own APK via the system's package manager.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}
     */
    @Deprecated
    @UnsupportedAppUsage
    public void setPluginsPath(String pluginsPath) {
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
     * @deprecated Database paths are managed by the implementation and calling this method
     *             will have no effect.
     */
    @Deprecated
    public abstract void setDatabasePath(String databasePath);

    /**
     * Sets the path where the Geolocation databases should be saved. In order
     * for Geolocation permissions and cached positions to be persisted, this
     * method must be called with a path to which the application can write.
     *
     * @param databasePath a path to the directory where databases should be
     *                     saved.
     * @deprecated Geolocation database are managed by the implementation and calling this method
     *             will have no effect.
     */
    @Deprecated
    public abstract void setGeolocationDatabasePath(String databasePath);

    /**
     * Sets whether the Application Caches API should be enabled. The default
     * is {@code false}. Note that in order for the Application Caches API to be
     * enabled, a valid database path must also be supplied to
     * {@link #setAppCachePath}.
     *
     * @param flag {@code true} if the WebView should enable Application Caches
     * @deprecated The Application Cache API is deprecated and this method will
     *             become a no-op on all Android versions once support is
     *             removed in Chromium. Consider using Service Workers instead.
     *             See https://web.dev/appcache-removal/ for more information.
     * @removed The Application Cache API is no longer supported and this method
     *          is a no-op on WebView 95 and later. Consider using Service Workers
     *          instead. See https://web.dev/appcache-removal/ for more information.
     */
    @Deprecated
    public void setAppCacheEnabled(boolean flag) {}

    /**
     * Sets the path to the Application Caches files. In order for the
     * Application Caches API to be enabled, this method must be called with a
     * path to which the application can write. This method should only be
     * called once: repeated calls are ignored.
     *
     * @param appCachePath a String path to the directory containing
     *                     Application Caches files.
     * @see #setAppCacheEnabled
     * @deprecated The Application Cache API is deprecated and this method will
     *             become a no-op on all Android versions once support is
     *             removed in Chromium. Consider using Service Workers instead.
     *             See https://web.dev/appcache-removal/ for more information.
     * @removed The Application Cache API is no longer supported and this method
     *          is a no-op on WebView 95 and later. Consider using Service Workers
     *          instead. See https://web.dev/appcache-removal/ for more information.
     */
    @Deprecated
    public void setAppCachePath(String appCachePath) {}

    /**
     * Sets the maximum size for the Application Cache content. The passed size
     * will be rounded to the nearest value that the database can support, so
     * this should be viewed as a guide, not a hard limit. Setting the
     * size to a value less than current database size does not cause the
     * database to be trimmed. The default size is {@link Long#MAX_VALUE}.
     * It is recommended to leave the maximum size set to the default value.
     *
     * @param appCacheMaxSize the maximum size in bytes
     * @deprecated Quota is managed automatically; this method is a no-op.
     * @removed Quota is managed automatically; this method is a no-op.
     */
    @Deprecated
    public void setAppCacheMaxSize(long appCacheMaxSize) {}

    /**
     * Sets whether the database storage API is enabled. The default value is
     * false. See also {@link #setDatabasePath} for how to correctly set up the
     * database storage API.
     *
     * This setting is global in effect, across all WebView instances in a process.
     * Note you should only modify this setting prior to making <b>any</b> WebView
     * page load within a given process, as the WebView implementation may ignore
     * changes to this setting after that point.
     *
     * @param flag {@code true} if the WebView should use the database storage API
     * @deprecated WebSQL is deprecated and this method will become a no-op on all
     * Android versions once support is removed in Chromium. See
     * https://developer.chrome.com/blog/deprecating-web-sql for more information.
     */
    @Deprecated
    public abstract void setDatabaseEnabled(boolean flag);

    /**
     * Sets whether the DOM storage API is enabled. The default value is {@code false}.
     *
     * @param flag {@code true} if the WebView should use the DOM storage API
     */
    public abstract void setDomStorageEnabled(boolean flag);

    /**
     * Gets whether the DOM Storage APIs are enabled.
     *
     * @return {@code true} if the DOM Storage APIs are enabled
     * @see #setDomStorageEnabled
     */
    public abstract boolean getDomStorageEnabled();

    /**
     * Gets the path to where database storage API databases are saved.
     *
     * @return the String path to the database storage API databases
     * @see #setDatabasePath
     * @deprecated Database paths are managed by the implementation this method is obsolete.
     */
    @Deprecated
    public abstract String getDatabasePath();

    /**
     * Gets whether the database storage API is enabled.
     *
     * @return {@code true} if the database storage API is enabled
     * @see #setDatabaseEnabled
     * @deprecated WebSQL is deprecated and this method will become a no-op on all
     * Android versions once support is removed in Chromium. See
     * https://developer.chrome.com/blog/deprecating-web-sql for more information.
     */
    @Deprecated
    public abstract boolean getDatabaseEnabled();

    /**
     * Sets whether Geolocation is enabled. The default is {@code true}.
     * <p>
     * Please note that in order for the Geolocation API to be usable
     * by a page in the WebView, the following requirements must be met:
     * <ul>
     *   <li>an application must have permission to access the device location,
     *   see {@link android.Manifest.permission#ACCESS_COARSE_LOCATION},
     *   {@link android.Manifest.permission#ACCESS_FINE_LOCATION};
     *   <li>an application must provide an implementation of the
     *   {@link WebChromeClient#onGeolocationPermissionsShowPrompt} callback
     *   to receive notifications that a page is requesting access to location
     *   via the JavaScript Geolocation API.
     * </ul>
     * <p>
     *
     * @param flag whether Geolocation should be enabled
     */
    public abstract void setGeolocationEnabled(boolean flag);

    /**
     * Gets whether JavaScript is enabled.
     *
     * @return {@code true} if JavaScript is enabled
     * @see #setJavaScriptEnabled
     */
    public abstract boolean getJavaScriptEnabled();

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
     * @return {@code true} if plugins are enabled
     * @see #setPluginsEnabled
     * @deprecated This method has been replaced by {@link #getPluginState}
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    @Deprecated
    public abstract boolean getPluginsEnabled();

    /**
     * Gets the current state regarding whether plugins are enabled.
     *
     * @return the plugin state as a {@link PluginState} value
     * @see #setPluginState
     * @deprecated Plugins are not supported in API level
     *             {@link android.os.Build.VERSION_CODES#KITKAT} or later;
     *             enabling plugins is a no-op.
     */
    @Deprecated
    public abstract PluginState getPluginState();

    /**
     * Gets the directory that contains the plugin libraries. This method is
     * obsolete since each plugin is now loaded from its own package.
     *
     * @return an empty string
     * @deprecated This method is no longer used as plugins are loaded from
     * their own APK via the system's package manager.
     * @hide Since API level {@link android.os.Build.VERSION_CODES#JELLY_BEAN_MR2}
     */
    @Deprecated
    @UnsupportedAppUsage
    public String getPluginsPath() {
        // Unconditionally returns empty string, so no need for derived classes to override.
        return "";
    }

    /**
     * Allows JavaScript to open windows without a user gesture. This applies to
     * the JavaScript function {@code window.open()}. The default is
     * {@code false}: attempts without a user gesture will fail and do nothing.
     * <p>
     * This is not affected by the {@link #setSupportMultipleWindows} setting;
     * the user gesture requirement is enforced even if multiple windows are
     * disabled.
     *
     * @param flag {@code true} if JavaScript can open windows without a user
     *             gesture.
     */
    public abstract void setJavaScriptCanOpenWindowsAutomatically(boolean flag);

    /**
     * Gets whether JavaScript can open windows without a user gesture.
     *
     * @return {@code true} if JavaScript can open windows without a user
     *         gesture using {@code window.open()}
     * @see #setJavaScriptCanOpenWindowsAutomatically
     */
    public abstract boolean getJavaScriptCanOpenWindowsAutomatically();

    /**
     * Sets the default text encoding name to use when decoding html pages.
     * The default is "UTF-8".
     *
     * @param encoding the text encoding name
     */
    public abstract void setDefaultTextEncodingName(String encoding);

    /**
     * Gets the default text encoding name.
     *
     * @return the default text encoding name as a string
     * @see #setDefaultTextEncodingName
     */
    public abstract String getDefaultTextEncodingName();

    /**
     * Sets the WebView's user-agent string. If the string is {@code null} or empty,
     * the system default value will be used.
     *
     * <p>If the user-agent is overridden in this way, the values of the User-Agent Client Hints
     * headers and {@code navigator.userAgentData} for this WebView could be changed.
     * <p> See <a href="{@docRoot}reference/androidx/webkit/WebSettingsCompat
     * #setUserAgentMetadata(WebSettings,UserAgentMetadata)">androidx.webkit.WebSettingsCompat
     * #setUserAgentMetadata(WebSettings,UserAgentMetadata)</a> for details.
     *
     * <p>Note that starting from {@link android.os.Build.VERSION_CODES#KITKAT} Android
     * version, changing the user-agent while loading a web page causes WebView
     * to initiate loading once again.
     *
     * @param ua new user-agent string
     */
    public abstract void setUserAgentString(@Nullable String ua);

    /**
     * Gets the WebView's user-agent string.
     *
     * @return the WebView's user-agent string
     * @see #setUserAgentString
     */
    public abstract String getUserAgentString();

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
     * default value is {@code true}.
     *
     * @param flag whether the WebView needs to set a node
     */
    public abstract void setNeedInitialFocus(boolean flag);

    /**
     * Sets the priority of the Render thread. Unlike the other settings, this
     * one only needs to be called once per process. The default value is
     * {@link RenderPriority#NORMAL}.
     *
     * @param priority the priority
     * @deprecated It is not recommended to adjust thread priorities, and this will
     *             not be supported in future versions.
     */
    @Deprecated
    public abstract void setRenderPriority(RenderPriority priority);

    /**
     * Overrides the way the cache is used. The way the cache is used is based
     * on the navigation type. For a normal page load, the cache is checked
     * and content is re-validated as needed. When navigating back, content is
     * not revalidated, instead the content is just retrieved from the cache.
     * This method allows the client to override this behavior by specifying
     * one of {@link #LOAD_DEFAULT},
     * {@link #LOAD_CACHE_ELSE_NETWORK}, {@link #LOAD_NO_CACHE} or
     * {@link #LOAD_CACHE_ONLY}. The default value is {@link #LOAD_DEFAULT}.
     *
     * @param mode the mode to use
     */
    public abstract void setCacheMode(@CacheMode int mode);

    /**
     * Gets the current setting for overriding the cache mode.
     *
     * @return the current setting for overriding the cache mode
     * @see #setCacheMode
     */
    @CacheMode
    public abstract int getCacheMode();

    /**
     * Configures the WebView's behavior when a secure origin attempts to load a resource from an
     * insecure origin.
     *
     * By default, apps that target {@link android.os.Build.VERSION_CODES#KITKAT} or below default
     * to {@link #MIXED_CONTENT_ALWAYS_ALLOW}. Apps targeting
     * {@link android.os.Build.VERSION_CODES#LOLLIPOP} default to {@link #MIXED_CONTENT_NEVER_ALLOW}.
     *
     * The preferred and most secure mode of operation for the WebView is
     * {@link #MIXED_CONTENT_NEVER_ALLOW} and use of {@link #MIXED_CONTENT_ALWAYS_ALLOW} is
     * strongly discouraged.
     *
     * @param mode The mixed content mode to use. One of {@link #MIXED_CONTENT_NEVER_ALLOW},
     *     {@link #MIXED_CONTENT_ALWAYS_ALLOW} or {@link #MIXED_CONTENT_COMPATIBILITY_MODE}.
     */
    public abstract void setMixedContentMode(int mode);

    /**
     * Gets the current behavior of the WebView with regard to loading insecure content from a
     * secure origin.
     * @return The current setting, one of {@link #MIXED_CONTENT_NEVER_ALLOW},
     *     {@link #MIXED_CONTENT_ALWAYS_ALLOW} or {@link #MIXED_CONTENT_COMPATIBILITY_MODE}.
     */
    public abstract int getMixedContentMode();

    /**
     * Sets whether to use a video overlay for embedded encrypted video.
     * In API levels prior to {@link android.os.Build.VERSION_CODES#LOLLIPOP}, encrypted video can
     * only be rendered directly on a secure video surface, so it had been a hard problem to play
     * encrypted video in HTML.  When this flag is on, WebView can play encrypted video (MSE/EME)
     * by using a video overlay (aka hole-punching) for videos embedded using HTML &lt;video&gt;
     * tag.<br>
     * Caution: This setting is intended for use only in a narrow set of circumstances and apps
     * should only enable it if they require playback of encrypted video content. It will impose
     * the following limitations on the WebView:
     * <ul>
     * <li> Only one video overlay can be played at a time.
     * <li> Changes made to position or dimensions of a video element may be propagated to the
     * corresponding video overlay with a noticeable delay.
     * <li> The video overlay is not visible to web APIs and as such may not interact with
     * script or styling. For example, CSS styles applied to the &lt;video&gt; tag may be ignored.
     * </ul>
     * This is not an exhaustive set of constraints and it may vary with new versions of the
     * WebView.
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    public abstract void setVideoOverlayForEmbeddedEncryptedVideoEnabled(boolean flag);

    /**
     * Gets whether a video overlay will be used for embedded encrypted video.
     *
     * @return {@code true} if WebView uses a video overlay for embedded encrypted video.
     * @see #setVideoOverlayForEmbeddedEncryptedVideoEnabled
     * @hide
     */
    @SuppressWarnings("HiddenAbstractMethod")
    @SystemApi
    public abstract boolean getVideoOverlayForEmbeddedEncryptedVideoEnabled();

    /**
     * Sets whether this WebView should raster tiles when it is
     * offscreen but attached to a window. Turning this on can avoid
     * rendering artifacts when animating an offscreen WebView on-screen.
     * Offscreen WebViews in this mode use more memory. The default value is
     * false.<br>
     * Please follow these guidelines to limit memory usage:
     * <ul>
     * <li> WebView size should be not be larger than the device screen size.
     * <li> Limit use of this mode to a small number of WebViews. Use it for
     *   visible WebViews and WebViews about to be animated to visible.
     * </ul>
     */
    public abstract void setOffscreenPreRaster(boolean enabled);

    /**
     * Gets whether this WebView should raster tiles when it is
     * offscreen but attached to a window.
     * @return {@code true} if this WebView will raster tiles when it is
     * offscreen but attached to a window.
     */
    public abstract boolean getOffscreenPreRaster();


    /**
     * Sets whether Safe Browsing is enabled. Safe Browsing allows WebView to
     * protect against malware and phishing attacks by verifying the links.
     *
     * <p>
     * Safe Browsing can be disabled for all WebViews using a manifest tag (read <a
     * href="{@docRoot}reference/android/webkit/WebView.html">general Safe Browsing info</a>). The
     * manifest tag has a lower precedence than this API.
     *
     * <p>
     * Safe Browsing is enabled by default for devices which support it.
     *
     * @param enabled Whether Safe Browsing is enabled.
     */
    public abstract void setSafeBrowsingEnabled(boolean enabled);

    /**
     * Gets whether Safe Browsing is enabled.
     * See {@link #setSafeBrowsingEnabled}.
     *
     * @return {@code true} if Safe Browsing is enabled and {@code false} otherwise.
     */
    public abstract boolean getSafeBrowsingEnabled();


    /**
     * Set the force dark mode for this WebView.
     *
     * @param forceDark the force dark mode to set.
     * @see #getForceDark
     * @deprecated The "force dark" model previously implemented by WebView was complex
     * and didn't interoperate well with current Web standards for
     * {@code prefers-color-scheme} and {@code color-scheme}. In apps with
     * {@code targetSdkVersion} &ge; {@link android.os.Build.VERSION_CODES#TIRAMISU}
     * this API is a no-op and WebView will always use the dark style defined by web content
     * authors if the app's theme is dark. To customize the behavior, refer to
     * {@link #setAlgorithmicDarkeningAllowed}.
     */
    public void setForceDark(@ForceDark int forceDark) {
        // Stub implementation to satisfy Roboelectrc shadows that don't override this yet.
    }

    /**
     * Get the force dark mode for this WebView.
     * The default force dark mode is {@link #FORCE_DARK_AUTO}.
     *
     * @return the currently set force dark mode.
     * @see #setForceDark
     * @deprecated refer to {@link #setForceDark}.
     */
    public @ForceDark int getForceDark() {
        // Stub implementation to satisfy Roboelectrc shadows that don't override this yet.
        return FORCE_DARK_AUTO;
    }

    /**
     * Control whether algorithmic darkening is allowed.
     *
     * <p class="note">
     * <b>Note:</b> This API and the behaviour described only apply to apps with
     * {@code targetSdkVersion} &ge; {@link android.os.Build.VERSION_CODES#TIRAMISU}.
     *
     * <p>
     * WebView always sets the media query {@code prefers-color-scheme} according to the app's
     * theme attribute {@link android.R.styleable#Theme_isLightTheme isLightTheme}, i.e.
     * {@code prefers-color-scheme} is {@code light} if isLightTheme is true or not specified,
     * otherwise it is {@code dark}. This means that the web content's light or dark style will
     * be applied automatically to match the app's theme if the content supports it.
     *
     * <p>
     * Algorithmic darkening is disallowed by default.
     * <p>
     * If the app's theme is dark and it allows algorithmic darkening, WebView will attempt to
     * darken web content using an algorithm, if the content doesn't define its own dark styles
     * and doesn't explicitly disable darkening.
     *
     * <p>
     * If Android is applying Force Dark to WebView then WebView will ignore the value of
     * this setting and behave as if it were set to true.
     *
     * <p>
     * The deprecated {@link #setForceDark} and related API are no-ops in apps with
     * {@code targetSdkVersion} &ge; {@link android.os.Build.VERSION_CODES#TIRAMISU},
     * but they still apply to apps with
     * {@code targetSdkVersion} &lt; {@link android.os.Build.VERSION_CODES#TIRAMISU}.
     *
     * <p>
     * The below table summarizes how APIs work with different apps.
     *
     * <table border="2" width="85%" align="center" cellpadding="5">
     *     <thead>
     *         <tr>
     *             <th>App</th>
     *             <th>Web content which uses {@code prefers-color-scheme}</th>
     *             <th>Web content which does not use {@code prefers-color-scheme}</th>
     *         </tr>
     *     </thead>
     *     <tbody>
     *     <tr>
     *         <td>App with {@code isLightTheme} True or not set</td>
     *         <td>Renders with the light theme defined by the content author.</td>
     *         <td>Renders with the default styling defined by the content author.</td>
     *     </tr>
     *     <tr>
     *         <td>App with Android forceDark in effect</td>
     *         <td>Renders with the dark theme defined by the content author.</td>
     *         <td>Renders with the styling modified to dark colors by an algorithm
     *             if allowed by the content author.</td>
     *     </tr>
     *     <tr>
     *         <td>App with {@code isLightTheme} False,
     *            {@code targetSdkVersion} &lt; {@link android.os.Build.VERSION_CODES#TIRAMISU},
     *             and has {@code FORCE_DARK_AUTO}</td>
     *         <td>Renders with the dark theme defined by the content author.</td>
     *         <td>Renders with the default styling defined by the content author.</td>
     *     </tr>
     *     <tr>
     *         <td>App with {@code isLightTheme} False,
     *            {@code targetSdkVersion} &ge; {@link android.os.Build.VERSION_CODES#TIRAMISU},
     *             and {@code setAlgorithmicDarkening(false)}</td>
     *         <td>Renders with the dark theme defined by the content author.</td>
     *         <td>Renders with the default styling defined by the content author.</td>
     *     </tr>
     *     <tr>
     *         <td>App with {@code isLightTheme} False,
     *            {@code targetSdkVersion} &ge; {@link android.os.Build.VERSION_CODES#TIRAMISU},
     *             and {@code setAlgorithmicDarkening(true)}</td>
     *         <td>Renders with the dark theme defined by the content author.</td>
     *         <td>Renders with the styling modified to dark colors by an algorithm if allowed
     *             by the content author.</td>
     *     </tr>
     *     </tbody>
     * </table>
     * </p>
     *
     * @param allow allow algorithmic darkening or not.
     */
    public void setAlgorithmicDarkeningAllowed(boolean allow) {
        // Stub implementation to satisfy Roboelectrc shadows that don't override this yet.
    }

    /**
     * Get if algorithmic darkening is allowed or not for this WebView.
     * The default is false.
     *
     * @return if the algorithmic darkening is allowed or not.
     * @see #setAlgorithmicDarkeningAllowed
     */
    public boolean isAlgorithmicDarkeningAllowed() {
        // Stub implementation to satisfy Roboelectrc shadows that don't override this yet.
        return false;
    }

    /**
     * @hide
     */
    @IntDef(flag = true, prefix = { "MENU_ITEM_" }, value = {
            MENU_ITEM_NONE,
            MENU_ITEM_SHARE,
            MENU_ITEM_WEB_SEARCH,
            MENU_ITEM_PROCESS_TEXT
    })
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.PARAMETER, ElementType.METHOD})
    private @interface MenuItemFlags {}

    /**
     * Disables the action mode menu items according to {@code menuItems} flag.
     * @param menuItems an integer field flag for the menu items to be disabled.
     */
    public abstract void setDisabledActionModeMenuItems(@MenuItemFlags int menuItems);

    /**
     * Gets the action mode menu items that are disabled, expressed in an integer field flag.
     * The default value is {@link #MENU_ITEM_NONE}
     *
     * @return all the disabled menu item flags combined with bitwise OR.
     */
    public abstract @MenuItemFlags int getDisabledActionModeMenuItems();

    /**
     * No menu items should be disabled.
     *
     * @see #setDisabledActionModeMenuItems
     */
    public static final int MENU_ITEM_NONE = 0;

    /**
     * Disable menu item "Share".
     *
     * @see #setDisabledActionModeMenuItems
     */
    public static final int MENU_ITEM_SHARE = 1 << 0;

    /**
     * Disable menu item "Web Search".
     *
     * @see #setDisabledActionModeMenuItems
     */
    public static final int MENU_ITEM_WEB_SEARCH = 1 << 1;

    /**
     * Disable all the action mode menu items for text processing.
     * By default WebView searches for activities that are able to handle
     * {@link android.content.Intent#ACTION_PROCESS_TEXT} and show them in the
     * action mode menu. If this flag is set via {@link
     * #setDisabledActionModeMenuItems}, these menu items will be disabled.
     *
     * @see #setDisabledActionModeMenuItems
     */
    public static final int MENU_ITEM_PROCESS_TEXT = 1 << 2;
}
