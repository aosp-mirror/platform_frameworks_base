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

import android.content.ComponentName;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * Tests for the DeviceOwner object that saves & loads device and policy owner information.
 * run this test with:
 *   make -j FrameworksServicesTests
 *   runtest --path frameworks/base/services/tests/servicestests/ \
 *       src/com/android/server/devicepolicy/DeviceOwnerTest.java
 */
public class DeviceOwnerTest extends AndroidTestCase {

    private ByteArrayInputStream mInputStreamForTest;
    private final ByteArrayOutputStream mOutputStreamForTest = new ByteArrayOutputStream();

    @SmallTest
    public void testDeviceOwnerOnly() throws Exception {
        DeviceOwner out = new DeviceOwner(null, mOutputStreamForTest);
        out.setDeviceOwner("some.device.owner.package", "owner");
        out.writeOwnerFile();

        mInputStreamForTest = new ByteArrayInputStream(mOutputStreamForTest.toByteArray());
        DeviceOwner in = new DeviceOwner(mInputStreamForTest, null);
        in.readOwnerFile();

        assertEquals("some.device.owner.package", in.getDeviceOwnerPackageName());
        assertEquals("owner", in.getDeviceOwnerName());
        assertNull(in.getProfileOwnerComponent(1));
    }

    @SmallTest
    public void testProfileOwnerOnly() throws Exception {
        DeviceOwner out = new DeviceOwner(null, mOutputStreamForTest);
        ComponentName admin = new ComponentName(
            "some.profile.owner.package", "some.profile.owner.package.Class");
        out.setProfileOwner(admin, "some-company", 1);
        out.writeOwnerFile();

        mInputStreamForTest = new ByteArrayInputStream(mOutputStreamForTest.toByteArray());
        DeviceOwner in = new DeviceOwner(mInputStreamForTest, null);
        in.readOwnerFile();

        assertNull(in.getDeviceOwnerPackageName());
        assertNull(in.getDeviceOwnerName());
        assertEquals(admin, in.getProfileOwnerComponent(1));
        assertEquals("some-company", in.getProfileOwnerName(1));
    }

    @SmallTest
    public void testDeviceAndProfileOwners() throws Exception {
        DeviceOwner out = new DeviceOwner(null, mOutputStreamForTest);
        ComponentName profileAdmin = new ComponentName(
            "some.profile.owner.package", "some.profile.owner.package.Class");
        ComponentName otherProfileAdmin = new ComponentName(
            "some.other.profile.owner", "some.other.profile.owner.OtherClass");
        // Old code used package name rather than component name, so the class
        // bit could be empty.
        ComponentName legacyComponentName = new ComponentName("legacy.profile.owner.package", "");
        out.setDeviceOwner("some.device.owner.package", "owner");
        out.setProfileOwner(profileAdmin, "some-company", 1);
        out.setProfileOwner(otherProfileAdmin, "some-other-company", 2);
        out.setProfileOwner(legacyComponentName, "legacy-company", 3);
        out.writeOwnerFile();

        mInputStreamForTest = new ByteArrayInputStream(mOutputStreamForTest.toByteArray());

        DeviceOwner in = new DeviceOwner(mInputStreamForTest, null);
        in.readOwnerFile();

        assertEquals("some.device.owner.package", in.getDeviceOwnerPackageName());
        assertEquals("owner", in.getDeviceOwnerName());
        assertEquals(profileAdmin, in.getProfileOwnerComponent(1));
        assertEquals("some-company", in.getProfileOwnerName(1));
        assertEquals(otherProfileAdmin, in.getProfileOwnerComponent(2));
        assertEquals("some-other-company", in.getProfileOwnerName(2));
        assertEquals(legacyComponentName, in.getProfileOwnerComponent(3));
    }
}
