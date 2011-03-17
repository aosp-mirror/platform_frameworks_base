package com.android.overlaytest;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.test.AndroidTestCase;
import java.io.InputStream;
import java.util.Locale;

public abstract class OverlayBaseTest extends AndroidTestCase {
    private Resources mResources;
    protected boolean mWithOverlay; // will be set by subclasses

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

    private void assertResource(int resId, boolean ewo, boolean ew) throws Throwable {
        boolean expected = mWithOverlay ? ew : ewo;
        boolean actual = mResources.getBoolean(resId);
        assertEquals(expected, actual);
    }

    private void assertResource(int resId, String ewo, String ew) throws Throwable {
        String expected = mWithOverlay ? ew : ewo;
        String actual = mResources.getString(resId);
        assertEquals(expected, actual);
    }

    private void assertResource(int resId, int[] ewo, int[] ew) throws Throwable {
        int[] expected = mWithOverlay ? ew : ewo;
        int[] actual = mResources.getIntArray(resId);
        assertEquals("length:", expected.length, actual.length);
        for (int i = 0; i < actual.length; ++i) {
            assertEquals("index " + i + ":", actual[i], expected[i]);
        }
    }

    public void testBooleanOverlay() throws Throwable {
        // config_automatic_brightness_available has overlay (default config)
        final int resId = com.android.internal.R.bool.config_automatic_brightness_available;
        assertResource(resId, false, true);
    }

    public void testBoolean() throws Throwable {
        // config_bypass_keyguard_if_slider_open has no overlay
        final int resId = com.android.internal.R.bool.config_bypass_keyguard_if_slider_open;
        assertResource(resId, true, true);
    }

    public void testStringOverlay() throws Throwable {
        // phoneTypeCar has an overlay (default config), which shouldn't shadow
        // the Swedish translation
        final int resId = com.android.internal.R.string.phoneTypeCar;
        setLocale("sv_SE");
        assertResource(resId, "Bil", "Bil");
    }

    public void testStringSwedishOverlay() throws Throwable {
        // phoneTypeWork has overlay (no default config, only for lang=sv)
        final int resId = com.android.internal.R.string.phoneTypeWork;
        setLocale("en_US");
        assertResource(resId, "Work", "Work");
        setLocale("sv_SE");
        assertResource(resId, "Arbete", "Jobb");
    }

    public void testString() throws Throwable {
        // phoneTypeHome has no overlay
        final int resId = com.android.internal.R.string.phoneTypeHome;
        setLocale("en_US");
        assertResource(resId, "Home", "Home");
        setLocale("sv_SE");
        assertResource(resId, "Hem", "Hem");
    }

    public void testIntegerArrayOverlay() throws Throwable {
        // config_scrollBarrierVibePattern has overlay (default config)
        final int resId = com.android.internal.R.array.config_scrollBarrierVibePattern;
        assertResource(resId, new int[]{0, 15, 10, 10}, new int[]{100, 200, 300});
    }

    public void testIntegerArray() throws Throwable {
        // config_virtualKeyVibePattern has no overlay
        final int resId = com.android.internal.R.array.config_virtualKeyVibePattern;
        final int[] expected = {0, 10, 20, 30};
        assertResource(resId, expected, expected);
    }

    public void testAsset() throws Throwable {
        // drawable/default_background.jpg has overlay (default config)
        final int resId = com.android.internal.R.drawable.default_wallpaper;
        int actual = calculateRawResourceChecksum(resId);
        int expected = mWithOverlay ? 0x000051da : 0x0014ebce;
        assertEquals(expected, actual);
    }
}
