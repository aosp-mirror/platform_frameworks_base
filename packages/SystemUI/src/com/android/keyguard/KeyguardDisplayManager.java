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

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.MediaRouter;
import android.media.MediaRouter.RouteInfo;
import android.os.Bundle;
import android.os.Trace;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.keyguard.dagger.KeyguardStatusViewComponent;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dagger.qualifiers.UiBackground;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationBarView;
import com.android.systemui.settings.DisplayTracker;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import dagger.Lazy;

public class KeyguardDisplayManager {
    protected static final String TAG = "KeyguardDisplayManager";
    private static final boolean DEBUG = KeyguardConstants.DEBUG;

    private MediaRouter mMediaRouter = null;
    private final DisplayManager mDisplayService;
    private final DisplayTracker mDisplayTracker;
    private final Lazy<NavigationBarController> mNavigationBarControllerLazy;
    private final KeyguardStatusViewComponent.Factory mKeyguardStatusViewComponentFactory;
    private final Context mContext;

    private boolean mShowing;
    private final DisplayInfo mTmpDisplayInfo = new DisplayInfo();

    private final SparseArray<Presentation> mPresentations = new SparseArray<>();

    private final DisplayTracker.Callback mDisplayCallback =
            new DisplayTracker.Callback() {
                @Override
                public void onDisplayAdded(int displayId) {
                    Trace.beginSection(
                            "KeyguardDisplayManager#onDisplayAdded(displayId=" + displayId + ")");
                    final Display display = mDisplayService.getDisplay(displayId);
                    if (mShowing) {
                        updateNavigationBarVisibility(displayId, false /* navBarVisible */);
                        showPresentation(display);
                    }
                    Trace.endSection();
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    Trace.beginSection(
                            "KeyguardDisplayManager#onDisplayRemoved(displayId=" + displayId + ")");
                    hidePresentation(displayId);
                    Trace.endSection();
                }
            };

    @Inject
    public KeyguardDisplayManager(Context context,
            Lazy<NavigationBarController> navigationBarControllerLazy,
            KeyguardStatusViewComponent.Factory keyguardStatusViewComponentFactory,
            DisplayTracker displayTracker,
            @Main Executor mainExecutor,
            @UiBackground Executor uiBgExecutor) {
        mContext = context;
        mNavigationBarControllerLazy = navigationBarControllerLazy;
        mKeyguardStatusViewComponentFactory = keyguardStatusViewComponentFactory;
        uiBgExecutor.execute(() -> mMediaRouter = mContext.getSystemService(MediaRouter.class));
        mDisplayService = mContext.getSystemService(DisplayManager.class);
        mDisplayTracker = displayTracker;
        mDisplayTracker.addDisplayChangeCallback(mDisplayCallback, mainExecutor);
    }

    private boolean isKeyguardShowable(Display display) {
        if (display == null) {
            if (DEBUG) Log.i(TAG, "Cannot show Keyguard on null display");
            return false;
        }
        if (display.getDisplayId() == mDisplayTracker.getDefaultDisplayId()) {
            if (DEBUG) Log.i(TAG, "Do not show KeyguardPresentation on the default display");
            return false;
        }
        display.getDisplayInfo(mTmpDisplayInfo);
        if ((mTmpDisplayInfo.flags & Display.FLAG_PRIVATE) != 0) {
            if (DEBUG) Log.i(TAG, "Do not show KeyguardPresentation on a private display");
            return false;
        }
        if ((mTmpDisplayInfo.flags & Display.FLAG_ALWAYS_UNLOCKED) != 0) {
            if (DEBUG) {
                Log.i(TAG, "Do not show KeyguardPresentation on an unlocked display");
            }
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
            final Presentation newPresentation = createPresentation(display);
            newPresentation.setOnDismissListener(dialog -> {
                if (newPresentation.equals(mPresentations.get(displayId))) {
                    mPresentations.remove(displayId);
                }
            });
            presentation = newPresentation;
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

    KeyguardPresentation createPresentation(Display display) {
        return new KeyguardPresentation(mContext, display, mKeyguardStatusViewComponentFactory);
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
            if (mMediaRouter != null) {
                mMediaRouter.addCallback(MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                        mMediaRouterCallback, MediaRouter.CALLBACK_FLAG_PASSIVE_DISCOVERY);
            } else {
                Log.w(TAG, "MediaRouter not yet initialized");
            }
            updateDisplays(true /* showing */);
        }
        mShowing = true;
    }

    public void hide() {
        if (mShowing) {
            if (DEBUG) Log.v(TAG, "hide");
            if (mMediaRouter != null) {
                mMediaRouter.removeCallback(mMediaRouterCallback);
            }
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
            final Display[] displays = mDisplayTracker.getAllDisplays();
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
        if (displayId == mDisplayTracker.getDefaultDisplayId()) return;

        NavigationBarView navBarView = mNavigationBarControllerLazy.get()
                .getNavigationBarView(displayId);
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
        private final KeyguardStatusViewComponent.Factory mKeyguardStatusViewComponentFactory;
        private KeyguardClockSwitchController mKeyguardClockSwitchController;
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
                KeyguardStatusViewComponent.Factory keyguardStatusViewComponentFactory) {
            super(context, display, R.style.Theme_SystemUI_KeyguardPresentation,
                    WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            mKeyguardStatusViewComponentFactory = keyguardStatusViewComponentFactory;
            setCancelable(false);
        }

        @Override
        public void cancel() {
            // Do not allow anything to cancel KeyguardPresentation except KeyguardDisplayManager.
        }

        @Override
        public void onDetachedFromWindow() {
            mClock.removeCallbacks(mMoveTextRunnable);
        }

        @Override
        public void onDisplayChanged() {
            updateBounds();
            getWindow().getDecorView().requestLayout();
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            updateBounds();

            setContentView(LayoutInflater.from(getContext())
                    .inflate(R.layout.keyguard_presentation, null));

            // Logic to make the lock screen fullscreen
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
            getWindow().getAttributes().setFitInsetsTypes(0 /* types */);
            getWindow().setNavigationBarContrastEnforced(false);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);

            mClock = findViewById(R.id.clock);

            // Avoid screen burn in
            mClock.post(mMoveTextRunnable);

            mKeyguardClockSwitchController = mKeyguardStatusViewComponentFactory
                    .build(findViewById(R.id.clock))
                    .getKeyguardClockSwitchController();

            mKeyguardClockSwitchController.setOnlyClock(true);
            mKeyguardClockSwitchController.init();
        }

        private void updateBounds() {
            final Rect bounds = getWindow().getWindowManager().getMaximumWindowMetrics()
                    .getBounds();
            mUsableWidth = VIDEO_SAFE_REGION * bounds.width() / 100;
            mUsableHeight = VIDEO_SAFE_REGION * bounds.height() / 100;
            mMarginLeft = (100 - VIDEO_SAFE_REGION) * bounds.width() / 200;
            mMarginTop = (100 - VIDEO_SAFE_REGION) * bounds.height() / 200;
        }
    }
}
