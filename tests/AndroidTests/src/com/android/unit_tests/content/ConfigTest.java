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

    private static void checkValue(Resources res, int resId, String expectedValue) {
        try {
            String actual = res.getString(resId);
            assertNotNull("Returned wrong configuration-based simple value: expected <nothing>, got '"
                    + actual + "' from resource 0x"
                    + Integer.toHexString(resId),
                    expectedValue);
            assertEquals("Returned wrong configuration-based simple value: expected "
                    + expectedValue + ", got '" + actual + "' from resource 0x"
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
            assertEquals("Returned wrong configuration-based style value: expected "
                    + expectedValues[i] + ", got '" + actual + "' from attr "
                    + i + " of resource 0x" + Integer.toHexString(resId),
                    actual, expectedValues[i]);
        }
        sa.recycle();
    }

    public Resources getResources(Configuration config,
            int mcc, int mnc, int touchscreen, int keyboard, int keysHidden,
            int navigation, int width, int height) {
        AssetManager assmgr = new AssetManager();
        assmgr.addAssetPath(mContext.getPackageResourcePath());
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        d.getMetrics(metrics);
        config.mcc = mcc;
        config.mnc = mnc;
        config.touchscreen = touchscreen;
        config.keyboard = keyboard;
        config.keyboardHidden = keysHidden;
        config.navigation = navigation;
        metrics.widthPixels = width;
        metrics.heightPixels = height;
        return new Resources(assmgr, metrics, config);
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

    private static void checkAllExcept(Resources res, int[] notResIds) {
        checkPair(res, notResIds,
                R.configVarying.simple_default, "only simple default",
                R.configVarying.bag_default, "only bag default");
        checkPair(res, notResIds,
                R.configVarying.simple_mcc111, "only simple mcc111",
                R.configVarying.bag_mcc111, "only bag mcc111");
        checkPair(res, notResIds,
                R.configVarying.simple_mnc222, "only simple mnc222",
                R.configVarying.bag_mnc222, "only bag mnc222");
        checkPair(res, notResIds,
                R.configVarying.simple_xx, "only simple xx",
                R.configVarying.bag_xx, "only bag xx");
        checkPair(res, notResIds,
                R.configVarying.simple_xx_rYY, "only simple xx_rYY",
                R.configVarying.bag_xx_rYY, "only bag xx_rYY");
        checkPair(res, notResIds,
                R.configVarying.simple_notouch, "only simple notouch",
                R.configVarying.bag_notouch, "only bag notouch");
        checkPair(res, notResIds,
                R.configVarying.simple_finger, "only simple finger",
                R.configVarying.bag_finger, "only bag finger");
        checkPair(res, notResIds,
                R.configVarying.simple_stylus, "only simple stylus",
                R.configVarying.bag_stylus, "only bag stylus");
        checkPair(res, notResIds,
                R.configVarying.simple_12key, "only simple 12key",
                R.configVarying.bag_12key, "only bag 12key");
        checkPair(res, notResIds,
                R.configVarying.simple_320x200, "only simple 320x200",
                R.configVarying.bag_320x200, "only bag 320x200");
        checkPair(res, notResIds,
                R.configVarying.simple_480x320, "only simple 480x320",
                R.configVarying.bag_480x320, "only bag 480x320");
    }
    
    @SmallTest
    public void testDefaultNavigationMethod() throws Exception {
        assertEquals(mContext.getResources().getConfiguration().navigation, 
                Configuration.NAVIGATION_TRACKBALL);
    }

    @SmallTest
    public void testAllConfigs() throws Exception {
        /**
         * Test a resource that contains a value for each possible single
         * configuration value.
         */
        Configuration config = new Configuration();
        Resources res = getResources(config, 0, 0, 0, 0, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple default");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag default"});

        config.locale = new Locale("xx");
        res = getResources(config, 0, 0, 0, 0, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple xx");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag xx"});

        config.locale = new Locale("xx", "YY");
        res = getResources(config, 0, 0, 0, 0, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple xx-rYY");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag xx-rYY"});

        config = new Configuration();
        res = getResources(config, 111, 0, 0, 0, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple mcc111");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag mcc111"});

        res = getResources(config, 0, 222, 0, 0, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple mnc222");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag mnc222"});

        res = getResources(config, 0, 0, Configuration.TOUCHSCREEN_NOTOUCH, 0, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple notouch");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag notouch"});

        res = getResources(config, 0, 0, Configuration.TOUCHSCREEN_FINGER, 0, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple finger");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag finger"});

        res = getResources(config, 0, 0, Configuration.TOUCHSCREEN_STYLUS, 0, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple stylus");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag stylus"});

        res = getResources(config, 0, 0, 0, Configuration.KEYBOARD_NOKEYS, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple nokeys");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag nokeys"});

        res = getResources(config, 0, 0, 0, Configuration.KEYBOARD_QWERTY, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple qwerty");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag qwerty"});

        res = getResources(config, 0, 0, 0, Configuration.KEYBOARD_12KEY, 0, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple 12key");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 12key"});

        res = getResources(config, 0, 0, 0, 0, Configuration.KEYBOARDHIDDEN_YES, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple keyshidden");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag keyshidden"});

        res = getResources(config, 0, 0, 0, 0, Configuration.KEYBOARDHIDDEN_NO, 0, 0, 0);
        checkValue(res, R.configVarying.simple, "simple keysexposed");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag keysexposed"});

        res = getResources(config, 0, 0, 0, 0, 0, Configuration.NAVIGATION_NONAV, 0, 0);
        checkValue(res, R.configVarying.simple, "simple nonav");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag nonav"});

        res = getResources(config, 0, 0, 0, 0, 0, Configuration.NAVIGATION_DPAD, 0, 0);
        checkValue(res, R.configVarying.simple, "simple dpad");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag dpad"});

        res = getResources(config, 0, 0, 0, 0, 0, Configuration.NAVIGATION_TRACKBALL, 0, 0);
        checkValue(res, R.configVarying.simple, "simple trackball");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag trackball"});

        res = getResources(config, 0, 0, 0, 0, 0, Configuration.NAVIGATION_WHEEL, 0, 0);
        checkValue(res, R.configVarying.simple, "simple wheel");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag wheel"});

        res = getResources(config, 0, 0, 0, 0, 0, 0, 320, 200);
        checkValue(res, R.configVarying.simple, "simple 320x200");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 320x200"});

        res = getResources(config, 0, 0, 0, 0, 0, 0, 480, 320);
        checkValue(res, R.configVarying.simple, "simple 480x320");
        checkValue(res, R.configVarying.bag,
                R.styleable.TestConfig, new String[]{"bag 480x320"});
    }

    @MediumTest
    public void testSingleConfig() throws Exception {
        /**
         * Test resources that contain a value for only one possible configuration
         * value.  XXX This is not yet complete.
         */
        Configuration config = new Configuration();
        Resources res = getResources(config, 0, 0, 0, 0, 0, 0, 0, 0);
        checkAllExcept(res, new int[]{
                R.configVarying.simple_xx,
                R.configVarying.simple_xx_rYY});

        config.locale = new Locale("xx");
        res = getResources(config, 0, 0, 0, 0, 0, 0, 0, 0);
        checkAllExcept(res, null);

        config.locale = new Locale("xx", "YY");
        res = getResources(config, 0, 0, 0, 0, 0, 0, 0, 0);
        checkAllExcept(res, null);

        config.locale = new Locale("xx", "ZZ");
        res = getResources(config, 0, 0, 0, 0, 0, 0, 0, 0);
        checkAllExcept(res, new int[]{R.configVarying.simple_xx_rYY});

        config = new Configuration();
        res = getResources(config, 111, 0, 0, 0, 0, 0, 0, 0);
        checkAllExcept(res, new int[]{
                R.configVarying.simple_xx,
                R.configVarying.simple_xx_rYY});

        res = getResources(config, 0, 222, 0, 0, 0, 0, 0, 0);
        checkAllExcept(res, new int[]{
                R.configVarying.simple_xx,
                R.configVarying.simple_xx_rYY});

        res = getResources(config, 0, 0, Configuration.TOUCHSCREEN_NOTOUCH, 0, 0, 0, 0, 0);
        checkAllExcept(res, new int[]{
                R.configVarying.simple_xx,
                R.configVarying.simple_xx_rYY,
                R.configVarying.simple_finger,
                R.configVarying.simple_stylus});

        res = getResources(config, 0, 0, Configuration.TOUCHSCREEN_FINGER, 0, 0, 0, 0, 0);
        checkAllExcept(res, new int[]{
                R.configVarying.simple_xx,
                R.configVarying.simple_xx_rYY,
                R.configVarying.simple_notouch,
                R.configVarying.simple_stylus});

        res = getResources(config, 0, 0, Configuration.TOUCHSCREEN_STYLUS, 0, 0, 0, 0, 0);
        checkAllExcept(res, new int[]{
                R.configVarying.simple_xx,
                R.configVarying.simple_xx_rYY,
                R.configVarying.simple_notouch,
                R.configVarying.simple_finger});

        res = getResources(config, 0, 0, 0, Configuration.KEYBOARD_12KEY, 0, 0, 0, 0);
        checkAllExcept(res, new int[]{
                R.configVarying.simple_xx,
                R.configVarying.simple_xx_rYY});

        res = getResources(config, 0, 0, 0, 0, 0, 0, 320, 200);
        checkAllExcept(res, new int[]{
                R.configVarying.simple_xx,
                R.configVarying.simple_xx_rYY,
                R.configVarying.simple_480x320});

        res = getResources(config, 0, 0, 0, 0, 0, 0, 480, 320);
        checkAllExcept(res, new int[]{
                R.configVarying.simple_xx,
                R.configVarying.simple_xx_rYY,
                R.configVarying.simple_320x200});
    }
}
