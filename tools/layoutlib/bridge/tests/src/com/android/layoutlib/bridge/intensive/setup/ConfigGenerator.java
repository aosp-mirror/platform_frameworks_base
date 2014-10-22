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

package com.android.layoutlib.bridge.intensive.setup;

import com.android.ide.common.rendering.api.HardwareConfig;
import com.android.ide.common.resources.configuration.CountryCodeQualifier;
import com.android.ide.common.resources.configuration.DensityQualifier;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.ide.common.resources.configuration.KeyboardStateQualifier;
import com.android.ide.common.resources.configuration.LanguageQualifier;
import com.android.ide.common.resources.configuration.LayoutDirectionQualifier;
import com.android.ide.common.resources.configuration.NavigationMethodQualifier;
import com.android.ide.common.resources.configuration.NetworkCodeQualifier;
import com.android.ide.common.resources.configuration.NightModeQualifier;
import com.android.ide.common.resources.configuration.RegionQualifier;
import com.android.ide.common.resources.configuration.ScreenDimensionQualifier;
import com.android.ide.common.resources.configuration.ScreenOrientationQualifier;
import com.android.ide.common.resources.configuration.ScreenRatioQualifier;
import com.android.ide.common.resources.configuration.ScreenSizeQualifier;
import com.android.ide.common.resources.configuration.TextInputMethodQualifier;
import com.android.ide.common.resources.configuration.TouchScreenQualifier;
import com.android.ide.common.resources.configuration.UiModeQualifier;
import com.android.ide.common.resources.configuration.VersionQualifier;
import com.android.resources.Density;
import com.android.resources.Keyboard;
import com.android.resources.KeyboardState;
import com.android.resources.Navigation;
import com.android.resources.NightMode;
import com.android.resources.ScreenOrientation;
import com.android.resources.ScreenRatio;
import com.android.resources.ScreenSize;
import com.android.resources.TouchScreen;
import com.android.resources.UiMode;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import com.google.android.collect.Maps;

/**
 * Provides {@link FolderConfiguration} and {@link HardwareConfig} for various devices. Also
 * provides utility methods to parse build.prop and attrs.xml to generate the appropriate maps.
 */
@SuppressWarnings("UnusedDeclaration") // For the pre-configured nexus generators.
public class ConfigGenerator {

    public static final ConfigGenerator NEXUS_4 = new ConfigGenerator();

    public static final ConfigGenerator NEXUS_5 = new ConfigGenerator()
                                                        .setScreenHeight(1920)
                                                        .setScreenWidth(1080)
                                                        .setXdpi(445)
                                                        .setYdpi(445)
                                                        .setOrientation(ScreenOrientation.PORTRAIT)
                                                        .setDensity(Density.XXHIGH)
                                                        .setRatio(ScreenRatio.NOTLONG)
                                                        .setSize(ScreenSize.NORMAL)
                                                        .setKeyboard(Keyboard.NOKEY)
                                                        .setTouchScreen(TouchScreen.FINGER)
                                                        .setKeyboardState(KeyboardState.SOFT)
                                                        .setSoftButtons(true)
                                                        .setNavigation(Navigation.NONAV);

    public static final ConfigGenerator NEXUS_7 = new ConfigGenerator()
                                                        .setScreenHeight(1920)
                                                        .setScreenWidth(1200)
                                                        .setXdpi(323)
                                                        .setYdpi(323)
                                                        .setOrientation(ScreenOrientation.PORTRAIT)
                                                        .setDensity(Density.XHIGH)
                                                        .setRatio(ScreenRatio.NOTLONG)
                                                        .setSize(ScreenSize.LARGE)
                                                        .setKeyboard(Keyboard.NOKEY)
                                                        .setTouchScreen(TouchScreen.FINGER)
                                                        .setKeyboardState(KeyboardState.SOFT)
                                                        .setSoftButtons(true)
                                                        .setNavigation(Navigation.NONAV);

    public static final ConfigGenerator NEXUS_10 = new ConfigGenerator()
                                                        .setScreenHeight(1600)
                                                        .setScreenWidth(2560)
                                                        .setXdpi(300)
                                                        .setYdpi(300)
                                                        .setOrientation(ScreenOrientation.LANDSCAPE)
                                                        .setDensity(Density.XHIGH)
                                                        .setRatio(ScreenRatio.NOTLONG)
                                                        .setSize(ScreenSize.XLARGE)
                                                        .setKeyboard(Keyboard.NOKEY)
                                                        .setTouchScreen(TouchScreen.FINGER)
                                                        .setKeyboardState(KeyboardState.SOFT)
                                                        .setSoftButtons(true)
                                                        .setNavigation(Navigation.NONAV);

    private static final String TAG_ATTR = "attr";
    private static final String TAG_ENUM = "enum";
    private static final String TAG_FLAG = "flag";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_VALUE = "value";

    // Device Configuration. Defaults are for a Nexus 4 device.
    private int mScreenHeight = 1280;
    private int mScreenWidth = 768;
    private int mXdpi = 320;
    private int mYdpi = 320;
    private ScreenOrientation mOrientation = ScreenOrientation.PORTRAIT;
    private Density mDensity = Density.XHIGH;
    private ScreenRatio mRatio = ScreenRatio.NOTLONG;
    private ScreenSize mSize = ScreenSize.NORMAL;
    private Keyboard mKeyboard = Keyboard.NOKEY;
    private TouchScreen mTouchScreen = TouchScreen.FINGER;
    private KeyboardState mKeyboardState = KeyboardState.SOFT;
    private boolean mSoftButtons = true;
    private Navigation mNavigation = Navigation.NONAV;

    public FolderConfiguration getFolderConfig() {
        FolderConfiguration config = new FolderConfiguration();
        config.createDefault();
        config.setDensityQualifier(new DensityQualifier(mDensity));
        config.setNavigationMethodQualifier(new NavigationMethodQualifier(mNavigation));
        if (mScreenWidth > mScreenHeight) {
            config.setScreenDimensionQualifier(new ScreenDimensionQualifier(mScreenWidth,
                    mScreenHeight));
        } else {
            config.setScreenDimensionQualifier(new ScreenDimensionQualifier(mScreenHeight,
                    mScreenWidth));
        }
        config.setScreenRatioQualifier(new ScreenRatioQualifier(mRatio));
        config.setScreenSizeQualifier(new ScreenSizeQualifier(mSize));
        config.setTextInputMethodQualifier(new TextInputMethodQualifier(mKeyboard));
        config.setTouchTypeQualifier(new TouchScreenQualifier(mTouchScreen));
        config.setKeyboardStateQualifier(new KeyboardStateQualifier(mKeyboardState));
        config.setScreenOrientationQualifier(new ScreenOrientationQualifier(mOrientation));

        config.updateScreenWidthAndHeight();

        // some default qualifiers.
        config.setUiModeQualifier(new UiModeQualifier(UiMode.NORMAL));
        config.setNightModeQualifier(new NightModeQualifier(NightMode.NOTNIGHT));
        config.setCountryCodeQualifier(new CountryCodeQualifier());
        config.setLanguageQualifier(new LanguageQualifier());
        config.setLayoutDirectionQualifier(new LayoutDirectionQualifier());
        config.setNetworkCodeQualifier(new NetworkCodeQualifier());
        config.setRegionQualifier(new RegionQualifier());
        config.setVersionQualifier(new VersionQualifier());
        return config;
    }

    public HardwareConfig getHardwareConfig() {
        return new HardwareConfig(mScreenWidth, mScreenHeight, mDensity, mXdpi, mYdpi, mSize,
                mOrientation, mSoftButtons);
    }

    public static Map<String, String> loadProperties(File path) {
        Properties p = new Properties();
        Map<String, String> map = Maps.newHashMap();
        try {
            p.load(new FileInputStream(path));
            for (String key : p.stringPropertyNames()) {
                map.put(key, p.getProperty(key));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return map;
    }

    public static Map<String, Map<String, Integer>> getEnumMap(File path) {
        Map<String, Map<String, Integer>> map = Maps.newHashMap();
        try {
            XmlPullParser xmlPullParser = XmlPullParserFactory.newInstance().newPullParser();
            xmlPullParser.setInput(new FileInputStream(path), null);
            int eventType = xmlPullParser.getEventType();
            String attr = null;
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    if (TAG_ATTR.equals(xmlPullParser.getName())) {
                        attr = xmlPullParser.getAttributeValue(null, ATTR_NAME);
                    } else if (TAG_ENUM.equals(xmlPullParser.getName())
                            || TAG_FLAG.equals(xmlPullParser.getName())) {
                        String name = xmlPullParser.getAttributeValue(null, ATTR_NAME);
                        String value = xmlPullParser.getAttributeValue(null, ATTR_VALUE);
                        // Integer.decode cannot handle "ffffffff", see JDK issue 6624867
                        int i = (int) (long) Long.decode(value);
                        assert attr != null;
                        Map<String, Integer> attributeMap = map.get(attr);
                        if (attributeMap == null) {
                            attributeMap = Maps.newHashMap();
                            map.put(attr, attributeMap);
                        }
                        attributeMap.put(name, i);
                    }
                } else if (eventType == XmlPullParser.END_TAG) {
                    if (TAG_ATTR.equals(xmlPullParser.getName())) {
                        attr = null;
                    }
                }
                eventType = xmlPullParser.next();
            }
        } catch (XmlPullParserException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return map;
    }

    // Methods to set the configuration values.

    public ConfigGenerator setScreenHeight(int height) {
        mScreenHeight = height;
        return this;
    }

    public ConfigGenerator setScreenWidth(int width) {
        mScreenWidth = width;
        return this;
    }

    public ConfigGenerator setXdpi(int xdpi) {
        mXdpi = xdpi;
        return this;
    }

    public ConfigGenerator setYdpi(int ydpi) {
        mYdpi = ydpi;
        return this;
    }

    public ConfigGenerator setOrientation(ScreenOrientation orientation) {
        mOrientation = orientation;
        return this;
    }

    public ConfigGenerator setDensity(Density density) {
        mDensity = density;
        return this;
    }

    public ConfigGenerator setRatio(ScreenRatio ratio) {
        mRatio = ratio;
        return this;
    }

    public ConfigGenerator setSize(ScreenSize size) {
        mSize = size;
        return this;
    }

    public ConfigGenerator setKeyboard(Keyboard keyboard) {
        mKeyboard = keyboard;
        return this;
    }

    public ConfigGenerator setTouchScreen(TouchScreen touchScreen) {
        mTouchScreen = touchScreen;
        return this;
    }

    public ConfigGenerator setKeyboardState(KeyboardState state) {
        mKeyboardState = state;
        return this;
    }

    public ConfigGenerator setSoftButtons(boolean softButtons) {
        mSoftButtons = softButtons;
        return this;
    }

    public ConfigGenerator setNavigation(Navigation navigation) {
        mNavigation = navigation;
        return this;
    }
}
