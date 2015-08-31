/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.content.Context;
import android.test.AndroidTestCase;

import java.io.File;

/**
 * Tests for the DeviceOwner object that saves & loads device and policy owner information.
 * run this test with:
 *   make -j FrameworksServicesTests
 *   runtest --path frameworks/base/services/tests/servicestests/ \
 *       src/com/android/server/devicepolicy/DeviceOwnerTest.java
 */
public class OwnersTest extends AndroidTestCase {

    private static class OwnersSub extends Owners {
        private final File mLegacyFile;
        private final File mDeviceOwnerFile;
        private final File mProfileOwnerBase;

        public OwnersSub(Context context, File legacyFile, File deviceOwnerFile,
                File profileOwnerBase) {
            super(context);
            mLegacyFile = legacyFile;
            mDeviceOwnerFile = deviceOwnerFile;
            mProfileOwnerBase = profileOwnerBase;
        }

        @Override
        File getLegacyConfigFileWithTestOverride() {
            return mLegacyFile;
        }

        @Override
        File getDeviceOwnerFileWithTestOverride() {
            return mDeviceOwnerFile;
        }

        @Override
        File getProfileOwnerFileWithTestOverride(int userId) {
            return new File(mDeviceOwnerFile.getAbsoluteFile() + "-" + userId);
        }
    }

    // TODO Write tests
}
