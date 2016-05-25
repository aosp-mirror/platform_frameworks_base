/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import com.android.ide.common.rendering.api.HardwareConfig;
import com.android.ide.common.rendering.api.RenderResources;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.SessionParams;
import com.android.ide.common.rendering.api.StyleResourceValue;
import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.layoutlib.bridge.android.RenderParamsFlags;
import com.android.layoutlib.bridge.bars.AppCompatActionBar;
import com.android.layoutlib.bridge.bars.BridgeActionBar;
import com.android.layoutlib.bridge.bars.Config;
import com.android.layoutlib.bridge.bars.FrameworkActionBar;
import com.android.layoutlib.bridge.bars.NavigationBar;
import com.android.layoutlib.bridge.bars.StatusBar;
import com.android.layoutlib.bridge.bars.TitleBar;
import com.android.resources.Density;
import com.android.resources.ResourceType;
import com.android.resources.ScreenOrientation;

import android.annotation.NonNull;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.widget.LinearLayout.VERTICAL;
import static com.android.layoutlib.bridge.impl.ResourceHelper.getBooleanThemeValue;

/**
 * The Layout used to create the system decor.
 *
 * The layout inflated will contain a content frame where the user's layout can be inflated.
 * <pre>
 *  +-------------------------------------------------+---+
 *  | Status bar                                      | N |
 *  +-------------------------------------------------+ a |
 *  | Title/Action bar (optional)                     | v |
 *  +-------------------------------------------------+   |
 *  | Content, vertical extending                     | b |
 *  |                                                 | a |
 *  |                                                 | r |
 *  +-------------------------------------------------+---+
 * </pre>
 * or
 * <pre>
 *  +-------------------------------------+
 *  | Status bar                          |
 *  +-------------------------------------+
 *  | Title/Action bar (optional)         |
 *  +-------------------------------------+
 *  | Content, vertical extending         |
 *  |                                     |
 *  |                                     |
 *  +-------------------------------------+
 *  | Nav bar                             |
 *  +-------------------------------------+
 * </pre>
 *
 */
class Layout extends RelativeLayout {

    // Theme attributes used for configuring appearance of the system decor.
    private static final String ATTR_WINDOW_FLOATING = "windowIsFloating";
    private static final String ATTR_WINDOW_BACKGROUND = "windowBackground";
    private static final String ATTR_WINDOW_FULL_SCREEN = "windowFullscreen";
    private static final String ATTR_NAV_BAR_HEIGHT = "navigation_bar_height";
    private static final String ATTR_NAV_BAR_WIDTH = "navigation_bar_width";
    private static final String ATTR_STATUS_BAR_HEIGHT = "status_bar_height";
    private static final String ATTR_WINDOW_ACTION_BAR = "windowActionBar";
    private static final String ATTR_ACTION_BAR_SIZE = "actionBarSize";
    private static final String ATTR_WINDOW_NO_TITLE = "windowNoTitle";
    private static final String ATTR_WINDOW_TITLE_SIZE = "windowTitleSize";
    private static final String ATTR_WINDOW_TRANSLUCENT_STATUS = StatusBar.ATTR_TRANSLUCENT;
    private static final String ATTR_WINDOW_TRANSLUCENT_NAV = NavigationBar.ATTR_TRANSLUCENT;
    private static final String PREFIX_THEME_APPCOMPAT = "Theme.AppCompat";

    // Default sizes
    private static final int DEFAULT_STATUS_BAR_HEIGHT = 25;
    private static final int DEFAULT_TITLE_BAR_HEIGHT = 25;
    private static final int DEFAULT_NAV_BAR_SIZE = 48;

    // Ids assigned to components created. This is so that we can refer to other components in
    // layout params.
    private static final String ID_NAV_BAR = "navBar";
    private static final String ID_STATUS_BAR = "statusBar";
    private static final String ID_TITLE_BAR = "titleBar";
    // Prefix used with the above ids in order to make them unique in framework namespace.
    private static final String ID_PREFIX = "android_layoutlib_";

    /**
     * Temporarily store the builder so that it doesn't have to be passed to all methods used
     * during inflation.
     */
    private Builder mBuilder;

    /**
     * This holds user's layout.
     */
    private FrameLayout mContentRoot;

    public Layout(@NonNull Builder builder) {
        super(builder.mContext);
        mBuilder = builder;
        if (builder.mWindowBackground != null) {
            Drawable d = ResourceHelper.getDrawable(builder.mWindowBackground, builder.mContext);
            setBackground(d);
        }

        int simulatedPlatformVersion = getParams().getSimulatedPlatformVersion();
        HardwareConfig hwConfig = getParams().getHardwareConfig();
        Density density = hwConfig.getDensity();
        boolean isRtl = Bridge.isLocaleRtl(getParams().getLocale());
        setLayoutDirection(isRtl? LAYOUT_DIRECTION_RTL : LAYOUT_DIRECTION_LTR);

        NavigationBar navBar = null;
        if (mBuilder.hasNavBar()) {
            navBar = createNavBar(getContext(), density, isRtl, getParams().isRtlSupported(),
                    simulatedPlatformVersion);
        }

        StatusBar statusBar = null;
        if (builder.mStatusBarSize > 0) {
            statusBar = createStatusBar(getContext(), density, isRtl, getParams().isRtlSupported(),
                    simulatedPlatformVersion);
        }

        View actionBar = null;
        TitleBar titleBar = null;
        if (builder.mActionBarSize > 0) {
            BridgeActionBar bar = createActionBar(getContext(), getParams());
            mContentRoot = bar.getContentRoot();
            actionBar = bar.getRootView();
        } else if (mBuilder.mTitleBarSize > 0) {
            titleBar = createTitleBar(getContext(), getParams().getAppLabel(),
                    simulatedPlatformVersion);
        }

        addViews(titleBar, mContentRoot == null ? (mContentRoot = createContentFrame()) : actionBar,
                statusBar, navBar);
        // Done with the builder. Don't hold a reference to it.
        mBuilder = null;
     }

    @NonNull
    private FrameLayout createContentFrame() {
        FrameLayout contentRoot = new FrameLayout(getContext());
        LayoutParams params = createLayoutParams(MATCH_PARENT, MATCH_PARENT);
        int rule = mBuilder.isNavBarVertical() ? START_OF : ABOVE;
        if (mBuilder.hasNavBar() && mBuilder.solidBars()) {
            params.addRule(rule, getId(ID_NAV_BAR));
        }
        int below = -1;
        if (mBuilder.mActionBarSize <= 0 && mBuilder.mTitleBarSize > 0) {
            below = getId(ID_TITLE_BAR);
        } else if (mBuilder.hasStatusBar() && mBuilder.solidBars()) {
            below = getId(ID_STATUS_BAR);
        }
        if (below != -1) {
            params.addRule(BELOW, below);
        }
        contentRoot.setLayoutParams(params);
        return contentRoot;
    }

    @NonNull
    private LayoutParams createLayoutParams(int width, int height) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        if (width > 0) {
            width = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, width, metrics);
        }
        if (height > 0) {
            height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, height, metrics);
        }
        return new LayoutParams(width, height);
    }

    @NonNull
    public FrameLayout getContentRoot() {
        return mContentRoot;
    }

    @NonNull
    private SessionParams getParams() {
        return mBuilder.mParams;
    }

    @NonNull
    @Override
    public BridgeContext getContext(){
        return (BridgeContext) super.getContext();
    }

    /**
     * @param isRtl    whether the current locale is an RTL locale.
     * @param isRtlSupported    whether the applications supports RTL (i.e. has supportsRtl=true
     * in the manifest and targetSdkVersion >= 17.
     */
    @NonNull
    private StatusBar createStatusBar(BridgeContext context, Density density, boolean isRtl,
            boolean isRtlSupported, int simulatedPlatformVersion) {
        StatusBar statusBar =
                new StatusBar(context, density, isRtl, isRtlSupported, simulatedPlatformVersion);
        LayoutParams params = createLayoutParams(MATCH_PARENT, mBuilder.mStatusBarSize);
        if (mBuilder.isNavBarVertical()) {
            params.addRule(START_OF, getId(ID_NAV_BAR));
        }
        statusBar.setLayoutParams(params);
        statusBar.setId(getId(ID_STATUS_BAR));
        return statusBar;
    }

    private BridgeActionBar createActionBar(@NonNull BridgeContext context,
            @NonNull SessionParams params) {
        boolean isMenu = "menu".equals(params.getFlag(RenderParamsFlags.FLAG_KEY_ROOT_TAG));

        BridgeActionBar actionBar;
        if (mBuilder.isThemeAppCompat() && !isMenu) {
            actionBar = new AppCompatActionBar(context, params);
        } else {
            actionBar = new FrameworkActionBar(context, params);
        }
        LayoutParams layoutParams = createLayoutParams(MATCH_PARENT, MATCH_PARENT);
        int rule = mBuilder.isNavBarVertical() ? START_OF : ABOVE;
        if (mBuilder.hasNavBar() && mBuilder.solidBars()) {
            layoutParams.addRule(rule, getId(ID_NAV_BAR));
        }
        if (mBuilder.hasStatusBar() && mBuilder.solidBars()) {
            layoutParams.addRule(BELOW, getId(ID_STATUS_BAR));
        }
        actionBar.getRootView().setLayoutParams(layoutParams);
        actionBar.createMenuPopup();
        return actionBar;
    }

    @NonNull
    private TitleBar createTitleBar(BridgeContext context, String title,
            int simulatedPlatformVersion) {
        TitleBar titleBar = new TitleBar(context, title, simulatedPlatformVersion);
        LayoutParams params = createLayoutParams(MATCH_PARENT, mBuilder.mTitleBarSize);
        if (mBuilder.hasStatusBar() && mBuilder.solidBars()) {
            params.addRule(BELOW, getId(ID_STATUS_BAR));
        }
        if (mBuilder.isNavBarVertical() && mBuilder.solidBars()) {
            params.addRule(START_OF, getId(ID_NAV_BAR));
        }
        titleBar.setLayoutParams(params);
        titleBar.setId(getId(ID_TITLE_BAR));
        return titleBar;
    }

    /**
     * @param isRtl    whether the current locale is an RTL locale.
     * @param isRtlSupported    whether the applications supports RTL (i.e. has supportsRtl=true
     * in the manifest and targetSdkVersion >= 17.
     */
    @NonNull
    private NavigationBar createNavBar(BridgeContext context, Density density, boolean isRtl,
            boolean isRtlSupported, int simulatedPlatformVersion) {
        int orientation = mBuilder.mNavBarOrientation;
        int size = mBuilder.mNavBarSize;
        NavigationBar navBar = new NavigationBar(context, density, orientation, isRtl,
                isRtlSupported, simulatedPlatformVersion);
        boolean isVertical = mBuilder.isNavBarVertical();
        int w = isVertical ? size : MATCH_PARENT;
        int h = isVertical ? MATCH_PARENT : size;
        LayoutParams params = createLayoutParams(w, h);
        params.addRule(isVertical ? ALIGN_PARENT_END : ALIGN_PARENT_BOTTOM);
        navBar.setLayoutParams(params);
        navBar.setId(getId(ID_NAV_BAR));
        return navBar;
    }

    private void addViews(@NonNull View... views) {
        for (View view : views) {
            if (view != null) {
                addView(view);
            }
        }
    }

    private int getId(String name) {
        return Bridge.getResourceId(ResourceType.ID, ID_PREFIX + name);
    }

    /**
     * A helper class to help initialize the Layout.
     */
    static class Builder {
        @NonNull
        private final SessionParams mParams;
        @NonNull
        private final BridgeContext mContext;
        private final RenderResources mResources;
        
        private final boolean mWindowIsFloating;
        private ResourceValue mWindowBackground;
        private int mStatusBarSize;
        private int mNavBarSize;
        private int mNavBarOrientation;
        private int mActionBarSize;
        private int mTitleBarSize;
        private boolean mTranslucentStatus;
        private boolean mTranslucentNav;

        private Boolean mIsThemeAppCompat;

        public Builder(@NonNull SessionParams params, @NonNull BridgeContext context) {
            mParams = params;
            mContext = context;
            mResources = mParams.getResources();
            mWindowIsFloating = getBooleanThemeValue(mResources, ATTR_WINDOW_FLOATING, true, true);
            
            findBackground();

            if (!mParams.isForceNoDecor()) {
                findStatusBar();
                findActionBar();
                findNavBar();
            }
        }

        private void findBackground() {
            if (!mParams.isBgColorOverridden()) {
                mWindowBackground = mResources.findItemInTheme(ATTR_WINDOW_BACKGROUND, true);
                mWindowBackground = mResources.resolveResValue(mWindowBackground);
            }
        }

        private void findStatusBar() {
            boolean windowFullScreen =
                    getBooleanThemeValue(mResources, ATTR_WINDOW_FULL_SCREEN, true, false);
            if (!windowFullScreen && !mWindowIsFloating) {
                mStatusBarSize =
                        getDimension(ATTR_STATUS_BAR_HEIGHT, true, DEFAULT_STATUS_BAR_HEIGHT);
                mTranslucentStatus = getBooleanThemeValue(mResources,
                        ATTR_WINDOW_TRANSLUCENT_STATUS, true, false);
            }
        }

        private void  findActionBar() {
            if (mWindowIsFloating) {
                return;
            }
            // Check if an actionbar is needed
            boolean windowActionBar = getBooleanThemeValue(mResources, ATTR_WINDOW_ACTION_BAR,
                    !isThemeAppCompat(), true);
            if (windowActionBar) {
                mActionBarSize = getDimension(ATTR_ACTION_BAR_SIZE, true, DEFAULT_TITLE_BAR_HEIGHT);
            } else {
                // Maybe the gingerbread era title bar is needed
                boolean windowNoTitle =
                        getBooleanThemeValue(mResources, ATTR_WINDOW_NO_TITLE, true, false);
                if (!windowNoTitle) {
                    mTitleBarSize =
                            getDimension(ATTR_WINDOW_TITLE_SIZE, true, DEFAULT_TITLE_BAR_HEIGHT);
                }
            }
        }

        private void findNavBar() {
            if (hasSoftwareButtons() && !mWindowIsFloating) {

                // get orientation
                HardwareConfig hwConfig = mParams.getHardwareConfig();
                boolean barOnBottom = true;

                if (hwConfig.getOrientation() == ScreenOrientation.LANDSCAPE) {
                    int shortSize = hwConfig.getScreenHeight();
                    int shortSizeDp = shortSize * DisplayMetrics.DENSITY_DEFAULT /
                            hwConfig.getDensity().getDpiValue();

                    // 0-599dp: "phone" UI with bar on the side
                    // 600+dp: "tablet" UI with bar on the bottom
                    barOnBottom = shortSizeDp >= 600;
                }

                mNavBarOrientation = barOnBottom ? LinearLayout.HORIZONTAL : VERTICAL;
                mNavBarSize = getDimension(barOnBottom ? ATTR_NAV_BAR_HEIGHT : ATTR_NAV_BAR_WIDTH,
                        true, DEFAULT_NAV_BAR_SIZE);
                mTranslucentNav = getBooleanThemeValue(mResources,
                        ATTR_WINDOW_TRANSLUCENT_NAV, true, false);
            }
        }

        private int getDimension(String attr, boolean isFramework, int defaultValue) {
            ResourceValue value = mResources.findItemInTheme(attr, isFramework);
            value = mResources.resolveResValue(value);
            if (value != null) {
                TypedValue typedValue = ResourceHelper.getValue(attr, value.getValue(), true);
                if (typedValue != null) {
                    return (int) typedValue.getDimension(mContext.getMetrics());
                }
            }
            return defaultValue;
        }

        private boolean hasSoftwareButtons() {
            return mParams.getHardwareConfig().hasSoftwareButtons();
        }

        private boolean isThemeAppCompat() {
            // If a cached value exists, return it.
            if (mIsThemeAppCompat != null) {
                return mIsThemeAppCompat;
            }
            // Ideally, we should check if the corresponding activity extends
            // android.support.v7.app.ActionBarActivity, and not care about the theme name at all.
            StyleResourceValue defaultTheme = mResources.getDefaultTheme();
            // We can't simply check for parent using resources.themeIsParentOf() since the
            // inheritance structure isn't really what one would expect. The first common parent
            // between Theme.AppCompat.Light and Theme.AppCompat is Theme.Material (for v21).
            boolean isThemeAppCompat = false;
            for (int i = 0; i < 50; i++) {
                if (defaultTheme == null) {
                    break;
                }
                // for loop ensures that we don't run into cyclic theme inheritance.
                if (defaultTheme.getName().startsWith(PREFIX_THEME_APPCOMPAT)) {
                    isThemeAppCompat = true;
                    break;
                }
                defaultTheme = mResources.getParent(defaultTheme);
            }
            mIsThemeAppCompat = isThemeAppCompat;
            return isThemeAppCompat;
        }

        /**
         * Return true if the status bar or nav bar are present, they are not translucent (i.e
         * content doesn't overlap with them).
         */
        private boolean solidBars() {
            return !(hasNavBar() && mTranslucentNav) && !(hasStatusBar() && mTranslucentStatus);
        }

        private boolean hasNavBar() {
            return Config.showOnScreenNavBar(mParams.getSimulatedPlatformVersion()) &&
                    hasSoftwareButtons() && mNavBarSize > 0;
        }

        private boolean hasStatusBar() {
            return mStatusBarSize > 0;
        }

        /**
         * Return true if the nav bar is present and is vertical.
         */
        private boolean isNavBarVertical() {
            return hasNavBar() && mNavBarOrientation == VERTICAL;
        }
    }
}
