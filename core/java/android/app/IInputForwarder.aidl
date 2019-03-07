/**
 * Copyright (c) 2017, The Android Open Source Project
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

package android.app;

import android.view.InputEvent;

/**
 * Forwards input events into owned activity container, used in {@link android.app.ActivityView}.
 * To forward input to other apps {@link android.Manifest.permission.INJECT_EVENTS} permission is
 * required.
 * @hide
 */
interface IInputForwarder {
    @UnsupportedAppUsage
    boolean forwardEvent(in InputEvent event);
}