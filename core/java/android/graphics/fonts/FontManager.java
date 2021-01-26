/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.RemoteException;
import android.text.FontConfig;
import android.util.Log;

import com.android.internal.graphics.fonts.IFontManager;

import java.util.Objects;

/**
 * This class gives you control of system installed font files.
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.FONT_SERVICE)
public class FontManager {
    private static final String TAG = "FontManager";
    private final @NonNull IFontManager mIFontManager;

    private FontManager(@NonNull IFontManager iFontManager) {
        mIFontManager = iFontManager;
    }

    /**
     * Returns the system font configuration.
     *
     * This information is expected to be used by system font updater. If you are looking for APIs
     * about drawing text and/or high-level system font information, use
     * {@link android.graphics.Typeface} or {@link SystemFonts} instead.
     *
     * @return The current font configuration. null if failed to fetch information from the system
     *         service.
     */
    public @Nullable FontConfig getFontConfig() {
        try {
            return mIFontManager.getFontConfig();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call getFontConfig", e);
            return null;
        }
    }

    /**
     * Factory method of the FontManager.
     *
     * Do not use this method directly. Use getSystemService(Context.FONT_SERVICE) instead.
     *
     * @return A new instance of FontManager
     * @hide
     */
    public static FontManager create(@NonNull IFontManager iFontManager) {
        Objects.requireNonNull(iFontManager);
        return new FontManager(iFontManager);
    }
}
