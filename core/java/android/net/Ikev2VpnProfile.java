/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.net;

import static android.net.PlatformVpnProfile.TYPE_IKEV2_IPSEC_PSK;
import static android.net.PlatformVpnProfile.TYPE_IKEV2_IPSEC_RSA;
import static android.net.PlatformVpnProfile.TYPE_IKEV2_IPSEC_USER_PASS;

import static com.android.internal.annotations.VisibleForTesting.Visibility;
import static com.android.internal.util.Preconditions.checkStringNotEmpty;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.content.pm.PackageManager;
import android.os.Process;
import android.security.Credentials;
import android.security.KeyStore;
import android.security.keystore.AndroidKeyStoreProvider;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.net.VpnProfile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The Ikev2VpnProfile is a configuration for the platform setup of IKEv2/IPsec VPNs.
 *
 * <p>Together with VpnManager, this allows apps to provision IKEv2/IPsec VPNs that do not require
 * the VPN app to constantly run in the background.
 *
 * @see VpnManager
 * @see <a href="https://tools.ietf.org/html/rfc7296#section-3.3.2">RFC 7296 - Internet Key
 *     Exchange, Version 2 (IKEv2)</a>
 */
public final class Ikev2VpnProfile extends PlatformVpnProfile {
    /** Prefix for when a Private Key is an alias to look for in KeyStore @hide */
    public static final String PREFIX_KEYSTORE_ALIAS = "KEYSTORE_ALIAS:";
    /** Prefix for when a Private Key is stored directly in the profile @hide */
    public static final String PREFIX_INLINE = "INLINE:";

    private static final String MISSING_PARAM_MSG_TMPL = "Required parameter was not provided: %s";
    private static final String EMPTY_CERT = "";

    /** @hide */
    public static final List<String> DEFAULT_ALGORITHMS =
            Collections.unmodifiableList(Arrays.asList(
                    IpSecAlgorithm.CRYPT_AES_CBC,
                    IpSecAlgorithm.AUTH_HMAC_SHA256,
                    IpSecAlgorithm.AUTH_HMAC_SHA384,
                    IpSecAlgorithm.AUTH_HMAC_SHA512,
                    IpSecAlgorithm.AUTH_CRYPT_AES_GCM));

    @NonNull private final String mServerAddr;
    @NonNull private final String mUserIdentity;

    // PSK authentication
    @Nullable private final byte[] mPresharedKey;

    // Username/Password, RSA authentication
    @Nullable private final X509Certificate mServerRootCaCert;

    // Username/Password authentication
    @Nullable private final String mUsername;
    @Nullable private final String mPassword;

    // RSA Certificate authentication
    @Nullable private final PrivateKey mRsaPrivateKey;
    @Nullable private final X509Certificate mUserCert;

    @Nullable private final ProxyInfo mProxyInfo;
    @NonNull private final List<String> mAllowedAlgorithms;
    private final boolean mIsBypassable; // Defaults in builder
    private final boolean mIsMetered; // Defaults in builder
    private final int mMaxMtu; // Defaults in builder
    private final boolean mIsRestrictedToTestNetworks;

    private Ikev2VpnProfile(
            int type,
            @NonNull String serverAddr,
            @NonNull String userIdentity,
            @Nullable byte[] presharedKey,
            @Nullable X509Certificate serverRootCaCert,
            @Nullable String username,
            @Nullable String password,
            @Nullable PrivateKey rsaPrivateKey,
            @Nullable X509Certificate userCert,
            @Nullable ProxyInfo proxyInfo,
            @NonNull List<String> allowedAlgorithms,
            boolean isBypassable,
            boolean isMetered,
            int maxMtu,
            boolean restrictToTestNetworks) {
        super(type);

        checkNotNull(serverAddr, MISSING_PARAM_MSG_TMPL, "Server address");
        checkNotNull(userIdentity, MISSING_PARAM_MSG_TMPL, "User Identity");
        checkNotNull(allowedAlgorithms, MISSING_PARAM_MSG_TMPL, "Allowed Algorithms");

        mServerAddr = serverAddr;
        mUserIdentity = userIdentity;
        mPresharedKey =
                presharedKey == null ? null : Arrays.copyOf(presharedKey, presharedKey.length);
        mServerRootCaCert = serverRootCaCert;
        mUsername = username;
        mPassword = password;
        mRsaPrivateKey = rsaPrivateKey;
        mUserCert = userCert;
        mProxyInfo = new ProxyInfo(proxyInfo);

        // UnmodifiableList doesn't make a defensive copy by default.
        mAllowedAlgorithms = Collections.unmodifiableList(new ArrayList<>(allowedAlgorithms));

        mIsBypassable = isBypassable;
        mIsMetered = isMetered;
        mMaxMtu = maxMtu;
        mIsRestrictedToTestNetworks = restrictToTestNetworks;

        validate();
    }

    private void validate() {
        // Server Address not validated except to check an address was provided. This allows for
        // dual-stack servers and hostname based addresses.
        checkStringNotEmpty(mServerAddr, MISSING_PARAM_MSG_TMPL, "Server Address");
        checkStringNotEmpty(mUserIdentity, MISSING_PARAM_MSG_TMPL, "User Identity");

        // IPv6 MTU is greater; since profiles may be started by the system on IPv4 and IPv6
        // networks, the VPN must provide a link fulfilling the stricter of the two conditions
        // (at least that of the IPv6 MTU).
        if (mMaxMtu < LinkProperties.MIN_MTU_V6) {
            throw new IllegalArgumentException(
                    "Max MTU must be at least" + LinkProperties.MIN_MTU_V6);
        }

        switch (mType) {
            case TYPE_IKEV2_IPSEC_USER_PASS:
                checkNotNull(mUsername, MISSING_PARAM_MSG_TMPL, "Username");
                checkNotNull(mPassword, MISSING_PARAM_MSG_TMPL, "Password");

                if (mServerRootCaCert != null) checkCert(mServerRootCaCert);

                break;
            case TYPE_IKEV2_IPSEC_PSK:
                checkNotNull(mPresharedKey, MISSING_PARAM_MSG_TMPL, "Preshared Key");
                break;
            case TYPE_IKEV2_IPSEC_RSA:
                checkNotNull(mUserCert, MISSING_PARAM_MSG_TMPL, "User cert");
                checkNotNull(mRsaPrivateKey, MISSING_PARAM_MSG_TMPL, "RSA Private key");

                checkCert(mUserCert);
                if (mServerRootCaCert != null) checkCert(mServerRootCaCert);

                break;
            default:
                throw new IllegalArgumentException("Invalid auth method set");
        }

        validateAllowedAlgorithms(mAllowedAlgorithms);
    }

    /**
     * Validates that the allowed algorithms are a valid set for IPsec purposes
     *
     * <p>In order for the algorithm list to be a valid set, it must contain at least one algorithm
     * that provides Authentication, and one that provides Encryption. Authenticated Encryption with
     * Associated Data (AEAD) algorithms are counted as providing Authentication and Encryption.
     *
     * @param allowedAlgorithms The list to be validated
     */
    private static void validateAllowedAlgorithms(@NonNull List<String> algorithmNames) {
        VpnProfile.validateAllowedAlgorithms(algorithmNames);

        // First, make sure no insecure algorithms were proposed.
        if (algorithmNames.contains(IpSecAlgorithm.AUTH_HMAC_MD5)
                || algorithmNames.contains(IpSecAlgorithm.AUTH_HMAC_SHA1)) {
            throw new IllegalArgumentException("Algorithm not supported for IKEv2 VPN profiles");
        }

        // Validate that some valid combination (AEAD or AUTH + CRYPT) is present
        if (hasAeadAlgorithms(algorithmNames) || hasNormalModeAlgorithms(algorithmNames)) {
            return;
        }

        throw new IllegalArgumentException("Algorithm set missing support for Auth, Crypt or both");
    }

    /**
     * Checks if the provided list has AEAD algorithms
     *
     * @hide
     */
    public static boolean hasAeadAlgorithms(@NonNull List<String> algorithmNames) {
        return algorithmNames.contains(IpSecAlgorithm.AUTH_CRYPT_AES_GCM);
    }

    /**
     * Checks the provided list has acceptable (non-AEAD) authentication and encryption algorithms
     *
     * @hide
     */
    public static boolean hasNormalModeAlgorithms(@NonNull List<String> algorithmNames) {
        final boolean hasCrypt = algorithmNames.contains(IpSecAlgorithm.CRYPT_AES_CBC);
        final boolean hasAuth = algorithmNames.contains(IpSecAlgorithm.AUTH_HMAC_SHA256)
                || algorithmNames.contains(IpSecAlgorithm.AUTH_HMAC_SHA384)
                || algorithmNames.contains(IpSecAlgorithm.AUTH_HMAC_SHA512);

        return hasCrypt && hasAuth;
    }

    /** Retrieves the server address string. */
    @NonNull
    public String getServerAddr() {
        return mServerAddr;
    }

    /** Retrieves the user identity. */
    @NonNull
    public String getUserIdentity() {
        return mUserIdentity;
    }

    /**
     * Retrieves the pre-shared key.
     *
     * <p>May be null if the profile is not using Pre-shared key authentication.
     */
    @Nullable
    public byte[] getPresharedKey() {
        return mPresharedKey == null ? null : Arrays.copyOf(mPresharedKey, mPresharedKey.length);
    }

    /**
     * Retrieves the certificate for the server's root CA.
     *
     * <p>May be null if the profile is not using RSA Digital Signature Authentication or
     * Username/Password authentication
     */
    @Nullable
    public X509Certificate getServerRootCaCert() {
        return mServerRootCaCert;
    }

    /**
     * Retrieves the username.
     *
     * <p>May be null if the profile is not using Username/Password authentication
     */
    @Nullable
    public String getUsername() {
        return mUsername;
    }

    /**
     * Retrieves the password.
     *
     * <p>May be null if the profile is not using Username/Password authentication
     */
    @Nullable
    public String getPassword() {
        return mPassword;
    }

    /**
     * Retrieves the RSA private key.
     *
     * <p>May be null if the profile is not using RSA Digital Signature authentication
     */
    @Nullable
    public PrivateKey getRsaPrivateKey() {
        return mRsaPrivateKey;
    }

    /** Retrieves the user certificate, if any was set. */
    @Nullable
    public X509Certificate getUserCert() {
        return mUserCert;
    }

    /** Retrieves the proxy information if any was set */
    @Nullable
    public ProxyInfo getProxyInfo() {
        return mProxyInfo;
    }

    /** Returns all the algorithms allowed by this VPN profile. */
    @NonNull
    public List<String> getAllowedAlgorithms() {
        return mAllowedAlgorithms;
    }

    /** Returns whether or not the VPN profile should be bypassable. */
    public boolean isBypassable() {
        return mIsBypassable;
    }

    /** Returns whether or not the VPN profile should be always considered metered. */
    public boolean isMetered() {
        return mIsMetered;
    }

    /** Retrieves the maximum MTU set for this VPN profile. */
    public int getMaxMtu() {
        return mMaxMtu;
    }

    /**
     * Returns whether or not this VPN profile is restricted to test networks.
     *
     * @hide
     */
    public boolean isRestrictedToTestNetworks() {
        return mIsRestrictedToTestNetworks;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mType,
                mServerAddr,
                mUserIdentity,
                Arrays.hashCode(mPresharedKey),
                mServerRootCaCert,
                mUsername,
                mPassword,
                mRsaPrivateKey,
                mUserCert,
                mProxyInfo,
                mAllowedAlgorithms,
                mIsBypassable,
                mIsMetered,
                mMaxMtu,
                mIsRestrictedToTestNetworks);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Ikev2VpnProfile)) {
            return false;
        }

        final Ikev2VpnProfile other = (Ikev2VpnProfile) obj;
        return mType == other.mType
                && Objects.equals(mServerAddr, other.mServerAddr)
                && Objects.equals(mUserIdentity, other.mUserIdentity)
                && Arrays.equals(mPresharedKey, other.mPresharedKey)
                && Objects.equals(mServerRootCaCert, other.mServerRootCaCert)
                && Objects.equals(mUsername, other.mUsername)
                && Objects.equals(mPassword, other.mPassword)
                && Objects.equals(mRsaPrivateKey, other.mRsaPrivateKey)
                && Objects.equals(mUserCert, other.mUserCert)
                && Objects.equals(mProxyInfo, other.mProxyInfo)
                && Objects.equals(mAllowedAlgorithms, other.mAllowedAlgorithms)
                && mIsBypassable == other.mIsBypassable
                && mIsMetered == other.mIsMetered
                && mMaxMtu == other.mMaxMtu
                && mIsRestrictedToTestNetworks == other.mIsRestrictedToTestNetworks;
    }

    /**
     * Builds a VpnProfile instance for internal use, based on the stored IKEv2/IPsec parameters.
     *
     * <p>Redundant authentication information (from previous calls to other setAuth* methods) will
     * be discarded.
     *
     * @hide
     */
    @NonNull
    public VpnProfile toVpnProfile() throws IOException, GeneralSecurityException {
        final VpnProfile profile = new VpnProfile("" /* Key; value unused by IKEv2VpnProfile(s) */,
                mIsRestrictedToTestNetworks);
        profile.type = mType;
        profile.server = mServerAddr;
        profile.ipsecIdentifier = mUserIdentity;
        profile.proxy = mProxyInfo;
        profile.setAllowedAlgorithms(mAllowedAlgorithms);
        profile.isBypassable = mIsBypassable;
        profile.isMetered = mIsMetered;
        profile.maxMtu = mMaxMtu;
        profile.areAuthParamsInline = true;
        profile.saveLogin = true;

        switch (mType) {
            case TYPE_IKEV2_IPSEC_USER_PASS:
                profile.username = mUsername;
                profile.password = mPassword;
                profile.ipsecCaCert =
                        mServerRootCaCert == null ? "" : certificateToPemString(mServerRootCaCert);
                break;
            case TYPE_IKEV2_IPSEC_PSK:
                profile.ipsecSecret = encodeForIpsecSecret(mPresharedKey);
                break;
            case TYPE_IKEV2_IPSEC_RSA:
                profile.ipsecUserCert = certificateToPemString(mUserCert);
                profile.ipsecSecret =
                        PREFIX_INLINE + encodeForIpsecSecret(mRsaPrivateKey.getEncoded());
                profile.ipsecCaCert =
                        mServerRootCaCert == null ? "" : certificateToPemString(mServerRootCaCert);
                break;
            default:
                throw new IllegalArgumentException("Invalid auth method set");
        }

        return profile;
    }

    /**
     * Constructs a Ikev2VpnProfile from an internal-use VpnProfile instance.
     *
     * <p>Redundant authentication information (not related to profile type) will be discarded.
     *
     * @hide
     */
    @NonNull
    public static Ikev2VpnProfile fromVpnProfile(@NonNull VpnProfile profile)
            throws IOException, GeneralSecurityException {
        return fromVpnProfile(profile, null);
    }

    /**
     * Builds the Ikev2VpnProfile from the given profile.
     *
     * @param profile the source VpnProfile to build from
     * @param keyStore the Android Keystore instance to use to retrieve the private key, or null if
     *     the private key is PEM-encoded into the profile.
     * @return The IKEv2/IPsec VPN profile
     * @hide
     */
    @NonNull
    public static Ikev2VpnProfile fromVpnProfile(
            @NonNull VpnProfile profile, @Nullable KeyStore keyStore)
            throws IOException, GeneralSecurityException {
        final Builder builder = new Builder(profile.server, profile.ipsecIdentifier);
        builder.setProxy(profile.proxy);
        builder.setAllowedAlgorithms(profile.getAllowedAlgorithms());
        builder.setBypassable(profile.isBypassable);
        builder.setMetered(profile.isMetered);
        builder.setMaxMtu(profile.maxMtu);
        if (profile.isRestrictedToTestNetworks) {
            builder.restrictToTestNetworks();
        }

        switch (profile.type) {
            case TYPE_IKEV2_IPSEC_USER_PASS:
                builder.setAuthUsernamePassword(
                        profile.username,
                        profile.password,
                        certificateFromPemString(profile.ipsecCaCert));
                break;
            case TYPE_IKEV2_IPSEC_PSK:
                builder.setAuthPsk(decodeFromIpsecSecret(profile.ipsecSecret));
                break;
            case TYPE_IKEV2_IPSEC_RSA:
                final PrivateKey key;
                if (profile.ipsecSecret.startsWith(PREFIX_KEYSTORE_ALIAS)) {
                    Objects.requireNonNull(keyStore, "Missing Keystore for aliased PrivateKey");

                    final String alias =
                            profile.ipsecSecret.substring(PREFIX_KEYSTORE_ALIAS.length());
                    key = AndroidKeyStoreProvider.loadAndroidKeyStorePrivateKeyFromKeystore(
                            keyStore, alias, Process.myUid());
                } else if (profile.ipsecSecret.startsWith(PREFIX_INLINE)) {
                    key = getPrivateKey(profile.ipsecSecret.substring(PREFIX_INLINE.length()));
                } else {
                    throw new IllegalArgumentException("Invalid RSA private key prefix");
                }

                final X509Certificate userCert = certificateFromPemString(profile.ipsecUserCert);
                final X509Certificate serverRootCa = certificateFromPemString(profile.ipsecCaCert);
                builder.setAuthDigitalSignature(userCert, key, serverRootCa);
                break;
            default:
                throw new IllegalArgumentException("Invalid auth method set");
        }

        return builder.build();
    }

    /**
     * Validates that the VpnProfile is acceptable for the purposes of an Ikev2VpnProfile.
     *
     * @hide
     */
    public static boolean isValidVpnProfile(@NonNull VpnProfile profile) {
        if (profile.server.isEmpty() || profile.ipsecIdentifier.isEmpty()) {
            return false;
        }

        switch (profile.type) {
            case TYPE_IKEV2_IPSEC_USER_PASS:
                if (profile.username.isEmpty() || profile.password.isEmpty()) {
                    return false;
                }
                break;
            case TYPE_IKEV2_IPSEC_PSK:
                if (profile.ipsecSecret.isEmpty()) {
                    return false;
                }
                break;
            case TYPE_IKEV2_IPSEC_RSA:
                if (profile.ipsecSecret.isEmpty() || profile.ipsecUserCert.isEmpty()) {
                    return false;
                }
                break;
            default:
                return false;
        }

        return true;
    }

    /**
     * Converts a X509 Certificate to a PEM-formatted string.
     *
     * <p>Must be public due to runtime-package restrictions.
     *
     * @hide
     */
    @NonNull
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static String certificateToPemString(@Nullable X509Certificate cert)
            throws IOException, CertificateEncodingException {
        if (cert == null) {
            return EMPTY_CERT;
        }

        // Credentials.convertToPem outputs ASCII bytes.
        return new String(Credentials.convertToPem(cert), StandardCharsets.US_ASCII);
    }

    /**
     * Decodes the provided Certificate(s).
     *
     * <p>Will use the first one if the certStr encodes more than one certificate.
     */
    @Nullable
    private static X509Certificate certificateFromPemString(@Nullable String certStr)
            throws CertificateException {
        if (certStr == null || EMPTY_CERT.equals(certStr)) {
            return null;
        }

        try {
            final List<X509Certificate> certs =
                    Credentials.convertFromPem(certStr.getBytes(StandardCharsets.US_ASCII));
            return certs.isEmpty() ? null : certs.get(0);
        } catch (IOException e) {
            throw new CertificateException(e);
        }
    }

    /** @hide */
    @NonNull
    public static String encodeForIpsecSecret(@NonNull byte[] secret) {
        checkNotNull(secret, MISSING_PARAM_MSG_TMPL, "secret");

        return Base64.getEncoder().encodeToString(secret);
    }

    @NonNull
    private static byte[] decodeFromIpsecSecret(@NonNull String encoded) {
        checkNotNull(encoded, MISSING_PARAM_MSG_TMPL, "encoded");

        return Base64.getDecoder().decode(encoded);
    }

    @NonNull
    private static PrivateKey getPrivateKey(@NonNull String keyStr)
            throws InvalidKeySpecException, NoSuchAlgorithmException {
        final PKCS8EncodedKeySpec privateKeySpec =
                new PKCS8EncodedKeySpec(decodeFromIpsecSecret(keyStr));
        final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(privateKeySpec);
    }

    private static void checkCert(@NonNull X509Certificate cert) {
        try {
            certificateToPemString(cert);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("Certificate could not be encoded");
        }
    }

    private static @NonNull <T> T checkNotNull(
            final T reference, final String messageTemplate, final Object... messageArgs) {
        return Objects.requireNonNull(reference, String.format(messageTemplate, messageArgs));
    }

    /** A incremental builder for IKEv2 VPN profiles */
    public static final class Builder {
        private int mType = -1;
        @NonNull private final String mServerAddr;
        @NonNull private final String mUserIdentity;

        // PSK authentication
        @Nullable private byte[] mPresharedKey;

        // Username/Password, RSA authentication
        @Nullable private X509Certificate mServerRootCaCert;

        // Username/Password authentication
        @Nullable private String mUsername;
        @Nullable private String mPassword;

        // RSA Certificate authentication
        @Nullable private PrivateKey mRsaPrivateKey;
        @Nullable private X509Certificate mUserCert;

        @Nullable private ProxyInfo mProxyInfo;
        @NonNull private List<String> mAllowedAlgorithms = DEFAULT_ALGORITHMS;
        private boolean mIsBypassable = false;
        private boolean mIsMetered = true;
        private int mMaxMtu = PlatformVpnProfile.MAX_MTU_DEFAULT;
        private boolean mIsRestrictedToTestNetworks = false;

        /**
         * Creates a new builder with the basic parameters of an IKEv2/IPsec VPN.
         *
         * @param serverAddr the server that the VPN should connect to
         * @param identity the identity string to be used for IKEv2 authentication
         */
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder(@NonNull String serverAddr, @NonNull String identity) {
            checkNotNull(serverAddr, MISSING_PARAM_MSG_TMPL, "serverAddr");
            checkNotNull(identity, MISSING_PARAM_MSG_TMPL, "identity");

            mServerAddr = serverAddr;
            mUserIdentity = identity;
        }

        private void resetAuthParams() {
            mPresharedKey = null;
            mServerRootCaCert = null;
            mUsername = null;
            mPassword = null;
            mRsaPrivateKey = null;
            mUserCert = null;
        }

        /**
         * Set the IKEv2 authentication to use the provided username/password.
         *
         * <p>Setting this will configure IKEv2 authentication using EAP-MSCHAPv2. Only one
         * authentication method may be set. This method will overwrite any previously set
         * authentication method.
         *
         * @param user the username to be used for EAP-MSCHAPv2 authentication
         * @param pass the password to be used for EAP-MSCHAPv2 authentication
         * @param serverRootCa the root certificate to be used for verifying the identity of the
         *     server
         * @return this {@link Builder} object to facilitate chaining of method calls
         * @throws IllegalArgumentException if any of the certificates were invalid or of an
         *     unrecognized format
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder setAuthUsernamePassword(
                @NonNull String user,
                @NonNull String pass,
                @Nullable X509Certificate serverRootCa) {
            checkNotNull(user, MISSING_PARAM_MSG_TMPL, "user");
            checkNotNull(pass, MISSING_PARAM_MSG_TMPL, "pass");

            // Test to make sure all auth params can be encoded safely.
            if (serverRootCa != null) checkCert(serverRootCa);

            resetAuthParams();
            mUsername = user;
            mPassword = pass;
            mServerRootCaCert = serverRootCa;
            mType = VpnProfile.TYPE_IKEV2_IPSEC_USER_PASS;
            return this;
        }

        /**
         * Set the IKEv2 authentication to use Digital Signature Authentication with the given key.
         *
         * <p>Setting this will configure IKEv2 authentication using a Digital Signature scheme.
         * Only one authentication method may be set. This method will overwrite any previously set
         * authentication method.
         *
         * @param userCert the username to be used for RSA Digital signiture authentication
         * @param key the PrivateKey instance associated with the user ceritificate, used for
         *     constructing the signature
         * @param serverRootCa the root certificate to be used for verifying the identity of the
         *     server
         * @return this {@link Builder} object to facilitate chaining of method calls
         * @throws IllegalArgumentException if any of the certificates were invalid or of an
         *     unrecognized format
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder setAuthDigitalSignature(
                @NonNull X509Certificate userCert,
                @NonNull PrivateKey key,
                @Nullable X509Certificate serverRootCa) {
            checkNotNull(userCert, MISSING_PARAM_MSG_TMPL, "userCert");
            checkNotNull(key, MISSING_PARAM_MSG_TMPL, "key");

            // Test to make sure all auth params can be encoded safely.
            checkCert(userCert);
            if (serverRootCa != null) checkCert(serverRootCa);

            resetAuthParams();
            mUserCert = userCert;
            mRsaPrivateKey = key;
            mServerRootCaCert = serverRootCa;
            mType = VpnProfile.TYPE_IKEV2_IPSEC_RSA;
            return this;
        }

        /**
         * Set the IKEv2 authentication to use Preshared keys.
         *
         * <p>Setting this will configure IKEv2 authentication using a Preshared Key. Only one
         * authentication method may be set. This method will overwrite any previously set
         * authentication method.
         *
         * @param psk the key to be used for Pre-Shared Key authentication
         * @return this {@link Builder} object to facilitate chaining of method calls
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder setAuthPsk(@NonNull byte[] psk) {
            checkNotNull(psk, MISSING_PARAM_MSG_TMPL, "psk");

            resetAuthParams();
            mPresharedKey = psk;
            mType = VpnProfile.TYPE_IKEV2_IPSEC_PSK;
            return this;
        }

        /**
         * Sets whether apps can bypass this VPN connection.
         *
         * <p>By default, all traffic from apps are forwarded through the VPN interface and it is
         * not possible for unprivileged apps to side-step the VPN. If a VPN is set to bypassable,
         * apps may use methods such as {@link Network#getSocketFactory} or {@link
         * Network#openConnection} to instead send/receive directly over the underlying network or
         * any other network they have permissions for.
         *
         * @param isBypassable Whether or not the VPN should be considered bypassable. Defaults to
         *     {@code false}.
         * @return this {@link Builder} object to facilitate chaining of method calls
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder setBypassable(boolean isBypassable) {
            mIsBypassable = isBypassable;
            return this;
        }

        /**
         * Sets a proxy for the VPN network.
         *
         * <p>Note that this proxy is only a recommendation and it may be ignored by apps.
         *
         * @param proxy the ProxyInfo to be set for the VPN network
         * @return this {@link Builder} object to facilitate chaining of method calls
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder setProxy(@Nullable ProxyInfo proxy) {
            mProxyInfo = proxy;
            return this;
        }

        /**
         * Set the upper bound of the maximum transmission unit (MTU) of the VPN interface.
         *
         * <p>If it is not set, a safe value will be used. Additionally, the actual link MTU will be
         * dynamically calculated/updated based on the underlying link's mtu.
         *
         * @param mtu the MTU (in bytes) of the VPN interface
         * @return this {@link Builder} object to facilitate chaining of method calls
         * @throws IllegalArgumentException if the value is not at least the minimum IPv6 MTU (1280)
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder setMaxMtu(int mtu) {
            // IPv6 MTU is greater; since profiles may be started by the system on IPv4 and IPv6
            // networks, the VPN must provide a link fulfilling the stricter of the two conditions
            // (at least that of the IPv6 MTU).
            if (mtu < LinkProperties.MIN_MTU_V6) {
                throw new IllegalArgumentException(
                        "Max MTU must be at least " + LinkProperties.MIN_MTU_V6);
            }
            mMaxMtu = mtu;
            return this;
        }

        /**
         * Marks the VPN network as metered.
         *
         * <p>A VPN network is classified as metered when the user is sensitive to heavy data usage
         * due to monetary costs and/or data limitations. In such cases, you should set this to
         * {@code true} so that apps on the system can avoid doing large data transfers. Otherwise,
         * set this to {@code false}. Doing so would cause VPN network to inherit its meteredness
         * from the underlying network.
         *
         * @param isMetered {@code true} if the VPN network should be treated as metered regardless
         *     of underlying network meteredness. Defaults to {@code true}.
         * @return this {@link Builder} object to facilitate chaining of method calls
         * @see NetworkCapabilities#NET_CAPABILITY_NOT_METERED
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder setMetered(boolean isMetered) {
            mIsMetered = isMetered;
            return this;
        }

        /**
         * Sets the allowable set of IPsec algorithms
         *
         * <p>If set, this will constrain the set of algorithms that the IPsec tunnel will use for
         * integrity verification and encryption to the provided list.
         *
         * <p>The set of allowed IPsec algorithms is defined in {@link IpSecAlgorithm}. Adding of
         * algorithms that are considered insecure (such as AUTH_HMAC_MD5 and AUTH_HMAC_SHA1) is not
         * permitted, and will result in an IllegalArgumentException being thrown.
         *
         * <p>The provided algorithm list must contain at least one algorithm that provides
         * Authentication, and one that provides Encryption. Authenticated Encryption with
         * Associated Data (AEAD) algorithms provide both Authentication and Encryption.
         *
         * <p>By default, this profile will use any algorithm defined in {@link IpSecAlgorithm},
         * with the exception of those considered insecure (as described above).
         *
         * @param algorithmNames the list of supported IPsec algorithms
         * @return this {@link Builder} object to facilitate chaining of method calls
         * @see IpSecAlgorithm
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder setAllowedAlgorithms(@NonNull List<String> algorithmNames) {
            checkNotNull(algorithmNames, MISSING_PARAM_MSG_TMPL, "algorithmNames");
            validateAllowedAlgorithms(algorithmNames);

            mAllowedAlgorithms = algorithmNames;
            return this;
        }

        /**
         * Restricts this profile to use test networks (only).
         *
         * <p>This method is for testing only, and must not be used by apps. Calling
         * provisionVpnProfile() with a profile where test-network usage is enabled will require the
         * MANAGE_TEST_NETWORKS permission.
         *
         * @hide
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Builder restrictToTestNetworks() {
            mIsRestrictedToTestNetworks = true;
            return this;
        }

        /**
         * Validates, builds and provisions the VpnProfile.
         *
         * @throws IllegalArgumentException if any of the required keys or values were invalid
         */
        @NonNull
        @RequiresFeature(PackageManager.FEATURE_IPSEC_TUNNELS)
        public Ikev2VpnProfile build() {
            return new Ikev2VpnProfile(
                    mType,
                    mServerAddr,
                    mUserIdentity,
                    mPresharedKey,
                    mServerRootCaCert,
                    mUsername,
                    mPassword,
                    mRsaPrivateKey,
                    mUserCert,
                    mProxyInfo,
                    mAllowedAlgorithms,
                    mIsBypassable,
                    mIsMetered,
                    mMaxMtu,
                    mIsRestrictedToTestNetworks);
        }
    }
}
