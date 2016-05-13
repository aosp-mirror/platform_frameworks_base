package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.DEBUG_DIM_LAYER;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;
import static com.android.server.wm.WindowManagerService.LAYER_OFFSET_DIM;

import android.graphics.Rect;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.TypedValue;

import com.android.server.wm.DimLayer.DimLayerUser;

import java.io.PrintWriter;

/**
 * Centralizes the control of dim layers used for
 * {@link android.view.WindowManager.LayoutParams#FLAG_DIM_BEHIND}
 * as well as other use cases (such as dimming above a dead window).
 */
class DimLayerController {
    private static final String TAG_LOCAL = "DimLayerController";
    private static final String TAG = TAG_WITH_CLASS_NAME ? TAG_LOCAL : TAG_WM;

    /** Amount of time in milliseconds to animate the dim surface from one value to another,
     * when no window animation is driving it. */
    private static final int DEFAULT_DIM_DURATION = 200;

    /**
     * The default amount of dim applied over a dead window
     */
    private static final float DEFAULT_DIM_AMOUNT_DEAD_WINDOW = 0.5f;

    // Shared dim layer for fullscreen users. {@link DimLayerState#dimLayer} will point to this
    // instead of creating a new object per fullscreen task on a display.
    private DimLayer mSharedFullScreenDimLayer;

    private ArrayMap<DimLayer.DimLayerUser, DimLayerState> mState = new ArrayMap<>();

    private DisplayContent mDisplayContent;

    private Rect mTmpBounds = new Rect();

    DimLayerController(DisplayContent displayContent) {
        mDisplayContent = displayContent;
    }

    /** Updates the dim layer bounds, recreating it if needed. */
    void updateDimLayer(DimLayer.DimLayerUser dimLayerUser) {
        final DimLayerState state = getOrCreateDimLayerState(dimLayerUser);
        final boolean previousFullscreen = state.dimLayer != null
                && state.dimLayer == mSharedFullScreenDimLayer;
        DimLayer newDimLayer;
        final int displayId = mDisplayContent.getDisplayId();
        if (dimLayerUser.dimFullscreen()) {
            if (previousFullscreen && mSharedFullScreenDimLayer != null) {
                // Update the bounds for fullscreen in case of rotation.
                mSharedFullScreenDimLayer.setBoundsForFullscreen();
                return;
            }
            // Use shared fullscreen dim layer
            newDimLayer = mSharedFullScreenDimLayer;
            if (newDimLayer == null) {
                if (state.dimLayer != null) {
                    // Re-purpose the previous dim layer.
                    newDimLayer = state.dimLayer;
                } else {
                    // Create new full screen dim layer.
                    newDimLayer = new DimLayer(mDisplayContent.mService, dimLayerUser, displayId,
                            getDimLayerTag(dimLayerUser));
                }
                dimLayerUser.getDimBounds(mTmpBounds);
                newDimLayer.setBounds(mTmpBounds);
                mSharedFullScreenDimLayer = newDimLayer;
            } else if (state.dimLayer != null) {
                state.dimLayer.destroySurface();
            }
        } else {
            newDimLayer = (state.dimLayer == null || previousFullscreen)
                    ? new DimLayer(mDisplayContent.mService, dimLayerUser, displayId,
                            getDimLayerTag(dimLayerUser))
                    : state.dimLayer;
            dimLayerUser.getDimBounds(mTmpBounds);
            newDimLayer.setBounds(mTmpBounds);
        }
        state.dimLayer = newDimLayer;
    }

    private static String getDimLayerTag(DimLayerUser dimLayerUser) {
        return TAG_LOCAL + "/" + dimLayerUser.toShortString();
    }

    private DimLayerState getOrCreateDimLayerState(DimLayer.DimLayerUser dimLayerUser) {
        if (DEBUG_DIM_LAYER) Slog.v(TAG, "getOrCreateDimLayerState, dimLayerUser="
                + dimLayerUser.toShortString());
        DimLayerState state = mState.get(dimLayerUser);
        if (state == null) {
            state = new DimLayerState();
            mState.put(dimLayerUser, state);
        }
        return state;
    }

    private void setContinueDimming(DimLayer.DimLayerUser dimLayerUser) {
        DimLayerState state = mState.get(dimLayerUser);
        if (state == null) {
            if (DEBUG_DIM_LAYER) Slog.w(TAG, "setContinueDimming, no state for: "
                    + dimLayerUser.toShortString());
            return;
        }
        state.continueDimming = true;
    }

    boolean isDimming() {
        for (int i = mState.size() - 1; i >= 0; i--) {
            DimLayerState state = mState.valueAt(i);
            if (state.dimLayer != null && state.dimLayer.isDimming()) {
                return true;
            }
        }
        return false;
    }

    void resetDimming() {
        for (int i = mState.size() - 1; i >= 0; i--) {
            mState.valueAt(i).continueDimming = false;
        }
    }

    private boolean getContinueDimming(DimLayer.DimLayerUser dimLayerUser) {
        DimLayerState state = mState.get(dimLayerUser);
        return state != null && state.continueDimming;
    }

    void startDimmingIfNeeded(DimLayer.DimLayerUser dimLayerUser,
            WindowStateAnimator newWinAnimator, boolean aboveApp) {
        // Only set dim params on the highest dimmed layer.
        // Don't turn on for an unshown surface, or for any layer but the highest dimmed layer.
        DimLayerState state = getOrCreateDimLayerState(dimLayerUser);
        state.dimAbove = aboveApp;
        if (DEBUG_DIM_LAYER) Slog.v(TAG, "startDimmingIfNeeded,"
                + " dimLayerUser=" + dimLayerUser.toShortString()
                + " newWinAnimator=" + newWinAnimator
                + " state.animator=" + state.animator);
        if (newWinAnimator.getShown() && (state.animator == null
                || !state.animator.getShown()
                || state.animator.mAnimLayer <= newWinAnimator.mAnimLayer)) {
            state.animator = newWinAnimator;
            if (state.animator.mWin.mAppToken == null && !dimLayerUser.dimFullscreen()) {
                // Dim should cover the entire screen for system windows.
                mDisplayContent.getLogicalDisplayRect(mTmpBounds);
            } else {
                dimLayerUser.getDimBounds(mTmpBounds);
            }
            state.dimLayer.setBounds(mTmpBounds);
        }
    }

    void stopDimmingIfNeeded() {
        if (DEBUG_DIM_LAYER) Slog.v(TAG, "stopDimmingIfNeeded, mState.size()=" + mState.size());
        for (int i = mState.size() - 1; i >= 0; i--) {
            DimLayer.DimLayerUser dimLayerUser = mState.keyAt(i);
            stopDimmingIfNeeded(dimLayerUser);
        }
    }

    private void stopDimmingIfNeeded(DimLayer.DimLayerUser dimLayerUser) {
        // No need to check if state is null, we know the key has a value.
        DimLayerState state = mState.get(dimLayerUser);
        if (DEBUG_DIM_LAYER) Slog.v(TAG, "stopDimmingIfNeeded,"
                + " dimLayerUser=" + dimLayerUser.toShortString()
                + " state.continueDimming=" + state.continueDimming
                + " state.dimLayer.isDimming=" + state.dimLayer.isDimming());
        if (state.animator != null && state.animator.mWin.mWillReplaceWindow) {
            return;
        }

        if (!state.continueDimming && state.dimLayer.isDimming()) {
            state.animator = null;
            dimLayerUser.getDimBounds(mTmpBounds);
            state.dimLayer.setBounds(mTmpBounds);
        }
    }

    boolean animateDimLayers() {
        int fullScreen = -1;
        int fullScreenAndDimming = -1;
        boolean result = false;

        for (int i = mState.size() - 1; i >= 0; i--) {
            DimLayer.DimLayerUser user = mState.keyAt(i);
            DimLayerState state = mState.valueAt(i);
            // We have to check that we are actually the shared fullscreen layer
            // for this path. If we began as non fullscreen and became fullscreen
            // (e.g. Docked stack closing), then we may not be the shared layer
            // and we have to make sure we always animate the layer.
            if (user.dimFullscreen() && state.dimLayer == mSharedFullScreenDimLayer) {
                fullScreen = i;
                if (mState.valueAt(i).continueDimming) {
                    fullScreenAndDimming = i;
                }
            } else {
                // We always want to animate the non fullscreen windows, they don't share their
                // dim layers.
                result |= animateDimLayers(user);
            }
        }
        // For the shared, full screen dim layer, we prefer the animation that is causing it to
        // appear.
        if (fullScreenAndDimming != -1) {
            result |= animateDimLayers(mState.keyAt(fullScreenAndDimming));
        } else if (fullScreen != -1) {
            // If there is no animation for the full screen dim layer to appear, we can use any of
            // the animators that will cause it to disappear.
            result |= animateDimLayers(mState.keyAt(fullScreen));
        }
        return result;
    }

    private boolean animateDimLayers(DimLayer.DimLayerUser dimLayerUser) {
        DimLayerState state = mState.get(dimLayerUser);
        if (DEBUG_DIM_LAYER) Slog.v(TAG, "animateDimLayers,"
                + " dimLayerUser=" + dimLayerUser.toShortString()
                + " state.animator=" + state.animator
                + " state.continueDimming=" + state.continueDimming);
        final int dimLayer;
        final float dimAmount;
        if (state.animator == null) {
            dimLayer = state.dimLayer.getLayer();
            dimAmount = 0;
        } else {
            if (state.dimAbove) {
                dimLayer = state.animator.mAnimLayer + LAYER_OFFSET_DIM;
                dimAmount = DEFAULT_DIM_AMOUNT_DEAD_WINDOW;
            } else {
                dimLayer = state.animator.mAnimLayer - LAYER_OFFSET_DIM;
                dimAmount = state.animator.mWin.mAttrs.dimAmount;
            }
        }
        final float targetAlpha = state.dimLayer.getTargetAlpha();
        if (targetAlpha != dimAmount) {
            if (state.animator == null) {
                state.dimLayer.hide(DEFAULT_DIM_DURATION);
            } else {
                long duration = (state.animator.mAnimating && state.animator.mAnimation != null)
                        ? state.animator.mAnimation.computeDurationHint()
                        : DEFAULT_DIM_DURATION;
                if (targetAlpha > dimAmount) {
                    duration = getDimLayerFadeDuration(duration);
                }
                state.dimLayer.show(dimLayer, dimAmount, duration);
            }
        } else if (state.dimLayer.getLayer() != dimLayer) {
            state.dimLayer.setLayer(dimLayer);
        }
        if (state.dimLayer.isAnimating()) {
            if (!mDisplayContent.mService.okToDisplay()) {
                // Jump to the end of the animation.
                state.dimLayer.show();
            } else {
                return state.dimLayer.stepAnimation();
            }
        }
        return false;
    }

    boolean isDimming(DimLayer.DimLayerUser dimLayerUser, WindowStateAnimator winAnimator) {
        DimLayerState state = mState.get(dimLayerUser);
        return state != null && state.animator == winAnimator && state.dimLayer.isDimming();
    }

    private long getDimLayerFadeDuration(long duration) {
        TypedValue tv = new TypedValue();
        mDisplayContent.mService.mContext.getResources().getValue(
                com.android.internal.R.fraction.config_dimBehindFadeDuration, tv, true);
        if (tv.type == TypedValue.TYPE_FRACTION) {
            duration = (long) tv.getFraction(duration, duration);
        } else if (tv.type >= TypedValue.TYPE_FIRST_INT && tv.type <= TypedValue.TYPE_LAST_INT) {
            duration = tv.data;
        }
        return duration;
    }

    void close() {
        for (int i = mState.size() - 1; i >= 0; i--) {
            DimLayerState state = mState.valueAt(i);
            state.dimLayer.destroySurface();
        }
        mState.clear();
        mSharedFullScreenDimLayer = null;
    }

    void removeDimLayerUser(DimLayer.DimLayerUser dimLayerUser) {
        DimLayerState state = mState.get(dimLayerUser);
        if (state != null) {
            // Destroy the surface, unless it's the shared fullscreen dim.
            if (state.dimLayer != mSharedFullScreenDimLayer) {
                state.dimLayer.destroySurface();
            }
            mState.remove(dimLayerUser);
        }
    }

    void applyDimBehind(DimLayer.DimLayerUser dimLayerUser, WindowStateAnimator animator) {
        applyDim(dimLayerUser, animator, false /* aboveApp */);
    }

    void applyDimAbove(DimLayer.DimLayerUser dimLayerUser, WindowStateAnimator animator) {
        applyDim(dimLayerUser, animator, true /* aboveApp */);
    }

    void applyDim(
            DimLayer.DimLayerUser dimLayerUser, WindowStateAnimator animator, boolean aboveApp) {
        if (dimLayerUser == null) {
            Slog.e(TAG, "Trying to apply dim layer for: " + this
                    + ", but no dim layer user found.");
            return;
        }
        if (!getContinueDimming(dimLayerUser)) {
            setContinueDimming(dimLayerUser);
            if (!isDimming(dimLayerUser, animator)) {
                if (DEBUG_DIM_LAYER) Slog.v(TAG, "Win " + this + " start dimming.");
                startDimmingIfNeeded(dimLayerUser, animator, aboveApp);
            }
        }
    }

    private static class DimLayerState {
        // The particular window requesting a dim layer. If null, hide dimLayer.
        WindowStateAnimator animator;
        // Set to false at the start of performLayoutAndPlaceSurfaces. If it is still false by the
        // end then stop any dimming.
        boolean continueDimming;
        DimLayer dimLayer;
        boolean dimAbove;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DimLayerController");
        final String doubleSpace = "  ";
        final String prefixPlusDoubleSpace = prefix + doubleSpace;

        for (int i = 0, n = mState.size(); i < n; i++) {
            pw.println(prefixPlusDoubleSpace + mState.keyAt(i).toShortString());
            DimLayerState state = mState.valueAt(i);
            pw.println(prefixPlusDoubleSpace + doubleSpace + "dimLayer="
                    + (state.dimLayer == mSharedFullScreenDimLayer ? "shared" : state.dimLayer)
                    + ", animator=" + state.animator + ", continueDimming=" + state.continueDimming);
            if (state.dimLayer != null) {
                state.dimLayer.printTo(prefixPlusDoubleSpace + doubleSpace, pw);
            }
        }
    }
}
