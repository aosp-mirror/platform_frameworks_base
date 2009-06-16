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
    }
}
