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

import static android.provider.settings.validators.SettingsValidators.ANY_STRING_VALIDATOR;
import static android.provider.settings.validators.SettingsValidators.LOCALE_VALIDATOR;

/**
 * Ensure a restored value is a string in the format the text-to-speech system handles
 *
 * @hide
 */
final class TTSListValidator extends ListValidator {

    TTSListValidator() {
        super(",");
    }

    protected boolean isEntryValid(String entry) {
        return entry != null && entry.length() > 0;
    }

    protected boolean isItemValid(String item) {
        String[] parts = item.split(":");
        // Replaces any old language separator (-) with the new one (_)
        return ((parts.length == 2)
                && (parts[0].length() > 0)
                && ANY_STRING_VALIDATOR.validate(parts[0])
                && LOCALE_VALIDATOR.validate(parts[1].replace('-', '_')));
    }
}
