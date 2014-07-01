package com.android.speech.tts;

import android.speech.tts.TtsEngines;
import android.test.InstrumentationTestCase;

import java.util.Locale;

public class TtsEnginesTests extends InstrumentationTestCase {
    private TtsEngines mTtsHelper;

    @Override
    public void setUp() {
        mTtsHelper = new TtsEngines(getInstrumentation().getContext());
    }

    public void testParseLocaleString() {
        assertEquals(new Locale("en", "US"), mTtsHelper.parseLocaleString("eng-usa"));
        assertEquals(new Locale("en", "US"), mTtsHelper.parseLocaleString("eng-USA"));
        assertEquals(new Locale("en", "US"), mTtsHelper.parseLocaleString("en-US"));
        assertEquals(new Locale("en", "US"), mTtsHelper.parseLocaleString("en_us"));
        assertEquals(new Locale("en", "US"), mTtsHelper.parseLocaleString("eng_US"));
        assertEquals(new Locale("en", "US", "foobar"),
                mTtsHelper.parseLocaleString("eng_US-foobar"));
        assertEquals(new Locale("en", "", "foobar"), mTtsHelper.parseLocaleString("eng__foobar"));
        assertNull(mTtsHelper.parseLocaleString("cc_xx_barbar"));
        assertNull(mTtsHelper.parseLocaleString("cc--barbar"));

        assertEquals(new Locale("en"), mTtsHelper.parseLocaleString("eng"));
        assertEquals(new Locale("en","US","var"), mTtsHelper.parseLocaleString("eng-USA-var"));
    }

    public void testToOldLocaleStringFormat() {
        assertArraysEqual(new String[]{"deu", "DEU", ""},
                TtsEngines.toOldLocaleStringFormat(new Locale("de", "DE")));
        assertArraysEqual(new String[]{"deu", "", ""},
                TtsEngines.toOldLocaleStringFormat(new Locale("de")));
        assertArraysEqual(new String[]{"eng", "", ""},
                TtsEngines.toOldLocaleStringFormat(new Locale("en")));
        assertArraysEqual(new String[]{"eng", "USA", ""},
                TtsEngines.toOldLocaleStringFormat(new Locale("foo")));
    }

    public void testNormalizeLocale() {
        assertEquals(Locale.UK,
                TtsEngines.normalizeTTSLocale(new Locale("eng", "gbr")));
        assertEquals(Locale.UK,
                TtsEngines.normalizeTTSLocale(new Locale("eng", "GBR")));
        assertEquals(Locale.GERMANY,
                TtsEngines.normalizeTTSLocale(new Locale("deu", "deu")));
        assertEquals(Locale.GERMAN,
                TtsEngines.normalizeTTSLocale(new Locale("deu")));
        assertEquals(new Locale("yyy", "DE"),
                TtsEngines.normalizeTTSLocale(new Locale("yyy", "DE")));
    }

    public void testGetLocalePrefForEngine() {
        assertEquals(new Locale("en", "US"),
                mTtsHelper.getLocalePrefForEngine("foo","foo:en-US"));
        assertEquals(new Locale("en", "US"),
                mTtsHelper.getLocalePrefForEngine("foo","foo:eng-usa"));
        assertEquals(new Locale("en", "US"),
                mTtsHelper.getLocalePrefForEngine("foo","foo:eng_USA"));
        assertEquals(new Locale("de", "DE"),
                mTtsHelper.getLocalePrefForEngine("foo","foo:deu-deu"));
        assertEquals(Locale.getDefault(),
                mTtsHelper.getLocalePrefForEngine("foo","foo:,bar:xx"));
        assertEquals(Locale.getDefault(),
                mTtsHelper.getLocalePrefForEngine("other","foo:,bar:xx"));
    }

    private void assertArraysEqual(String[] expected, String[] actual) {
        assertEquals("array length", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("index " + i, expected[i], actual[i]);
        }
    }
}