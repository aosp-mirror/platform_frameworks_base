/*
 * Copyright (C) 2010 The Android Open Source Project
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
package android.webkit;

interface ZoomControlBase {

    /**
     * Causes the on-screen zoom control to be made visible
     */
    public void show();

    /**
     * Causes the on-screen zoom control to disappear
     */
    public void hide();

    /**
     * Enables the control to update its state if necessary in response to a
     * change in the pages zoom level. For example, if the max zoom level is
     * reached then the control can disable the button for zooming in.
     */
    public void update();

    /**
     * Checks to see if the control is currently visible to the user.
     */
    public boolean isVisible();
}
