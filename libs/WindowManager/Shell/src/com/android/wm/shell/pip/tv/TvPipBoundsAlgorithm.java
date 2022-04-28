/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.pip.tv;

import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;

import static com.android.wm.shell.pip.tv.TvPipBoundsState.ORIENTATION_HORIZONTAL;
import static com.android.wm.shell.pip.tv.TvPipBoundsState.ORIENTATION_UNDETERMINED;
import static com.android.wm.shell.pip.tv.TvPipBoundsState.ORIENTATION_VERTICAL;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.ArraySet;
import android.util.Size;
import android.view.Gravity;

import androidx.annotation.NonNull;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipSnapAlgorithm;
import com.android.wm.shell.pip.tv.TvPipKeepClearAlgorithm.Placement;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.Set;

/**
 * Contains pip bounds calculations that are specific to TV.
 */
public class TvPipBoundsAlgorithm extends PipBoundsAlgorithm {

    private static final String TAG = TvPipBoundsAlgorithm.class.getSimpleName();
    private static final boolean DEBUG = TvPipController.DEBUG;

    private final @NonNull TvPipBoundsState mTvPipBoundsState;

    private int mFixedExpandedHeightInPx;
    private int mFixedExpandedWidthInPx;

    private final TvPipKeepClearAlgorithm mKeepClearAlgorithm;

    public TvPipBoundsAlgorithm(Context context,
            @NonNull TvPipBoundsState tvPipBoundsState,
            @NonNull PipSnapAlgorithm pipSnapAlgorithm) {
        super(context, tvPipBoundsState, pipSnapAlgorithm);
        this.mTvPipBoundsState = tvPipBoundsState;
        this.mKeepClearAlgorithm = new TvPipKeepClearAlgorithm(SystemClock::uptimeMillis);
        reloadResources(context);
    }

    private void reloadResources(Context context) {
        final Resources res = context.getResources();
        mFixedExpandedHeightInPx = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_pictureInPictureExpandedHorizontalHeight);
        mFixedExpandedWidthInPx = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_pictureInPictureExpandedVerticalWidth);
        mKeepClearAlgorithm.setPipAreaPadding(
                res.getDimensionPixelSize(R.dimen.pip_keep_clear_area_padding));
        mKeepClearAlgorithm.setMaxRestrictedDistanceFraction(
                res.getFraction(R.fraction.config_pipMaxRestrictedMoveDistance, 1, 1));
        mKeepClearAlgorithm.setStashDuration(res.getInteger(R.integer.config_pipStashDuration));
    }

    @Override
    public void onConfigurationChanged(Context context) {
        super.onConfigurationChanged(context);
        reloadResources(context);
    }

    /** Returns the destination bounds to place the PIP window on entry. */
    @Override
    public Rect getEntryDestinationBounds() {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: getEntryDestinationBounds()", TAG);
        }
        updateExpandedPipSize();
        final boolean isPipExpanded = mTvPipBoundsState.isTvExpandedPipSupported()
                && mTvPipBoundsState.getDesiredTvExpandedAspectRatio() != 0
                && !mTvPipBoundsState.isTvPipManuallyCollapsed();
        if (isPipExpanded) {
            updateGravityOnExpandToggled(Gravity.NO_GRAVITY, true);
        }
        mTvPipBoundsState.setTvPipExpanded(isPipExpanded);
        return getTvPipBounds().getBounds();
    }

    /** Returns the current bounds adjusted to the new aspect ratio, if valid. */
    @Override
    public Rect getAdjustedDestinationBounds(Rect currentBounds, float newAspectRatio) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: getAdjustedDestinationBounds: %f", TAG, newAspectRatio);
        }
        return getTvPipBounds().getBounds();
    }

    /**
     * Calculates the PiP bounds.
     */
    public Placement getTvPipBounds() {
        final Size pipSize = getPipSize();
        final Rect displayBounds = mTvPipBoundsState.getDisplayBounds();
        final Size screenSize = new Size(displayBounds.width(), displayBounds.height());
        final Rect insetBounds = new Rect();
        getInsetBounds(insetBounds);

        Set<Rect> restrictedKeepClearAreas = mTvPipBoundsState.getRestrictedKeepClearAreas();
        Set<Rect> unrestrictedKeepClearAreas = mTvPipBoundsState.getUnrestrictedKeepClearAreas();

        if (mTvPipBoundsState.isImeShowing()) {
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: IME showing, height: %d",
                        TAG, mTvPipBoundsState.getImeHeight());
            }

            final Rect imeBounds = new Rect(
                    0,
                    insetBounds.bottom - mTvPipBoundsState.getImeHeight(),
                    insetBounds.right,
                    insetBounds.bottom);

            unrestrictedKeepClearAreas = new ArraySet<>(unrestrictedKeepClearAreas);
            unrestrictedKeepClearAreas.add(imeBounds);
        }

        mKeepClearAlgorithm.setGravity(mTvPipBoundsState.getTvPipGravity());
        mKeepClearAlgorithm.setScreenSize(screenSize);
        mKeepClearAlgorithm.setMovementBounds(insetBounds);
        mKeepClearAlgorithm.setStashOffset(mTvPipBoundsState.getStashOffset());
        mKeepClearAlgorithm.setPipPermanentDecorInsets(
                mTvPipBoundsState.getPipMenuPermanentDecorInsets());
        mKeepClearAlgorithm.setPipTemporaryDecorInsets(
                mTvPipBoundsState.getPipMenuTemporaryDecorInsets());

        final Placement placement = mKeepClearAlgorithm.calculatePipPosition(
                pipSize,
                restrictedKeepClearAreas,
                unrestrictedKeepClearAreas);

        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: screenSize: %s", TAG, screenSize);
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: stashOffset: %d", TAG, mTvPipBoundsState.getStashOffset());
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: insetBounds: %s", TAG, insetBounds.toShortString());
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: pipSize: %s", TAG, pipSize);
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: gravity: %s", TAG, Gravity.toString(mTvPipBoundsState.getTvPipGravity()));
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: restrictedKeepClearAreas: %s", TAG, restrictedKeepClearAreas);
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: unrestrictedKeepClearAreas: %s", TAG, unrestrictedKeepClearAreas);
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: placement: %s", TAG, placement);
        }

        return placement;
    }

    /**
     * @return previous gravity if it is to be saved, or {@link Gravity#NO_GRAVITY} if not.
     */
    int updateGravityOnExpandToggled(int previousGravity, boolean expanding) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: updateGravityOnExpandToggled(), expanding: %b"
                    + ", mOrientation: %d, previous gravity: %s",
                    TAG, expanding, mTvPipBoundsState.getTvFixedPipOrientation(),
                    Gravity.toString(previousGravity));
        }

        if (!mTvPipBoundsState.isTvExpandedPipSupported()) {
            return Gravity.NO_GRAVITY;
        }

        if (expanding && mTvPipBoundsState.getTvFixedPipOrientation() == ORIENTATION_UNDETERMINED) {
            float expandedRatio = mTvPipBoundsState.getDesiredTvExpandedAspectRatio();
            if (expandedRatio == 0) {
                return Gravity.NO_GRAVITY;
            }
            if (expandedRatio < 1) {
                mTvPipBoundsState.setTvFixedPipOrientation(ORIENTATION_VERTICAL);
            } else {
                mTvPipBoundsState.setTvFixedPipOrientation(ORIENTATION_HORIZONTAL);
            }
        }

        int gravityToSave = Gravity.NO_GRAVITY;
        int currentGravity = mTvPipBoundsState.getTvPipGravity();
        int updatedGravity;

        if (expanding) {
            // save collapsed gravity
            gravityToSave = mTvPipBoundsState.getTvPipGravity();

            if (mTvPipBoundsState.getTvFixedPipOrientation() == ORIENTATION_HORIZONTAL) {
                updatedGravity =
                        Gravity.CENTER_HORIZONTAL | (currentGravity
                                & Gravity.VERTICAL_GRAVITY_MASK);
            } else {
                updatedGravity =
                        Gravity.CENTER_VERTICAL | (currentGravity
                                & Gravity.HORIZONTAL_GRAVITY_MASK);
            }
        } else {
            if (previousGravity != Gravity.NO_GRAVITY) {
                // The pip hasn't been moved since expanding,
                // go back to previous collapsed position.
                updatedGravity = previousGravity;
            } else {
                if (mTvPipBoundsState.getTvFixedPipOrientation() == ORIENTATION_HORIZONTAL) {
                    updatedGravity =
                            Gravity.RIGHT | (currentGravity & Gravity.VERTICAL_GRAVITY_MASK);
                } else {
                    updatedGravity =
                            Gravity.BOTTOM | (currentGravity & Gravity.HORIZONTAL_GRAVITY_MASK);
                }
            }
        }
        mTvPipBoundsState.setTvPipGravity(updatedGravity);
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: new gravity: %s", TAG, Gravity.toString(updatedGravity));
        }

        return gravityToSave;
    }

    /**
     * @return true if gravity changed
     */
    boolean updateGravity(int keycode) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: updateGravity, keycode: %d", TAG, keycode);
        }

        // Check if position change is valid
        if (mTvPipBoundsState.isTvPipExpanded()) {
            int mOrientation = mTvPipBoundsState.getTvFixedPipOrientation();
            if (mOrientation == ORIENTATION_VERTICAL
                    && (keycode == KEYCODE_DPAD_UP || keycode == KEYCODE_DPAD_DOWN)
                    || mOrientation == ORIENTATION_HORIZONTAL
                    && (keycode == KEYCODE_DPAD_RIGHT || keycode == KEYCODE_DPAD_LEFT)) {
                return false;
            }
        }

        int currentGravity = mTvPipBoundsState.getTvPipGravity();
        int updatedGravity;
        // First axis
        switch (keycode) {
            case KEYCODE_DPAD_UP:
                updatedGravity = Gravity.TOP;
                break;
            case KEYCODE_DPAD_DOWN:
                updatedGravity = Gravity.BOTTOM;
                break;
            case KEYCODE_DPAD_LEFT:
                updatedGravity = Gravity.LEFT;
                break;
            case KEYCODE_DPAD_RIGHT:
                updatedGravity = Gravity.RIGHT;
                break;
            default:
                updatedGravity = currentGravity;
        }

        // Second axis
        switch (keycode) {
            case KEYCODE_DPAD_UP:
            case KEYCODE_DPAD_DOWN:
                if (mTvPipBoundsState.isTvPipExpanded()) {
                    updatedGravity |= Gravity.CENTER_HORIZONTAL;
                } else {
                    updatedGravity |= (currentGravity & Gravity.HORIZONTAL_GRAVITY_MASK);
                }
                break;
            case KEYCODE_DPAD_LEFT:
            case KEYCODE_DPAD_RIGHT:
                if (mTvPipBoundsState.isTvPipExpanded()) {
                    updatedGravity |= Gravity.CENTER_VERTICAL;
                } else {
                    updatedGravity |= (currentGravity & Gravity.VERTICAL_GRAVITY_MASK);
                }
                break;
            default:
                break;
        }

        if (updatedGravity != currentGravity) {
            mTvPipBoundsState.setTvPipGravity(updatedGravity);
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: new gravity: %s", TAG, Gravity.toString(updatedGravity));
            }
            return true;
        }
        return false;
    }

    private Size getPipSize() {
        final boolean isExpanded =
                mTvPipBoundsState.isTvExpandedPipSupported() && mTvPipBoundsState.isTvPipExpanded()
                        && mTvPipBoundsState.getDesiredTvExpandedAspectRatio() != 0;
        if (isExpanded) {
            return mTvPipBoundsState.getTvExpandedSize();
        } else {
            final Rect normalBounds = getNormalBounds();
            return new Size(normalBounds.width(), normalBounds.height());
        }
    }

    /**
     * Updates {@link TvPipBoundsState#getTvExpandedSize()} based on
     * {@link TvPipBoundsState#getDesiredTvExpandedAspectRatio()}, the screen size.
     */
    void updateExpandedPipSize() {
        final DisplayLayout displayLayout = mTvPipBoundsState.getDisplayLayout();
        final float expandedRatio =
                mTvPipBoundsState.getDesiredTvExpandedAspectRatio(); // width / height
        final Insets pipDecorations = mTvPipBoundsState.getPipMenuPermanentDecorInsets();

        final Size expandedSize;
        if (expandedRatio == 0) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                       "%s: updateExpandedPipSize(): Expanded mode aspect ratio"
                               + " of 0 not supported", TAG);
            return;
        } else if (expandedRatio < 1) {
            // vertical
            if (mTvPipBoundsState.getTvFixedPipOrientation() == ORIENTATION_HORIZONTAL) {
                expandedSize = mTvPipBoundsState.getTvExpandedSize();
            } else {
                int maxHeight = displayLayout.height() - (2 * mScreenEdgeInsets.y)
                        - pipDecorations.top - pipDecorations.bottom;
                float aspectRatioHeight = mFixedExpandedWidthInPx / expandedRatio;

                if (maxHeight > aspectRatioHeight) {
                    if (DEBUG) {
                        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                                "%s: Accommodate aspect ratio", TAG);
                    }
                    expandedSize = new Size(mFixedExpandedWidthInPx, (int) aspectRatioHeight);
                } else {
                    if (DEBUG) {
                        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                                "%s: Aspect ratio is too extreme, use max size", TAG);
                    }
                    expandedSize = new Size(mFixedExpandedWidthInPx, maxHeight);
                }
            }
        } else {
            // horizontal
            if (mTvPipBoundsState.getTvFixedPipOrientation() == ORIENTATION_VERTICAL) {
                expandedSize = mTvPipBoundsState.getTvExpandedSize();
            } else {
                int maxWidth = displayLayout.width() - (2 * mScreenEdgeInsets.x)
                        - pipDecorations.left - pipDecorations.right;
                float aspectRatioWidth = mFixedExpandedHeightInPx * expandedRatio;
                if (maxWidth > aspectRatioWidth) {
                    if (DEBUG) {
                        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                                "%s: Accommodate aspect ratio", TAG);
                    }
                    expandedSize = new Size((int) aspectRatioWidth, mFixedExpandedHeightInPx);
                } else {
                    if (DEBUG) {
                        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                                "%s: Aspect ratio is too extreme, use max size", TAG);
                    }
                    expandedSize = new Size(maxWidth, mFixedExpandedHeightInPx);
                }
            }
        }

        mTvPipBoundsState.setTvExpandedSize(expandedSize);
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                       "%s: updateExpandedPipSize(): expanded size, width: %d, height: %d",
                    TAG, expandedSize.getWidth(), expandedSize.getHeight());
        }
    }

    void keepUnstashedForCurrentKeepClearAreas() {
        mKeepClearAlgorithm.keepUnstashedForCurrentKeepClearAreas();
    }
}
