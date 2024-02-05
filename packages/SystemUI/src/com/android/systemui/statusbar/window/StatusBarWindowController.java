/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.window;

import static android.view.WindowInsets.Type.mandatorySystemGestures;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsets.Type.tappableElement;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;

import static com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_NONE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.InsetsFrameProvider;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DelegateLaunchAnimatorController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider;
import com.android.systemui.unfold.UnfoldTransitionProgressProvider;
import com.android.systemui.unfold.util.JankMonitorTransitionProgressListener;

import java.util.Optional;

import javax.inject.Inject;

/**
 * Encapsulates all logic for the status bar window state management.
 */
@SysUISingleton
public class StatusBarWindowController {
    private static final String TAG = "StatusBarWindowController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final IWindowManager mIWindowManager;
    private final StatusBarContentInsetsProvider mContentInsetsProvider;
    private int mBarHeight = -1;
    private final State mCurrentState = new State();
    private boolean mIsAttached;

    private final ViewGroup mStatusBarWindowView;
    private final FragmentService mFragmentService;
    // The container in which we should run launch animations started from the status bar and
    //   expanding into the opening window.
    private final ViewGroup mLaunchAnimationContainer;
    private WindowManager.LayoutParams mLp;
    private final WindowManager.LayoutParams mLpChanged;
    private final Binder mInsetsSourceOwner = new Binder();

    @Inject
    public StatusBarWindowController(
            Context context,
            @StatusBarWindowModule.InternalWindowView StatusBarWindowView statusBarWindowView,
            WindowManager windowManager,
            IWindowManager iWindowManager,
            StatusBarContentInsetsProvider contentInsetsProvider,
            FragmentService fragmentService,
            @Main Resources resources,
            Optional<UnfoldTransitionProgressProvider> unfoldTransitionProgressProvider) {
        mContext = context;
        mWindowManager = windowManager;
        mIWindowManager = iWindowManager;
        mContentInsetsProvider = contentInsetsProvider;
        mStatusBarWindowView = statusBarWindowView;
        mFragmentService = fragmentService;
        mLaunchAnimationContainer = mStatusBarWindowView.findViewById(
                R.id.status_bar_launch_animation_container);
        mLpChanged = new WindowManager.LayoutParams();

        if (mBarHeight < 0) {
            mBarHeight = SystemBarUtils.getStatusBarHeight(mContext);
        }
        unfoldTransitionProgressProvider.ifPresent(
                unfoldProgressProvider -> unfoldProgressProvider.addCallback(
                        new JankMonitorTransitionProgressListener(
                                /* attachedViewProvider=*/ () -> mStatusBarWindowView)));
    }

    public int getStatusBarHeight() {
        return mBarHeight;
    }

    /**
     * Rereads the status bar height and reapplys the current state if the height
     * is different.
     */
    public void refreshStatusBarHeight() {
        Trace.beginSection("StatusBarWindowController#refreshStatusBarHeight");
        try {
            int heightFromConfig = SystemBarUtils.getStatusBarHeight(mContext);

            if (mBarHeight != heightFromConfig) {
                mBarHeight = heightFromConfig;
                apply(mCurrentState);
            }

            if (DEBUG) Log.v(TAG, "defineSlots");
        } finally {
            Trace.endSection();
        }
    }

    /**
     * Adds the status bar view to the window manager.
     */
    public void attach() {
        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        Trace.beginSection("StatusBarWindowController.getBarLayoutParams");
        mLp = getBarLayoutParams(mContext.getDisplay().getRotation());
        Trace.endSection();

        mWindowManager.addView(mStatusBarWindowView, mLp);
        mLpChanged.copyFrom(mLp);

        mContentInsetsProvider.addCallback(this::calculateStatusBarLocationsForAllRotations);
        calculateStatusBarLocationsForAllRotations();
        mIsAttached = true;
        apply(mCurrentState);
    }

    /** Adds the given view to the status bar window view. */
    public void addViewToWindow(View view, ViewGroup.LayoutParams layoutParams) {
        mStatusBarWindowView.addView(view, layoutParams);
    }

    /** Returns the status bar window's background view. */
    public View getBackgroundView() {
        return mStatusBarWindowView.findViewById(R.id.status_bar_container);
    }

    /** Returns a fragment host manager for the status bar window view. */
    public FragmentHostManager getFragmentHostManager() {
        return mFragmentService.getFragmentHostManager(mStatusBarWindowView);
    }

    /**
     * Provides an updated animation controller if we're animating a view in the status bar.
     *
     * This is needed because we have to make sure that the status bar window matches the full
     * screen during the animation and that we are expanding the view below the other status bar
     * text.
     *
     * @param rootView the root view of the animation
     * @param animationController the default animation controller to use
     * @return If the animation is on a view in the status bar, returns an Optional containing an
     *   updated animation controller that handles status-bar-related animation details. Returns an
     *   empty optional if the animation is *not* on a view in the status bar.
     */
    public Optional<ActivityLaunchAnimator.Controller> wrapAnimationControllerIfInStatusBar(
            View rootView, ActivityLaunchAnimator.Controller animationController) {
        if (rootView != mStatusBarWindowView) {
            return Optional.empty();
        }

        animationController.setTransitionContainer(mLaunchAnimationContainer);
        return Optional.of(new DelegateLaunchAnimatorController(animationController) {
            @Override
            public void onTransitionAnimationStart(boolean isExpandingFullyAbove) {
                getDelegate().onTransitionAnimationStart(isExpandingFullyAbove);
                setLaunchAnimationRunning(true);
            }

            @Override
            public void onTransitionAnimationEnd(boolean isExpandingFullyAbove) {
                getDelegate().onTransitionAnimationEnd(isExpandingFullyAbove);
                setLaunchAnimationRunning(false);
            }
        });
    }

    private WindowManager.LayoutParams getBarLayoutParams(int rotation) {
        WindowManager.LayoutParams lp = getBarLayoutParamsForRotation(rotation);
        lp.paramsForRotation = new WindowManager.LayoutParams[4];
        for (int rot = Surface.ROTATION_0; rot <= Surface.ROTATION_270; rot++) {
            lp.paramsForRotation[rot] = getBarLayoutParamsForRotation(rot);
        }
        return lp;
    }

    private WindowManager.LayoutParams getBarLayoutParamsForRotation(int rotation) {
        int height = SystemBarUtils.getStatusBarHeightForRotation(mContext, rotation);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        lp.token = new Binder();
        lp.gravity = Gravity.TOP;
        lp.setFitInsetsTypes(0 /* types */);
        lp.setTitle("StatusBar");
        lp.packageName = mContext.getPackageName();
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        final InsetsFrameProvider gestureInsetsProvider =
                new InsetsFrameProvider(mInsetsSourceOwner, 0, mandatorySystemGestures());
        final int safeTouchRegionHeight = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.display_cutout_touchable_region_size);
        if (safeTouchRegionHeight > 0) {
            gestureInsetsProvider.setMinimalInsetsSizeInDisplayCutoutSafe(
                    Insets.of(0, safeTouchRegionHeight, 0, 0));
        }
        lp.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(mInsetsSourceOwner, 0, statusBars())
                        .setInsetsSize(getInsets(height)),
                new InsetsFrameProvider(mInsetsSourceOwner, 0, tappableElement())
                        .setInsetsSize(getInsets(height)),
                gestureInsetsProvider
        };
        return lp;

    }

    private void calculateStatusBarLocationsForAllRotations() {
        Rect[] bounds = new Rect[4];
        final DisplayCutout displayCutout = mContext.getDisplay().getCutout();
        bounds[0] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_NONE, displayCutout);
        bounds[1] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_LANDSCAPE, displayCutout);
        bounds[2] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_UPSIDE_DOWN, displayCutout);
        bounds[3] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_SEASCAPE, displayCutout);

        try {
            mIWindowManager.updateStaticPrivacyIndicatorBounds(mContext.getDisplayId(), bounds);
        } catch (RemoteException e) {
             //Swallow
        }
    }

    /** Set force status bar visible. */
    public void setForceStatusBarVisible(boolean forceStatusBarVisible) {
        mCurrentState.mForceStatusBarVisible = forceStatusBarVisible;
        apply(mCurrentState);
    }

    /**
     * Sets whether an ongoing process requires the status bar to be forced visible.
     *
     * This method is separate from {@link this#setForceStatusBarVisible} because the ongoing
     * process **takes priority**. For example, if {@link this#setForceStatusBarVisible} is set to
     * false but this method is set to true, then the status bar **will** be visible.
     *
     * TODO(b/195839150): We should likely merge this method and
     * {@link this#setForceStatusBarVisible} together and use some sort of ranking system instead.
     */
    public void setOngoingProcessRequiresStatusBarVisible(boolean visible) {
        mCurrentState.mOngoingProcessRequiresStatusBarVisible = visible;
        apply(mCurrentState);
    }

    /**
     * Set whether a launch animation is currently running. If true, this will ensure that the
     * window matches its parent height so that the animation is not clipped by the normal status
     * bar height.
     */
    private void setLaunchAnimationRunning(boolean isLaunchAnimationRunning) {
        if (isLaunchAnimationRunning == mCurrentState.mIsLaunchAnimationRunning) {
            return;
        }

        mCurrentState.mIsLaunchAnimationRunning = isLaunchAnimationRunning;
        apply(mCurrentState);
    }

    private void applyHeight(State state) {
        mLpChanged.height =
                state.mIsLaunchAnimationRunning ? ViewGroup.LayoutParams.MATCH_PARENT : mBarHeight;
        for (int rot = Surface.ROTATION_0; rot <= Surface.ROTATION_270; rot++) {
            int height = SystemBarUtils.getStatusBarHeightForRotation(mContext, rot);
            mLpChanged.paramsForRotation[rot].height =
                    state.mIsLaunchAnimationRunning ? ViewGroup.LayoutParams.MATCH_PARENT : height;
            // The status bar height could change at runtime if one display has a cutout while
            // another doesn't (like some foldables). It could also change when using debug cutouts.
            // So, we need to re-fetch the height and re-apply it to the insets each time to avoid
            // bugs like b/290300359.
            InsetsFrameProvider[] providers = mLpChanged.paramsForRotation[rot].providedInsets;
            if (providers != null) {
                for (InsetsFrameProvider provider : providers) {
                    provider.setInsetsSize(getInsets(height));
                }
            }
        }
    }

    /**
     * Get the insets that should be applied to the status bar window given the current status bar
     * height.
     *
     * The status bar window height can sometimes be full-screen (see {@link #applyHeight(State)}.
     * However, the status bar *insets* should *not* be full-screen, because this would prevent apps
     * from drawing any content and can cause animations to be cancelled (see b/283958440). Instead,
     * the status bar insets should always be equal to the space occupied by the actual status bar
     * content -- setting the insets correctly will prevent window manager from unnecessarily
     * re-drawing this window and other windows. This method provides the correct insets.
     */
    private Insets getInsets(int height) {
        return Insets.of(0, height, 0, 0);
    }

    private void apply(State state) {
        if (!mIsAttached) {
            return;
        }
        applyForceStatusBarVisibleFlag(state);
        applyHeight(state);
        if (mLp != null && mLp.copyFrom(mLpChanged) != 0) {
            mWindowManager.updateViewLayout(mStatusBarWindowView, mLp);
        }
    }

    private static class State {
        boolean mForceStatusBarVisible;
        boolean mIsLaunchAnimationRunning;
        boolean mOngoingProcessRequiresStatusBarVisible;
    }

    private void applyForceStatusBarVisibleFlag(State state) {
        if (state.mForceStatusBarVisible
                || state.mIsLaunchAnimationRunning
                // Don't force-show the status bar if the user has already dismissed it.
                || state.mOngoingProcessRequiresStatusBarVisible) {
            mLpChanged.forciblyShownTypes |= WindowInsets.Type.statusBars();
        } else {
            mLpChanged.forciblyShownTypes &= ~WindowInsets.Type.statusBars();
        }
    }
}
