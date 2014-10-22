/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.security;

import android.os.Environment;
import android.os.FileUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import libcore.io.IoUtils;

/**
 *@hide
 */
public class SystemKeyStore {

    private static final String SYSTEM_KEYSTORE_DIRECTORY = "misc/systemkeys";
    private static final String KEY_FILE_EXTENSION = ".sks";
    private static SystemKeyStore mInstance = new SystemKeyStore();

    private SystemKeyStore() { }

    public static SystemKeyStore getInstance() {
        return mInstance;
    }

    public static String toHexString(byte[] keyData) {
        if (keyData == null) {
            return null;
        }
        int keyLen = keyData.length;
        int expectedStringLen = keyData.length * 2;
        StringBuilder sb = new StringBuilder(expectedStringLen);
        for (int i = 0; i < keyData.length; i++) {
            String hexStr = Integer.toString(keyData[i] & 0x00FF, 16);
            if (hexStr.length() == 1) {
                hexStr = "0" + hexStr;
            }
            sb.append(hexStr);
        }
        return sb.toString();
    }

    public String generateNewKeyHexString(int numBits, String algName, String keyName)
            throws NoSuchAlgorithmException {
        return toHexString(generateNewKey(numBits, algName, keyName));
    }

    public byte[] generateNewKey(int numBits, String algName, String keyName)
            throws NoSuchAlgorithmException {

        // Check if key with similar name exists. If so, return null.
        File keyFile = getKeyFile(keyName);
        if (keyFile.exists()) {
            throw new IllegalArgumentException();
        }

        KeyGenerator skg = KeyGenerator.getInstance(algName);
        SecureRandom srng = SecureRandom.getInstance("SHA1PRNG");
        skg.init(numBits, srng);

        SecretKey sk = skg.generateKey();
        byte[] retKey = sk.getEncoded();

        try {
            // Store the key
            if (!keyFile.createNewFile()) {
                throw new IllegalArgumentException();
            }

            FileOutputStream fos = new FileOutputStream(keyFile);
            fos.write(retKey);
            fos.flush();
            FileUtils.sync(fos);
            fos.close();
            FileUtils.setPermissions(keyFile.getName(), (FileUtils.S_IRUSR | FileUtils.S_IWUSR),
                -1, -1);
        } catch (IOException ioe) {
            return null;
        }
        return retKey;
    }

    private File getKeyFile(String keyName) {
        File sysKeystoreDir = new File(Environment.getDataDirectory(),
                SYSTEM_KEYSTORE_DIRECTORY);
        File keyFile = new File(sysKeystoreDir, keyName + KEY_FILE_EXTENSION);
        return keyFile;
    }

    public String retrieveKeyHexString(String keyName) throws IOException {
        return toHexString(retrieveKey(keyName));
    }

    public byte[] retrieveKey(String keyName) throws IOException {
        File keyFile = getKeyFile(keyName);
        if (!keyFile.exists()) {
            return null;
        }
        return IoUtils.readFileAsByteArray(keyFile.toString());
    }

    public void deleteKey(String keyName) {

        // Get the file first.
        File keyFile = getKeyFile(keyName);
        if (!keyFile.exists()) {
            throw new IllegalArgumentException();
        }

        keyFile.delete();
    }
}
