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

package com.android.server;

import android.content.Context;
import android.graphics.FontListParser;
import android.os.ParcelFileDescriptor;
import android.text.FontConfig;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.font.IFontManager;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class FontManagerService extends IFontManager.Stub {
    private static final String TAG = "FontManagerService";
    private static final String FONTS_CONFIG = "/system/etc/fonts.xml";

    @GuardedBy("mLock")
    private FontConfig mConfig;
    private final Object mLock = new Object();

    public static final class Lifecycle extends SystemService {
        private final FontManagerService mService;

        public Lifecycle(Context context) {
            super(context);
            mService = new FontManagerService();
        }

        @Override
        public void onStart() {
            try {
                publishBinderService(Context.FONT_SERVICE, mService);
            } catch (Throwable t) {
                // Starting this service is not critical to the running of this device and should
                // therefore not crash the device. If it fails, log the error and continue.
                Slog.e(TAG, "Could not start the FontManagerService.", t);
            }
        }
    }

    @Override
    public FontConfig getSystemFonts() {
        synchronized (mLock) {
            if (mConfig != null) {
                return new FontConfig(mConfig);
            }

            FontConfig config = loadFromSystem();
            if (config == null) {
                return null;
            }

            final int size = config.getFamilies().size();
            for (int i = 0; i < size; ++i) {
                FontConfig.Family family = config.getFamilies().get(i);
                for (int j = 0; j < family.getFonts().size(); ++j) {
                    FontConfig.Font font = family.getFonts().get(j);
                    File fontFile = new File(font.getFontName());
                    try {
                        font.setFd(ParcelFileDescriptor.open(
                                fontFile, ParcelFileDescriptor.MODE_READ_ONLY));
                    } catch (IOException e) {
                        Slog.e(TAG, "Error opening font file " + font.getFontName(), e);
                    }
                }
            }

            mConfig = config;
            return new FontConfig(mConfig);
        }
    }

    private FontConfig loadFromSystem() {
        File configFilename = new File(FONTS_CONFIG);
        try {
            FileInputStream fontsIn = new FileInputStream(configFilename);
            return FontListParser.parse(fontsIn);
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Error opening " + configFilename, e);
        }
        return null;
    }

    public FontManagerService() {
    }
}
