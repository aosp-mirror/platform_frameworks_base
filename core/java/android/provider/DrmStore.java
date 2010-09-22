/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.provider;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.drm.mobile1.DrmRawContent;
import android.drm.mobile1.DrmRights;
import android.drm.mobile1.DrmRightsManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * The DRM provider contains forward locked DRM content.
 *
 * @hide
 */
public final class DrmStore
{
    private static final String TAG = "DrmStore";

    public static final String AUTHORITY = "drm";

    /**
     * This is in the Manifest class of the drm provider, but that isn't visible
     * in the framework.
     */
    private static final String ACCESS_DRM_PERMISSION = "android.permission.ACCESS_DRM";

    /**
     * Fields for DRM database
     */

    public interface Columns extends BaseColumns {
        /**
         * The data stream for the file
         * <P>Type: DATA STREAM</P>
         */
        public static final String DATA = "_data";

        /**
         * The size of the file in bytes
         * <P>Type: INTEGER (long)</P>
         */
        public static final String SIZE = "_size";

        /**
         * The title of the file content
         * <P>Type: TEXT</P>
         */
        public static final String TITLE = "title";

        /**
         * The MIME type of the file
         * <P>Type: TEXT</P>
         */
        public static final String MIME_TYPE = "mime_type";

    }

    public interface Images extends Columns {

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/images");
    }

    public interface Audio extends Columns {

        public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/audio");
    }

    /**
     * Utility function for inserting a file into the DRM content provider.
     *
     * @param cr The content resolver to use
     * @param file The file to insert
     * @param title The title for the content (or null)
     * @return uri to the DRM record or null
     */
    public static final Intent addDrmFile(ContentResolver cr, File file, String title) {
        FileInputStream fis = null;
        Intent result = null;

        try {
            fis = new FileInputStream(file);
            if (title == null) {
                title = file.getName();
                int lastDot = title.lastIndexOf('.');
                if (lastDot > 0) {
                    title = title.substring(0, lastDot);
                }
            }
            result = addDrmFile(cr, fis, title);
        } catch (Exception e) {
            Log.e(TAG, "pushing file failed", e);
        } finally {
            try {
                if (fis != null)
                    fis.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException in DrmStore.addDrmFile()", e);
            }
        }

        return result;
    }

    /**
     * Utility function for inserting a file stream into the DRM content provider.
     *
     * @param cr The content resolver to use
     * @param fis The FileInputStream to insert
     * @param title The title for the content (or null)
     * @return uri to the DRM record or null
     */
    public static final Intent addDrmFile(ContentResolver cr, FileInputStream fis, String title) {
        OutputStream os = null;
        Intent result = null;

        try {
            DrmRawContent content = new DrmRawContent(fis, (int) fis.available(),
                    DrmRawContent.DRM_MIMETYPE_MESSAGE_STRING);
            String mimeType = content.getContentType();
            long size = fis.getChannel().size();

            DrmRightsManager manager = manager = DrmRightsManager.getInstance();
            DrmRights rights = manager.queryRights(content);
            InputStream stream = content.getContentInputStream(rights);

            Uri contentUri = null;
            if (mimeType.startsWith("audio/")) {
                contentUri = DrmStore.Audio.CONTENT_URI;
            } else if (mimeType.startsWith("image/")) {
                contentUri = DrmStore.Images.CONTENT_URI;
            } else {
                Log.w(TAG, "unsupported mime type " + mimeType);
            }

            if (contentUri != null) {
                ContentValues values = new ContentValues(3);
                values.put(DrmStore.Columns.TITLE, title);
                values.put(DrmStore.Columns.SIZE, size);
                values.put(DrmStore.Columns.MIME_TYPE, mimeType);

                Uri uri = cr.insert(contentUri, values);
                if (uri != null) {
                    os = cr.openOutputStream(uri);

                    byte[] buffer = new byte[1000];
                    int count;

                    while ((count = stream.read(buffer)) != -1) {
                        os.write(buffer, 0, count);
                    }
                    result = new Intent();
                    result.setDataAndType(uri, mimeType);

                }
            }
        } catch (Exception e) {
            Log.e(TAG, "pushing file failed", e);
        } finally {
            try {
                if (fis != null)
                    fis.close();
                if (os != null)
                    os.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException in DrmStore.addDrmFile()", e);
            }
        }

        return result;
    }

    /**
     * Utility function to enforce any permissions required to access DRM
     * content.
     *
     * @param context A context used for checking calling permission.
     */
    public static void enforceAccessDrmPermission(Context context) {
        if (context.checkCallingOrSelfPermission(ACCESS_DRM_PERMISSION) !=
                PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires DRM permission");
        }
    }

}
