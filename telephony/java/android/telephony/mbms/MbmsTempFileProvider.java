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
 * limitations under the License
 */

package android.telephony.mbms;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.telephony.MbmsDownloadSession;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Objects;

/**
 * @hide
 */
public class MbmsTempFileProvider extends ContentProvider {
    public static final String TEMP_FILE_ROOT_PREF_FILE_NAME = "MbmsTempFileRootPrefs";
    public static final String TEMP_FILE_ROOT_PREF_NAME = "mbms_temp_file_root";

    private String mAuthority;
    private Context mContext;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection,
            @Nullable String selection, @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {
        throw new UnsupportedOperationException("No querying supported");
    }

    @Override
    public String getType(@NonNull Uri uri) {
        // EMBMS temp files can contain arbitrary content.
        return "application/octet-stream";
    }

    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("No inserting supported");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection,
            @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("No deleting supported");
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String
            selection, @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("No updating supported");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        // ContentProvider has already checked granted permissions
        final File file = getFileForUri(mContext, mAuthority, uri);
        final int fileMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, fileMode);
    }

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);

        // Correctness check our security
        if (info.exported) {
            throw new SecurityException("Provider must not be exported");
        }
        if (!info.grantUriPermissions) {
            throw new SecurityException("Provider must grant uri permissions");
        }

        mAuthority = info.authority;
        mContext = context;
    }

    public static Uri getUriForFile(Context context, String authority, File file) {
        // Get the canonical path of the temp file
        String filePath;
        try {
            filePath = file.getCanonicalPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not get canonical path for file " + file);
        }

        // Make sure the temp file is contained in the temp file directory as configured in the
        // manifest
        File tempFileDir = getEmbmsTempFileDir(context);
        if (!MbmsUtils.isContainedIn(tempFileDir, file)) {
            throw new IllegalArgumentException("File " + file + " is not contained in the temp " +
                    "file directory, which is " + tempFileDir);
        }

        // Get the canonical path of the temp file directory
        String tempFileDirPath;
        try {
            tempFileDirPath = tempFileDir.getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException(
                    "Could not get canonical path for temp file root dir " + tempFileDir);
        }

        // Start at first char of path under temp file directory
        String pathFragment;
        if (tempFileDirPath.endsWith("/")) {
            pathFragment = filePath.substring(tempFileDirPath.length());
        } else {
            pathFragment = filePath.substring(tempFileDirPath.length() + 1);
        }

        String encodedPath = Uri.encode(pathFragment);
        return new Uri.Builder().scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority).encodedPath(encodedPath).build();
    }

    public static File getFileForUri(Context context, String authority, Uri uri)
            throws FileNotFoundException {
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            throw new IllegalArgumentException("Uri must have scheme content");
        }
        if (!Objects.equals(authority, uri.getAuthority())) {
            throw new IllegalArgumentException("Uri does not have a matching authority: " +
                    authority + ", " + uri.getAuthority());
        }

        String relPath = Uri.decode(uri.getEncodedPath());
        File file;
        File tempFileDir;

        try {
            tempFileDir = getEmbmsTempFileDir(context).getCanonicalFile();
            file = new File(tempFileDir, relPath).getCanonicalFile();
        } catch (IOException e) {
            throw new FileNotFoundException("Could not resolve paths");
        }

        if (!file.getPath().startsWith(tempFileDir.getPath())) {
            throw new SecurityException("Resolved path jumped beyond configured root");
        }

        return file;
    }

    /**
     * Returns a File for the directory used to store temp files for this app
     */
    public static File getEmbmsTempFileDir(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(TEMP_FILE_ROOT_PREF_FILE_NAME, 0);
        String storedTempFileRoot = prefs.getString(TEMP_FILE_ROOT_PREF_NAME, null);
        try {
            if (storedTempFileRoot != null) {
                return new File(storedTempFileRoot).getCanonicalFile();
            } else {
                return new File(context.getFilesDir(),
                        MbmsDownloadSession.DEFAULT_TOP_LEVEL_TEMP_DIRECTORY).getCanonicalFile();
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to canonicalize temp file root path " + e);
        }
    }
}
