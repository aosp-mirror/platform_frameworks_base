/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app;

/**
 * Interface to control the ANR dialog within the activity manager
 * {@hide}
 */
public interface AnrController {
    /**
     * Returns the delay in milliseconds for an ANR dialog that is about to be shown for
     * {@code packageName} with {@code uid}.
     *
     * Implementations should only return a positive value if they actually expect the
     * {@code packageName} to be delayed due to them.

     * If there are multiple controllers registered, the controller with the max delay will
     * be selected and will receive an {@link #onAnrDelayStarted} callback at the start of the
     * delay and an {@link #onAnrDelayCompleted} at the end of the delay.
     */
    long getAnrDelayMillis(String packageName, int uid);

    /**
     * Notifies the controller at the start of the ANR dialog delay for {@code packageName} with
     * {@code uid}. The controller can decide to show a progress UI after this notification.
     */
    void onAnrDelayStarted(String packageName, int uid);

    /**
     * Notifies the controller at the end of the ANR dialog delay for {@code packageName} with
     * {@code uid}.
     *
     * @return whether the ANR dialog should be shown or cancelled. {@code true} if the
     * ANR dialog should be shown, {@code false} if it should be cancelled.
     */
    boolean onAnrDelayCompleted(String packageName, int uid);
}
