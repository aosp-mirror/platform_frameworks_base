/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.content.pm;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * Class that provides fallback values for {@link ApplicationInfo#category}.
 *
 * @hide
 */
public class FallbackCategoryProvider {
    private static final String TAG = "FallbackCategoryProvider";

    private static final ArrayMap<String, Integer> sFallbacks = new ArrayMap<>();

    public static void loadFallbacks() {
        sFallbacks.clear();
        if (SystemProperties.getBoolean("fw.ignore_fb_categories", false)) {
            Log.d(TAG, "Ignoring fallback categories");
            return;
        }

        final AssetManager assets = new AssetManager();
        assets.addAssetPath("/system/framework/framework-res.apk");
        final Resources res = new Resources(assets, null, null);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                res.openRawResource(com.android.internal.R.raw.fallback_categories)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.charAt(0) == '#') continue;
                final String[] split = line.split(",");
                if (split.length == 2) {
                    sFallbacks.put(split[0], Integer.parseInt(split[1]));
                }
            }
            Log.d(TAG, "Found " + sFallbacks.size() + " fallback categories");
        } catch (IOException | NumberFormatException e) {
            Log.w(TAG, "Failed to read fallback categories", e);
        }
    }

    public static int getFallbackCategory(String packageName) {
        return sFallbacks.getOrDefault(packageName, ApplicationInfo.CATEGORY_UNDEFINED);
    }
}
