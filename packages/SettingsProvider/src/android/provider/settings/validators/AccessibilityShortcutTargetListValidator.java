/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.provider.settings.validators;

import static android.provider.settings.validators.SettingsValidators.COMPONENT_NAME_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.PACKAGE_NAME_VALIDATOR;

import android.text.TextUtils;

/**
 * Ensure a restored value is a string in the format the accessibility shortcut system handles
 *
 * @hide
 */
public final class AccessibilityShortcutTargetListValidator extends ListValidator {
    public AccessibilityShortcutTargetListValidator() {
        super(":");
    }

    @Override
    protected boolean isEntryValid(String entry) {
        return !TextUtils.isEmpty(entry);
    }

    @Override
    protected boolean isItemValid(String item) {
        if (TextUtils.isEmpty(item)) {
            return false;
        }
        return (COMPONENT_NAME_VALIDATOR.validate(item) || PACKAGE_NAME_VALIDATOR.validate(item));
    }
}
