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
import static android.view.Display.DEFAULT_DISPLAY;

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

    private IWindowContainer mLastImeTarget = null;
    private boolean mShouldAdjustForIme = false;

    private DisplayImeController.ImePositionProcessor mImePositionProcessor =
            new DisplayImeController.ImePositionProcessor() {
                private int mStartTop = 0;
                private int mFinalTop = 0;
                @Override
                public void onImeStartPositioning(int displayId, int imeTop, int finalImeTop,
                        boolean showing, SurfaceControl.Transaction t) {
                    mStartTop = imeTop;
                    mFinalTop = finalImeTop;
                    if (showing) {
                        try {
                            mLastImeTarget = ActivityTaskManager.getTaskOrganizerController()
                                    .getImeTarget(displayId);
                            mShouldAdjustForIme = mLastImeTarget != null
                                    && !mSplitLayout.mDisplayLayout.isLandscape()
                                    && (mLastImeTarget.asBinder()
                                    == mSplits.mSecondary.token.asBinder());
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failed to get IME target", e);
                        }
                    }
                    if (!mShouldAdjustForIme) {
                        setAdjustedForIme(false);
                        return;
                    }
                    mView.setAdjustedForIme(showing, showing
                            ? DisplayImeController.ANIMATION_DURATION_SHOW_MS
                            : DisplayImeController.ANIMATION_DURATION_HIDE_MS);
                    // Reposition the server's secondary split position so that it evaluates
                    // insets properly.
                    WindowContainerTransaction wct = new WindowContainerTransaction();
                    if (showing) {
                        mSplitLayout.updateAdjustedBounds(finalImeTop, imeTop, finalImeTop);
                        wct.setBounds(mSplits.mSecondary.token, mSplitLayout.mAdjustedSecondary);
                    } else {
                        wct.setBounds(mSplits.mSecondary.token, mSplitLayout.mSecondary);
                    }
                    try {
                        ActivityTaskManager.getTaskOrganizerController()
                                .applyContainerTransaction(wct, null /* organizer */);
                    } catch (RemoteException e) {
                    }
                    setAdjustedForIme(showing);
                }

                @Override
                public void onImePositionChanged(int displayId, int imeTop,
                        SurfaceControl.Transaction t) {
                    if (!mShouldAdjustForIme) {
                        return;
                    }
                    mSplitLayout.updateAdjustedBounds(imeTop, mStartTop, mFinalTop);
                    mView.resizeSplitSurfaces(t, mSplitLayout.mAdjustedPrimary,
                            mSplitLayout.mAdjustedSecondary);
                    final boolean showing = mFinalTop < mStartTop;
                    final float progress = ((float) (imeTop - mStartTop)) / (mFinalTop - mStartTop);
                    final float fraction = showing ? progress : 1.f - progress;
                    mView.setResizeDimLayer(t, true /* primary */, fraction * 0.3f);
                }

                @Override
                public void onImeEndPositioning(int displayId, int imeTop,
                        boolean showing, SurfaceControl.Transaction t) {
                    if (!mShouldAdjustForIme) {
                        return;
                    }
                    mSplitLayout.updateAdjustedBounds(imeTop, mStartTop, mFinalTop);
                    mView.resizeSplitSurfaces(t, mSplitLayout.mAdjustedPrimary,
                            mSplitLayout.mAdjustedSecondary);
                    mView.setResizeDimLayer(t, true /* primary */, showing ? 0.3f : 0.f);
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
