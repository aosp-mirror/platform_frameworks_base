/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.internal.widget;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.ArrayMap;

/**
 * A decorator for {@link ILockSettings} that caches the key-value responses in memory.
 *
 * Specifically, the return values of {@link #getString(String, String, int)},
 * {@link #getLong(String, long, int)} and {@link #getBoolean(String, boolean, int)} are cached.
 */
public class LockPatternUtilsCache implements ILockSettings {

    private static final String HAS_LOCK_PATTERN_CACHE_KEY
            = "LockPatternUtils.Cache.HasLockPatternCacheKey";
    private static final String HAS_LOCK_PASSWORD_CACHE_KEY
            = "LockPatternUtils.Cache.HasLockPasswordCacheKey";

    private static LockPatternUtilsCache sInstance;

    private final ILockSettings mService;

    /** Only access when holding {@code mCache} lock. */
    private final ArrayMap<CacheKey, Object> mCache = new ArrayMap<>();

    /** Only access when holding {@link #mCache} lock. */
    private final CacheKey mCacheKey = new CacheKey();


    public static synchronized LockPatternUtilsCache getInstance(ILockSettings service) {
        if (sInstance == null) {
            sInstance = new LockPatternUtilsCache(service);
        }
        return sInstance;
    }

    // ILockSettings

    private LockPatternUtilsCache(ILockSettings service) {
        mService = service;
        try {
            service.registerObserver(mObserver);
        } catch (RemoteException e) {
            // Not safe to do caching without the observer. System process has probably died
            // anyway, so crashing here is fine.
            throw new RuntimeException(e);
        }
    }

    public void setBoolean(String key, boolean value, int userId) throws RemoteException {
        invalidateCache(key, userId);
        mService.setBoolean(key, value, userId);
        putCache(key, userId, value);
    }

    public void setLong(String key, long value, int userId) throws RemoteException {
        invalidateCache(key, userId);
        mService.setLong(key, value, userId);
        putCache(key, userId, value);
    }

    public void setString(String key, String value, int userId) throws RemoteException {
        invalidateCache(key, userId);
        mService.setString(key, value, userId);
        putCache(key, userId, value);
    }

    public long getLong(String key, long defaultValue, int userId) throws RemoteException {
        Object value = peekCache(key, userId);
        if (value instanceof Long) {
            return (long) value;
        }
        long result = mService.getLong(key, defaultValue, userId);
        putCache(key, userId, result);
        return result;
    }

    public String getString(String key, String defaultValue, int userId) throws RemoteException {
        Object value = peekCache(key, userId);
        if (value instanceof String) {
            return (String) value;
        }
        String result = mService.getString(key, defaultValue, userId);
        putCache(key, userId, result);
        return result;
    }

    public boolean getBoolean(String key, boolean defaultValue, int userId) throws RemoteException {
        Object value = peekCache(key, userId);
        if (value instanceof Boolean) {
            return (boolean) value;
        }
        boolean result = mService.getBoolean(key, defaultValue, userId);
        putCache(key, userId, result);
        return result;
    }

    @Override
    public void setLockPattern(String pattern, int userId) throws RemoteException {
        invalidateCache(HAS_LOCK_PATTERN_CACHE_KEY, userId);
        mService.setLockPattern(pattern, userId);
        putCache(HAS_LOCK_PATTERN_CACHE_KEY, userId, pattern != null);
    }

    @Override
    public boolean checkPattern(String pattern, int userId) throws RemoteException {
        return mService.checkPattern(pattern, userId);
    }

    @Override
    public void setLockPassword(String password, int userId) throws RemoteException {
        invalidateCache(HAS_LOCK_PASSWORD_CACHE_KEY, userId);
        mService.setLockPassword(password, userId);
        putCache(HAS_LOCK_PASSWORD_CACHE_KEY, userId, password != null);
    }

    @Override
    public boolean checkPassword(String password, int userId) throws RemoteException {
        return mService.checkPassword(password, userId);
    }

    @Override
    public boolean checkVoldPassword(int userId) throws RemoteException {
        return mService.checkVoldPassword(userId);
    }

    @Override
    public boolean havePattern(int userId) throws RemoteException {
        Object value = peekCache(HAS_LOCK_PATTERN_CACHE_KEY, userId);
        if (value instanceof Boolean) {
            return (boolean) value;
        }
        boolean result = mService.havePattern(userId);
        putCache(HAS_LOCK_PATTERN_CACHE_KEY, userId, result);
        return result;
    }

    @Override
    public boolean havePassword(int userId) throws RemoteException {
        Object value = peekCache(HAS_LOCK_PASSWORD_CACHE_KEY, userId);
        if (value instanceof Boolean) {
            return (boolean) value;
        }
        boolean result = mService.havePassword(userId);
        putCache(HAS_LOCK_PASSWORD_CACHE_KEY, userId, result);
        return result;
    }

    @Override
    public void removeUser(int userId) throws RemoteException {
        mService.removeUser(userId);
    }

    @Override
    public void registerObserver(ILockSettingsObserver observer) throws RemoteException {
        mService.registerObserver(observer);
    }

    @Override
    public void unregisterObserver(ILockSettingsObserver observer) throws RemoteException {
        mService.unregisterObserver(observer);
    }

    @Override
    public IBinder asBinder() {
        return mService.asBinder();
    }

    // Caching

    private Object peekCache(String key, int userId) {
        synchronized (mCache) {
            // Safe to reuse mCacheKey, because it is not stored in the map.
            return mCache.get(mCacheKey.set(key, userId));
        }
    }

    private void putCache(String key, int userId, Object value) {
        synchronized (mCache) {
            // Create a new key, because this will be stored in the map.
            mCache.put(new CacheKey().set(key, userId), value);
        }
    }

    private void invalidateCache(String key, int userId) {
        synchronized (mCache) {
            // Safe to reuse mCacheKey, because it is not stored in the map.
            mCache.remove(mCacheKey.set(key, userId));
        }
    }

    private final ILockSettingsObserver mObserver = new ILockSettingsObserver.Stub() {
        @Override
        public void onLockSettingChanged(String key, int userId) throws RemoteException {
            invalidateCache(key, userId);
        }
    };

    private static final class CacheKey {
        String key;
        int userId;

        public CacheKey set(String key, int userId) {
            this.key = key;
            this.userId = userId;
            return this;
        }

        public CacheKey copy() {
            return new CacheKey().set(key, userId);
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof CacheKey))
                return false;
            CacheKey o = (CacheKey) obj;
            return userId == o.userId && key.equals(o.key);
        }

        @Override
        public int hashCode() {
            return key.hashCode() ^ userId;
        }
    }
}
