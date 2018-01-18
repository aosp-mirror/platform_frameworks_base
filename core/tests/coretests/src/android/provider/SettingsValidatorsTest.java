/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.provider;

import static org.junit.Assert.fail;

import android.platform.test.annotations.Presubmit;
import android.provider.SettingsValidators.Validator;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Map;

/** Tests that ensure all backed up settings have non-null validators. */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class SettingsValidatorsTest {

    @Test
    public void ensureAllBackedUpSystemSettingsHaveValidators() {
        String offenders = getOffenders(Settings.System.SETTINGS_TO_BACKUP,
                Settings.System.VALIDATORS);

        failIfOffendersPresent(offenders, "Settings.System");
    }

    @Test
    public void ensureAllBackedUpGlobalSettingsHaveValidators() {
        String offenders = getOffenders(Settings.Global.SETTINGS_TO_BACKUP,
                Settings.Global.VALIDATORS);

        failIfOffendersPresent(offenders, "Settings.Global");
    }

    private void failIfOffendersPresent(String offenders, String settingsType) {
        if (offenders.length() > 0) {
            fail("All " + settingsType + " settings that are backed up have to have a non-null"
                    + " validator, but those don't: " + offenders);
        }
    }

    private String getOffenders(String[] settingsToBackup, Map<String, Validator> validators) {
        StringBuilder offenders = new StringBuilder();
        for (String setting : settingsToBackup) {
            if (validators.get(setting) == null) {
                offenders.append(setting).append(" ");
            }
        }
        return offenders.toString();
    }
}
