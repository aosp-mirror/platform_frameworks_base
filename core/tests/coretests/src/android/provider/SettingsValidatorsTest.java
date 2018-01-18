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
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests that ensure all backed up settings have non-null validators. */
@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class SettingsValidatorsTest {

    @Test
    public void ensureAllBackedUpSystemSettingsHaveValidators() {
        StringBuilder offenders = new StringBuilder();
        for (String setting : Settings.System.SETTINGS_TO_BACKUP) {
            if (Settings.System.VALIDATORS.get(setting) == null) {
                offenders.append(setting).append(" ");
            }
        }

        // if there're any offenders fail the test and report them
        String offendersStr = offenders.toString();
        if (offendersStr.length() > 0) {
            fail("All Settings.System settings that are backed up have to have a non-null"
                    + " validator, but those don't: " + offendersStr);
        }
    }
}
