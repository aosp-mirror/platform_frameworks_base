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
    private static final String TAG = "Keystore";
    private static final String[] NOTFOUND = new String[0];

    /**
     */
    public static Keystore getInstance() {
        return new FileKeystore();
    }

    /**
     */
    public abstract String getUserkey(String key);

    /**
     */
    public abstract String getCertificate(String key);

    /**
     */
    public abstract String[] getAllCertificateKeys();

    /**
     */
    public abstract String[] getAllUserkeyKeys();

    private static class FileKeystore extends Keystore {
        private static final String SERVICE_NAME = "keystore";
        private static final String LIST_CERTIFICATES = "listcerts";
        private static final String LIST_USERKEYS = "listuserkeys";
        private static final String PATH = "/data/misc/keystore/";
        private static final String USERKEY_PATH = PATH + "userkeys/";
        private static final String CERT_PATH = PATH + "certs/";
        private static final ServiceCommand mServiceCommand =
                new ServiceCommand(SERVICE_NAME);

        @Override
        public String getUserkey(String key) {
            return USERKEY_PATH + key;
        }

        @Override
        public String getCertificate(String key) {
            return CERT_PATH + key;
        }

        /**
         * Returns the array of the certificate names in keystore if successful.
         * Or return an empty array if error.
         *
         * @return array of the certificates
         */
        @Override
        public String[] getAllCertificateKeys() {
            try {
                String result = mServiceCommand.execute(LIST_CERTIFICATES);
                if (result != null) return result.split("\\s+");
                return NOTFOUND;
            } catch (NumberFormatException ex) {
                return NOTFOUND;
            }
        }

        /**
         * Returns the array of the names of private keys in keystore if successful.
         * Or return an empty array if errors.
         *
         * @return array of the user keys
         */
        @Override
        public String[] getAllUserkeyKeys() {
            try {
                String result = mServiceCommand.execute(LIST_USERKEYS);
                if (result != null) return result.split("\\s+");
                return NOTFOUND;
            } catch (NumberFormatException ex) {
                return NOTFOUND;
            }
        }
    }
}
