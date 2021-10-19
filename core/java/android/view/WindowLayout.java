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

package android.view;

import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import android.graphics.Insets;
import android.graphics.Rect;

/**
 * Computes window frames.
 * @hide
 */
public class WindowLayout {
    private final Rect mTempDisplayCutoutSafeExceptMaybeBarsRect = new Rect();
    private final Rect mTempRect = new Rect();

    public boolean computeWindowFrames(WindowManager.LayoutParams attrs, InsetsState state,
            Rect displayCutoutSafe, Rect windowBounds, InsetsVisibilities requestedVisibilities,
            Rect attachedWindowFrame, Rect outDisplayFrame, Rect outParentFrame) {
        final int type = attrs.type;
        final int fl = attrs.flags;
        final int pfl = attrs.privateFlags;
        final boolean layoutInScreen = (fl & FLAG_LAYOUT_IN_SCREEN) == FLAG_LAYOUT_IN_SCREEN;

        // Compute bounds restricted by insets
        final Insets insets = state.calculateInsets(windowBounds, attrs.getFitInsetsTypes(),
                attrs.isFitInsetsIgnoringVisibility());
        final @WindowInsets.Side.InsetsSide int sides = attrs.getFitInsetsSides();
        final int left = (sides & WindowInsets.Side.LEFT) != 0 ? insets.left : 0;
        final int top = (sides & WindowInsets.Side.TOP) != 0 ? insets.top : 0;
        final int right = (sides & WindowInsets.Side.RIGHT) != 0 ? insets.right : 0;
        final int bottom = (sides & WindowInsets.Side.BOTTOM) != 0 ? insets.bottom : 0;
        outDisplayFrame.set(windowBounds.left + left, windowBounds.top + top,
                windowBounds.right - right, windowBounds.bottom - bottom);

        if (attachedWindowFrame == null) {
            outParentFrame.set(outDisplayFrame);
            if ((pfl & PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME) != 0) {
                final InsetsSource source = state.peekSource(ITYPE_IME);
                if (source != null) {
                    outParentFrame.inset(source.calculateInsets(
                            outParentFrame, false /* ignoreVisibility */));
                }
            }
        } else {
            outParentFrame.set(!layoutInScreen ? attachedWindowFrame : outDisplayFrame);
        }

        // Compute bounds restricted by display cutout
        final DisplayCutout cutout = state.getDisplayCutout();
        if (cutout.isEmpty()) {
            return false;
        }
        boolean clippedByDisplayCutout = false;
        final Rect displayCutoutSafeExceptMaybeBars = mTempDisplayCutoutSafeExceptMaybeBarsRect;
        displayCutoutSafeExceptMaybeBars.set(displayCutoutSafe);

        // Ensure that windows with a non-ALWAYS display cutout mode are laid out in
        // the cutout safe zone.
        final int cutoutMode = attrs.layoutInDisplayCutoutMode;
        if (cutoutMode != LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS) {
            final Rect displayFrame = state.getDisplayFrame();
            final InsetsSource statusBarSource = state.peekSource(ITYPE_STATUS_BAR);
            if (statusBarSource != null && displayCutoutSafe.top > displayFrame.top) {
                // Make sure that the zone we're avoiding for the cutout is at least as tall as the
                // status bar; otherwise fullscreen apps will end up cutting halfway into the status
                // bar.
                displayCutoutSafeExceptMaybeBars.top =
                        Math.max(statusBarSource.getFrame().bottom, displayCutoutSafe.top);
            }
            if (cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) {
                if (displayFrame.width() < displayFrame.height()) {
                    displayCutoutSafeExceptMaybeBars.top = Integer.MIN_VALUE;
                    displayCutoutSafeExceptMaybeBars.bottom = Integer.MAX_VALUE;
                } else {
                    displayCutoutSafeExceptMaybeBars.left = Integer.MIN_VALUE;
                    displayCutoutSafeExceptMaybeBars.right = Integer.MAX_VALUE;
                }
            }
            final boolean layoutInsetDecor = (attrs.flags & FLAG_LAYOUT_INSET_DECOR) != 0;
            if (layoutInScreen && layoutInsetDecor
                    && (cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    || cutoutMode == LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)) {
                final Insets systemBarsInsets = state.calculateInsets(
                        displayFrame, WindowInsets.Type.systemBars(), requestedVisibilities);
                if (systemBarsInsets.left > 0) {
                    displayCutoutSafeExceptMaybeBars.left = Integer.MIN_VALUE;
                }
                if (systemBarsInsets.top > 0) {
                    displayCutoutSafeExceptMaybeBars.top = Integer.MIN_VALUE;
                }
                if (systemBarsInsets.right > 0) {
                    displayCutoutSafeExceptMaybeBars.right = Integer.MAX_VALUE;
                }
                if (systemBarsInsets.bottom > 0) {
                    displayCutoutSafeExceptMaybeBars.bottom = Integer.MAX_VALUE;
                }
            }
            if (type == TYPE_INPUT_METHOD) {
                final InsetsSource navSource = state.peekSource(ITYPE_NAVIGATION_BAR);
                if (navSource != null && navSource.calculateInsets(displayFrame, true).bottom > 0) {
                    // The IME can always extend under the bottom cutout if the navbar is there.
                    displayCutoutSafeExceptMaybeBars.bottom = Integer.MAX_VALUE;
                }
            }
            final boolean attachedInParent = attachedWindowFrame != null && !layoutInScreen;

            // TYPE_BASE_APPLICATION windows are never considered floating here because they don't
            // get cropped / shifted to the displayFrame in WindowState.
            final boolean floatingInScreenWindow = !attrs.isFullscreen() && layoutInScreen
                    && type != TYPE_BASE_APPLICATION;

            // Windows that are attached to a parent and laid out in said parent already avoid
            // the cutout according to that parent and don't need to be further constrained.
            // Floating IN_SCREEN windows get what they ask for and lay out in the full screen.
            // They will later be cropped or shifted using the displayFrame in WindowState,
            // which prevents overlap with the DisplayCutout.
            if (!attachedInParent && !floatingInScreenWindow) {
                mTempRect.set(outParentFrame);
                outParentFrame.intersectUnchecked(displayCutoutSafeExceptMaybeBars);
                clippedByDisplayCutout = !mTempRect.equals(outParentFrame);
            }
            outDisplayFrame.intersectUnchecked(displayCutoutSafeExceptMaybeBars);
        }
        return clippedByDisplayCutout;
    }
}
