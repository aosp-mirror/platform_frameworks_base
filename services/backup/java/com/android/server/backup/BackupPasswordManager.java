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

package com.android.server.backup;

import android.content.Context;
import android.util.Slog;

import com.android.server.backup.utils.DataStreamFileCodec;
import com.android.server.backup.utils.DataStreamCodec;
import com.android.server.backup.utils.PasswordUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;

/**
 * Manages persisting and verifying backup passwords.
 *
 * <p>Does not persist the password itself, but persists a PBKDF2 hash with a randomly chosen (also
 * persisted) salt. Validation is performed by running the challenge text through the same
 * PBKDF2 cycle with the persisted salt, and checking the hashes match.
 *
 * @see PasswordUtils for the hashing algorithm.
 */
public final class BackupPasswordManager {
    private static final String TAG = "BackupPasswordManager";
    private static final boolean DEBUG = false;

    private static final int BACKUP_PW_FILE_VERSION = 2;
    private static final int DEFAULT_PW_FILE_VERSION = 1;

    private static final String PASSWORD_VERSION_FILE_NAME = "pwversion";
    private static final String PASSWORD_HASH_FILE_NAME = "pwhash";

    // See https://android-developers.googleblog.com/2013/12/changes-to-secretkeyfactory-api-in.html
    public static final String PBKDF_CURRENT = "PBKDF2WithHmacSHA1";
    public static final String PBKDF_FALLBACK = "PBKDF2WithHmacSHA1And8bit";

    private final SecureRandom mRng;
    private final Context mContext;
    private final File mBaseStateDir;

    private String mPasswordHash;
    private int mPasswordVersion;
    private byte[] mPasswordSalt;

    /**
     * Creates an instance enforcing permissions using the {@code context} and persisting password
     * data within the {@code baseStateDir}.
     *
     * @param context The context, for enforcing permissions around setting the password.
     * @param baseStateDir A directory within which to persist password data.
     * @param secureRandom Random number generator with which to generate password salts.
     */
    BackupPasswordManager(Context context, File baseStateDir, SecureRandom secureRandom) {
        mContext = context;
        mRng = secureRandom;
        mBaseStateDir = baseStateDir;
        loadStateFromFilesystem();
    }

    /**
     * Returns {@code true} if a password for backup is set.
     *
     * @throws SecurityException If caller does not have {@link android.Manifest.permission#BACKUP}
     *   permission.
     */
    boolean hasBackupPassword() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "hasBackupPassword");
        return mPasswordHash != null && mPasswordHash.length() > 0;
    }

    /**
     * Returns {@code true} if {@code password} matches the persisted password.
     *
     * @throws SecurityException If caller does not have {@link android.Manifest.permission#BACKUP}
     *   permission.
     */
    boolean backupPasswordMatches(String password) {
        if (hasBackupPassword() && !passwordMatchesSaved(password)) {
            if (DEBUG) Slog.w(TAG, "Backup password mismatch; aborting");
            return false;
        }
        return true;
    }

    /**
     * Sets the new password, given a correct current password.
     *
     * @throws SecurityException If caller does not have {@link android.Manifest.permission#BACKUP}
     *   permission.
     * @return {@code true} if has permission to set the password, {@code currentPassword}
     *   matches the currently persisted password, and is able to persist {@code newPassword}.
     */
    boolean setBackupPassword(String currentPassword, String newPassword) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.BACKUP,
                "setBackupPassword");

        if (!passwordMatchesSaved(currentPassword)) {
            return false;
        }

        // Snap up to latest password file version.
        try {
            getPasswordVersionFileCodec().serialize(BACKUP_PW_FILE_VERSION);
            mPasswordVersion = BACKUP_PW_FILE_VERSION;
        } catch (IOException e) {
            Slog.e(TAG, "Unable to write backup pw version; password not changed");
            return false;
        }

        if (newPassword == null || newPassword.isEmpty()) {
            return clearPassword();
        }

        try {
            byte[] salt = randomSalt();
            String newPwHash = PasswordUtils.buildPasswordHash(
                    PBKDF_CURRENT, newPassword, salt, PasswordUtils.PBKDF2_HASH_ROUNDS);

            getPasswordHashFileCodec().serialize(new BackupPasswordHash(newPwHash, salt));
            mPasswordHash = newPwHash;
            mPasswordSalt = salt;
            return true;
        } catch (IOException e) {
            Slog.e(TAG, "Unable to set backup password");
        }
        return false;
    }

    /**
     * Returns {@code true} if should try salting using the older PBKDF algorithm.
     *
     * <p>This is {@code true} for v1 files.
     */
    private boolean usePbkdf2Fallback() {
        return mPasswordVersion < BACKUP_PW_FILE_VERSION;
    }

    /**
     * Deletes the current backup password.
     *
     * @return {@code true} if successful.
     */
    private boolean clearPassword() {
        File passwordHashFile = getPasswordHashFile();
        if (passwordHashFile.exists() && !passwordHashFile.delete()) {
            Slog.e(TAG, "Unable to clear backup password");
            return false;
        }

        mPasswordHash = null;
        mPasswordSalt = null;
        return true;
    }

    /**
     * Sets the password hash, salt, and version in the object from what has been persisted to the
     * filesystem.
     */
    private void loadStateFromFilesystem() {
        try {
            mPasswordVersion = getPasswordVersionFileCodec().deserialize();
        } catch (IOException e) {
            Slog.e(TAG, "Unable to read backup pw version");
            mPasswordVersion = DEFAULT_PW_FILE_VERSION;
        }

        try {
            BackupPasswordHash hash = getPasswordHashFileCodec().deserialize();
            mPasswordHash = hash.hash;
            mPasswordSalt = hash.salt;
        } catch (IOException e) {
            Slog.e(TAG, "Unable to read saved backup pw hash");
        }
    }

    /**
     * Whether the candidate password matches the current password. If the persisted password is an
     * older version, attempts hashing using the older algorithm.
     *
     * @param candidatePassword The password to try.
     * @return {@code true} if the passwords match.
     */
    private boolean passwordMatchesSaved(String candidatePassword) {
        return passwordMatchesSaved(PBKDF_CURRENT, candidatePassword)
                || (usePbkdf2Fallback() && passwordMatchesSaved(PBKDF_FALLBACK, candidatePassword));
    }

    /**
     * Returns {@code true} if the candidate password is correct.
     *
     * @param algorithm The algorithm used to hash passwords.
     * @param candidatePassword The candidate password to compare to the current password.
     * @return {@code true} if the candidate password matched the saved password.
     */
    private boolean passwordMatchesSaved(String algorithm, String candidatePassword) {
        if (mPasswordHash == null) {
            return candidatePassword == null || candidatePassword.equals("");
        } else if (candidatePassword == null || candidatePassword.length() == 0) {
            // The current password is not zero-length, but the candidate password is.
            return false;
        } else {
            String candidatePasswordHash = PasswordUtils.buildPasswordHash(
                    algorithm, candidatePassword, mPasswordSalt, PasswordUtils.PBKDF2_HASH_ROUNDS);
            return mPasswordHash.equalsIgnoreCase(candidatePasswordHash);
        }
    }

    private byte[] randomSalt() {
        int bitsPerByte = 8;
        byte[] array = new byte[PasswordUtils.PBKDF2_SALT_SIZE / bitsPerByte];
        mRng.nextBytes(array);
        return array;
    }

    private DataStreamFileCodec<Integer> getPasswordVersionFileCodec() {
        return new DataStreamFileCodec<>(
                new File(mBaseStateDir, PASSWORD_VERSION_FILE_NAME),
                new PasswordVersionFileCodec());
    }

    private DataStreamFileCodec<BackupPasswordHash> getPasswordHashFileCodec() {
        return new DataStreamFileCodec<>(getPasswordHashFile(), new PasswordHashFileCodec());
    }

    private File getPasswordHashFile() {
        return new File(mBaseStateDir, PASSWORD_HASH_FILE_NAME);
    }

    /**
     * Container class for a PBKDF hash and the salt used to create the hash.
     */
    private static final class BackupPasswordHash {
        public String hash;
        public byte[] salt;

        BackupPasswordHash(String hash, byte[] salt) {
            this.hash = hash;
            this.salt = salt;
        }
    }

    /**
     * The password version file contains a single 32-bit integer.
     */
    private static final class PasswordVersionFileCodec implements
            DataStreamCodec<Integer> {
        @Override
        public void serialize(Integer integer, DataOutputStream dataOutputStream)
                throws IOException {
            dataOutputStream.write(integer);
        }

        @Override
        public Integer deserialize(DataInputStream dataInputStream) throws IOException {
            return dataInputStream.readInt();
        }
    }

    /**
     * The passwords hash file contains
     *
     * <ul>
     *     <li>A 32-bit integer representing the number of bytes in the salt;
     *     <li>The salt bytes;
     *     <li>A UTF-8 string of the hash.
     * </ul>
     */
    private static final class PasswordHashFileCodec implements
            DataStreamCodec<BackupPasswordHash> {
        @Override
        public void serialize(BackupPasswordHash backupPasswordHash,
                DataOutputStream dataOutputStream) throws IOException {
            dataOutputStream.writeInt(backupPasswordHash.salt.length);
            dataOutputStream.write(backupPasswordHash.salt);
            dataOutputStream.writeUTF(backupPasswordHash.hash);
        }

        @Override
        public BackupPasswordHash deserialize(
                DataInputStream dataInputStream) throws IOException {
            int saltLen = dataInputStream.readInt();
            byte[] salt = new byte[saltLen];
            dataInputStream.readFully(salt);
            String hash = dataInputStream.readUTF();
            return new BackupPasswordHash(hash, salt);
        }
    }
}
