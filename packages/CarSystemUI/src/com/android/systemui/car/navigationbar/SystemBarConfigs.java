/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.navigationbar;

import android.annotation.IntDef;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.view.Gravity;
import android.view.InsetsState;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;
import com.android.systemui.car.notification.BottomNotificationPanelViewMediator;
import com.android.systemui.car.notification.TopNotificationPanelViewMediator;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

/**
 * Reads configs for system bars for each side (TOP, BOTTOM, LEFT, and RIGHT) and returns the
 * corresponding {@link android.view.WindowManager.LayoutParams} per the configuration.
 */
@SysUISingleton
public class SystemBarConfigs {

    private static final String TAG = SystemBarConfigs.class.getSimpleName();
    // The z-order from which system bars will start to appear on top of HUN's.
    private static final int HUN_ZORDER = 10;

    @IntDef(value = {TOP, BOTTOM, LEFT, RIGHT})
    @Target({ElementType.TYPE_PARAMETER, ElementType.TYPE_USE})
    private @interface SystemBarSide {
    }

    public static final int TOP = 0;
    public static final int BOTTOM = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;

    /*
        NOTE: The elements' order in the map below must be preserved as-is since the correct
        corresponding values are obtained by the index.
     */
    private static final int[] BAR_TYPE_MAP = {
            InsetsState.ITYPE_STATUS_BAR,
            InsetsState.ITYPE_NAVIGATION_BAR,
            InsetsState.ITYPE_CLIMATE_BAR,
            InsetsState.ITYPE_EXTRA_NAVIGATION_BAR
    };

    private static final Map<@SystemBarSide Integer, Integer> BAR_GRAVITY_MAP = new ArrayMap<>();
    private static final Map<@SystemBarSide Integer, String> BAR_TITLE_MAP = new ArrayMap<>();
    private static final Map<@SystemBarSide Integer, Integer> BAR_GESTURE_MAP = new ArrayMap<>();

    private final Resources mResources;
    private final Map<@SystemBarSide Integer, SystemBarConfig> mSystemBarConfigMap =
            new ArrayMap<>();
    private final List<@SystemBarSide Integer> mSystemBarSidesByZOrder = new ArrayList<>();

    private boolean mTopNavBarEnabled;
    private boolean mBottomNavBarEnabled;
    private boolean mLeftNavBarEnabled;
    private boolean mRightNavBarEnabled;

    @Inject
    public SystemBarConfigs(@Main Resources resources) {
        mResources = resources;

        populateMaps();
        readConfigs();
        checkEnabledBarsHaveUniqueBarTypes();
        checkAllOverlappingBarsHaveDifferentZOrders();
        checkSystemBarEnabledForNotificationPanel();
        setInsetPaddingsForOverlappingCorners();
        sortSystemBarSidesByZOrder();
    }

    protected WindowManager.LayoutParams getLayoutParamsBySide(@SystemBarSide int side) {
        return mSystemBarConfigMap.get(side) != null
                ? mSystemBarConfigMap.get(side).getLayoutParams() : null;
    }

    protected boolean getEnabledStatusBySide(@SystemBarSide int side) {
        switch (side) {
            case TOP:
                return mTopNavBarEnabled;
            case BOTTOM:
                return mBottomNavBarEnabled;
            case LEFT:
                return mLeftNavBarEnabled;
            case RIGHT:
                return mRightNavBarEnabled;
            default:
                return false;
        }
    }

    protected void insetSystemBar(@SystemBarSide int side, CarNavigationBarView view) {
        if (mSystemBarConfigMap.get(side) == null) return;

        int[] paddings = mSystemBarConfigMap.get(side).getPaddings();
        view.setPadding(paddings[2], paddings[0], paddings[3], paddings[1]);
    }

    protected void setInsetUpdater(@SystemBarSide int side, CarNavigationBarView view) {
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            updateInsetPaddings(side, getSystemBarsVisibility(insets));
            insetSystemBar(side, view);
            return insets;
        });
    }

    protected List<Integer> getSystemBarSidesByZOrder() {
        return mSystemBarSidesByZOrder;
    }

    @VisibleForTesting
    void updateInsetPaddings(@SystemBarSide int side,
            Map<@SystemBarSide Integer, Boolean> barVisibilities) {
        SystemBarConfig currentConfig = mSystemBarConfigMap.get(side);

        if (currentConfig == null) return;

        if (isHorizontalBar(side)) {
            if (mLeftNavBarEnabled && currentConfig.getZOrder() < mSystemBarConfigMap.get(
                    LEFT).getZOrder()) {
                currentConfig.setPaddingBySide(LEFT,
                        barVisibilities.get(LEFT) ? mSystemBarConfigMap.get(LEFT).getGirth() : 0);
            }
            if (mRightNavBarEnabled && currentConfig.getZOrder() < mSystemBarConfigMap.get(
                    RIGHT).getZOrder()) {
                currentConfig.setPaddingBySide(RIGHT,
                        barVisibilities.get(RIGHT) ? mSystemBarConfigMap.get(RIGHT).getGirth() : 0);
            }
        }
        if (isVerticalBar(side)) {
            if (mTopNavBarEnabled && currentConfig.getZOrder() < mSystemBarConfigMap.get(
                    TOP).getZOrder()) {
                currentConfig.setPaddingBySide(TOP,
                        barVisibilities.get(TOP) ? mSystemBarConfigMap.get(TOP).getGirth() : 0);
            }
            if (mBottomNavBarEnabled && currentConfig.getZOrder() < mSystemBarConfigMap.get(
                    BOTTOM).getZOrder()) {
                currentConfig.setPaddingBySide(BOTTOM,
                        barVisibilities.get(BOTTOM) ? mSystemBarConfigMap.get(BOTTOM).getGirth()
                                : 0);
            }
        }
    }

    @VisibleForTesting
    static int getHunZOrder() {
        return HUN_ZORDER;
    }

    private static void populateMaps() {
        BAR_GRAVITY_MAP.put(TOP, Gravity.TOP);
        BAR_GRAVITY_MAP.put(BOTTOM, Gravity.BOTTOM);
        BAR_GRAVITY_MAP.put(LEFT, Gravity.LEFT);
        BAR_GRAVITY_MAP.put(RIGHT, Gravity.RIGHT);

        BAR_TITLE_MAP.put(TOP, "TopCarSystemBar");
        BAR_TITLE_MAP.put(BOTTOM, "BottomCarSystemBar");
        BAR_TITLE_MAP.put(LEFT, "LeftCarSystemBar");
        BAR_TITLE_MAP.put(RIGHT, "RightCarSystemBar");

        BAR_GESTURE_MAP.put(TOP, InsetsState.ITYPE_TOP_GESTURES);
        BAR_GESTURE_MAP.put(BOTTOM, InsetsState.ITYPE_BOTTOM_GESTURES);
        BAR_GESTURE_MAP.put(LEFT, InsetsState.ITYPE_LEFT_GESTURES);
        BAR_GESTURE_MAP.put(RIGHT, InsetsState.ITYPE_RIGHT_GESTURES);
    }

    private void readConfigs() {
        mTopNavBarEnabled = mResources.getBoolean(R.bool.config_enableTopNavigationBar);
        mBottomNavBarEnabled = mResources.getBoolean(R.bool.config_enableBottomNavigationBar);
        mLeftNavBarEnabled = mResources.getBoolean(R.bool.config_enableLeftNavigationBar);
        mRightNavBarEnabled = mResources.getBoolean(R.bool.config_enableRightNavigationBar);

        if (mTopNavBarEnabled) {
            SystemBarConfig topBarConfig =
                    new SystemBarConfigBuilder()
                            .setSide(TOP)
                            .setGirth(mResources.getDimensionPixelSize(
                                    com.android.internal.R.dimen.status_bar_height))
                            .setBarType(mResources.getInteger(R.integer.config_topSystemBarType))
                            .setZOrder(mResources.getInteger(R.integer.config_topSystemBarZOrder))
                            .build();
            mSystemBarConfigMap.put(TOP, topBarConfig);
        }

        if (mBottomNavBarEnabled) {
            SystemBarConfig bottomBarConfig =
                    new SystemBarConfigBuilder()
                            .setSide(BOTTOM)
                            .setGirth(mResources.getDimensionPixelSize(
                                    com.android.internal.R.dimen.navigation_bar_height))
                            .setBarType(mResources.getInteger(R.integer.config_bottomSystemBarType))
                            .setZOrder(
                                    mResources.getInteger(R.integer.config_bottomSystemBarZOrder))
                            .build();
            mSystemBarConfigMap.put(BOTTOM, bottomBarConfig);
        }

        if (mLeftNavBarEnabled) {
            SystemBarConfig leftBarConfig =
                    new SystemBarConfigBuilder()
                            .setSide(LEFT)
                            .setGirth(mResources.getDimensionPixelSize(
                                    R.dimen.car_left_navigation_bar_width))
                            .setBarType(mResources.getInteger(R.integer.config_leftSystemBarType))
                            .setZOrder(mResources.getInteger(R.integer.config_leftSystemBarZOrder))
                            .build();
            mSystemBarConfigMap.put(LEFT, leftBarConfig);
        }

        if (mRightNavBarEnabled) {
            SystemBarConfig rightBarConfig =
                    new SystemBarConfigBuilder()
                            .setSide(RIGHT)
                            .setGirth(mResources.getDimensionPixelSize(
                                    R.dimen.car_right_navigation_bar_width))
                            .setBarType(mResources.getInteger(R.integer.config_rightSystemBarType))
                            .setZOrder(mResources.getInteger(R.integer.config_rightSystemBarZOrder))
                            .build();
            mSystemBarConfigMap.put(RIGHT, rightBarConfig);
        }
    }

    private void checkEnabledBarsHaveUniqueBarTypes() throws RuntimeException {
        Set<Integer> barTypesUsed = new ArraySet<>();
        int enabledNavBarCount = mSystemBarConfigMap.size();

        for (SystemBarConfig systemBarConfig : mSystemBarConfigMap.values()) {
            barTypesUsed.add(systemBarConfig.getBarType());
        }

        // The number of bar types used cannot be fewer than that of enabled system bars.
        if (barTypesUsed.size() < enabledNavBarCount) {
            throw new RuntimeException("Each enabled system bar must have a unique bar type. Check "
                    + "the configuration in config.xml");
        }
    }

    private void checkAllOverlappingBarsHaveDifferentZOrders() {
        checkOverlappingBarsHaveDifferentZOrders(TOP, LEFT);
        checkOverlappingBarsHaveDifferentZOrders(TOP, RIGHT);
        checkOverlappingBarsHaveDifferentZOrders(BOTTOM, LEFT);
        checkOverlappingBarsHaveDifferentZOrders(BOTTOM, RIGHT);
    }

    private void checkSystemBarEnabledForNotificationPanel() throws RuntimeException {
        String notificationPanelMediatorName =
                mResources.getString(R.string.config_notificationPanelViewMediator);
        if (notificationPanelMediatorName == null) {
            return;
        }

        Class<?> notificationPanelMediatorUsed = null;
        try {
            notificationPanelMediatorUsed = Class.forName(notificationPanelMediatorName);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }

        if (!mTopNavBarEnabled && notificationPanelMediatorUsed.isAssignableFrom(
                TopNotificationPanelViewMediator.class)) {
            throw new RuntimeException(
                    "Top System Bar must be enabled to use " + notificationPanelMediatorName);
        }

        if (!mBottomNavBarEnabled && notificationPanelMediatorUsed.isAssignableFrom(
                BottomNotificationPanelViewMediator.class)) {
            throw new RuntimeException("Bottom System Bar must be enabled to use "
                    + notificationPanelMediatorName);
        }
    }

    private void setInsetPaddingsForOverlappingCorners() {
        Map<@SystemBarSide Integer, Boolean> systemBarVisibilityOnInit =
                getSystemBarsVisibilityOnInit();
        updateInsetPaddings(TOP, systemBarVisibilityOnInit);
        updateInsetPaddings(BOTTOM, systemBarVisibilityOnInit);
        updateInsetPaddings(LEFT, systemBarVisibilityOnInit);
        updateInsetPaddings(RIGHT, systemBarVisibilityOnInit);
    }

    private void sortSystemBarSidesByZOrder() {
        List<SystemBarConfig> systemBarsByZOrder = new ArrayList<>(mSystemBarConfigMap.values());

        systemBarsByZOrder.sort(new Comparator<SystemBarConfig>() {
            @Override
            public int compare(SystemBarConfig o1, SystemBarConfig o2) {
                return o1.getZOrder() - o2.getZOrder();
            }
        });

        systemBarsByZOrder.forEach(systemBarConfig -> {
            mSystemBarSidesByZOrder.add(systemBarConfig.getSide());
        });
    }

    @InsetsState.InternalInsetsType
    private int getSystemBarTypeBySide(@SystemBarSide int side) {
        return mSystemBarConfigMap.get(side) != null
                ? mSystemBarConfigMap.get(side).getBarType() : null;
    }

    // On init, system bars are visible as long as they are enabled.
    private Map<@SystemBarSide Integer, Boolean> getSystemBarsVisibilityOnInit() {
        ArrayMap<@SystemBarSide Integer, Boolean> visibilityMap = new ArrayMap<>();
        visibilityMap.put(TOP, mTopNavBarEnabled);
        visibilityMap.put(BOTTOM, mBottomNavBarEnabled);
        visibilityMap.put(LEFT, mLeftNavBarEnabled);
        visibilityMap.put(RIGHT, mRightNavBarEnabled);
        return visibilityMap;
    }

    private Map<@SystemBarSide Integer, Boolean> getSystemBarsVisibility(WindowInsets insets) {
        ArrayMap<@SystemBarSide Integer, Boolean> visibilityMap = new ArrayMap<>();
        if (mTopNavBarEnabled) {
            visibilityMap.put(TOP, getSystemBarInsetVisibleBySide(TOP, insets));
        }
        if (mBottomNavBarEnabled) {
            visibilityMap.put(BOTTOM, getSystemBarInsetVisibleBySide(BOTTOM, insets));
        }
        if (mLeftNavBarEnabled) {
            visibilityMap.put(LEFT, getSystemBarInsetVisibleBySide(LEFT, insets));
        }
        if (mRightNavBarEnabled) {
            visibilityMap.put(RIGHT, getSystemBarInsetVisibleBySide(RIGHT, insets));
        }
        return visibilityMap;
    }

    private boolean getSystemBarInsetVisibleBySide(@SystemBarSide int side, WindowInsets insets) {
        if (mSystemBarConfigMap.get(side) == null) return false;

        int internalInsetType = BAR_TYPE_MAP[getSystemBarTypeBySide(side)];
        int publicInsetType = InsetsState.toPublicType(internalInsetType);

        return insets.isVisible(publicInsetType);
    }

    private void checkOverlappingBarsHaveDifferentZOrders(@SystemBarSide int horizontalSide,
            @SystemBarSide int verticalSide) {

        if (isVerticalBar(horizontalSide) || isHorizontalBar(verticalSide)) {
            Log.w(TAG, "configureBarPaddings: Returning immediately since the horizontal and "
                    + "vertical sides were not provided correctly.");
            return;
        }

        SystemBarConfig horizontalBarConfig = mSystemBarConfigMap.get(horizontalSide);
        SystemBarConfig verticalBarConfig = mSystemBarConfigMap.get(verticalSide);

        if (verticalBarConfig != null && horizontalBarConfig != null) {
            int horizontalBarZOrder = horizontalBarConfig.getZOrder();
            int verticalBarZOrder = verticalBarConfig.getZOrder();

            if (horizontalBarZOrder == verticalBarZOrder) {
                throw new RuntimeException(
                        BAR_TITLE_MAP.get(horizontalSide) + " " + BAR_TITLE_MAP.get(verticalSide)
                                + " have the same Z-Order, and so their placing order cannot be "
                                + "determined. Determine which bar should be placed on top of the "
                                + "other bar and change the Z-order in config.xml accordingly."
                );
            }
        }
    }

    private static boolean isHorizontalBar(@SystemBarSide int side) {
        return side == TOP || side == BOTTOM;
    }

    private static boolean isVerticalBar(@SystemBarSide int side) {
        return side == LEFT || side == RIGHT;
    }

    private static final class SystemBarConfig {
        private final int mSide;
        private final int mBarType;
        private final int mGirth;
        private final int mZOrder;

        private int[] mPaddings = new int[]{0, 0, 0, 0};

        private SystemBarConfig(@SystemBarSide int side, int barType, int girth, int zOrder) {
            mSide = side;
            mBarType = barType;
            mGirth = girth;
            mZOrder = zOrder;
        }

        private int getSide() {
            return mSide;
        }

        private int getBarType() {
            return mBarType;
        }

        private int getGirth() {
            return mGirth;
        }

        private int getZOrder() {
            return mZOrder;
        }

        private int[] getPaddings() {
            return mPaddings;
        }

        private WindowManager.LayoutParams getLayoutParams() {
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    isHorizontalBar(mSide) ? ViewGroup.LayoutParams.MATCH_PARENT : mGirth,
                    isHorizontalBar(mSide) ? mGirth : ViewGroup.LayoutParams.MATCH_PARENT,
                    mapZOrderToBarType(mZOrder),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle(BAR_TITLE_MAP.get(mSide));
            lp.providesInsetsTypes = new int[]{BAR_TYPE_MAP[mBarType], BAR_GESTURE_MAP.get(mSide)};
            lp.setFitInsetsTypes(0);
            lp.windowAnimations = 0;
            lp.gravity = BAR_GRAVITY_MAP.get(mSide);
            return lp;
        }

        private int mapZOrderToBarType(int zOrder) {
            return zOrder >= HUN_ZORDER ? WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL
                    : WindowManager.LayoutParams.TYPE_STATUS_BAR_ADDITIONAL;
        }

        private void setPaddingBySide(@SystemBarSide int side, int padding) {
            mPaddings[side] = padding;
        }
    }

    private static final class SystemBarConfigBuilder {
        private int mSide;
        private int mBarType;
        private int mGirth;
        private int mZOrder;

        private SystemBarConfigBuilder setSide(@SystemBarSide int side) {
            mSide = side;
            return this;
        }

        private SystemBarConfigBuilder setBarType(int type) {
            mBarType = type;
            return this;
        }

        private SystemBarConfigBuilder setGirth(int girth) {
            mGirth = girth;
            return this;
        }

        private SystemBarConfigBuilder setZOrder(int zOrder) {
            mZOrder = zOrder;
            return this;
        }

        private SystemBarConfig build() {
            return new SystemBarConfig(mSide, mBarType, mGirth, mZOrder);
        }
    }
}
