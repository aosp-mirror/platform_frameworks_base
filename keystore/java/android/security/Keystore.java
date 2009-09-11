/*
 * Copyright (C) 2009 The Android Open Source Project
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

/**
 * The Keystore class provides the functions to list the certs/keys in keystore.
 * {@hide}
 */

public abstract class Keystore {
    /** Action to unlock (or initialize) the keystore. */
    public static final String ACTION_UNLOCK_CREDENTIAL_STORAGE =
            "android.security.UNLOCK_CREDENTIAL_STORAGE";

    // Keystore States
    public static final int BOOTUP = 0;
    public static final int UNINITIALIZED = 1;
    public static final int LOCKED = 2;
    public static final int UNLOCKED = 3;

    private static final String TAG = "Keystore";
    private static final String[] NOTFOUND = new String[0];

    public static Keystore getInstance() {
        return new FileKeystore();
    }

    public abstract int lock();
    public abstract int unlock(String password);
    public abstract int getState();
    public abstract int changePassword(String oldPassword, String newPassword);
    public abstract int setPassword(String firstPassword);
    public abstract String[] listKeys(String namespace);
    public abstract int put(String namespace, String keyname, String value);
    public abstract String get(String namespace, String keyname);
    public abstract int remove(String namespace, String keyname);
    public abstract int reset();

    private static class FileKeystore extends Keystore {
        private static final String SERVICE_NAME = "keystore";
        private static final String CA_CERTIFICATE = "CaCertificate";
        private static final String USER_CERTIFICATE = "UserCertificate";
        private static final String USER_KEY = "UserPrivateKey";
        private static final ServiceCommand mServiceCommand =
                new ServiceCommand(SERVICE_NAME);

        @Override
        public int lock() {
            Reply result = mServiceCommand.execute(ServiceCommand.LOCK);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public int unlock(String password) {
            Reply result = mServiceCommand.execute(ServiceCommand.UNLOCK,
                    password);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public int getState() {
            Reply result = mServiceCommand.execute(ServiceCommand.GET_STATE);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public int changePassword(String oldPassword, String newPassword) {
            Reply result = mServiceCommand.execute(ServiceCommand.PASSWD,
                    oldPassword, newPassword);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public int setPassword(String firstPassword) {
            Reply result = mServiceCommand.execute(ServiceCommand.PASSWD,
                    firstPassword);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public String[] listKeys(String namespace) {
            Reply result = mServiceCommand.execute(ServiceCommand.LIST_KEYS,
                    namespace);
            if ((result == null) || (result.returnCode != 0) ||
                    (result.len == 0)) {
                return NOTFOUND;
            }
            return new String(result.data, 0, result.len).split("\\s+");
        }

        @Override
        public int put(String namespace, String keyname, String value) {
            Reply result = mServiceCommand.execute(ServiceCommand.PUT_KEY,
                    namespace, keyname, value);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public String get(String namespace, String keyname) {
            Reply result = mServiceCommand.execute(ServiceCommand.GET_KEY,
                    namespace, keyname);
            return (result != null) ? ((result.returnCode != 0) ? null :
                    new String(result.data, 0, result.len)) : null;
        }

        @Override
        public int remove(String namespace, String keyname) {
            Reply result = mServiceCommand.execute(ServiceCommand.REMOVE_KEY,
                    namespace, keyname);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public int reset() {
            Reply result = mServiceCommand.execute(ServiceCommand.RESET);
            return (result != null) ? result.returnCode : -1;
        }
    }
}
