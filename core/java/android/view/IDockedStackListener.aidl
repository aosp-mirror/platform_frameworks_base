/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

/**
  * Listener for showing/hiding of the dock divider. Will fire when an app is shown in side by side
  * mode and a divider should be shown.
  *
  * @hide
  */
oneway interface IDockedStackListener {

    /**
     * Will fire when an app is shown in side by side mode and a divider should be shown.
     */
    void onDividerVisibilityChanged(boolean visible);

    /**
     * Called when the docked stack gets created or removed.
     */
    void onDockedStackExistsChanged(boolean exists);

    /**
     * Called when window manager decides to minimize the docked stack. The divider should make
     * itself not interactable and shrink a bit in this state.
     *
     * @param minimized Whether the docked stack is currently minimized.
     * @param animDuration The duration of the animation for changing the minimized state.
     */
    void onDockedStackMinimizedChanged(boolean minimized, long animDuration);

    /**
     * Called when window manager decides to adjust the divider for IME. Like the minimized state,
     * the divider should make itself not interactable and shrink a bit, but in a different way.s
     *
     * @param minimized Whether the stacks are currently adjusted for the IME
     * @param animDuration The duration of the animation for changing the adjusted state.
     */
    void onAdjustedForImeChanged(boolean adjustedForIme, long animDuration);

    /**
     * Called when window manager repositioned the docked stack after a screen rotation change.
     */
    void onDockSideChanged(int newDockSide);
}
