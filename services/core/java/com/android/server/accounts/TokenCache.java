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

package com.android.server.accounts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

/**
 * TokenCaches manage tokens associated with an account in memory.
 */
/* default */ class TokenCache {

    private static class Value {
        public final String token;
        public final long expiryEpochMillis;

        public Value(String token, long expiryEpochMillis) {
            this.token = token;
            this.expiryEpochMillis = expiryEpochMillis;
        }
    }

    private static class Key {
        public final String packageName;
        public final String tokenType;
        public final byte[] sigDigest;

        public Key(String tokenType, String packageName, byte[] sigDigest) {
            this.tokenType = tokenType;
            this.packageName = packageName;
            this.sigDigest = sigDigest;
        }

        @Override
        public boolean equals(Object o) {
            if (o != null && o instanceof Key) {
                Key cacheKey = (Key) o;
                return Objects.equals(packageName, cacheKey.packageName)
                        && Objects.equals(tokenType, cacheKey.tokenType)
                        && Arrays.equals(sigDigest, cacheKey.sigDigest);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return packageName.hashCode() ^ tokenType.hashCode() ^ Arrays.hashCode(sigDigest);
        }
    }

    /**
     * Map associating basic token lookup information with with actual tokens (and optionally their
     * expiration times). 
     */
    private HashMap<Key, Value> mCachedTokens = new HashMap<>();

    /**
     * Map associated tokens with an Evictor that will manage evicting the token from the cache.
     * This reverse lookup is needed because very little information is given at token invalidation
     * time.
     */
    private HashMap<String, Evictor> mTokenEvictors = new HashMap<>();

    private class Evictor {
        private final String mToken;
        private final List<Key> mKeys;

        public Evictor(String token) {
            mKeys = new ArrayList<>();
            mToken = token;
        }

        public void add(Key k) {
            mKeys.add(k);
        }

        public void evict() {
            for (Key k : mKeys) {
                mCachedTokens.remove(k);
            }
            // Clear out the evictor reference.
            mTokenEvictors.remove(mToken);
        }
    }

    /**
     * Caches the specified token until the specified expiryMillis. The token will be associated
     * with the given token type, package name, and digest of signatures.
     *
     * @param token
     * @param tokenType
     * @param packageName
     * @param sigDigest
     * @param expiryMillis
     */
    public void put(
            String token,
            String tokenType,
            String packageName,
            byte[] sigDigest,
            long expiryMillis) {
        if (token == null || System.currentTimeMillis() > expiryMillis) {
            return;
        }
        Key k = new Key(tokenType, packageName, sigDigest);
        // Prep evictor. No token should be cached without a corresponding evictor.
        Evictor evictor = mTokenEvictors.get(token);
        if (evictor == null) {
            evictor = new Evictor(token);
        }
        evictor.add(k);
        mTokenEvictors.put(token, evictor);
        // Then cache values.
        Value v = new Value(token, expiryMillis);
        mCachedTokens.put(k, v);
    }

    /**
     * Evicts the specified token from the cache. This should be called as part of a token
     * invalidation workflow.
     */
    public void remove(String token) {
        Evictor evictor = mTokenEvictors.get(token);
        if (evictor == null) {
            // This condition is expected if the token isn't cached.
            return;
        }
        evictor.evict();
    }

    /**
     * Gets a token from the cache if possible.
     */
    public String get(String tokenType, String packageName, byte[] sigDigest) {
        Key k = new Key(tokenType, packageName, sigDigest);
        Value v = mCachedTokens.get(k);
        long currentTime = System.currentTimeMillis();
        if (v != null && currentTime < v.expiryEpochMillis) {
            return v.token;
        } else if (v != null) {
            remove(v.token);
        }
        return null;
    }
}
