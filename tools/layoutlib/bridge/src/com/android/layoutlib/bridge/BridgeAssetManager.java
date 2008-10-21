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

package com.android.layoutlib.bridge;

import android.content.res.AssetManager;
import android.content.res.Configuration;

import java.util.Locale;

public class BridgeAssetManager extends AssetManager {
    
    /**
     * Change the configuation used when retrieving resources.  Not for use by
     * applications.
     * {@hide}
     */
    @Override
    public void setConfiguration(int mcc, int mnc, String locale,
            int orientation, int touchscreen, int density, int keyboard,
            int keyboardHidden, int navigation, int screenWidth, int screenHeight,
            int version)  {
        
        Configuration c = new Configuration();
        c.mcc = mcc;
        c.mnc = mnc;
        c.locale = new Locale(locale);
        c.touchscreen = touchscreen;
        c.keyboard = keyboard;
        c.keyboardHidden = keyboardHidden;
        c.navigation = navigation;
        c.orientation = orientation;
    }
}
