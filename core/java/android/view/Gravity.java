/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.graphics.Rect;

/**
 * Standard constants and tools for placing an object within a potentially
 * larger container.
 */
public class Gravity
{
    /** Contstant indicating that no gravity has been set **/
    public static final int NO_GRAVITY = 0x0000;
    
    /** Raw bit indicating the gravity for an axis has been specified. */
    public static final int AXIS_SPECIFIED = 0x0001;

    /** Raw bit controlling how the left/top edge is placed. */
    public static final int AXIS_PULL_BEFORE = 0x0002;
    /** Raw bit controlling how the right/bottom edge is placed. */
    public static final int AXIS_PULL_AFTER = 0x0004;

    /** Bits defining the horizontal axis. */
    public static final int AXIS_X_SHIFT = 0;
    /** Bits defining the vertical axis. */
    public static final int AXIS_Y_SHIFT = 4;

    /** Push object to the top of its container, not changing its size. */
    public static final int TOP = (AXIS_PULL_BEFORE|AXIS_SPECIFIED)<<AXIS_Y_SHIFT;
    /** Push object to the bottom of its container, not changing its size. */
    public static final int BOTTOM = (AXIS_PULL_AFTER|AXIS_SPECIFIED)<<AXIS_Y_SHIFT;
    /** Push object to the left of its container, not changing its size. */
    public static final int LEFT = (AXIS_PULL_BEFORE|AXIS_SPECIFIED)<<AXIS_X_SHIFT;
    /** Push object to the right of its container, not changing its size. */
    public static final int RIGHT = (AXIS_PULL_AFTER|AXIS_SPECIFIED)<<AXIS_X_SHIFT;

    /** Place object in the vertical center of its container, not changing its
     *  size. */
    public static final int CENTER_VERTICAL = AXIS_SPECIFIED<<AXIS_Y_SHIFT;
    /** Grow the vertical size of the object if needed so it completely fills
     *  its container. */
    public static final int FILL_VERTICAL = TOP|BOTTOM;

    /** Place object in the horizontal center of its container, not changing its
     *  size. */
    public static final int CENTER_HORIZONTAL = AXIS_SPECIFIED<<AXIS_X_SHIFT;
    /** Grow the horizontal size of the object if needed so it completely fills
     *  its container. */
    public static final int FILL_HORIZONTAL = LEFT|RIGHT;

    /** Place the object in the center of its container in both the vertical
     *  and horizontal axis, not changing its size. */
    public static final int CENTER = CENTER_VERTICAL|CENTER_HORIZONTAL;

    /** Grow the horizontal and vertical size of the obejct if needed so it
     *  completely fills its container. */
    public static final int FILL = FILL_VERTICAL|FILL_HORIZONTAL;

    /**
     * Binary mask to get the horizontal gravity of a gravity.
     */
    public static final int HORIZONTAL_GRAVITY_MASK = (AXIS_SPECIFIED |
            AXIS_PULL_BEFORE | AXIS_PULL_AFTER) << AXIS_X_SHIFT;
    /**
     * Binary mask to get the vertical gravity of a gravity.
     */
    public static final int VERTICAL_GRAVITY_MASK = (AXIS_SPECIFIED |
            AXIS_PULL_BEFORE | AXIS_PULL_AFTER) << AXIS_Y_SHIFT;

    /**
     * Apply a gravity constant to an object.
     * 
     * @param gravity The desired placement of the object, as defined by the
     *                constants in this class.
     * @param w The horizontal size of the object.
     * @param h The vertical size of the object.
     * @param container The frame of the containing space, in which the object
     *                  will be placed.  Should be large enough to contain the
     *                  width and height of the object.
     * @param outRect Receives the computed frame of the object in its
     *                container.
     */
    public static void apply(int gravity, int w, int h, Rect container,
                             Rect outRect) {
        apply(gravity, w, h, container, 0, 0, outRect);
    }

    /**
     * Apply a gravity constant to an object.
     * 
     * @param gravity The desired placement of the object, as defined by the
     *                constants in this class.
     * @param w The horizontal size of the object.
     * @param h The vertical size of the object.
     * @param container The frame of the containing space, in which the object
     *                  will be placed.  Should be large enough to contain the
     *                  width and height of the object.
     * @param xAdj Offset to apply to the X axis.  If gravity is LEFT this
     *             pushes it to the right; if gravity is RIGHT it pushes it to
     *             the left; if gravity is CENTER_HORIZONTAL it pushes it to the
     *             right or left; otherwise it is ignored.
     * @param yAdj Offset to apply to the Y axis.  If gravity is TOP this pushes
     *             it down; if gravity is BOTTOM it pushes it up; if gravity is
     *             CENTER_VERTICAL it pushes it down or up; otherwise it is
     *             ignored.
     * @param outRect Receives the computed frame of the object in its
     *                container.
     */
    public static void apply(int gravity, int w, int h, Rect container,
                             int xAdj, int yAdj, Rect outRect) {
        if ((gravity&((AXIS_PULL_BEFORE|AXIS_PULL_AFTER)<<AXIS_X_SHIFT))
             == ((AXIS_PULL_BEFORE|AXIS_PULL_AFTER)<<AXIS_X_SHIFT)) {
            outRect.left = container.left;
            outRect.right = container.right;
        } else {
            outRect.left = applyMovement(
                gravity>>AXIS_X_SHIFT, w, container.left, container.right, xAdj);
            outRect.right = outRect.left + w;
        }

        if ((gravity&((AXIS_PULL_BEFORE|AXIS_PULL_AFTER)<<AXIS_Y_SHIFT))
             == ((AXIS_PULL_BEFORE|AXIS_PULL_AFTER)<<AXIS_Y_SHIFT)) {
            outRect.top = container.top;
            outRect.bottom = container.bottom;
        } else {
            outRect.top = applyMovement(
                gravity>>AXIS_Y_SHIFT, h, container.top, container.bottom, yAdj);
            outRect.bottom = outRect.top + h;
        }
    }

    /**
     * <p>Indicate whether the supplied gravity has a vertical pull.</p>
     *
     * @param gravity the gravity to check for vertical pull
     * @return true if the supplied gravity has a vertical pull
     */
    public static boolean isVertical(int gravity) {
        return gravity > 0 && (gravity & VERTICAL_GRAVITY_MASK) != 0;
    }

    /**
     * <p>Indicate whether the supplied gravity has an horizontal pull.</p>
     *
     * @param gravity the gravity to check for horizontal pull
     * @return true if the supplied gravity has an horizontal pull
     */
    public static boolean isHorizontal(int gravity) {
        return gravity > 0 && (gravity & HORIZONTAL_GRAVITY_MASK) != 0;
    }

    private static int applyMovement(int mode, int size,
            int start, int end, int adj) {
        if ((mode & AXIS_PULL_BEFORE) != 0) {
            return start + adj;
        }

        if ((mode & AXIS_PULL_AFTER) != 0) {
            return end - size - adj;
        }

        return start + ((end - start - size)/2) + adj;
    }
}

