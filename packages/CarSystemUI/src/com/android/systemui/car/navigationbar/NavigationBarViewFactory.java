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

import android.content.Context;
import android.util.ArrayMap;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;

import com.android.car.ui.FocusParkingView;
import com.android.systemui.R;

import javax.inject.Inject;
import javax.inject.Singleton;

/** A factory that creates and caches views for navigation bars. */
@Singleton
public class NavigationBarViewFactory {

    private static final String TAG = NavigationBarViewFactory.class.getSimpleName();
    private static final ArrayMap<Type, Integer> sLayoutMap = setupLayoutMapping();

    private static ArrayMap<Type, Integer> setupLayoutMapping() {
        ArrayMap<Type, Integer> map = new ArrayMap<>();
        map.put(Type.TOP, R.layout.car_top_navigation_bar);
        map.put(Type.TOP_UNPROVISIONED, R.layout.car_top_navigation_bar_unprovisioned);
        map.put(Type.BOTTOM, R.layout.car_navigation_bar);
        map.put(Type.BOTTOM_UNPROVISIONED, R.layout.car_navigation_bar_unprovisioned);
        map.put(Type.LEFT, R.layout.car_left_navigation_bar);
        map.put(Type.LEFT_UNPROVISIONED, R.layout.car_left_navigation_bar_unprovisioned);
        map.put(Type.RIGHT, R.layout.car_right_navigation_bar);
        map.put(Type.RIGHT_UNPROVISIONED, R.layout.car_right_navigation_bar_unprovisioned);
        return map;
    }

    private final Context mContext;
    private final ArrayMap<Type, CarNavigationBarView> mCachedViewMap = new ArrayMap<>(
            Type.values().length);
    private final ArrayMap<Type, ViewGroup> mCachedContainerMap = new ArrayMap<>();

    /** Type of navigation bar to be created. */
    private enum Type {
        TOP,
        TOP_UNPROVISIONED,
        BOTTOM,
        BOTTOM_UNPROVISIONED,
        LEFT,
        LEFT_UNPROVISIONED,
        RIGHT,
        RIGHT_UNPROVISIONED
    }

    @Inject
    public NavigationBarViewFactory(Context context) {
        mContext = context;
    }

    /** Gets the top window. */
    public ViewGroup getTopWindow() {
        return getWindowCached(Type.TOP);
    }

    /** Gets the bottom window. */
    public ViewGroup getBottomWindow() {
        return getWindowCached(Type.BOTTOM);
    }

    /** Gets the left window. */
    public ViewGroup getLeftWindow() {
        return getWindowCached(Type.LEFT);
    }

    /** Gets the right window. */
    public ViewGroup getRightWindow() {
        return getWindowCached(Type.RIGHT);
    }

    /** Gets the top bar. */
    public CarNavigationBarView getTopBar(boolean isSetUp) {
        return getBar(isSetUp, Type.TOP, Type.TOP_UNPROVISIONED);
    }

    /** Gets the bottom bar. */
    public CarNavigationBarView getBottomBar(boolean isSetUp) {
        return getBar(isSetUp, Type.BOTTOM, Type.BOTTOM_UNPROVISIONED);
    }

    /** Gets the left bar. */
    public CarNavigationBarView getLeftBar(boolean isSetUp) {
        return getBar(isSetUp, Type.LEFT, Type.LEFT_UNPROVISIONED);
    }

    /** Gets the right bar. */
    public CarNavigationBarView getRightBar(boolean isSetUp) {
        return getBar(isSetUp, Type.RIGHT, Type.RIGHT_UNPROVISIONED);
    }

    private ViewGroup getWindowCached(Type type) {
        if (mCachedContainerMap.containsKey(type)) {
            return mCachedContainerMap.get(type);
        }

        ViewGroup window = (ViewGroup) View.inflate(mContext,
                R.layout.navigation_bar_window, /* root= */ null);
        mCachedContainerMap.put(type, window);
        return mCachedContainerMap.get(type);
    }

    private CarNavigationBarView getBar(boolean isSetUp, Type provisioned, Type unprovisioned) {
        CarNavigationBarView view;
        if (isSetUp) {
            view = getBarCached(provisioned, sLayoutMap.get(provisioned));
        } else {
            view = getBarCached(unprovisioned, sLayoutMap.get(unprovisioned));
        }

        if (view == null) {
            String name = isSetUp ? provisioned.name() : unprovisioned.name();
            Log.e(TAG, "CarStatusBar failed inflate for " + name);
            throw new RuntimeException(
                    "Unable to build " + name + " nav bar due to missing layout");
        }
        return view;
    }

    private CarNavigationBarView getBarCached(Type type, @LayoutRes int barLayout) {
        if (mCachedViewMap.containsKey(type)) {
            return mCachedViewMap.get(type);
        }

        CarNavigationBarView view = (CarNavigationBarView) View.inflate(mContext, barLayout,
                /* root= */ null);

        // Include a FocusParkingView at the beginning. The rotary controller "parks" the focus here
        // when the user navigates to another window. This is also used to prevent wrap-around.
        view.addView(new FocusParkingView(mContext), 0);

        mCachedViewMap.put(type, view);
        return mCachedViewMap.get(type);
    }
}
