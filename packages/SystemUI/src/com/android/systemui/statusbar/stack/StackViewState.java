/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.view.View;

import com.android.systemui.statusbar.ExpandableView;

/**
* A state of an expandable view
*/
public class StackViewState extends ViewState {

    // These are flags such that we can create masks for filtering.

    public static final int LOCATION_UNKNOWN = 0x00;
    public static final int LOCATION_FIRST_CARD = 0x01;
    public static final int LOCATION_TOP_STACK_HIDDEN = 0x02;
    public static final int LOCATION_TOP_STACK_PEEKING = 0x04;
    public static final int LOCATION_MAIN_AREA = 0x08;
    public static final int LOCATION_BOTTOM_STACK_PEEKING = 0x10;
    public static final int LOCATION_BOTTOM_STACK_HIDDEN = 0x20;
    /** The view isn't layouted at all. */
    public static final int LOCATION_GONE = 0x40;

    public int height;
    public boolean dimmed;
    public boolean dark;
    public boolean hideSensitive;
    public boolean belowSpeedBump;

    /**
     * The amount which the view should be clipped from the top. This is calculated to
     * perceive consistent shadows.
     */
    public int clipTopAmount;

    /**
     * How much does the child overlap with the previous view on the top? Can be used for
     * a clipping optimization
     */
    public int topOverLap;

    /**
     * The index of the view, only accounting for views not equal to GONE
     */
    public int notGoneIndex;

    /**
     * The location this view is currently rendered at.
     *
     * <p>See <code>LOCATION_</code> flags.</p>
     */
    public int location;

    @Override
    public void copyFrom(ViewState viewState) {
        super.copyFrom(viewState);
        if (viewState instanceof StackViewState) {
            StackViewState svs = (StackViewState) viewState;
            height = svs.height;
            dimmed = svs.dimmed;
            dark = svs.dark;
            hideSensitive = svs.hideSensitive;
            belowSpeedBump = svs.belowSpeedBump;
            clipTopAmount = svs.clipTopAmount;
            topOverLap = svs.topOverLap;
            notGoneIndex = svs.notGoneIndex;
            location = svs.location;
        }
    }
}
