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
import android.graphics.Rect;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;

import androidx.annotation.NonNull;

import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipSnapAlgorithm;

/**
 * Contains pip bounds calculations that are specific to TV.
 */
public class TvPipBoundsAlgorithm extends PipBoundsAlgorithm {

    private static final String TAG = TvPipBoundsAlgorithm.class.getSimpleName();
    private static final boolean DEBUG = TvPipController.DEBUG;

    private final @android.annotation.NonNull TvPipBoundsState mTvPipBoundsState;

    private int mFixedExpandedHeightInPx;
    private int mFixedExpandedWidthInPx;

    public TvPipBoundsAlgorithm(Context context,
            @NonNull TvPipBoundsState tvPipBoundsState,
            @NonNull PipSnapAlgorithm pipSnapAlgorithm) {
        super(context, tvPipBoundsState, pipSnapAlgorithm);
        this.mTvPipBoundsState = tvPipBoundsState;
    }

    @Override
    protected void reloadResources(Context context) {
        super.reloadResources(context);
        final Resources res = context.getResources();
        mFixedExpandedHeightInPx = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_pictureInPictureExpandedHorizontalHeight);
        mFixedExpandedWidthInPx = res.getDimensionPixelSize(
                com.android.internal.R.dimen.config_pictureInPictureExpandedVerticalWidth);
    }

    /** Returns the destination bounds to place the PIP window on entry. */
    @Override
    public Rect getEntryDestinationBounds() {
        if (DEBUG) Log.d(TAG, "getEntryDestinationBounds()");
        if (mTvPipBoundsState.getTvExpandedAspectRatio() != 0
                && !mTvPipBoundsState.isTvPipManuallyCollapsed()) {
            updatePositionOnExpandToggled(Gravity.NO_GRAVITY, true);
        }
        return getTvPipBounds(true);
    }

    /** Returns the current bounds adjusted to the new aspect ratio, if valid. */
    @Override
    public Rect getAdjustedDestinationBounds(Rect currentBounds, float newAspectRatio) {
        if (DEBUG) Log.d(TAG, "getAdjustedDestinationBounds: " + newAspectRatio);
        return getTvPipBounds(mTvPipBoundsState.isTvPipExpanded());
    }

    /**
     * The normal bounds at a different position on the screen.
     */
    public Rect getTvNormalBounds() {
        Rect normalBounds = getNormalBounds();
        Rect insetBounds = new Rect();
        getInsetBounds(insetBounds);

        if (mTvPipBoundsState.isImeShowing()) {
            if (DEBUG) Log.d(TAG, "IME showing, height: " + mTvPipBoundsState.getImeHeight());
            insetBounds.bottom -= mTvPipBoundsState.getImeHeight();
        }

        Rect result = new Rect();
        Gravity.apply(mTvPipBoundsState.getTvPipGravity(), normalBounds.width(),
                normalBounds.height(), insetBounds, result);

        if (DEBUG) {
            Log.d(TAG, "normalBounds: " + normalBounds.toShortString());
            Log.d(TAG, "insetBounds: " + insetBounds.toShortString());
            Log.d(TAG, "gravity: " + Gravity.toString(mTvPipBoundsState.getTvPipGravity()));
            Log.d(TAG, "resultBounds: " + result.toShortString());
        }

        mTvPipBoundsState.setTvPipExpanded(false);

        return result;
    }

    /**
     * @return previous gravity if it is to be saved, or Gravity.NO_GRAVITY if not.
     */
    int updatePositionOnExpandToggled(int previousGravity, boolean expanding) {
        if (DEBUG) {
            Log.d(TAG, "updatePositionOnExpandToggle(), expanding: " + expanding
                    + ", mOrientation: " + mTvPipBoundsState.getTvFixedPipOrientation()
                    + ", previous gravity: " + Gravity.toString(previousGravity));
        }

        if (!mTvPipBoundsState.isTvExpandedPipEnabled()) {
            return Gravity.NO_GRAVITY;
        }

        if (expanding && mTvPipBoundsState.getTvFixedPipOrientation() == ORIENTATION_UNDETERMINED) {
            float expandedRatio = mTvPipBoundsState.getTvExpandedAspectRatio();
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
        if (DEBUG) Log.d(TAG, "new gravity: " + Gravity.toString(updatedGravity));

        return gravityToSave;
    }

    /**
     * @return true if position changed
     */
    boolean updatePosition(int keycode) {
        if (DEBUG) Log.d(TAG, "updatePosition, keycode: " + keycode);

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
            if (DEBUG) Log.d(TAG, "new gravity: " + Gravity.toString(updatedGravity));
            return true;
        }
        return false;
    }

    /**
     * Calculates the PiP bounds.
     */
    public Rect getTvPipBounds(boolean expandedIfPossible) {
        if (DEBUG) {
            Log.d(TAG, "getExpandedBoundsIfPossible with gravity "
                    + Gravity.toString(mTvPipBoundsState.getTvPipGravity())
                    + ", fixed orientation: " + mTvPipBoundsState.getTvFixedPipOrientation());
        }

        if (!mTvPipBoundsState.isTvExpandedPipEnabled() || !expandedIfPossible) {
            return getTvNormalBounds();
        }

        DisplayLayout displayLayout = mTvPipBoundsState.getDisplayLayout();
        float expandedRatio = mTvPipBoundsState.getTvExpandedAspectRatio(); // width / height
        Size expandedSize;
        if (expandedRatio == 0) {
            Log.d(TAG, "Expanded mode not supported");
            return getTvNormalBounds();
        } else if (expandedRatio < 1) {
            // vertical
            if (mTvPipBoundsState.getTvFixedPipOrientation() == ORIENTATION_HORIZONTAL) {
                expandedSize = mTvPipBoundsState.getTvExpandedSize();
            } else {
                int maxHeight = displayLayout.height() - (2 * mScreenEdgeInsets.y);
                float aspectRatioHeight = mFixedExpandedWidthInPx / expandedRatio;

                if (maxHeight > aspectRatioHeight) {
                    if (DEBUG) Log.d(TAG, "Accommodate aspect ratio");
                    expandedSize = new Size(mFixedExpandedWidthInPx, (int) aspectRatioHeight);
                } else {
                    if (DEBUG) Log.d(TAG, "Aspect ratio is too extreme, use max size");
                    expandedSize = new Size(mFixedExpandedWidthInPx, maxHeight);
                }
            }
        } else {
            // horizontal
            if (mTvPipBoundsState.getTvFixedPipOrientation() == ORIENTATION_VERTICAL) {
                expandedSize = mTvPipBoundsState.getTvExpandedSize();
            } else {
                int maxWidth = displayLayout.width() - (2 * mScreenEdgeInsets.x);
                float aspectRatioWidth = mFixedExpandedHeightInPx * expandedRatio;
                if (maxWidth > aspectRatioWidth) {
                    if (DEBUG) Log.d(TAG, "Accommodate aspect ratio");
                    expandedSize = new Size((int) aspectRatioWidth, mFixedExpandedHeightInPx);
                } else {
                    if (DEBUG) Log.d(TAG, "Aspect ratio is too extreme, use max size");
                    expandedSize = new Size(maxWidth, mFixedExpandedHeightInPx);
                }
            }
        }

        if (expandedSize == null) {
            return getTvNormalBounds();
        }

        if (DEBUG) {
            Log.d(TAG, "expanded size, width: " + expandedSize.getWidth()
                    + ", height: " + expandedSize.getHeight());
        }

        Rect insetBounds = new Rect();
        getInsetBounds(insetBounds);

        Rect expandedBounds = new Rect();
        Gravity.apply(mTvPipBoundsState.getTvPipGravity(), expandedSize.getWidth(),
                expandedSize.getHeight(), insetBounds, expandedBounds);
        if (DEBUG) Log.d(TAG, "expanded bounds: " + expandedBounds.toShortString());

        mTvPipBoundsState.setTvExpandedSize(expandedSize);
        mTvPipBoundsState.setTvPipExpanded(true);
        return expandedBounds;
    }

}
