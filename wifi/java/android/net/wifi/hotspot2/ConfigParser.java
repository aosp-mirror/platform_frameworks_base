/**
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.net.wifi.hotspot2;

import android.net.wifi.hotspot2.omadm.PpsMoParser;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Utility class for building PasspointConfiguration from an installation file.
 */
public final class ConfigParser {
    private static final String TAG = "ConfigParser";

    // Header names.
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";

    // MIME types.
    private static final String TYPE_MULTIPART_MIXED = "multipart/mixed";
    private static final String TYPE_WIFI_CONFIG = "application/x-wifi-config";
    private static final String TYPE_PASSPOINT_PROFILE = "application/x-passpoint-profile";
    private static final String TYPE_CA_CERT = "application/x-x509-ca-cert";
    private static final String TYPE_PKCS12 = "application/x-pkcs12";

    private static final String ENCODING_BASE64 = "base64";
    private static final String BOUNDARY = "boundary=";

    /**
     * Class represent a MIME (Multipurpose Internet Mail Extension) part.
     */
    private static class MimePart {
        /**
         * Content type of the part.
         */
        public String type = null;

        /**
         * Decoded data.
         */
        public byte[] data = null;

        /**
         * Flag indicating if this is the last part (ending with --{boundary}--).
         */
        public boolean isLast = false;
    }

    /**
     * Class represent the MIME (Multipurpose Internet Mail Extension) header.
     */
    private static class MimeHeader {
        /**
         * Content type.
         */
        public String contentType = null;

        /**
         * Boundary string (optional), only applies for the outter MIME header.
         */
        public String boundary = null;

        /**
         * Encoding type.
         */
        public String encodingType = null;
    }

    /**
     * @hide
     */
    public ConfigParser() {}

    /**
     * Parse the Hotspot 2.0 Release 1 configuration data into a {@link PasspointConfiguration}
     * object.  The configuration data is a base64 encoded MIME multipart data.  Below is
     * the format of the decoded message:
     *
     * Content-Type: multipart/mixed; boundary={boundary}
     * Content-Transfer-Encoding: base64
     * [Skip uninterested headers]
     *
     * --{boundary}
     * Content-Type: application/x-passpoint-profile
     * Content-Transfer-Encoding: base64
     *
     * [base64 encoded Passpoint profile data]
     * --{boundary}
     * Content-Type: application/x-x509-ca-cert
     * Content-Transfer-Encoding: base64
     *
     * [base64 encoded X509 CA certificate data]
     * --{boundary}
     * Content-Type: application/x-pkcs12
     * Content-Transfer-Encoding: base64
     *
     * [base64 encoded PKCS#12 ASN.1 structure containing client certificate chain]
     * --{boundary}
     *
     * @param mimeType MIME type of the encoded data.
     * @param data A base64 encoded MIME multipart message containing the Passpoint profile
     *             (required), CA (Certificate Authority) certificate (optional), and client
     *             certificate chain (optional).
     * @return {@link PasspointConfiguration}
     */
    public static PasspointConfiguration parsePasspointConfig(String mimeType, byte[] data) {
        // Verify MIME type.
        if (!TextUtils.equals(mimeType, TYPE_WIFI_CONFIG)) {
            Log.e(TAG, "Unexpected MIME type: " + mimeType);
            return null;
        }

        try {
            // Decode the data.
            byte[] decodedData = Base64.decode(new String(data, StandardCharsets.ISO_8859_1),
                    Base64.DEFAULT);
            Map<String, byte[]> mimeParts = parseMimeMultipartMessage(new LineNumberReader(
                    new InputStreamReader(new ByteArrayInputStream(decodedData),
                            StandardCharsets.ISO_8859_1)));
            return createPasspointConfig(mimeParts);
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "Failed to parse installation file: " + e.getMessage());
            return null;
        }
    }

    /**
     * Create a {@link PasspointConfiguration} object from list of MIME (Multipurpose Internet
     * Mail Extension) parts.
     *
     * @param mimeParts Map of content type and content data.
     * @return {@link PasspointConfiguration}
     * @throws IOException
     */
    private static PasspointConfiguration createPasspointConfig(Map<String, byte[]> mimeParts)
            throws IOException {
        byte[] profileData = mimeParts.get(TYPE_PASSPOINT_PROFILE);
        if (profileData == null) {
            throw new IOException("Missing Passpoint Profile");
        }

        PasspointConfiguration config = PpsMoParser.parseMoText(new String(profileData));
        if (config == null) {
            throw new IOException("Failed to parse Passpoint profile");
        }

        // Credential is needed for storing the certificates and private client key.
        if (config.getCredential() == null) {
            throw new IOException("Passpoint profile missing credential");
        }

        // Parse CA (Certificate Authority) certificate.
        byte[] caCertData = mimeParts.get(TYPE_CA_CERT);
        if (caCertData != null) {
            try {
                config.getCredential().setCaCertificate(parseCACert(caCertData));
            } catch (CertificateException e) {
                throw new IOException("Failed to parse CA Certificate");
            }
        }

        // Parse PKCS12 data for client private key and certificate chain.
        byte[] pkcs12Data = mimeParts.get(TYPE_PKCS12);
        if (pkcs12Data != null) {
            try {
                Pair<PrivateKey, List<X509Certificate>> clientKey = parsePkcs12(pkcs12Data);
                config.getCredential().setClientPrivateKey(clientKey.first);
                config.getCredential().setClientCertificateChain(
                        clientKey.second.toArray(new X509Certificate[clientKey.second.size()]));
            } catch(GeneralSecurityException | IOException e) {
                throw new IOException("Failed to parse PCKS12 string");
            }
        }
        return config;
    }

    /**
     * Parse a MIME (Multipurpose Internet Mail Extension) multipart message from the given
     * input stream.
     *
     * @param in The input stream for reading the message data
     * @return A map of a content type and content data pair
     * @throws IOException
     */
    private static Map<String, byte[]> parseMimeMultipartMessage(LineNumberReader in)
            throws IOException {
        // Parse the outer MIME header.
        MimeHeader header = parseHeaders(in);
        if (!TextUtils.equals(header.contentType, TYPE_MULTIPART_MIXED)) {
            throw new IOException("Invalid content type: " + header.contentType);
        }
        if (TextUtils.isEmpty(header.boundary)) {
            throw new IOException("Missing boundary string");
        }
        if (!TextUtils.equals(header.encodingType, ENCODING_BASE64)) {
            throw new IOException("Unexpected encoding: " + header.encodingType);
        }

        // Read pass the first boundary string.
        for (;;) {
            String line = in.readLine();
            if (line == null) {
                throw new IOException("Unexpected EOF before first boundary @ " +
                        in.getLineNumber());
            }
            if (line.equals("--" + header.boundary)) {
                break;
            }
        }

        // Parse each MIME part.
        Map<String, byte[]> mimeParts = new HashMap<>();
        boolean isLast = false;
        do {
            MimePart mimePart = parseMimePart(in, header.boundary);
            mimeParts.put(mimePart.type, mimePart.data);
            isLast = mimePart.isLast;
        } while(!isLast);
        return mimeParts;
    }

    /**
     * Parse a MIME (Multipurpose Internet Mail Extension) part.  We expect the data to
     * be encoded in base64.
     *
     * @param in Input stream to read the data from
     * @param boundary Boundary string indicate the end of the part
     * @return {@link MimePart}
     * @throws IOException
     */
    private static MimePart parseMimePart(LineNumberReader in, String boundary)
            throws IOException {
        MimeHeader header = parseHeaders(in);
        // Expect encoding type to be base64.
        if (!TextUtils.equals(header.encodingType, ENCODING_BASE64)) {
            throw new IOException("Unexpected encoding type: " + header.encodingType);
        }

        // Check for a valid content type.
        if (!TextUtils.equals(header.contentType, TYPE_PASSPOINT_PROFILE) &&
                !TextUtils.equals(header.contentType, TYPE_CA_CERT) &&
                !TextUtils.equals(header.contentType, TYPE_PKCS12)) {
            throw new IOException("Unexpected content type: " + header.contentType);
        }

        StringBuilder text = new StringBuilder();
        boolean isLast = false;
        String partBoundary = "--" + boundary;
        String endBoundary = partBoundary + "--";
        for (;;) {
            String line = in.readLine();
            if (line == null) {
                throw new IOException("Unexpected EOF file in body @ " + in.getLineNumber());
            }
            // Check for boundary line.
            if (line.startsWith(partBoundary)) {
                if (line.equals(endBoundary)) {
                    isLast = true;
                }
                break;
            }
            text.append(line);
        }

        MimePart part = new MimePart();
        part.type = header.contentType;
        part.data = Base64.decode(text.toString(), Base64.DEFAULT);
        part.isLast = isLast;
        return part;
    }

    /**
     * Parse a MIME (Multipurpose Internet Mail Extension) header from the input stream.
     * @param in Input stream to read from.
     * @return {@link MimeHeader}
     * @throws IOException
     */
    private static MimeHeader parseHeaders(LineNumberReader in)
            throws IOException {
        MimeHeader header = new MimeHeader();

        // Read the header from the input stream.
        Map<String, String> headers = readHeaders(in);

        // Parse each header.
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            switch (entry.getKey()) {
                case CONTENT_TYPE:
                    Pair<String, String> value = parseContentType(entry.getValue());
                    header.contentType = value.first;
                    header.boundary = value.second;
                    break;
                case CONTENT_TRANSFER_ENCODING:
                    header.encodingType = entry.getValue();
                    break;
                default:
                    Log.d(TAG, "Ignore header: " + entry.getKey());
                    break;
            }
        }
        return header;
    }

    /**
     * Parse the Content-Type header value.  The value will contain the content type string and
     * an optional boundary string separated by a ";".  Below are examples of valid Content-Type
     * header value:
     *   multipart/mixed; boundary={boundary}
     *   application/x-passpoint-profile
     *
     * @param contentType The Content-Type value string
     * @return A pair of content type and boundary string
     * @throws IOException
     */
    private static Pair<String, String> parseContentType(String contentType) throws IOException {
        String[] attributes = contentType.split(";");
        String type = null;
        String boundary = null;

        if (attributes.length < 1) {
            throw new IOException("Invalid Content-Type: " + contentType);
        }

        // The type is always the first attribute.
        type = attributes[0].trim();
        // Look for boundary string from the rest of the attributes.
        for (int i = 1; i < attributes.length; i++) {
            String attribute = attributes[i].trim();
            if (!attribute.startsWith(BOUNDARY)) {
                Log.d(TAG, "Ignore Content-Type attribute: " + attributes[i]);
                continue;
            }
            boundary = attribute.substring(BOUNDARY.length());
            // Remove the leading and trailing quote if present.
            if (boundary.length() > 1 && boundary.startsWith("\"") && boundary.endsWith("\"")) {
                boundary = boundary.substring(1, boundary.length()-1);
            }
        }

        return new Pair<String, String>(type, boundary);
    }

    /**
     * Read the headers from the given input stream.  The header section is terminated by
     * an empty line.
     *
     * @param in The input stream to read from
     * @return Map of key-value pairs.
     * @throws IOException
     */
    private static Map<String, String> readHeaders(LineNumberReader in)
            throws IOException {
        Map<String, String> headers = new HashMap<>();
        String line;
        String name = null;
        StringBuilder value = null;
        for (;;) {
            line = in.readLine();
            if (line == null) {
                throw new IOException("Missing line @ " + in.getLineNumber());
            }

            // End of headers section.
            if (line.length() == 0 || line.trim().length() == 0) {
                // Save the previous header line.
                if (name != null) {
                    headers.put(name, value.toString());
                }
                break;
            }

            int nameEnd = line.indexOf(':');
            if (nameEnd < 0) {
                if (value != null) {
                    // Continuation line for the header value.
                    value.append(' ').append(line.trim());
                } else {
                    throw new IOException("Bad header line: '" + line + "' @ " +
                            in.getLineNumber());
                }
            } else {
                // New header line detected, make sure it doesn't start with a whitespace.
                if (Character.isWhitespace(line.charAt(0))) {
                    throw new IOException("Illegal blank prefix in header line '" + line +
                            "' @ " + in.getLineNumber());
                }

                if (name != null) {
                    // Save the previous header line.
                    headers.put(name, value.toString());
                }

                // Setup the current header line.
                name = line.substring(0, nameEnd).trim();
                value = new StringBuilder();
                value.append(line.substring(nameEnd+1).trim());
            }
        }
        return headers;
    }

    /**
     * Parse a CA (Certificate Authority) certificate data and convert it to a
     * X509Certificate object.
     *
     * @param octets Certificate data
     * @return X509Certificate
     * @throws CertificateException
     */
    private static X509Certificate parseCACert(byte[] octets) throws CertificateException {
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(octets));
    }

    private static Pair<PrivateKey, List<X509Certificate>> parsePkcs12(byte[] octets)
            throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ByteArrayInputStream in = new ByteArrayInputStream(octets);
        ks.load(in, new char[0]);
        in.close();

        // Only expects one set of key and certificate chain.
        if (ks.size() != 1) {
            throw new IOException("Unexpected key size: " + ks.size());
        }

        String alias = ks.aliases().nextElement();
        if (alias == null) {
            throw new IOException("No alias found");
        }

        PrivateKey clientKey = (PrivateKey) ks.getKey(alias, null);
        List<X509Certificate> clientCertificateChain = null;
        Certificate[] chain = ks.getCertificateChain(alias);
        if (chain != null) {
            clientCertificateChain = new ArrayList<>();
            for (Certificate certificate : chain) {
                if (!(certificate instanceof X509Certificate)) {
                    throw new IOException("Unexpceted certificate type: " +
                            certificate.getClass());
                }
                clientCertificateChain.add((X509Certificate) certificate);
            }
        }
        return new Pair<PrivateKey, List<X509Certificate>>(clientKey, clientCertificateChain);
    }
}
