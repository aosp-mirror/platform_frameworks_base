/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Typeface;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.IOException;

/** A service for managing system fonts. */
// TODO(b/173619554): Add API to update fonts.
public final class FontManagerService {

    private static final String TAG = "FontManagerService";

    /** Class to manage FontManagerService's lifecycle. */
    public static final class Lifecycle extends SystemService {
        private final FontManagerService mService;

        public Lifecycle(@NonNull Context context) {
            super(context);
            mService = new FontManagerService();
        }

        @Override
        public void onStart() {
            LocalServices.addService(FontManagerInternal.class,
                    new FontManagerInternal() {
                        @Override
                        @Nullable
                        public SharedMemory getSerializedSystemFontMap() {
                            return mService.getSerializedSystemFontMap();
                        }
                    });
        }
    }

    @GuardedBy("this")
    @Nullable
    private SharedMemory mSerializedSystemFontMap = null;

    @Nullable
    private SharedMemory getSerializedSystemFontMap() {
        synchronized (FontManagerService.this) {
            if (mSerializedSystemFontMap == null) {
                mSerializedSystemFontMap = createSerializedSystemFontMapLocked();
            }
            return mSerializedSystemFontMap;
        }
    }

    @Nullable
    private SharedMemory createSerializedSystemFontMapLocked() {
        // TODO(b/173619554): use updated fonts.
        try {
            return Typeface.serializeFontMap(Typeface.getSystemFontMap());
        } catch (IOException | ErrnoException e) {
            Slog.e(TAG, "Failed to serialize SystemServer system font map", e);
        }
        return null;
    }
}
