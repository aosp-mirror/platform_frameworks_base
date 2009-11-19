/*
 * Copyright (C) 2009 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.service.wallpaper;

import android.content.res.Resources;
import android.os.Bundle;
import android.preference.PreferenceActivity;

/**
 * Base class for activities that will be used to configure the settings of
 * a wallpaper.  You should derive from this class to allow it to select the
 * proper theme of the activity depending on how it is being used.
 * @hide
 */
public class WallpaperSettingsActivity extends PreferenceActivity {
    /**
     * This boolean extra in the launch intent indicates that the settings
     * are being used while the wallpaper is in preview mode.
     */
    final public static String EXTRA_PREVIEW_MODE
            = "android.service.wallpaper.PREVIEW_MODE";
    
    @Override
    protected void onCreate(Bundle icicle) {
        if (false) {
            Resources.Theme theme = getTheme();
            if (getIntent().getBooleanExtra(EXTRA_PREVIEW_MODE, false)) {
                theme.applyStyle(com.android.internal.R.style.PreviewWallpaperSettings, true);
            } else {
                theme.applyStyle(com.android.internal.R.style.ActiveWallpaperSettings, true);
            }
        }
        super.onCreate(icicle);
    }
}
