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

import java.util.Set;

/**
 * Calculates the adjusted position that does not occlude keep clear areas.
 */
public class PipKeepClearAlgorithm {

    /** Returns a new {@code Rect} that does not occlude the provided keep clear areas. */
    public Rect adjust(Rect defaultBounds, Set<Rect> restrictedKeepClearAreas,
            Set<Rect> unrestrictedKeepClearAreas) {
        if (restrictedKeepClearAreas.isEmpty()) {
            return defaultBounds;
        }
        // TODO(b/183746978): implement the adjustment algorithm
        // naively check if areas intersect, an if so move PiP upwards
        Rect outBounds = new Rect(defaultBounds);
        for (Rect r : restrictedKeepClearAreas) {
            if (r.intersect(outBounds)) {
                outBounds.offset(0, r.top - outBounds.bottom);
            }
        }
        return outBounds;
    }
}
