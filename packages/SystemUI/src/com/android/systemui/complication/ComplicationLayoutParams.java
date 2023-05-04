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
package com.android.systemui.complication;

import android.annotation.IntDef;
import android.view.ViewGroup;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link ComplicationLayoutParams} allows a {@link Complication} to express its preferred location
 * and dimensions. Note that these parameters are not directly applied by any {@link ViewGroup}.
 * They are instead consulted for the final parameters which best seem fit for usage.
 */
public class ComplicationLayoutParams extends ViewGroup.LayoutParams {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "POSITION_" }, value = {
            POSITION_TOP,
            POSITION_END,
            POSITION_BOTTOM,
            POSITION_START,
    })

    public @interface Position {}
    /** Align view with the top of parent or bottom of preceding {@link Complication}. */
    public static final int POSITION_TOP = 1 << 0;
    /** Align view with the bottom of parent or top of preceding {@link Complication}. */
    public static final int POSITION_BOTTOM = 1 << 1;
    /** Align view with the start of parent or end of preceding {@link Complication}. */
    public static final int POSITION_START = 1 << 2;
    /** Align view with the end of parent or start of preceding {@link Complication}. */
    public static final int POSITION_END = 1 << 3;

    private static final int FIRST_POSITION = POSITION_TOP;
    private static final int LAST_POSITION = POSITION_END;

    private static final int DIRECTIONAL_SPACING_UNSPECIFIED = 0xFFFFFFFF;
    private static final int CONSTRAINT_UNSPECIFIED = 0xFFFFFFFF;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "DIRECTION_" }, value = {
            DIRECTION_UP,
            DIRECTION_DOWN,
            DIRECTION_START,
            DIRECTION_END,
    })

    @interface Direction {}
    /** Position view upward from position. */
    public static final int DIRECTION_UP = 1 << 0;
    /** Position view downward from position. */
    public static final int DIRECTION_DOWN = 1 << 1;
    /** Position view towards the start of the parent. */
    public static final int DIRECTION_START = 1 << 2;
    /** Position view towards the end of parent. */
    public static final int DIRECTION_END = 1 << 3;

    @Position
    private final int mPosition;

    @Direction
    private final int mDirection;

    private final int mWeight;

    private final int mDirectionalSpacing;

    private final int mConstraint;

    private final boolean mSnapToGuide;

    // Do not allow specifying opposite positions
    private static final int[] INVALID_POSITIONS =
            { POSITION_BOTTOM | POSITION_TOP, POSITION_END | POSITION_START };

    // Do not allow for specifying a direction towards the outside of the container.
    private static final Map<Integer, Integer> INVALID_DIRECTIONS;
    static {
        INVALID_DIRECTIONS = new HashMap<>();
        INVALID_DIRECTIONS.put(POSITION_BOTTOM, DIRECTION_DOWN);
        INVALID_DIRECTIONS.put(POSITION_TOP, DIRECTION_UP);
        INVALID_DIRECTIONS.put(POSITION_START, DIRECTION_START);
        INVALID_DIRECTIONS.put(POSITION_END, DIRECTION_END);
    }

    /**
     * Constructs a {@link ComplicationLayoutParams}.
     * @param width The width {@link android.view.View.MeasureSpec} for the view.
     * @param height The height {@link android.view.View.MeasureSpec} for the view.
     * @param position The place within the parent container where the view should be positioned.
     * @param direction The direction the view should be laid out from either the parent container
     *                  or preceding view.
     * @param weight The weight that should be considered for this view when compared to other
     *               views. This has an impact on the placement of the view but not the rendering of
     *               the view.
     */
    public ComplicationLayoutParams(int width, int height, @Position int position,
            @Direction int direction, int weight) {
        this(width, height, position, direction, weight, DIRECTIONAL_SPACING_UNSPECIFIED,
                CONSTRAINT_UNSPECIFIED, false);
    }

    /**
     * Constructs a {@link ComplicationLayoutParams}.
     * @param width The width {@link android.view.View.MeasureSpec} for the view.
     * @param height The height {@link android.view.View.MeasureSpec} for the view.
     * @param position The place within the parent container where the view should be positioned.
     * @param direction The direction the view should be laid out from either the parent container
     *                  or preceding view.
     * @param weight The weight that should be considered for this view when compared to other
     *               views. This has an impact on the placement of the view but not the rendering of
     *               the view.
     * @param directionalSpacing The spacing to apply between complications.
     */
    public ComplicationLayoutParams(int width, int height, @Position int position,
            @Direction int direction, int weight, int directionalSpacing) {
        this(width, height, position, direction, weight, directionalSpacing, CONSTRAINT_UNSPECIFIED,
                false);
    }

    /**
     * Constructs a {@link ComplicationLayoutParams}.
     * @param width The width {@link android.view.View.MeasureSpec} for the view.
     * @param height The height {@link android.view.View.MeasureSpec} for the view.
     * @param position The place within the parent container where the view should be positioned.
     * @param direction The direction the view should be laid out from either the parent container
     *                  or preceding view.
     * @param weight The weight that should be considered for this view when compared to other
     *               views. This has an impact on the placement of the view but not the rendering of
     *               the view.
     * @param directionalSpacing The spacing to apply between complications.
     * @param constraint The max width or height the complication is allowed to spread, depending on
     *                   its direction. For horizontal directions, this would be applied on width,
     *                   and for vertical directions, height.
     */
    public ComplicationLayoutParams(int width, int height, @Position int position,
            @Direction int direction, int weight, int directionalSpacing, int constraint) {
        this(width, height, position, direction, weight, directionalSpacing, constraint, false);
    }

    /**
     * Constructs a {@link ComplicationLayoutParams}.
     * @param width The width {@link android.view.View.MeasureSpec} for the view.
     * @param height The height {@link android.view.View.MeasureSpec} for the view.
     * @param position The place within the parent container where the view should be positioned.
     * @param direction The direction the view should be laid out from either the parent container
     *                  or preceding view.
     * @param weight The weight that should be considered for this view when compared to other
     *               views. This has an impact on the placement of the view but not the rendering of
     *               the view.
     * @param snapToGuide When set to {@code true}, the dimension perpendicular to the direction
     *                    will be automatically set to align with a predetermined guide for that
     *                    side. For example, if the complication is aligned to the top end and
     *                    direction is down, then the width of the complication will be set to span
     *                    from the end of the parent to the guide.
     */
    public ComplicationLayoutParams(int width, int height, @Position int position,
            @Direction int direction, int weight, boolean snapToGuide) {
        this(width, height, position, direction, weight, DIRECTIONAL_SPACING_UNSPECIFIED,
                CONSTRAINT_UNSPECIFIED, snapToGuide);
    }

    /**
     * Constructs a {@link ComplicationLayoutParams}.
     * @param width The width {@link android.view.View.MeasureSpec} for the view.
     * @param height The height {@link android.view.View.MeasureSpec} for the view.
     * @param position The place within the parent container where the view should be positioned.
     * @param direction The direction the view should be laid out from either the parent container
     *                  or preceding view.
     * @param weight The weight that should be considered for this view when compared to other
     *               views. This has an impact on the placement of the view but not the rendering of
     *               the view.
     * @param directionalSpacing The spacing to apply between complications.
     * @param constraint The max width or height the complication is allowed to spread, depending on
     *                   its direction. For horizontal directions, this would be applied on width,
     *                   and for vertical directions, height.
     * @param snapToGuide When set to {@code true}, the dimension perpendicular to the direction
     *                    will be automatically set to align with a predetermined guide for that
     *                    side. For example, if the complication is aligned to the top end and
     *                    direction is down, then the width of the complication will be set to span
     *                    from the end of the parent to the guide.
     */
    public ComplicationLayoutParams(int width, int height, @Position int position,
            @Direction int direction, int weight, int directionalSpacing, int constraint,
            boolean snapToGuide) {
        super(width, height);

        if (!validatePosition(position)) {
            throw new IllegalArgumentException("invalid position:" + position);
        }
        mPosition = position;

        if (!validateDirection(position, direction)) {
            throw new IllegalArgumentException("invalid direction:" + direction);
        }

        mDirection = direction;

        mWeight = weight;

        mDirectionalSpacing = directionalSpacing;

        mConstraint = constraint;

        mSnapToGuide = snapToGuide;
    }

    /**
     * Constructs {@link ComplicationLayoutParams} from an existing instance.
     */
    public ComplicationLayoutParams(ComplicationLayoutParams source) {
        super(source);
        mPosition = source.mPosition;
        mDirection = source.mDirection;
        mWeight = source.mWeight;
        mDirectionalSpacing = source.mDirectionalSpacing;
        mConstraint = source.mConstraint;
        mSnapToGuide = source.mSnapToGuide;
    }

    private static boolean validateDirection(@Position int position, @Direction int direction) {
        for (int currentPosition = FIRST_POSITION; currentPosition <= LAST_POSITION;
                currentPosition <<= 1) {
            if ((position & currentPosition) == currentPosition
                    && INVALID_DIRECTIONS.containsKey(currentPosition)
                    && (direction & INVALID_DIRECTIONS.get(currentPosition)) != 0) {
                return false;
            }
        }

        return true;
    }

    /**
     * Iterates over the defined positions and invokes the specified {@link Consumer} for each
     * position specified for this {@link ComplicationLayoutParams}.
     */
    public void iteratePositions(Consumer<Integer> consumer) {
        iteratePositions(consumer, mPosition);
    }

    /**
     * Iterates over the defined positions and invokes the specified {@link Consumer} for each
     * position specified by the given {@code position}.
     */
    public static void iteratePositions(Consumer<Integer> consumer, @Position int position) {
        for (int currentPosition = FIRST_POSITION; currentPosition <= LAST_POSITION;
                currentPosition <<= 1) {
            if ((position & currentPosition) == currentPosition) {
                consumer.accept(currentPosition);
            }
        }
    }

    private static boolean validatePosition(@Position int position) {
        if (position == 0) {
            return false;
        }

        for (int combination : INVALID_POSITIONS) {
            if ((position & combination) == combination) {
                return false;
            }
        }

        return true;
    }

    @Direction
    public int getDirection() {
        return mDirection;
    }

    @Position
    public int getPosition() {
        return mPosition;
    }

    /**
     * Returns the set weight for the complication. The weight determines ordering a complication
     * given the same position/direction.
     */
    public int getWeight() {
        return mWeight;
    }

    /**
     * Returns the spacing to apply between complications, or the given default if no spacing is
     * specified.
     */
    public int getDirectionalSpacing(int defaultSpacing) {
        return mDirectionalSpacing == DIRECTIONAL_SPACING_UNSPECIFIED
                ? defaultSpacing : mDirectionalSpacing;
    }

    /**
     * Returns whether the horizontal or vertical constraint has been specified.
     */
    public boolean constraintSpecified() {
        return mConstraint != CONSTRAINT_UNSPECIFIED;
    }

    /**
     * Returns the horizontal or vertical constraint of the complication, depending its direction.
     * For horizontal directions, this is the max width, and for vertical directions, max height.
     */
    public int getConstraint() {
        return mConstraint;
    }

    /**
     * Returns whether the complication's dimension perpendicular to direction should be
     * automatically set.
     */
    public boolean snapsToGuide() {
        return mSnapToGuide;
    }
}
