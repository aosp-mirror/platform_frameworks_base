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

package com.android.wm.shell.apppairs;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
import static android.view.WindowManager.LayoutParams.FLAG_SLIPPERY;
import static android.view.WindowManager.LayoutParams.FLAG_SPLIT_TOUCH;
import static android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Binder;
import android.os.IBinder;
import android.view.Display;
import android.view.IWindow;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.view.WindowlessWindowManager;

import com.android.wm.shell.R;

/**
 * Records and handles layout of a pair of apps.
 */
final class AppPairLayout {
    private static final String DIVIDER_WINDOW_TITLE = "AppPairDivider";
    private final Display mDisplay;
    private final int mDividerWindowWidth;
    private final int mDividerWindowInsets;
    private final AppPairWindowManager mAppPairWindowManager;

    private Context mContext;
    private Rect mRootBounds;
    private DIVIDE_POLICY mDividePolicy;

    private SurfaceControlViewHost mViewHost;
    private SurfaceControl mDividerLeash;

    AppPairLayout(
            Context context,
            Display display,
            Configuration configuration,
            SurfaceControl rootLeash) {
        mContext = context.createConfigurationContext(configuration);
        mDisplay = display;
        mRootBounds = configuration.windowConfiguration.getBounds();
        mDividerWindowWidth = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        mDividerWindowInsets = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_insets);

        mAppPairWindowManager = new AppPairWindowManager(configuration, rootLeash);
        mDividePolicy = DIVIDE_POLICY.MIDDLE;
        mDividePolicy.update(mRootBounds, mDividerWindowWidth, mDividerWindowInsets);
    }

    boolean updateConfiguration(Configuration configuration) {
        mAppPairWindowManager.setConfiguration(configuration);
        final Rect rootBounds = configuration.windowConfiguration.getBounds();
        if (isIdenticalBounds(mRootBounds, rootBounds)) {
            return false;
        }

        mContext = mContext.createConfigurationContext(configuration);
        mRootBounds = rootBounds;
        mDividePolicy.update(mRootBounds, mDividerWindowWidth, mDividerWindowInsets);
        release();
        init();
        return true;
    }

    Rect getBounds1() {
        return mDividePolicy.mBounds1;
    }

    Rect getBounds2() {
        return mDividePolicy.mBounds2;
    }

    Rect getDividerBounds() {
        return mDividePolicy.mDividerBounds;
    }

    SurfaceControl getDividerLeash() {
        return mDividerLeash;
    }

    void release() {
        if (mViewHost == null) {
            return;
        }
        mViewHost.release();
        mDividerLeash = null;
        mViewHost = null;
    }

    void init() {
        if (mViewHost == null) {
            mViewHost = new SurfaceControlViewHost(mContext, mDisplay, mAppPairWindowManager);
        }

        final DividerView dividerView = (DividerView) LayoutInflater.from(mContext)
                .inflate(R.layout.split_divider, null);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                mDividePolicy.mDividerBounds.width(),
                mDividePolicy.mDividerBounds.height(),
                TYPE_DOCK_DIVIDER,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH
                        | FLAG_SPLIT_TOUCH | FLAG_SLIPPERY,
                PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.setTitle(DIVIDER_WINDOW_TITLE);
        lp.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION;

        mViewHost.setView(dividerView, lp);
        mDividerLeash = mAppPairWindowManager.getSurfaceControl(mViewHost.getWindowToken());
    }

    private static boolean isIdenticalBounds(Rect bounds1, Rect bounds2) {
        return bounds1.left == bounds2.left && bounds1.top == bounds2.top
                && bounds1.right == bounds2.right && bounds1.bottom == bounds2.bottom;
    }

    /**
     * Indicates the policy of placing divider bar and corresponding split-screens.
     */
    // TODO(172704238): add more divide policy and provide snap to resize feature for divider bar.
    enum DIVIDE_POLICY {
        MIDDLE;

        void update(Rect rootBounds, int dividerWindowWidth, int dividerWindowInsets) {
            final int dividerOffset = dividerWindowWidth / 2;
            final int boundsOffset = dividerOffset - dividerWindowInsets;

            mDividerBounds = new Rect(rootBounds);
            mBounds1 = new Rect(rootBounds);
            mBounds2 = new Rect(rootBounds);

            switch (this) {
                case MIDDLE:
                default:
                    if (isLandscape(rootBounds)) {
                        mDividerBounds.left = rootBounds.width() / 2 - dividerOffset;
                        mDividerBounds.right = rootBounds.width() / 2 + dividerOffset;
                        mBounds1.left = rootBounds.width() / 2 + boundsOffset;
                        mBounds2.right = rootBounds.width() / 2 - boundsOffset;
                    } else {
                        mDividerBounds.top = rootBounds.height() / 2 - dividerOffset;
                        mDividerBounds.bottom = rootBounds.height() / 2 + dividerOffset;
                        mBounds1.bottom = rootBounds.height() / 2 - boundsOffset;
                        mBounds2.top = rootBounds.height() / 2 + boundsOffset;
                    }
            }
        }

        private boolean isLandscape(Rect bounds) {
            return bounds.width() > bounds.height();
        }

        Rect mDividerBounds;
        Rect mBounds1;
        Rect mBounds2;
    }

    /**
     * WindowManger for app pair. Holds view hierarchy for the root task.
     */
    private static final class AppPairWindowManager extends WindowlessWindowManager {
        AppPairWindowManager(Configuration config, SurfaceControl rootSurface) {
            super(config, rootSurface, null /* hostInputToken */);
        }

        @Override
        public void setTouchRegion(IBinder window, Region region) {
            super.setTouchRegion(window, region);
        }

        @Override
        public SurfaceControl getSurfaceControl(IWindow window) {
            return super.getSurfaceControl(window);
        }

        @Override
        public void setConfiguration(Configuration configuration) {
            super.setConfiguration(configuration);
        }
    }
}
