/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.pip.phone;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.util.ArraySet;
import android.view.Gravity;

import com.android.wm.shell.R;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipKeepClearAlgorithm;

import java.util.Set;

/**
 * Calculates the adjusted position that does not occlude keep clear areas.
 */
public class PhonePipKeepClearAlgorithm implements PipKeepClearAlgorithm {

    protected int mKeepClearAreasPadding;

    public PhonePipKeepClearAlgorithm(Context context) {
        reloadResources(context);
    }

    private void reloadResources(Context context) {
        final Resources res = context.getResources();
        mKeepClearAreasPadding = res.getDimensionPixelSize(R.dimen.pip_keep_clear_areas_padding);
    }

    /**
     * Adjusts the current position of PiP to avoid occluding keep clear areas. This will push PiP
     * towards the closest edge and then apply calculations to avoid occluding keep clear areas.
     */
    public Rect adjust(PipBoundsState pipBoundsState, PipBoundsAlgorithm pipBoundsAlgorithm) {
        Rect startingBounds = pipBoundsState.getBounds().isEmpty()
                ? pipBoundsAlgorithm.getEntryDestinationBoundsIgnoringKeepClearAreas()
                : pipBoundsState.getBounds();
        float snapFraction = pipBoundsAlgorithm.getSnapFraction(startingBounds);
        int verticalGravity = Gravity.BOTTOM;
        int horizontalGravity;
        if (snapFraction >= 0.5f && snapFraction < 2.5f) {
            horizontalGravity = Gravity.RIGHT;
        } else {
            horizontalGravity = Gravity.LEFT;
        }
        // push the bounds based on the gravity
        Rect insets = new Rect();
        pipBoundsAlgorithm.getInsetBounds(insets);
        if (pipBoundsState.isImeShowing()) {
            insets.bottom -= pipBoundsState.getImeHeight();
        }
        Rect pushedBounds = new Rect(startingBounds);
        if (verticalGravity == Gravity.BOTTOM) {
            pushedBounds.offsetTo(pushedBounds.left,
                    insets.bottom - pushedBounds.height());
        }
        if (horizontalGravity == Gravity.RIGHT) {
            pushedBounds.offsetTo(insets.right - pushedBounds.width(), pushedBounds.top);
        } else {
            pushedBounds.offsetTo(insets.left, pushedBounds.top);
        }
        return findUnoccludedPosition(pushedBounds, pipBoundsState.getRestrictedKeepClearAreas(),
                pipBoundsState.getUnrestrictedKeepClearAreas(), insets);
    }

    /** Returns a new {@code Rect} that does not occlude the provided keep clear areas. */
    public Rect findUnoccludedPosition(Rect defaultBounds, Set<Rect> restrictedKeepClearAreas,
            Set<Rect> unrestrictedKeepClearAreas, Rect allowedBounds) {
        if (restrictedKeepClearAreas.isEmpty() && unrestrictedKeepClearAreas.isEmpty()) {
            return defaultBounds;
        }
        Set<Rect> keepClearAreas = new ArraySet<>();
        if (!restrictedKeepClearAreas.isEmpty()) {
            keepClearAreas.addAll(restrictedKeepClearAreas);
        }
        if (!unrestrictedKeepClearAreas.isEmpty()) {
            keepClearAreas.addAll(unrestrictedKeepClearAreas);
        }
        Rect outBounds = new Rect(defaultBounds);
        for (Rect r : keepClearAreas) {
            Rect tmpRect = new Rect(r);
            // add extra padding to the keep clear area
            tmpRect.inset(-mKeepClearAreasPadding, -mKeepClearAreasPadding);
            if (Rect.intersects(r, outBounds)) {
                if (tryOffsetUp(outBounds, tmpRect, allowedBounds)) continue;
                if (tryOffsetLeft(outBounds, tmpRect, allowedBounds)) continue;
                if (tryOffsetDown(outBounds, tmpRect, allowedBounds)) continue;
                if (tryOffsetRight(outBounds, tmpRect, allowedBounds)) continue;
            }
        }
        return outBounds;
    }

    private static boolean tryOffsetLeft(Rect rectToMove, Rect rectToAvoid, Rect allowedBounds) {
        return tryOffset(rectToMove, rectToAvoid, allowedBounds,
                rectToAvoid.left - rectToMove.right, 0);
    }

    private static boolean tryOffsetRight(Rect rectToMove, Rect rectToAvoid, Rect allowedBounds) {
        return tryOffset(rectToMove, rectToAvoid, allowedBounds,
                rectToAvoid.right - rectToMove.left, 0);
    }

    private static boolean tryOffsetUp(Rect rectToMove, Rect rectToAvoid, Rect allowedBounds) {
        return tryOffset(rectToMove, rectToAvoid, allowedBounds,
                0, rectToAvoid.top - rectToMove.bottom);
    }

    private static boolean tryOffsetDown(Rect rectToMove, Rect rectToAvoid, Rect allowedBounds) {
        return tryOffset(rectToMove, rectToAvoid, allowedBounds,
                0, rectToAvoid.bottom - rectToMove.top);
    }

    private static boolean tryOffset(Rect rectToMove, Rect rectToAvoid, Rect allowedBounds,
            int dx, int dy) {
        Rect tmp = new Rect(rectToMove);
        tmp.offset(dx, dy);
        if (!Rect.intersects(rectToAvoid, tmp) && allowedBounds.contains(tmp)) {
            rectToMove.offsetTo(tmp.left, tmp.top);
            return true;
        }
        return false;
    }
}
