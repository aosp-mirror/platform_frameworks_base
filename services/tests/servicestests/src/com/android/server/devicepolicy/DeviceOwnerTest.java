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
    private ByteArrayOutputStream mOutputStreamForTest = new ByteArrayOutputStream();

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
        assertNull(in.getProfileOwnerPackageName(1));
    }

    @SmallTest
    public void testProfileOwnerOnly() throws Exception {
        DeviceOwner out = new DeviceOwner(null, mOutputStreamForTest);
        out.setProfileOwner("some.profile.owner.package", "some-company", 1);
        out.writeOwnerFile();

        mInputStreamForTest = new ByteArrayInputStream(mOutputStreamForTest.toByteArray());
        DeviceOwner in = new DeviceOwner(mInputStreamForTest, null);
        in.readOwnerFile();

        assertNull(in.getDeviceOwnerPackageName());
        assertNull(in.getDeviceOwnerName());
        assertEquals("some.profile.owner.package", in.getProfileOwnerPackageName(1));
        assertEquals("some-company", in.getProfileOwnerName(1));
    }

    @SmallTest
    public void testDeviceAndProfileOwners() throws Exception {
        DeviceOwner out = new DeviceOwner(null, mOutputStreamForTest);
        out.setDeviceOwner("some.device.owner.package", "owner");
        out.setProfileOwner("some.profile.owner.package", "some-company", 1);
        out.setProfileOwner("some.other.profile.owner", "some-other-company", 2);
        out.writeOwnerFile();

        mInputStreamForTest = new ByteArrayInputStream(mOutputStreamForTest.toByteArray());

        DeviceOwner in = new DeviceOwner(mInputStreamForTest, null);
        in.readOwnerFile();

        assertEquals("some.device.owner.package", in.getDeviceOwnerPackageName());
        assertEquals("owner", in.getDeviceOwnerName());
        assertEquals("some.profile.owner.package", in.getProfileOwnerPackageName(1));
        assertEquals("some-company", in.getProfileOwnerName(1));
        assertEquals("some.other.profile.owner", in.getProfileOwnerPackageName(2));
        assertEquals("some-other-company", in.getProfileOwnerName(2));
    }
}