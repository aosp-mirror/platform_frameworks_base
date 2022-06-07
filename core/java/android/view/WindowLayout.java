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

import static android.view.Gravity.DISPLAY_CLIP_HORIZONTAL;
import static android.view.Gravity.DISPLAY_CLIP_VERTICAL;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
import static android.view.WindowManager.LayoutParams.FLAG_SCALED;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_INSET_PARENT_FRAME_BY_IME;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ERROR;

import android.app.WindowConfiguration;
import android.app.WindowConfiguration.WindowingMode;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.window.ClientWindowFrames;

/**
 * Computes window frames.
 * @hide
 */
public class WindowLayout {
    private static final String TAG = WindowLayout.class.getSimpleName();
    private static final boolean DEBUG = false;

    public static final int UNSPECIFIED_LENGTH = -1;

    /** These coordinates are the borders of the window layout. */
    static final int MIN_X = -100000;
    static final int MIN_Y = -100000;
    static final int MAX_X = 100000;
    static final int MAX_Y = 100000;

    private final Rect mTempDisplayCutoutSafeExceptMaybeBarsRect = new Rect();
    private final Rect mTempRect = new Rect();

    public void computeFrames(WindowManager.LayoutParams attrs, InsetsState state,
            Rect displayCutoutSafe, Rect windowBounds, @WindowingMode int windowingMode,
            int requestedWidth, int requestedHeight, InsetsVisibilities requestedVisibilities,
            Rect attachedWindowFrame, float compatScale, ClientWindowFrames outFrames) {
        final int type = attrs.type;
        final int fl = attrs.flags;
        final int pfl = attrs.privateFlags;
        final boolean layoutInScreen = (fl & FLAG_LAYOUT_IN_SCREEN) == FLAG_LAYOUT_IN_SCREEN;
        final Rect outDisplayFrame = outFrames.displayFrame;
        final Rect outParentFrame = outFrames.parentFrame;
        final Rect outFrame = outFrames.frame;

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
        final int cutoutMode = attrs.layoutInDisplayCutoutMode;
        final DisplayCutout cutout = state.getDisplayCutout();
        final Rect displayCutoutSafeExceptMaybeBars = mTempDisplayCutoutSafeExceptMaybeBarsRect;
        displayCutoutSafeExceptMaybeBars.set(displayCutoutSafe);
        outFrames.isParentFrameClippedByDisplayCutout = false;
        if (cutoutMode != LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS && !cutout.isEmpty()) {
            // Ensure that windows with a non-ALWAYS display cutout mode are laid out in
            // the cutout safe zone.
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
                outFrames.isParentFrameClippedByDisplayCutout = !mTempRect.equals(outParentFrame);
            }
            outDisplayFrame.intersectUnchecked(displayCutoutSafeExceptMaybeBars);
        }

        final boolean noLimits = (attrs.flags & FLAG_LAYOUT_NO_LIMITS) != 0;
        final boolean inMultiWindowMode = WindowConfiguration.inMultiWindowMode(windowingMode);

        // TYPE_SYSTEM_ERROR is above the NavigationBar so it can't be allowed to extend over it.
        // Also, we don't allow windows in multi-window mode to extend out of the screen.
        if (noLimits && type != TYPE_SYSTEM_ERROR && !inMultiWindowMode) {
            outDisplayFrame.left = MIN_X;
            outDisplayFrame.top = MIN_Y;
            outDisplayFrame.right = MAX_X;
            outDisplayFrame.bottom = MAX_Y;
        }

        final boolean hasCompatScale = compatScale != 1f;
        final int pw = outParentFrame.width();
        final int ph = outParentFrame.height();
        final boolean extendedByCutout =
                (attrs.privateFlags & PRIVATE_FLAG_LAYOUT_SIZE_EXTENDED_BY_CUTOUT) != 0;
        int rw = requestedWidth;
        int rh = requestedHeight;
        float x, y;
        int w, h;

        // If the view hierarchy hasn't been measured, the requested width and height would be
        // UNSPECIFIED_LENGTH. This can happen in the first layout of a window or in the simulated
        // layout. If extendedByCutout is true, we cannot use the requested lengths. Otherwise,
        // the window frame might be extended again because the requested lengths may come from the
        // window frame.
        if (rw == UNSPECIFIED_LENGTH || extendedByCutout) {
            rw = attrs.width >= 0 ? attrs.width : pw;
        }
        if (rh == UNSPECIFIED_LENGTH || extendedByCutout) {
            rh = attrs.height >= 0 ? attrs.height : ph;
        }

        if ((attrs.flags & FLAG_SCALED) != 0) {
            if (attrs.width < 0) {
                w = pw;
            } else if (hasCompatScale) {
                w = (int) (attrs.width * compatScale + .5f);
            } else {
                w = attrs.width;
            }
            if (attrs.height < 0) {
                h = ph;
            } else if (hasCompatScale) {
                h = (int) (attrs.height * compatScale + .5f);
            } else {
                h = attrs.height;
            }
        } else {
            if (attrs.width == MATCH_PARENT) {
                w = pw;
            } else if (hasCompatScale) {
                w = (int) (rw * compatScale + .5f);
            } else {
                w = rw;
            }
            if (attrs.height == MATCH_PARENT) {
                h = ph;
            } else if (hasCompatScale) {
                h = (int) (rh * compatScale + .5f);
            } else {
                h = rh;
            }
        }

        if (hasCompatScale) {
            x = attrs.x * compatScale;
            y = attrs.y * compatScale;
        } else {
            x = attrs.x;
            y = attrs.y;
        }

        if (inMultiWindowMode
                && (attrs.privateFlags & PRIVATE_FLAG_LAYOUT_CHILD_WINDOW_IN_PARENT_FRAME) == 0) {
            // Make sure window fits in parent frame since it is in a non-fullscreen task as
            // required by {@link Gravity#apply} call.
            w = Math.min(w, pw);
            h = Math.min(h, ph);
        }

        // We need to fit it to the display if either
        // a) The window is in a fullscreen container, or we don't have a task (we assume fullscreen
        // for the taskless windows)
        // b) If it's a secondary app window, we also need to fit it to the display unless
        // FLAG_LAYOUT_NO_LIMITS is set. This is so we place Popups, dialogs, and similar windows on
        // screen, but SurfaceViews want to be always at a specific location so we don't fit it to
        // the display.
        final boolean fitToDisplay = !inMultiWindowMode
                || ((attrs.type != TYPE_BASE_APPLICATION) && !noLimits);

        // Set mFrame
        Gravity.apply(attrs.gravity, w, h, outParentFrame,
                (int) (x + attrs.horizontalMargin * pw),
                (int) (y + attrs.verticalMargin * ph), outFrame);

        // Now make sure the window fits in the overall display frame.
        if (fitToDisplay) {
            Gravity.applyDisplay(attrs.gravity, outDisplayFrame, outFrame);
        }

        if (extendedByCutout && !displayCutoutSafe.contains(outFrame)) {
            mTempRect.set(outFrame);

            // Move the frame into displayCutoutSafe.
            final int clipFlags = DISPLAY_CLIP_VERTICAL | DISPLAY_CLIP_HORIZONTAL;
            Gravity.applyDisplay(attrs.gravity & ~clipFlags, displayCutoutSafe,
                    mTempRect);

            if (mTempRect.intersect(outDisplayFrame)) {
                outFrame.union(mTempRect);
            }
        }

        if (DEBUG) Log.d(TAG, "computeWindowFrames " + attrs.getTitle()
                + " outFrames=" + outFrames
                + " windowBounds=" + windowBounds.toShortString()
                + " attachedWindowFrame=" + (attachedWindowFrame != null
                        ? attachedWindowFrame.toShortString()
                        : "null")
                + " requestedWidth=" + requestedWidth
                + " requestedHeight=" + requestedHeight
                + " compatScale=" + compatScale
                + " windowingMode=" + WindowConfiguration.windowingModeToString(windowingMode)
                + " displayCutoutSafe=" + displayCutoutSafe
                + " attrs=" + attrs
                + " state=" + state
                + " requestedVisibilities=" + requestedVisibilities);
    }

    public static void computeSurfaceSize(WindowManager.LayoutParams attrs, Rect maxBounds,
            int requestedWidth, int requestedHeight, Rect winFrame, boolean dragResizing,
            Point outSurfaceSize) {
        int width;
        int height;
        if ((attrs.flags & WindowManager.LayoutParams.FLAG_SCALED) != 0) {
            // For a scaled surface, we always want the requested size.
            width = requestedWidth;
            height = requestedHeight;
        } else {
            // When we're doing a drag-resizing, request a surface that's fullscreen size,
            // so that we don't need to reallocate during the process. This also prevents
            // buffer drops due to size mismatch.
            if (dragResizing) {
                // The maxBounds should match the display size which applies fixed-rotation
                // transformation if there is any.
                width = maxBounds.width();
                height = maxBounds.height();
            } else {
                width = winFrame.width();
                height = winFrame.height();
            }
        }

        // This doesn't necessarily mean that there is an error in the system. The sizes might be
        // incorrect, because it is before the first layout or draw.
        if (width < 1) {
            width = 1;
        }
        if (height < 1) {
            height = 1;
        }

        // Adjust for surface insets.
        final Rect surfaceInsets = attrs.surfaceInsets;
        width += surfaceInsets.left + surfaceInsets.right;
        height += surfaceInsets.top + surfaceInsets.bottom;

        outSurfaceSize.set(width, height);
    }
}
