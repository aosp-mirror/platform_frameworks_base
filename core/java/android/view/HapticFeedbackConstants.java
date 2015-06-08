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
     * This is a private constant.  Feel free to renumber as desired.
     * @hide
     */
    public static final int SAFE_MODE_DISABLED = 10000;
    
    /**
     * This is a private constant.  Feel free to renumber as desired.
     * @hide
     */
    public static final int SAFE_MODE_ENABLED = 10001;
    
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
     */
    public static final int FLAG_IGNORE_GLOBAL_SETTING = 0x0002;
}
