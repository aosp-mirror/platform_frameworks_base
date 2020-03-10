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

package com.android.systemui.stackdivider;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.SCREEN_HEIGHT_DP_UNDEFINED;
import static android.content.res.Configuration.SCREEN_WIDTH_DP_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;
import android.view.IWindowContainer;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.View;
import android.view.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.TransactionPool;
import com.android.systemui.recents.Recents;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.wm.DisplayChangeController;
import com.android.systemui.wm.DisplayController;
import com.android.systemui.wm.DisplayImeController;
import com.android.systemui.wm.DisplayLayout;
import com.android.systemui.wm.SystemWindows;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Consumer;

import javax.inject.Singleton;

import dagger.Lazy;

/**
 * Controls the docked stack divider.
 */
@Singleton
public class Divider extends SystemUI implements DividerView.DividerCallbacks,
        DisplayController.OnDisplaysChangedListener {
    private static final String TAG = "Divider";

    static final boolean DEBUG = true;

    static final int DEFAULT_APP_TRANSITION_DURATION = 336;
    static final float ADJUSTED_NONFOCUS_DIM = 0.3f;

    private final Optional<Lazy<Recents>> mRecentsOptionalLazy;

    private DividerWindowManager mWindowManager;
    private DividerView mView;
    private final DividerState mDividerState = new DividerState();
    private boolean mVisible = false;
    private boolean mMinimized = false;
    private boolean mAdjustedForIme = false;
    private boolean mHomeStackResizable = false;
    private ForcedResizableInfoActivityController mForcedResizableController;
    private SystemWindows mSystemWindows;
    final SurfaceSession mSurfaceSession = new SurfaceSession();
    private DisplayController mDisplayController;
    private DisplayImeController mImeController;
    final TransactionPool mTransactionPool;

    // Keeps track of real-time split geometry including snap positions and ime adjustments
    private SplitDisplayLayout mSplitLayout;

    // Transient: this contains the layout calculated for a new rotation requested by WM. This is
    // kept around so that we can wait for a matching configuration change and then use the exact
    // layout that we sent back to WM.
    private SplitDisplayLayout mRotateSplitLayout;

    private Handler mHandler;
    private KeyguardStateController mKeyguardStateController;

    private final ArrayList<WeakReference<Consumer<Boolean>>> mDockedStackExistsListeners =
            new ArrayList<>();

    private SplitScreenTaskOrganizer mSplits = new SplitScreenTaskOrganizer(this);

    private DisplayChangeController.OnDisplayChangingListener mRotationController =
            (display, fromRotation, toRotation, t) -> {
                DisplayLayout displayLayout =
                        new DisplayLayout(mDisplayController.getDisplayLayout(display));
                SplitDisplayLayout sdl = new SplitDisplayLayout(mContext, displayLayout, mSplits);
                sdl.rotateTo(toRotation);
                mRotateSplitLayout = sdl;
                int position = mMinimized ? mView.mSnapTargetBeforeMinimized.position
                        : mView.getCurrentPosition();
                DividerSnapAlgorithm snap = sdl.getSnapAlgorithm();
                final DividerSnapAlgorithm.SnapTarget target =
                        snap.calculateNonDismissingSnapTarget(position);
                sdl.resizeSplits(target.position, t);

                if (inSplitMode()) {
                    WindowManagerProxy.applyHomeTasksMinimized(sdl, mSplits.mSecondary.token, t);
                }
            };

    private DisplayImeController.ImePositionProcessor mImePositionProcessor =
            new DisplayImeController.ImePositionProcessor() {
                /**
                 * These are the y positions of the top of the IME surface when it is hidden and
                 * when it is shown respectively. These are NOT necessarily the top of the visible
                 * IME itself.
                 */
                private int mHiddenTop = 0;
                private int mShownTop = 0;

                // The following are target states (what we are curretly animating towards).
                /**
                 * {@code true} if, at the end of the animation, the split task positions should be
                 * adjusted by height of the IME. This happens when the secondary split is the IME
                 * target.
                 */
                private boolean mTargetAdjusted = false;
                /**
                 * {@code true} if, at the end of the animation, the IME should be shown/visible
                 * regardless of what has focus.
                 */
                private boolean mTargetShown = false;

                // The following are the current (most recent) states set during animation
                /**
                 * {@code true} if the secondary split has IME focus.
                 */
                private boolean mSecondaryHasFocus = false;
                /** The dimming currently applied to the primary/secondary splits. */
                private float mLastPrimaryDim = 0.f;
                private float mLastSecondaryDim = 0.f;
                /** The most recent y position of the top of the IME surface */
                private int mLastAdjustTop = -1;

                // The following are states reached last time an animation fully completed.
                /** {@code true} if the IME was shown/visible by the last-completed animation. */
                private boolean mImeWasShown = false;
                /**
                 * {@code true} if the split positions were adjusted by the last-completed
                 * animation.
                 */
                private boolean mAdjusted = false;

                /**
                 * When some aspect of split-screen needs to animate independent from the IME,
                 * this will be non-null and control split animation.
                 */
                @Nullable
                private ValueAnimator mAnimation = null;

                private boolean getSecondaryHasFocus(int displayId) {
                    try {
                        IWindowContainer imeSplit = ActivityTaskManager.getTaskOrganizerController()
                                .getImeTarget(displayId);
                        return imeSplit != null
                                && (imeSplit.asBinder() == mSplits.mSecondary.token.asBinder());
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Failed to get IME target", e);
                    }
                    return false;
                }

                @Override
                public void onImeStartPositioning(int displayId, int hiddenTop, int shownTop,
                        boolean imeShouldShow, SurfaceControl.Transaction t) {
                    mSecondaryHasFocus = getSecondaryHasFocus(displayId);
                    mTargetAdjusted = imeShouldShow && mSecondaryHasFocus
                            && !mSplitLayout.mDisplayLayout.isLandscape();
                    mHiddenTop = hiddenTop;
                    mShownTop = shownTop;
                    mTargetShown = imeShouldShow;
                    if (mLastAdjustTop < 0) {
                        mLastAdjustTop = imeShouldShow ? hiddenTop : shownTop;
                    }
                    if (mAnimation != null || (mImeWasShown && imeShouldShow
                            && mTargetAdjusted != mAdjusted)) {
                        // We need to animate adjustment independently of the IME position, so
                        // start our own animation to drive adjustment. This happens when a
                        // different split's editor has gained focus while the IME is still visible.
                        startAsyncAnimation();
                    }
                    // Reposition the server's secondary split position so that it evaluates
                    // insets properly.
                    WindowContainerTransaction wct = new WindowContainerTransaction();
                    if (mTargetAdjusted) {
                        mSplitLayout.updateAdjustedBounds(mShownTop, mHiddenTop, mShownTop);
                        wct.setBounds(mSplits.mSecondary.token, mSplitLayout.mAdjustedSecondary);
                        // "Freeze" the configuration size so that the app doesn't get a config
                        // or relaunch. This is required because normally nav-bar contributes
                        // to configuration bounds (via nondecorframe).
                        Rect adjustAppBounds = new Rect(mSplits.mSecondary.configuration
                                .windowConfiguration.getAppBounds());
                        adjustAppBounds.offset(0, mSplitLayout.mAdjustedSecondary.top
                                - mSplitLayout.mSecondary.top);
                        wct.setAppBounds(mSplits.mSecondary.token, adjustAppBounds);
                        wct.setScreenSizeDp(mSplits.mSecondary.token,
                                mSplits.mSecondary.configuration.screenWidthDp,
                                mSplits.mSecondary.configuration.screenHeightDp);
                    } else {
                        wct.setBounds(mSplits.mSecondary.token, mSplitLayout.mSecondary);
                        wct.setAppBounds(mSplits.mSecondary.token, null);
                        wct.setScreenSizeDp(mSplits.mSecondary.token,
                                SCREEN_WIDTH_DP_UNDEFINED, SCREEN_HEIGHT_DP_UNDEFINED);
                    }
                    try {
                        ActivityTaskManager.getTaskOrganizerController()
                                .applyContainerTransaction(wct, null /* organizer */);
                    } catch (RemoteException e) {
                    }

                    // Update all the adjusted-for-ime states
                    mView.setAdjustedForIme(mTargetShown, mTargetShown
                            ? DisplayImeController.ANIMATION_DURATION_SHOW_MS
                            : DisplayImeController.ANIMATION_DURATION_HIDE_MS);
                    setAdjustedForIme(mTargetShown);
                }

                @Override
                public void onImePositionChanged(int displayId, int imeTop,
                        SurfaceControl.Transaction t) {
                    if (mAnimation != null) {
                        // Not synchronized with IME anymore, so return.
                        return;
                    }
                    final float fraction = ((float) imeTop - mHiddenTop) / (mShownTop - mHiddenTop);
                    final float progress = mTargetShown ? fraction : 1.f - fraction;
                    onProgress(progress, t);
                }

                @Override
                public void onImeEndPositioning(int displayId, boolean cancelled,
                        SurfaceControl.Transaction t) {
                    if (mAnimation != null) {
                        // Not synchronized with IME anymore, so return.
                        return;
                    }
                    onEnd(cancelled, t);
                }

                private void onProgress(float progress, SurfaceControl.Transaction t) {
                    if (mTargetAdjusted != mAdjusted) {
                        final float fraction = mTargetAdjusted ? progress : 1.f - progress;
                        mLastAdjustTop =
                                (int) (fraction * mShownTop + (1.f - fraction) * mHiddenTop);
                        mSplitLayout.updateAdjustedBounds(mLastAdjustTop, mHiddenTop, mShownTop);
                        mView.resizeSplitSurfaces(t, mSplitLayout.mAdjustedPrimary,
                                mSplitLayout.mAdjustedSecondary);
                    }
                    final float invProg = 1.f - progress;
                    final float targetPrimaryDim = (mSecondaryHasFocus && mTargetShown)
                            ? ADJUSTED_NONFOCUS_DIM : 0.f;
                    final float targetSecondaryDim = (!mSecondaryHasFocus && mTargetShown)
                            ? ADJUSTED_NONFOCUS_DIM : 0.f;
                    mView.setResizeDimLayer(t, true /* primary */,
                            mLastPrimaryDim * invProg + progress * targetPrimaryDim);
                    mView.setResizeDimLayer(t, false /* primary */,
                            mLastSecondaryDim * invProg + progress * targetSecondaryDim);
                }

                private void onEnd(boolean cancelled, SurfaceControl.Transaction t) {
                    if (!cancelled) {
                        onProgress(1.f, t);
                        mAdjusted = mTargetAdjusted;
                        mImeWasShown = mTargetShown;
                        mLastAdjustTop = mAdjusted ? mShownTop : mHiddenTop;
                        mLastPrimaryDim =
                                (mSecondaryHasFocus && mTargetShown) ? ADJUSTED_NONFOCUS_DIM : 0.f;
                        mLastSecondaryDim =
                                (!mSecondaryHasFocus && mTargetShown) ? ADJUSTED_NONFOCUS_DIM : 0.f;
                    }
                }

                private void startAsyncAnimation() {
                    if (mAnimation != null) {
                        mAnimation.cancel();
                    }
                    mAnimation = ValueAnimator.ofFloat(0.f, 1.f);
                    mAnimation.setDuration(DisplayImeController.ANIMATION_DURATION_SHOW_MS);
                    if (mTargetAdjusted != mAdjusted) {
                        final float fraction =
                                ((float) mLastAdjustTop - mHiddenTop) / (mShownTop - mHiddenTop);
                        final float progress = mTargetAdjusted ? fraction : 1.f - fraction;
                        mAnimation.setCurrentFraction(progress);
                    }

                    mAnimation.addUpdateListener(animation -> {
                        SurfaceControl.Transaction t = mTransactionPool.acquire();
                        float value = (float) animation.getAnimatedValue();
                        onProgress(value, t);
                        t.apply();
                        mTransactionPool.release(t);
                    });
                    mAnimation.setInterpolator(DisplayImeController.INTERPOLATOR);
                    mAnimation.addListener(new AnimatorListenerAdapter() {
                        private boolean mCancel = false;
                        @Override
                        public void onAnimationCancel(Animator animation) {
                            mCancel = true;
                        }
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            SurfaceControl.Transaction t = mTransactionPool.acquire();
                            onEnd(mCancel, t);
                            t.apply();
                            mTransactionPool.release(t);
                            mAnimation = null;
                        }
                    });
                    mAnimation.start();
                }
            };

    public Divider(Context context, Optional<Lazy<Recents>> recentsOptionalLazy,
            DisplayController displayController, SystemWindows systemWindows,
            DisplayImeController imeController, Handler handler,
            KeyguardStateController keyguardStateController, TransactionPool transactionPool) {
        super(context);
        mDisplayController = displayController;
        mSystemWindows = systemWindows;
        mImeController = imeController;
        mHandler = handler;
        mKeyguardStateController = keyguardStateController;
        mRecentsOptionalLazy = recentsOptionalLazy;
        mForcedResizableController = new ForcedResizableInfoActivityController(context, this);
        mTransactionPool = transactionPool;
    }

    @Override
    public void start() {
        mWindowManager = new DividerWindowManager(mSystemWindows);
        mDisplayController.addDisplayWindowListener(this);
        // Hide the divider when keyguard is showing. Even though keyguard/statusbar is above
        // everything, it is actually transparent except for notifications, so we still need to
        // hide any surfaces that are below it.
        // TODO(b/148906453): Figure out keyguard dismiss animation for divider view.
        mKeyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onUnlockedChanged() {

            }

            @Override
            public void onKeyguardShowingChanged() {
                if (!inSplitMode() || mView == null) {
                    return;
                }
                mView.setHidden(mKeyguardStateController.isShowing());
            }

            @Override
            public void onKeyguardFadingAwayChanged() {

            }
        });
        // Don't initialize the divider or anything until we get the default display.
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }
        mSplitLayout = new SplitDisplayLayout(mDisplayController.getDisplayContext(displayId),
                mDisplayController.getDisplayLayout(displayId), mSplits);
        mImeController.addPositionProcessor(mImePositionProcessor);
        mDisplayController.addDisplayChangingController(mRotationController);
        try {
            mSplits.init(ActivityTaskManager.getTaskOrganizerController(), mSurfaceSession);
            // Set starting tile bounds based on middle target
            final WindowContainerTransaction tct = new WindowContainerTransaction();
            int midPos = mSplitLayout.getSnapAlgorithm().getMiddleTarget().position;
            mSplitLayout.resizeSplits(midPos, tct);
            ActivityTaskManager.getTaskOrganizerController().applyContainerTransaction(tct,
                    null /* organizer */);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to register docked stack listener", e);
        }
        update(mDisplayController.getDisplayContext(displayId).getResources().getConfiguration());
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        if (displayId != DEFAULT_DISPLAY) {
            return;
        }
        mSplitLayout = new SplitDisplayLayout(mDisplayController.getDisplayContext(displayId),
                mDisplayController.getDisplayLayout(displayId), mSplits);
        if (mRotateSplitLayout == null) {
            int midPos = mSplitLayout.getSnapAlgorithm().getMiddleTarget().position;
            final WindowContainerTransaction tct = new WindowContainerTransaction();
            mSplitLayout.resizeSplits(midPos, tct);
            try {
                ActivityTaskManager.getTaskOrganizerController().applyContainerTransaction(tct,
                        null /* organizer */);
            } catch (RemoteException e) {
            }
        } else if (mRotateSplitLayout != null
                && mSplitLayout.mDisplayLayout.rotation()
                        == mRotateSplitLayout.mDisplayLayout.rotation()) {
            mSplitLayout.mPrimary = new Rect(mRotateSplitLayout.mPrimary);
            mSplitLayout.mSecondary = new Rect(mRotateSplitLayout.mSecondary);
            mRotateSplitLayout = null;
        }
        update(newConfig);
    }

    Handler getHandler() {
        return mHandler;
    }

    public DividerView getView() {
        return mView;
    }

    public boolean isMinimized() {
        return mMinimized;
    }

    public boolean isHomeStackResizable() {
        return mHomeStackResizable;
    }

    /** {@code true} if this is visible */
    public boolean inSplitMode() {
        return mView != null && mView.getVisibility() == View.VISIBLE;
    }

    private void addDivider(Configuration configuration) {
        Context dctx = mDisplayController.getDisplayContext(mContext.getDisplayId());
        mView = (DividerView)
                LayoutInflater.from(dctx).inflate(R.layout.docked_stack_divider, null);
        DisplayLayout displayLayout = mDisplayController.getDisplayLayout(mContext.getDisplayId());
        mView.injectDependencies(mWindowManager, mDividerState, this, mSplits, mSplitLayout);
        mView.setVisibility(mVisible ? View.VISIBLE : View.INVISIBLE);
        mView.setMinimizedDockStack(mMinimized, mHomeStackResizable);
        final int size = dctx.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.docked_stack_divider_thickness);
        final boolean landscape = configuration.orientation == ORIENTATION_LANDSCAPE;
        final int width = landscape ? size : displayLayout.width();
        final int height = landscape ? displayLayout.height() : size;
        mWindowManager.add(mView, width, height, mContext.getDisplayId());
    }

    private void removeDivider() {
        if (mView != null) {
            mView.onDividerRemoved();
        }
        mWindowManager.remove();
    }

    private void update(Configuration configuration) {
        removeDivider();
        addDivider(configuration);
        if (mMinimized && mView != null) {
            mView.setMinimizedDockStack(true, mHomeStackResizable);
            updateTouchable();
        }
    }

    void updateVisibility(final boolean visible) {
        if (mVisible != visible) {
            mVisible = visible;
            mView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);

            if (visible) {
                mView.enterSplitMode(mHomeStackResizable);
                // Update state because animations won't finish.
                mView.setMinimizedDockStack(mMinimized, mHomeStackResizable);
            } else {
                mView.exitSplitMode();
                // un-minimize so that next entry triggers minimize anim.
                mView.setMinimizedDockStack(false /* minimized */, mHomeStackResizable);
            }
            // Notify existence listeners
            synchronized (mDockedStackExistsListeners) {
                mDockedStackExistsListeners.removeIf(wf -> {
                    Consumer<Boolean> l = wf.get();
                    if (l != null) l.accept(visible);
                    return l == null;
                });
            }
        }
    }

    private void setHomeStackResizable(boolean resizable) {
        if (mHomeStackResizable == resizable) {
            return;
        }
        mHomeStackResizable = resizable;
        if (!inSplitMode()) {
            return;
        }
        WindowManagerProxy.applyHomeTasksMinimized(mSplitLayout, mSplits.mSecondary.token);
    }

    private void updateMinimizedDockedStack(final boolean minimized, final long animDuration,
            final boolean isHomeStackResizable) {
        setHomeStackResizable(isHomeStackResizable);
        if (animDuration > 0) {
            mView.setMinimizedDockStack(minimized, animDuration, isHomeStackResizable);
        } else {
            mView.setMinimizedDockStack(minimized, isHomeStackResizable);
        }
        updateTouchable();
    }

    /** Switch to minimized state if appropriate */
    public void setMinimized(final boolean minimized) {
        mHandler.post(() -> {
            if (!inSplitMode()) {
                return;
            }
            if (mMinimized == minimized) {
                return;
            }
            mMinimized = minimized;
            WindowManagerProxy.applyPrimaryFocusable(mSplits, !mMinimized);
            mView.setMinimizedDockStack(minimized, getAnimDuration(), mHomeStackResizable);
            updateTouchable();
        });
    }

    void setAdjustedForIme(boolean adjustedForIme) {
        if (mAdjustedForIme == adjustedForIme) {
            return;
        }
        mAdjustedForIme = adjustedForIme;
        updateTouchable();
    }

    private void updateTouchable() {
        mWindowManager.setTouchable((mHomeStackResizable || !mMinimized) && !mAdjustedForIme);
    }

    /**
     * Workaround for b/62528361, at the time recents has drawn, it may happen before a
     * configuration change to the Divider, and internally, the event will be posted to the
     * subscriber, or DividerView, which has been removed and prevented from resizing. Instead,
     * register the event handler here and proxy the event to the current DividerView.
     */
    public void onRecentsDrawn() {
        if (mView != null) {
            mView.onRecentsDrawn();
        }
    }

    public void onUndockingTask() {
        if (mView != null) {
            mView.onUndockingTask();
        }
    }

    public void onDockedFirstAnimationFrame() {
        if (mView != null) {
            mView.onDockedFirstAnimationFrame();
        }
    }

    public void onDockedTopTask() {
        if (mView != null) {
            mView.onDockedTopTask();
        }
    }

    public void onAppTransitionFinished() {
        if (mView == null) {
            return;
        }
        mForcedResizableController.onAppTransitionFinished();
    }

    @Override
    public void onDraggingStart() {
        mForcedResizableController.onDraggingStart();
    }

    @Override
    public void onDraggingEnd() {
        mForcedResizableController.onDraggingEnd();
    }

    @Override
    public void growRecents() {
        mRecentsOptionalLazy.ifPresent(recentsLazy -> recentsLazy.get().growRecents());
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.print("  mVisible="); pw.println(mVisible);
        pw.print("  mMinimized="); pw.println(mMinimized);
        pw.print("  mAdjustedForIme="); pw.println(mAdjustedForIme);
    }

    long getAnimDuration() {
        float transitionScale = Settings.Global.getFloat(mContext.getContentResolver(),
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                mContext.getResources().getFloat(
                        com.android.internal.R.dimen
                                .config_appTransitionAnimationDurationScaleDefault));
        final long transitionDuration = DEFAULT_APP_TRANSITION_DURATION;
        return (long) (transitionDuration * transitionScale);
    }

    /** Register a listener that gets called whenever the existence of the divider changes */
    public void registerInSplitScreenListener(Consumer<Boolean> listener) {
        listener.accept(inSplitMode());
        synchronized (mDockedStackExistsListeners) {
            mDockedStackExistsListeners.add(new WeakReference<>(listener));
        }
    }

    void startEnterSplit() {
        // Set resizable directly here because applyEnterSplit already resizes home stack.
        mHomeStackResizable = WindowManagerProxy.applyEnterSplit(mSplits, mSplitLayout);
    }

    void ensureMinimizedSplit() {
        final boolean wasMinimized = mMinimized;
        mMinimized = true;
        setHomeStackResizable(mSplits.mSecondary.isResizable());
        WindowManagerProxy.applyPrimaryFocusable(mSplits, false /* focusable */);
        if (!inSplitMode()) {
            // Wasn't in split-mode yet, so enter now.
            if (DEBUG) {
                Log.d(TAG, " entering split mode with minimized=true");
            }
            updateVisibility(true /* visible */);
        } else if (!wasMinimized) {
            if (DEBUG) {
                Log.d(TAG, " in split mode, but minimizing ");
            }
            // Was already in split-mode, update just minimized state.
            updateMinimizedDockedStack(mMinimized, getAnimDuration(),
                    mHomeStackResizable);
        }
    }

    void ensureNormalSplit() {
        if (mMinimized) {
            WindowManagerProxy.applyPrimaryFocusable(mSplits, true /* focusable */);
        }
        if (!inSplitMode()) {
            // Wasn't in split-mode, so enter now.
            if (DEBUG) {
                Log.d(TAG, " enter split mode unminimized ");
            }
            mMinimized = false;
            updateVisibility(true /* visible */);
        }
        if (mMinimized) {
            // Was in minimized state, so leave that.
            if (DEBUG) {
                Log.d(TAG, " in split mode already, but unminimizing ");
            }
            mMinimized = false;
            updateMinimizedDockedStack(mMinimized, getAnimDuration(),
                    mHomeStackResizable);
        }
    }
}
