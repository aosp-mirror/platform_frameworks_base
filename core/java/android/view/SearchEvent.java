/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * Class that contains information about an event that triggers a search.
 */
public class SearchEvent {

    private InputDevice mInputDevice;

    /** Create a new search event. */
    public SearchEvent(InputDevice inputDevice) {
        mInputDevice = inputDevice;
    }

    /**
     * Returns the {@link InputDevice} that triggered the search.
     * @return InputDevice the InputDevice that triggered the search.
     */
    public InputDevice getInputDevice() {
        return mInputDevice;
    }
}
