/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.keyguard;

import static android.view.Display.DEFAULT_DISPLAY;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.os.Bundle;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.NavigationBarController;
import com.android.systemui.statusbar.phone.NavigationBarView;
import com.android.systemui.util.InjectionInflationController;

public class KeyguardDisplayManager {
    protected static final String TAG = "KeyguardDisplayManager";
    private static boolean DEBUG = KeyguardConstants.DEBUG;

    private final MediaRouter mMediaRouter;
    private final DisplayManager mDisplayService;
    private final InjectionInflationController mInjectableInflater;
    private final Context mContext;

    private boolean mShowing;
    private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();

    private final SparseArray<Presentation> mPresentations = new SparseArray<>();

    private final NavigationBarController mNavBarController =
            Dependency.get(NavigationBarController.class);

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {

        @Override
        public void onDisplayAdded(int displayId) {
            final Display display = mDisplayService.getDisplay(displayId);
            if (mShowing) {
                updateNavigationBarVisibility(displayId, false /* navBarVisible */);
                showPresentation(display);
            }
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId == DEFAULT_DISPLAY) return;
            final Display display = mDisplayService.getDisplay(displayId);
            if (display != null && mShowing) {
                final Presentation presentation = mPresentations.get(displayId);
                if (presentation != null && !presentation.getDisplay().equals(display)) {
                    hidePresentation(displayId);
                    showPresentation(display);
                }
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            hidePresentation(displayId);
        }
    };

    public KeyguardDisplayManager(Context context,
            InjectionInflationController injectableInflater) {
        mContext = context;
        mInjectableInflater = injectableInflater;
        mMediaRouter = mContext.getSystemService(MediaRouter.class);
        mDisplayService = mContext.getSystemService(DisplayManager.class);
        mDisplayService.registerDisplayListener(mDisplayListener, null /* handler */);
    }

    private boolean isKeyguardShowable(Display display) {
        if (display == null) {
            if (DEBUG) Log.i(TAG, "Cannot show Keyguard on null display");
            return false;
        }
        if (display.getDisplayId() == DEFAULT_DISPLAY) {
            if (DEBUG) Log.i(TAG, "Do not show KeyguardPresentation on the default display");
            return false;
        }
        display.getDisplayInfo(mTmpDisplayInfo);
        if ((mTmpDisplayInfo.flags & Display.FLAG_PRIVATE) != 0) {
            if (DEBUG) Log.i(TAG, "Do not show KeyguardPresentation on a private display");
            return false;
        }
        return true;
    }
    /**
     * @param display The display to show the presentation on.
     * @return {@code true} if a presentation was added.
     *         {@code false} if the presentation cannot be added on that display or the presentation
     *         was already there.
     */
    private boolean showPresentation(Display display) {
        if (!isKeyguardShowable(display)) return false;
        if (DEBUG) Log.i(TAG, "Keyguard enabled on display: " + display);
        final int displayId = display.getDisplayId();
        Presentation presentation = mPresentations.get(displayId);
        if (presentation == null) {
            presentation = new KeyguardPresentation(mContext, display, mInjectableInflater);
            presentation.setOnDismissListener(dialog -> {
                if (null != mPresentations.get(displayId)) {
                    mPresentations.remove(displayId);
                }
            });
            try {
                presentation.show();
            } catch (WindowManager.InvalidDisplayException ex) {
                Log.w(TAG, "Invalid display:", ex);
                presentation = null;
            }
            if (presentation != null) {
                mPresentations.append(displayId, presentation);
                return true;
            }
        }
        return false;
    }

    /**
     * @param displayId The id of the display to hide the presentation off.
     */
    private void hidePresentation(int displayId) {
        final Presentation presentation = mPresentations.get(displayId);
        if (presentation != null) {
            presentation.dismiss();
            mPresentations.remove(displayId);
        }
    }

    public void show() {
        if (!mShowing) {
            if (DEBUG) Log.v(TAG, "show");
            mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                    mMediaRouterCallback, MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
            updateDisplays(true /* showing */);
        }
        mShowing = true;
    }

    public void hide() {
        if (mShowing) {
            if (DEBUG) Log.v(TAG, "hide");
            mMediaRouter.removeCallback(mMediaRouterCallback);
            updateDisplays(false /* showing */);
        }
        mShowing = false;
    }

    private final MediaRouter.SimpleCallback mMediaRouterCallback =
            new MediaRouter.SimpleCallback() {
        @Override
        public void onRouteSelected(MediaRouter router, int type, RouteInfo info) {
            if (DEBUG) Log.d(TAG, "onRouteSelected: type=" + type + ", info=" + info);
            updateDisplays(mShowing);
        }

        @Override
        public void onRouteUnselected(MediaRouter router, int type, RouteInfo info) {
            if (DEBUG) Log.d(TAG, "onRouteUnselected: type=" + type + ", info=" + info);
            updateDisplays(mShowing);
        }

        @Override
        public void onRoutePresentationDisplayChanged(MediaRouter router, RouteInfo info) {
            if (DEBUG) Log.d(TAG, "onRoutePresentationDisplayChanged: info=" + info);
            updateDisplays(mShowing);
        }
    };

    protected boolean updateDisplays(boolean showing) {
        boolean changed = false;
        if (showing) {
            final Display[] displays = mDisplayService.getDisplays();
            for (Display display : displays) {
                int displayId = display.getDisplayId();
                updateNavigationBarVisibility(displayId, false /* navBarVisible */);
                changed |= showPresentation(display);
            }
        } else {
            changed = mPresentations.size() > 0;
            for (int i = mPresentations.size() - 1; i >= 0; i--) {
                int displayId = mPresentations.keyAt(i);
                updateNavigationBarVisibility(displayId, true /* navBarVisible */);
                mPresentations.valueAt(i).dismiss();
            }
            mPresentations.clear();
        }
        return changed;
    }

    // TODO(b/127878649): this logic is from
    //  {@link StatusBarKeyguardViewManager#updateNavigationBarVisibility}. Try to revisit a long
    //  term solution in R.
    private void updateNavigationBarVisibility(int displayId, boolean navBarVisible) {
        // Leave this task to {@link StatusBarKeyguardViewManager}
        if (displayId == DEFAULT_DISPLAY) return;

        NavigationBarView navBarView = mNavBarController.getNavigationBarView(displayId);
        // We may not have nav bar on a display.
        if (navBarView == null) return;

        if (navBarVisible) {
            navBarView.getRootView().setVisibility(View.VISIBLE);
        } else {
            navBarView.getRootView().setVisibility(View.GONE);
        }

    }

    @VisibleForTesting
    static final class KeyguardPresentation extends Presentation {
        private static final int VIDEO_SAFE_REGION = 80; // Percentage of display width & height
        private static final int MOVE_CLOCK_TIMEOUT = 10000; // 10s
        private final InjectionInflationController mInjectableInflater;
        private View mClock;
        private int mUsableWidth;
        private int mUsableHeight;
        private int mMarginTop;
        private int mMarginLeft;
        Runnable mMoveTextRunnable = new Runnable() {
            @Override
            public void run() {
                int x = mMarginLeft + (int) (Math.random() * (mUsableWidth - mClock.getWidth()));
                int y = mMarginTop + (int) (Math.random() * (mUsableHeight - mClock.getHeight()));
                mClock.setTranslationX(x);
                mClock.setTranslationY(y);
                mClock.postDelayed(mMoveTextRunnable, MOVE_CLOCK_TIMEOUT);
            }
        };

        KeyguardPresentation(Context context, Display display,
                InjectionInflationController injectionInflater) {
            super(context, display, R.style.Theme_SystemUI_KeyguardPresentation);
            mInjectableInflater = injectionInflater;
            getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            setCancelable(false);
        }

        @Override
        public void onDetachedFromWindow() {
            mClock.removeCallbacks(mMoveTextRunnable);
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            Point p = new Point();
            getDisplay().getSize(p);
            mUsableWidth = VIDEO_SAFE_REGION * p.x/100;
            mUsableHeight = VIDEO_SAFE_REGION * p.y/100;
            mMarginLeft = (100 - VIDEO_SAFE_REGION) * p.x / 200;
            mMarginTop = (100 - VIDEO_SAFE_REGION) * p.y / 200;

            LayoutInflater inflater = mInjectableInflater.injectable(
                    LayoutInflater.from(getContext()));
            setContentView(inflater.inflate(R.layout.keyguard_presentation, null));

            // Logic to make the lock screen fullscreen
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);

            mClock = findViewById(R.id.clock);

            // Avoid screen burn in
            mClock.post(mMoveTextRunnable);
        }
    }
}
