/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.stack;

import java.util.ArrayList;

/**
 * Filters the animations for only a certain type of properties.
 */
public class AnimationFilter {
    boolean animateAlpha;
    boolean animateY;
    boolean animateZ;
    boolean animateScale;
    boolean animateHeight;
    boolean animateTopInset;
    boolean animateDimmed;
    boolean animateDark;
    boolean animateHideSensitive;
    boolean hasDelays;
    boolean hasGoToFullShadeEvent;
    boolean hasDarkEvent;
    int darkAnimationOriginIndex;

    public AnimationFilter animateAlpha() {
        animateAlpha = true;
        return this;
    }

    public AnimationFilter animateY() {
        animateY = true;
        return this;
    }

    public AnimationFilter hasDelays() {
        hasDelays = true;
        return this;
    }

    public AnimationFilter animateZ() {
        animateZ = true;
        return this;
    }

    public AnimationFilter animateScale() {
        animateScale = true;
        return this;
    }

    public AnimationFilter animateHeight() {
        animateHeight = true;
        return this;
    }

    public AnimationFilter animateTopInset() {
        animateTopInset = true;
        return this;
    }

    public AnimationFilter animateDimmed() {
        animateDimmed = true;
        return this;
    }

    public AnimationFilter animateDark() {
        animateDark = true;
        return this;
    }

    public AnimationFilter animateHideSensitive() {
        animateHideSensitive = true;
        return this;
    }

    /**
     * Combines multiple filters into {@code this} filter, using or as the operand .
     *
     * @param events The animation events from the filters to combine.
     */
    public void applyCombination(ArrayList<NotificationStackScrollLayout.AnimationEvent> events) {
        reset();
        int size = events.size();
        for (int i = 0; i < size; i++) {
            NotificationStackScrollLayout.AnimationEvent ev = events.get(i);
            combineFilter(events.get(i).filter);
            if (ev.animationType ==
                    NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_GO_TO_FULL_SHADE) {
                hasGoToFullShadeEvent = true;
            }
            if (ev.animationType ==
                    NotificationStackScrollLayout.AnimationEvent.ANIMATION_TYPE_DARK) {
                hasDarkEvent = true;
                darkAnimationOriginIndex = ev.darkAnimationOriginIndex;
            }
        }
    }

    private void combineFilter(AnimationFilter filter) {
        animateAlpha |= filter.animateAlpha;
        animateY |= filter.animateY;
        animateZ |= filter.animateZ;
        animateScale |= filter.animateScale;
        animateHeight |= filter.animateHeight;
        animateTopInset |= filter.animateTopInset;
        animateDimmed |= filter.animateDimmed;
        animateDark |= filter.animateDark;
        animateHideSensitive |= filter.animateHideSensitive;
        hasDelays |= filter.hasDelays;
    }

    private void reset() {
        animateAlpha = false;
        animateY = false;
        animateZ = false;
        animateScale = false;
        animateHeight = false;
        animateTopInset = false;
        animateDimmed = false;
        animateDark = false;
        animateHideSensitive = false;
        hasDelays = false;
        hasGoToFullShadeEvent = false;
        hasDarkEvent = false;
        darkAnimationOriginIndex =
                NotificationStackScrollLayout.AnimationEvent.DARK_ANIMATION_ORIGIN_INDEX_ABOVE;
    }
}
