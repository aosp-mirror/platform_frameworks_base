/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_APP_TRANSITIONS;
import static com.android.internal.protolog.ProtoLogGroup.WM_DEBUG_WALLPAPER;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.SparseArray;

import com.android.internal.protolog.ProtoLog;

import java.util.function.Consumer;

/**
 * A token that represents a set of wallpaper windows.
 */
class WallpaperWindowToken extends WindowToken {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "WallpaperWindowToken" : TAG_WM;

    private boolean mShowWhenLocked = false;
    float mWallpaperX = -1;
    float mWallpaperY = -1;
    float mWallpaperXStep = -1;
    float mWallpaperYStep = -1;
    int mWallpaperDisplayOffsetX = Integer.MIN_VALUE;
    int mWallpaperDisplayOffsetY = Integer.MIN_VALUE;

    /**
     * Map from {@link android.app.WallpaperManager.ScreenOrientation} to crop rectangles.
     * Crop rectangles represent the part of the wallpaper displayed for each screen orientation.
     */
    private SparseArray<Rect> mCropHints = new SparseArray<>();

    WallpaperWindowToken(WindowManagerService service, IBinder token, boolean explicit,
            DisplayContent dc, boolean ownerCanManageAppTokens) {
        this(service, token, explicit, dc, ownerCanManageAppTokens, null /* options */);
    }

    WallpaperWindowToken(WindowManagerService service, IBinder token, boolean explicit,
            DisplayContent dc, boolean ownerCanManageAppTokens, @Nullable Bundle options) {
        super(service, token, TYPE_WALLPAPER, explicit, dc, ownerCanManageAppTokens,
                false /* roundedCornerOverlay */, false /* fromClientToken */, options);
        dc.mWallpaperController.addWallpaperToken(this);
        setWindowingMode(WINDOWING_MODE_FULLSCREEN);
    }

    @Override
    WallpaperWindowToken asWallpaperToken() {
        return this;
    }

    @Override
    void setExiting(boolean animateExit) {
        super.setExiting(animateExit);
        mDisplayContent.mWallpaperController.removeWallpaperToken(this);
    }

    @Override
    public void prepareSurfaces() {
        super.prepareSurfaces();

        if (mWmService.mFlags.mEnsureWallpaperInTransitions) {
            // Similar to Task.prepareSurfaces, outside of transitions we need to apply visibility
            // changes directly. In transitions the transition player will take care of applying the
            // visibility change.
            if (!mTransitionController.inTransition(this)) {
                getSyncTransaction().setVisibility(mSurfaceControl, isVisible());
            }
        }
    }

    /**
     * Controls whether this wallpaper shows underneath the keyguard or is hidden and only
     * revealed once keyguard is dismissed.
     */
    void setShowWhenLocked(boolean showWhenLocked) {
        if (showWhenLocked == mShowWhenLocked) {
            return;
        }
        mShowWhenLocked = showWhenLocked;
        // Move the window token to the front (private) or back (showWhenLocked). This is possible
        // because the DisplayArea underneath TaskDisplayArea only contains TYPE_WALLPAPER windows.
        final int position = showWhenLocked ? POSITION_BOTTOM : POSITION_TOP;

        // Note: Moving all the way to the front or back breaks ordering based on addition times.
        // There should never have more than one non-animating token of each type.
        getParent().positionChildAt(position, this /* child */, false /*includingParents */);
        mDisplayContent.mWallpaperController.onWallpaperTokenReordered();
    }

    boolean canShowWhenLocked() {
        return mShowWhenLocked;
    }

    void setCropHints(SparseArray<Rect> cropHints) {
        mCropHints = cropHints.clone();
    }

    SparseArray<Rect> getCropHints() {
        return mCropHints;
    }

    void sendWindowWallpaperCommand(
            String action, int x, int y, int z, Bundle extras, boolean sync) {
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = mChildren.get(wallpaperNdx);
            try {
                wallpaper.mClient.dispatchWallpaperCommand(action, x, y, z, extras, sync);
                // We only want to be synchronous with one wallpaper.
                sync = false;
            } catch (RemoteException e) {
            }
        }
    }

    void updateWallpaperOffset(boolean sync) {
        final WallpaperController wallpaperController = mDisplayContent.mWallpaperController;
        for (int wallpaperNdx = mChildren.size() - 1; wallpaperNdx >= 0; wallpaperNdx--) {
            final WindowState wallpaper = mChildren.get(wallpaperNdx);
            if (wallpaperController.updateWallpaperOffset(wallpaper,
                    sync && !mWmService.mFlags.mWallpaperOffsetAsync)) {
                // We only want to be synchronous with one wallpaper.
                sync = false;
            }
        }
    }

    void updateWallpaperWindows(boolean visible) {
        if (mVisibleRequested != visible) {
            ProtoLog.d(WM_DEBUG_WALLPAPER, "Wallpaper token %s visible=%b",
                    token, visible);
            setVisibility(visible);
        }

        final WindowState wallpaperTarget =
                mDisplayContent.mWallpaperController.getWallpaperTarget();

        if (visible && wallpaperTarget != null) {
            if ((wallpaperTarget.mActivityRecord == null
                    // Ignore invisible activity because it may be moving to background.
                    || wallpaperTarget.mActivityRecord.isVisibleRequested())
                    && wallpaperTarget.mToken.hasFixedRotationTransform()) {
                // If the wallpaper target has a fixed rotation, we want the wallpaper to follow its
                // rotation
                linkFixedRotationTransform(wallpaperTarget.mToken);
            }
        }
        if (mTransitionController.inTransition(this)) {
            // If wallpaper is in transition, setVisible() will be called from commitVisibility()
            // when finishing transition. Otherwise commitVisibility() is already called from above
            // setVisibility().
            return;
        }

        setVisible(visible);
    }

    private void setVisible(boolean visible) {
        final boolean wasClientVisible = isClientVisible();
        setClientVisible(visible);
        if (visible && !wasClientVisible) {
            for (int i = mChildren.size() - 1; i >= 0; i--) {
                final WindowState wallpaper = mChildren.get(i);
                wallpaper.requestUpdateWallpaperIfNeeded();
            }
        }
    }

    /**
     * Sets the requested visibility of this token. The visibility may not be if this is part of a
     * transition. In that situation, make sure to call {@link #commitVisibility} when done.
     */
    void setVisibility(boolean visible) {
        if (mVisibleRequested != visible) {
            // Before setting mVisibleRequested so we can track changes.
            final WindowState wpTarget = mDisplayContent.mWallpaperController.getWallpaperTarget();
            final boolean isTargetNotCollectedActivity = wpTarget == null
                    || (wpTarget.mActivityRecord != null
                            && !mTransitionController.isCollecting(wpTarget.mActivityRecord));
            // Skip collecting requesting-invisible wallpaper if the wallpaper target is empty or
            // a non-collected activity. Because the visibility change may be called after the
            // transition of activity is finished, e.g. WallpaperController#hideWallpapers from
            // hiding surface of the target. Then if there is a next transition, the wallpaper
            // change may be collected into the unrelated transition and cause a weird animation.
            if (!isTargetNotCollectedActivity || visible) {
                mTransitionController.collect(this);
            }

            setVisibleRequested(visible);
        }

        // If in a transition, defer commits for activities that are going invisible
        if (!visible && (mTransitionController.inTransition()
                || getDisplayContent().mAppTransition.isRunning())) {
            return;
        }

        commitVisibility(visible);
    }

    /** Commits the visibility of this token. This will directly update the visibility. */
    void commitVisibility(boolean visible) {
        if (visible == isVisible()) return;

        ProtoLog.v(WM_DEBUG_APP_TRANSITIONS,
                "commitVisibility: %s: visible=%b mVisibleRequested=%b", this,
                isVisible(), mVisibleRequested);

        setVisibleRequested(visible);
        setVisible(visible);
    }

    boolean hasVisibleNotDrawnWallpaper() {
        if (!isVisible()) return false;
        for (int j = mChildren.size() - 1; j >= 0; --j) {
            final WindowState wallpaper = mChildren.get(j);
            if (!wallpaper.isDrawn() && wallpaper.isVisible()) {
                return true;
            }
        }
        return false;
    }

    @Override
    void forAllWallpaperWindows(Consumer<WallpaperWindowToken> callback) {
        callback.accept(this);
    }

    @Override
    boolean fillsParent() {
        return true;
    }

    @Override
    boolean showWallpaper() {
        return false;
    }

    @Override
    protected boolean setVisibleRequested(boolean visible) {
        if (!super.setVisibleRequested(visible)) return false;
        setInsetsFrozen(!visible);
        return true;
    }

    @Override
    protected boolean onChildVisibleRequestedChanged(@Nullable WindowContainer child) {
        // Wallpaper manages visibleRequested directly (it's not determined by children)
        return false;
    }

    @Override
    boolean isVisible() {
        return isClientVisible();
    }

    @Override
    boolean isSyncFinished(BLASTSyncEngine.SyncGroup group) {
        // TODO(b/233286785): Support sync state for wallpaper. See WindowState#prepareSync.
        return !mVisibleRequested || !hasVisibleNotDrawnWallpaper();
    }

    @Override
    public String toString() {
        if (stringName == null) {
            StringBuilder sb = new StringBuilder();
            sb.append("WallpaperWindowToken{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" token="); sb.append(token); sb.append('}');
            stringName = sb.toString();
        }
        return stringName;
    }
}
