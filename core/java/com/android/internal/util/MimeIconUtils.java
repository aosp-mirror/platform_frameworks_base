/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver.TypeInfo;
import android.content.res.Resources;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.util.ArrayMap;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;

import libcore.net.MimeUtils;

import java.util.Locale;
import java.util.Objects;

public class MimeIconUtils {
    @GuardedBy("sCache")
    private static final ArrayMap<String, TypeInfo> sCache = new ArrayMap<>();

    private static TypeInfo buildTypeInfo(String mimeType, int iconId,
            int labelId, int extLabelId) {
        final Resources res = Resources.getSystem();

        // If this MIME type has an extension, customize the label
        final CharSequence label;
        final String ext = MimeUtils.guessExtensionFromMimeType(mimeType);
        if (!TextUtils.isEmpty(ext) && extLabelId != -1) {
            label = res.getString(extLabelId, ext.toUpperCase(Locale.US));
        } else {
            label = res.getString(labelId);
        }

        return new TypeInfo(Icon.createWithResource(res, iconId), label, label);
    }

    private static @Nullable TypeInfo buildTypeInfo(@NonNull String mimeType) {
        switch (mimeType) {
            case "inode/directory":
            case "vnd.android.document/directory":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_folder,
                        R.string.mime_type_folder, -1);

            case "application/vnd.android.package-archive":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_apk,
                        R.string.mime_type_apk, -1);

            case "application/pgp-keys":
            case "application/pgp-signature":
            case "application/x-pkcs12":
            case "application/x-pkcs7-certreqresp":
            case "application/x-pkcs7-crl":
            case "application/x-x509-ca-cert":
            case "application/x-x509-user-cert":
            case "application/x-pkcs7-certificates":
            case "application/x-pkcs7-mime":
            case "application/x-pkcs7-signature":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_certificate,
                        R.string.mime_type_generic, R.string.mime_type_generic_ext);

            case "application/rdf+xml":
            case "application/rss+xml":
            case "application/x-object":
            case "application/xhtml+xml":
            case "text/css":
            case "text/html":
            case "text/xml":
            case "text/x-c++hdr":
            case "text/x-c++src":
            case "text/x-chdr":
            case "text/x-csrc":
            case "text/x-dsrc":
            case "text/x-csh":
            case "text/x-haskell":
            case "text/x-java":
            case "text/x-literate-haskell":
            case "text/x-pascal":
            case "text/x-tcl":
            case "text/x-tex":
            case "application/x-latex":
            case "application/x-texinfo":
            case "application/atom+xml":
            case "application/ecmascript":
            case "application/json":
            case "application/javascript":
            case "application/xml":
            case "text/javascript":
            case "application/x-javascript":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_codes,
                        R.string.mime_type_document, R.string.mime_type_document_ext);

            case "application/mac-binhex40":
            case "application/rar":
            case "application/zip":
            case "application/x-apple-diskimage":
            case "application/x-debian-package":
            case "application/x-gtar":
            case "application/x-iso9660-image":
            case "application/x-lha":
            case "application/x-lzh":
            case "application/x-lzx":
            case "application/x-stuffit":
            case "application/x-tar":
            case "application/x-webarchive":
            case "application/x-webarchive-xml":
            case "application/gzip":
            case "application/x-7z-compressed":
            case "application/x-deb":
            case "application/x-rar-compressed":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_compressed,
                        R.string.mime_type_compressed, R.string.mime_type_compressed_ext);

            case "text/x-vcard":
            case "text/vcard":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_contact,
                        R.string.mime_type_generic, R.string.mime_type_generic_ext);

            case "text/calendar":
            case "text/x-vcalendar":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_event,
                        R.string.mime_type_generic, R.string.mime_type_generic_ext);

            case "application/x-font":
            case "application/font-woff":
            case "application/x-font-woff":
            case "application/x-font-ttf":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_font,
                        R.string.mime_type_generic, R.string.mime_type_generic_ext);

            case "application/vnd.oasis.opendocument.graphics":
            case "application/vnd.oasis.opendocument.graphics-template":
            case "application/vnd.oasis.opendocument.image":
            case "application/vnd.stardivision.draw":
            case "application/vnd.sun.xml.draw":
            case "application/vnd.sun.xml.draw.template":
            case "application/vnd.google-apps.drawing":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_image,
                        R.string.mime_type_image, R.string.mime_type_image_ext);

            case "application/pdf":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_pdf,
                        R.string.mime_type_document, R.string.mime_type_document_ext);

            case "application/vnd.stardivision.impress":
            case "application/vnd.sun.xml.impress":
            case "application/vnd.sun.xml.impress.template":
            case "application/x-kpresenter":
            case "application/vnd.oasis.opendocument.presentation":
            case "application/vnd.google-apps.presentation":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_presentation,
                        R.string.mime_type_presentation, R.string.mime_type_presentation_ext);

            case "application/vnd.oasis.opendocument.spreadsheet":
            case "application/vnd.oasis.opendocument.spreadsheet-template":
            case "application/vnd.stardivision.calc":
            case "application/vnd.sun.xml.calc":
            case "application/vnd.sun.xml.calc.template":
            case "application/x-kspread":
            case "application/vnd.google-apps.spreadsheet":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_spreadsheet,
                        R.string.mime_type_spreadsheet, R.string.mime_type_spreadsheet_ext);

            case "application/vnd.oasis.opendocument.text":
            case "application/vnd.oasis.opendocument.text-master":
            case "application/vnd.oasis.opendocument.text-template":
            case "application/vnd.oasis.opendocument.text-web":
            case "application/vnd.stardivision.writer":
            case "application/vnd.stardivision.writer-global":
            case "application/vnd.sun.xml.writer":
            case "application/vnd.sun.xml.writer.global":
            case "application/vnd.sun.xml.writer.template":
            case "application/x-abiword":
            case "application/x-kword":
            case "application/vnd.google-apps.document":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_document,
                        R.string.mime_type_document, R.string.mime_type_document_ext);

            case "application/x-quicktimeplayer":
            case "application/x-shockwave-flash":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_video,
                        R.string.mime_type_video, R.string.mime_type_video_ext);

            case "application/msword":
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document":
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.template":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_word,
                        R.string.mime_type_document, R.string.mime_type_document_ext);

            case "application/vnd.ms-excel":
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet":
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.template":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_excel,
                        R.string.mime_type_spreadsheet, R.string.mime_type_spreadsheet_ext);

            case "application/vnd.ms-powerpoint":
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation":
            case "application/vnd.openxmlformats-officedocument.presentationml.template":
            case "application/vnd.openxmlformats-officedocument.presentationml.slideshow":
                return buildTypeInfo(mimeType, R.drawable.ic_doc_powerpoint,
                        R.string.mime_type_presentation, R.string.mime_type_presentation_ext);

            default:
                return buildGenericTypeInfo(mimeType);
        }
    }

    private static @Nullable TypeInfo buildGenericTypeInfo(@NonNull String mimeType) {
        // Look for partial matches
        if (mimeType.startsWith("audio/")) {
            return buildTypeInfo(mimeType, R.drawable.ic_doc_audio,
                    R.string.mime_type_audio, R.string.mime_type_audio_ext);
        } else if (mimeType.startsWith("video/")) {
            return buildTypeInfo(mimeType, R.drawable.ic_doc_video,
                    R.string.mime_type_video, R.string.mime_type_video_ext);
        } else if (mimeType.startsWith("image/")) {
            return buildTypeInfo(mimeType, R.drawable.ic_doc_image,
                    R.string.mime_type_image, R.string.mime_type_image_ext);
        } else if (mimeType.startsWith("text/")) {
            return buildTypeInfo(mimeType, R.drawable.ic_doc_text,
                    R.string.mime_type_document, R.string.mime_type_document_ext);
        }

        // As one last-ditch effort, try "bouncing" the MIME type through its
        // default extension. This handles cases like "application/x-flac" to
        // ".flac" to "audio/flac".
        final String bouncedMimeType = MimeUtils
                .guessMimeTypeFromExtension(MimeUtils.guessExtensionFromMimeType(mimeType));
        if (bouncedMimeType != null && !Objects.equals(mimeType, bouncedMimeType)) {
            return buildTypeInfo(bouncedMimeType);
        }

        // Worst case, return a generic file
        return buildTypeInfo(mimeType, R.drawable.ic_doc_generic,
                R.string.mime_type_generic, R.string.mime_type_generic_ext);
    }

    public static @NonNull TypeInfo getTypeInfo(@NonNull String mimeType) {
        // Normalize MIME type
        mimeType = mimeType.toLowerCase(Locale.US);

        synchronized (sCache) {
            TypeInfo res = sCache.get(mimeType);
            if (res == null) {
                res = buildTypeInfo(mimeType);
                sCache.put(mimeType, res);
            }
            return res;
        }
    }
}
