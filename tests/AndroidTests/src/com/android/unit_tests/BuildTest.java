/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.unit_tests;

import android.os.Build;
import android.server.data.BuildData;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import junit.framework.Assert;
import junit.framework.TestCase;

/**
 * Provides test cases for android.os.Build and android.server.data.BuildData,
 * and, in turn, many of the system properties set by the build system.
 */
public class BuildTest extends TestCase {

    private static final String TAG = "BuildTest";

    /**
     * Asserts that a String is non-null and non-empty.  If it is not,
     * an AssertionFailedError is thrown with the given message.
     */
    private static void assertNotEmpty(String message, String string) {
        //Log.i(TAG, "" + message + ": " + string);
        assertNotNull(message, string);
        assertFalse(message, string.equals(""));
    }

    /**
     * Asserts that a String is non-null and non-empty.  If it is not,
     * an AssertionFailedError is thrown.
     */
    private static void assertNotEmpty(String string) {
        assertNotEmpty(null, string);
    }

    /**
     * Asserts that all android.os.Build fields are non-empty and/or in a valid range.
     */
    @SmallTest
    public void testBuildFields() throws Exception {
        assertNotEmpty("ID", Build.ID);
        assertNotEmpty("DISPLAY", Build.DISPLAY);
        assertNotEmpty("PRODUCT", Build.PRODUCT);
        assertNotEmpty("DEVICE", Build.DEVICE);
        assertNotEmpty("BOARD", Build.BOARD);
        assertNotEmpty("BRAND", Build.BRAND);
        assertNotEmpty("MODEL", Build.MODEL);
        assertNotEmpty("VERSION.INCREMENTAL", Build.VERSION.INCREMENTAL);
        assertNotEmpty("VERSION.RELEASE", Build.VERSION.RELEASE);
        assertNotEmpty("TYPE", Build.TYPE);
        Assert.assertNotNull("TAGS", Build.TAGS); // TAGS is allowed to be empty.
        assertNotEmpty("FINGERPRINT", Build.FINGERPRINT);
        Assert.assertTrue("TIME", Build.TIME > 0);
        assertNotEmpty("USER", Build.USER);
        assertNotEmpty("HOST", Build.HOST);

        // TODO: if any of the android.os.Build fields have additional constraints
        // (e.g., must be a C identifier, must be a valid filename, must not contain any spaces)
        // add tests for them.
    }

    /**
     * Asserts that android.server.data.BuildData behaves as expected.
     */
    @SmallTest
    public void testBuildData() throws Exception {
        BuildData bd;

        /*
         * Default constructor
         */
        bd = new BuildData();
        assertNotEmpty(bd.getFingerprint());
        assertNotEmpty(bd.getIncrementalVersion());
        Assert.assertTrue(bd.getTime() > 0);

        /*
         * Explicit constructor
         */
        final String FINGERPRINT = "fingerprint";
        final String INCREMENTAL_VERSION = "74321";  // a valid long, for the serialization test
        final long TIME = 12345;
        bd = new BuildData(FINGERPRINT, INCREMENTAL_VERSION, TIME);
        Assert.assertEquals(FINGERPRINT, bd.getFingerprint());
        Assert.assertEquals(INCREMENTAL_VERSION, bd.getIncrementalVersion());
        Assert.assertTrue(bd.getTime() == TIME);

// The serialization methods are package-private.
//
// import java.io.ByteArrayInputStream;
// import java.io.ByteArrayOutputStream;
// import java.io.DataInputStream;
// import java.io.DataOutputStream;
//
//        /*
//         * Serialization
//         */
//        ByteArrayOutputStream out = new ByteArrayOutputStream();
//        bd.write(new DataOutputStream(out));
//        Assert.assertTrue(out.size() > 0);
//
//        /*
//         * Deserialization
//         *
//         * The current version of BuildData converts the incremental version to
//         * and from a long when serializing/deserializing.  Future versions should
//         * treat it as a string.
//         */
//        BuildData bd2 =
//                new BuildData(new DataInputStream(new ByteArrayInputStream(out.toByteArray())));
//        Assert.assertEquals(bd.getFingerprint(), bd2.getFingerprint());
//        Assert.assertEquals(bd.getIncrementalVersion(), bd2.getIncrementalVersion());
//        Assert.assertTrue(bd.getTime() == bd2.getTime());
    }
}
