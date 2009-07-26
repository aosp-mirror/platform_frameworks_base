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

    // Keystore States
    public static final int BOOTUP = 0;
    public static final int UNINITIALIZED = 1;
    public static final int LOCKED = 2;
    public static final int UNLOCKED = 3;

    /**
     */
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

    // TODO: for migrating to the mini-keystore, clean up from here
    /**
     */
    public abstract String getCaCertificate(String key);

    /**
     */
    public abstract String getUserCertificate(String key);

    /**
     */
    public abstract String getUserPrivateKey(String key);

    /**
     * Returns the array of the certificate keynames in keystore if successful.
     * Or return an empty array if error.
     *
     * @return array of the certificate keynames
     */
    public abstract String[] getAllUserCertificateKeys();

    /**
     */
    public abstract String[] getAllCaCertificateKeys();

    /**
     */
    public abstract String[] getSupportedKeyStrenghs();

    /**
     * Generates a key pair and returns the certificate request.
     * @param keyStrengthIndex index to the array of supported key strengths
     * @param challenge the challenge message in the keygen tag
     * @param organizations the organization string, e.g.,
     *      "/C=US/ST={state}/L={city}/O={company}/OU={app}/CN={hostname}"
     * @return the certificate request
     */
    public abstract String generateKeyPair(
            int keyStrengthIndex, String challenge, String organizations);

    public abstract void addCertificate(byte[] cert);
    // to here

    private static class FileKeystore extends Keystore {
        private static final String SERVICE_NAME = "keystore";
        private static final String CA_CERTIFICATE = "CaCertificate";
        private static final String USER_CERTIFICATE = "UserCertificate";
        private static final String USER_KEY = "UserPrivateKey";
        private static final String COMMAND_DELIMITER = " ";
        private static final ServiceCommand mServiceCommand =
                new ServiceCommand(SERVICE_NAME);

        // TODO: for migrating to the mini-keystore, start from here
        @Override
        public String getUserPrivateKey(String key) {
            return "";
        }

        @Override
        public String getUserCertificate(String key) {
            return "";
        }

        @Override
        public String getCaCertificate(String key) {
            return "";
        }

        @Override
        public String[] getAllUserCertificateKeys() {
            return new String[0];
        }

        @Override
        public String[] getAllCaCertificateKeys() {
          return new String[0];
        }

        @Override
        public String[] getSupportedKeyStrenghs() {
            // TODO: real implementation
            return new String[] {"High Grade", "Medium Grade"};
        }

        @Override
        public String generateKeyPair(int keyStrengthIndex, String challenge,
                String organizations) {
            // TODO: real implementation
            return "-----BEGIN CERTIFICATE REQUEST-----"
                    + "\nMIICzjCCAbYCAQAwgYgxCzAJBgNVBAYTAlVTMRMwEQYDVQQIEwpDYWxpZm9ybmlh"
                    + "\nMRYwFAYDVQQHEw1Nb3VudGFpbiBWaWV3MRMwEQYDVQQKEwpHb29nbGUgSW5jMRYw"
                    + "\nFAYDVQQLEw1SZW1vdGUgQWNjZXNzMRAwDgYDVQQLEwdHbGFwdG9wMQ0wCwYDVQQD"
                    + "\nEwR0ZXN0MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAznwy7a16O35u"
                    + "\nODLQOw6yHAxozrrX1J+c0reiIh8GYohwKrBedFnQ/FnTls6bxY4fNHD+SZvFFgvU"
                    + "\nECBFOfRmRm7AFo51qT0t2a8qgvDLM6L1qGkmy94W28Q3OlcpF2QianHYdjyGT+Ac"
                    + "\nYDek1Zi/E/mdPzuVM/K8tkB7n8ktC0PTm1ZtdMRauE5R0WrEhWuF6In/2gy1Q/Zh"
                    + "\noy7/zQqpbPl2ouulvkx1Y3OXHM6XPNFLoHS1gH0HyAuBUokO0QmetRn6ngJSvz7e"
                    + "\nVD7QYRppGp+g4BxqaV9XSxhaaKrMs4PAld9enV51X9qjvjCRBve2QxtuJgMfGJdU"
                    + "\njGr/JweZoQIDAQABoAAwDQYJKoZIhvcNAQEFBQADggEBADtxOtEseoLOVYh6sh4b"
                    + "\nWCdngK87uHn2bdGipFwKdNTxQDdxNQLAKdoGYIfbVsC1cDgFiufeNwVukxxymdnm"
                    + "\nk0GGK+0O0tZKENv8ysgfbgEsHpJH9FoR5Y5XEq1etejkcgCp59dyhrSk0DLyVm0D"
                    + "\nIfTC/nsK95H7AAGOkbbDFo2otyLNNrthYncQ9diAG0UzzLacA+86JXZmD3HyC48u"
                    + "\nI9hsivVnTTfl9afcfVAhfxbQ6HgkhZZjbjFjfABSd4v8wKlAAqK58VxCajNVOVcV"
                    + "\ncCzOWf6NpE7xEHCf32i8bWDP6hi0WgQcdpQwnZNKhhTLGNb23Uty6HYlJhbxexC7"
                    + "\nUoM="
                    + "\n-----END CERTIFICATE REQUEST-----";
        }

        @Override
        public void addCertificate(byte[] cert) {
            // TODO: real implementation
        }

        // to here

        @Override
        public int lock() {
            Reply result = mServiceCommand.execute(ServiceCommand.LOCK, null);
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
            Reply result = mServiceCommand.execute(ServiceCommand.GET_STATE,
                    null);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public int changePassword(String oldPassword, String newPassword) {
            Reply result = mServiceCommand.execute(ServiceCommand.PASSWD,
                    oldPassword + " " + newPassword);
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
                    namespace + " " + keyname + " " + value);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public String get(String namespace, String keyname) {
            Reply result = mServiceCommand.execute(ServiceCommand.GET_KEY,
                    namespace + " " + keyname);
            return (result != null) ? ((result.returnCode != 0) ? null :
                    new String(result.data, 0, result.len)) : null;
        }

        @Override
        public int remove(String namespace, String keyname) {
            Reply result = mServiceCommand.execute(ServiceCommand.REMOVE_KEY,
                    namespace + " " + keyname);
            return (result != null) ? result.returnCode : -1;
        }

        @Override
        public int reset() {
            Reply result = mServiceCommand.execute(ServiceCommand.RESET, null);
            return (result != null) ? result.returnCode : -1;
        }
    }
}
