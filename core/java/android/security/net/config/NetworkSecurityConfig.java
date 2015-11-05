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

import android.util.ArraySet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.X509TrustManager;

/**
 * @hide
 */
public final class NetworkSecurityConfig {
    private final boolean mCleartextTrafficPermitted;
    private final boolean mHstsEnforced;
    private final PinSet mPins;
    private final List<CertificatesEntryRef> mCertificatesEntryRefs;
    private Set<TrustAnchor> mAnchors;
    private final Object mAnchorsLock = new Object();
    private X509TrustManager mTrustManager;
    private final Object mTrustManagerLock = new Object();

    public NetworkSecurityConfig(boolean cleartextTrafficPermitted, boolean hstsEnforced,
            PinSet pins, List<CertificatesEntryRef> certificatesEntryRefs) {
        mCleartextTrafficPermitted = cleartextTrafficPermitted;
        mHstsEnforced = hstsEnforced;
        mPins = pins;
        mCertificatesEntryRefs = certificatesEntryRefs;
    }

    public Set<TrustAnchor> getTrustAnchors() {
        synchronized (mAnchorsLock) {
            if (mAnchors != null) {
                return mAnchors;
            }
            Set<TrustAnchor> anchors = new ArraySet<TrustAnchor>();
            for (CertificatesEntryRef ref : mCertificatesEntryRefs) {
                anchors.addAll(ref.getTrustAnchors());
            }
            mAnchors = anchors;
            return anchors;
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

    public X509TrustManager getTrustManager() {
        synchronized(mTrustManagerLock) {
            if (mTrustManager == null) {
                mTrustManager = new NetworkSecurityTrustManager(this);
            }
            return mTrustManager;
        }
    }

    void onTrustStoreChange() {
        synchronized (mAnchorsLock) {
            mAnchors = null;
        }
    }
}
