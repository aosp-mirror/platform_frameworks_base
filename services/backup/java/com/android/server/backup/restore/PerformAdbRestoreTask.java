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

import static com.android.server.backup.BackupPasswordManager.PBKDF_CURRENT;
import static com.android.server.backup.BackupPasswordManager.PBKDF_FALLBACK;
import static com.android.server.backup.RefactoredBackupManagerService.BACKUP_FILE_HEADER_MAGIC;
import static com.android.server.backup.RefactoredBackupManagerService.BACKUP_FILE_VERSION;
import static com.android.server.backup.RefactoredBackupManagerService.BACKUP_MANIFEST_FILENAME;
import static com.android.server.backup.RefactoredBackupManagerService.BACKUP_METADATA_FILENAME;
import static com.android.server.backup.RefactoredBackupManagerService.DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.MORE_DEBUG;
import static com.android.server.backup.RefactoredBackupManagerService.OP_TYPE_RESTORE_WAIT;
import static com.android.server.backup.RefactoredBackupManagerService.SETTINGS_PACKAGE;
import static com.android.server.backup.RefactoredBackupManagerService.SHARED_BACKUP_AGENT_PACKAGE;
import static com.android.server.backup.RefactoredBackupManagerService.TAG;
import static com.android.server.backup.RefactoredBackupManagerService.TIMEOUT_FULL_BACKUP_INTERVAL;
import static com.android.server.backup.RefactoredBackupManagerService.TIMEOUT_RESTORE_INTERVAL;
import static com.android.server.backup.internal.BackupHandler.MSG_RESTORE_OPERATION_TIMEOUT;

import android.app.ApplicationThreadConstants;
import android.app.IBackupAgent;
import android.app.backup.FullBackup;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IFullBackupRestoreObserver;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.Signature;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.backup.FileMetadata;
import com.android.server.backup.KeyValueAdbRestoreEngine;
import com.android.server.backup.PackageManagerBackupAgent;
import com.android.server.backup.RefactoredBackupManagerService;
import com.android.server.backup.fullbackup.FullBackupObbConnection;
import com.android.server.backup.utils.BytesReadListener;
import com.android.server.backup.utils.FullBackupRestoreObserverUtils;
import com.android.server.backup.utils.PasswordUtils;
import com.android.server.backup.utils.RestoreUtils;
import com.android.server.backup.utils.TarBackupReader;

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

    private final RefactoredBackupManagerService mBackupManagerService;
    private final ParcelFileDescriptor mInputFile;
    private final String mCurrentPassword;
    private final String mDecryptPassword;
    private final AtomicBoolean mLatchObject;
    private final PackageManagerBackupAgent mPackageManagerBackupAgent;
    private final RestoreInstallObserver mInstallObserver = new RestoreInstallObserver();
    private final RestoreDeleteObserver mDeleteObserver = new RestoreDeleteObserver();

    private IFullBackupRestoreObserver mObserver;
    private IBackupAgent mAgent;
    private String mAgentPackage;
    private ApplicationInfo mTargetApp;
    private FullBackupObbConnection mObbConnection = null;
    private ParcelFileDescriptor[] mPipes = null;
    private byte[] mWidgetData = null;

    private long mBytes;

    // Runner that can be placed on a separate thread to do in-process invocation
    // of the "restore finished" API asynchronously.  Used by adb restore.
    private static class RestoreFinishedRunnable implements Runnable {

        private final IBackupAgent mAgent;
        private final int mToken;
        private final RefactoredBackupManagerService mBackupManagerService;

        RestoreFinishedRunnable(IBackupAgent agent, int token,
                RefactoredBackupManagerService backupManagerService) {
            mAgent = agent;
            mToken = token;
            mBackupManagerService = backupManagerService;
        }

        @Override
        public void run() {
            try {
                mAgent.doRestoreFinished(mToken, mBackupManagerService.getBackupManagerBinder());
            } catch (RemoteException e) {
                // never happens; this is used only for local binder calls
            }
        }
    }

    // possible handling states for a given package in the restore dataset
    private final HashMap<String, RestorePolicy> mPackagePolicies
            = new HashMap<>();

    // installer package names for each encountered app, derived from the manifests
    private final HashMap<String, String> mPackageInstallers = new HashMap<>();

    // Signatures for a given package found in its manifest file
    private final HashMap<String, Signature[]> mManifestSignatures
            = new HashMap<>();

    // Packages we've already wiped data on when restoring their first file
    private final HashSet<String> mClearedPackages = new HashSet<>();

    public PerformAdbRestoreTask(RefactoredBackupManagerService backupManagerService,
            ParcelFileDescriptor fd, String curPassword, String decryptPassword,
            IFullBackupRestoreObserver observer, AtomicBoolean latch) {
        this.mBackupManagerService = backupManagerService;
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
        mClearedPackages.add(SETTINGS_PACKAGE);
    }

    @Override
    public void run() {
        Slog.i(TAG, "--- Performing full-dataset restore ---");
        mObbConnection.establish();
        mObserver = FullBackupRestoreObserverUtils.sendStartRestore(mObserver);

        // Are we able to restore shared-storage data?
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            mPackagePolicies.put(SHARED_BACKUP_AGENT_PACKAGE, RestorePolicy.ACCEPT);
        }

        FileInputStream rawInStream = null;
        try {
            if (!mBackupManagerService.backupPasswordMatches(mCurrentPassword)) {
                if (DEBUG) {
                    Slog.w(TAG, "Backup password mismatch; aborting");
                }
                return;
            }

            mBytes = 0;

            rawInStream = new FileInputStream(mInputFile.getFileDescriptor());

            InputStream tarInputStream = parseBackupFileHeaderAndReturnTarStream(rawInStream,
                    mDecryptPassword);
            if (tarInputStream == null) {
                // There was an error reading the backup file, which is already handled and logged.
                // Just abort.
                return;
            }

            byte[] buffer = new byte[32 * 1024];
            boolean didRestore;
            do {
                didRestore = restoreOneFile(tarInputStream, false /* mustKillAgent */, buffer,
                        null /* onlyPackage */, true /* allowApks */,
                        mBackupManagerService.generateRandomIntegerToken(), null /* monitor */);
            } while (didRestore);

            if (MORE_DEBUG) {
                Slog.v(TAG, "Done consuming input tarfile, total bytes=" + mBytes);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Unable to read restore input");
        } finally {
            tearDownPipes();
            tearDownAgent(mTargetApp, true);

            try {
                if (rawInStream != null) {
                    rawInStream.close();
                }
                mInputFile.close();
            } catch (IOException e) {
                Slog.w(TAG, "Close of restore data pipe threw", e);
                /* nothing we can do about this */
            }
            synchronized (mLatchObject) {
                mLatchObject.set(true);
                mLatchObject.notifyAll();
            }
            mObbConnection.tearDown();
            mObserver = FullBackupRestoreObserverUtils.sendEndRestore(mObserver);
            Slog.d(TAG, "Full restore pass complete.");
            mBackupManagerService.getWakelock().release();
        }
    }

    private static void readFullyOrThrow(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int bytesRead = in.read(buffer, offset, buffer.length - offset);
            if (bytesRead <= 0) {
                throw new IOException("Couldn't fully read data");
            }
            offset += bytesRead;
        }
    }

    @VisibleForTesting
    public static InputStream parseBackupFileHeaderAndReturnTarStream(
            InputStream rawInputStream,
            String decryptPassword)
            throws IOException {
        // First, parse out the unencrypted/uncompressed header
        boolean compressed = false;
        InputStream preCompressStream = rawInputStream;

        boolean okay = false;
        final int headerLen = BACKUP_FILE_HEADER_MAGIC.length();
        byte[] streamHeader = new byte[headerLen];
        readFullyOrThrow(rawInputStream, streamHeader);
        byte[] magicBytes = BACKUP_FILE_HEADER_MAGIC.getBytes(
                "UTF-8");
        if (Arrays.equals(magicBytes, streamHeader)) {
            // okay, header looks good.  now parse out the rest of the fields.
            String s = readHeaderLine(rawInputStream);
            final int archiveVersion = Integer.parseInt(s);
            if (archiveVersion <= BACKUP_FILE_VERSION) {
                // okay, it's a version we recognize.  if it's version 1, we may need
                // to try two different PBKDF2 regimes to compare checksums.
                final boolean pbkdf2Fallback = (archiveVersion == 1);

                s = readHeaderLine(rawInputStream);
                compressed = (Integer.parseInt(s) != 0);
                s = readHeaderLine(rawInputStream);
                if (s.equals("none")) {
                    // no more header to parse; we're good to go
                    okay = true;
                } else if (decryptPassword != null && decryptPassword.length() > 0) {
                    preCompressStream = decodeAesHeaderAndInitialize(
                            decryptPassword, s, pbkdf2Fallback,
                            rawInputStream);
                    if (preCompressStream != null) {
                        okay = true;
                    }
                } else {
                    Slog.w(TAG, "Archive is encrypted but no password given");
                }
            } else {
                Slog.w(TAG, "Wrong header version: " + s);
            }
        } else {
            Slog.w(TAG, "Didn't read the right header magic");
        }

        if (!okay) {
            Slog.w(TAG, "Invalid restore data; aborting.");
            return null;
        }

        // okay, use the right stream layer based on compression
        return compressed ? new InflaterInputStream(preCompressStream) : preCompressStream;
    }

    private static String readHeaderLine(InputStream in) throws IOException {
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

    private static InputStream attemptMasterKeyDecryption(String decryptPassword, String algorithm,
            byte[] userSalt, byte[] ckSalt,
            int rounds, String userIvHex, String masterKeyBlobHex, InputStream rawInStream,
            boolean doLog) {
        InputStream result = null;

        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            SecretKey userKey = PasswordUtils
                    .buildPasswordKey(algorithm, decryptPassword, userSalt,
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
                Slog.w(TAG, "Incorrect password");
            }
        } catch (InvalidAlgorithmParameterException e) {
            if (doLog) {
                Slog.e(TAG, "Needed parameter spec unavailable!", e);
            }
        } catch (BadPaddingException e) {
            // This case frequently occurs when the wrong password is used to decrypt
            // the master key.  Use the identical "incorrect password" log text as is
            // used in the checksum failure log in order to avoid providing additional
            // information to an attacker.
            if (doLog) {
                Slog.w(TAG, "Incorrect password");
            }
        } catch (IllegalBlockSizeException e) {
            if (doLog) {
                Slog.w(TAG, "Invalid block size in master key");
            }
        } catch (NoSuchAlgorithmException e) {
            if (doLog) {
                Slog.e(TAG, "Needed decryption algorithm unavailable!");
            }
        } catch (NoSuchPaddingException e) {
            if (doLog) {
                Slog.e(TAG, "Needed padding mechanism unavailable!");
            }
        } catch (InvalidKeyException e) {
            if (doLog) {
                Slog.w(TAG, "Illegal password; aborting");
            }
        }

        return result;
    }

    private static InputStream decodeAesHeaderAndInitialize(String decryptPassword,
            String encryptionName,
            boolean pbkdf2Fallback,
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
                result = attemptMasterKeyDecryption(decryptPassword, PBKDF_CURRENT,
                        userSalt, ckSalt, rounds, userIvHex, masterKeyBlobHex, rawInStream, false);
                if (result == null && pbkdf2Fallback) {
                    result = attemptMasterKeyDecryption(
                            decryptPassword, PBKDF_FALLBACK, userSalt, ckSalt,
                            rounds, userIvHex, masterKeyBlobHex, rawInStream, true);
                }
            } else {
                Slog.w(TAG, "Unsupported encryption method: " + encryptionName);
            }
        } catch (NumberFormatException e) {
            Slog.w(TAG, "Can't parse restore data header");
        } catch (IOException e) {
            Slog.w(TAG, "Can't read input header");
        }

        return result;
    }

    boolean restoreOneFile(InputStream instream, boolean mustKillAgent, byte[] buffer,
            PackageInfo onlyPackage, boolean allowApks, int token, IBackupManagerMonitor monitor) {
        BytesReadListener bytesReadListener = new BytesReadListener() {
            @Override
            public void onBytesRead(long bytesRead) {
                mBytes += bytesRead;
            }
        };
        TarBackupReader tarBackupReader = new TarBackupReader(instream,
                bytesReadListener, monitor);
        FileMetadata info;
        try {
            info = tarBackupReader.readTarHeaders();
            if (info != null) {
                if (MORE_DEBUG) {
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
                        if (DEBUG) {
                            Slog.d(TAG, "Saw new package; finalizing old one");
                        }
                        // Now we're really done
                        tearDownPipes();
                        tearDownAgent(mTargetApp, true);
                        mTargetApp = null;
                        mAgentPackage = null;
                    }
                }

                if (info.path.equals(BACKUP_MANIFEST_FILENAME)) {
                    Signature[] signatures = tarBackupReader.readAppManifestAndReturnSignatures(
                            info);
                    RestorePolicy restorePolicy = tarBackupReader.chooseRestorePolicy(
                            mBackupManagerService.getPackageManager(), allowApks,
                            info, signatures);
                    mManifestSignatures.put(info.packageName, signatures);
                    mPackagePolicies.put(pkg, restorePolicy);
                    mPackageInstallers.put(pkg, info.installerPackageName);
                    // We've read only the manifest content itself at this point,
                    // so consume the footer before looping around to the next
                    // input file
                    tarBackupReader.skipTarPadding(info.size);
                    mObserver = FullBackupRestoreObserverUtils.sendOnRestorePackage(mObserver, pkg);
                } else if (info.path.equals(BACKUP_METADATA_FILENAME)) {
                    // Metadata blobs!
                    tarBackupReader.readMetadata(info);

                    // The following only exist because we want to keep refactoring as safe as
                    // possible, without changing too much.
                    // TODO: Refactor, so that there are no funny things like this.
                    // This is read during TarBackupReader.readMetadata().
                    mWidgetData = tarBackupReader.getWidgetData();
                    // This can be nulled during TarBackupReader.readMetadata().
                    monitor = tarBackupReader.getMonitor();

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
                                if (DEBUG) {
                                    Slog.d(TAG, "APK file; installing");
                                }
                                // Try to install the app.
                                String installerName = mPackageInstallers.get(pkg);
                                boolean isSuccessfullyInstalled = RestoreUtils.installApk(
                                        instream, mBackupManagerService.getPackageManager(),
                                        mInstallObserver, mDeleteObserver, mManifestSignatures,
                                        mPackagePolicies, info, installerName,
                                        bytesReadListener, mBackupManagerService.getDataDir()
                                                                                         );
                                // good to go; promote to ACCEPT
                                mPackagePolicies.put(pkg, isSuccessfullyInstalled
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
                                if (DEBUG) {
                                    Slog.d(TAG, "apk present but ACCEPT");
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
                            Slog.e(TAG, "Invalid policy from manifest");
                            okay = false;
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                            break;
                    }

                    // The path needs to be canonical
                    if (!isCanonicalFilePath(info.path)) {
                        okay = false;
                    }

                    // If the policy is satisfied, go ahead and set up to pipe the
                    // data to the agent.
                    if (DEBUG && okay && mAgent != null) {
                        Slog.i(TAG, "Reusing existing agent instance");
                    }
                    if (okay && mAgent == null) {
                        if (DEBUG) {
                            Slog.d(TAG, "Need to launch agent for " + pkg);
                        }

                        try {
                            mTargetApp =
                                    mBackupManagerService.getPackageManager().getApplicationInfo(
                                            pkg, 0);

                            // If we haven't sent any data to this app yet, we probably
                            // need to clear it first.  Check that.
                            if (!mClearedPackages.contains(pkg)) {
                                // apps with their own backup agents are
                                // responsible for coherently managing a full
                                // restore.
                                if (mTargetApp.backupAgentName == null) {
                                    if (DEBUG) {
                                        Slog.d(TAG,
                                                "Clearing app data preparatory to full restore");
                                    }
                                    mBackupManagerService.clearApplicationDataSynchronous(pkg);
                                } else {
                                    if (DEBUG) {
                                        Slog.d(TAG, "backup agent ("
                                                + mTargetApp.backupAgentName + ") => no clear");
                                    }
                                }
                                mClearedPackages.add(pkg);
                            } else {
                                if (DEBUG) {
                                    Slog.d(TAG, "We've initialized this app already; no clear "
                                            + "required");
                                }
                            }

                            // All set; now set up the IPC and launch the agent
                            setUpPipes();
                            mAgent = mBackupManagerService.bindToAgentSynchronous(mTargetApp,
                                    FullBackup.KEY_VALUE_DATA_TOKEN.equals(info.domain)
                                            ? ApplicationThreadConstants.BACKUP_MODE_INCREMENTAL
                                            : ApplicationThreadConstants.BACKUP_MODE_RESTORE_FULL);
                            mAgentPackage = pkg;
                        } catch (IOException e) {
                            // fall through to error handling
                        } catch (NameNotFoundException e) {
                            // fall through to error handling
                        }

                        if (mAgent == null) {
                            Slog.e(TAG, "Unable to create agent for " + pkg);
                            okay = false;
                            tearDownPipes();
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                        }
                    }

                    // Sanity check: make sure we never give data to the wrong app.  This
                    // should never happen but a little paranoia here won't go amiss.
                    if (okay && !pkg.equals(mAgentPackage)) {
                        Slog.e(TAG, "Restoring data for " + pkg
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
                        try {
                            mBackupManagerService.prepareOperationTimeout(
                                    token, TIMEOUT_RESTORE_INTERVAL, null, OP_TYPE_RESTORE_WAIT);

                            if (FullBackup.OBB_TREE_TOKEN.equals(info.domain)) {
                                if (DEBUG) {
                                    Slog.d(TAG, "Restoring OBB file for " + pkg
                                            + " : " + info.path);
                                }
                                mObbConnection.restoreObbFile(pkg, mPipes[0],
                                        info.size, info.type, info.path, info.mode,
                                        info.mtime, token,
                                        mBackupManagerService.getBackupManagerBinder());
                            } else if (FullBackup.KEY_VALUE_DATA_TOKEN.equals(info.domain)) {
                                if (DEBUG) {
                                    Slog.d(TAG, "Restoring key-value file for " + pkg
                                            + " : " + info.path);
                                }
                                KeyValueAdbRestoreEngine restoreEngine =
                                        new KeyValueAdbRestoreEngine(
                                                mBackupManagerService,
                                                mBackupManagerService.getDataDir(), info, mPipes[0],
                                                mAgent, token);
                                new Thread(restoreEngine, "restore-key-value-runner").start();
                            } else {
                                if (DEBUG) {
                                    Slog.d(TAG, "Invoking agent to restore file " + info.path);
                                }
                                // fire up the app's agent listening on the socket.  If
                                // the agent is running in the system process we can't
                                // just invoke it asynchronously, so we provide a thread
                                // for it here.
                                if (mTargetApp.processName.equals("system")) {
                                    Slog.d(TAG, "system process agent - spinning a thread");
                                    RestoreFileRunnable runner = new RestoreFileRunnable(
                                            mBackupManagerService, mAgent, info, mPipes[0], token);
                                    new Thread(runner, "restore-sys-runner").start();
                                } else {
                                    mAgent.doRestoreFile(mPipes[0], info.size, info.type,
                                            info.domain, info.path, info.mode, info.mtime,
                                            token, mBackupManagerService.getBackupManagerBinder());
                                }
                            }
                        } catch (IOException e) {
                            // couldn't dup the socket for a process-local restore
                            Slog.d(TAG, "Couldn't establish restore");
                            agentSuccess = false;
                            okay = false;
                        } catch (RemoteException e) {
                            // whoops, remote entity went away.  We'll eat the content
                            // ourselves, then, and not copy it over.
                            Slog.e(TAG, "Agent crashed during full restore");
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
                                        Slog.e(TAG, "Failed to write to restore pipe", e);
                                        pipeOkay = false;
                                    }
                                }
                            }

                            // done sending that file!  Now we just need to consume
                            // the delta from info.size to the end of block.
                            tarBackupReader.skipTarPadding(info.size);

                            // and now that we've sent it all, wait for the remote
                            // side to acknowledge receipt
                            agentSuccess = mBackupManagerService.waitUntilOperationComplete(token);
                        }

                        // okay, if the remote end failed at any point, deal with
                        // it by ignoring the rest of the restore on it
                        if (!agentSuccess) {
                            if (DEBUG) {
                                Slog.d(TAG, "Agent failure restoring " + pkg + "; now ignoring");
                            }
                            mBackupManagerService.getBackupHandler().removeMessages(
                                    MSG_RESTORE_OPERATION_TIMEOUT);
                            tearDownPipes();
                            tearDownAgent(mTargetApp, false);
                            mPackagePolicies.put(pkg, RestorePolicy.IGNORE);
                        }
                    }

                    // Problems setting up the agent communication, or an already-
                    // ignored package: skip to the next tar stream entry by
                    // reading and discarding this file.
                    if (!okay) {
                        if (DEBUG) {
                            Slog.d(TAG, "[discarding file content]");
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
            if (DEBUG) {
                Slog.w(TAG, "io exception on restore socket read", e);
            }
            // treat as EOF
            info = null;
        }

        return (info != null);
    }

    private static boolean isCanonicalFilePath(String path) {
        if (path.contains("..") || path.contains("//")) {
            if (MORE_DEBUG) {
                Slog.w(TAG, "Dropping invalid path " + path);
            }
            return false;
        }

        return true;
    }

    private void setUpPipes() throws IOException {
        mPipes = ParcelFileDescriptor.createPipe();
    }

    private void tearDownPipes() {
        if (mPipes != null) {
            try {
                mPipes[0].close();
                mPipes[0] = null;
                mPipes[1].close();
                mPipes[1] = null;
            } catch (IOException e) {
                Slog.w(TAG, "Couldn't close agent pipes", e);
            }
            mPipes = null;
        }
    }

    private void tearDownAgent(ApplicationInfo app, boolean doRestoreFinished) {
        if (mAgent != null) {
            try {
                // In the adb restore case, we do restore-finished here
                if (doRestoreFinished) {
                    final int token = mBackupManagerService.generateRandomIntegerToken();
                    final AdbRestoreFinishedLatch latch = new AdbRestoreFinishedLatch(
                            mBackupManagerService, token);
                    mBackupManagerService.prepareOperationTimeout(
                            token, TIMEOUT_FULL_BACKUP_INTERVAL, latch, OP_TYPE_RESTORE_WAIT);
                    if (mTargetApp.processName.equals("system")) {
                        if (MORE_DEBUG) {
                            Slog.d(TAG, "system agent - restoreFinished on thread");
                        }
                        Runnable runner = new RestoreFinishedRunnable(mAgent, token,
                                mBackupManagerService);
                        new Thread(runner, "restore-sys-finished-runner").start();
                    } else {
                        mAgent.doRestoreFinished(token,
                                mBackupManagerService.getBackupManagerBinder());
                    }

                    latch.await();
                }

                mBackupManagerService.tearDownAgentAndKill(app);
            } catch (RemoteException e) {
                Slog.d(TAG, "Lost app trying to shut down");
            }
            mAgent = null;
        }
    }

}
