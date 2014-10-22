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

package com.android.layoutlib.bridge.bars;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static android.os.Build.VERSION_CODES.*;

/**
 * Various helper methods to simulate older versions of platform.
 */
public class Config {

    // each of these resource dirs must end in '/'
    private static final String GINGERBREAD_DIR      = "/bars/v9/";
    private static final String JELLYBEAN_DIR        = "/bars/v18/";
    private static final String KITKAT_DIR           = "/bars/v19/";
    private static final String DEFAULT_RESOURCE_DIR = "/bars/v21/";

    private static final List<String> sDefaultResourceDir =
            Collections.singletonList(DEFAULT_RESOURCE_DIR);

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    public static boolean showOnScreenNavBar(int platformVersion) {
        return platformVersion == 0 || platformVersion >= ICE_CREAM_SANDWICH;
    }

    public static int getStatusBarColor(int platformVersion) {
        // return white for froyo and earlier; black otherwise.
        return platformVersion == 0 || platformVersion >= GINGERBREAD ? BLACK : WHITE;
    }

    public static List<String> getResourceDirs(int platformVersion) {
        // Special case the most used scenario.
        if (platformVersion == 0) {
            return sDefaultResourceDir;
        }
        List<String> list = new ArrayList<String>(4);
        // Gingerbread - uses custom battery and wifi icons.
        if (platformVersion <= GINGERBREAD) {
            list.add(GINGERBREAD_DIR);
        }
        // ICS - JellyBean uses custom battery, wifi.
        if (platformVersion <= JELLY_BEAN_MR2) {
            list.add(JELLYBEAN_DIR);
        }
        // KitKat - uses custom wifi and nav icons.
        if (platformVersion <= KITKAT) {
            list.add(KITKAT_DIR);
        }
        list.add(DEFAULT_RESOURCE_DIR);

        return Collections.unmodifiableList(list);
    }

    public static String getTime(int platformVersion) {
        if (platformVersion == 0) {
            return "5:00";
        }
        if (platformVersion < GINGERBREAD) {
            return "2:20";
        }
        if (platformVersion < ICE_CREAM_SANDWICH) {
            return "2:30";
        }
        if (platformVersion < JELLY_BEAN) {
            return "4:00";
        }
        if (platformVersion < KITKAT) {
            return "4:30";
        }
        if (platformVersion <= KITKAT_WATCH) {
            return "4:40";
        }
        // Should never happen.
        return "4:04";
    }

    public static int getTimeColor(int platformVersion) {
        if (platformVersion == 0 || platformVersion >= KITKAT ||
                platformVersion > FROYO && platformVersion < HONEYCOMB) {
            // Gingerbread and KitKat onwards.
            return WHITE;
        }
        // Black for froyo.
        if (platformVersion < GINGERBREAD) {
            return BLACK;
        } else if (platformVersion < KITKAT) {
            // Honeycomb to JB-mr2: Holo blue light.
            return 0xff33b5e5;
        }
        // Should never happen.
        return WHITE;
    }

    public static String getWifiIconType(int platformVersion) {
        return platformVersion == 0 ? "xml" : "png";
    }
}
