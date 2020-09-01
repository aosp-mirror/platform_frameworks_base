/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content;

import static org.junit.Assert.assertEquals;

import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ApexEnvironmentTest {

    @Test
    public void dataDirectoryPathsAreAsExpected() {
        ApexEnvironment apexEnvironment = ApexEnvironment.getApexEnvironment("my.apex");

        assertEquals("/data/misc/apexdata/my.apex",
                apexEnvironment.getDeviceProtectedDataDir().getAbsolutePath());

        assertEquals("/data/misc_de/5/apexdata/my.apex",
                apexEnvironment
                        .getDeviceProtectedDataDirForUser(UserHandle.of(5)).getAbsolutePath());

        assertEquals("/data/misc_ce/16/apexdata/my.apex",
                apexEnvironment.getCredentialProtectedDataDirForUser(
                        UserHandle.of(16)).getAbsolutePath());
    }
}
