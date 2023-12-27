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

package com.android.server.policy;

import android.annotation.Nullable;
import android.os.SystemClock;

import com.android.internal.annotations.Keep;
import com.android.server.LocalServices;

/** Policy controlling the decision and execution of window-related wake ups. */
@Keep
public interface WindowWakeUpPolicyInternal {

    /**
     * A delegate that can choose to intercept Input-related wake ups.
     *
     * <p>This delegate is not meant to control policy decisions on whether or not to wake up. The
     * policy makes that decision, and forwards the wake up request to the delegate as necessary.
     * Therefore, the role of the delegate is to handle the actual "waking" of the device in
     * response to the respective input event.
     */
    @Keep
    interface InputWakeUpDelegate {
        /**
         * Wakes up the device in response to a key event.
         *
         * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
         * @param keyCode the {@link android.view.KeyEvent} key code of the key event.
         * @param isDown {@code true} if the event's action is {@link KeyEvent#ACTION_DOWN}.
         * @return {@code true} if the delegate handled the wake up. {@code false} if the delegate
         *      decided not to handle the wake up. The policy will execute the wake up in this case.
         */
        boolean wakeUpFromKey(long eventTime, int keyCode, boolean isDown);
        /**
         * Wakes up the device in response to a motion event.
         *
         * @param eventTime the timestamp of the event in {@link SystemClock#uptimeMillis()}.
         * @param source the {@link android.view.InputDevice} source that caused the event.
         * @param isDown {@code true} if the event's action is {@link MotionEvent#ACTION_DOWN}.
         * @return {@code true} if the delegate handled the wake up. {@code false} if the delegate
         *      decided not to handle the wake up. The policy will execute the wake up in this case.
         */
        boolean wakeUpFromMotion(long eventTime, int source, boolean isDown);
    }

    /**
     * Allows injecting a delegate for controlling input-based wake ups.
     *
     * <p>A delegate can be injected to the policy by system_server components only, and should be
     * done via the {@link LocalServices} interface.
     *
     * <p>There can at most be one active delegate. If there's no delegate set (or if a {@code null}
     * delegate is set), the policy will handle waking up the device in response to input events.
     *
     * @param delegate an implementation of {@link InputWakeUpDelegate} that handles input-based
     *      wake up requests. {@code null} to let the policy handle these wake ups.
     */
    @Keep
    void setInputWakeUpDelegate(@Nullable InputWakeUpDelegate delegate);
}
