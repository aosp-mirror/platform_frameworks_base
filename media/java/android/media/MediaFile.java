/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.media;

import static android.content.ContentResolver.MIME_TYPE_DEFAULT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.mtp.MtpConstants;

import libcore.content.type.MimeMap;

import java.util.HashMap;
import java.util.Locale;

/**
 * MediaScanner helper class.
 * <p>
 * This heavily relies upon extension to MIME type mappings which are maintained
 * in {@link MimeMap}, to ensure consistency across the OS.
 * <p>
 * When adding a new file type, first add the MIME type mapping to
 * {@link MimeMap}, and then add the MTP format mapping here.
 *
 * @hide
 */
public class MediaFile {

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    private static final int FIRST_AUDIO_FILE_TYPE = 1;
    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    private static final int LAST_AUDIO_FILE_TYPE = 10;

    /** @deprecated file types no longer exist */
    @Deprecated
    public static class MediaFileType {
        @UnsupportedAppUsage
        public final int fileType;
        @UnsupportedAppUsage
        public final String mimeType;

        MediaFileType(int fileType, String mimeType) {
            this.fileType = fileType;
            this.mimeType = mimeType;
        }
    }

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    private static final HashMap<String, MediaFileType> sFileTypeMap = new HashMap<>();
    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    private static final HashMap<String, Integer> sFileTypeToFormatMap = new HashMap<>();

    // maps mime type to MTP format code
    @UnsupportedAppUsage
    private static final HashMap<String, Integer> sMimeTypeToFormatMap = new HashMap<>();
    // maps MTP format code to mime type
    @UnsupportedAppUsage
    private static final HashMap<Integer, String> sFormatToMimeTypeMap = new HashMap<>();

    @UnsupportedAppUsage
    public MediaFile() {
    }

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    static void addFileType(String extension, int fileType, String mimeType) {
    }

    private static void addFileType(int mtpFormatCode, @NonNull String mimeType) {
        if (!sMimeTypeToFormatMap.containsKey(mimeType)) {
            sMimeTypeToFormatMap.put(mimeType, Integer.valueOf(mtpFormatCode));
        }
        if (!sFormatToMimeTypeMap.containsKey(mtpFormatCode)) {
            sFormatToMimeTypeMap.put(mtpFormatCode, mimeType);
        }
    }

    static {
        addFileType(MtpConstants.FORMAT_MP3, "audio/mpeg");
        addFileType(MtpConstants.FORMAT_WAV, "audio/x-wav");
        addFileType(MtpConstants.FORMAT_WMA, "audio/x-ms-wma");
        addFileType(MtpConstants.FORMAT_OGG, "audio/ogg");
        addFileType(MtpConstants.FORMAT_AAC, "audio/aac");
        addFileType(MtpConstants.FORMAT_FLAC, "audio/flac");
        addFileType(MtpConstants.FORMAT_AIFF, "audio/x-aiff");
        addFileType(MtpConstants.FORMAT_MP2, "audio/mpeg");

        addFileType(MtpConstants.FORMAT_MPEG, "video/mpeg");
        addFileType(MtpConstants.FORMAT_MP4_CONTAINER, "video/mp4");
        addFileType(MtpConstants.FORMAT_3GP_CONTAINER, "video/3gpp");
        addFileType(MtpConstants.FORMAT_3GP_CONTAINER, "video/3gpp2");
        addFileType(MtpConstants.FORMAT_AVI, "video/avi");
        addFileType(MtpConstants.FORMAT_WMV, "video/x-ms-wmv");
        addFileType(MtpConstants.FORMAT_ASF, "video/x-ms-asf");

        addFileType(MtpConstants.FORMAT_EXIF_JPEG, "image/jpeg");
        addFileType(MtpConstants.FORMAT_GIF, "image/gif");
        addFileType(MtpConstants.FORMAT_PNG, "image/png");
        addFileType(MtpConstants.FORMAT_BMP, "image/x-ms-bmp");
        addFileType(MtpConstants.FORMAT_HEIF, "image/heif");
        addFileType(MtpConstants.FORMAT_DNG, "image/x-adobe-dng");
        addFileType(MtpConstants.FORMAT_TIFF, "image/tiff");
        addFileType(MtpConstants.FORMAT_TIFF, "image/x-canon-cr2");
        addFileType(MtpConstants.FORMAT_TIFF, "image/x-nikon-nrw");
        addFileType(MtpConstants.FORMAT_TIFF, "image/x-sony-arw");
        addFileType(MtpConstants.FORMAT_TIFF, "image/x-panasonic-rw2");
        addFileType(MtpConstants.FORMAT_TIFF, "image/x-olympus-orf");
        addFileType(MtpConstants.FORMAT_TIFF, "image/x-pentax-pef");
        addFileType(MtpConstants.FORMAT_TIFF, "image/x-samsung-srw");
        addFileType(MtpConstants.FORMAT_TIFF_EP, "image/tiff");
        addFileType(MtpConstants.FORMAT_TIFF_EP, "image/x-nikon-nef");
        addFileType(MtpConstants.FORMAT_JP2, "image/jp2");
        addFileType(MtpConstants.FORMAT_JPX, "image/jpx");

        addFileType(MtpConstants.FORMAT_M3U_PLAYLIST, "audio/x-mpegurl");
        addFileType(MtpConstants.FORMAT_PLS_PLAYLIST, "audio/x-scpls");
        addFileType(MtpConstants.FORMAT_WPL_PLAYLIST, "application/vnd.ms-wpl");
        addFileType(MtpConstants.FORMAT_ASX_PLAYLIST, "video/x-ms-asf");

        addFileType(MtpConstants.FORMAT_TEXT, "text/plain");
        addFileType(MtpConstants.FORMAT_HTML, "text/html");
        addFileType(MtpConstants.FORMAT_XML_DOCUMENT, "text/xml");

        addFileType(MtpConstants.FORMAT_MS_WORD_DOCUMENT,
                "application/msword");
        addFileType(MtpConstants.FORMAT_MS_WORD_DOCUMENT,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        addFileType(MtpConstants.FORMAT_MS_EXCEL_SPREADSHEET,
                "application/vnd.ms-excel");
        addFileType(MtpConstants.FORMAT_MS_EXCEL_SPREADSHEET,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        addFileType(MtpConstants.FORMAT_MS_POWERPOINT_PRESENTATION,
                "application/vnd.ms-powerpoint");
        addFileType(MtpConstants.FORMAT_MS_POWERPOINT_PRESENTATION,
                "application/vnd.openxmlformats-officedocument.presentationml.presentation");
    }

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    public static boolean isAudioFileType(int fileType) {
        return false;
    }

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    public static boolean isVideoFileType(int fileType) {
        return false;
    }

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    public static boolean isImageFileType(int fileType) {
        return false;
    }

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    public static boolean isPlayListFileType(int fileType) {
        return false;
    }

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    public static boolean isDrmFileType(int fileType) {
        return false;
    }

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    public static MediaFileType getFileType(String path) {
        return null;
    }

    /**
     * Check whether the mime type is document or not.
     * @param mimeType the mime type to check
     * @return true, if the mimeType is matched. Otherwise, false.
     */
    public static boolean isDocumentMimeType(@Nullable String mimeType) {
        if (mimeType == null) {
            return false;
        }

        final String normalizedMimeType = normalizeMimeType(mimeType);
        if (normalizedMimeType.startsWith("text/")) {
            return true;
        }

        switch (normalizedMimeType.toLowerCase(Locale.ROOT)) {
            case "application/epub+zip":
            case "application/msword":
            case "application/pdf":
            case "application/rtf":
            case "application/vnd.ms-excel":
            case "application/vnd.ms-excel.addin.macroenabled.12":
            case "application/vnd.ms-excel.sheet.binary.macroenabled.12":
            case "application/vnd.ms-excel.sheet.macroenabled.12":
            case "application/vnd.ms-excel.template.macroenabled.12":
            case "application/vnd.ms-powerpoint":
            case "application/vnd.ms-powerpoint.addin.macroenabled.12":
            case "application/vnd.ms-powerpoint.presentation.macroenabled.12":
            case "application/vnd.ms-powerpoint.slideshow.macroenabled.12":
            case "application/vnd.ms-powerpoint.template.macroenabled.12":
            case "application/vnd.ms-word.document.macroenabled.12":
            case "application/vnd.ms-word.template.macroenabled.12":
            case "application/vnd.oasis.opendocument.chart":
            case "application/vnd.oasis.opendocument.database":
            case "application/vnd.oasis.opendocument.formula":
            case "application/vnd.oasis.opendocument.graphics":
            case "application/vnd.oasis.opendocument.graphics-template":
            case "application/vnd.oasis.opendocument.presentation":
            case "application/vnd.oasis.opendocument.presentation-template":
            case "application/vnd.oasis.opendocument.spreadsheet":
            case "application/vnd.oasis.opendocument.spreadsheet-template":
            case "application/vnd.oasis.opendocument.text":
            case "application/vnd.oasis.opendocument.text-master":
            case "application/vnd.oasis.opendocument.text-template":
            case "application/vnd.oasis.opendocument.text-web":
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
            case "application/vnd.openxmlformats-officedocument.presentationml.slideshow":
            case "application/vnd.openxmlformats-officedocument.presentationml.template":
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.template":
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.template":
            case "application/vnd.stardivision.calc":
            case "application/vnd.stardivision.chart":
            case "application/vnd.stardivision.draw":
            case "application/vnd.stardivision.impress":
            case "application/vnd.stardivision.impress-packed":
            case "application/vnd.stardivision.mail":
            case "application/vnd.stardivision.math":
            case "application/vnd.stardivision.writer":
            case "application/vnd.stardivision.writer-global":
            case "application/vnd.sun.xml.calc":
            case "application/vnd.sun.xml.calc.template":
            case "application/vnd.sun.xml.draw":
            case "application/vnd.sun.xml.draw.template":
            case "application/vnd.sun.xml.impress":
            case "application/vnd.sun.xml.impress.template":
            case "application/vnd.sun.xml.math":
            case "application/vnd.sun.xml.writer":
            case "application/vnd.sun.xml.writer.global":
            case "application/vnd.sun.xml.writer.template":
            case "application/x-mspublisher":
                return true;
            default:
                return false;
        }
    }

    public static boolean isExifMimeType(@Nullable String mimeType) {
        // For simplicity, assume that all image files might have EXIF data
        return isImageMimeType(mimeType);
    }

    public static boolean isAudioMimeType(@Nullable String mimeType) {
        return normalizeMimeType(mimeType).startsWith("audio/");
    }

    public static boolean isVideoMimeType(@Nullable String mimeType) {
        return normalizeMimeType(mimeType).startsWith("video/");
    }

    public static boolean isImageMimeType(@Nullable String mimeType) {
        return normalizeMimeType(mimeType).startsWith("image/");
    }

    public static boolean isPlayListMimeType(@Nullable String mimeType) {
        switch (normalizeMimeType(mimeType)) {
            case "application/vnd.ms-wpl":
            case "audio/x-mpegurl":
            case "audio/mpegurl":
            case "application/x-mpegurl":
            case "application/vnd.apple.mpegurl":
            case "audio/x-scpls":
                return true;
            default:
                return false;
        }
    }

    public static boolean isDrmMimeType(@Nullable String mimeType) {
        return normalizeMimeType(mimeType).equals("application/x-android-drm-fl");
    }

    // generates a title based on file name
    @UnsupportedAppUsage
    public static @NonNull String getFileTitle(@NonNull String path) {
        // extract file name after last slash
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) {
            lastSlash++;
            if (lastSlash < path.length()) {
                path = path.substring(lastSlash);
            }
        }
        // truncate the file extension (if any)
        int lastDot = path.lastIndexOf('.');
        if (lastDot > 0) {
            path = path.substring(0, lastDot);
        }
        return path;
    }

    public static @Nullable String getFileExtension(@Nullable String path) {
        if (path == null) {
            return null;
        }
        int lastDot = path.lastIndexOf('.');
        if (lastDot >= 0) {
            return path.substring(lastDot + 1);
        } else {
            return null;
        }
    }

    /** @deprecated file types no longer exist */
    @Deprecated
    @UnsupportedAppUsage
    public static int getFileTypeForMimeType(String mimeType) {
        return 0;
    }

    /**
     * Find the best MIME type for the given item. Prefers mappings from file
     * extensions, since they're more accurate than format codes.
     */
    public static @NonNull String getMimeType(@Nullable String path, int formatCode) {
        // First look for extension mapping
        String mimeType = getMimeTypeForFile(path);
        if (!MIME_TYPE_DEFAULT.equals(mimeType)) {
            return mimeType;
        }

        // Otherwise look for format mapping
        return getMimeTypeForFormatCode(formatCode);
    }

    @UnsupportedAppUsage
    public static @NonNull String getMimeTypeForFile(@Nullable String path) {
        String ext = getFileExtension(path);
        final String mimeType = MimeMap.getDefault().guessMimeTypeFromExtension(ext);
        return (mimeType != null) ? mimeType : MIME_TYPE_DEFAULT;
    }

    public static @NonNull String getMimeTypeForFormatCode(int formatCode) {
        final String mimeType = sFormatToMimeTypeMap.get(formatCode);
        return (mimeType != null) ? mimeType : MIME_TYPE_DEFAULT;
    }

    /**
     * Find the best MTP format code mapping for the given item. Prefers
     * mappings from MIME types, since they're more accurate than file
     * extensions.
     */
    public static int getFormatCode(@Nullable String path, @Nullable String mimeType) {
        // First look for MIME type mapping
        int formatCode = getFormatCodeForMimeType(mimeType);
        if (formatCode != MtpConstants.FORMAT_UNDEFINED) {
            return formatCode;
        }

        // Otherwise look for extension mapping
        return getFormatCodeForFile(path);
    }

    public static int getFormatCodeForFile(@Nullable String path) {
        return getFormatCodeForMimeType(getMimeTypeForFile(path));
    }

    public static int getFormatCodeForMimeType(@Nullable String mimeType) {
        if (mimeType == null) {
            return MtpConstants.FORMAT_UNDEFINED;
        }

        // First look for direct mapping
        Integer value = sMimeTypeToFormatMap.get(mimeType);
        if (value != null) {
            return value.intValue();
        }

        // Otherwise look for indirect mapping
        mimeType = normalizeMimeType(mimeType);
        value = sMimeTypeToFormatMap.get(mimeType);
        if (value != null) {
            return value.intValue();
        } else if (mimeType.startsWith("audio/")) {
            return MtpConstants.FORMAT_UNDEFINED_AUDIO;
        } else if (mimeType.startsWith("video/")) {
            return MtpConstants.FORMAT_UNDEFINED_VIDEO;
        } else if (mimeType.startsWith("image/")) {
            return MtpConstants.FORMAT_DEFINED;
        } else {
            return MtpConstants.FORMAT_UNDEFINED;
        }
    }

    /**
     * Normalize the given MIME type by bouncing through a default file
     * extension, if defined. This handles cases like "application/x-flac" to
     * ".flac" to "audio/flac".
     */
    private static @NonNull String normalizeMimeType(@Nullable String mimeType) {
        MimeMap mimeMap = MimeMap.getDefault();
        final String extension = mimeMap.guessExtensionFromMimeType(mimeType);
        if (extension != null) {
            final String extensionMimeType = mimeMap.guessMimeTypeFromExtension(extension);
            if (extensionMimeType != null) {
                return extensionMimeType;
            }
        }
        return (mimeType != null) ? mimeType : MIME_TYPE_DEFAULT;
    }
}
