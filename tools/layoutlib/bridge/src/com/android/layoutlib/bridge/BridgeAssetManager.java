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
     * This initializes the static field {@link AssetManager#mSystem} which is used
     * by methods who get a global asset manager using {@link AssetManager#getSystem()}.
     * <p/>
     * They will end up using our bridge asset manager.
     * <p/>
     * {@link Bridge} calls this method after setting up a new bridge.
     */
    /*package*/ static AssetManager initSystem() {
        if (!(AssetManager.sSystem instanceof BridgeAssetManager)) {
            // Note that AssetManager() creates a system AssetManager and we override it
            // with our BridgeAssetManager.
            AssetManager.sSystem = new BridgeAssetManager();
            AssetManager.sSystem.makeStringBlocks(false);
        }
        return AssetManager.sSystem;
    }
    
    /**
     * Clears the static {@link AssetManager#sSystem} to make sure we don't leave objects
     * around that would prevent us from unloading the library.
     */
    /*package*/ static void clearSystem() {
        AssetManager.sSystem = null;
    }
    
    private BridgeAssetManager() {
    }
    
    /**
     * Change the configuration used when retrieving resources.  Not for use by applications.
     */
    @Override
    public void setConfiguration(int mcc, int mnc, String locale,
            int orientation, int touchscreen, int density, int keyboard,
            int keyboardHidden, int navigation, int screenWidth, int screenHeight,
            int screenLayout, int version)  {
        
        Configuration c = new Configuration();
        c.mcc = mcc;
        c.mnc = mnc;
        c.locale = new Locale(locale);
        c.touchscreen = touchscreen;
        c.keyboard = keyboard;
        c.keyboardHidden = keyboardHidden;
        c.navigation = navigation;
        c.orientation = orientation;
        c.screenLayout = screenLayout;
    }
}
