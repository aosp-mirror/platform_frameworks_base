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

package com.android.settingslib.drawer;

import androidx.annotation.NonNull;

/**
 * A controller that manages events for switch.
 *
 * @deprecated Use {@link EntriesProvider} with {@link ProviderSwitch} instead.
 */
@Deprecated
public abstract class SwitchController extends EntryController implements ProviderSwitch {


    /**
     * Returns the key for this switch.
     */
    public abstract String getSwitchKey();

    /**
     * Returns the checked state of this switch.
     */
    protected abstract boolean isChecked();

    /**
     * Called when the checked state of this switch is changed.
     *
     * @return true if the checked state was successfully changed, otherwise false
     */
    protected abstract boolean onCheckedChanged(boolean checked);

    /**
     * Returns the error message which will be toasted when {@link #onCheckedChanged} returns false.
     */
    protected abstract String getErrorMessage(boolean attemptedChecked);

    @Override
    public String getKey() {
        return getSwitchKey();
    }

    @Override
    public boolean isSwitchChecked() {
        return isChecked();
    }

    @Override
    public boolean onSwitchCheckedChanged(boolean checked) {
        return onCheckedChanged(checked);
    }

    @Override
    public String getSwitchErrorMessage(boolean attemptedChecked) {
        return getErrorMessage(attemptedChecked);
    }

    /**
     * Same as {@link EntryController.MetaData}, for backwards compatibility purpose.
     *
     * @deprecated Use {@link EntryController.MetaData} instead.
     */
    @Deprecated
    protected static class MetaData extends EntryController.MetaData {
        /**
         * @param category the category of the switch. This value must be from {@link CategoryKey}.
         *
         * @deprecated Use {@link EntryController.MetaData} instead.
         */
        @Deprecated
        public MetaData(@NonNull String category) {
            super(category);
        }
    }
}
