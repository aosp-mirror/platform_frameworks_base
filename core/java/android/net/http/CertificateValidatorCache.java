/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.os.SystemClock;

import android.security.Sha1MessageDigest;

import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertPath;
import java.security.GeneralSecurityException;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Random;


/**
 * Validator cache used to speed-up certificate chain validation. The idea is
 * to keep each secure domain name associated with a cryptographically secure
 * hash of the certificate chain successfully used to validate the domain. If
 * we establish connection with the domain more than once and each time receive
 * the same list of certificates, we do not have to re-validate.
 * 
 * {@hide}
 */
class CertificateValidatorCache {

    // TODO: debug only!
    public static long mSave = 0;
    public static long mCost = 0;
    // TODO: debug only!

    /**
     * The cache-entry lifetime in milliseconds (here, 10 minutes)
     */
    private static final long CACHE_ENTRY_LIFETIME = 10 * 60 * 1000;

    /**
     * The certificate factory
     */
    private static CertificateFactory sCertificateFactory;

    /**
     * The certificate validator cache map (domain to a cache entry)
     */
    private HashMap<Integer, CacheEntry> mCacheMap;

    /**
     * Random salt
     */
    private int mBigScrew;

    /**
     * @param certificate The array of server certificates to compute a
     * secure hash from
     * @return The secure hash computed from server certificates
     */
    public static byte[] secureHash(Certificate[] certificates) {
        byte[] secureHash = null;

        // TODO: debug only!
        long beg = SystemClock.uptimeMillis();
        // TODO: debug only!

        if (certificates != null && certificates.length != 0) {
            byte[] encodedCertPath = null;
            try {
                synchronized (CertificateValidatorCache.class) {
                    if (sCertificateFactory == null) {
                        try {
                            sCertificateFactory =
                                CertificateFactory.getInstance("X.509");
                        } catch(GeneralSecurityException e) {
                            if (HttpLog.LOGV) {
                                HttpLog.v("CertificateValidatorCache:" +
                                          " failed to create the certificate factory");
                            }
                        }
                    }
                }

                CertPath certPath =
                    sCertificateFactory.generateCertPath(Arrays.asList(certificates));
                if (certPath != null) {
                    encodedCertPath = certPath.getEncoded();
                    if (encodedCertPath != null) {
                      Sha1MessageDigest messageDigest =
                          new Sha1MessageDigest();
                      secureHash = messageDigest.digest(encodedCertPath);
                    }
                }
            } catch (GeneralSecurityException e) {}
        }

        // TODO: debug only!
        long end = SystemClock.uptimeMillis();
        mCost += (end - beg);
        // TODO: debug only!

        return secureHash;
    }

    /**
     * Creates a new certificate-validator cache
     */
    public CertificateValidatorCache() {
        Random random = new Random();
        mBigScrew = random.nextInt();

        mCacheMap = new HashMap<Integer, CacheEntry>();
    }

     /**
     * @param domain The domain to check against
     * @param secureHash The secure hash to check against
     * @return True iff there is a valid (not expired) cache entry
     * associated with the domain and the secure hash
     */
    public boolean has(String domain, byte[] secureHash) {
        boolean rval = false;

        if (domain != null && domain.length() != 0) {
            if (secureHash != null && secureHash.length != 0) {
                CacheEntry cacheEntry = (CacheEntry)mCacheMap.get(
                    new Integer(mBigScrew ^ domain.hashCode()));
                if (cacheEntry != null) {
                    if (!cacheEntry.expired()) {
                        rval = cacheEntry.has(domain, secureHash);
                        // TODO: debug only!
                        if (rval) {
                            mSave += cacheEntry.mSave;
                        }
                        // TODO: debug only!
                    } else {
                        mCacheMap.remove(cacheEntry);
                    }
                }
            }
        }

        return rval;
    }

    /**
     * Adds the (domain, secureHash) tuple to the cache
     * @param domain The domain to be added to the cache
     * @param secureHash The secure hash to be added to the cache
     * @return True iff succeeds
     */
    public boolean put(String domain, byte[] secureHash, long save) {
        if (domain != null && domain.length() != 0) {
            if (secureHash != null && secureHash.length != 0) {
                mCacheMap.put(
                    new Integer(mBigScrew ^ domain.hashCode()),
                    new CacheEntry(domain, secureHash, save));

                return true;
            }
        }

        return false;
    }

    /**
     * Certificate-validator cache entry. We have one per domain
     */
    private class CacheEntry {

        /**
         * The hash associated with this cache entry
         */
        private byte[] mHash;

        /**
         * The time associated with this cache entry
         */
        private long mTime;

        // TODO: debug only!
        public long mSave;
        // TODO: debug only!

        /**
         * The host associated with this cache entry
         */
        private String mDomain;

        /**
         * Creates a new certificate-validator cache entry
         * @param domain The domain to be associated with this cache entry
         * @param secureHash The secure hash to be associated with this cache
         * entry
         */
        public CacheEntry(String domain, byte[] secureHash, long save) {
            mDomain = domain;
            mHash = secureHash;
            // TODO: debug only!
            mSave = save;
            // TODO: debug only!
            mTime = SystemClock.uptimeMillis();
        }

        /**
         * @return True iff the cache item has expired
         */
        public boolean expired() {
            return CACHE_ENTRY_LIFETIME < SystemClock.uptimeMillis() - mTime;
        }

        /**
         * @param domain The domain to check
         * @param secureHash The secure hash to check
         * @return True iff the given domain and hash match those associated
         * with this entry
         */
        public boolean has(String domain, byte[] secureHash) {
            if (domain != null && 0 < domain.length()) {
                if (!mDomain.equals(domain)) {
                    return false;
                }
            }

            if (secureHash != null) {
                int hashLength = secureHash.length;
                if (0 < hashLength) {
                    if (hashLength == mHash.length) {
                        for (int i = 0; i < hashLength; ++i) {
                            if (secureHash[i] != mHash[i]) {
                                return false;
                            }
                        }
                        return true;
                    }
                }
            }

            return false;
        }
    }
};
