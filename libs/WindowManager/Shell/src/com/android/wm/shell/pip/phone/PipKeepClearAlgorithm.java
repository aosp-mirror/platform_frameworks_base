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

import android.graphics.Rect;
import android.util.ArraySet;

import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;

import java.util.Set;

/**
 * Calculates the adjusted position that does not occlude keep clear areas.
 */
public class PipKeepClearAlgorithm {

    /**
     * Adjusts the current position of PiP to avoid occluding keep clear areas. If the user has
     * moved PiP manually, the unmodified current position will be returned instead.
     */
    public Rect adjust(PipBoundsState boundsState, PipBoundsAlgorithm boundsAlgorithm) {
        if (boundsState.hasUserResizedPip()) {
            return boundsState.getBounds();
        }
        return adjust(boundsAlgorithm.getEntryDestinationBounds(),
                boundsState.getRestrictedKeepClearAreas(),
                boundsState.getUnrestrictedKeepClearAreas(), boundsState.getDisplayBounds());
    }

    /** Returns a new {@code Rect} that does not occlude the provided keep clear areas. */
    public Rect adjust(Rect defaultBounds, Set<Rect> restrictedKeepClearAreas,
            Set<Rect> unrestrictedKeepClearAreas, Rect displayBounds) {
        if (restrictedKeepClearAreas.isEmpty()) {
            return defaultBounds;
        }
        Set<Rect> keepClearAreas = new ArraySet<>();
        if (!restrictedKeepClearAreas.isEmpty()) {
            keepClearAreas.addAll(restrictedKeepClearAreas);
        }
        Rect outBounds = new Rect(defaultBounds);
        for (Rect r : keepClearAreas) {
            if (Rect.intersects(r, outBounds)) {
                if (tryOffsetUp(outBounds, r, displayBounds)) continue;
                if (tryOffsetLeft(outBounds, r, displayBounds)) continue;
                if (tryOffsetDown(outBounds, r, displayBounds)) continue;
                if (tryOffsetRight(outBounds, r, displayBounds)) continue;
            }
        }
        return outBounds;
    }

    private boolean tryOffsetLeft(Rect rectToMove, Rect rectToAvoid, Rect displayBounds) {
        return tryOffset(rectToMove, rectToAvoid, displayBounds,
                rectToAvoid.left - rectToMove.right, 0);
    }

    private boolean tryOffsetRight(Rect rectToMove, Rect rectToAvoid, Rect displayBounds) {
        return tryOffset(rectToMove, rectToAvoid, displayBounds,
                rectToAvoid.right - rectToMove.left, 0);
    }

    private boolean tryOffsetUp(Rect rectToMove, Rect rectToAvoid, Rect displayBounds) {
        return tryOffset(rectToMove, rectToAvoid, displayBounds,
                0, rectToAvoid.top - rectToMove.bottom);
    }

    private boolean tryOffsetDown(Rect rectToMove, Rect rectToAvoid, Rect displayBounds) {
        return tryOffset(rectToMove, rectToAvoid, displayBounds,
                0, rectToAvoid.bottom - rectToMove.top);
    }

    private boolean tryOffset(Rect rectToMove, Rect rectToAvoid, Rect displayBounds,
            int dx, int dy) {
        Rect tmp = new Rect(rectToMove);
        tmp.offset(dx, dy);
        if (!Rect.intersects(rectToAvoid, tmp) && displayBounds.contains(tmp)) {
            rectToMove.offsetTo(tmp.left, tmp.top);
            return true;
        }
        return false;
    }
}
