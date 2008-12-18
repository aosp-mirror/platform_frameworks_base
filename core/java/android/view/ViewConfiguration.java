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

/**
 * Contains methods to standard constants used in the UI for timeouts, sizes, and distances.
 *
 */
public class ViewConfiguration {

    /**
     * Defines the width of the horizontal scrollbar and the height of the vertical scrollbar in
     * pixels
     */
    private static final int SCROLL_BAR_SIZE = 6;

    /**
     * Defines the length of the fading edges in pixels
     */
    private static final int FADING_EDGE_LENGTH = 12;

    /**
     * Defines the duration in milliseconds of the pressed state in child
     * components.
     */
    private static final int PRESSED_STATE_DURATION = 85;
    
    /**
     * Defines the duration in milliseconds before a press turns into
     * a long press
     */
    private static final int LONG_PRESS_TIMEOUT = 500;
    
    /**
     * Defines the duration in milliseconds a user needs to hold down the
     * appropriate button to bring up the global actions dialog (power off,
     * lock screen, etc).
     */
    private static final int GLOBAL_ACTIONS_KEY_TIMEOUT = 500;
    
    /**
     * Defines the duration in milliseconds we will wait to see if a touch event 
     * is a top or a scroll. If the user does not move within this interval, it is
     * considered to be a tap. 
     */
    private static final int TAP_TIMEOUT = 100;
    
    /**
     * Defines the duration in milliseconds we will wait to see if a touch event 
     * is a jump tap. If the user does not complete the jump tap within this interval, it is
     * considered to be a tap. 
     */
    private static final int JUMP_TAP_TIMEOUT = 500;
    
    /**
     * Defines the duration in milliseconds we want to display zoom controls in response 
     * to a user panning within an application.
     */
    private static final int ZOOM_CONTROLS_TIMEOUT = 3000;

    /**
     * Inset in pixels to look for touchable content when the user touches the edge of the screen
     */
    private static final int EDGE_SLOP = 12;
    
    /**
     * Distance a touch can wander before we think the user is scrolling in pixels
     */
    private static final int TOUCH_SLOP = 12;
    
    /**
     * Distance a touch needs to be outside of a window's bounds for it to
     * count as outside for purposes of dismissing the window.
     */
    private static final int WINDOW_TOUCH_SLOP = 16;

    /**
     * Minimum velocity to initiate a fling, as measured in pixels per second
     */
    private static final int MINIMUM_FLING_VELOCITY = 50;

    /**
     * The maximum size of View's drawing cache, expressed in bytes. This size
     * should be at least equal to the size of the screen in ARGB888 format.
     */
    private static final int MAXIMUM_DRAWING_CACHE_SIZE = 320 * 480 * 4; // One HVGA screen, ARGB8888

    /**
     * The coefficient of friction applied to flings/scrolls.
     */
    private static float SCROLL_FRICTION = 0.015f;

    /**
     * @return The width of the horizontal scrollbar and the height of the vertical
     *         scrollbar in pixels
     */
    public static int getScrollBarSize() {
        return SCROLL_BAR_SIZE;
    }

    /**
     * @return Defines the length of the fading edges in pixels
     */
    public static int getFadingEdgeLength() {
        return FADING_EDGE_LENGTH;
    }
    
    /**
     * @return Defines the duration in milliseconds of the pressed state in child
     * components.
     */
    public static int getPressedStateDuration() {
        return PRESSED_STATE_DURATION;
    }
    
    /**
     * @return Defines the duration in milliseconds before a press turns into
     * a long press
     */
    public static int getLongPressTimeout() {
        return LONG_PRESS_TIMEOUT;
    }
    
    /**
     * @return Defines the duration in milliseconds we will wait to see if a touch event 
     * is a top or a scroll. If the user does not move within this interval, it is
     * considered to be a tap. 
     */
    public static int getTapTimeout() {
        return TAP_TIMEOUT;
    }
    
    /**
     * @return Defines the duration in milliseconds we will wait to see if a touch event 
     * is a jump tap. If the user does not move within this interval, it is
     * considered to be a tap. 
     */
    public static int getJumpTapTimeout() {
        return JUMP_TAP_TIMEOUT;
    }
    
    /**
     * @return Inset in pixels to look for touchable content when the user touches the edge of the
     *         screen
     */
    public static int getEdgeSlop() {
        return EDGE_SLOP;
    }
    
    /**
     * @return Distance a touch can wander before we think the user is scrolling in pixels
     */
    public static int getTouchSlop() {
        return TOUCH_SLOP;
    }
    
    /**
     * @return Distance a touch must be outside the bounds of a window for it
     * to be counted as outside the window for purposes of dismissing that
     * window.
     */
    public static int getWindowTouchSlop() {
        return WINDOW_TOUCH_SLOP;
    }
    
    /**
     * Minimum velocity to initiate a fling, as measured in pixels per second
     */
    public static int getMinimumFlingVelocity() {    
     return MINIMUM_FLING_VELOCITY;
    }

    /**
     * The maximum drawing cache size expressed in bytes.
     *
     * @return the maximum size of View's drawing cache expressed in bytes
     */
    public static int getMaximumDrawingCacheSize() {
        return MAXIMUM_DRAWING_CACHE_SIZE;
    }

    /**
     * The amount of time that the zoom controls should be
     * displayed on the screen expressed in milliseconds.
     * 
     * @return the time the zoom controls should be visible expressed
     * in milliseconds.
     */
    public static long getZoomControlsTimeout() {
        return ZOOM_CONTROLS_TIMEOUT;
    }

    /**
     * The amount of time a user needs to press the relevant key to bring up
     * the global actions dialog.
     *
     * @return how long a user needs to press the relevant key to bring up
     *   the global actions dialog.
     */
    public static long getGlobalActionKeyTimeout() {
        return GLOBAL_ACTIONS_KEY_TIMEOUT;
    }

    /**
     * The amount of friction applied to scrolls and flings.
     * 
     * @return A scalar dimensionless value representing the coefficient of
     *         friction.
     */
    public static float getScrollFriction() {
        return SCROLL_FRICTION;
    }
}
