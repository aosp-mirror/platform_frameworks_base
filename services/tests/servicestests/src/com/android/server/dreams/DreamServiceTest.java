/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.dreams;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.service.dreams.DreamService;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamServiceTest {
    @Test
    public void testMetadataParsing() throws PackageManager.NameNotFoundException {
        final String testPackageName = "com.android.frameworks.servicestests";
        final String testDreamClassName = "com.android.server.dreams.TestDreamService";
        final String testSettingsActivity = "com.android.server.dreams/.TestDreamSettingsActivity";

        final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        final ServiceInfo si = context.getPackageManager().getServiceInfo(
                new ComponentName(testPackageName, testDreamClassName),
                PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA));
        final DreamService.DreamMetadata metadata = DreamService.getDreamMetadata(context, si);

        assertEquals(0, metadata.settingsActivity.compareTo(
                ComponentName.unflattenFromString(testSettingsActivity)));
        assertFalse(metadata.showComplications);
    }
}
