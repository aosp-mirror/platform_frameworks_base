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

package com.android.overlaytest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.UiAutomation;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.util.AttributeSet;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.ArrayUtils;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Ignore
public abstract class OverlayBaseTest {
    private Resources mResources;
    private final int mMode;
    static final int MODE_NO_OVERLAY = 0;
    static final int MODE_SINGLE_OVERLAY = 1;
    static final int MODE_MULTIPLE_OVERLAYS = 2;

    static final String APP_OVERLAY_ONE_PKG = "com.android.overlaytest.app_overlay_one";
    static final String APP_OVERLAY_TWO_PKG = "com.android.overlaytest.app_overlay_two";
    static final String FRAMEWORK_OVERLAY_PKG = "com.android.overlaytest.framework";

    protected OverlayBaseTest(int mode) {
        mMode = mode;
    }

    @Before
    public void setUp() {
        mResources = InstrumentationRegistry.getContext().getResources();
    }

    private int calculateRawResourceChecksum(int resId) throws Throwable {
        try (InputStream input = mResources.openRawResource(resId)) {
            int ch, checksum = 0;
            while ((ch = input.read()) != -1) {
                checksum = (checksum + ch) % 0xffddbb00;
            }
            return checksum;
        }
    }

    private void setLocale(Locale locale) {
        final LocaleList locales = new LocaleList(locale);
        LocaleList.setDefault(locales);
        Configuration config = new Configuration();
        config.setLocales(locales);
        mResources.updateConfiguration(config, mResources.getDisplayMetrics());
    }

    private boolean getExpected(boolean no, boolean so, boolean mo) {
        switch (mMode) {
            case MODE_NO_OVERLAY:
                return no;
            case MODE_SINGLE_OVERLAY:
                return so;
            case MODE_MULTIPLE_OVERLAYS:
                return mo;
            default:
                fail("Unknown mode!");
                return no;
        }
    }

    private String getExpected(String no, String so, String mo) {
        switch (mMode) {
            case MODE_NO_OVERLAY:
                return no;
            case MODE_SINGLE_OVERLAY:
                return so;
            case MODE_MULTIPLE_OVERLAYS:
                return mo;
            default:
                fail("Unknown mode!");
                return no;
        }
    }

    private int getExpected(int no, int so, int mo) {
        switch (mMode) {
            case MODE_NO_OVERLAY:
                return no;
            case MODE_SINGLE_OVERLAY:
                return so;
            case MODE_MULTIPLE_OVERLAYS:
                return mo;
            default:
                fail("Unknown mode!");
                return no;
        }
    }

    private int[] getExpected(int[] no, int[] so, int[] mo) {
        switch (mMode) {
            case MODE_NO_OVERLAY:
                return no;
            case MODE_SINGLE_OVERLAY:
                return so;
            case MODE_MULTIPLE_OVERLAYS:
                return mo;
            default:
                fail("Unknown mode!");
                return no;
        }
    }

    private void assertResource(int resId, boolean no, boolean so, boolean mo) throws Throwable {
        boolean expected = getExpected(no, so, mo);
        boolean actual = mResources.getBoolean(resId);
        assertEquals(expected, actual);
    }

    private void assertResource(int resId, int no, int so, int mo) throws Throwable {
        int expected = getExpected(no, so, mo);
        int actual = mResources.getInteger(resId);
        assertEquals(expected, actual);
    }

    private void assertResource(int resId, String no, String so, String mo) throws Throwable {
        String expected = getExpected(no, so, mo);
        String actual = mResources.getString(resId);
        assertEquals(expected, actual);
    }

    private void assertResource(int resId, int[] no, int[] so, int[] mo) throws Throwable {
        int[] expected = getExpected(no, so, mo);
        int[] actual = mResources.getIntArray(resId);
        assertEquals("length:", expected.length, actual.length);
        for (int i = 0; i < actual.length; ++i) {
            assertEquals("index " + i + ":", actual[i], expected[i]);
        }
    }

    @Test
    public void testFrameworkBooleanOverlay() throws Throwable {
        // config_annoy_dianne has the value:
        // - true when no overlay exists (MODE_NO_OVERLAY)
        // - false when a single overlay exists (MODE_SINGLE_OVERLAY)
        // - false when multiple overlays exists (MODE_MULTIPLE_OVERLAYS)
        final int resId = com.android.internal.R.bool.config_annoy_dianne;
        assertResource(resId, true, false, false);
    }

    @Test
    public void testBooleanOverlay() throws Throwable {
        // usually_false has the value:
        // - false when no overlay exists (MODE_NO_OVERLAY)
        // - true when a single overlay exists (MODE_SINGLE_OVERLAY)
        // - false when multiple overlays exists (MODE_MULTIPLE_OVERLAYS)
        final int resId = R.bool.usually_false;
        assertResource(resId, false, true, false);
    }

    @Test
    public void testBoolean() throws Throwable {
        // always_true has no overlay
        final int resId = R.bool.always_true;
        assertResource(resId, true, true, true);
    }

    @Test
    public void testIntegerArrayOverlay() throws Throwable {
        // fibonacci has values:
        // - eight first values of Fibonacci sequence, when no overlay exists (MODE_NO_OVERLAY)
        // - eight first values of Fibonacci sequence (reversed), for single and multiple overlays
        //   (MODE_SINGLE_OVERLAY, MODE_MULTIPLE_OVERLAYS)
        final int resId = R.array.fibonacci;
        assertResource(resId,
                new int[]{1, 1, 2, 3, 5, 8, 13, 21},
                new int[]{21, 13, 8, 5, 3, 2, 1, 1},
                new int[]{21, 13, 8, 5, 3, 2, 1, 1});
    }

    @Test
    public void testIntegerArray() throws Throwable {
        // prime_numbers has no overlay
        final int resId = R.array.prime_numbers;
        final int[] expected = {2, 3, 5, 7, 11, 13, 17, 19};
        assertResource(resId, expected, expected, expected);
    }

    @Test
    public void testDrawable() throws Throwable {
        // drawable-nodpi/drawable has overlay (default config)
        final int resId = R.drawable.drawable;
        int actual = calculateRawResourceChecksum(resId);
        int expected = 0;
        switch (mMode) {
            case MODE_NO_OVERLAY:
                expected = 0x00005665;
                break;
            case MODE_SINGLE_OVERLAY:
            case MODE_MULTIPLE_OVERLAYS:
                expected = 0x000051da;
                break;
            default:
                fail("Unknown mode " + mMode);
        }
        assertEquals(expected, actual);
    }

    @Test
    public void testAppString() throws Throwable {
        final int resId = R.string.str;
        assertResource(resId, "none", "single", "multiple");
    }

    @Test
    public void testApp2() throws Throwable {
        final int resId = R.string.str2; // only in base package and first app overlay
        assertResource(resId, "none", "single", "single");
    }

    @Test
    public void testAppXml() throws Throwable {
        int expected = getExpected(0, 1, 2);
        int actual = -1;
        XmlResourceParser parser = mResources.getXml(R.xml.integer);
        int type = parser.getEventType();
        while (type != XmlResourceParser.END_DOCUMENT && actual == -1) {
            if (type == XmlResourceParser.START_TAG && "integer".equals(parser.getName())) {
                AttributeSet as = Xml.asAttributeSet(parser);
                actual = as.getAttributeIntValue(null, "value", -1);
            }
            type = parser.next();
        }
        parser.close();
        assertEquals(expected, actual);
    }

    @Test
    public void testAppRaw() throws Throwable {
        final int resId = R.raw.lorem_ipsum;

        InputStream input = null;
        BufferedReader reader = null;
        String actual = "";
        try {
            input = mResources.openRawResource(resId);
            reader = new BufferedReader(new InputStreamReader(input));
            actual = reader.readLine();
        } finally {
            if (reader != null) {
                reader.close();
            }
            if (input != null) {
                input.close();
            }
        }

        final String no = "Lorem ipsum dolor sit amet, consectetur adipisicing elit, sed do "
                + "eiusmod tempor incididunt ut labore et dolore magna aliqua. Ut enim ad minim "
                + "veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo "
                + "consequat. Duis aute irure dolor in reprehenderit in voluptate velit esse "
                + "cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat cupidatat non "
                + "proident, sunt in culpa qui officia deserunt mollit anim id est laborum.";
        final String so = "Lorem ipsum: single overlay.";
        final String mo = "Lorem ipsum: multiple overlays.";

        assertEquals(getExpected(no, so, mo), actual);
    }

    @Test
    public void testAssetsNotPossibleToOverlay() throws Throwable {
        final AssetManager am = mResources.getAssets();

        // AssetManager#list will include assets from all loaded non-overlay
        // APKs, including the framework; framework-res.apk contains at least
        // assets/{images,webkit}. Rather than checking the list, verify that
        // assets only present in overlays are never part of the list.
        String[] files = am.list("");
        assertTrue(ArrayUtils.contains(files, "package-name.txt"));
        assertFalse(ArrayUtils.contains(files, "foo.txt"));
        assertFalse(ArrayUtils.contains(files, "bar.txt"));

        String contents = null;
        try (InputStream is = am.open("package-name.txt")) {
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder str = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                str.append(line);
            }
            contents = str.toString();
        }
        assertEquals("com.android.overlaytest", contents);
    }

    /*
     * testMatrix* tests
     *
     * The naming convention textMatrixABCDEF refers to in which packages and
     * which configurations a resource is defined (1 if the resource is
     * defined). If defined, a slot is always given the same value.
     *
     * SLOT  PACKAGE           CONFIGURATION  VALUE
     * A     target package    (default)      100
     * B     target package    -sv            200
     * C     AppOverlayOne     (default)      300
     * D     AppOverlayOne     -sv            400
     * E     AppOverlayTwo     (default)      500
     * F     AppOverlayTwo     -sv            600
     *
     * Example: in testMatrix101110, the base package defines the
     * R.integer.matrix101110 resource for the default configuration (value
     * 100), OverlayAppFirst defines it for both default and Swedish
     * configurations (values 300 and 400, respectively), and OverlayAppSecond
     * defines it for the default configuration (value 500). If both overlays
     * are loaded, the expected value after setting the language to Swedish is
     * 400.
     */
    @Test
    public void testMatrix100000() throws Throwable {
        final int resId = R.integer.matrix_100000;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 100, 100);
    }

    @Test
    public void testMatrix100001() throws Throwable {
        final int resId = R.integer.matrix_100001;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 100, 600);
    }

    @Test
    public void testMatrix100010() throws Throwable {
        final int resId = R.integer.matrix_100010;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 100, 500);
    }

    @Test
    public void testMatrix100011() throws Throwable {
        final int resId = R.integer.matrix_100011;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 100, 600);
    }

    @Test
    public void testMatrix100100() throws Throwable {
        final int resId = R.integer.matrix_100100;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 400, 400);
    }

    @Test
    public void testMatrix100101() throws Throwable {
        final int resId = R.integer.matrix_100101;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 400, 600);
    }

    @Test
    public void testMatrix100110() throws Throwable {
        final int resId = R.integer.matrix_100110;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 400, 400);
    }

    @Test
    public void testMatrix100111() throws Throwable {
        final int resId = R.integer.matrix_100111;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 400, 600);
    }

    @Test
    public void testMatrix101000() throws Throwable {
        final int resId = R.integer.matrix_101000;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 300, 300);
    }

    @Test
    public void testMatrix101001() throws Throwable {
        final int resId = R.integer.matrix_101001;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 300, 600);
    }

    @Test
    public void testMatrix101010() throws Throwable {
        final int resId = R.integer.matrix_101010;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 300, 500);
    }

    @Test
    public void testMatrix101011() throws Throwable {
        final int resId = R.integer.matrix_101011;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 300, 600);
    }

    @Test
    public void testMatrix101100() throws Throwable {
        final int resId = R.integer.matrix_101100;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 400, 400);
    }

    @Test
    public void testMatrix101101() throws Throwable {
        final int resId = R.integer.matrix_101101;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 400, 600);
    }

    @Test
    public void testMatrix101110() throws Throwable {
        final int resId = R.integer.matrix_101110;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 400, 400);
    }

    @Test
    public void testMatrix101111() throws Throwable {
        final int resId = R.integer.matrix_101111;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 100, 400, 600);
    }

    @Test
    public void testMatrix110000() throws Throwable {
        final int resId = R.integer.matrix_110000;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 200, 200);
    }

    @Test
    public void testMatrix110001() throws Throwable {
        final int resId = R.integer.matrix_110001;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 200, 600);
    }

    @Test
    public void testMatrix110010() throws Throwable {
        final int resId = R.integer.matrix_110010;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 200, 200);
    }

    @Test
    public void testMatrix110011() throws Throwable {
        final int resId = R.integer.matrix_110011;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 200, 600);
    }

    @Test
    public void testMatrix110100() throws Throwable {
        final int resId = R.integer.matrix_110100;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 400, 400);
    }

    @Test
    public void testMatrix110101() throws Throwable {
        final int resId = R.integer.matrix_110101;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 400, 600);
    }

    @Test
    public void testMatrix110110() throws Throwable {
        final int resId = R.integer.matrix_110110;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 400, 400);
    }

    @Test
    public void testMatrix110111() throws Throwable {
        final int resId = R.integer.matrix_110111;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 400, 600);
    }

    @Test
    public void testMatrix111000() throws Throwable {
        final int resId = R.integer.matrix_111000;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 200, 200);
    }

    @Test
    public void testMatrix111001() throws Throwable {
        final int resId = R.integer.matrix_111001;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 200, 600);
    }

    @Test
    public void testMatrix111010() throws Throwable {
        final int resId = R.integer.matrix_111010;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 200, 200);
    }

    @Test
    public void testMatrix111011() throws Throwable {
        final int resId = R.integer.matrix_111011;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 200, 600);
    }

    @Test
    public void testMatrix111100() throws Throwable {
        final int resId = R.integer.matrix_111100;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 400, 400);
    }

    @Test
    public void testMatrix111101() throws Throwable {
        final int resId = R.integer.matrix_111101;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 400, 600);
    }

    @Test
    public void testMatrix111110() throws Throwable {
        final int resId = R.integer.matrix_111110;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 400, 400);
    }

    @Test
    public void testMatrix111111() throws Throwable {
        final int resId = R.integer.matrix_111111;
        setLocale(new Locale("sv", "SE"));
        assertResource(resId, 200, 400, 600);
    }

    /**
     * Executes the shell command and reads all the output to ensure the command ran and didn't
     * get stuck buffering on output.
     */
    protected static String executeShellCommand(UiAutomation automation, String command)
            throws Exception {
        final ParcelFileDescriptor pfd = automation.executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            final BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder str = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                str.append(line);
            }
            return str.toString();
        }
    }

    /**
     * Enables overlay packages and waits for a configuration change event before
     * returning, to guarantee that Resources are up-to-date.
     * @param packages the list of package names to enable.
     */
    protected static void enableOverlayPackages(String... packages) throws Exception {
        enableOverlayPackages(true, packages);
    }

    /**
     * Disables overlay packages and waits for a configuration change event before
     * returning, to guarantee that Resources are up-to-date.
     * @param packages the list of package names to disable.
     */
    protected static void disableOverlayPackages(String... packages) throws Exception {
        enableOverlayPackages(false, packages);
    }

    /**
     * Enables/disables overlay packages and waits for a configuration change event before
     * returning, to guarantee that Resources are up-to-date.
     * @param enable enables the overlays when true, disables when false.
     * @param packages the list of package names to enable/disable.
     */
    private static void enableOverlayPackages(boolean enable, String[] packages)
            throws Exception {
        final UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation();
        for (final String pkg : packages) {
            executeShellCommand(uiAutomation,
                    "cmd overlay " + (enable ? "enable " : "disable ") + pkg);
        }

        // Wait for the overlay change to propagate.
        Thread.sleep(1000);
    }
}
