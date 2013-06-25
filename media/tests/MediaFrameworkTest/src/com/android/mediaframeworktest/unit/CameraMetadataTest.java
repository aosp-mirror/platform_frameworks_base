/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;
import android.hardware.photography.CameraMetadata;

/**
 * <pre>
 * adb shell am instrument \
 *      -e class 'com.android.mediaframeworktest.unit.CameraMetadataTest' \
 *      -w com.android.mediaframeworktest/.MediaFrameworkUnitTestRunner
 * </pre>
 */
public class CameraMetadataTest extends junit.framework.TestCase {

    CameraMetadata mMetadata;
    Parcel mParcel;

    @Override
    public void setUp() {
        mMetadata = new CameraMetadata();
        mParcel = Parcel.obtain();
    }

    @Override
    public void tearDown() throws Exception {
        mMetadata.close();
        mMetadata = null;

        mParcel.recycle();
        mParcel = null;
    }

    @SmallTest
    public void testNew() {
        assertEquals(0, mMetadata.getEntryCount());
        assertTrue(mMetadata.isEmpty());
    }

    @SmallTest
    public void testClose() throws Exception {
        mMetadata.isEmpty(); // no throw

        assertFalse(mMetadata.isClosed());

        mMetadata.close();

        assertTrue(mMetadata.isClosed());

        // OK: second close should not throw
        mMetadata.close();

        assertTrue(mMetadata.isClosed());

        // All other calls after close should throw IllegalStateException

        try {
            mMetadata.isEmpty();
            fail("Unreachable -- isEmpty after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
            // good: we expect calling this method after close to fail
        }

        try {
            mMetadata.getEntryCount();
            fail("Unreachable -- getEntryCount after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
            // good: we expect calling this method after close to fail
        }


        try {
            mMetadata.swap(mMetadata);
            fail("Unreachable -- swap after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
         // good: we expect calling this method after close to fail
        }

        try {
            mMetadata.readFromParcel(mParcel);
            fail("Unreachable -- readFromParcel after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
         // good: we expect calling this method after close to fail
        }

        try {
            mMetadata.writeToParcel(mParcel, /*flags*/0);
            fail("Unreachable -- writeToParcel after close should throw IllegalStateException");
        } catch (IllegalStateException e) {
         // good: we expect calling this method after close to fail
        }
    }
}
