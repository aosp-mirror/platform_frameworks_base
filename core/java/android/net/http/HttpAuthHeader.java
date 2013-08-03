/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.net.http;

import java.util.Locale;

/**
 * HttpAuthHeader: a class to store HTTP authentication-header parameters.
 * For more information, see: RFC 2617: HTTP Authentication.
 * 
 * {@hide}
 */
public class HttpAuthHeader {
    /**
     * Possible HTTP-authentication header tokens to search for:
     */
    public final static String BASIC_TOKEN = "Basic";
    public final static String DIGEST_TOKEN = "Digest";

    private final static String REALM_TOKEN = "realm";
    private final static String NONCE_TOKEN = "nonce";
    private final static String STALE_TOKEN = "stale";
    private final static String OPAQUE_TOKEN = "opaque";
    private final static String QOP_TOKEN = "qop";
    private final static String ALGORITHM_TOKEN = "algorithm";

    /**
     * An authentication scheme. We currently support two different schemes:
     * HttpAuthHeader.BASIC  - basic, and
     * HttpAuthHeader.DIGEST - digest (algorithm=MD5, QOP="auth").
     */
    private int mScheme;

    public static final int UNKNOWN = 0;
    public static final int BASIC = 1;
    public static final int DIGEST = 2;

    /**
     * A flag, indicating that the previous request from the client was
     * rejected because the nonce value was stale. If stale is TRUE
     * (case-insensitive), the client may wish to simply retry the request
     * with a new encrypted response, without reprompting the user for a
     * new username and password.
     */
    private boolean mStale;

    /**
     * A string to be displayed to users so they know which username and
     * password to use.
     */
    private String mRealm;

    /**
     * A server-specified data string which should be uniquely generated
     * each time a 401 response is made.
     */
    private String mNonce;

    /**
     * A string of data, specified by the server, which should be returned
     *  by the client unchanged in the Authorization header of subsequent
     * requests with URIs in the same protection space.
     */
    private String mOpaque;

    /**
     * This directive is optional, but is made so only for backward
     * compatibility with RFC 2069 [6]; it SHOULD be used by all
     * implementations compliant with this version of the Digest scheme.
     * If present, it is a quoted string of one or more tokens indicating
     * the "quality of protection" values supported by the server.  The
     * value "auth" indicates authentication; the value "auth-int"
     * indicates authentication with integrity protection.
     */
    private String mQop;

    /**
     * A string indicating a pair of algorithms used to produce the digest
     * and a checksum. If this is not present it is assumed to be "MD5".
     */
    private String mAlgorithm;

    /**
     * Is this authentication request a proxy authentication request?
     */
    private boolean mIsProxy;

    /**
     * Username string we get from the user.
     */
    private String mUsername;

    /**
     * Password string we get from the user.
     */
    private String mPassword;

    /**
     * Creates a new HTTP-authentication header object from the
     * input header string.
     * The header string is assumed to contain parameters of at
     * most one authentication-scheme (ensured by the caller).
     */
    public HttpAuthHeader(String header) {
        if (header != null) {
            parseHeader(header);
        }
    }

    /**
     * @return True iff this is a proxy authentication header.
     */
    public boolean isProxy() {
        return mIsProxy;
    }

    /**
     * Marks this header as a proxy authentication header.
     */
    public void setProxy() {
        mIsProxy = true;
    }

    /**
     * @return The username string.
     */
    public String getUsername() {
        return mUsername;
    }

    /**
     * Sets the username string.
     */
    public void setUsername(String username) {
        mUsername = username;
    }

    /**
     * @return The password string.
     */
    public String getPassword() {
        return mPassword;
    }

    /**
     * Sets the password string.
     */
    public void setPassword(String password) {
        mPassword = password;
    }

    /**
     * @return True iff this is the  BASIC-authentication request.
     */
    public boolean isBasic () {
        return mScheme == BASIC;
    }

    /**
     * @return True iff this is the DIGEST-authentication request.
     */
    public boolean isDigest() {
        return mScheme == DIGEST;
    }

    /**
     * @return The authentication scheme requested. We currently
     * support two schemes:
     * HttpAuthHeader.BASIC  - basic, and
     * HttpAuthHeader.DIGEST - digest (algorithm=MD5, QOP="auth").
     */
    public int getScheme() {
        return mScheme;
    }

    /**
     * @return True if indicating that the previous request from
     * the client was rejected because the nonce value was stale.
     */
    public boolean getStale() {
        return mStale;
    }

    /**
     * @return The realm value or null if there is none.
     */
    public String getRealm() {
        return mRealm;
    }

    /**
     * @return The nonce value or null if there is none.
     */
    public String getNonce() {
        return mNonce;
    }

    /**
     * @return The opaque value or null if there is none.
     */
    public String getOpaque() {
        return mOpaque;
    }

    /**
     * @return The QOP ("quality-of_protection") value or null if
     * there is none. The QOP value is always lower-case.
     */
    public String getQop() {
        return mQop;
    }

    /**
     * @return The name of the algorithm used or null if there is
     * none. By default, MD5 is used.
     */
    public String getAlgorithm() {
        return mAlgorithm;
    }

    /**
     * @return True iff the authentication scheme requested by the
     * server is supported; currently supported schemes:
     * BASIC,
     * DIGEST (only algorithm="md5", no qop or qop="auth).
     */
    public boolean isSupportedScheme() {
        // it is a good idea to enforce non-null realms!
        if (mRealm != null) {
            if (mScheme == BASIC) {
                return true;
            } else {
                if (mScheme == DIGEST) {
                    return
                        mAlgorithm.equals("md5") &&
                        (mQop == null || mQop.equals("auth"));
                }
            }
        }

        return false;
    }

    /**
     * Parses the header scheme name and then scheme parameters if
     * the scheme is supported.
     */
    private void parseHeader(String header) {
        if (HttpLog.LOGV) {
            HttpLog.v("HttpAuthHeader.parseHeader(): header: " + header);
        }

        if (header != null) {
            String parameters = parseScheme(header);
            if (parameters != null) {
                // if we have a supported scheme
                if (mScheme != UNKNOWN) {
                    parseParameters(parameters);
                }
            }
        }
    }

    /**
     * Parses the authentication scheme name. If we have a Digest
     * scheme, sets the algorithm value to the default of MD5.
     * @return The authentication scheme parameters string to be
     * parsed later (if the scheme is supported) or null if failed
     * to parse the scheme (the header value is null?).
     */
    private String parseScheme(String header) {
        if (header != null) {
            int i = header.indexOf(' ');
            if (i >= 0) {
                String scheme = header.substring(0, i).trim();
                if (scheme.equalsIgnoreCase(DIGEST_TOKEN)) {
                    mScheme = DIGEST;

                    // md5 is the default algorithm!!!
                    mAlgorithm = "md5";
                } else {
                    if (scheme.equalsIgnoreCase(BASIC_TOKEN)) {
                        mScheme = BASIC;
                    }
                }

                return header.substring(i + 1);
            }
        }

        return null;
    }

    /**
     * Parses a comma-separated list of authentification scheme
     * parameters.
     */
    private void parseParameters(String parameters) {
        if (HttpLog.LOGV) {
            HttpLog.v("HttpAuthHeader.parseParameters():" +
                      " parameters: " + parameters);
        }

        if (parameters != null) {
            int i;
            do {
                i = parameters.indexOf(',');
                if (i < 0) {
                    // have only one parameter
                    parseParameter(parameters);
                } else {
                    parseParameter(parameters.substring(0, i));
                    parameters = parameters.substring(i + 1);
                }
            } while (i >= 0);
        }
    }

    /**
     * Parses a single authentication scheme parameter. The parameter
     * string is expected to follow the format: PARAMETER=VALUE.
     */
    private void parseParameter(String parameter) {
        if (parameter != null) {
            // here, we are looking for the 1st occurence of '=' only!!!
            int i = parameter.indexOf('=');
            if (i >= 0) {
                String token = parameter.substring(0, i).trim();
                String value =
                    trimDoubleQuotesIfAny(parameter.substring(i + 1).trim());

                if (HttpLog.LOGV) {
                    HttpLog.v("HttpAuthHeader.parseParameter():" +
                              " token: " + token +
                              " value: " + value);
                }

                if (token.equalsIgnoreCase(REALM_TOKEN)) {
                    mRealm = value;
                } else {
                    if (mScheme == DIGEST) {
                        parseParameter(token, value);
                    }
                }
            }
        }
    }

    /**
     * If the token is a known parameter name, parses and initializes
     * the token value.
     */
    private void parseParameter(String token, String value) {
        if (token != null && value != null) {
            if (token.equalsIgnoreCase(NONCE_TOKEN)) {
                mNonce = value;
                return;
            }

            if (token.equalsIgnoreCase(STALE_TOKEN)) {
                parseStale(value);
                return;
            }

            if (token.equalsIgnoreCase(OPAQUE_TOKEN)) {
                mOpaque = value;
                return;
            }

            if (token.equalsIgnoreCase(QOP_TOKEN)) {
                mQop = value.toLowerCase(Locale.ROOT);
                return;
            }

            if (token.equalsIgnoreCase(ALGORITHM_TOKEN)) {
                mAlgorithm = value.toLowerCase(Locale.ROOT);
                return;
            }
        }
    }

    /**
     * Parses and initializes the 'stale' paramer value. Any value
     * different from case-insensitive "true" is considered "false".
     */
    private void parseStale(String value) {
        if (value != null) {
            if (value.equalsIgnoreCase("true")) {
                mStale = true;
            }
        }
    }

    /**
     * Trims double-quotes around a parameter value if there are any.
     * @return The string value without the outermost pair of double-
     * quotes or null if the original value is null.
     */
    static private String trimDoubleQuotesIfAny(String value) {
        if (value != null) {
            int len = value.length();
            if (len > 2 &&
                value.charAt(0) == '\"' && value.charAt(len - 1) == '\"') {
                return value.substring(1, len - 1);
            }
        }

        return value;
    }
}
