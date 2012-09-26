/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.app.backup;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import libcore.io.ErrnoException;
import libcore.io.Libcore;

/**
 * Global constant definitions et cetera related to the full-backup-to-fd
 * binary format.  Nothing in this namespace is part of any API; it's all
 * hidden details of the current implementation gathered into one location.
 *
 * @hide
 */
public class FullBackup {
    static final String TAG = "FullBackup";

    public static final String APK_TREE_TOKEN = "a";
    public static final String OBB_TREE_TOKEN = "obb";
    public static final String ROOT_TREE_TOKEN = "r";
    public static final String DATA_TREE_TOKEN = "f";
    public static final String DATABASE_TREE_TOKEN = "db";
    public static final String SHAREDPREFS_TREE_TOKEN = "sp";
    public static final String CACHE_TREE_TOKEN = "c";
    public static final String SHARED_STORAGE_TOKEN = "shared";

    public static final String APPS_PREFIX = "apps/";
    public static final String SHARED_PREFIX = SHARED_STORAGE_TOKEN + "/";

    public static final String FULL_BACKUP_INTENT_ACTION = "fullback";
    public static final String FULL_RESTORE_INTENT_ACTION = "fullrest";
    public static final String CONF_TOKEN_INTENT_EXTRA = "conftoken";

    /**
     * @hide
     */
    static public native int backupToTar(String packageName, String domain,
            String linkdomain, String rootpath, String path, BackupDataOutput output);

    /**
     * Copy data from a socket to the given File location on permanent storage.  The
     * modification time and access mode of the resulting file will be set if desired,
     * although group/all rwx modes will be stripped: the restored file will not be
     * accessible from outside the target application even if the original file was.
     * If the {@code type} parameter indicates that the result should be a directory,
     * the socket parameter may be {@code null}; even if it is valid, no data will be
     * read from it in this case.
     * <p>
     * If the {@code mode} argument is negative, then the resulting output file will not
     * have its access mode or last modification time reset as part of this operation.
     *
     * @param data Socket supplying the data to be copied to the output file.  If the
     *    output is a directory, this may be {@code null}.
     * @param size Number of bytes of data to copy from the socket to the file.  At least
     *    this much data must be available through the {@code data} parameter.
     * @param type Must be either {@link BackupAgent#TYPE_FILE} for ordinary file data
     *    or {@link BackupAgent#TYPE_DIRECTORY} for a directory.
     * @param mode Unix-style file mode (as used by the chmod(2) syscall) to be set on
     *    the output file or directory.  group/all rwx modes are stripped even if set
     *    in this parameter.  If this parameter is negative then neither
     *    the mode nor the mtime values will be applied to the restored file.
     * @param mtime A timestamp in the standard Unix epoch that will be imposed as the
     *    last modification time of the output file.  if the {@code mode} parameter is
     *    negative then this parameter will be ignored.
     * @param outFile Location within the filesystem to place the data.  This must point
     *    to a location that is writeable by the caller, prefereably using an absolute path.
     * @throws IOException
     */
    static public void restoreFile(ParcelFileDescriptor data,
            long size, int type, long mode, long mtime, File outFile) throws IOException {
        if (type == BackupAgent.TYPE_DIRECTORY) {
            // Canonically a directory has no associated content, so we don't need to read
            // anything from the pipe in this case.  Just create the directory here and
            // drop down to the final metadata adjustment.
            if (outFile != null) outFile.mkdirs();
        } else {
            FileOutputStream out = null;

            // Pull the data from the pipe, copying it to the output file, until we're done
            try {
                if (outFile != null) {
                    File parent = outFile.getParentFile();
                    if (!parent.exists()) {
                        // in practice this will only be for the default semantic directories,
                        // and using the default mode for those is appropriate.
                        parent.mkdirs();
                    }
                    out = new FileOutputStream(outFile);
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to create/open file " + outFile.getPath(), e);
            }

            byte[] buffer = new byte[32 * 1024];
            final long origSize = size;
            FileInputStream in = new FileInputStream(data.getFileDescriptor());
            while (size > 0) {
                int toRead = (size > buffer.length) ? buffer.length : (int)size;
                int got = in.read(buffer, 0, toRead);
                if (got <= 0) {
                    Log.w(TAG, "Incomplete read: expected " + size + " but got "
                            + (origSize - size));
                    break;
                }
                if (out != null) {
                    try {
                        out.write(buffer, 0, got);
                    } catch (IOException e) {
                        // Problem writing to the file.  Quit copying data and delete
                        // the file, but of course keep consuming the input stream.
                        Log.e(TAG, "Unable to write to file " + outFile.getPath(), e);
                        out.close();
                        out = null;
                        outFile.delete();
                    }
                }
                size -= got;
            }
            if (out != null) out.close();
        }

        // Now twiddle the state to match the backup, assuming all went well
        if (mode >= 0 && outFile != null) {
            try {
                // explicitly prevent emplacement of files accessible by outside apps
                mode &= 0700;
                Libcore.os.chmod(outFile.getPath(), (int)mode);
            } catch (ErrnoException e) {
                e.rethrowAsIOException();
            }
            outFile.setLastModified(mtime);
        }
    }
}
