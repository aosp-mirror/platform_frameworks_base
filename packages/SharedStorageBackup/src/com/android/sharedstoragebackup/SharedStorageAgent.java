package com.android.sharedstoragebackup;

import android.app.backup.FullBackupAgent;
import android.app.backup.FullBackup;
import android.app.backup.FullBackupDataOutput;
import android.content.Context;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.ArraySet;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import android.os.SystemProperties;
import android.content.Intent;
import android.net.Uri;

public class SharedStorageAgent extends FullBackupAgent {
    static final String TAG = "SharedStorageAgent";
    static final boolean DEBUG = false;

    StorageVolume[] mVolumes;

    @Override
    public void onCreate() {
        StorageManager mgr = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        if (mgr != null) {
            mVolumes = mgr.getVolumeList();
        } else {
            Slog.e(TAG, "Unable to access Storage Manager");
        }
    }

    /**
     * Full backup of the shared-storage filesystem
     */
    @Override
    public void onFullBackup(FullBackupDataOutput output) throws IOException {
        // If there are shared-storage volumes available, run the inherited directory-
        // hierarchy backup process on them.  By convention in the Storage Manager, the
        // "primary" shared storage volume is first in the list.
        if (mVolumes != null) {
            if (DEBUG) Slog.i(TAG, "Backing up " + mVolumes.length + " shared volumes");
            // Ignore all apps' getExternalFilesDir() content; it is backed up as part of
            // each app-specific payload.
            ArraySet<String> externalFilesDirFilter = new ArraySet();
            final File externalAndroidRoot = new File(Environment.getExternalStorageDirectory(),
                    Environment.DIRECTORY_ANDROID);
            externalFilesDirFilter.add(externalAndroidRoot.getCanonicalPath());

            for (int i = 0; i < mVolumes.length; i++) {
                StorageVolume v = mVolumes[i];
                // Express the contents of volume N this way in the tar stream:
                //     shared/N/path/to/file
                // The restore will then extract to the given volume
                String domain = FullBackup.SHARED_PREFIX + i;
                fullBackupFileTree(null, domain, v.getPath(),
                        null /* manifestExcludes */,
                        externalFilesDirFilter /* systemExcludes */, output);
            }
        }
    }

    /**
     * Full restore of one file to shared storage
     */
    @Override
    public void onRestoreFile(ParcelFileDescriptor data, long size,
            int type, String domain, String relpath, long mode, long mtime)
            throws IOException {
        if (DEBUG) Slog.d(TAG, "Shared restore: [ " + domain + " : " + relpath + "]");

        File outFile = null;

        // The file path must be in the semantic form [number]/path/to/file...
        int slash = relpath.indexOf('/');
        if (slash > 0) {
            try {
                int i = Integer.parseInt(relpath.substring(0, slash));
                if (i <= mVolumes.length) {
                    outFile = new File(mVolumes[i].getPath(), relpath.substring(slash + 1));
                    if (DEBUG) Slog.i(TAG, " => " + outFile.getAbsolutePath());
                } else {
                    Slog.w(TAG, "Cannot restore data for unavailable volume " + i);
                }
            } catch (NumberFormatException e) {
                if (DEBUG) Slog.w(TAG, "Bad volume number token: " + relpath.substring(0, slash));
            }
        } else {
            if (DEBUG) Slog.i(TAG, "Can't find volume-number token");
        }
        if (outFile == null) {
            Slog.e(TAG, "Skipping data with malformed path " + relpath);
        }

        FullBackup.restoreFile(data, size, type, -1, mtime, outFile);
        if (isStrictOpEnable()) {
            getApplicationContext().sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(outFile)));
        }
    }

    private boolean isStrictOpEnable() {
        return SystemProperties.getBoolean("persist.sys.strict_op_enable", false);
    }
}
