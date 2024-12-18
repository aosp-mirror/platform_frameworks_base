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

package com.android.systemui.statusbar.notification.stack;

import android.util.Property;
import android.view.View;

import androidx.collection.ArraySet;

import java.util.ArrayList;

/**
 * Filters the animations for only a certain type of properties.
 */
public class AnimationFilter {
    public static final int NO_DELAY = -1;
    boolean animateAlpha;
    boolean animateX;
    public boolean animateY;
    boolean animateZ;
    boolean animateHeight;
    boolean animateTopInset;
    boolean animateHideSensitive;
    boolean hasDelays;
    boolean hasGoToFullShadeEvent;
    long customDelay;
    private ArraySet<Property> mAnimatedProperties = new ArraySet<>();

    public AnimationFilter animateAlpha() {
        animateAlpha = true;
        return this;
    }

    public AnimationFilter animateScale() {
        animate(View.SCALE_X);
        animate(View.SCALE_Y);
        return this;
    }

    public AnimationFilter animateX() {
        animateX = true;
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

    public AnimationFilter animateHeight() {
        animateHeight = true;
        return this;
    }

    public AnimationFilter animateTopInset() {
        animateTopInset = true;
        return this;
    }

    public AnimationFilter animateHideSensitive() {
        animateHideSensitive = true;
        return this;
    }

    public boolean shouldAnimateY(View view) {
        return animateY;
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
        }
    }

    public void combineFilter(AnimationFilter filter) {
        animateAlpha |= filter.animateAlpha;
        animateX |= filter.animateX;
        animateY |= filter.animateY;
        animateZ |= filter.animateZ;
        animateHeight |= filter.animateHeight;
        animateTopInset |= filter.animateTopInset;
        animateHideSensitive |= filter.animateHideSensitive;
        hasDelays |= filter.hasDelays;
        mAnimatedProperties.addAll(filter.mAnimatedProperties);
    }

    public void reset() {
        animateAlpha = false;
        animateX = false;
        animateY = false;
        animateZ = false;
        animateHeight = false;
        animateTopInset = false;
        animateHideSensitive = false;
        hasDelays = false;
        hasGoToFullShadeEvent = false;
        customDelay = NO_DELAY;
        mAnimatedProperties.clear();
    }

    public AnimationFilter animate(Property property) {
        mAnimatedProperties.add(property);
        return this;
    }

    public boolean shouldAnimateProperty(Property property) {
        // TODO: migrate all existing animators to properties
        return mAnimatedProperties.contains(property);
    }
}
