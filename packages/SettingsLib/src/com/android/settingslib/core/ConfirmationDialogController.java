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

package com.android.settingslib.core;

import androidx.annotation.Nullable;
import androidx.preference.Preference;

/**
 * Interface for {@link AbstractPreferenceController} objects which manage confirmation dialogs
 */
public interface ConfirmationDialogController {
    /**
     * Returns the key for this preference.
     */
    String getPreferenceKey();

    /**
     * Shows the dialog
     * @param preference Preference object relevant to the dialog being shown
     */
    void showConfirmationDialog(@Nullable Preference preference);

    /**
     * Dismiss the dialog managed by this object
     */
    void dismissConfirmationDialog();

    /**
     * @return {@code true} if the dialog is showing
     */
    boolean isConfirmationDialogShowing();
}
