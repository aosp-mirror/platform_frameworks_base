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

package com.android.server.backup.utils;

import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_NAME;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_EVENT_PACKAGE_VERSION;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_MANIFEST_PACKAGE_NAME;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_OLD_VERSION;
import static android.app.backup.BackupManagerMonitor.EXTRA_LOG_POLICY_ALLOW_APKS;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_CATEGORY_AGENT;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_APK_NOT_INSTALLED;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_CANNOT_RESTORE_WITHOUT_APK;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_EXPECTED_DIFFERENT_PACKAGE;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_ALLOW_BACKUP_FALSE;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_FULL_RESTORE_SIGNATURE_MISMATCH;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_MISSING_SIGNATURE;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_RESTORE_ANY_VERSION;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_SYSTEM_APP_NO_AGENT;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_VERSIONS_MATCH;
import static android.app.backup.BackupManagerMonitor.LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER;

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.UserBackupManagerService.BACKUP_MANIFEST_FILENAME;
import static com.android.server.backup.UserBackupManagerService.BACKUP_MANIFEST_VERSION;
import static com.android.server.backup.UserBackupManagerService.BACKUP_METADATA_FILENAME;
import static com.android.server.backup.UserBackupManagerService.BACKUP_WIDGET_METADATA_TOKEN;
import static com.android.server.backup.UserBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;

import android.app.backup.BackupAgent;
import android.app.backup.BackupManagerMonitor;
import android.app.backup.FullBackup;
import android.app.backup.IBackupManagerMonitor;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Slog;

import com.android.server.backup.FileMetadata;
import com.android.server.backup.restore.RestorePolicy;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Utility methods to read backup tar file.
 */
public class TarBackupReader {
    private static final int TAR_HEADER_OFFSET_TYPE_CHAR = 156;
    private static final int TAR_HEADER_LENGTH_PATH = 100;
    private static final int TAR_HEADER_OFFSET_PATH = 0;
    private static final int TAR_HEADER_LENGTH_PATH_PREFIX = 155;
    private static final int TAR_HEADER_OFFSET_PATH_PREFIX = 345;
    private static final int TAR_HEADER_LENGTH_MODE = 8;
    private static final int TAR_HEADER_OFFSET_MODE = 100;
    private static final int TAR_HEADER_LENGTH_MODTIME = 12;
    private static final int TAR_HEADER_OFFSET_MODTIME = 136;
    private static final int TAR_HEADER_LENGTH_FILESIZE = 12;
    private static final int TAR_HEADER_OFFSET_FILESIZE = 124;
    private static final int TAR_HEADER_LONG_RADIX = 8;

    private final InputStream mInputStream;
    private final BytesReadListener mBytesReadListener;

    private IBackupManagerMonitor mMonitor;

    // Widget blob to be restored out-of-band.
    private byte[] mWidgetData = null;

    public TarBackupReader(InputStream inputStream, BytesReadListener bytesReadListener,
            IBackupManagerMonitor monitor) {
        mInputStream = inputStream;
        mBytesReadListener = bytesReadListener;
        mMonitor = monitor;
    }

    /**
     * Consumes a tar file header block [sequence] and accumulates the relevant metadata.
     */
    public FileMetadata readTarHeaders() throws IOException {
        byte[] block = new byte[512];
        FileMetadata info = null;

        boolean gotHeader = readTarHeader(block);
        if (gotHeader) {
            try {
                // okay, presume we're okay, and extract the various metadata
                info = new FileMetadata();
                info.size = extractRadix(block,
                        TAR_HEADER_OFFSET_FILESIZE,
                        TAR_HEADER_LENGTH_FILESIZE,
                        TAR_HEADER_LONG_RADIX);
                info.mtime = extractRadix(block,
                        TAR_HEADER_OFFSET_MODTIME,
                        TAR_HEADER_LENGTH_MODTIME,
                        TAR_HEADER_LONG_RADIX);
                info.mode = extractRadix(block,
                        TAR_HEADER_OFFSET_MODE,
                        TAR_HEADER_LENGTH_MODE,
                        TAR_HEADER_LONG_RADIX);

                info.path = extractString(block,
                        TAR_HEADER_OFFSET_PATH_PREFIX,
                        TAR_HEADER_LENGTH_PATH_PREFIX);
                String path = extractString(block,
                        TAR_HEADER_OFFSET_PATH,
                        TAR_HEADER_LENGTH_PATH);
                if (path.length() > 0) {
                    if (info.path.length() > 0) {
                        info.path += '/';
                    }
                    info.path += path;
                }

                // tar link indicator field: 1 byte at offset 156 in the header.
                int typeChar = block[TAR_HEADER_OFFSET_TYPE_CHAR];
                if (typeChar == 'x') {
                    // pax extended header, so we need to read that
                    gotHeader = readPaxExtendedHeader(info);
                    if (gotHeader) {
                        // and after a pax extended header comes another real header -- read
                        // that to find the real file type
                        gotHeader = readTarHeader(block);
                    }
                    if (!gotHeader) {
                        throw new IOException("Bad or missing pax header");
                    }

                    typeChar = block[TAR_HEADER_OFFSET_TYPE_CHAR];
                }

                switch (typeChar) {
                    case '0':
                        info.type = BackupAgent.TYPE_FILE;
                        break;
                    case '5': {
                        info.type = BackupAgent.TYPE_DIRECTORY;
                        if (info.size != 0) {
                            Slog.w(TAG, "Directory entry with nonzero size in header");
                            info.size = 0;
                        }
                        break;
                    }
                    case 0: {
                        // presume EOF
                        if (MORE_DEBUG) {
                            Slog.w(TAG, "Saw type=0 in tar header block, info=" + info);
                        }
                        return null;
                    }
                    default: {
                        Slog.e(TAG, "Unknown tar entity type: " + typeChar);
                        throw new IOException("Unknown entity type " + typeChar);
                    }
                }

                // Parse out the path
                //
                // first: apps/shared/unrecognized
                if (FullBackup.SHARED_PREFIX.regionMatches(0,
                        info.path, 0, FullBackup.SHARED_PREFIX.length())) {
                    // File in shared storage.  !!! TODO: implement this.
                    info.path = info.path.substring(FullBackup.SHARED_PREFIX.length());
                    info.packageName = SHARED_BACKUP_AGENT_PACKAGE;
                    info.domain = FullBackup.SHARED_STORAGE_TOKEN;
                    if (DEBUG) {
                        Slog.i(TAG, "File in shared storage: " + info.path);
                    }
                } else if (FullBackup.APPS_PREFIX.regionMatches(0,
                        info.path, 0, FullBackup.APPS_PREFIX.length())) {
                    // App content!  Parse out the package name and domain

                    // strip the apps/ prefix
                    info.path = info.path.substring(FullBackup.APPS_PREFIX.length());

                    // extract the package name
                    int slash = info.path.indexOf('/');
                    if (slash < 0) {
                        throw new IOException("Illegal semantic path in " + info.path);
                    }
                    info.packageName = info.path.substring(0, slash);
                    info.path = info.path.substring(slash + 1);

                    // if it's a manifest or metadata payload we're done, otherwise parse
                    // out the domain into which the file will be restored
                    if (!info.path.equals(BACKUP_MANIFEST_FILENAME) &&
                            !info.path.equals(BACKUP_METADATA_FILENAME)) {
                        slash = info.path.indexOf('/');
                        if (slash < 0) {
                            throw new IOException("Illegal semantic path in non-manifest "
                                    + info.path);
                        }
                        info.domain = info.path.substring(0, slash);
                        info.path = info.path.substring(slash + 1);
                    }
                }
            } catch (IOException e) {
                if (DEBUG) {
                    Slog.e(TAG, "Parse error in header: " + e.getMessage());
                    if (MORE_DEBUG) {
                        hexLog(block);
                    }
                }
                throw e;
            }
        }
        return info;
    }

    /**
     * Tries to read exactly the given number of bytes into a buffer at the stated offset.
     *
     * @param in - input stream to read bytes from..
     * @param buffer - where to write bytes to.
     * @param offset - offset in buffer to write bytes to.
     * @param size - number of bytes to read.
     * @return number of bytes actually read.
     * @throws IOException in case of an error.
     */
    private static int readExactly(InputStream in, byte[] buffer, int offset, int size)
            throws IOException {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be > 0");
        }
        if (MORE_DEBUG) {
            Slog.i(TAG, "  ... readExactly(" + size + ") called");
        }
        int soFar = 0;
        while (soFar < size) {
            int nRead = in.read(buffer, offset + soFar, size - soFar);
            if (nRead <= 0) {
                if (MORE_DEBUG) {
                    Slog.w(TAG, "- wanted exactly " + size + " but got only " + soFar);
                }
                break;
            }
            soFar += nRead;
            if (MORE_DEBUG) {
                Slog.v(TAG, "   + got " + nRead + "; now wanting " + (size - soFar));
            }
        }
        return soFar;
    }

    /**
     * Reads app manifest, filling version and hasApk fields in the metadata, and returns array of
     * signatures.
     *
     * @param info - file metadata.
     * @return array of signatures or null, in case of an error.
     * @throws IOException in case of an error.
     */
    public Signature[] readAppManifestAndReturnSignatures(FileMetadata info)
            throws IOException {
        // Fail on suspiciously large manifest files
        if (info.size > 64 * 1024) {
            throw new IOException("Restore manifest too big; corrupt? size=" + info.size);
        }

        byte[] buffer = new byte[(int) info.size];
        if (MORE_DEBUG) {
            Slog.i(TAG,
                    "   readAppManifestAndReturnSignatures() looking for " + info.size + " bytes");
        }
        if (readExactly(mInputStream, buffer, 0, (int) info.size) == info.size) {
            mBytesReadListener.onBytesRead(info.size);
        } else {
            throw new IOException("Unexpected EOF in manifest");
        }

        String[] str = new String[1];
        int offset = 0;

        try {
            offset = extractLine(buffer, offset, str);
            int version = Integer.parseInt(str[0]);
            if (version == BACKUP_MANIFEST_VERSION) {
                offset = extractLine(buffer, offset, str);
                String manifestPackage = str[0];
                // TODO: handle <original-package>
                if (manifestPackage.equals(info.packageName)) {
                    offset = extractLine(buffer, offset, str);
                    info.version = Integer.parseInt(str[0]);  // app version
                    offset = extractLine(buffer, offset, str);
                    // This is the platform version, which we don't use, but we parse it
                    // as a safety against corruption in the manifest.
                    Integer.parseInt(str[0]);
                    offset = extractLine(buffer, offset, str);
                    info.installerPackageName = (str[0].length() > 0) ? str[0] : null;
                    offset = extractLine(buffer, offset, str);
                    info.hasApk = str[0].equals("1");
                    offset = extractLine(buffer, offset, str);
                    int numSigs = Integer.parseInt(str[0]);
                    if (numSigs > 0) {
                        Signature[] sigs = new Signature[numSigs];
                        for (int i = 0; i < numSigs; i++) {
                            offset = extractLine(buffer, offset, str);
                            sigs[i] = new Signature(str[0]);
                        }
                        return sigs;
                    } else {
                        Slog.i(TAG, "Missing signature on backed-up package " + info.packageName);
                        mMonitor = BackupManagerMonitorUtils.monitorEvent(
                                mMonitor,
                                LOG_EVENT_ID_MISSING_SIGNATURE,
                                null,
                                LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                BackupManagerMonitorUtils.putMonitoringExtra(null,
                                        EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName));
                    }
                } else {
                    Slog.i(TAG, "Expected package " + info.packageName
                            + " but restore manifest claims " + manifestPackage);
                    Bundle monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra(null,
                            EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName);
                    monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra(
                            monitoringExtras,
                            EXTRA_LOG_MANIFEST_PACKAGE_NAME, manifestPackage);
                    mMonitor = BackupManagerMonitorUtils.monitorEvent(
                            mMonitor,
                            LOG_EVENT_ID_EXPECTED_DIFFERENT_PACKAGE,
                            null,
                            LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                            monitoringExtras);
                }
            } else {
                Slog.i(TAG, "Unknown restore manifest version " + version
                        + " for package " + info.packageName);
                Bundle monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra(null,
                        EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName);
                monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra(monitoringExtras,
                        EXTRA_LOG_EVENT_PACKAGE_VERSION, version);
                mMonitor = BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_UNKNOWN_VERSION,
                        null,
                        LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);

            }
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Corrupt restore manifest for package " + info.packageName);
            mMonitor = BackupManagerMonitorUtils.monitorEvent(
                    mMonitor,
                    BackupManagerMonitor.LOG_EVENT_ID_CORRUPT_MANIFEST,
                    null,
                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    BackupManagerMonitorUtils.putMonitoringExtra(null, EXTRA_LOG_EVENT_PACKAGE_NAME,
                            info.packageName));
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, e.getMessage());
        }

        return null;
    }

    /**
     * Chooses restore policy.
     *
     * @param packageManager - PackageManager instance.
     * @param allowApks - allow restore set to include apks.
     * @param info - file metadata.
     * @param signatures - array of signatures parsed from backup file.
     * @param userId - ID of the user for which restore is performed.
     * @return a restore policy constant.
     */
    public RestorePolicy chooseRestorePolicy(PackageManager packageManager,
            boolean allowApks, FileMetadata info, Signature[] signatures,
            PackageManagerInternal pmi, int userId) {
        if (signatures == null) {
            return RestorePolicy.IGNORE;
        }

        RestorePolicy policy = RestorePolicy.IGNORE;

        // Okay, got the manifest info we need...
        try {
            PackageInfo pkgInfo = packageManager.getPackageInfoAsUser(
                    info.packageName, PackageManager.GET_SIGNING_CERTIFICATES, userId);
            // Fall through to IGNORE if the app explicitly disallows backup
            final int flags = pkgInfo.applicationInfo.flags;
            if ((flags & ApplicationInfo.FLAG_ALLOW_BACKUP) != 0) {
                // Restore system-uid-space packages only if they have
                // defined a custom backup agent
                if (!UserHandle.isCore(pkgInfo.applicationInfo.uid)
                        || (pkgInfo.applicationInfo.backupAgentName != null)) {
                    // Verify signatures against any installed version; if they
                    // don't match, then we fall though and ignore the data.  The
                    // signatureMatch() method explicitly ignores the signature
                    // check for packages installed on the system partition, because
                    // such packages are signed with the platform cert instead of
                    // the app developer's cert, so they're different on every
                    // device.
                    if (AppBackupUtils.signaturesMatch(signatures, pkgInfo, pmi)) {
                        if ((pkgInfo.applicationInfo.flags
                                & ApplicationInfo.FLAG_RESTORE_ANY_VERSION) != 0) {
                            Slog.i(TAG, "Package has restoreAnyVersion; taking data");
                            mMonitor = BackupManagerMonitorUtils.monitorEvent(
                                    mMonitor,
                                    LOG_EVENT_ID_RESTORE_ANY_VERSION,
                                    pkgInfo,
                                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                    null);
                            policy = RestorePolicy.ACCEPT;
                        } else if (pkgInfo.getLongVersionCode() >= info.version) {
                            Slog.i(TAG, "Sig + version match; taking data");
                            policy = RestorePolicy.ACCEPT;
                            mMonitor = BackupManagerMonitorUtils.monitorEvent(
                                    mMonitor,
                                    LOG_EVENT_ID_VERSIONS_MATCH,
                                    pkgInfo,
                                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                    null);
                        } else {
                            // The data is from a newer version of the app than
                            // is presently installed.  That means we can only
                            // use it if the matching apk is also supplied.
                            if (allowApks) {
                                Slog.i(TAG, "Data version " + info.version
                                        + " is newer than installed "
                                        + "version "
                                        + pkgInfo.getLongVersionCode()
                                        + " - requiring apk");
                                policy = RestorePolicy.ACCEPT_IF_APK;
                            } else {
                                Slog.i(TAG, "Data requires newer version "
                                        + info.version + "; ignoring");
                                mMonitor = BackupManagerMonitorUtils
                                        .monitorEvent(mMonitor,
                                                LOG_EVENT_ID_VERSION_OF_BACKUP_OLDER,
                                                pkgInfo,
                                                LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                                BackupManagerMonitorUtils
                                                        .putMonitoringExtra(
                                                                null,
                                                                EXTRA_LOG_OLD_VERSION,
                                                                info.version));

                                policy = RestorePolicy.IGNORE;
                            }
                        }
                    } else {
                        Slog.w(TAG, "Restore manifest signatures do not match "
                                + "installed application for "
                                + info.packageName);
                        mMonitor = BackupManagerMonitorUtils.monitorEvent(
                                mMonitor,
                                LOG_EVENT_ID_FULL_RESTORE_SIGNATURE_MISMATCH,
                                pkgInfo,
                                LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                                null);
                    }
                } else {
                    Slog.w(TAG, "Package " + info.packageName
                            + " is system level with no agent");
                    mMonitor = BackupManagerMonitorUtils.monitorEvent(
                            mMonitor,
                            LOG_EVENT_ID_SYSTEM_APP_NO_AGENT,
                            pkgInfo,
                            LOG_EVENT_CATEGORY_AGENT,
                            null);
                }
            } else {
                if (DEBUG) {
                    Slog.i(TAG,
                            "Restore manifest from " + info.packageName + " but allowBackup=false");
                }
                mMonitor = BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        LOG_EVENT_ID_FULL_RESTORE_ALLOW_BACKUP_FALSE,
                        pkgInfo,
                        LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        null);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // Okay, the target app isn't installed.  We can process
            // the restore properly only if the dataset provides the
            // apk file and we can successfully install it.
            if (allowApks) {
                if (DEBUG) {
                    Slog.i(TAG, "Package " + info.packageName
                            + " not installed; requiring apk in dataset");
                }
                policy = RestorePolicy.ACCEPT_IF_APK;
            } else {
                policy = RestorePolicy.IGNORE;
            }
            Bundle monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra(
                    null,
                    EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName);
            monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra(
                    monitoringExtras,
                    EXTRA_LOG_POLICY_ALLOW_APKS, allowApks);
            mMonitor = BackupManagerMonitorUtils.monitorEvent(
                    mMonitor,
                    LOG_EVENT_ID_APK_NOT_INSTALLED,
                    null,
                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    monitoringExtras);
        }

        if (policy == RestorePolicy.ACCEPT_IF_APK && !info.hasApk) {
            Slog.i(TAG, "Cannot restore package " + info.packageName
                    + " without the matching .apk");
            mMonitor = BackupManagerMonitorUtils.monitorEvent(
                    mMonitor,
                    LOG_EVENT_ID_CANNOT_RESTORE_WITHOUT_APK,
                    null,
                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    BackupManagerMonitorUtils.putMonitoringExtra(null,
                            EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName));
        }

        return policy;
    }

    // Given an actual file content size, consume the post-content padding mandated
    // by the tar format.
    public void skipTarPadding(long size) throws IOException {
        long partial = (size + 512) % 512;
        if (partial > 0) {
            final int needed = 512 - (int) partial;
            if (MORE_DEBUG) {
                Slog.i(TAG, "Skipping tar padding: " + needed + " bytes");
            }
            byte[] buffer = new byte[needed];
            if (readExactly(mInputStream, buffer, 0, needed) == needed) {
                mBytesReadListener.onBytesRead(needed);
            } else {
                throw new IOException("Unexpected EOF in padding");
            }
        }
    }

    /**
     * Read a widget metadata file, returning the restored blob.
     */
    public void readMetadata(FileMetadata info) throws IOException {
        // Fail on suspiciously large widget dump files
        if (info.size > 64 * 1024) {
            throw new IOException("Metadata too big; corrupt? size=" + info.size);
        }

        byte[] buffer = new byte[(int) info.size];
        if (readExactly(mInputStream, buffer, 0, (int) info.size) == info.size) {
            mBytesReadListener.onBytesRead(info.size);
        } else {
            throw new IOException("Unexpected EOF in widget data");
        }

        String[] str = new String[1];
        int offset = extractLine(buffer, 0, str);
        int version = Integer.parseInt(str[0]);
        if (version == BACKUP_MANIFEST_VERSION) {
            offset = extractLine(buffer, offset, str);
            final String pkg = str[0];
            if (info.packageName.equals(pkg)) {
                // Data checks out -- the rest of the buffer is a concatenation of
                // binary blobs as described in the comment at writeAppWidgetData()
                ByteArrayInputStream bin = new ByteArrayInputStream(buffer,
                        offset, buffer.length - offset);
                DataInputStream in = new DataInputStream(bin);
                while (bin.available() > 0) {
                    int token = in.readInt();
                    int size = in.readInt();
                    if (size > 64 * 1024) {
                        throw new IOException("Datum " + Integer.toHexString(token)
                                + " too big; corrupt? size=" + info.size);
                    }
                    switch (token) {
                        case BACKUP_WIDGET_METADATA_TOKEN: {
                            if (MORE_DEBUG) {
                                Slog.i(TAG, "Got widget metadata for " + info.packageName);
                            }
                            mWidgetData = new byte[size];
                            in.read(mWidgetData);
                            break;
                        }
                        default: {
                            if (DEBUG) {
                                Slog.i(TAG, "Ignoring metadata blob " + Integer.toHexString(token)
                                        + " for " + info.packageName);
                            }
                            in.skipBytes(size);
                            break;
                        }
                    }
                }
            } else {
                Slog.w(TAG,
                        "Metadata mismatch: package " + info.packageName + " but widget data for "
                                + pkg);

                Bundle monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra(null,
                        EXTRA_LOG_EVENT_PACKAGE_NAME, info.packageName);
                monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra(monitoringExtras,
                        BackupManagerMonitor.EXTRA_LOG_WIDGET_PACKAGE_NAME, pkg);
                mMonitor = BackupManagerMonitorUtils.monitorEvent(
                        mMonitor,
                        BackupManagerMonitor.LOG_EVENT_ID_WIDGET_METADATA_MISMATCH,
                        null,
                        LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                        monitoringExtras);
            }
        } else {
            Slog.w(TAG, "Unsupported metadata version " + version);

            Bundle monitoringExtras = BackupManagerMonitorUtils
                    .putMonitoringExtra(null, EXTRA_LOG_EVENT_PACKAGE_NAME,
                            info.packageName);
            monitoringExtras = BackupManagerMonitorUtils.putMonitoringExtra(monitoringExtras,
                    EXTRA_LOG_EVENT_PACKAGE_VERSION, version);
            mMonitor = BackupManagerMonitorUtils.monitorEvent(
                    mMonitor,
                    BackupManagerMonitor.LOG_EVENT_ID_WIDGET_UNKNOWN_VERSION,
                    null,
                    LOG_EVENT_CATEGORY_BACKUP_MANAGER_POLICY,
                    monitoringExtras);
        }
    }

    /**
     * Builds a line from a byte buffer starting at 'offset'.
     *
     * @param buffer - where to read a line from.
     * @param offset - offset in buffer to read a line from.
     * @param outStr - an output parameter, the result will be put in outStr.
     * @return the index of the next unconsumed data in the buffer.
     * @throws IOException in case of an error.
     */
    private static int extractLine(byte[] buffer, int offset, String[] outStr) throws IOException {
        final int end = buffer.length;
        if (offset >= end) {
            throw new IOException("Incomplete data");
        }

        int pos;
        for (pos = offset; pos < end; pos++) {
            byte c = buffer[pos];
            // at LF we declare end of line, and return the next char as the
            // starting point for the next time through
            if (c == '\n') {
                break;
            }
        }
        outStr[0] = new String(buffer, offset, pos - offset);
        pos++;  // may be pointing an extra byte past the end but that's okay
        return pos;
    }

    private boolean readTarHeader(byte[] block) throws IOException {
        final int got = readExactly(mInputStream, block, 0, 512);
        if (got == 0) {
            return false;     // Clean EOF
        }
        if (got < 512) {
            throw new IOException("Unable to read full block header");
        }
        mBytesReadListener.onBytesRead(512);
        return true;
    }

    // overwrites 'info' fields based on the pax extended header
    private boolean readPaxExtendedHeader(FileMetadata info)
            throws IOException {
        // We should never see a pax extended header larger than this
        if (info.size > 32 * 1024) {
            Slog.w(TAG, "Suspiciously large pax header size " + info.size + " - aborting");
            throw new IOException("Sanity failure: pax header size " + info.size);
        }

        // read whole blocks, not just the content size
        int numBlocks = (int) ((info.size + 511) >> 9);
        byte[] data = new byte[numBlocks * 512];
        if (readExactly(mInputStream, data, 0, data.length) < data.length) {
            throw new IOException("Unable to read full pax header");
        }
        mBytesReadListener.onBytesRead(data.length);

        final int contentSize = (int) info.size;
        int offset = 0;
        do {
            // extract the line at 'offset'
            int eol = offset + 1;
            while (eol < contentSize && data[eol] != ' ') {
                eol++;
            }
            if (eol >= contentSize) {
                // error: we just hit EOD looking for the end of the size field
                throw new IOException("Invalid pax data");
            }
            // eol points to the space between the count and the key
            int linelen = (int) extractRadix(data, offset, eol - offset, 10);
            int key = eol + 1;  // start of key=value
            eol = offset + linelen - 1; // trailing LF
            int value;
            for (value = key + 1; data[value] != '=' && value <= eol; value++) {
                ;
            }
            if (value > eol) {
                throw new IOException("Invalid pax declaration");
            }

            // pax requires that key/value strings be in UTF-8
            String keyStr = new String(data, key, value - key, "UTF-8");
            // -1 to strip the trailing LF
            String valStr = new String(data, value + 1, eol - value - 1, "UTF-8");

            if ("path".equals(keyStr)) {
                info.path = valStr;
            } else if ("size".equals(keyStr)) {
                info.size = Long.parseLong(valStr);
            } else {
                if (DEBUG) {
                    Slog.i(TAG, "Unhandled pax key: " + key);
                }
            }

            offset += linelen;
        } while (offset < contentSize);

        return true;
    }

    private static long extractRadix(byte[] data, int offset, int maxChars, int radix)
            throws IOException {
        long value = 0;
        final int end = offset + maxChars;
        for (int i = offset; i < end; i++) {
            final byte b = data[i];
            // Numeric fields in tar can terminate with either NUL or SPC
            if (b == 0 || b == ' ') {
                break;
            }
            if (b < '0' || b > ('0' + radix - 1)) {
                throw new IOException("Invalid number in header: '" + (char) b
                        + "' for radix " + radix);
            }
            value = radix * value + (b - '0');
        }
        return value;
    }

    private static String extractString(byte[] data, int offset, int maxChars) throws IOException {
        final int end = offset + maxChars;
        int eos = offset;
        // tar string fields terminate early with a NUL
        while (eos < end && data[eos] != 0) {
            eos++;
        }
        return new String(data, offset, eos - offset, "US-ASCII");
    }

    private static void hexLog(byte[] block) {
        int offset = 0;
        int todo = block.length;
        StringBuilder buf = new StringBuilder(64);
        while (todo > 0) {
            buf.append(String.format("%04x   ", offset));
            int numThisLine = (todo > 16) ? 16 : todo;
            for (int i = 0; i < numThisLine; i++) {
                buf.append(String.format("%02x ", block[offset + i]));
            }
            Slog.i("hexdump", buf.toString());
            buf.setLength(0);
            todo -= numThisLine;
            offset += numThisLine;
        }
    }

    public IBackupManagerMonitor getMonitor() {
        return mMonitor;
    }

    public byte[] getWidgetData() {
        return mWidgetData;
    }
}
