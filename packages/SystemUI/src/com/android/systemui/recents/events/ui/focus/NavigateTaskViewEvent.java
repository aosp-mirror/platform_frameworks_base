/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.recents.events.ui.focus;

import android.view.KeyEvent;
import com.android.systemui.recents.events.EventBus;

/**
 * Navigates the task view by arrow keys.
 */
public class NavigateTaskViewEvent extends EventBus.Event {
    public enum Direction {
        UNDEFINED, UP, DOWN, LEFT, RIGHT;
    }

    public Direction direction;
    public NavigateTaskViewEvent(Direction direction) {
        this.direction = direction;
    }

    public static Direction getDirectionFromKeyCode(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                return Direction.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return Direction.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return Direction.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return Direction.RIGHT;
            default:
                return Direction.UNDEFINED;
        }
    }
}
