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

package com.android.server.backup.restore;

import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.PackageInstallObserver;
import android.app.backup.FullBackup;
import android.app.backup.IFullBackupRestoreObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageDeleteObserver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.util.Slog;

import com.android.server.backup.FileMetadata;
import com.android.server.backup.KeyValueAdbRestoreEngine;
import com.android.server.backup.PackageManagerBackupAgent;
import com.android.server.backup.RefactoredBackupManagerService;
import com.android.server.backup.fullbackup.FullBackupObbConnection;
import com.android.server.backup.utils.AppBackupUtils;
import com.android.server.backup.utils.PasswordUtils;
import com.android.server.backup.utils.TarBackupReader;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.InflaterInputStream;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class PerformAdbRestoreTask implements Runnable {

    private RefactoredBackupManagerService backupManagerService;
    ParcelFileDescriptor mInputFile;
    String mCurrentPassword;
    String mDecryptPassword;
    IFullBackupRestoreObserver mObserver;
    AtomicBoolean mLatchObject;
    IBackupAgent mAgent;
    PackageManagerBackupAgent mPackageManagerBackupAgent;
    String mAgentPackage;
    ApplicationInfo mTargetApp;
    FullBackupObbConnection mObbConnection = null;
    ParcelFileDescriptor[] mPipes = null;
    byte[] mWidgetData = null;

    long mBytes;

    // Runner that can be placed on a separate thread to do in-process invocation
    // of the "restore finished" API asynchronously.  Used by adb restore.
    class RestoreFinishedRunnable implements Runnable {

        final IBackupAgent mAgent;
        final int mToken;

        RestoreFinishedRunnable(IBackupAgent agent, int token) {
            mAgent = agent;
            mToken = token;
        }

        @Override
        public void run() {
            try {
                mAgent.doRestoreFinished(mToken, backupManagerService.getBackupManagerBinder());
            } catch (RemoteException e) {
                // never happens; this is used only for local binder calls
            }
        }
    }

    // possible handling states for a given package in the restore dataset
    final HashMap<String, RestorePolicy> mPackagePolicies
            = new HashMap<>();

    // installer package names for each encountered app, derived from the manifests
    final HashMap<String, String> mPackageInstallers = new HashMap<>();

    // Signatures for a given package found in its manifest file
    final HashMap<String, Signature[]> mManifestSignatures
            = new HashMap<>();

    // Packages we've already wiped data on when restoring their first file
    final HashSet<String> mClearedPackages = new HashSet<>();

    public PerformAdbRestoreTask(RefactoredBackupManagerService backupManagerService,
            ParcelFileDescriptor fd, String curPassword, String decryptPassword,
            IFullBackupRestoreObserver observer, AtomicBoolean latch) {
        this.backupManagerService = backupManagerService;
        mInputFile = fd;
        mCurrentPassword = curPassword;
        mDecryptPassword = decryptPassword;
        mObserver = observer;
        mLatchObject = latch;
        mAgent = null;
        mPackageManagerBackupAgent = new PackageManagerBackupAgent(
                backupManagerService.getPackageManager());
        mAgentPackage = null;
        mTargetApp = null;
        mObbConnection = new FullBackupObbConnection(backupManagerService);

        // Which packages we've already wiped data on.  We prepopulate this
        // with a whitelist of packages known to be unclearable.
        mClearedPackages.add("android");
        mClearedPackages.add(RefactoredBackupManagerService.SETTINGS_PACKAGE);
    }

    class RestoreFileRunnable implements Runnable {

        IBackupAgent mAgent;
        FileMetadata mInfo;
        ParcelFileDescriptor mSocket;
        int mToken;

        RestoreFileRunnable(IBackupAgent agent, FileMetadata info,
                ParcelFileDescriptor socket, int token) throws IOException {
            mAgent = agent;
            mInfo = info;
            mToken = token;

            // This class is used strictly for process-local binder invocations.  The
            // semantics of ParcelFileDescriptor differ in this case; in particular, we
            // do not automatically get a 'dup'ed descriptor that we can can continue
            // to use asynchronously from the caller.  So, we make sure to dup it ourselves
            // before proceeding to do the restore.
            mSocket = ParcelFileDescriptor.dup(socket.getFileDescriptor());
        }

        @Override
        public void run() {
            try {
                mAgent.doRestoreFile(mSocket, mInfo.size, mInfo.type,
                        mInfo.domain, mInfo.path, mInfo.mode, mInfo.mtime,
                        mToken, backupManagerService.getBackupManagerBinder());
            } catch (RemoteException e) {
                // never happens; this is used strictly for local binder calls
            }
        }
    }

    @Override
    public void run() {
        Slog.i(RefactoredBackupManagerService.TAG, "--- Performing full-dataset restore ---");
        mObbConnection.establish();
        sendStartRestore();

        // Are we able to restore shared-storage data?
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            mPackagePolicies.put(RefactoredBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE,
                    RestorePolicy.ACCEPT);
        }

        FileInputStream rawInStream = null;
        DataInputStream rawDataIn = null;
        try {
            if (!backupManagerService.backupPasswordMatches(mCurrentPassword)) {
                if (RefactoredBackupManagerService.DEBUG) {
                    Slog.w(RefactoredBackupManagerService.TAG,
                            "Backup password mismatch; aborting");
                }
                return;
            }

            mBytes = 0;
            byte[] buffer = new byte[32 * 1024];
            rawInStream = new FileInputStream(mInputFile.getFileDescriptor());
            rawDataIn = new DataInputStream(rawInStream);

            // First, parse out the unencrypted/uncompressed header
            boolean compressed = false;
            InputStream preCompressStream = rawInStream;
            final InputStream in;

            boolean okay = false;
            final int headerLen = RefactoredBackupManagerService.BACKUP_FILE_HEADER_MAGIC.length();
            byte[] streamHeader = new byte[headerLen];
            rawDataIn.readFully(streamHeader);
            byte[] magicBytes = RefactoredBackupManagerService.BACKUP_FILE_HEADER_MAGIC.getBytes(
                    "UTF-8");
            if (Arrays.equals(magicBytes, streamHeader)) {
                // okay, header looks good.  now parse out the rest of the fields.
                String s = readHeaderLine(rawInStream);
                final int archiveVersion = Integer.parseInt(s);
                if (archiveVersion <= RefactoredBackupManagerService.BACKUP_FILE_VERSION) {
                    // okay, it's a version we recognize.  if it's version 1, we may need
                    // to try two different PBKDF2 regimes to compare checksums.
                    final boolean pbkdf2Fallback = (archiveVersion == 1);

                    s = readHeaderLine(rawInStream);
                    compressed = (Integer.parseInt(s) != 0);
                    s = readHeaderLine(rawInStream);
                    if (s.equals("none")) {
                        // no more header to parse; we're good to go
                        okay = true;
                    } else if (mDecryptPassword != null && mDecryptPassword.length() > 0) {
                        preCompressStream = decodeAesHeaderAndInitialize(s, pbkdf2Fallback,
                                rawInStream);
                        if (preCompressStream != null) {
                            okay = true;
                        }
                    } else {
                        Slog.w(RefactoredBackupManagerService.TAG,
                                "Archive is encrypted but no password given");
                    }
                } else {
                    Slog.w(RefactoredBackupManagerService.TAG, "Wrong header version: " + s);
                }
            } else {
                Slog.w(RefactoredBackupManagerService.TAG, "Didn't read the right header magic");
            }

            if (!okay) {
                Slog.w(RefactoredBackupManagerService.TAG, "Invalid restore data; aborting.");
                return;
            }

            // okay, use the right stream layer based on compression
            in = (compressed) ? new InflaterInputStream(preCompressStream) : preCompressStream;

            boolean didRestore;
            do {
                didRestore = restoreOneFile(in, buffer);
            } while (didRestore);

            if (RefactoredBackupManagerService.MORE_DEBUG) {
                Slog.v(RefactoredBackupManagerService.TAG,
                        "Done consuming input tarfile, total bytes=" + mBytes);
            }
        } catch (IOException e) {
            Slog.e(RefactoredBackupManagerService.TAG, "Unable to read restore input");
        } finally {
            tearDownPipes();
            tearDownAgent(mTargetApp, true);

            try {
                if (rawDataIn != null) {
                    rawDataIn.close();
                }
                if (rawInStream != null) {
                    rawInStream.close();
                }
                mInputFile.close();
            } catch (IOException e) {
                Slog.w(RefactoredBackupManagerService.TAG, "Close of restore data pipe threw", e);
                /* nothing we can do about this */
            }
            synchronized (mLatchObject) {
                mLatchObject.set(true);
                mLatchObject.notifyAll();
            }
            mObbConnection.tearDown();
            sendEndRestore();
            Slog.d(RefactoredBackupManagerService.TAG, "Full restore pass complete.");
            backupManagerService.getWakelock().release();
        }
    }

    String readHeaderLine(InputStream in) throws IOException {
        int c;
        StringBuilder buffer = new StringBuilder(80);
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                break;   // consume and discard the newlines
            }
            buffer.append((char) c);
        }
        return buffer.toString();
    }

    InputStream attemptMasterKeyDecryption(String algorithm, byte[] userSalt, byte[] ckSalt,
            int rounds, String userIvHex, String masterKeyBlobHex, InputStream rawInStream,
            boolean doLog) {
        InputStream result = null;

        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKey userKey = PasswordUtils
                    .buildPasswordKey(algorithm, mDecryptPassword, userSalt,
                            rounds);
            byte[] IV = PasswordUtils.hexToByteArray(userIvHex);
            IvParameterSpec ivSpec = new IvParameterSpec(IV);
            c.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(userKey.getEncoded(), "AES"),
                    ivSpec);
            byte[] mkCipher = PasswordUtils.hexToByteArray(masterKeyBlobHex);
            byte[] mkBlob = c.doFinal(mkCipher);

            // first, the master key IV
            int offset = 0;
            int len = mkBlob[offset++];
            IV = Arrays.copyOfRange(mkBlob, offset, offset + len);
            offset += len;
            // then the master key itself
            len = mkBlob[offset++];
            byte[] mk = Arrays.copyOfRange(mkBlob,
                    offset, offset + len);
            offset += len;
            // and finally the master key checksum hash
            len = mkBlob[offset++];
            byte[] mkChecksum = Arrays.copyOfRange(mkBlob,
                    offset, offset + len);

            // now validate the decrypted master key against the checksum
            byte[] calculatedCk = PasswordUtils.makeKeyChecksum(algorithm, mk, ckSalt,
                    rounds);
            if (Arrays.equals(calculatedCk, mkChecksum)) {
                ivSpec = new IvParameterSpec(IV);
                c.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(mk, "AES"),
                        ivSpec);
                // Only if all of the above worked properly will 'result' be assigned
                result = new CipherInputStream(rawInStream, c);
            } else if (doLog) {
                Slog.w(RefactoredBackupManagerService.TAG, "Incorrect password");
            }
        } catch (InvalidAlgorithmParameterException e) {
            if (doLog) {
                Slog.e(RefactoredBackupManagerService.TAG, "Needed parameter spec unavailable!", e);
            }
        } catch (BadPaddingException e) {
            // This case frequently occurs when the wrong password is used to decrypt
            // the master key.  Use the identical "incorrect password" log text as is
            // used in the checksum failure log in order to avoid providing additional
            // information to an attacker.
            if (doLog) {
                Slog.w(RefactoredBackupManagerService.TAG, "Incorrect password");
            }
        } catch (IllegalBlockSizeException e) {
            if (doLog) {
                Slog.w(RefactoredBackupManagerService.TAG, "Invalid block size in master key");
            }
        } catch (NoSuchAlgorithmException e) {
            if (doLog) {
                Slog.e(RefactoredBackupManagerService.TAG,
                        "Needed decryption algorithm unavailable!");
            }
        } catch (NoSuchPaddingException e) {
            if (doLog) {
                Slog.e(RefactoredBackupManagerService.TAG, "Needed padding mechanism unavailable!");
            }
        } catch (InvalidKeyException e) {
            if (doLog) {
                Slog.w(RefactoredBackupManagerService.TAG, "Illegal password; aborting");
            }
        }

        return result;
    }

    InputStream decodeAesHeaderAndInitialize(String encryptionName, boolean pbkdf2Fallback,
            InputStream rawInStream) {
        InputStream result = null;
        try {
            if (encryptionName.equals(PasswordUtils.ENCRYPTION_ALGORITHM_NAME)) {

                String userSaltHex = readHeaderLine(rawInStream); // 5
                byte[] userSalt = PasswordUtils.hexToByteArray(userSaltHex);

                String ckSaltHex = readHeaderLine(rawInStream); // 6
                byte[] ckSalt = PasswordUtils.hexToByteArray(ckSaltHex);

                int rounds = Integer.parseInt(readHeaderLine(rawInStream)); // 7
                String userIvHex = readHeaderLine(rawInStream); // 8

                String masterKeyBlobHex = readHeaderLine(rawInStream); // 9

                // decrypt the master key blob
                result = attemptMasterKeyDecryption(RefactoredBackupManagerService.PBKDF_CURRENT,
                        userSalt, ckSalt,
                        rounds, userIvHex, masterKeyBlobHex, rawInStream, false);
                if (result == null && pbkdf2Fallback) {
                    result = attemptMasterKeyDecryption(
                            RefactoredBackupManagerService.PBKDF_FALLBACK, userSalt, ckSalt,
                            rounds, userIvHex, masterKeyBlobHex, rawInStream, true);
                }
            } else {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "Unsupported encryption method: " + encryptionName);
            }
        } catch (NumberFormatException e) {
            Slog.w(RefactoredBackupManagerService.TAG, "Can't parse restore data header");
        } catch (IOException e) {
            Slog.w(RefactoredBackupManagerService.TAG, "Can't read input header");
        }

        return result;
    }

    boolean restoreOneFile(InputStream instream, byte[] buffer) {
        TarBackupReader tarBackupReader = new TarBackupReader(instream,
                new TarBackupReader.BytesReadListener() {
                    @Override
                    public void onBytesRead(long bytesRead) {
                        mBytes += bytesRead;
                    }
                }, null /* monitor */);
        FileMetadata info;
        try {
            info = tarBackupReader.readTarHeaders();
            if (info != null) {
                if (RefactoredBackupManagerService.MORE_DEBUG) {
                    info.dump();
                }

                final String pkg = info.packageName;
                if (!pkg.equals(mAgentPackage)) {
                    // okay, change in package; set up our various
                    // bookkeeping if we haven't seen it yet
                    if (!mPackagePolicies.containsKey(pkg)) {
                        mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                    }

                    // Clean up the previous agent relationship if necessary,
                    // and let the observer know we're considering a new app.
                    if (mAgent != null) {
                        if (RefactoredBackupManagerService.DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "Saw new package; finalizing old one");
                        }
                        // Now we're really done
                        tearDownPipes();
                        tearDownAgent(mTargetApp, true);
                        mTargetApp = null;
                        mAgentPackage = null;
                    }
                }

                if (info.path.equals(RefactoredBackupManagerService.BACKUP_MANIFEST_FILENAME)) {
                    RestorePolicy appManifest = tarBackupReader.readAppManifest(
                            backupManagerService.getPackageManager(), true /* allowApks */,
                            mManifestSignatures, info);
                    mPackagePolicies.put(pkg, appManifest);
                    mPackageInstallers.put(pkg, info.installerPackageName);
                    // We've read only the manifest content itself at this point,
                    // so consume the footer before looping around to the next
                    // input file
                    tarBackupReader.skipTarPadding(info.size);
                    sendOnRestorePackage(pkg);
                } else if (info.path.equals(
                        RefactoredBackupManagerService.BACKUP_METADATA_FILENAME)) {
                    // Metadata blobs!
                    tarBackupReader.readMetadata(info);

                    // The following only exist because we want to keep refactoring as safe as
                    // possible, without changing too much.
                    // TODO: Refactor, so that there are no funny things like this.
                    // This is read during TarBackupReader.readMetadata().
                    mWidgetData = tarBackupReader.getWidgetData();

                    tarBackupReader.skipTarPadding(info.size);
                } else {
                    // Non-manifest, so it's actual file data.  Is this a package
                    // we're ignoring?
                    boolean okay = true;
                    RestorePolicy policy = mPackagePolicies.get(pkg);
                    switch (policy) {
                        case IGNORE:
                            okay = false;
                            break;

                        case ACCEPT_IF_APK:
                            // If we're in accept-if-apk state, then the first file we
                            // see MUST be the apk.
                            if (info.domain.equals(FullBackup.APK_TREE_TOKEN)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "APK file; installing");
                                }
                                // Try to install the app.
                                String installerName = mPackageInstallers.get(pkg);
                                okay = installApk(info, installerName, instream);
                                // good to go; promote to ACCEPT
                                mPackagePolicies.put(pkg, (okay)
                                        ? RestorePolicy.ACCEPT
                                        : RestorePolicy.IGNORE);
                                // At this point we've consumed this file entry
                                // ourselves, so just strip the tar footer and
                                // go on to the next file in the input stream
                                tarBackupReader.skipTarPadding(info.size);
                                return true;
                            } else {
                                // File data before (or without) the apk.  We can't
                                // handle it coherently in this case so ignore it.
                                mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                                okay = false;
                            }
                            break;

                        case ACCEPT:
                            if (info.domain.equals(FullBackup.APK_TREE_TOKEN)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "apk present but ACCEPT");
                                }
                                // we can take the data without the apk, so we
                                // *want* to do so.  skip the apk by declaring this
                                // one file not-okay without changing the restore
                                // policy for the package.
                                okay = false;
                            }
                            break;

                        default:
                            // Something has gone dreadfully wrong when determining
                            // the restore policy from the manifest.  Ignore the
                            // rest of this package's data.
                            Slog.e(RefactoredBackupManagerService.TAG,
                                    "Invalid policy from manifest");
                            okay = false;
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                            break;
                    }

                    // The path needs to be canonical
                    if (info.path.contains("..") || info.path.contains("//")) {
                        if (RefactoredBackupManagerService.MORE_DEBUG) {
                            Slog.w(RefactoredBackupManagerService.TAG,
                                    "Dropping invalid path " + info.path);
                        }
                        okay = false;
                    }

                    // If the policy is satisfied, go ahead and set up to pipe the
                    // data to the agent.
                    if (RefactoredBackupManagerService.DEBUG && okay && mAgent != null) {
                        Slog.i(RefactoredBackupManagerService.TAG,
                                "Reusing existing agent instance");
                    }
                    if (okay && mAgent == null) {
                        if (RefactoredBackupManagerService.DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "Need to launch agent for " + pkg);
                        }

                        try {
                            mTargetApp =
                                    backupManagerService.getPackageManager().getApplicationInfo(
                                            pkg, 0);

                            // If we haven't sent any data to this app yet, we probably
                            // need to clear it first.  Check that.
                            if (!mClearedPackages.contains(pkg)) {
                                // apps with their own backup agents are
                                // responsible for coherently managing a full
                                // restore.
                                if (mTargetApp.backupAgentName == null) {
                                    if (RefactoredBackupManagerService.DEBUG) {
                                        Slog.d(RefactoredBackupManagerService.TAG,
                                                "Clearing app data preparatory to full restore");
                                    }
                                    backupManagerService.clearApplicationDataSynchronous(pkg);
                                } else {
                                    if (RefactoredBackupManagerService.DEBUG) {
                                        Slog.d(RefactoredBackupManagerService.TAG, "backup agent ("
                                                + mTargetApp.backupAgentName + ") => no clear");
                                    }
                                }
                                mClearedPackages.add(pkg);
                            } else {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "We've initialized this app already; no clear "
                                                    + "required");
                                }
                            }

                            // All set; now set up the IPC and launch the agent
                            setUpPipes();
                            mAgent = backupManagerService.bindToAgentSynchronous(mTargetApp,
                                    ApplicationThreadConstants.BACKUP_MODE_RESTORE_FULL);
                            mAgentPackage = pkg;
                        } catch (IOException e) {
                            // fall through to error handling
                        } catch (NameNotFoundException e) {
                            // fall through to error handling
                        }

                        if (mAgent == null) {
                            if (RefactoredBackupManagerService.DEBUG) {
                                Slog.d(RefactoredBackupManagerService.TAG,
                                        "Unable to create agent for " + pkg);
                            }
                            okay = false;
                            tearDownPipes();
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                        }
                    }

                    // Sanity check: make sure we never give data to the wrong app.  This
                    // should never happen but a little paranoia here won't go amiss.
                    if (okay && !pkg.equals(mAgentPackage)) {
                        Slog.e(RefactoredBackupManagerService.TAG, "Restoring data for " + pkg
                                + " but agent is for " + mAgentPackage);
                        okay = false;
                    }

                    // At this point we have an agent ready to handle the full
                    // restore data as well as a pipe for sending data to
                    // that agent.  Tell the agent to start reading from the
                    // pipe.
                    if (okay) {
                        boolean agentSuccess = true;
                        long toCopy = info.size;
                        final int token = backupManagerService.generateRandomIntegerToken();
                        try {
                            backupManagerService
                                    .prepareOperationTimeout(token,
                                            RefactoredBackupManagerService.TIMEOUT_RESTORE_INTERVAL,
                                            null,
                                            RefactoredBackupManagerService.OP_TYPE_RESTORE_WAIT);
                            if (FullBackup.OBB_TREE_TOKEN.equals(info.domain)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "Restoring OBB file for " + pkg
                                                    + " : " + info.path);
                                }
                                mObbConnection.restoreObbFile(pkg, mPipes[0],
                                        info.size, info.type, info.path, info.mode,
                                        info.mtime, token,
                                        backupManagerService.getBackupManagerBinder());
                            } else if (FullBackup.KEY_VALUE_DATA_TOKEN.equals(info.domain)) {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "Restoring key-value file for " + pkg
                                                    + " : " + info.path);
                                }
                                KeyValueAdbRestoreEngine restoreEngine =
                                        new KeyValueAdbRestoreEngine(
                                                backupManagerService,
                                                backupManagerService.getDataDir(), info, mPipes[0],
                                                mAgent, token);
                                new Thread(restoreEngine, "restore-key-value-runner").start();
                            } else {
                                if (RefactoredBackupManagerService.DEBUG) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "Invoking agent to restore file "
                                                    + info.path);
                                }
                                // fire up the app's agent listening on the socket.  If
                                // the agent is running in the system process we can't
                                // just invoke it asynchronously, so we provide a thread
                                // for it here.
                                if (mTargetApp.processName.equals("system")) {
                                    Slog.d(RefactoredBackupManagerService.TAG,
                                            "system process agent - spinning a thread");
                                    RestoreFileRunnable runner = new RestoreFileRunnable(
                                            mAgent, info, mPipes[0], token);
                                    new Thread(runner, "restore-sys-runner").start();
                                } else {
                                    mAgent.doRestoreFile(mPipes[0], info.size, info.type,
                                            info.domain, info.path, info.mode, info.mtime,
                                            token, backupManagerService.getBackupManagerBinder());
                                }
                            }
                        } catch (IOException e) {
                            // couldn't dup the socket for a process-local restore
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "Couldn't establish restore");
                            agentSuccess = false;
                            okay = false;
                        } catch (RemoteException e) {
                            // whoops, remote entity went away.  We'll eat the content
                            // ourselves, then, and not copy it over.
                            Slog.e(RefactoredBackupManagerService.TAG,
                                    "Agent crashed during full restore");
                            agentSuccess = false;
                            okay = false;
                        }

                        // Copy over the data if the agent is still good
                        if (okay) {
                            boolean pipeOkay = true;
                            FileOutputStream pipe = new FileOutputStream(
                                    mPipes[1].getFileDescriptor());
                            while (toCopy > 0) {
                                int toRead = (toCopy > buffer.length)
                                        ? buffer.length : (int) toCopy;
                                int nRead = instream.read(buffer, 0, toRead);
                                if (nRead >= 0) {
                                    mBytes += nRead;
                                }
                                if (nRead <= 0) {
                                    break;
                                }
                                toCopy -= nRead;

                                // send it to the output pipe as long as things
                                // are still good
                                if (pipeOkay) {
                                    try {
                                        pipe.write(buffer, 0, nRead);
                                    } catch (IOException e) {
                                        Slog.e(RefactoredBackupManagerService.TAG,
                                                "Failed to write to restore pipe", e);
                                        pipeOkay = false;
                                    }
                                }
                            }

                            // done sending that file!  Now we just need to consume
                            // the delta from info.size to the end of block.
                            tarBackupReader.skipTarPadding(info.size);

                            // and now that we've sent it all, wait for the remote
                            // side to acknowledge receipt
                            agentSuccess = backupManagerService.waitUntilOperationComplete(token);
                        }

                        // okay, if the remote end failed at any point, deal with
                        // it by ignoring the rest of the restore on it
                        if (!agentSuccess) {
                            if (RefactoredBackupManagerService.DEBUG) {
                                Slog.d(RefactoredBackupManagerService.TAG,
                                        "Agent failure restoring " + pkg + "; now ignoring");
                            }
                            backupManagerService.getBackupHandler().removeMessages(
                                    RefactoredBackupManagerService.MSG_RESTORE_OPERATION_TIMEOUT);
                            tearDownPipes();
                            tearDownAgent(mTargetApp, false);
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                        }
                    }

                    // Problems setting up the agent communication, or an already-
                    // ignored package: skip to the next tar stream entry by
                    // reading and discarding this file.
                    if (!okay) {
                        if (RefactoredBackupManagerService.DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG, "[discarding file content]");
                        }
                        long bytesToConsume = (info.size + 511) & ~511;
                        while (bytesToConsume > 0) {
                            int toRead = (bytesToConsume > buffer.length)
                                    ? buffer.length : (int) bytesToConsume;
                            long nRead = instream.read(buffer, 0, toRead);
                            if (nRead >= 0) {
                                mBytes += nRead;
                            }
                            if (nRead <= 0) {
                                break;
                            }
                            bytesToConsume -= nRead;
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (RefactoredBackupManagerService.DEBUG) {
                Slog.w(RefactoredBackupManagerService.TAG, "io exception on restore socket read",
                        e);
            }
            // treat as EOF
            info = null;
        }

        return (info != null);
    }

    void setUpPipes() throws IOException {
        mPipes = ParcelFileDescriptor.createPipe();
    }

    void tearDownPipes() {
        if (mPipes != null) {
            try {
                mPipes[0].close();
                mPipes[0] = null;
                mPipes[1].close();
                mPipes[1] = null;
            } catch (IOException e) {
                Slog.w(RefactoredBackupManagerService.TAG, "Couldn't close agent pipes", e);
            }
            mPipes = null;
        }
    }

    void tearDownAgent(ApplicationInfo app, boolean doRestoreFinished) {
        if (mAgent != null) {
            try {
                // In the adb restore case, we do restore-finished here
                if (doRestoreFinished) {
                    final int token = backupManagerService.generateRandomIntegerToken();
                    final AdbRestoreFinishedLatch latch = new AdbRestoreFinishedLatch(
                            backupManagerService, token);
                    backupManagerService
                            .prepareOperationTimeout(token,
                                    RefactoredBackupManagerService.TIMEOUT_FULL_BACKUP_INTERVAL,
                                    latch,
                                    RefactoredBackupManagerService.OP_TYPE_RESTORE_WAIT);
                    if (mTargetApp.processName.equals("system")) {
                        if (RefactoredBackupManagerService.MORE_DEBUG) {
                            Slog.d(RefactoredBackupManagerService.TAG,
                                    "system agent - restoreFinished on thread");
                        }
                        Runnable runner = new RestoreFinishedRunnable(mAgent, token);
                        new Thread(runner, "restore-sys-finished-runner").start();
                    } else {
                        mAgent.doRestoreFinished(token,
                                backupManagerService.getBackupManagerBinder());
                    }

                    latch.await();
                }

                backupManagerService.tearDownAgentAndKill(app);
            } catch (RemoteException e) {
                Slog.d(RefactoredBackupManagerService.TAG, "Lost app trying to shut down");
            }
            mAgent = null;
        }
    }

    class RestoreInstallObserver extends PackageInstallObserver {

        final AtomicBoolean mDone = new AtomicBoolean();
        String mPackageName;
        int mResult;

        public void reset() {
            synchronized (mDone) {
                mDone.set(false);
            }
        }

        public void waitForCompletion() {
            synchronized (mDone) {
                while (mDone.get() == false) {
                    try {
                        mDone.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

        int getResult() {
            return mResult;
        }

        @Override
        public void onPackageInstalled(String packageName, int returnCode,
                String msg, Bundle extras) {
            synchronized (mDone) {
                mResult = returnCode;
                mPackageName = packageName;
                mDone.set(true);
                mDone.notifyAll();
            }
        }
    }

    class RestoreDeleteObserver extends IPackageDeleteObserver.Stub {

        final AtomicBoolean mDone = new AtomicBoolean();
        int mResult;

        public void reset() {
            synchronized (mDone) {
                mDone.set(false);
            }
        }

        public void waitForCompletion() {
            synchronized (mDone) {
                while (mDone.get() == false) {
                    try {
                        mDone.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }

        @Override
        public void packageDeleted(String packageName, int returnCode) throws RemoteException {
            synchronized (mDone) {
                mResult = returnCode;
                mDone.set(true);
                mDone.notifyAll();
            }
        }
    }

    final RestoreInstallObserver mInstallObserver = new RestoreInstallObserver();
    final RestoreDeleteObserver mDeleteObserver = new RestoreDeleteObserver();

    boolean installApk(FileMetadata info, String installerPackage, InputStream instream) {
        boolean okay = true;

        if (RefactoredBackupManagerService.DEBUG) {
            Slog.d(RefactoredBackupManagerService.TAG,
                    "Installing from backup: " + info.packageName);
        }

        // The file content is an .apk file.  Copy it out to a staging location and
        // attempt to install it.
        File apkFile = new File(backupManagerService.getDataDir(), info.packageName);
        try {
            FileOutputStream apkStream = new FileOutputStream(apkFile);
            byte[] buffer = new byte[32 * 1024];
            long size = info.size;
            while (size > 0) {
                long toRead = (buffer.length < size) ? buffer.length : size;
                int didRead = instream.read(buffer, 0, (int) toRead);
                if (didRead >= 0) {
                    mBytes += didRead;
                }
                apkStream.write(buffer, 0, didRead);
                size -= didRead;
            }
            apkStream.close();

            // make sure the installer can read it
            apkFile.setReadable(true, false);

            // Now install it
            Uri packageUri = Uri.fromFile(apkFile);
            mInstallObserver.reset();
            backupManagerService.getPackageManager().installPackage(packageUri, mInstallObserver,
                    PackageManager.INSTALL_REPLACE_EXISTING | PackageManager.INSTALL_FROM_ADB,
                    installerPackage);
            mInstallObserver.waitForCompletion();

            if (mInstallObserver.getResult() != PackageManager.INSTALL_SUCCEEDED) {
                // The only time we continue to accept install of data even if the
                // apk install failed is if we had already determined that we could
                // accept the data regardless.
                if (mPackagePolicies.get(info.packageName) != RestorePolicy.ACCEPT) {
                    okay = false;
                }
            } else {
                // Okay, the install succeeded.  Make sure it was the right app.
                boolean uninstall = false;
                if (!mInstallObserver.mPackageName.equals(info.packageName)) {
                    Slog.w(RefactoredBackupManagerService.TAG,
                            "Restore stream claimed to include apk for "
                                    + info.packageName + " but apk was really "
                                    + mInstallObserver.mPackageName);
                    // delete the package we just put in place; it might be fraudulent
                    okay = false;
                    uninstall = true;
                } else {
                    try {
                        PackageInfo pkg = backupManagerService.getPackageManager().getPackageInfo(
                                info.packageName,
                                PackageManager.GET_SIGNATURES);
                        if ((pkg.applicationInfo.flags & ApplicationInfo.FLAG_ALLOW_BACKUP)
                                == 0) {
                            Slog.w(RefactoredBackupManagerService.TAG,
                                    "Restore stream contains apk of package "
                                            + info.packageName
                                            + " but it disallows backup/restore");
                            okay = false;
                        } else {
                            // So far so good -- do the signatures match the manifest?
                            Signature[] sigs = mManifestSignatures.get(info.packageName);
                            if (AppBackupUtils.signaturesMatch(sigs, pkg)) {
                                // If this is a system-uid app without a declared backup agent,
                                // don't restore any of the file data.
                                if ((pkg.applicationInfo.uid < Process.FIRST_APPLICATION_UID)
                                        && (pkg.applicationInfo.backupAgentName == null)) {
                                    Slog.w(RefactoredBackupManagerService.TAG,
                                            "Installed app " + info.packageName
                                                    + " has restricted uid and no agent");
                                    okay = false;
                                }
                            } else {
                                Slog.w(RefactoredBackupManagerService.TAG,
                                        "Installed app " + info.packageName
                                                + " signatures do not match restore manifest");
                                okay = false;
                                uninstall = true;
                            }
                        }
                    } catch (NameNotFoundException e) {
                        Slog.w(RefactoredBackupManagerService.TAG,
                                "Install of package " + info.packageName
                                        + " succeeded but now not found");
                        okay = false;
                    }
                }

                // If we're not okay at this point, we need to delete the package
                // that we just installed.
                if (uninstall) {
                    mDeleteObserver.reset();
                    backupManagerService.getPackageManager().deletePackage(
                            mInstallObserver.mPackageName,
                            mDeleteObserver, 0);
                    mDeleteObserver.waitForCompletion();
                }
            }
        } catch (IOException e) {
            Slog.e(RefactoredBackupManagerService.TAG,
                    "Unable to transcribe restored apk for install");
            okay = false;
        } finally {
            apkFile.delete();
        }

        return okay;
    }

    void sendStartRestore() {
        if (mObserver != null) {
            try {
                mObserver.onStartRestore();
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: startRestore");
                mObserver = null;
            }
        }
    }

    void sendOnRestorePackage(String name) {
        if (mObserver != null) {
            try {
                // TODO: use a more user-friendly name string
                mObserver.onRestorePackage(name);
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: restorePackage");
                mObserver = null;
            }
        }
    }

    void sendEndRestore() {
        if (mObserver != null) {
            try {
                mObserver.onEndRestore();
            } catch (RemoteException e) {
                Slog.w(RefactoredBackupManagerService.TAG,
                        "full restore observer went away: endRestore");
                mObserver = null;
            }
        }
    }
}
