/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.util;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAG_IS_DISPLAY;

import android.annotation.NonNull;
import android.view.WindowManager;
import android.window.TransitionInfo;

/** Various utility functions for transitions. */
public class TransitionUtil {

    /** @return true if the transition was triggered by opening something vs closing something */
    public static boolean isOpeningType(@WindowManager.TransitionType int type) {
        return type == TRANSIT_OPEN
                || type == TRANSIT_TO_FRONT
                || type == TRANSIT_KEYGUARD_GOING_AWAY;
    }

    /** @return true if the transition was triggered by closing something vs opening something */
    public static boolean isClosingType(@WindowManager.TransitionType int type) {
        return type == TRANSIT_CLOSE || type == TRANSIT_TO_BACK;
    }

    /** Returns {@code true} if the transition has a display change. */
    public static boolean hasDisplayChange(@NonNull TransitionInfo info) {
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            if (change.getMode() == TRANSIT_CHANGE && change.hasFlags(FLAG_IS_DISPLAY)) {
                return true;
            }
        }
        return false;
    }

}
