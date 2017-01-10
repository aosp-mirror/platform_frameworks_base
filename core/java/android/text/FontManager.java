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

package android.text;

import android.os.RemoteException;

import com.android.internal.font.IFontManager;

/**
 * Interact with the Font service.
 */
public final class FontManager {
    private static final String TAG = "FontManager";

    private final IFontManager mService;

    /**
     * @hide
     */
    public FontManager(IFontManager service) {
        mService = service;
    }

    /**
     * Retrieve the system fonts data. This loads the fonts.xml data if needed and loads all system
     * fonts in to memory, providing file descriptors for them.
     */
    public FontConfig getSystemFonts() {
        try {
            return mService.getSystemFonts();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
