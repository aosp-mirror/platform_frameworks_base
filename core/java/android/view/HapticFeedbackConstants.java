/*
 * Copyright (C) 2009 The Android Open Source Project
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
 * Constants to be used to perform haptic feedback effects via
 * {@link View#performHapticFeedback(int)} 
 */
public class HapticFeedbackConstants {

    private HapticFeedbackConstants() {}

    /**
     * No haptic feedback should be performed. Applications may use this value to indicate skipping
     * a call to {@link View#performHapticFeedback} entirely, or else rely that it will immediately
     * return {@code false}.
     */
    public static final int NO_HAPTICS = -1;

    /**
     * The user has performed a long press on an object that is resulting
     * in an action being performed.
     */
    public static final int LONG_PRESS = 0;

    /**
     * The user has pressed on a virtual on-screen key.
     */
    public static final int VIRTUAL_KEY = 1;

    /**
     * The user has pressed a soft keyboard key.
     */
    public static final int KEYBOARD_TAP = 3;

    /**
     * The user has pressed either an hour or minute tick of a Clock.
     */
    public static final int CLOCK_TICK = 4;

    /**
     * The user has pressed either a day or month or year date of a Calendar.
     * @hide
     */
    public static final int CALENDAR_DATE = 5;

    /**
     * The user has performed a context click on an object.
     */
    public static final int CONTEXT_CLICK = 6;

    /**
     * The user has pressed a virtual or software keyboard key.
     */
    public static final int KEYBOARD_PRESS = KEYBOARD_TAP;

    /**
     * The user has released a virtual keyboard key.
     */
    public static final int KEYBOARD_RELEASE = 7;

    /**
     * The user has released a virtual key.
     */
    public static final int VIRTUAL_KEY_RELEASE = 8;

    /**
     * The user has performed a selection/insertion handle move on text field.
     */
    public static final int TEXT_HANDLE_MOVE = 9;

    /**
     * The user unlocked the device
     * @hide
     */
    public static final int ENTRY_BUMP = 10;

    /**
     * The user has moved the dragged object within a droppable area.
     * @hide
     */
    public static final int DRAG_CROSSING = 11;

    /**
     * The user has started a gesture (e.g. on the soft keyboard).
     */
    public static final int GESTURE_START = 12;

    /**
     * The user has finished a gesture (e.g. on the soft keyboard).
     */
    public static final int GESTURE_END = 13;

    /**
     * The user's squeeze crossed the gesture's initiation threshold.
     * @hide
     */
    public static final int EDGE_SQUEEZE = 14;

    /**
     * The user's squeeze crossed the gesture's release threshold.
     * @hide
     */
    public static final int EDGE_RELEASE = 15;

    /**
     * A haptic effect to signal the confirmation or successful completion of a user
     * interaction.
     */
    public static final int CONFIRM = 16;

    /**
     * A haptic effect to signal the rejection or failure of a user interaction.
     */
    public static final int REJECT = 17;

    /**
     * A haptic effect to provide texture while scrolling.
     *
     * @hide
     */
    public static final int SCROLL_TICK = 18;

    /**
     * A haptic effect to signal that a list element has been focused while scrolling.
     *
     * @hide
     */
    public static final int SCROLL_ITEM_FOCUS = 19;

    /**
     * A haptic effect to signal reaching the scrolling limits of a list while scrolling.
     *
     * @hide
     */
    public static final int SCROLL_LIMIT = 20;

    /**
     * The user has toggled a switch or button into the on position.
     */
    public static final int TOGGLE_ON = 21;

    /**
     * The user has toggled a switch or button into the off position.
     */
    public static final int TOGGLE_OFF = 22;

    /**
     * The user is executing a swipe/drag-style gesture, such as pull-to-refresh, where the
     * gesture action is “eligible” at a certain threshold of movement, and can be cancelled by
     * moving back past the threshold. This constant indicates that the user's motion has just
     * passed the threshold for the action to be activated on release.
     *
     * @see #GESTURE_THRESHOLD_DEACTIVATE
     */
    public static final int GESTURE_THRESHOLD_ACTIVATE = 23;

    /**
     * The user is executing a swipe/drag-style gesture, such as pull-to-refresh, where the
     * gesture action is “eligible” at a certain threshold of movement, and can be cancelled by
     * moving back past the threshold. This constant indicates that the user's motion has just
     * re-crossed back "under" the threshold for the action to be activated, meaning the gesture is
     * currently in a cancelled state.
     *
     * @see #GESTURE_THRESHOLD_ACTIVATE
     */
    public static final int GESTURE_THRESHOLD_DEACTIVATE = 24;

    /**
     * The user has started a drag-and-drop gesture. The drag target has just been "picked up".
     */
    public static final int DRAG_START = 25;

    /**
     * The user is switching between a series of potential choices, for example items in a list
     * or discrete points on a slider.
     *
     * <p>See also {@link #SEGMENT_FREQUENT_TICK} for cases where density of choices is high, and
     * the haptics should be lighter or suppressed for a better user experience.
     */
    public static final int SEGMENT_TICK = 26;

    /**
     * The user is switching between a series of many potential choices, for example minutes on a
     * clock face, or individual percentages. This constant is expected to be very soft, so as
     * not to be uncomfortable when performed a lot in quick succession. If the device can’t make
     * a suitably soft vibration, then it may not make any vibration.
     *
     * <p>Some specializations of this constant exist for specific actions, notably
     * {@link #CLOCK_TICK} and {@link #TEXT_HANDLE_MOVE}.
     *
     * <p>See also {@link #SEGMENT_TICK}.
    */
    public static final int SEGMENT_FREQUENT_TICK = 27;

    /**
     * The phone has booted with safe mode enabled.
     * This is a private constant.  Feel free to renumber as desired.
     * @hide
     */
    public static final int SAFE_MODE_ENABLED = 10001;

    /**
     * Invocation of the voice assistant via hardware button.
     * This is a private constant.  Feel free to renumber as desired.
     * @hide
     */
    public static final int ASSISTANT_BUTTON = 10002;

    /**
     * The user has performed a long press on the power button hardware that is resulting
     * in an action being performed.
     * This is a private constant.  Feel free to renumber as desired.
     * @hide
     */
    public static final int LONG_PRESS_POWER_BUTTON = 10003;

    /**
     * Flag for {@link View#performHapticFeedback(int, int)
     * View.performHapticFeedback(int, int)}: Ignore the setting in the
     * view for whether to perform haptic feedback, do it always.
     */
    public static final int FLAG_IGNORE_VIEW_SETTING = 0x0001;

    /**
     * Flag for {@link View#performHapticFeedback(int, int)
     * View.performHapticFeedback(int, int)}: Ignore the global setting
     * for whether to perform haptic feedback, do it always.
     *
     * @deprecated Starting from {@link android.os.Build.VERSION_CODES#TIRAMISU} only privileged
     * apps can ignore user settings for touch feedback.
     */
    @Deprecated
    public static final int FLAG_IGNORE_GLOBAL_SETTING = 0x0002;
}
