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
public class ExpandableViewState extends ViewState {

    // These are flags such that we can create masks for filtering.

    /**
     * No known location. This is the default and should not be set after an invocation of the
     * algorithm.
     */
    public static final int LOCATION_UNKNOWN = 0x00;

    /**
     * The location is the first heads up notification, so on the very top.
     */
    public static final int LOCATION_FIRST_HUN = 0x01;

    /**
     * The location is hidden / scrolled away on the top.
     */
    public static final int LOCATION_HIDDEN_TOP = 0x02;

    /**
     * The location is in the main area of the screen and visible.
     */
    public static final int LOCATION_MAIN_AREA = 0x04;

    /**
     * The location is in the bottom stack and it's peeking
     */
    public static final int LOCATION_BOTTOM_STACK_PEEKING = 0x08;

    /**
     * The location is in the bottom stack and it's hidden.
     */
    public static final int LOCATION_BOTTOM_STACK_HIDDEN = 0x10;

    /**
     * The view isn't layouted at all.
     * */
    public static final int LOCATION_GONE = 0x40;

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
        if (viewState instanceof ExpandableViewState) {
            ExpandableViewState svs = (ExpandableViewState) viewState;
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

    /**
     * Applies a {@link ExpandableViewState} to a {@link ExpandableView}.
     */
    @Override
    public void applyToView(View view) {
        super.applyToView(view);
        if (view instanceof ExpandableView) {
            ExpandableView expandableView = (ExpandableView) view;

            int height = expandableView.getActualHeight();
            int newHeight = this.height;

            // apply height
            if (height != newHeight) {
                expandableView.setActualHeight(newHeight, false /* notifyListeners */);
            }

            float shadowAlpha = expandableView.getShadowAlpha();
            float newShadowAlpha = this.shadowAlpha;

            // apply shadowAlpha
            if (shadowAlpha != newShadowAlpha) {
                expandableView.setShadowAlpha(newShadowAlpha);
            }

            // apply dimming
            expandableView.setDimmed(this.dimmed, false /* animate */);

            // apply hiding sensitive
            expandableView.setHideSensitive(
                    this.hideSensitive, false /* animated */, 0 /* delay */, 0 /* duration */);

            // apply below shelf speedBump
            expandableView.setBelowSpeedBump(this.belowSpeedBump);

            // apply dark
            expandableView.setDark(this.dark, false /* animate */, 0 /* delay */);

            // apply clipping
            float oldClipTopAmount = expandableView.getClipTopAmount();
            if (oldClipTopAmount != this.clipTopAmount) {
                expandableView.setClipTopAmount(this.clipTopAmount);
            }
        }
    }
}
