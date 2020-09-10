package com.android.server.accessibility.gestures;

import android.graphics.PointF;
import android.util.MathUtils;
import android.view.MotionEvent;

/**
 * Some helper functions for gesture detection.
 */
public final class GestureUtils {

    public static int MM_PER_CM = 10;
    public static float CM_PER_INCH = 2.54f;

    private GestureUtils() {
        /* cannot be instantiated */
    }

    public static boolean isMultiTap(MotionEvent firstUp, MotionEvent secondUp,
            int multiTapTimeSlop, int multiTapDistanceSlop) {
        if (firstUp == null || secondUp == null) return false;
        return eventsWithinTimeAndDistanceSlop(firstUp, secondUp, multiTapTimeSlop,
                multiTapDistanceSlop);
    }

    private static boolean eventsWithinTimeAndDistanceSlop(MotionEvent first, MotionEvent second,
            int timeout, int distance) {
        if (isTimedOut(first, second, timeout)) {
            return false;
        }
        final double deltaMove = distance(first, second);
        if (deltaMove >= distance) {
            return false;
        }
        return true;
    }

    public static double distance(MotionEvent first, MotionEvent second) {
        return MathUtils.dist(first.getX(), first.getY(), second.getX(), second.getY());
    }

    /**
     * Returns the minimum distance between {@code pointerDown} and each pointer of
     * {@link MotionEvent}.
     *
     * @param pointerDown The action pointer location of the {@link MotionEvent} with
     *     {@link MotionEvent#ACTION_DOWN} or {@link MotionEvent#ACTION_POINTER_DOWN}
     * @param moveEvent The {@link MotionEvent} with {@link MotionEvent#ACTION_MOVE}
     * @return the movement of the pointer.
     */
    public static double distanceClosestPointerToPoint(PointF pointerDown, MotionEvent moveEvent) {
        float movement = Float.MAX_VALUE;
        for (int i = 0; i < moveEvent.getPointerCount(); i++) {
            final float moveDelta = MathUtils.dist(pointerDown.x, pointerDown.y, moveEvent.getX(i),
                    moveEvent.getY(i));
            if (movement > moveDelta) {
                movement = moveDelta;
            }
        }
        return movement;
    }

    public static boolean isTimedOut(MotionEvent firstUp, MotionEvent secondUp, int timeout) {
        final long deltaTime = secondUp.getEventTime() - firstUp.getEventTime();
        return (deltaTime >= timeout);
    }

    /**
     * Determines whether a two pointer gesture is a dragging one.
     *
     * @return True if the gesture is a dragging one.
     */
    public static boolean isDraggingGesture(float firstPtrDownX, float firstPtrDownY,
            float secondPtrDownX, float secondPtrDownY, float firstPtrX, float firstPtrY,
            float secondPtrX, float secondPtrY, float maxDraggingAngleCos) {

        // Check if the pointers are moving in the same direction.
        final float firstDeltaX = firstPtrX - firstPtrDownX;
        final float firstDeltaY = firstPtrY - firstPtrDownY;

        if (firstDeltaX == 0 && firstDeltaY == 0) {
            return true;
        }

        final float firstMagnitude = (float) Math.hypot(firstDeltaX, firstDeltaY);
        final float firstXNormalized =
            (firstMagnitude > 0) ? firstDeltaX / firstMagnitude : firstDeltaX;
        final float firstYNormalized =
            (firstMagnitude > 0) ? firstDeltaY / firstMagnitude : firstDeltaY;

        final float secondDeltaX = secondPtrX - secondPtrDownX;
        final float secondDeltaY = secondPtrY - secondPtrDownY;

        if (secondDeltaX == 0 && secondDeltaY == 0) {
            return true;
        }

        final float secondMagnitude = (float) Math.hypot(secondDeltaX, secondDeltaY);
        final float secondXNormalized =
            (secondMagnitude > 0) ? secondDeltaX / secondMagnitude : secondDeltaX;
        final float secondYNormalized =
            (secondMagnitude > 0) ? secondDeltaY / secondMagnitude : secondDeltaY;

        final float angleCos =
            firstXNormalized * secondXNormalized + firstYNormalized * secondYNormalized;

        if (angleCos < maxDraggingAngleCos) {
            return false;
        }

        return true;
    }

    /**
     * Gets the index of the pointer that went up or down from a motion event.
     */
    public static int getActionIndex(MotionEvent event) {
        return (event.getAction()
                & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
    }
}
