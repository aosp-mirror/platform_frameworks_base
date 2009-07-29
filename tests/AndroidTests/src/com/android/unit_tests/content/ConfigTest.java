/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.unit_tests.content;

import android.content.Context;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;
import com.android.unit_tests.R;

import java.util.Locale;

public class ConfigTest extends AndroidTestCase {
    enum properties {
        LANGUAGE,
        COUNTRY,
        MCC,
        MNC,
        TOUCHSCREEN,
        KEYBOARD,
        KEYBOARDHIDDEN,
        NAVIGATION,
        ORIENTATION,
        WIDTH,
        HEIGHT,
        DENSITY
    }

    private static void checkValue(Resources res, int resId, String expectedValue) {
        try {
            String actual = res.getString(resId);
            assertNotNull("Returned wrong configuration-based simple value: expected <nothing>, got '"
                    + actual + "' from resource 0x"
                    + Integer.toHexString(resId),
                    expectedValue);
            assertEquals("Returned wrong configuration-based simple value: expected '"
                    + expectedValue + "', got '" + actual + "' from resource 0x"
                    + Integer.toHexString(resId),
                    expectedValue, actual);
        } catch (Resources.NotFoundException e) {
            assertNull("Resource not found for configuration-based simple value: expecting \""
                    + expectedValue + "\"",
                    expectedValue);
        }
    }

    private static void checkValue(Resources res, int resId,
            int[] styleable, String[] expectedValues) {
        Resources.Theme theme = res.newTheme();
        TypedArray sa = theme.obtainStyledAttributes(resId, styleable);
        for (int i = 0; i < styleable.length; i++) {
            String actual = sa.getString(i);
            assertEquals("Returned wrong configuration-based style value: expected '"
                    + expectedValues[i] + "', got '" + actual + "' from attr "
                    + i + " of resource 0x" + Integer.toHexString(resId),
                    actual, expectedValues[i]);
        }
        sa.recycle();
    }

    class TotalConfig {
        Configuration mConfig;
        DisplayMetrics mMetrics;
    
        TotalConfig() {
            mConfig = new Configuration();
            // don't rely on build settings - they may change
            mConfig.locale = new Locale("en", "US");
            mConfig.mcc = 310;
            mConfig.mnc = 001; // unused
            mConfig.touchscreen = Configuration.TOUCHSCREEN_FINGER;
            mConfig.keyboard = Configuration.KEYBOARD_QWERTY;
            mConfig.keyboardHidden = Configuration.KEYBOARDHIDDEN_YES;
            mConfig.navigation = Configuration.NAVIGATION_TRACKBALL;
            mConfig.orientation = Configuration.ORIENTATION_PORTRAIT;

            mMetrics = new DisplayMetrics();
            mMetrics.widthPixels = 200;
            mMetrics.heightPixels = 320;
            mMetrics.density = 1;
        }

        void setProperty(properties p, int value) {
            switch(p) {
                case MCC:
                    mConfig.mcc = value;
                    break;
                case MNC:
                    mConfig.mnc = value;
                    break;
                case TOUCHSCREEN:
                    mConfig.touchscreen = value;
                    break;
                case KEYBOARD:
                    mConfig.keyboard = value;
                    break;
                case KEYBOARDHIDDEN:
                    mConfig.keyboardHidden = value;
                    break;
                case NAVIGATION:
                    mConfig.navigation = value;
                    break;
                case ORIENTATION:
                    mConfig.orientation = value;
                    break;
                case WIDTH:
                    mMetrics.widthPixels = value;
                    break;
                case HEIGHT:
                    mMetrics.heightPixels = value;
                    break;
                case DENSITY:
                    // this is the ratio from the standard

                    mMetrics.density = (((float)value)/((float)DisplayMetrics.DENSITY_DEFAULT));
                    break;
                default:
                    assert(false);
                    break;
            }
        }

        public void setProperty(properties p, String value) {
            switch(p) {
                case LANGUAGE:
                    String oldCountry = mConfig.locale.getCountry();
                    mConfig.locale = new Locale(value, oldCountry);
                    break;
                case COUNTRY:
                    String oldLanguage = mConfig.locale.getLanguage();
                    mConfig.locale = new Locale(oldLanguage, value);
                    break;
                default:
                    assert(false);
                    break;
            }
        }

        public Resources getResources() {
            AssetManager assmgr = new AssetManager();
            assmgr.addAssetPath(mContext.getPackageResourcePath());
            return new Resources(assmgr, mMetrics, mConfig);
        }
    }

    private static void checkPair(Resources res, int[] notResIds,
            int simpleRes, String simpleString,
            int bagRes, String bagString) {
        boolean willHave = true;
        if (notResIds != null) {
            for (int i : notResIds) {
                if (i == simpleRes) {
                    willHave = false;
                    break;
                }
            }
        }
        checkValue(res, simpleRes, willHave ? simpleString : null);
        checkValue(res, bagRes, R.styleable.TestConfig,
                new String[]{willHave ? bagString : null});
    }

    @SmallTest
    public void testAllConfigs() throws Exception {
        /**
         * Test a resource that contains a value for each possible single
         * configuration value.
         */
        TotalConfig config = new TotalConfig();
        Resources res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple default");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag default"});

        config = new TotalConfig();
        config.setProperty(properties.LANGUAGE, "xx");
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple xx");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag xx"});

        config = new TotalConfig();
        config.setProperty(properties.LANGUAGE, "xx");
        config.setProperty(properties.COUNTRY, "YY");
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple xx-rYY");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag xx-rYY"});

        config = new TotalConfig();
        config.setProperty(properties.MCC, 111);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple mcc111");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag mcc111"});

        config = new TotalConfig();
        config.setProperty(properties.MNC, 222);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple mnc222");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag mnc222"});

        config = new TotalConfig();
        config.setProperty(properties.TOUCHSCREEN, Configuration.TOUCHSCREEN_NOTOUCH);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple notouch");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag notouch"});

        config = new TotalConfig();
        config.setProperty(properties.TOUCHSCREEN, Configuration.TOUCHSCREEN_STYLUS);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple stylus");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag stylus"});

        config = new TotalConfig();
        config.setProperty(properties.KEYBOARD, Configuration.KEYBOARD_NOKEYS);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple nokeys");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag nokeys"});

        config = new TotalConfig();
        config.setProperty(properties.KEYBOARD, Configuration.KEYBOARD_12KEY);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 12key");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 12key"});

        config = new TotalConfig();
        config.setProperty(properties.KEYBOARDHIDDEN, Configuration.KEYBOARDHIDDEN_NO);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple keysexposed");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag keysexposed"});

        config = new TotalConfig();
        config.setProperty(properties.NAVIGATION, Configuration.NAVIGATION_NONAV);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple nonav");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag nonav"});

        config = new TotalConfig();
        config.setProperty(properties.NAVIGATION, Configuration.NAVIGATION_DPAD);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple dpad");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag dpad"});

        config = new TotalConfig();
        config.setProperty(properties.NAVIGATION, Configuration.NAVIGATION_WHEEL);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple wheel");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag wheel"});

        config = new TotalConfig();
        config.setProperty(properties.HEIGHT, 480);
        config.setProperty(properties.WIDTH, 320);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 480x320");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 480x320"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 240);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 240dpi");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 240dpi"});

        config = new TotalConfig();
        config.setProperty(properties.ORIENTATION, Configuration.ORIENTATION_LANDSCAPE);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple landscape");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag landscape"});

        config = new TotalConfig();
        config.setProperty(properties.ORIENTATION, Configuration.ORIENTATION_SQUARE);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple square");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag square"});
    }

    @MediumTest
    public void testDensity() throws Exception {
        // have 32, 240 and the default 160 content.
        // rule is that closest wins, with down scaling (larger content)
        // being twice as nice as upscaling.
        // transition at H/2 * (-1 +/- sqrt(1+8L/H))
        // SO, X < 49 goes to 32
        // 49 >= X < 182 goes to 160
        // X >= 182 goes to 240
        TotalConfig config = new TotalConfig();
        config.setProperty(properties.DENSITY, 2);
        Resources res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 32dpi");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 32dpi"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 32);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 32dpi");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 32dpi"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 48);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 32dpi");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 32dpi"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 49);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple default");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag default"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 150);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple default");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag default"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 181);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple default");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag default"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 182);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 240dpi");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 240dpi"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 239);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 240dpi");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 240dpi"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 490);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 240dpi");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 240dpi"});
    }

// TODO - add tests for special cases - ie, other key params seem ignored if 
// nokeys is set

    @MediumTest
    public void testCombinations() throws Exception {
        /**
         * Verify that proper strings are found for multiple-selectivity case
         * (ie, a string set for locale and mcc is found only when both are
         * true).
         */
        TotalConfig config = new TotalConfig();
        config.setProperty(properties.LANGUAGE, "xx");
        config.setProperty(properties.COUNTRY, "YY");
        config.setProperty(properties.MCC, 111);
        Resources res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple mcc111 xx-rYY");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag mcc111 xx-rYY"});

        config = new TotalConfig();
        config.setProperty(properties.LANGUAGE, "xx");
        config.setProperty(properties.COUNTRY, "YY");
        config.setProperty(properties.MCC, 333);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple xx-rYY");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag xx-rYY"});

        config = new TotalConfig();
        config.setProperty(properties.MNC, 333);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple default");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag default"});
    }

    @MediumTest
    public void testPrecidence() throws Exception {
        /**
         * Verify that in cases of ties, the specific ordering is followed
         */

        /**
         * Precidence order: mcc, mnc, locale, orientation, density,
         * touchscreen, hidden, keyboard, navigation, width-height
         */

        /**
         * verify mcc trumps mnc.  Have 110-xx, 220-xx but no 110-220
         * so with is selected?  Should be mcc110-xx. 
         */
        TotalConfig config = new TotalConfig();
        config.setProperty(properties.MCC, 110);
        config.setProperty(properties.MNC, 220);
        config.setProperty(properties.LANGUAGE, "xx");
        Resources res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple mcc110 xx");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag mcc110 xx"});

        /* full A + B + C doesn't exist.  Do we get A + C or B + C? 
         */
        config = new TotalConfig();
        config.setProperty(properties.MCC, 111);
        config.setProperty(properties.MNC, 222);
        config.setProperty(properties.LANGUAGE, "xx");
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple mcc111 mnc222");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag mcc111 mnc222"});

        config = new TotalConfig();
        config.setProperty(properties.MNC, 222);
        config.setProperty(properties.LANGUAGE, "xx");
        config.setProperty(properties.ORIENTATION, 
                Configuration.ORIENTATION_SQUARE);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple mnc222 xx");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag mnc222 xx"});

        config = new TotalConfig();
        config.setProperty(properties.LANGUAGE, "xx");
        config.setProperty(properties.ORIENTATION, 
                Configuration.ORIENTATION_SQUARE);
        config.setProperty(properties.DENSITY, 32);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple xx square");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag xx square"});

        config = new TotalConfig();
        config.setProperty(properties.ORIENTATION, 
                Configuration.ORIENTATION_SQUARE);
        config.setProperty(properties.DENSITY, 32);
        config.setProperty(properties.TOUCHSCREEN, 
                Configuration.TOUCHSCREEN_STYLUS);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple square 32dpi");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag square 32dpi"});

        config = new TotalConfig();
        config.setProperty(properties.DENSITY, 32);
        config.setProperty(properties.TOUCHSCREEN, 
                Configuration.TOUCHSCREEN_STYLUS);
        config.setProperty(properties.KEYBOARDHIDDEN, 
                Configuration.KEYBOARDHIDDEN_NO);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 32dpi stylus");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 32dpi stylus"});

        config = new TotalConfig();
        config.setProperty(properties.TOUCHSCREEN, 
                Configuration.TOUCHSCREEN_STYLUS);
        config.setProperty(properties.KEYBOARDHIDDEN, 
                Configuration.KEYBOARDHIDDEN_NO);
        config.setProperty(properties.KEYBOARD, Configuration.KEYBOARD_12KEY);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple stylus keysexposed");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag stylus keysexposed"});

        config = new TotalConfig();
        config.setProperty(properties.KEYBOARDHIDDEN, 
                Configuration.KEYBOARDHIDDEN_NO);
        config.setProperty(properties.KEYBOARD, Configuration.KEYBOARD_12KEY);
        config.setProperty(properties.NAVIGATION, 
                Configuration.NAVIGATION_DPAD);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple keysexposed 12key");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag keysexposed 12key"});

        config = new TotalConfig();
        config.setProperty(properties.KEYBOARD, Configuration.KEYBOARD_12KEY);
        config.setProperty(properties.NAVIGATION, 
                Configuration.NAVIGATION_DPAD);
        config.setProperty(properties.HEIGHT, 63);
        config.setProperty(properties.WIDTH, 57);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple 12key dpad");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 12key dpad"});

        config = new TotalConfig();
        config.setProperty(properties.NAVIGATION, 
                Configuration.NAVIGATION_DPAD);
        config.setProperty(properties.HEIGHT, 640);
        config.setProperty(properties.WIDTH, 400);
        res = config.getResources();
        checkValue(res, R.configVarying.simple, "simple dpad");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag dpad"});
    }
}
