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

    // for compatiblity, start from here
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

    // to here

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

    public abstract void addCertificate(String cert);

    private static class FileKeystore extends Keystore {
        private static final String SERVICE_NAME = "keystore";
        private static final String LIST_CA_CERTIFICATES = "listcacerts";
        private static final String LIST_USER_CERTIFICATES = "listusercerts";
        private static final String GET_CA_CERTIFICATE = "getcacert";
        private static final String GET_USER_CERTIFICATE = "getusercert";
        private static final String GET_USER_KEY = "getuserkey";
        private static final String ADD_CA_CERTIFICATE = "addcacert";
        private static final String ADD_USER_CERTIFICATE = "addusercert";
        private static final String ADD_USER_KEY = "adduserkey";
        private static final String COMMAND_DELIMITER = "\t";
        private static final ServiceCommand mServiceCommand =
                new ServiceCommand(SERVICE_NAME);

        // for compatiblity, start from here

        private static final String LIST_CERTIFICATES = "listcerts";
        private static final String LIST_USERKEYS = "listuserkeys";
        private static final String PATH = "/data/misc/keystore/";
        private static final String USERKEY_PATH = PATH + "userkeys/";
        private static final String CERT_PATH = PATH + "certs/";

        @Override
        public String getUserkey(String key) {
            return USERKEY_PATH + key;
        }

        @Override
        public String getCertificate(String key) {
            return CERT_PATH + key;
        }

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

        // to here

        @Override
        public String getUserPrivateKey(String key) {
            return mServiceCommand.execute(
                    GET_USER_KEY + COMMAND_DELIMITER + key);
        }

        @Override
        public String getUserCertificate(String key) {
            return mServiceCommand.execute(
                    GET_USER_CERTIFICATE + COMMAND_DELIMITER + key);
        }

        @Override
        public String getCaCertificate(String key) {
            return mServiceCommand.execute(
                    GET_CA_CERTIFICATE + COMMAND_DELIMITER + key);
        }

        @Override
        public String[] getAllUserCertificateKeys() {
            try {
                String result = mServiceCommand.execute(LIST_USER_CERTIFICATES);
                if (result != null) return result.split("\\s+");
                return NOTFOUND;
            } catch (NumberFormatException ex) {
                return NOTFOUND;
            }
        }

        @Override
        public String[] getAllCaCertificateKeys() {
            try {
                String result = mServiceCommand.execute(LIST_CA_CERTIFICATES);
                if (result != null) return result.split("\\s+");
                return NOTFOUND;
            } catch (NumberFormatException ex) {
                return NOTFOUND;
            }
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
        public void addCertificate(String cert) {
            // TODO: real implementation
        }

        private boolean addUserCertificate(String key, String certificate,
                String privateKey) {
            if(mServiceCommand.execute(ADD_USER_CERTIFICATE + COMMAND_DELIMITER
                    + key + COMMAND_DELIMITER + certificate) != null) {
                if (mServiceCommand.execute(ADD_USER_KEY + COMMAND_DELIMITER
                        + key + COMMAND_DELIMITER + privateKey) != null) {
                    return true;
                }
            }
            return false;
        }

        private boolean addCaCertificate(String key, String content) {
            if (mServiceCommand.execute(ADD_CA_CERTIFICATE + COMMAND_DELIMITER
                    + key + COMMAND_DELIMITER + content) != null) {
                return true;
            }
            return false;
        }

    }
}
