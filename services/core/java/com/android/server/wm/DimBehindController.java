package com.android.server.wm;

import static com.android.server.wm.WindowManagerService.DEBUG_DIM_LAYER;

import android.graphics.Rect;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.TypedValue;

import java.io.PrintWriter;

/**
 * Centralizes the control of dim layers used for
 * {@link android.view.WindowManager.LayoutParams#FLAG_DIM_BEHIND}.
 */
class DimBehindController {
    private static final String TAG = "DimBehindController";

    /** Amount of time in milliseconds to animate the dim surface from one value to another,
     * when no window animation is driving it. */
    private static final int DEFAULT_DIM_DURATION = 200;

    // Shared dim layer for fullscreen users. {@link DimBehindState#dimLayer} will point to this
    // instead of creating a new object per fullscreen task on a display.
    private DimLayer mSharedFullScreenDimLayer;

    private ArrayMap<DimLayer.DimLayerUser, DimBehindState> mState = new ArrayMap<>();

    private DisplayContent mDisplayContent;

    private Rect mTmpBounds = new Rect();

    DimBehindController(DisplayContent displayContent) {
        mDisplayContent = displayContent;
    }

    /** Updates the dim layer bounds, recreating it if needed. */
    void updateDimLayer(DimLayer.DimLayerUser dimLayerUser) {
        DimBehindState state = getOrCreateDimBehindState(dimLayerUser);
        final boolean previousFullscreen = state.dimLayer != null
                && state.dimLayer == mSharedFullScreenDimLayer;
        DimLayer newDimLayer;
        final int displayId = mDisplayContent.getDisplayId();
        if (dimLayerUser.isFullscreen()) {
            if (previousFullscreen) {
                // Nothing to do here...
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
                    newDimLayer = new DimLayer(mDisplayContent.mService, dimLayerUser, displayId);
                }
                dimLayerUser.getBounds(mTmpBounds);
                newDimLayer.setBounds(mTmpBounds);
                mSharedFullScreenDimLayer = newDimLayer;
            } else if (state.dimLayer != null) {
                state.dimLayer. destroySurface();
            }
        } else {
            newDimLayer = (state.dimLayer == null || previousFullscreen)
                    ? new DimLayer(mDisplayContent.mService, dimLayerUser, displayId)
                    : state.dimLayer;
            dimLayerUser.getBounds(mTmpBounds);
            newDimLayer.setBounds(mTmpBounds);
        }
        state.dimLayer = newDimLayer;
    }

    private DimBehindState getOrCreateDimBehindState(DimLayer.DimLayerUser dimLayerUser) {
        if (DEBUG_DIM_LAYER) Slog.v(TAG, "getDimBehindState, dimLayerUser="
                + dimLayerUser.toShortString());
        DimBehindState state = mState.get(dimLayerUser);
        if (state == null) {
            state = new DimBehindState();
            mState.put(dimLayerUser, state);
        }
        return state;
    }

    private void setContinueDimming(DimLayer.DimLayerUser dimLayerUser) {
        DimBehindState state = mState.get(dimLayerUser);
        if (state == null) {
            if (DEBUG_DIM_LAYER) Slog.w(TAG, "setContinueDimming, no state for: "
                    + dimLayerUser.toShortString());
            return;
        }
        state.continueDimming = true;
    }

    boolean isDimming() {
        for (int i = mState.size() - 1; i >= 0; i--) {
            DimBehindState state = mState.valueAt(i);
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
        DimBehindState state = mState.get(dimLayerUser);
        return state != null && state.continueDimming;
    }

    void startDimmingIfNeeded(DimLayer.DimLayerUser dimLayerUser,
            WindowStateAnimator newWinAnimator) {
        // Only set dim params on the highest dimmed layer.
        // Don't turn on for an unshown surface, or for any layer but the highest dimmed layer.
        DimBehindState state = getOrCreateDimBehindState(dimLayerUser);
        if (DEBUG_DIM_LAYER) Slog.v(TAG, "startDimmingIfNeeded,"
                + " dimLayerUser=" + dimLayerUser.toShortString()
                + " newWinAnimator=" + newWinAnimator
                + " state.animator=" + state.animator);
        if (newWinAnimator.mSurfaceShown && (state.animator == null
                || !state.animator.mSurfaceShown
                || state.animator.mAnimLayer <= newWinAnimator.mAnimLayer)) {
            state.animator = newWinAnimator;
            if (state.animator.mWin.mAppToken == null && !dimLayerUser.isFullscreen()) {
                // Dim should cover the entire screen for system windows.
                mDisplayContent.getLogicalDisplayRect(mTmpBounds);
                state.dimLayer.setBounds(mTmpBounds);
            }
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
        DimBehindState state = mState.get(dimLayerUser);
        if (DEBUG_DIM_LAYER) Slog.v(TAG, "stopDimmingIfNeeded,"
                + " dimLayerUser=" + dimLayerUser.toShortString()
                + " state.continueDimming=" + state.continueDimming
                + " state.dimLayer.isDimming=" + state.dimLayer.isDimming());
        if (!state.continueDimming && state.dimLayer.isDimming()) {
            state.animator = null;
            dimLayerUser.getBounds(mTmpBounds);
            state.dimLayer.setBounds(mTmpBounds);
        }
    }

    boolean animateDimLayers() {
        int fullScreen = -1;
        for (int i = mState.size() - 1; i >= 0; i--) {
            DimLayer.DimLayerUser dimLayerUser = mState.keyAt(i);
            if (dimLayerUser.isFullscreen()) {
                fullScreen = i;
                if (mState.valueAt(i).continueDimming) {
                    break;
                }
            }
        }
        if (fullScreen != -1) {
            return animateDimLayers(mState.keyAt(fullScreen));
        } else {
            boolean result = false;
            for (int i = mState.size() - 1; i >= 0; i--) {
                result |= animateDimLayers(mState.keyAt(i));
            }
            return result;
        }
    }

    private boolean animateDimLayers(DimLayer.DimLayerUser dimLayerUser) {
        DimBehindState state = mState.get(dimLayerUser);
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
            dimLayer = state.animator.mAnimLayer - WindowManagerService.LAYER_OFFSET_DIM;
            dimAmount = state.animator.mWin.mAttrs.dimAmount;
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
                    duration = getDimBehindFadeDuration(duration);
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
        DimBehindState state = mState.get(dimLayerUser);
        return state != null && state.animator == winAnimator && state.dimLayer.isDimming();
    }

    private long getDimBehindFadeDuration(long duration) {
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
            DimBehindState state = mState.valueAt(i);
            state.dimLayer.destroySurface();
        }
        mState.clear();
        mSharedFullScreenDimLayer = null;
    }

    void removeDimLayerUser(DimLayer.DimLayerUser dimLayerUser) {
        mState.remove(dimLayerUser);
    }

    void applyDimBehind(DimLayer.DimLayerUser dimLayerUser, WindowStateAnimator animator) {
        if (dimLayerUser == null) {
            Slog.e(TAG, "Trying to apply dim layer for: " + this
                    + ", but no dim layer user found.");
            return;
        }
        if (!getContinueDimming(dimLayerUser)) {
            setContinueDimming(dimLayerUser);
            if (!isDimming(dimLayerUser, animator)) {
                if (DEBUG_DIM_LAYER) Slog.v(TAG, "Win " + this + " start dimming.");
                startDimmingIfNeeded(dimLayerUser, animator);
            }
        }
    }

    private static class DimBehindState {
        // The particular window with FLAG_DIM_BEHIND set. If null, hide dimLayer.
        WindowStateAnimator animator;
        // Set to false at the start of performLayoutAndPlaceSurfaces. If it is still false by the
        // end then stop any dimming.
        boolean continueDimming;
        DimLayer dimLayer;
    }

    void dump(String prefix, PrintWriter pw) {
        pw.println(prefix + "DimBehindController");
        for (int i = 0, n = mState.size(); i < n; i++) {
            pw.println(prefix + "  " + mState.keyAt(i).toShortString());
            pw.print(prefix + "    ");
            DimBehindState state = mState.valueAt(i);
            pw.print("dimLayer=" + (state.dimLayer == mSharedFullScreenDimLayer ? "shared" :
                    state.dimLayer));
            pw.print(", animator=" + state.animator);
            pw.println(", continueDimming=" + state.continueDimming + "}");

        }
    }
}
