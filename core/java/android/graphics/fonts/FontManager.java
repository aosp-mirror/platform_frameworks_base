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

import android.annotation.IntDef;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
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

    /** @hide */
    @IntDef(prefix = "ERROR_CODE_",
            value = { ERROR_CODE_OK, ERROR_CODE_FAILED_TO_WRITE_FONT_FILE,
                    ERROR_CODE_VERIFICATION_FAILURE, ERROR_CODE_FONT_NAME_MISMATCH,
                    ERROR_CODE_INVALID_FONT_FILE, ERROR_CODE_MISSING_POST_SCRIPT_NAME,
                    ERROR_CODE_DOWNGRADING, ERROR_CODE_FAILED_TO_CREATE_CONFIG_FILE,
                    ERROR_CODE_FONT_UPDATER_DISABLED })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ErrorCode {}

    /**
     * Indicates an operation has processed successfully.
     * @hide
     */
    public static final int ERROR_CODE_OK = 0;

    /**
     * Indicates a failure of writing font files.
     * @hide
     */
    public static final int ERROR_CODE_FAILED_TO_WRITE_FONT_FILE = -1;

    /**
     * Indicates a failure of fs-verity setup.
     * @hide
     */
    public static final int ERROR_CODE_VERIFICATION_FAILURE = -2;

    /**
     * Indicates a failure of verifying the font name with PostScript name.
     * @hide
     */
    public static final int ERROR_CODE_FONT_NAME_MISMATCH = -3;

    /**
     * Indicates a failure of placing fonts due to unexpected font contents.
     * @hide
     */
    public static final int ERROR_CODE_INVALID_FONT_FILE = -4;

    /**
     * Indicates a failure due to missing PostScript name in name table.
     * @hide
     */
    public static final int ERROR_CODE_MISSING_POST_SCRIPT_NAME = -5;

    /**
     * Indicates a failure of placing fonts due to downgrading.
     * @hide
     */
    public static final int ERROR_CODE_DOWNGRADING = -6;

    /**
     * Indicates a failure of writing system font configuration XML file.
     * @hide
     */
    public static final int ERROR_CODE_FAILED_TO_CREATE_CONFIG_FILE = -7;

    /**
     * Indicates a failure due to disabled font updater.
     * @hide
     */
    public static final int ERROR_CODE_FONT_UPDATER_DISABLED = -8;

    /**
     * Indicates a failure of opening font file.
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int ERROR_CODE_FAILED_TO_OPEN_FONT_FILE = -10001;

    /**
     * Indicates a failure of opening signature file.
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int ERROR_CODE_FAILED_TO_OPEN_SIGNATURE_FILE = -10002;

    /**
     * Indicates a failure of invalid shell command arguments.
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int ERROR_CODE_INVALID_SHELL_ARGUMENT = -10003;

    /**
     * Indicates a failure of reading signature file.
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int ERROR_CODE_INVALID_SIGNATURE_FILE = -10004;

    /**
     * Indicates a failure due to exceeding allowed signature file size (8kb).
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int ERROR_CODE_SIGNATURE_TOO_LARGE = -10005;


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
