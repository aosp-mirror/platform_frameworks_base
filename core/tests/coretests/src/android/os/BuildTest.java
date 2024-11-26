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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.platform.test.flag.junit.SetFlagsRule;
import android.platform.test.ravenwood.RavenwoodRule;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import junit.framework.Assert;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Provides test cases for android.os.Build and, in turn, many of the
 * system properties set by the build system.
 */
@RunWith(AndroidJUnit4.class)
public class BuildTest {
    private static final String TAG = "BuildTest";

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(
                SetFlagsRule.DefaultInitValueType.NULL_DEFAULT);

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
    @Test
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
        assertNotEmpty("VERSION.RELEASE", Build.VERSION.RELEASE_OR_CODENAME);
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

    @Test
    public void testFlagEnabled() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_ANDROID_OS_BUILD_VANILLA_ICE_CREAM);
        assertTrue(Flags.androidOsBuildVanillaIceCream());
    }

    @Test
    public void testFlagDisabled() throws Exception {
        mSetFlagsRule.disableFlags(Flags.FLAG_ANDROID_OS_BUILD_VANILLA_ICE_CREAM);
        assertFalse(Flags.androidOsBuildVanillaIceCream());
    }

    @Test
    public void testParseFullVersionCorrectInputMajorDotMinor() throws Exception {
        int version = Build.parseFullVersion("12.34");
        assertEquals(12, Build.getMajorSdkVersion(version));
        assertEquals(34, Build.getMinorSdkVersion(version));
    }

    @Test
    public void testParseFullVersionCorrectInputOmitDotMinor() throws Exception {
        int version = Build.parseFullVersion("1234");
        assertEquals(1234, Build.getMajorSdkVersion(version));
        assertEquals(0, Build.getMinorSdkVersion(version));
    }

    @Test
    public void testParseFullVersionCorrectInputCurDevelopment() throws Exception {
        int version = Build.parseFullVersion(Integer.toString(Build.VERSION_CODES.CUR_DEVELOPMENT));
        assertEquals(Build.VERSION_CODES.CUR_DEVELOPMENT, Build.getMajorSdkVersion(version));
        assertEquals(0, Build.getMinorSdkVersion(version));
    }

    @Test
    public void testParseFullVersionIncorrectInputEmptyString() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            Build.parseFullVersion("");
        });
    }

    @Test
    public void testParseFullVersionIncorrectInputNoNumbersInString() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            Build.parseFullVersion("foobar");
        });
    }

    @Test
    public void testParseFullVersionIncorrectInputUnexpectedPatchVersion() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            Build.parseFullVersion("1.2.3");
        });
    }

    @Test
    public void testParseFullVersionIncorrectInputLeadingDotMissingMajorVersion() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            Build.parseFullVersion(".1234");
        });
    }

    @Test
    public void testParseFullVersionIncorrectInputTrailingDotMissingMinorVersion()
            throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            Build.parseFullVersion("1234.");
        });
    }

    @Test
    public void testParseFullVersionIncorrectInputNegativeMajorVersion() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            Build.parseFullVersion("-12.34");
        });
    }

    @Test
    public void testParseFullVersionIncorrectInputNegativeMinorVersion() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            Build.parseFullVersion("12.-34");
        });
    }

    @Test
    public void testFullVersionToStringCorrectInput() throws Exception {
        assertEquals("0.0", Build.fullVersionToString(0));
        assertEquals("1.0", Build.fullVersionToString(1 * 100000 + 0));
        assertEquals("1.1", Build.fullVersionToString(1 * 100000 + 1));
        assertEquals("12.34", Build.fullVersionToString(12 * 100000 + 34));
    }

    @Test
    public void testFullVersionToStringSameStringAfterRoundTripViaParseFullVersion()
            throws Exception {
        String s = "12.34";
        int major = Build.getMajorSdkVersion(Build.parseFullVersion(s));
        int minor = Build.getMinorSdkVersion(Build.parseFullVersion(s));
        assertEquals(s, Build.fullVersionToString(major * 100000 + minor));
    }

    @Test
    public void testFullVersionToStringIncorrectInputNegativeVersion() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            Build.fullVersionToString(-1);
        });
    }
}
