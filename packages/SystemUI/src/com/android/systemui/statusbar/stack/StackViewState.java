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

/**
* A state of an expandable view
*/
public class StackViewState extends ViewState {

    // These are flags such that we can create masks for filtering.

    public static final int LOCATION_UNKNOWN = 0x00;
    public static final int LOCATION_FIRST_HUN = 0x01;
    public static final int LOCATION_HIDDEN_TOP = 0x02;
    public static final int LOCATION_MAIN_AREA = 0x04;
    public static final int LOCATION_BOTTOM_STACK_PEEKING = 0x08;
    public static final int LOCATION_BOTTOM_STACK_HIDDEN = 0x10;
    /** The view isn't layouted at all. */
    public static final int LOCATION_GONE = 0x40;
    /**
     * The visible locations of a view.
     */
    public static final int VISIBLE_LOCATIONS = LOCATION_FIRST_HUN | LOCATION_MAIN_AREA;

    public int height;
    public boolean dimmed;
    public boolean dark;
    public boolean hideSensitive;
    public boolean belowSpeedBump;
    public float shadowAlpha;

    /**
     * How much the child overlaps with the previous child on top. This is used to
     * show the background properly when the child on top is translating away.
     */
    public int clipTopAmount;

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

    /**
     * Whether a child in a group is being clipped at the bottom.
     */
    public boolean isBottomClipped;

    @Override
    public void copyFrom(ViewState viewState) {
        super.copyFrom(viewState);
        if (viewState instanceof StackViewState) {
            StackViewState svs = (StackViewState) viewState;
            height = svs.height;
            dimmed = svs.dimmed;
            shadowAlpha = svs.shadowAlpha;
            dark = svs.dark;
            hideSensitive = svs.hideSensitive;
            belowSpeedBump = svs.belowSpeedBump;
            clipTopAmount = svs.clipTopAmount;
            notGoneIndex = svs.notGoneIndex;
            location = svs.location;
            isBottomClipped = svs.isBottomClipped;
        }
    }
}
