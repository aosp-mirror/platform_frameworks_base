/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.security.net.config;

import android.os.Build;
import android.util.ArrayMap;
import android.util.ArraySet;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.X509TrustManager;

/**
 * @hide
 */
public final class NetworkSecurityConfig {
    /** @hide */
    public static final boolean DEFAULT_CLEARTEXT_TRAFFIC_PERMITTED = true;
    /** @hide */
    public static final boolean DEFAULT_HSTS_ENFORCED = false;

    private final boolean mCleartextTrafficPermitted;
    private final boolean mHstsEnforced;
    private final PinSet mPins;
    private final List<CertificatesEntryRef> mCertificatesEntryRefs;
    private Set<TrustAnchor> mAnchors;
    private final Object mAnchorsLock = new Object();
    private NetworkSecurityTrustManager mTrustManager;
    private final Object mTrustManagerLock = new Object();

    private NetworkSecurityConfig(boolean cleartextTrafficPermitted, boolean hstsEnforced,
            PinSet pins, List<CertificatesEntryRef> certificatesEntryRefs) {
        mCleartextTrafficPermitted = cleartextTrafficPermitted;
        mHstsEnforced = hstsEnforced;
        mPins = pins;
        mCertificatesEntryRefs = certificatesEntryRefs;
        // Sort the certificates entry refs so that all entries that override pins come before
        // non-override pin entries. This allows us to handle the case where a certificate is in
        // multiple entry refs by returning the certificate from the first entry ref.
        Collections.sort(mCertificatesEntryRefs, new Comparator<CertificatesEntryRef>() {
            @Override
            public int compare(CertificatesEntryRef lhs, CertificatesEntryRef rhs) {
                if (lhs.overridesPins()) {
                    return rhs.overridesPins() ? 0 : -1;
                } else {
                    return rhs.overridesPins() ? 1 : 0;
                }
            }
        });
    }

    public Set<TrustAnchor> getTrustAnchors() {
        synchronized (mAnchorsLock) {
            if (mAnchors != null) {
                return mAnchors;
            }
            // Merge trust anchors based on the X509Certificate.
            // If we see the same certificate in two TrustAnchors, one with overridesPins and one
            // without, the one with overridesPins wins.
            // Because mCertificatesEntryRefs is sorted with all overridesPins anchors coming first
            // this can be simplified to just using the first occurrence of a certificate.
            Map<X509Certificate, TrustAnchor> anchorMap = new ArrayMap<>();
            for (CertificatesEntryRef ref : mCertificatesEntryRefs) {
                Set<TrustAnchor> anchors = ref.getTrustAnchors();
                for (TrustAnchor anchor : anchors) {
                    X509Certificate cert = anchor.certificate;
                    if (!anchorMap.containsKey(cert)) {
                        anchorMap.put(cert, anchor);
                    }
                }
            }
            ArraySet<TrustAnchor> anchors = new ArraySet<TrustAnchor>(anchorMap.size());
            anchors.addAll(anchorMap.values());
            mAnchors = anchors;
            return mAnchors;
        }
    }

    public boolean isCleartextTrafficPermitted() {
        return mCleartextTrafficPermitted;
    }

    public boolean isHstsEnforced() {
        return mHstsEnforced;
    }

    public PinSet getPins() {
        return mPins;
    }

    public NetworkSecurityTrustManager getTrustManager() {
        synchronized(mTrustManagerLock) {
            if (mTrustManager == null) {
                mTrustManager = new NetworkSecurityTrustManager(this);
            }
            return mTrustManager;
        }
    }

    /** @hide */
    public TrustAnchor findTrustAnchorBySubjectAndPublicKey(X509Certificate cert) {
        for (CertificatesEntryRef ref : mCertificatesEntryRefs) {
            TrustAnchor anchor = ref.findBySubjectAndPublicKey(cert);
            if (anchor != null) {
                return anchor;
            }
        }
        return null;
    }

    /** @hide */
    public TrustAnchor findTrustAnchorByIssuerAndSignature(X509Certificate cert) {
        for (CertificatesEntryRef ref : mCertificatesEntryRefs) {
            TrustAnchor anchor = ref.findByIssuerAndSignature(cert);
            if (anchor != null) {
                return anchor;
            }
        }
        return null;
    }

    /** @hide */
    public Set<X509Certificate> findAllCertificatesByIssuerAndSignature(X509Certificate cert) {
        Set<X509Certificate> certs = new ArraySet<X509Certificate>();
        for (CertificatesEntryRef ref : mCertificatesEntryRefs) {
            certs.addAll(ref.findAllCertificatesByIssuerAndSignature(cert));
        }
        return certs;
    }

    public void handleTrustStorageUpdate() {
        synchronized (mAnchorsLock) {
            mAnchors = null;
            for (CertificatesEntryRef ref : mCertificatesEntryRefs) {
                ref.handleTrustStorageUpdate();
            }
        }
        getTrustManager().handleTrustStorageUpdate();
    }

    /**
     * Return a {@link Builder} for the default {@code NetworkSecurityConfig}.
     *
     * <p>
     * The default configuration has the following properties:
     * <ol>
     * <li>Cleartext traffic is permitted.</li>
     * <li>HSTS is not enforced.</li>
     * <li>No certificate pinning is used.</li>
     * <li>The system certificate store is trusted for connections.</li>
     * <li>If the application targets API level 23 (Android M) or lower then the user certificate
     * store is trusted by default as well.</li>
     * </ol>
     *
     * @hide
     */
    public static final Builder getDefaultBuilder(int targetSdkVersion) {
        Builder builder = new Builder()
                .setCleartextTrafficPermitted(DEFAULT_CLEARTEXT_TRAFFIC_PERMITTED)
                .setHstsEnforced(DEFAULT_HSTS_ENFORCED)
                // System certificate store, does not bypass static pins.
                .addCertificatesEntryRef(
                        new CertificatesEntryRef(SystemCertificateSource.getInstance(), false));
        // Applications targeting N and above must opt in into trusting the user added certificate
        // store.
        if (targetSdkVersion <= Build.VERSION_CODES.M) {
            // User certificate store, does not bypass static pins.
            builder.addCertificatesEntryRef(
                    new CertificatesEntryRef(UserCertificateSource.getInstance(), false));
        }
        return builder;
    }

    /**
     * Builder for creating {@code NetworkSecurityConfig} objects.
     * @hide
     */
    public static final class Builder {
        private List<CertificatesEntryRef> mCertificatesEntryRefs;
        private PinSet mPinSet;
        private boolean mCleartextTrafficPermitted = DEFAULT_CLEARTEXT_TRAFFIC_PERMITTED;
        private boolean mHstsEnforced = DEFAULT_HSTS_ENFORCED;
        private boolean mCleartextTrafficPermittedSet = false;
        private boolean mHstsEnforcedSet = false;
        private Builder mParentBuilder;

        /**
         * Sets the parent {@code Builder} for this {@code Builder}.
         * The parent will be used to determine values not configured in this {@code Builder}
         * in {@link Builder#build()}, recursively if needed.
         */
        public Builder setParent(Builder parent) {
            // Sanity check to avoid adding loops.
            Builder current = parent;
            while (current != null) {
                if (current == this) {
                    throw new IllegalArgumentException("Loops are not allowed in Builder parents");
                }
                current = current.getParent();
            }
            mParentBuilder = parent;
            return this;
        }

        public Builder getParent() {
            return mParentBuilder;
        }

        public Builder setPinSet(PinSet pinSet) {
            mPinSet = pinSet;
            return this;
        }

        private PinSet getEffectivePinSet() {
            if (mPinSet != null) {
                return mPinSet;
            }
            if (mParentBuilder != null) {
                return mParentBuilder.getEffectivePinSet();
            }
            return PinSet.EMPTY_PINSET;
        }

        public Builder setCleartextTrafficPermitted(boolean cleartextTrafficPermitted) {
            mCleartextTrafficPermitted = cleartextTrafficPermitted;
            mCleartextTrafficPermittedSet = true;
            return this;
        }

        private boolean getEffectiveCleartextTrafficPermitted() {
            if (mCleartextTrafficPermittedSet) {
                return mCleartextTrafficPermitted;
            }
            if (mParentBuilder != null) {
                return mParentBuilder.getEffectiveCleartextTrafficPermitted();
            }
            return DEFAULT_CLEARTEXT_TRAFFIC_PERMITTED;
        }

        public Builder setHstsEnforced(boolean hstsEnforced) {
            mHstsEnforced = hstsEnforced;
            mHstsEnforcedSet = true;
            return this;
        }

        private boolean getEffectiveHstsEnforced() {
            if (mHstsEnforcedSet) {
                return mHstsEnforced;
            }
            if (mParentBuilder != null) {
                return mParentBuilder.getEffectiveHstsEnforced();
            }
            return DEFAULT_HSTS_ENFORCED;
        }

        public Builder addCertificatesEntryRef(CertificatesEntryRef ref) {
            if (mCertificatesEntryRefs == null) {
                mCertificatesEntryRefs = new ArrayList<CertificatesEntryRef>();
            }
            mCertificatesEntryRefs.add(ref);
            return this;
        }

        public Builder addCertificatesEntryRefs(Collection<? extends CertificatesEntryRef> refs) {
            if (mCertificatesEntryRefs == null) {
                mCertificatesEntryRefs = new ArrayList<CertificatesEntryRef>();
            }
            mCertificatesEntryRefs.addAll(refs);
            return this;
        }

        private List<CertificatesEntryRef> getEffectiveCertificatesEntryRefs() {
            if (mCertificatesEntryRefs != null) {
                return mCertificatesEntryRefs;
            }
            if (mParentBuilder != null) {
                return mParentBuilder.getEffectiveCertificatesEntryRefs();
            }
            return Collections.<CertificatesEntryRef>emptyList();
        }

        public boolean hasCertificatesEntryRefs() {
            return mCertificatesEntryRefs != null;
        }

        List<CertificatesEntryRef> getCertificatesEntryRefs() {
            return mCertificatesEntryRefs;
        }

        public NetworkSecurityConfig build() {
            boolean cleartextPermitted = getEffectiveCleartextTrafficPermitted();
            boolean hstsEnforced = getEffectiveHstsEnforced();
            PinSet pinSet = getEffectivePinSet();
            List<CertificatesEntryRef> entryRefs = getEffectiveCertificatesEntryRefs();
            return new NetworkSecurityConfig(cleartextPermitted, hstsEnforced, pinSet, entryRefs);
        }
    }
}
