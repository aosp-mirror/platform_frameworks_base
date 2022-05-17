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

import static com.android.server.backup.BackupManagerService.DEBUG;
import static com.android.server.backup.BackupManagerService.MORE_DEBUG;
import static com.android.server.backup.BackupManagerService.TAG;
import static com.android.server.backup.BackupPasswordManager.PBKDF_CURRENT;
import static com.android.server.backup.BackupPasswordManager.PBKDF_FALLBACK;
import static com.android.server.backup.UserBackupManagerService.BACKUP_FILE_HEADER_MAGIC;
import static com.android.server.backup.UserBackupManagerService.BACKUP_FILE_VERSION;

import android.app.backup.BackupManager;
import android.app.backup.IFullBackupRestoreObserver;
import android.content.pm.PackageManagerInternal;
import android.os.ParcelFileDescriptor;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.backup.OperationStorage;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.fullbackup.FullBackupObbConnection;
import com.android.server.backup.utils.BackupEligibilityRules;
import com.android.server.backup.utils.FullBackupRestoreObserverUtils;
import com.android.server.backup.utils.PasswordUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
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

    private final UserBackupManagerService mBackupManagerService;
    private final OperationStorage mOperationStorage;
    private final ParcelFileDescriptor mInputFile;
    private final String mCurrentPassword;
    private final String mDecryptPassword;
    private final AtomicBoolean mLatchObject;
    private final FullBackupObbConnection mObbConnection;

    private IFullBackupRestoreObserver mObserver;

    public PerformAdbRestoreTask(
            UserBackupManagerService backupManagerService, OperationStorage operationStorage,
            ParcelFileDescriptor fd, String curPassword, String decryptPassword,
            IFullBackupRestoreObserver observer, AtomicBoolean latch) {
        this.mBackupManagerService = backupManagerService;
        mOperationStorage = operationStorage;
        mInputFile = fd;
        mCurrentPassword = curPassword;
        mDecryptPassword = decryptPassword;
        mObserver = observer;
        mLatchObject = latch;
        mObbConnection = new FullBackupObbConnection(backupManagerService);
    }

    @Override
    public void run() {
        Slog.i(TAG, "--- Performing full-dataset restore ---");
        mObbConnection.establish();
        mObserver = FullBackupRestoreObserverUtils.sendStartRestore(mObserver);

        FileInputStream rawInStream = null;
        try {
            if (!mBackupManagerService.backupPasswordMatches(mCurrentPassword)) {
                if (DEBUG) {
                    Slog.w(TAG, "Backup password mismatch; aborting");
                }
                return;
            }

            rawInStream = new FileInputStream(mInputFile.getFileDescriptor());

            InputStream tarInputStream = parseBackupFileHeaderAndReturnTarStream(rawInStream,
                    mDecryptPassword);
            if (tarInputStream == null) {
                // There was an error reading the backup file, which is already handled and logged.
                // Just abort.
                return;
            }

            BackupEligibilityRules eligibilityRules = new BackupEligibilityRules(
                    mBackupManagerService.getPackageManager(),
                    LocalServices.getService(PackageManagerInternal.class),
                    mBackupManagerService.getUserId(), BackupManager.OperationType.ADB_BACKUP);
            FullRestoreEngine mEngine = new FullRestoreEngine(mBackupManagerService,
                    mOperationStorage, null, mObserver, null, null,
                    true, 0 /*unused*/, true, eligibilityRules);
            FullRestoreEngineThread mEngineThread = new FullRestoreEngineThread(mEngine,
                    tarInputStream);
            mEngineThread.run();

            if (MORE_DEBUG) {
                Slog.v(TAG, "Done consuming input tarfile.");
            }
        } catch (Exception e) {
            Slog.e(TAG, "Unable to read restore input");
        } finally {
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

    private static InputStream attemptEncryptionKeyDecryption(String decryptPassword,
            String algorithm, byte[] userSalt, byte[] ckSalt, int rounds, String userIvHex,
            String encryptionKeyBlobHex, InputStream rawInStream, boolean doLog) {
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
            byte[] mkCipher = PasswordUtils.hexToByteArray(encryptionKeyBlobHex);
            byte[] mkBlob = c.doFinal(mkCipher);

            // first, the encryption key IV
            int offset = 0;
            int len = mkBlob[offset++];
            IV = Arrays.copyOfRange(mkBlob, offset, offset + len);
            offset += len;
            // then the encryption key itself
            len = mkBlob[offset++];
            byte[] encryptionKey = Arrays.copyOfRange(mkBlob,
                    offset, offset + len);
            offset += len;
            // and finally the encryption key checksum hash
            len = mkBlob[offset++];
            byte[] mkChecksum = Arrays.copyOfRange(mkBlob,
                    offset, offset + len);

            // now validate the decrypted encryption key against the checksum
            byte[] calculatedCk = PasswordUtils.makeKeyChecksum(algorithm, encryptionKey, ckSalt,
                    rounds);
            if (Arrays.equals(calculatedCk, mkChecksum)) {
                ivSpec = new IvParameterSpec(IV);
                c.init(Cipher.DECRYPT_MODE,
                        new SecretKeySpec(encryptionKey, "AES"),
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
            // the encryption key.  Use the identical "incorrect password" log text as is
            // used in the checksum failure log in order to avoid providing additional
            // information to an attacker.
            if (doLog) {
                Slog.w(TAG, "Incorrect password");
            }
        } catch (IllegalBlockSizeException e) {
            if (doLog) {
                Slog.w(TAG, "Invalid block size in encryption key");
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

                String encryptionKeyBlobHex = readHeaderLine(rawInStream); // 9

                // decrypt the encryption key blob
                result = attemptEncryptionKeyDecryption(decryptPassword, PBKDF_CURRENT, userSalt,
                        ckSalt, rounds, userIvHex, encryptionKeyBlobHex, rawInStream, false);
                if (result == null && pbkdf2Fallback) {
                    result = attemptEncryptionKeyDecryption(
                            decryptPassword, PBKDF_FALLBACK, userSalt, ckSalt,
                            rounds, userIvHex, encryptionKeyBlobHex, rawInStream, true);
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
}
