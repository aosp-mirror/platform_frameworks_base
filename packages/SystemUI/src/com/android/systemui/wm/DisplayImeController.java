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

package com.android.systemui.wm;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.IDisplayWindowInsetsController;
import android.view.InsetsSource;
import android.view.InsetsSourceControl;
import android.view.InsetsState;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.WindowInsets;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.systemui.TransactionPool;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.ArrayList;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manages IME control at the display-level. This occurs when IME comes up in multi-window mode.
 */
@Singleton
public class DisplayImeController implements DisplayController.OnDisplaysChangedListener {
    private static final String TAG = "DisplayImeController";

    private static final boolean DEBUG = false;

    public static final int ANIMATION_DURATION_SHOW_MS = 275;
    public static final int ANIMATION_DURATION_HIDE_MS = 340;
    public static final Interpolator INTERPOLATOR = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final int DIRECTION_NONE = 0;
    private static final int DIRECTION_SHOW = 1;
    private static final int DIRECTION_HIDE = 2;

    SystemWindows mSystemWindows;
    final Handler mHandler;
    final TransactionPool mTransactionPool;

    final SparseArray<PerDisplay> mImePerDisplay = new SparseArray<>();

    final ArrayList<ImePositionProcessor> mPositionProcessors = new ArrayList<>();

    @Inject
    public DisplayImeController(SystemWindows syswin, DisplayController displayController,
            @Main Handler mainHandler, TransactionPool transactionPool) {
        mHandler = mainHandler;
        mSystemWindows = syswin;
        mTransactionPool = transactionPool;
        displayController.addDisplayWindowListener(this);
    }

    @Override
    public void onDisplayAdded(int displayId) {
        // Add's a system-ui window-manager specifically for ime. This type is special because
        // WM will defer IME inset handling to it in multi-window scenarious.
        PerDisplay pd = new PerDisplay(displayId,
                mSystemWindows.mDisplayController.getDisplayLayout(displayId).rotation());
        try {
            mSystemWindows.mWmService.setDisplayWindowInsetsController(displayId, pd);
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to set insets controller on display " + displayId);
        }
        mImePerDisplay.put(displayId, pd);
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        PerDisplay pd = mImePerDisplay.get(displayId);
        if (pd == null) {
            return;
        }
        if (mSystemWindows.mDisplayController.getDisplayLayout(displayId).rotation()
                != pd.mRotation && isImeShowing(displayId)) {
            pd.startAnimation(true, false /* forceRestart */);
        }
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        try {
            mSystemWindows.mWmService.setDisplayWindowInsetsController(displayId, null);
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

    private void dispatchStartPositioning(int displayId, int hiddenTop, int shownTop,
            boolean show, SurfaceControl.Transaction t) {
        synchronized (mPositionProcessors) {
            for (ImePositionProcessor pp : mPositionProcessors) {
                pp.onImeStartPositioning(displayId, hiddenTop, shownTop, show, t);
            }
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

    class PerDisplay extends IDisplayWindowInsetsController.Stub {
        final int mDisplayId;
        final InsetsState mInsetsState = new InsetsState();
        InsetsSourceControl mImeSourceControl = null;
        int mAnimationDirection = DIRECTION_NONE;
        ValueAnimator mAnimation = null;
        int mRotation = Surface.ROTATION_0;
        boolean mImeShowing = false;

        PerDisplay(int displayId, int initialRotation) {
            mDisplayId = displayId;
            mRotation = initialRotation;
        }

        @Override
        public void insetsChanged(InsetsState insetsState) {
            if (mInsetsState.equals(insetsState)) {
                return;
            }

            final InsetsSource newSource = insetsState.getSource(InsetsState.ITYPE_IME);
            final Rect newFrame = newSource.getFrame();
            final Rect oldFrame = mInsetsState.getSource(InsetsState.ITYPE_IME).getFrame();

            mInsetsState.set(insetsState, true /* copySources */);
            if (mImeShowing && !newFrame.equals(oldFrame) && newSource.isVisible()) {
                if (DEBUG) Slog.d(TAG, "insetsChanged when IME showing, restart animation");
                startAnimation(mImeShowing, true /* forceRestart */);
            }
        }

        @Override
        public void insetsControlChanged(InsetsState insetsState,
                InsetsSourceControl[] activeControls) {
            insetsChanged(insetsState);
            if (activeControls != null) {
                for (InsetsSourceControl activeControl : activeControls) {
                    if (activeControl == null) {
                        continue;
                    }
                    if (activeControl.getType() == InsetsState.ITYPE_IME) {
                        mHandler.post(() -> {
                            final Point lastSurfacePosition = mImeSourceControl != null
                                    ? mImeSourceControl.getSurfacePosition() : null;
                            mImeSourceControl = activeControl;
                            if (!activeControl.getSurfacePosition().equals(lastSurfacePosition)
                                    && mAnimation != null) {
                                startAnimation(mImeShowing, true /* forceRestart */);
                            }
                        });
                    }
                }
            }
        }

        @Override
        public void showInsets(int types, boolean fromIme) {
            if ((types & WindowInsets.Type.ime()) == 0) {
                return;
            }
            if (DEBUG) Slog.d(TAG, "Got showInsets for ime");
            startAnimation(true /* show */, false /* forceRestart */);
        }

        @Override
        public void hideInsets(int types, boolean fromIme) {
            if ((types & WindowInsets.Type.ime()) == 0) {
                return;
            }
            if (DEBUG) Slog.d(TAG, "Got hideInsets for ime");
            startAnimation(false /* show */, false /* forceRestart */);
        }

        /**
         * Sends the local visibility state back to window manager. Needed for legacy adjustForIme.
         */
        private void setVisibleDirectly(boolean visible) {
            mInsetsState.getSource(InsetsState.ITYPE_IME).setVisible(visible);
            try {
                mSystemWindows.mWmService.modifyDisplayWindowInsets(mDisplayId, mInsetsState);
            } catch (RemoteException e) {
            }
        }

        private int imeTop(InsetsSource imeSource, float surfaceOffset) {
            return imeSource.getFrame().top + (int) surfaceOffset;
        }

        private void startAnimation(final boolean show, final boolean forceRestart) {
            final InsetsSource imeSource = mInsetsState.getSource(InsetsState.ITYPE_IME);
            if (imeSource == null || mImeSourceControl == null) {
                return;
            }
            mHandler.post(() -> {
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
                final float hiddenY = defaultY + imeSource.getFrame().height();
                final float shownY = defaultY;
                final float startY = show ? hiddenY : shownY;
                final float endY = show ? shownY : hiddenY;
                if (mAnimationDirection == DIRECTION_NONE && mImeShowing && show) {
                    // IME is already showing, so set seek to end
                    seekValue = shownY;
                    seek = true;
                }
                mAnimationDirection = show ? DIRECTION_SHOW : DIRECTION_HIDE;
                mImeShowing = show;
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
                    dispatchPositionChanged(mDisplayId, imeTop(imeSource, value), t);
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
                                    + imeTop(imeSource, hiddenY) + "->" + imeTop(imeSource, shownY)
                                    + " showing:" + (mAnimationDirection == DIRECTION_SHOW));
                        }
                        dispatchStartPositioning(mDisplayId, imeTop(imeSource, hiddenY),
                                imeTop(imeSource, shownY), mAnimationDirection == DIRECTION_SHOW,
                                t);
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
                        }
                        dispatchEndPositioning(mDisplayId, mCancelled, t);
                        if (mAnimationDirection == DIRECTION_HIDE && !mCancelled) {
                            t.hide(mImeSourceControl.getLeash());
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
            });
        }
    }

    /**
     * Allows other things to synchronize with the ime position
     */
    public interface ImePositionProcessor {
        /**
         * Called when the IME position is starting to animate.
         *
         * @param hiddenTop The y position of the top of the IME surface when it is hidden.
         * @param shownTop The y position of the top of the IME surface when it is shown.
         * @param showing {@code true} when we are animating from hidden to shown, {@code false}
         *                            when animating from shown to hidden.
         */
        default void onImeStartPositioning(int displayId, int hiddenTop, int shownTop,
                boolean showing, SurfaceControl.Transaction t) {}

        /**
         * Called when the ime position changed. This is expected to be a synchronous call on the
         * animation thread. Operations can be added to the transaction to be applied in sync.
         *
         * @param imeTop The current y position of the top of the IME surface.
         */
        default void onImePositionChanged(int displayId, int imeTop,
                SurfaceControl.Transaction t) {}

        /**
         * Called when the IME position is done animating.
         *
         * @param cancel {@code true} if this was cancelled. This implies another start is coming.
         */
        default void onImeEndPositioning(int displayId, boolean cancel,
                SurfaceControl.Transaction t) {}
    }
}
