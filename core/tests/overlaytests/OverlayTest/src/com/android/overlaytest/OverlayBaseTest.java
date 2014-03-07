package com.android.overlaytest;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.test.AndroidTestCase;
import android.util.AttributeSet;
import android.util.Xml;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Locale;

public abstract class OverlayBaseTest extends AndroidTestCase {
    private Resources mResources;
    protected int mMode; // will be set by subclasses
    static final protected int MODE_NO_OVERLAY = 0;
    static final protected int MODE_SINGLE_OVERLAY = 1;
    static final protected int MODE_MULTIPLE_OVERLAYS = 2;

    protected void setUp() {
        mResources = getContext().getResources();
    }

    private int calculateRawResourceChecksum(int resId) throws Throwable {
        InputStream input = null;
        try {
            input = mResources.openRawResource(resId);
            int ch, checksum = 0;
            while ((ch = input.read()) != -1) {
                checksum = (checksum + ch) % 0xffddbb00;
            }
            return checksum;
        } finally {
            input.close();
        }
    }

    private void setLocale(String code) {
        Locale locale = new Locale(code);
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.locale = locale;
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

    public void testFrameworkBooleanOverlay() throws Throwable {
        // config_annoy_dianne has the value:
        // - true when no overlay exists (MODE_NO_OVERLAY)
        // - false when a single overlay exists (MODE_SINGLE_OVERLAY)
        // - false when multiple overlays exists (MODE_MULTIPLE_OVERLAYS)
        final int resId = com.android.internal.R.bool.config_annoy_dianne;
        assertResource(resId, true, false, false);
    }

    public void testBooleanOverlay() throws Throwable {
        // usually_false has the value:
        // - false when no overlay exists (MODE_NO_OVERLAY)
        // - true when a single overlay exists (MODE_SINGLE_OVERLAY)
        // - false when multiple overlays exists (MODE_MULTIPLE_OVERLAYS)
        final int resId = R.bool.usually_false;
        assertResource(resId, false, true, false);
    }

    public void testBoolean() throws Throwable {
        // always_true has no overlay
        final int resId = R.bool.always_true;
        assertResource(resId, true, true, true);
    }

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

    public void testIntegerArray() throws Throwable {
        // prime_numbers has no overlay
        final int resId = R.array.prime_numbers;
        final int[] expected = {2, 3, 5, 7, 11, 13, 17, 19};
        assertResource(resId, expected, expected, expected);
    }

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

    public void testAppString() throws Throwable {
        final int resId = R.string.str;
        assertResource(resId, "none", "single", "multiple");
    }

    public void testApp2() throws Throwable {
        final int resId = R.string.str2; // only in base package and first app overlay
        assertResource(resId, "none", "single", "single");
    }

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
            reader.close();
            input.close();
        }

        final String no = "Lorem ipsum dolor sit amet, consectetur adipisicing elit, " +
            "sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. " +
            "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip " +
            "ex ea commodo consequat. Duis aute irure dolor in reprehenderit in voluptate velit " +
            "esse cillum dolore eu fugiat nulla pariatur. " +
            "Excepteur sint occaecat cupidatat non proident, " +
            "sunt in culpa qui officia deserunt mollit anim id est laborum.";
        final String so = "Lorem ipsum: single overlay.";
        final String mo = "Lorem ipsum: multiple overlays.";

        assertEquals(getExpected(no, so, mo), actual);
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
     * C     OverlayAppFirst   (default)      300
     * D     OverlayAppFirst   -sv            400
     * E     OverlayAppSecond  (default)      500
     * F     OverlayAppSecond  -sv            600
     *
     * Example: in testMatrix101110, the base package defines the
     * R.integer.matrix101110 resource for the default configuration (value
     * 100), OverlayAppFirst defines it for both default and Swedish
     * configurations (values 300 and 400, respectively), and OverlayAppSecond
     * defines it for the default configuration (value 500). If both overlays
     * are loaded, the expected value after setting the language to Swedish is
     * 400.
     */
    public void testMatrix100000() throws Throwable {
        final int resId = R.integer.matrix_100000;
        setLocale("sv_SE");
        assertResource(resId, 100, 100, 100);
    }

    public void testMatrix100001() throws Throwable {
        final int resId = R.integer.matrix_100001;
        setLocale("sv_SE");
        assertResource(resId, 100, 100, 600);
    }

    public void testMatrix100010() throws Throwable {
        final int resId = R.integer.matrix_100010;
        setLocale("sv_SE");
        assertResource(resId, 100, 100, 500);
    }

    public void testMatrix100011() throws Throwable {
        final int resId = R.integer.matrix_100011;
        setLocale("sv_SE");
        assertResource(resId, 100, 100, 600);
    }

    public void testMatrix100100() throws Throwable {
        final int resId = R.integer.matrix_100100;
        setLocale("sv_SE");
        assertResource(resId, 100, 400, 400);
    }

    public void testMatrix100101() throws Throwable {
        final int resId = R.integer.matrix_100101;
        setLocale("sv_SE");
        assertResource(resId, 100, 400, 600);
    }

    public void testMatrix100110() throws Throwable {
        final int resId = R.integer.matrix_100110;
        setLocale("sv_SE");
        assertResource(resId, 100, 400, 400);
    }

    public void testMatrix100111() throws Throwable {
        final int resId = R.integer.matrix_100111;
        setLocale("sv_SE");
        assertResource(resId, 100, 400, 600);
    }

    public void testMatrix101000() throws Throwable {
        final int resId = R.integer.matrix_101000;
        setLocale("sv_SE");
        assertResource(resId, 100, 300, 300);
    }

    public void testMatrix101001() throws Throwable {
        final int resId = R.integer.matrix_101001;
        setLocale("sv_SE");
        assertResource(resId, 100, 300, 600);
    }

    public void testMatrix101010() throws Throwable {
        final int resId = R.integer.matrix_101010;
        setLocale("sv_SE");
        assertResource(resId, 100, 300, 500);
    }

    public void testMatrix101011() throws Throwable {
        final int resId = R.integer.matrix_101011;
        setLocale("sv_SE");
        assertResource(resId, 100, 300, 600);
    }

    public void testMatrix101100() throws Throwable {
        final int resId = R.integer.matrix_101100;
        setLocale("sv_SE");
        assertResource(resId, 100, 400, 400);
    }

    public void testMatrix101101() throws Throwable {
        final int resId = R.integer.matrix_101101;
        setLocale("sv_SE");
        assertResource(resId, 100, 400, 600);
    }

    public void testMatrix101110() throws Throwable {
        final int resId = R.integer.matrix_101110;
        setLocale("sv_SE");
        assertResource(resId, 100, 400, 400);
    }

    public void testMatrix101111() throws Throwable {
        final int resId = R.integer.matrix_101111;
        setLocale("sv_SE");
        assertResource(resId, 100, 400, 600);
    }

    public void testMatrix110000() throws Throwable {
        final int resId = R.integer.matrix_110000;
        setLocale("sv_SE");
        assertResource(resId, 200, 200, 200);
    }

    public void testMatrix110001() throws Throwable {
        final int resId = R.integer.matrix_110001;
        setLocale("sv_SE");
        assertResource(resId, 200, 200, 600);
    }

    public void testMatrix110010() throws Throwable {
        final int resId = R.integer.matrix_110010;
        setLocale("sv_SE");
        assertResource(resId, 200, 200, 200);
    }

    public void testMatrix110011() throws Throwable {
        final int resId = R.integer.matrix_110011;
        setLocale("sv_SE");
        assertResource(resId, 200, 200, 600);
    }

    public void testMatrix110100() throws Throwable {
        final int resId = R.integer.matrix_110100;
        setLocale("sv_SE");
        assertResource(resId, 200, 400, 400);
    }

    public void testMatrix110101() throws Throwable {
        final int resId = R.integer.matrix_110101;
        setLocale("sv_SE");
        assertResource(resId, 200, 400, 600);
    }

    public void testMatrix110110() throws Throwable {
        final int resId = R.integer.matrix_110110;
        setLocale("sv_SE");
        assertResource(resId, 200, 400, 400);
    }

    public void testMatrix110111() throws Throwable {
        final int resId = R.integer.matrix_110111;
        setLocale("sv_SE");
        assertResource(resId, 200, 400, 600);
    }

    public void testMatrix111000() throws Throwable {
        final int resId = R.integer.matrix_111000;
        setLocale("sv_SE");
        assertResource(resId, 200, 200, 200);
    }

    public void testMatrix111001() throws Throwable {
        final int resId = R.integer.matrix_111001;
        setLocale("sv_SE");
        assertResource(resId, 200, 200, 600);
    }

    public void testMatrix111010() throws Throwable {
        final int resId = R.integer.matrix_111010;
        setLocale("sv_SE");
        assertResource(resId, 200, 200, 200);
    }

    public void testMatrix111011() throws Throwable {
        final int resId = R.integer.matrix_111011;
        setLocale("sv_SE");
        assertResource(resId, 200, 200, 600);
    }

    public void testMatrix111100() throws Throwable {
        final int resId = R.integer.matrix_111100;
        setLocale("sv_SE");
        assertResource(resId, 200, 400, 400);
    }

    public void testMatrix111101() throws Throwable {
        final int resId = R.integer.matrix_111101;
        setLocale("sv_SE");
        assertResource(resId, 200, 400, 600);
    }

    public void testMatrix111110() throws Throwable {
        final int resId = R.integer.matrix_111110;
        setLocale("sv_SE");
        assertResource(resId, 200, 400, 400);
    }

    public void testMatrix111111() throws Throwable {
        final int resId = R.integer.matrix_111111;
        setLocale("sv_SE");
        assertResource(resId, 200, 400, 600);
    }
}
