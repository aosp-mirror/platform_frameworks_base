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

package com.android.wm.shell.common;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.IntDef;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.util.SparseArray;
import android.view.IDisplayWindowInsetsController;
import android.view.IWindowManager;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.BinderThread;
import androidx.annotation.VisibleForTesting;

import com.android.internal.view.IInputMethodManager;

import java.util.ArrayList;
import java.util.concurrent.Executor;

/**
 * Manages IME control at the display-level. This occurs when IME comes up in multi-window mode.
 */
public class DisplayImeController implements DisplayController.OnDisplaysChangedListener {
    private static final String TAG = "DisplayImeController";

    private static final boolean DEBUG = false;

    // NOTE: All these constants came from InsetsController.
    public static final int ANIMATION_DURATION_SHOW_MS = 275;
    public static final int ANIMATION_DURATION_HIDE_MS = 340;
    public static final Interpolator INTERPOLATOR = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final int DIRECTION_NONE = 0;
    private static final int DIRECTION_SHOW = 1;
    private static final int DIRECTION_HIDE = 2;
    private static final int FLOATING_IME_BOTTOM_INSET = -80;

    protected final IWindowManager mWmService;
    protected final Executor mMainExecutor;
    private final TransactionPool mTransactionPool;
    private final DisplayController mDisplayController;
    private final SparseArray<PerDisplay> mImePerDisplay = new SparseArray<>();
    private final ArrayList<ImePositionProcessor> mPositionProcessors = new ArrayList<>();


    public DisplayImeController(IWindowManager wmService, DisplayController displayController,
            Executor mainExecutor, TransactionPool transactionPool) {
        mWmService = wmService;
        mDisplayController = displayController;
        mMainExecutor = mainExecutor;
        mTransactionPool = transactionPool;
    }

    /** Starts monitor displays changes and set insets controller for each displays. */
    public void startMonitorDisplays() {
        mDisplayController.addDisplayWindowListener(this);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        // Add's a system-ui window-manager specifically for ime. This type is special because
        // WM will defer IME inset handling to it in multi-window scenarious.
        PerDisplay pd = new PerDisplay(displayId,
                mDisplayController.getDisplayLayout(displayId).rotation());
        pd.register();
        mImePerDisplay.put(displayId, pd);
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        PerDisplay pd = mImePerDisplay.get(displayId);
        if (pd == null) {
            return;
        }
        if (mDisplayController.getDisplayLayout(displayId).rotation()
                != pd.mRotation && isImeShowing(displayId)) {
            pd.startAnimation(true, false /* forceRestart */);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        try {
            mWmService.setDisplayWindowInsetsController(displayId, null);
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to remove insets controller on display " + displayId);
        }
        mImePerDisplay.remove(displayId);
    }

    private boolean isImeShowing(int displayId) {
        PerDisplay pd = mImePerDisplay.get(displayId);
        if (pd == null) {
            return false;
        }
        final InsetsSource imeSource = pd.mInsetsState.getSource(InsetsState.ITYPE_IME);
        return imeSource != null && pd.mImeSourceControl != null && imeSource.isVisible();
    }

    private void dispatchPositionChanged(int displayId, int imeTop,
            SurfaceControl.Transaction t) {
        synchronized (mPositionProcessors) {
            for (ImePositionProcessor pp : mPositionProcessors) {
                pp.onImePositionChanged(displayId, imeTop, t);
            }
        }
    }

    @ImePositionProcessor.ImeAnimationFlags
    private int dispatchStartPositioning(int displayId, int hiddenTop, int shownTop,
            boolean show, boolean isFloating, SurfaceControl.Transaction t) {
        synchronized (mPositionProcessors) {
            int flags = 0;
            for (ImePositionProcessor pp : mPositionProcessors) {
                flags |= pp.onImeStartPositioning(
                        displayId, hiddenTop, shownTop, show, isFloating, t);
            }
            return flags;
        }
    }

    private void dispatchEndPositioning(int displayId, boolean cancel,
            SurfaceControl.Transaction t) {
        synchronized (mPositionProcessors) {
            for (ImePositionProcessor pp : mPositionProcessors) {
                pp.onImeEndPositioning(displayId, cancel, t);
            }
        }
    }

    private void dispatchImeControlTargetChanged(int displayId, boolean controlling) {
        synchronized (mPositionProcessors) {
            for (ImePositionProcessor pp : mPositionProcessors) {
                pp.onImeControlTargetChanged(displayId, controlling);
            }
        }
    }

    private void dispatchVisibilityChanged(int displayId, boolean isShowing) {
        synchronized (mPositionProcessors) {
            for (ImePositionProcessor pp : mPositionProcessors) {
                pp.onImeVisibilityChanged(displayId, isShowing);
            }
        }
    }

    /**
     * Adds an {@link ImePositionProcessor} to be called during ime position updates.
     */
    public void addPositionProcessor(ImePositionProcessor processor) {
        synchronized (mPositionProcessors) {
            if (mPositionProcessors.contains(processor)) {
                return;
            }
            mPositionProcessors.add(processor);
        }
    }

    /**
     * Removes an {@link ImePositionProcessor} to be called during ime position updates.
     */
    public void removePositionProcessor(ImePositionProcessor processor) {
        synchronized (mPositionProcessors) {
            mPositionProcessors.remove(processor);
        }
    }

    /** An implementation of {@link IDisplayWindowInsetsController} for a given display id. */
    public class PerDisplay {
        final int mDisplayId;
        final InsetsState mInsetsState = new InsetsState();
        protected final DisplayWindowInsetsControllerImpl mInsetsControllerImpl =
                new DisplayWindowInsetsControllerImpl();
        InsetsSourceControl mImeSourceControl = null;
        int mAnimationDirection = DIRECTION_NONE;
        ValueAnimator mAnimation = null;
        int mRotation = Surface.ROTATION_0;
        boolean mImeShowing = false;
        final Rect mImeFrame = new Rect();
        boolean mAnimateAlpha = true;

        public PerDisplay(int displayId, int initialRotation) {
            mDisplayId = displayId;
            mRotation = initialRotation;
        }

        public void register() {
            try {
                mWmService.setDisplayWindowInsetsController(mDisplayId, mInsetsControllerImpl);
            } catch (RemoteException e) {
                Slog.w(TAG, "Unable to set insets controller on display " + mDisplayId);
            }
        }

        protected void insetsChanged(InsetsState insetsState) {
            if (mInsetsState.equals(insetsState)) {
                return;
            }

            updateImeVisibility(insetsState.getSourceOrDefaultVisibility(InsetsState.ITYPE_IME));

            final InsetsSource newSource = insetsState.getSource(InsetsState.ITYPE_IME);
            final Rect newFrame = newSource.getFrame();
            final Rect oldFrame = mInsetsState.getSource(InsetsState.ITYPE_IME).getFrame();

            mInsetsState.set(insetsState, true /* copySources */);
            if (mImeShowing && !newFrame.equals(oldFrame) && newSource.isVisible()) {
                if (DEBUG) Slog.d(TAG, "insetsChanged when IME showing, restart animation");
                startAnimation(mImeShowing, true /* forceRestart */);
            }
        }

        @VisibleForTesting
        protected void insetsControlChanged(InsetsState insetsState,
                InsetsSourceControl[] activeControls) {
            insetsChanged(insetsState);
            InsetsSourceControl imeSourceControl = null;
            if (activeControls != null) {
                for (InsetsSourceControl activeControl : activeControls) {
                    if (activeControl == null) {
                        continue;
                    }
                    if (activeControl.getType() == InsetsState.ITYPE_IME) {
                        imeSourceControl = activeControl;
                    }
                }
            }

            final boolean hadImeSourceControl = mImeSourceControl != null;
            final boolean hasImeSourceControl = imeSourceControl != null;
            if (hadImeSourceControl != hasImeSourceControl) {
                dispatchImeControlTargetChanged(mDisplayId, hasImeSourceControl);
            }

            if (hasImeSourceControl) {
                final Point lastSurfacePosition = mImeSourceControl != null
                        ? mImeSourceControl.getSurfacePosition() : null;
                final boolean positionChanged =
                        !imeSourceControl.getSurfacePosition().equals(lastSurfacePosition);
                final boolean leashChanged =
                        !haveSameLeash(mImeSourceControl, imeSourceControl);
                if (mAnimation != null) {
                    if (positionChanged) {
                        startAnimation(mImeShowing, true /* forceRestart */);
                    }
                } else {
                    if (leashChanged) {
                        applyVisibilityToLeash(imeSourceControl);
                    }
                    if (!mImeShowing) {
                        removeImeSurface();
                    }
                    if (mImeSourceControl != null) {
                        mImeSourceControl.release(SurfaceControl::release);
                    }
                }
                mImeSourceControl = imeSourceControl;
            }
        }

        private void applyVisibilityToLeash(InsetsSourceControl imeSourceControl) {
            SurfaceControl leash = imeSourceControl.getLeash();
            if (leash != null) {
                SurfaceControl.Transaction t = mTransactionPool.acquire();
                if (mImeShowing) {
                    t.show(leash);
                } else {
                    t.hide(leash);
                }
                t.apply();
                mTransactionPool.release(t);
            }
        }

        protected void showInsets(int types, boolean fromIme) {
            if ((types & WindowInsets.Type.ime()) == 0) {
                return;
            }
            if (DEBUG) Slog.d(TAG, "Got showInsets for ime");
            startAnimation(true /* show */, false /* forceRestart */);
        }


        protected void hideInsets(int types, boolean fromIme) {
            if ((types & WindowInsets.Type.ime()) == 0) {
                return;
            }
            if (DEBUG) Slog.d(TAG, "Got hideInsets for ime");
            startAnimation(false /* show */, false /* forceRestart */);
        }

        public void topFocusedWindowChanged(String packageName) {
            // Do nothing
        }

        /**
         * Sends the local visibility state back to window manager. Needed for legacy adjustForIme.
         */
        private void setVisibleDirectly(boolean visible) {
            mInsetsState.getSource(InsetsState.ITYPE_IME).setVisible(visible);
            try {
                mWmService.modifyDisplayWindowInsets(mDisplayId, mInsetsState);
            } catch (RemoteException e) {
            }
        }

        private int imeTop(float surfaceOffset) {
            return mImeFrame.top + (int) surfaceOffset;
        }

        private boolean calcIsFloating(InsetsSource imeSource) {
            final Rect frame = imeSource.getFrame();
            if (frame.height() == 0) {
                return true;
            }
            // Some Floating Input Methods will still report a frame, but the frame is actually
            // a nav-bar inset created by WM and not part of the IME (despite being reported as
            // an IME inset). For now, we assume that no non-floating IME will be <= this nav bar
            // frame height so any reported frame that is <= nav-bar frame height is assumed to
            // be floating.
            return frame.height() <= mDisplayController.getDisplayLayout(mDisplayId)
                    .navBarFrameHeight();
        }

        private void startAnimation(final boolean show, final boolean forceRestart) {
            final InsetsSource imeSource = mInsetsState.getSource(InsetsState.ITYPE_IME);
            if (imeSource == null || mImeSourceControl == null) {
                return;
            }
            final Rect newFrame = imeSource.getFrame();
            final boolean isFloating = calcIsFloating(imeSource) && show;
            if (isFloating) {
                // This is a "floating" or "expanded" IME, so to get animations, just
                // pretend the ime has some size just below the screen.
                mImeFrame.set(newFrame);
                final int floatingInset = (int) (mDisplayController.getDisplayLayout(mDisplayId)
                        .density() * FLOATING_IME_BOTTOM_INSET);
                mImeFrame.bottom -= floatingInset;
            } else if (newFrame.height() != 0) {
                // Don't set a new frame if it's empty and hiding -- this maintains continuity
                mImeFrame.set(newFrame);
            }
            if (DEBUG) {
                Slog.d(TAG, "Run startAnim  show:" + show + "  was:"
                        + (mAnimationDirection == DIRECTION_SHOW ? "SHOW"
                        : (mAnimationDirection == DIRECTION_HIDE ? "HIDE" : "NONE")));
            }
            if (!forceRestart && (mAnimationDirection == DIRECTION_SHOW && show)
                    || (mAnimationDirection == DIRECTION_HIDE && !show)) {
                return;
            }
            boolean seek = false;
            float seekValue = 0;
            if (mAnimation != null) {
                if (mAnimation.isRunning()) {
                    seekValue = (float) mAnimation.getAnimatedValue();
                    seek = true;
                }
                mAnimation.cancel();
            }
            final float defaultY = mImeSourceControl.getSurfacePosition().y;
            final float x = mImeSourceControl.getSurfacePosition().x;
            final float hiddenY = defaultY + mImeFrame.height();
            final float shownY = defaultY;
            final float startY = show ? hiddenY : shownY;
            final float endY = show ? shownY : hiddenY;
            if (mAnimationDirection == DIRECTION_NONE && mImeShowing && show) {
                // IME is already showing, so set seek to end
                seekValue = shownY;
                seek = true;
            }
            mAnimationDirection = show ? DIRECTION_SHOW : DIRECTION_HIDE;
            updateImeVisibility(show);
            mAnimation = ValueAnimator.ofFloat(startY, endY);
            mAnimation.setDuration(
                    show ? ANIMATION_DURATION_SHOW_MS : ANIMATION_DURATION_HIDE_MS);
            if (seek) {
                mAnimation.setCurrentFraction((seekValue - startY) / (endY - startY));
            }

            mAnimation.addUpdateListener(animation -> {
                SurfaceControl.Transaction t = mTransactionPool.acquire();
                float value = (float) animation.getAnimatedValue();
                t.setPosition(mImeSourceControl.getLeash(), x, value);
                final float alpha = (mAnimateAlpha || isFloating)
                        ? (value - hiddenY) / (shownY - hiddenY) : 1.f;
                t.setAlpha(mImeSourceControl.getLeash(), alpha);
                dispatchPositionChanged(mDisplayId, imeTop(value), t);
                t.apply();
                mTransactionPool.release(t);
            });
            mAnimation.setInterpolator(INTERPOLATOR);
            mAnimation.addListener(new AnimatorListenerAdapter() {
                private boolean mCancelled = false;

                @Override
                public void onAnimationStart(Animator animation) {
                    SurfaceControl.Transaction t = mTransactionPool.acquire();
                    t.setPosition(mImeSourceControl.getLeash(), x, startY);
                    if (DEBUG) {
                        Slog.d(TAG, "onAnimationStart d:" + mDisplayId + " top:"
                                + imeTop(hiddenY) + "->" + imeTop(shownY)
                                + " showing:" + (mAnimationDirection == DIRECTION_SHOW));
                    }
                    int flags = dispatchStartPositioning(mDisplayId, imeTop(hiddenY),
                            imeTop(shownY), mAnimationDirection == DIRECTION_SHOW, isFloating, t);
                    mAnimateAlpha = (flags & ImePositionProcessor.IME_ANIMATION_NO_ALPHA) == 0;
                    final float alpha = (mAnimateAlpha || isFloating)
                            ? (startY - hiddenY) / (shownY - hiddenY)
                            : 1.f;
                    t.setAlpha(mImeSourceControl.getLeash(), alpha);
                    if (mAnimationDirection == DIRECTION_SHOW) {
                        t.show(mImeSourceControl.getLeash());
                    }
                    t.apply();
                    mTransactionPool.release(t);
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (DEBUG) Slog.d(TAG, "onAnimationEnd " + mCancelled);
                    SurfaceControl.Transaction t = mTransactionPool.acquire();
                    if (!mCancelled) {
                        t.setPosition(mImeSourceControl.getLeash(), x, endY);
                        t.setAlpha(mImeSourceControl.getLeash(), 1.f);
                    }
                    dispatchEndPositioning(mDisplayId, mCancelled, t);
                    if (mAnimationDirection == DIRECTION_HIDE && !mCancelled) {
                        t.hide(mImeSourceControl.getLeash());
                        removeImeSurface();
                    }
                    t.apply();
                    mTransactionPool.release(t);

                    mAnimationDirection = DIRECTION_NONE;
                    mAnimation = null;
                }
            });
            if (!show) {
                // When going away, queue up insets change first, otherwise any bounds changes
                // can have a "flicker" of ime-provided insets.
                setVisibleDirectly(false /* visible */);
            }
            mAnimation.start();
            if (show) {
                // When showing away, queue up insets change last, otherwise any bounds changes
                // can have a "flicker" of ime-provided insets.
                setVisibleDirectly(true /* visible */);
            }
        }

        private void updateImeVisibility(boolean isShowing) {
            if (mImeShowing != isShowing) {
                mImeShowing = isShowing;
                dispatchVisibilityChanged(mDisplayId, isShowing);
            }
        }

        @VisibleForTesting
        @BinderThread
        public class DisplayWindowInsetsControllerImpl
                extends IDisplayWindowInsetsController.Stub {
            @Override
            public void topFocusedWindowChanged(String packageName) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.topFocusedWindowChanged(packageName);
                });
            }

            @Override
            public void insetsChanged(InsetsState insetsState) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.insetsChanged(insetsState);
                });
            }

            @Override
            public void insetsControlChanged(InsetsState insetsState,
                    InsetsSourceControl[] activeControls) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.insetsControlChanged(insetsState, activeControls);
                });
            }

            @Override
            public void showInsets(int types, boolean fromIme) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.showInsets(types, fromIme);
                });
            }

            @Override
            public void hideInsets(int types, boolean fromIme) throws RemoteException {
                mMainExecutor.execute(() -> {
                    PerDisplay.this.hideInsets(types, fromIme);
                });
            }
        }
    }

    void removeImeSurface() {
        final IInputMethodManager imms = getImms();
        if (imms != null) {
            try {
                // Remove the IME surface to make the insets invisible for
                // non-client controlled insets.
                imms.removeImeSurface();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to remove IME surface.", e);
            }
        }
    }

    /**
     * Allows other things to synchronize with the ime position
     */
    public interface ImePositionProcessor {
        /**
         * Indicates that ime shouldn't animate alpha. It will always be opaque. Used when stuff
         * behind the IME shouldn't be visible (for example during split-screen adjustment where
         * there is nothing behind the ime).
         */
        int IME_ANIMATION_NO_ALPHA = 1;

        /** @hide */
        @IntDef(prefix = {"IME_ANIMATION_"}, value = {
                IME_ANIMATION_NO_ALPHA,
        })
        @interface ImeAnimationFlags {
        }

        /**
         * Called when the IME position is starting to animate.
         *
         * @param hiddenTop  The y position of the top of the IME surface when it is hidden.
         * @param shownTop   The y position of the top of the IME surface when it is shown.
         * @param showing    {@code true} when we are animating from hidden to shown, {@code false}
         *                   when animating from shown to hidden.
         * @param isFloating {@code true} when the ime is a floating ime (doesn't inset).
         * @return flags that may alter how ime itself is animated (eg. no-alpha).
         */
        @ImeAnimationFlags
        default int onImeStartPositioning(int displayId, int hiddenTop, int shownTop,
                boolean showing, boolean isFloating, SurfaceControl.Transaction t) {
            return 0;
        }

        /**
         * Called when the ime position changed. This is expected to be a synchronous call on the
         * animation thread. Operations can be added to the transaction to be applied in sync.
         *
         * @param imeTop The current y position of the top of the IME surface.
         */
        default void onImePositionChanged(int displayId, int imeTop, SurfaceControl.Transaction t) {
        }

        /**
         * Called when the IME position is done animating.
         *
         * @param cancel {@code true} if this was cancelled. This implies another start is coming.
         */
        default void onImeEndPositioning(int displayId, boolean cancel,
                SurfaceControl.Transaction t) {
        }

        /**
         * Called when the IME control target changed. So that the processor can restore its
         * adjusted layout when the IME insets is not controlling by the current controller anymore.
         *
         * @param controlling indicates whether the current controller is controlling IME insets.
         */
        default void onImeControlTargetChanged(int displayId, boolean controlling) {
        }

        /**
         * Called when the IME visibility changed.
         *
         * @param isShowing {@code true} if the IME is shown.
         */
        default void onImeVisibilityChanged(int displayId, boolean isShowing) {

        }
    }

    public IInputMethodManager getImms() {
        return IInputMethodManager.Stub.asInterface(
                ServiceManager.getService(Context.INPUT_METHOD_SERVICE));
    }

    private static boolean haveSameLeash(InsetsSourceControl a, InsetsSourceControl b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.getLeash() == b.getLeash()) {
            return true;
        }
        if (a.getLeash() == null || b.getLeash() == null) {
            return false;
        }
        return a.getLeash().isSameSurface(b.getLeash());
    }
}
