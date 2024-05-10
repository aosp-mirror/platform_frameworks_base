/**
 * Copyright (c) 2019, The Android Open Source Project
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
 * Listener for changes to gesture interception detector running at DecorView.
 *
 * {@hide}
 */
oneway interface IDecorViewGestureListener {
    /**
     * Called when a DecorView has started intercepting gesture.
     *
     * @param windowToken Where did this gesture interception result comes from.
     * @param intercepted Whether the gesture interception detector has started interception.
     */
    void onInterceptionChanged(in IBinder windowToken, in boolean intercepted);
}
