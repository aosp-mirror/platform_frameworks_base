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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.systemui.shared.system.WindowManagerWrapper.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Handler;
import android.provider.Settings;
import android.util.Slog;
import android.view.LayoutInflater;
import android.view.View;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowOrganizer;

import com.android.internal.policy.DividerSnapAlgorithm;
import com.android.systemui.R;
import com.android.systemui.SystemUI;
import com.android.systemui.TransactionPool;
import com.android.systemui.recents.Recents;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
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

    static final boolean DEBUG = false;

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
                if (!mSplits.isSplitScreenSupported()) {
                    return;
                }
                DisplayLayout displayLayout =
                        new DisplayLayout(mDisplayController.getDisplayLayout(display));
                SplitDisplayLayout sdl = new SplitDisplayLayout(mContext, displayLayout, mSplits);
                sdl.rotateTo(toRotation);
                mRotateSplitLayout = sdl;
                final int position = isDividerVisible()
                        ? (mMinimized ? mView.mSnapTargetBeforeMinimized.position
                                : mView.getCurrentPosition())
                        // snap resets to middle target when not in split-mode
                        : sdl.getSnapAlgorithm().getMiddleTarget().position;
                DividerSnapAlgorithm snap = sdl.getSnapAlgorithm();
                final DividerSnapAlgorithm.SnapTarget target =
                        snap.calculateNonDismissingSnapTarget(position);
                sdl.resizeSplits(target.position, t);

                if (isSplitActive()) {
                    WindowManagerProxy.applyHomeTasksMinimized(sdl, mSplits.mSecondary.token, t);
                }
            };

    private final DividerImeController mImePositionProcessor;

    private TaskStackChangeListener mActivityRestartListener = new TaskStackChangeListener() {
        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (!wasVisible || task.configuration.windowConfiguration.getWindowingMode()
                    != WINDOWING_MODE_SPLIT_SCREEN_PRIMARY || !mSplits.isSplitScreenSupported()) {
                return;
            }

            if (isMinimized()) {
                onUndockingTask();
            }
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
        mImePositionProcessor = new DividerImeController(mSplits, mTransactionPool, mHandler);
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
                if (!isDividerVisible() || mView == null) {
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
        if (!ActivityTaskManager.supportsSplitScreenMultiWindow(mContext)) {
            removeDivider();
            return;
        }
        try {
            mSplits.init();
            // Set starting tile bounds based on middle target
            final WindowContainerTransaction tct = new WindowContainerTransaction();
            int midPos = mSplitLayout.getSnapAlgorithm().getMiddleTarget().position;
            mSplitLayout.resizeSplits(midPos, tct);
            WindowOrganizer.applyTransaction(tct);
        } catch (Exception e) {
            Slog.e(TAG, "Failed to register docked stack listener", e);
            removeDivider();
            return;
        }
        ActivityManagerWrapper.getInstance().registerTaskStackListener(mActivityRestartListener);
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        if (displayId != DEFAULT_DISPLAY || !mSplits.isSplitScreenSupported()) {
            return;
        }
        mSplitLayout = new SplitDisplayLayout(mDisplayController.getDisplayContext(displayId),
                mDisplayController.getDisplayLayout(displayId), mSplits);
        if (mRotateSplitLayout == null) {
            int midPos = mSplitLayout.getSnapAlgorithm().getMiddleTarget().position;
            final WindowContainerTransaction tct = new WindowContainerTransaction();
            mSplitLayout.resizeSplits(midPos, tct);
            WindowOrganizer.applyTransaction(tct);
        } else if (mSplitLayout.mDisplayLayout.rotation()
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
    public boolean isDividerVisible() {
        return mView != null && mView.getVisibility() == View.VISIBLE;
    }

    /**
     * This indicates that at-least one of the splits has content. This differs from
     * isDividerVisible because the divider is only visible once *everything* is in split mode
     * while this only cares if some things are (eg. while entering/exiting as well).
     */
    private boolean isSplitActive() {
        return mSplits.mPrimary.topActivityType != ACTIVITY_TYPE_UNDEFINED
                || mSplits.mSecondary.topActivityType != ACTIVITY_TYPE_UNDEFINED;
    }

    private void addDivider(Configuration configuration) {
        Context dctx = mDisplayController.getDisplayContext(mContext.getDisplayId());
        mView = (DividerView)
                LayoutInflater.from(dctx).inflate(R.layout.docked_stack_divider, null);
        DisplayLayout displayLayout = mDisplayController.getDisplayLayout(mContext.getDisplayId());
        mView.injectDependencies(mWindowManager, mDividerState, this, mSplits, mSplitLayout,
                mImePositionProcessor);
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
        final boolean isDividerHidden = mView != null && mView.isHidden();

        removeDivider();
        addDivider(configuration);

        if (mMinimized) {
            mView.setMinimizedDockStack(true, mHomeStackResizable);
            updateTouchable();
        }
        mView.setHidden(isDividerHidden);
    }

    void onTaskVanished() {
        mHandler.post(this::removeDivider);
    }

    void onTasksReady() {
        mHandler.post(() -> update(mDisplayController.getDisplayContext(
                mContext.getDisplayId()).getResources().getConfiguration()));
    }

    private void updateVisibility(final boolean visible) {
        if (DEBUG) Slog.d(TAG, "Updating visibility " + mVisible + "->" + visible);
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

    void onSplitDismissed() {
        updateVisibility(false /* visible */);
        mMinimized = false;
        removeDivider();
    }

    /** Switch to minimized state if appropriate */
    public void setMinimized(final boolean minimized) {
        if (DEBUG) Slog.d(TAG, "posting ext setMinimized " + minimized + " vis:" + mVisible);
        mHandler.post(() -> {
            if (DEBUG) Slog.d(TAG, "run posted ext setMinimized " + minimized + " vis:" + mVisible);
            if (!mVisible) {
                return;
            }
            setHomeMinimized(minimized, mHomeStackResizable);
        });
    }

    private void setHomeMinimized(final boolean minimized, boolean homeStackResizable) {
        if (DEBUG) {
            Slog.d(TAG, "setHomeMinimized  min:" + mMinimized + "->" + minimized + " hrsz:"
                    + mHomeStackResizable + "->" + homeStackResizable
                    + " split:" + isDividerVisible());
        }
        WindowContainerTransaction wct = new WindowContainerTransaction();
        final boolean minimizedChanged = mMinimized != minimized;
        // Update minimized state
        if (minimizedChanged) {
            mMinimized = minimized;
        }
        // Always set this because we could be entering split when mMinimized is already true
        wct.setFocusable(mSplits.mPrimary.token, !mMinimized);

        // Update home-stack resizability
        final boolean homeResizableChanged = mHomeStackResizable != homeStackResizable;
        if (homeResizableChanged) {
            mHomeStackResizable = homeStackResizable;
            if (isDividerVisible()) {
                WindowManagerProxy.applyHomeTasksMinimized(
                        mSplitLayout, mSplits.mSecondary.token, wct);
            }
        }

        // Sync state to DividerView if it exists.
        if (mView != null) {
            final int displayId = mView.getDisplay() != null
                    ? mView.getDisplay().getDisplayId() : DEFAULT_DISPLAY;
            // pause ime here (before updateMinimizedDockedStack)
            if (mMinimized) {
                mImePositionProcessor.pause(displayId);
            }
            if (minimizedChanged || homeResizableChanged) {
                // This conflicts with IME adjustment, so only call it when things change.
                mView.setMinimizedDockStack(minimized, getAnimDuration(), homeStackResizable);
            }
            if (!mMinimized) {
                // afterwards so it can end any animations started in view
                mImePositionProcessor.resume(displayId);
            }
        }
        updateTouchable();
        WindowOrganizer.applyTransaction(wct);
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
        listener.accept(isDividerVisible());
        synchronized (mDockedStackExistsListeners) {
            mDockedStackExistsListeners.add(new WeakReference<>(listener));
        }
    }

    void startEnterSplit() {
        update(mDisplayController.getDisplayContext(
                mContext.getDisplayId()).getResources().getConfiguration());
        // Set resizable directly here because applyEnterSplit already resizes home stack.
        mHomeStackResizable = WindowManagerProxy.applyEnterSplit(mSplits, mSplitLayout);
    }

    void ensureMinimizedSplit() {
        setHomeMinimized(true /* minimized */, mSplits.mSecondary.isResizable());
        if (!isDividerVisible()) {
            // Wasn't in split-mode yet, so enter now.
            if (DEBUG) {
                Slog.d(TAG, " entering split mode with minimized=true");
            }
            updateVisibility(true /* visible */);
        }
    }

    void ensureNormalSplit() {
        setHomeMinimized(false /* minimized */, mHomeStackResizable);
        if (!isDividerVisible()) {
            // Wasn't in split-mode, so enter now.
            if (DEBUG) {
                Slog.d(TAG, " enter split mode unminimized ");
            }
            updateVisibility(true /* visible */);
        }
    }

    SplitDisplayLayout getSplitLayout() {
        return mSplitLayout;
    }

    /** @return the container token for the secondary split root task. */
    public WindowContainerToken getSecondaryRoot() {
        if (mSplits == null || mSplits.mSecondary == null) {
            return null;
        }
        return mSplits.mSecondary.token;
    }
}
