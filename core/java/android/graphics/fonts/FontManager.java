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

import android.Manifest;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.os.RemoteException;
import android.text.FontConfig;

import com.android.internal.graphics.fonts.IFontManager;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * This class gives you control of system installed font files.
 *
 * <p>
 * This class gives you the information of system font configuration and ability of changing them.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.FONT_SERVICE)
public class FontManager {
    private static final String TAG = "FontManager";
    private final @NonNull IFontManager mIFontManager;

    /** @hide */
    @IntDef(prefix = "RESULT_",
            value = { RESULT_SUCCESS, RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                    RESULT_ERROR_VERIFICATION_FAILURE, RESULT_ERROR_VERSION_MISMATCH,
                    RESULT_ERROR_INVALID_FONT_FILE, RESULT_ERROR_INVALID_FONT_NAME,
                    RESULT_ERROR_DOWNGRADING, RESULT_ERROR_FAILED_UPDATE_CONFIG,
                    RESULT_ERROR_FONT_UPDATER_DISABLED, RESULT_ERROR_FONT_NOT_FOUND })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ResultCode {}

    /**
     * Indicates that the request has been processed successfully.
     */
    public static final int RESULT_SUCCESS = 0;

    /**
     * Indicates that a failure occurred while writing the font file to disk.
     *
     * This is an internal error that the system cannot place the font file for being used by
     * application.
     */
    public static final int RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE = -1;

    /**
     * Indicates that a failure occurred during the verification of the font file.
     *
     * The system failed to verify given font file contents and signature with system installed
     * certificate.
     */
    public static final int RESULT_ERROR_VERIFICATION_FAILURE = -2;

    /**
     * Indicates that a failure occurred as a result of invalid font format or content.
     *
     * Android only accepts OpenType compliant font files.
     */
    public static final int RESULT_ERROR_INVALID_FONT_FILE = -3;

    /**
     * Indicates a failure due to missing PostScript name in font's name table.
     *
     * Indicates that a failure occurred since PostScript name in the name table(ID=6) was missing.
     * The font is expected to have a PostScript name.
     */
    public static final int RESULT_ERROR_INVALID_FONT_NAME = -4;

    /**
     * Indicates that a failure occurred due to downgrading the font version.
     *
     * The font must have equal or newer revision in its head table.
     */
    public static final int RESULT_ERROR_DOWNGRADING = -5;

    /**
     * Indicates that a failure occurred while updating system font configuration.
     *
     * This is an internal error that the system couldn't update the {@link FontConfig}.
     */
    public static final int RESULT_ERROR_FAILED_UPDATE_CONFIG = -6;

    /**
     * Indicates a failure due to disabled font updater.
     *
     * This is typically returned due to missing Linux kernel feature.
     * The font updater only works with the Linux kernel that has fs-verity feature. The fs-verity
     * is required after the device shipped with Android 11. Thus the updated device may not have
     * fs-verity feature and font updater is disabled.
     */
    public static final int RESULT_ERROR_FONT_UPDATER_DISABLED = -7;

    /**
     * Indicates that a failure occurred because provided {@code baseVersion} did not match.
     *
     * The {@code baseVersion} provided does not match to the current {@link FontConfig} version.
     * Please get the latest configuration and update {@code baseVersion} accordingly.
     */
    public static final int RESULT_ERROR_VERSION_MISMATCH = -8;

    /**
     * Indicates a failure occurred because a font with the specified PostScript name could not be
     * found.
     */
    public static final int RESULT_ERROR_FONT_NOT_FOUND = -9;

    /**
     * Indicates a failure of opening font file.
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int RESULT_ERROR_FAILED_TO_OPEN_FONT_FILE = -10001;

    /**
     * Indicates a failure of opening signature file.
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int RESULT_ERROR_FAILED_TO_OPEN_SIGNATURE_FILE = -10002;

    /**
     * Indicates a failure of invalid shell command arguments.
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int RESULT_ERROR_INVALID_SHELL_ARGUMENT = -10003;

    /**
     * Indicates a failure of reading signature file.
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int RESULT_ERROR_INVALID_SIGNATURE_FILE = -10004;

    /**
     * Indicates a failure due to exceeding allowed signature file size (8kb).
     *
     * This error code is only used with the shell command interaction.
     *
     * @hide
     */
    public static final int RESULT_ERROR_SIGNATURE_TOO_LARGE = -10005;


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
    public @NonNull FontConfig getFontConfig() {
        try {
            return mIFontManager.getFontConfig();
        } catch (RemoteException e) {
            throw e.rethrowAsRuntimeException();
        }
    }

    /**
     * Update or add system font families.
     *
     * <p>This method will update existing font families or add new font families. The updated
     * font family definitions will be used when creating {@link android.graphics.Typeface} objects
     * with using {@link android.graphics.Typeface#create(String, int)} specifying the family name,
     * or through XML resources.
     *
     * To protect devices, system font updater relies on a Linux Kernel feature called fs-verity.
     * If the device does not support fs-verity, {@link #RESULT_ERROR_FONT_UPDATER_DISABLED} will be
     * returned.
     *
     * <p>Android only accepts OpenType compliant font files. If other font files are provided,
     * {@link #RESULT_ERROR_INVALID_FONT_FILE} will be returned.
     *
     * <p>The font file to be updated is identified by PostScript name stored in the name table. If
     * the font file doesn't have PostScript name entry, {@link #RESULT_ERROR_INVALID_FONT_NAME}
     * will be returned.
     *
     * <p>The entire font file is verified with the given signature using system installed
     * certificates. If the system cannot verify the font file contents,
     * {@link #RESULT_ERROR_VERIFICATION_FAILURE} will be returned.
     *
     * <p>The font file must have a newer revision number in the head table. In other words, it is
     * not allowed to downgrade a font file. If an older font file is provided,
     * {@link #RESULT_ERROR_DOWNGRADING} will be returned.
     *
     * <p>The caller must specify the base config version for keeping the font configuration
     * consistent. If the font configuration is updated for some reason between the time you get
     * a configuration with {@link #getFontConfig()} and the time when you call this method,
     * {@link #RESULT_ERROR_VERSION_MISMATCH} will be returned. Get the latest font configuration by
     * calling {@link #getFontConfig()} and call this method again with the latest config version.
     *
     * @param request A {@link FontFamilyUpdateRequest} to execute.
     * @param baseVersion A base config version to be updated. You can get the latest config version
     *                    by {@link FontConfig#getConfigVersion()} via {@link #getFontConfig()}. If
     *                    the system has a newer config version, the update will fail with
     *                    {@link #RESULT_ERROR_VERSION_MISMATCH}.
     * @return A result code.
     * @see FontConfig#getConfigVersion()
     * @see #getFontConfig()
     * @see #RESULT_SUCCESS
     * @see #RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE
     * @see #RESULT_ERROR_VERIFICATION_FAILURE
     * @see #RESULT_ERROR_VERSION_MISMATCH
     * @see #RESULT_ERROR_INVALID_FONT_FILE
     * @see #RESULT_ERROR_INVALID_FONT_NAME
     * @see #RESULT_ERROR_DOWNGRADING
     * @see #RESULT_ERROR_FAILED_UPDATE_CONFIG
     * @see #RESULT_ERROR_FONT_UPDATER_DISABLED
     * @see #RESULT_ERROR_FONT_NOT_FOUND
     */
    @RequiresPermission(Manifest.permission.UPDATE_FONTS) public @ResultCode int updateFontFamily(
            @NonNull FontFamilyUpdateRequest request, @IntRange(from = 0) int baseVersion) {
        List<FontUpdateRequest> requests = new ArrayList<>();
        List<FontFileUpdateRequest> fontFileUpdateRequests = request.getFontFileUpdateRequests();
        for (int i = 0; i < fontFileUpdateRequests.size(); i++) {
            FontFileUpdateRequest fontFile = fontFileUpdateRequests.get(i);
            requests.add(new FontUpdateRequest(fontFile.getParcelFileDescriptor(),
                    fontFile.getSignature()));
        }
        List<FontFamilyUpdateRequest.FontFamily> fontFamilies = request.getFontFamilies();
        for (int i = 0; i < fontFamilies.size(); i++) {
            FontFamilyUpdateRequest.FontFamily fontFamily = fontFamilies.get(i);
            requests.add(new FontUpdateRequest(fontFamily.getName(), fontFamily.getFonts()));
        }
        try {
            return mIFontManager.updateFontFamily(requests, baseVersion);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
