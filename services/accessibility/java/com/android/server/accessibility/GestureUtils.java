package com.android.server.accessibility;

import android.util.MathUtils;
import android.view.MotionEvent;

/**
 * Some helper functions for gesture detection.
 */
final class GestureUtils {

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
}
