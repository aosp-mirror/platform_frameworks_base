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

package com.android.settingslib.drawer;

/**
 *  Interface for {@link EntryController} whose instances support switch widget provided from the
 *  content provider
 */
public interface ProviderSwitch {
    /**
     * Returns the checked state of this switch.
     */
    boolean isSwitchChecked();

    /**
     * Called when the checked state of this switch is changed.
     *
     * @return true if the checked state was successfully changed, otherwise false
     */
    boolean onSwitchCheckedChanged(boolean checked);

    /**
     * Returns the error message which will be toasted when {@link #onSwitchCheckedChanged} returns
     * false.
     */
    String getSwitchErrorMessage(boolean attemptedChecked);
}
