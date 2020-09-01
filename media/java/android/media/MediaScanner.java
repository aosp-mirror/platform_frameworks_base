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

import android.compat.annotation.UnsupportedAppUsage;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;

/**
 * @hide
 * @deprecated this media scanner has served faithfully for many years, but it's
 *             become tedious to test and maintain, mainly due to the way it
 *             weaves obscurely between managed and native code. It's been
 *             replaced by {@code ModernMediaScanner} in the
 *             {@code MediaProvider} package.
 */
@Deprecated
public class MediaScanner implements AutoCloseable {
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private static final String[] FILES_PRESCAN_PROJECTION = new String[] {
    };

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private final Context mContext;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private final String mPackageName;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private final Uri mAudioUri;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private final Uri mFilesUri;

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private String mDefaultRingtoneFilename;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private String mDefaultNotificationFilename;
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private String mDefaultAlarmAlertFilename;

    private static class FileEntry {
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        long mRowId;
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        boolean mLastModifiedChanged;

        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        FileEntry(long rowId, String path, long lastModified, int format) {
            throw new UnsupportedOperationException();
        }
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private MediaInserter mMediaInserter;

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    public MediaScanner(Context c, String volumeName) {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private final MyMediaScannerClient mClient = new MyMediaScannerClient();

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private boolean isDrmEnabled() {
        throw new UnsupportedOperationException();
    }

    private class MyMediaScannerClient implements MediaScannerClient {
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        private String mMimeType;
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        private int mFileType;
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        private String mPath;
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        private boolean mIsDrm;
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        private boolean mNoMedia;

        public MyMediaScannerClient() {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        public FileEntry beginFile(String path, String mimeType, long lastModified,
                long fileSize, boolean isDirectory, boolean noMedia) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        public void scanFile(String path, long lastModified, long fileSize,
                boolean isDirectory, boolean noMedia) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        public Uri doScanFile(String path, String mimeType, long lastModified,
                long fileSize, boolean isDirectory, boolean scanAlways, boolean noMedia) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        public void handleStringTag(String name, String value) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        public void setMimeType(String mimeType) {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        private ContentValues toValues() {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        private Uri endFile(FileEntry entry, boolean ringtones, boolean notifications,
                boolean alarms, boolean podcasts, boolean audiobooks, boolean music)
                throws RemoteException {
            throw new UnsupportedOperationException();
        }

        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
        private int getFileTypeFromDrm(String path) {
            throw new UnsupportedOperationException();
        }
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private void prescan(String filePath, boolean prescanFiles) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private void postscan(final String[] directories) throws RemoteException {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    public Uri scanSingleFile(String path, String mimeType) {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    public static boolean isNoMediaPath(String path) {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    FileEntry makeEntryFor(String path) {
        throw new UnsupportedOperationException();
    }

    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "All scanning requests should be performed through {@link android.media.MediaScannerConnection}")
    private void setLocale(String locale) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException();
    }
}
