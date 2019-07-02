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

import static com.android.server.testutils.TestUtils.assertExpectException;
import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;

import android.content.Context;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.backup.utils.PasswordUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.security.SecureRandom;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupPasswordManagerTest {
    private static final String PASSWORD_VERSION_FILE_NAME = "pwversion";
    private static final String PASSWORD_HASH_FILE_NAME = "pwhash";
    private static final String V1_HASH_ALGORITHM = "PBKDF2WithHmacSHA1And8bit";

    @Rule public TemporaryFolder mTemporaryFolder = new TemporaryFolder();

    @Mock private Context mContext;

    private File mStateFolder;
    private BackupPasswordManager mPasswordManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mStateFolder = mTemporaryFolder.newFolder();
        mPasswordManager = new BackupPasswordManager(mContext, mStateFolder, new SecureRandom());
    }

    @Test
    public void hasBackupPassword_isFalseIfFileDoesNotExist() {
        assertThat(mPasswordManager.hasBackupPassword()).isFalse();
    }

    @Test
    public void hasBackupPassword_isTrueIfFileExists() throws Exception {
        mPasswordManager.setBackupPassword(null, "password1234");
        assertThat(mPasswordManager.hasBackupPassword()).isTrue();
    }

    @Test
    public void hasBackupPassword_throwsSecurityExceptionIfLacksPermission() {
        setDoesNotHavePermission();

        assertExpectException(
                SecurityException.class,
                /* expectedExceptionMessageRegex */ null,
                () -> mPasswordManager.hasBackupPassword());
    }

    @Test
    public void backupPasswordMatches_isTrueIfNoPassword() {
        assertThat(mPasswordManager.backupPasswordMatches("anything")).isTrue();
    }

    @Test
    public void backupPasswordMatches_isTrueForSamePassword() {
        String password = "password1234";
        mPasswordManager.setBackupPassword(null, password);
        assertThat(mPasswordManager.backupPasswordMatches(password)).isTrue();
    }

    @Test
    public void backupPasswordMatches_isFalseForDifferentPassword() {
        mPasswordManager.setBackupPassword(null, "shiba");
        assertThat(mPasswordManager.backupPasswordMatches("corgi")).isFalse();
    }

    @Test
    public void backupPasswordMatches_worksForV1HashIfVersionIsV1() throws Exception {
        String password = "corgi\uFFFF";
        writePasswordVersionToFile(1);
        writeV1HashToFile(password, saltFixture());

        // Reconstruct so it reloads from filesystem
        mPasswordManager = new BackupPasswordManager(mContext, mStateFolder, new SecureRandom());

        assertThat(mPasswordManager.backupPasswordMatches(password)).isTrue();
    }

    @Test
    public void backupPasswordMatches_failsForV1HashIfVersionIsV2() throws Exception {
        // The algorithms produce identical hashes except if the password contains higher-order
        // unicode. See
        // https://android-developers.googleblog.com/2013/12/changes-to-secretkeyfactory-api-in.html
        String password = "corgi\uFFFF";
        writePasswordVersionToFile(2);
        writeV1HashToFile(password, saltFixture());

        // Reconstruct so it reloads from filesystem
        mPasswordManager = new BackupPasswordManager(mContext, mStateFolder, new SecureRandom());

        assertThat(mPasswordManager.backupPasswordMatches(password)).isFalse();
    }

    @Test
    public void backupPasswordMatches_throwsSecurityExceptionIfLacksPermission() {
        setDoesNotHavePermission();

        assertExpectException(
                SecurityException.class,
                /* expectedExceptionMessageRegex */ null,
                () -> mPasswordManager.backupPasswordMatches("password123"));
    }

    @Test
    public void setBackupPassword_persistsPasswordToFile() {
        String password = "shiba";

        mPasswordManager.setBackupPassword(null, password);

        BackupPasswordManager newManager = new BackupPasswordManager(
                mContext, mStateFolder, new SecureRandom());
        assertThat(newManager.backupPasswordMatches(password)).isTrue();
    }

    @Test
    public void setBackupPassword_failsIfCurrentPasswordIsWrong() {
        String secondPassword = "second password";
        mPasswordManager.setBackupPassword(null, "first password");

        boolean result = mPasswordManager.setBackupPassword(
                "incorrect pass", secondPassword);

        BackupPasswordManager newManager = new BackupPasswordManager(
                mContext, mStateFolder, new SecureRandom());
        assertThat(result).isFalse();
        assertThat(newManager.backupPasswordMatches(secondPassword)).isFalse();
    }

    @Test
    public void setBackupPassword_throwsSecurityExceptionIfLacksPermission() {
        setDoesNotHavePermission();

        assertExpectException(
                SecurityException.class,
                /* expectedExceptionMessageRegex */ null,
                () -> mPasswordManager.setBackupPassword(
                        "password123", "password111"));
    }

    private byte[] saltFixture() {
        byte[] bytes = new byte[64];
        for (int i = 0; i < 64; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    private void setDoesNotHavePermission() {
        doThrow(new SecurityException()).when(mContext)
                .enforceCallingOrSelfPermission(anyString(), anyString());
    }

    private void writeV1HashToFile(String password, byte[] salt) throws Exception {
        String hash = PasswordUtils.buildPasswordHash(
                V1_HASH_ALGORITHM, password, salt, PasswordUtils.PBKDF2_HASH_ROUNDS);
        writeHashAndSaltToFile(hash, salt);
    }

    private void writeHashAndSaltToFile(String hash, byte[] salt) throws Exception {
        FileOutputStream fos = null;
        DataOutputStream dos = null;

        try {
            File passwordHash = new File(mStateFolder, PASSWORD_HASH_FILE_NAME);
            fos = new FileOutputStream(passwordHash);
            dos = new DataOutputStream(fos);
            dos.writeInt(salt.length);
            dos.write(salt);
            dos.writeUTF(hash);
            dos.flush();
        } finally {
            if (dos != null) dos.close();
            if (fos != null) fos.close();
        }
    }

    private void writePasswordVersionToFile(int version) throws Exception {
        FileOutputStream fos = null;
        DataOutputStream dos = null;

        try {
            File passwordVersion = new File(mStateFolder, PASSWORD_VERSION_FILE_NAME);
            fos = new FileOutputStream(passwordVersion);
            dos = new DataOutputStream(fos);
            dos.writeInt(version);
            dos.flush();
        } finally {
            if (dos != null) dos.close();
            if (fos != null) fos.close();
        }
    }
}
