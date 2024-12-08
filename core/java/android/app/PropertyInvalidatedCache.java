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

package android.app;

import static android.text.TextUtils.formatSimple;
import static com.android.internal.util.Preconditions.checkArgumentPositive;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.ApplicationSharedMemory;
import com.android.internal.os.BackgroundThread;

import dalvik.annotation.optimization.CriticalNative;
import dalvik.annotation.optimization.FastNative;
import dalvik.annotation.optimization.NeverCompile;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LRU cache that's invalidated when an opaque value in a property changes. Self-synchronizing,
 * but doesn't hold a lock across data fetches on query misses.
 *
 * This interface is deprecated.  New clients should use {@link IpcDataCache} instead.  Internally,
 * that class uses {@link PropertyInvalidatedCache} , but that design may change in the future.
 *
 * @param <Query> The class used to index cache entries: must be hashable and comparable
 * @param <Result> The class holding cache entries; use a boxed primitive if possible
 * @hide
 */
@TestApi
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class PropertyInvalidatedCache<Query, Result> {
    /**
     * A method to report if the PermissionManager notifications can be separated from cache
     * invalidation.  The feature relies on a series of flags; the dependency is captured in this
     * method.
     * @hide
     */
    public static boolean separatePermissionNotificationsEnabled() {
        return isSharedMemoryAvailable()
                && Flags.picSeparatePermissionNotifications();
    }

    /**
     * This is a configuration class that customizes a cache instance.
     * @hide
     */
    @TestApi
    public static abstract class QueryHandler<Q,R> {
        /**
         * Compute a result given a query.  The semantics are those of Functor.
         */
        public abstract @Nullable R apply(@NonNull Q query);

        /**
         * Return true if a query should not use the cache. The default implementation returns true
         * if the process UID differs from the calling UID. This is to prevent a binder caller from
         * reading a cached value created due to a different binder caller, when processes are
         * caching on behalf of other processes.
         */
        public boolean shouldBypassCache(@NonNull Q query) {
            return false;
        }
    };

    /**
     * The system properties used by caches should be of the form <prefix>.<module>.<api>,
     * where the prefix is "cache_key", the module is one of the constants below, and the
     * api is any string.  The ability to write the property (which happens during
     * invalidation) depends on SELinux rules; these rules are defined against
     * <prefix>.<module>.  Therefore, the module chosen for a cache property must match
     * the permissions granted to the processes that contain the corresponding caches.
     * @hide
     */

    /**
     * The well-known key prefix.
     * @hide
     */
    private static final String CACHE_KEY_PREFIX = "cache_key";

    /**
     * The module used for unit tests and cts tests.  It is expected that no process in
     * the system has permissions to write properties with this module.
     * @hide
     */
    @TestApi
    public static final String MODULE_TEST = "test";

    /**
     * The module used for system server/framework caches.  This is not visible outside
     * the system processes.
     * @hide
     */
    @TestApi
    public static final String MODULE_SYSTEM = "system_server";

    /**
     * The module used for bluetooth caches.
     * @hide
     */
    @TestApi
    public static final String MODULE_BLUETOOTH = "bluetooth";

    /**
     * The module used for telephony caches.
     */
    public static final String MODULE_TELEPHONY = "telephony";

    /**
     * Constants that affect retries when the process is unable to write the property.
     * The first constant is the number of times the process will attempt to set the
     * property.  The second constant is the delay between attempts.
     */

    /**
     * Wait 200ms between retry attempts and the retry limit is 5.  That gives a total possible
     * delay of 1s, which should be less than ANR timeouts.  The goal is to have the system crash
     * because the property could not be set (which is a condition that is easily recognized) and
     * not crash because of an ANR (which can be confusing to debug).
     */
    private static final int PROPERTY_FAILURE_RETRY_DELAY_MILLIS = 200;
    private static final int PROPERTY_FAILURE_RETRY_LIMIT = 5;

    /**
     * Construct a system property that matches the rules described above.  The module is
     * one of the permitted values above.  The API is a string that is a legal Java simple
     * identifier.  The api is modified to conform to the system property style guide by
     * replacing every upper case letter with an underscore and the lower case equivalent.
     * (An initial upper case letter is not prefixed with an underscore).
     * There is no requirement that the apiName be the name of an actual API.
     *
     * Be aware that SystemProperties has a maximum length which is private to the
     * implementation.  The current maximum is 92 characters. If this method creates a
     * property name that is too long, SystemProperties.set() will fail without a good
     * error message.
     * @hide
     */
    @TestApi
    public static @NonNull String createPropertyName(@NonNull String module,
            @NonNull String apiName) {
        char[] api = apiName.toCharArray();
        int upper = 0;
        for (int i = 1; i < api.length; i++) {
            if (Character.isUpperCase(api[i])) {
                upper++;
            }
        }
        char[] suffix = new char[api.length + upper];
        int j = 0;
        for (int i = 0; i < api.length; i++) {
            if (Character.isJavaIdentifierPart(api[i])) {
                if (Character.isUpperCase(api[i])) {
                    if (i > 0) {
                        suffix[j++] = '_';
                    }
                    suffix[j++] = Character.toLowerCase(api[i]);
                } else {
                    suffix[j++] = api[i];
                }
            } else {
                throw new IllegalArgumentException("invalid api name");
            }
        }

        return CACHE_KEY_PREFIX + "." + module + "." + new String(suffix);
    }

    /**
     * The list of known and legal modules.  The list is not sorted.
     */
    private static final String[] sValidModule = {
        MODULE_SYSTEM, MODULE_BLUETOOTH, MODULE_TELEPHONY, MODULE_TEST,
    };

    /**
     * Verify that the module string is in the legal list.  Throw if it is not.
     */
    private static void throwIfInvalidModule(@NonNull String name) {
        for (int i = 0; i < sValidModule.length; i++) {
            if (sValidModule[i].equals(name)) return;
        }
        throw new IllegalArgumentException("invalid module: " + name);
    }

    /**
     * All legal keys start with one of the following strings.
     */
    private static final String[] sValidKeyPrefix = {
        CACHE_KEY_PREFIX + "." + MODULE_SYSTEM + ".",
        CACHE_KEY_PREFIX + "." + MODULE_BLUETOOTH + ".",
        CACHE_KEY_PREFIX + "." + MODULE_TELEPHONY + ".",
        CACHE_KEY_PREFIX + "." + MODULE_TEST + ".",
    };

    /**
     * Verify that the property name conforms to the standard and throw if this is not true.  Note
     * that this is done only once for a given property name; it does not have to be very fast.
     */
    private static void throwIfInvalidCacheKey(String name) {
        for (int i = 0; i < sValidKeyPrefix.length; i++) {
            if (name.startsWith(sValidKeyPrefix[i])) return;
        }
        throw new IllegalArgumentException("invalid cache name: " + name);
    }

    /**
     * Create a cache key for the system module.  The parameter is the API name.  This reduces
     * some of the boilerplate in system caches.  It is not needed in other modules because other
     * modules must use the {@link IpcDataCache} interfaces.
     * @hide
     */
    @NonNull
    public static String createSystemCacheKey(@NonNull String api) {
        return createPropertyName(MODULE_SYSTEM, api);
    }

    /**
     * Reserved nonce values.  Use isReservedNonce() to test for a reserved value.  Note that all
     * reserved values cause the cache to be skipped.
     */
    // This is the initial value of all cache keys.  It is changed when a cache is invalidated.
    @VisibleForTesting
    static final int NONCE_UNSET = 0;
    // This value is used in two ways.  First, it is used internally to indicate that the cache is
    // disabled for the current query.  Secondly, it is used to globally disable the cache across
    // the entire system.  Once a cache is disabled, there is no way to enable it again.  The
    // global behavior is unused and will likely be removed in the future.
    private static final int NONCE_DISABLED = 1;
    // The cache is corked, which means that clients must act as though the cache is always
    // invalid.  This is used when the server is processing updates that continuously invalidate
    // caches.  Rather than issuing individual invalidations (which has a performance penalty),
    // the server corks the caches at the start of the process and uncorks at the end of the
    // process.
    private static final int NONCE_CORKED = 2;
    // The cache is bypassed for the current query.  Unlike UNSET and CORKED, this value is never
    // written to global store.
    private static final int NONCE_BYPASS = 3;

    // The largest reserved nonce value.  Update this whenever a reserved nonce is added.
    private static final int MAX_RESERVED_NONCE = NONCE_BYPASS;

    private static boolean isReservedNonce(long n) {
        return n >= NONCE_UNSET && n <= MAX_RESERVED_NONCE;
    }

    /**
     * The names of the reserved nonces.
     */
    private static final String[] sNonceName =
            new String[]{ "unset", "disabled", "corked", "bypass" };

    // The standard tag for logging.
    private static final String TAG = "PropertyInvalidatedCache";

    // Set this true to enable very chatty logging.  Never commit this true.
    private static final boolean DEBUG = false;

    // Set this true to enable cache verification.  On every cache hit, the cache will compare the
    // cached value to a value pulled directly from the source.  This completely negates any
    // performance advantage of the cache.  Enable it only to test if a particular cache is not
    // being properly invalidated.
    private static final boolean VERIFY = false;

    // The test mode. This is only used to ensure that the test functions setTestMode() and
    // testPropertyName() are used correctly.
    private static boolean sTestMode = false;

    /**
     * The object-private lock.
     */
    private final Object mLock = new Object();

    // Per-Cache performance counters.
    @GuardedBy("mLock")
    private long mHits = 0;

    @GuardedBy("mLock")
    private long mMisses = 0;

    // This counter tracks the number of times {@link #recompute} returned a null value.  Null
    // results are cached, or not, depending on instantiation arguments.  Caching nulls when they
    // should not be cached is a functional error. Failing to cache nulls that can be cached is a
    // performance error.  A non-zero value here means the cache should be examined to be sure
    // that nulls are correctly cached, or not.
    @GuardedBy("mLock")
    private long mNulls = 0;

    @GuardedBy("mLock")
    private long[] mSkips = new long[MAX_RESERVED_NONCE + 1];

    @GuardedBy("mLock")
    private long mMissOverflow = 0;

    @GuardedBy("mLock")
    private long mHighWaterMark = 0;

    @GuardedBy("mLock")
    private long mClears = 0;

    /**
     * Protect objects that support corking.  mLock and sGlobalLock must never be taken while this
     * is held.
     */
    private static final Object sCorkLock = new Object();

    /**
     * A lock for the global list of caches and cache keys.  This must never be taken inside mLock
     * or sCorkLock.
     */
    private static final Object sGlobalLock = new Object();

    /**
     * A map of cache keys that have been disabled in the local process.  When a key is disabled
     * locally, existing caches are disabled and the key is saved in this map.  Future cache
     * instances that use the same key will be disabled in their constructor.  Note that "disabled"
     * means the cache is not used in this process.  Invalidation still proceeds normally, because
     * the cache may be used in other processes.
     */
    @GuardedBy("sGlobalLock")
    private static final HashSet<String> sDisabledKeys = new HashSet<>();

    /**
     * Weakly references all cache objects in the current process, allowing us to iterate over
     * them all for purposes like issuing debug dumps and reacting to memory pressure.
     */
    @GuardedBy("sGlobalLock")
    private static final WeakHashMap<PropertyInvalidatedCache, Void> sCaches = new WeakHashMap<>();

    /**
     * If sEnabled is false then all cache operations are stubbed out.  Set
     * it to false inside test processes.
     */
    private static boolean sEnabled = true;

    /**
     * Name of the property that holds the unique value that we use to invalidate the cache.
     */
    private final String mPropertyName;

    /**
     * The name by which this cache is known.  This should normally be the
     * binder call that is being cached, but the constructors default it to
     * the property name.
     */
    private final String mCacheName;

    /**
     * True if nulls are valid returns from recompute().
     */
    private final boolean mCacheNullResults;

    /**
     * The function that computes a Result, given a Query.  This function is called on a
     * cache miss.
     */
    private QueryHandler<Query, Result> mComputer;

    /**
     * A default function that delegates to the deprecated recompute() method.
     */
    private static class DefaultComputer<Query, Result> extends QueryHandler<Query, Result> {
        final PropertyInvalidatedCache<Query, Result> mCache;
        DefaultComputer(PropertyInvalidatedCache<Query, Result> cache) {
            mCache = cache;
        }
        public Result apply(Query query) {
            return mCache.recompute(query);
        }
    }

    /**
     * An array of hash maps, indexed by calling UID.  The class behaves a bit like a hash map
     * except that it uses the calling UID internally.
     */
    private class CacheMap<Query, Result> {

        // Create a new map for a UID, using the parent's configuration for max size.
        private LinkedHashMap<Query, Result> createMap() {
            return new LinkedHashMap<Query, Result>(
                2 /* start small */,
                0.75f /* default load factor */,
                true /* LRU access order */) {
                @GuardedBy("mLock")
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    final int size = size();
                    if (size > mHighWaterMark) {
                        mHighWaterMark = size;
                    }
                    if (size > mMaxEntries) {
                        mMissOverflow++;
                        return true;
                    }
                    return false;
                }
            };
        }

        // An array of maps, indexed by UID.
        private final SparseArray<LinkedHashMap<Query, Result>> mCache = new SparseArray<>();

        // If true, isolate the hash entries by calling UID.  If this is false, allow the cache
        // entries to be combined in a single hash map.
        private final boolean mIsolated;

        // Collect statistics.
        private final boolean mStatistics;

        // An array of booleans to indicate if a UID has been involved in a map access.  A value
        // exists for every UID that was ever involved during cache access. This is updated only
        // if statistics are being collected.
        private final SparseBooleanArray mUidSeen;

        // A hash map that ignores the UID.  This is used in look-aside fashion just for hit/miss
        // statistics.  This is updated only if statistics are being collected.
        private final ArraySet<Query> mShadowCache;

        // Shadow statistics.  Only hits and misses need to be recorded.  These are updated only
        // if statistics are being collected.  The "SelfHits" records hits when the UID is the
        // process uid.
        private int mShadowHits;
        private int mShadowMisses;
        private int mShadowSelfHits;

        // The process UID.
        private final int mSelfUid;

        // True in test mode.  In test mode, the cache uses Binder.getWorkSource() as the UID.
        private final boolean mTestMode;

        /**
         * Create a CacheMap.  UID isolation is enabled if the input parameter is true and if the
         * isolation feature is enabled.
         */
        CacheMap(boolean isolate, boolean testMode) {
            mIsolated = Flags.picIsolateCacheByUid() && isolate;
            mStatistics = Flags.picIsolatedCacheStatistics() && mIsolated;
            if (mStatistics) {
                mUidSeen = new SparseBooleanArray();
                mShadowCache = new ArraySet<>();
            } else {
                mUidSeen = null;
                mShadowCache = null;
            }
            mSelfUid = Process.myUid();
            mTestMode = testMode;
        }

        // Return the UID for this cache invocation.  If uid isolation is disabled, the value of 0
        // is returned, which effectively places all entries in a single hash map.
        private int callerUid() {
            if (!mIsolated) {
                return 0;
            } else if (mTestMode) {
                return Binder.getCallingWorkSourceUid();
            } else {
                return Binder.getCallingUid();
            }
        }

        /**
         * Lookup an entry in the cache.
         */
        Result get(Query query) {
            final int uid = callerUid();

            // Shadow statistics
            if (mStatistics) {
                if (mShadowCache.contains(query)) {
                    mShadowHits++;
                    if (uid == mSelfUid) {
                        mShadowSelfHits++;
                    }
                } else {
                    mShadowMisses++;
                }
            }

            var map = mCache.get(uid);
            if (map != null) {
                return map.get(query);
            } else {
                return null;
            }
        }

        /**
         * Return true if the entry is in the cache.
         */
        boolean containsKey(Query query) {
            final int uid = callerUid();
            var map = mCache.get(uid);
            if (map != null) {
                return map.containsKey(query);
            } else {
                return false;
            }
        }

        /**
         * Remove an entry from the cache.
         */
        void remove(Query query) {
            final int uid = callerUid();
            if (mStatistics) {
                mShadowCache.remove(query);
            }

            var map = mCache.get(uid);
            if (map != null) {
                map.remove(query);
            }
        }

        /**
         * Record an entry in the cache.
         */
        void put(Query query, Result result) {
            final int uid = callerUid();
            if (mStatistics) {
                mShadowCache.add(query);
                mUidSeen.put(uid, true);
            }

            var map = mCache.get(uid);
            if (map == null) {
                map = createMap();
                mCache.put(uid, map);
            }
            map.put(query, result);
        }

        /**
         * Return the number of entries in the cache.
         */
        int size() {
            int total = 0;
            for (int i = 0; i < mCache.size(); i++) {
                var map = mCache.valueAt(i);
                total += map.size();
            }
            return total;
        }

        /**
         * Clear the entries in the cache.  Update the shadow statistics.
         */
        void clear() {
            if (mStatistics) {
                mShadowCache.clear();
            }

            mCache.clear();
        }

        // Dump basic statistics, if any are collected.  Do nothing if statistics are not enabled.
        void dump(PrintWriter pw) {
            if (mStatistics) {
                pw.println(formatSimple("    ShadowHits: %d, ShadowMisses: %d, ShadowSize: %d",
                                mShadowHits, mShadowMisses, mShadowCache.size()));
                pw.println(formatSimple("    ShadowUids: %d, SelfUid: %d",
                                mUidSeen.size(), mShadowSelfHits));
            }
        }

        // Dump detailed statistics
        void dumpDetailed(PrintWriter pw) {
            for (int i = 0; i < mCache.size(); i++) {
                int uid = mCache.keyAt(i);
                var map = mCache.valueAt(i);

                Set<Map.Entry<Query, Result>> cacheEntries = map.entrySet();
                if (cacheEntries.size() == 0) {
                    break;
                }

                pw.println("    Contents:");
                pw.println(formatSimple("      Uid: %d\n", uid));
                for (Map.Entry<Query, Result> entry : cacheEntries) {
                    String key = Objects.toString(entry.getKey());
                    String value = Objects.toString(entry.getValue());

                    pw.println(formatSimple("      Key: %s\n      Value: %s\n", key, value));
                }
            }
        }
    }

    @GuardedBy("mLock")
    private final CacheMap<Query, Result> mCache;

    /**
     * The nonce handler for this cache.
     */
    @GuardedBy("mLock")
    private final NonceHandler mNonce;

    /**
     * The last nonce value that was observed.
     */
    @GuardedBy("mLock")
    private long mLastSeenNonce = NONCE_UNSET;

    /**
     * Whether we've disabled the cache in this process.
     */
    private boolean mDisabled = false;

    /**
     * Maximum number of entries the cache will maintain.
     */
    private final int mMaxEntries;

    /**
     * A class to manage cache keys.  There is a single instance of this class for each unique key
     * that is shared by all cache instances that use that key.  This class is abstract; subclasses
     * use different storage mechanisms for the nonces.
     */
    private static abstract class NonceHandler {
        // The name of the nonce.
        final String mName;

        // A lock to synchronize corking and invalidation.
        protected final Object mLock = new Object();

        // Count the number of times the property name was invalidated.
        @GuardedBy("mLock")
        private int mInvalidated = 0;

        // Count the number of times invalidate or cork calls were nops because the cache was
        // already corked.
        @GuardedBy("mLock")
        private int mCorkedInvalidates = 0;

        // Count the number of corks against this property name.  This is not a statistic.  It
        // increases when the property is corked and decreases when the property is uncorked.
        // Invalidation requests are ignored when the cork count is greater than zero.
        @GuardedBy("mLock")
        private int mCorks = 0;

        // True if this handler is in test mode.  If it is in test mode, then nonces are stored
        // and retrieved from mTestNonce.
        @GuardedBy("mLock")
        private boolean mTestMode = false;

        // This is the local value of the nonce, as last set by the NonceHandler.  It is always
        // updated by the setNonce() operation.  The getNonce() operation returns this value in
        // NonceLocal handlers and handlers in test mode.
        @GuardedBy("mLock")
        protected long mShadowNonce = NONCE_UNSET;

        // A list of watchers to be notified of changes.  This is null until at least one watcher
        // registers.  Checking for null is meant to be the fastest way the handler can determine
        // that there are no watchers to be notified.
        @GuardedBy("mLock")
        private ArrayList<Semaphore> mWatchers;

        /**
         * The methods to get and set a nonce from whatever storage is being used.  mLock may be
         * held when these methods are called.  Implementations that take locks must behave as
         * though mLock could be held.
         */
        abstract long getNonceInternal();
        abstract void setNonceInternal(long value);

        NonceHandler(@NonNull String name) {
            mName = name;
        }

        /**
         * Get a nonce from storage.  If the handler is in test mode, the nonce is returned from
         * the local mShadowNonce.
         */
        long getNonce() {
            synchronized (mLock) {
                if (mTestMode) return mShadowNonce;
            }
            return getNonceInternal();
        }

        /**
         * Write a nonce to storage.  The nonce is always written to the local mShadowNonce.  If
         * the handler is not in test mode the nonce is also written to storage.
         */
        void setNonce(long val) {
            synchronized (mLock) {
                mShadowNonce = val;
                if (!mTestMode) {
                    setNonceInternal(val);
                }
                wakeAllWatchersLocked();
            }
        }

        @GuardedBy("mLock")
        private void wakeAllWatchersLocked() {
            if (mWatchers != null) {
                for (int i = 0; i < mWatchers.size(); i++) {
                    mWatchers.get(i).release();
                }
            }
        }

        /**
         * Register a watcher to be notified when a nonce changes.  There is no check for
         * duplicates.  In general, this method is called only from {@link NonceWatcher}.
         */
        void registerWatcher(Semaphore s) {
            synchronized (mLock) {
                if (mWatchers == null) {
                    mWatchers = new ArrayList<>();
                }
                mWatchers.add(s);
            }
        }

        /**
         * Unregister a watcher.  Nothing happens if the watcher is not registered.
         */
        void unregisterWatcher(Semaphore s) {
            synchronized (mLock) {
                if (mWatchers != null) {
                    mWatchers.remove(s);
                }
            }
        }

        /**
         * Write the invalidation nonce for the property.
         */
        void invalidate() {
            if (!sEnabled) {
                if (DEBUG) {
                    Log.d(TAG, formatSimple("cache invalidate %s suppressed", mName));
                }
                return;
            }

            synchronized (mLock) {
                if (mCorks > 0) {
                    if (DEBUG) {
                        Log.d(TAG, "ignoring invalidation due to cork: " + mName);
                    }
                    mCorkedInvalidates++;
                    return;
                }

                final long nonce = getNonce();
                if (nonce == NONCE_DISABLED) {
                    if (DEBUG) {
                        Log.d(TAG, "refusing to invalidate disabled cache: " + mName);
                    }
                    return;
                }

                long newValue;
                do {
                    newValue = NoPreloadHolder.next();
                } while (isReservedNonce(newValue));
                if (DEBUG) {
                    Log.d(TAG, formatSimple(
                        "invalidating cache [%s]: [%s] -> [%s]",
                        mName, nonce, Long.toString(newValue)));
                }
                // There is a small race with concurrent disables here.  A compare-and-exchange
                // property operation would be required to eliminate the race condition.
                setNonce(newValue);
                mInvalidated++;
            }
        }

        void cork() {
            if (!sEnabled) {
                if (DEBUG) {
                    Log.d(TAG, formatSimple("cache corking %s suppressed", mName));
                }
                return;
            }

            synchronized (mLock) {
                int numberCorks = mCorks;
                if (DEBUG) {
                    Log.d(TAG, formatSimple(
                        "corking %s: numberCorks=%s", mName, numberCorks));
                }

                // If we're the first ones to cork this cache, set the cache to the corked state so
                // existing caches talk directly to their services while we've corked updates.
                // Make sure we don't clobber a disabled cache value.

                // TODO: we can skip this property write and leave the cache enabled if the
                // caller promises not to make observable changes to the cache backing state before
                // uncorking the cache, e.g., by holding a read lock across the cork-uncork pair.
                // Implement this more dangerous mode of operation if necessary.
                if (numberCorks == 0) {
                    final long nonce = getNonce();
                    if (nonce != NONCE_UNSET && nonce != NONCE_DISABLED) {
                        setNonce(NONCE_CORKED);
                    }
                } else {
                    mCorkedInvalidates++;
                }
                mCorks++;
                if (DEBUG) {
                    Log.d(TAG, "corked: " + mName);
                }
            }
        }

        void uncork() {
            if (!sEnabled) {
                if (DEBUG) {
                    Log.d(TAG, formatSimple("cache uncorking %s suppressed", mName));
                }
                return;
            }

            synchronized (mLock) {
                int numberCorks = --mCorks;
                if (DEBUG) {
                    Log.d(TAG, formatSimple(
                        "uncorking %s: numberCorks=%s", mName, numberCorks));
                }

                if (numberCorks < 0) {
                    throw new AssertionError("cork underflow: " + mName);
                }
                if (numberCorks == 0) {
                    // The property is fully uncorked and can be invalidated normally.
                    invalidate();
                    if (DEBUG) {
                        Log.d(TAG, "uncorked: " + mName);
                    }
                }
            }
        }

        /**
         * Globally (that is, system-wide) disable all caches that use this key.  There is no way
         * to re-enable these caches.
         */
        void disable() {
            if (!sEnabled) {
                return;
            }
            synchronized (mLock) {
                setNonce(NONCE_DISABLED);
            }
        }

        /**
         * Put this handler in or out of test mode.  Regardless of the current and next mode, the
         * test nonce variable is reset to UNSET.
         */
        void setTestMode(boolean mode) {
            synchronized (mLock) {
                mTestMode = mode;
                mShadowNonce = NONCE_UNSET;
            }
        }

        /**
         * Return the statistics associated with the key.  These statistics are not associated
         * with any individual cache.
         */
        record Stats(int invalidated, int corkedInvalidates) {}
        Stats getStats() {
            synchronized (mLock) {
                return new Stats(mInvalidated, mCorkedInvalidates);
            }
        }
    }

    /**
     * Manage nonces that are stored in a system property.
     */
    private static final class NonceSysprop extends NonceHandler {
        // A handle to the property, for fast lookups.
        private volatile SystemProperties.Handle mHandle;

        NonceSysprop(@NonNull String name) {
            super(name);
        }

        /**
         * Retrieve the nonce from the system property.  If the handle is null, this method
         * attempts to create a handle.  If handle creation fails, the method returns UNSET.  If
         * the handle is not null, the method returns a value read via the handle.  This read
         * occurs outside any lock.
         */
        @Override
        long getNonceInternal() {
            if (mHandle == null) {
                synchronized (mLock) {
                    if (mHandle == null) {
                        mHandle = SystemProperties.find(mName);
                        if (mHandle == null) {
                            return NONCE_UNSET;
                        }
                    }
                }
            }
            return mHandle.getLong(NONCE_UNSET);
        }

        /**
         * Write a nonce to a system property.
         */
        @Override
        void setNonceInternal(long value) {
            // Failing to set the nonce is a fatal error.  Failures setting a system property have
            // been reported; given that the failure is probably transient, this function includes
            // a retry.
            final String str = Long.toString(value);
            RuntimeException failure = null;
            for (int attempt = 0; attempt < PROPERTY_FAILURE_RETRY_LIMIT; attempt++) {
                try {
                    SystemProperties.set(mName, str);
                    if (attempt > 0) {
                        // This log is not guarded.  Based on known bug reports, it should
                        // occur once a week or less.  The purpose of the log message is to
                        // identify the retries as a source of delay that might be otherwise
                        // be attributed to the cache itself.
                        Log.w(TAG, "Nonce set after " + attempt + " tries");
                    }
                    return;
                } catch (RuntimeException e) {
                    if (failure == null) {
                        failure = e;
                    }
                    try {
                        Thread.sleep(PROPERTY_FAILURE_RETRY_DELAY_MILLIS);
                    } catch (InterruptedException x) {
                        // Ignore this exception.  The desired delay is only approximate and
                        // there is no issue if the sleep sometimes terminates early.
                    }
                }
            }
            // This point is reached only if SystemProperties.set() fails at least once.
            // Rethrow the first exception that was received.
            throw failure;
        }
    }

    /**
     * Manage nonces that are stored in shared memory.
     */
    private static final class NonceSharedMem extends NonceHandler {
        // The shared memory.
        private volatile NonceStore mStore;

        // The index of the nonce in shared memory.  This changes from INVALID only when the local
        // object is completely initialized.
        private volatile int mHandle = NonceStore.INVALID_NONCE_INDEX;

        // A short name that is saved in shared memory.  This is the portion of the property name
        // that follows the prefix.
        private final String mShortName;

        NonceSharedMem(@NonNull String name, @Nullable String prefix) {
            super(name);
            if ((prefix != null) && name.startsWith(prefix)) {
                mShortName = name.substring(prefix.length());
            } else {
                mShortName = name;
            }
        }

        // Initialize the mStore and mHandle variables.  This function does nothing if the
        // variables are already initialized.  Synchronization ensures that initialization happens
        // no more than once.  The function returns the new value of mHandle.
        //
        // If the "update" boolean is true, then the property is registered with the nonce store
        // before the associated handle is fetched.
        private int initialize(boolean update) {
            synchronized (mLock) {
                int handle = mHandle;
                if (handle == NonceStore.INVALID_NONCE_INDEX) {
                    if (mStore == null) {
                        mStore = NonceStore.getInstance();
                        if (mStore == null) {
                            return NonceStore.INVALID_NONCE_INDEX;
                        }
                    }
                    if (update) {
                        mStore.storeName(mShortName);
                    }
                    handle = mStore.getHandleForName(mShortName);
                    if (handle == NonceStore.INVALID_NONCE_INDEX) {
                        return NonceStore.INVALID_NONCE_INDEX;
                    }
                    // The handle must be valid.
                    mHandle = handle;
                }
                return handle;
            }
        }

        // Fetch the nonce from shared memory.  If the shared memory is not available, return
        // UNSET.  If the shared memory is available but the nonce name is not known (it may not
        // have been invalidated by the server yet), return UNSET.
        @Override
        long getNonceInternal() {
            int handle = mHandle;
            if (handle == NonceStore.INVALID_NONCE_INDEX) {
                handle = initialize(false);
                if (handle == NonceStore.INVALID_NONCE_INDEX) {
                    return NONCE_UNSET;
                }
            }
            return mStore.getNonce(handle);
        }

        // Set the nonce in shared memory.  If the shared memory is not available or if the nonce
        // cannot be registered in shared memory, throw an exception.
        @Override
        void setNonceInternal(long value) {
            int handle = mHandle;
            if (handle == NonceStore.INVALID_NONCE_INDEX) {
                handle = initialize(true);
                if (handle == NonceStore.INVALID_NONCE_INDEX) {
                    throw new IllegalStateException("unable to assign nonce handle: " + mName);
                }
            }
            mStore.setNonce(handle, value);
        }
    }

    /**
     * SystemProperties and shared storage are protected and cannot be written by random
     * processes.  So, for testing purposes, the NonceLocal handler stores the nonce locally.  The
     * NonceLocal uses the mShadowNonce in the superclass, regardless of test mode.
     */
    private static class NonceLocal extends NonceHandler {
        // The saved nonce.
        private long mValue;

        NonceLocal(@NonNull String name) {
            super(name);
        }

        @Override
        long getNonceInternal() {
            return mShadowNonce;
        }

        @Override
        void setNonceInternal(long value) {
            mShadowNonce = value;
        }
    }

    /**
     * A NonceWatcher lets an external client test if a nonce value has changed from the last time
     * the watcher was checked.
     * @hide
     */
    public static class NonceWatcher implements AutoCloseable {
        // The handler for the key.
        private final NonceHandler mHandler;

        // The last-seen value.  This is initialized to "unset".
        private long mLastSeen = NONCE_UNSET;

        // The semaphore that the watcher waits on.  A permit is released every time the nonce
        // changes.  Permits are acquired in the wait method.
        private final Semaphore mSem = new Semaphore(0);

        /**
         * Create a watcher for a handler.  The last-seen value is not set here and will be
         * "unset".  Therefore, a call to isChanged() will return true if the nonce has ever been
         * set, no matter when the watcher is first created.  Clients may want to flush that
         * change by calling isChanged() immediately after constructing the object.
         */
        private NonceWatcher(@NonNull NonceHandler handler) {
            mHandler = handler;
            mHandler.registerWatcher(mSem);
        }

        /**
         * Unregister to be notified when a nonce changes.  NonceHandler allows a call to
         * unregisterWatcher with a semaphore that is not registered, so there is no check inside
         * this method to guard against multiple closures.
         */
        @Override
        public void close() {
            mHandler.unregisterWatcher(mSem);
        }

        /**
         * Return the last seen value of the nonce.  This does not update that value.  Only
         * {@link #isChanged()} updates the value.
         */
        public long lastSeen() {
            return mLastSeen;
        }

        /**
         * Return true if the nonce has changed from the last time isChanged() was called.  The
         * method is not thread safe.
         * @hide
         */
        public boolean isChanged() {
            long current = mHandler.getNonce();
            if (current != mLastSeen) {
                mLastSeen = current;
                return true;
            }
            return false;
        }

        /**
         * Wait for the nonce value to change.  It is not guaranteed that the nonce has changed when
         * this returns: clients must confirm with {@link #isChanged}. The wait operation is only
         * effective in a process that writes the nonces.  The function returns the number of times
         * the nonce had changed since the last call to the method.
         * @hide
         */
        public int waitForChange() throws InterruptedException {
            mSem.acquire(1);
            return 1 + mSem.drainPermits();
        }

        /**
         * Wait for the nonce value to change.  It is not guaranteed that the nonce has changed when
         * this returns: clients must confirm with {@link #isChanged}. The wait operation is only
         * effective in a process that writes the nonces.  The function returns the number of times
         * the nonce changed since the last call to the method.  A return value of zero means the
         * timeout expired.  Beware that a timeout of 0 means the function will not wait at all.
         * @hide
         */
        public int waitForChange(long timeout, TimeUnit timeUnit) throws InterruptedException {
            if (mSem.tryAcquire(1, timeout, timeUnit)) {
                return 1 + mSem.drainPermits();
            } else {
                return 0;
            }
        }

        /**
         * Wake the watcher by releasing the semaphore.  This can be used to wake clients that are
         * blocked in {@link #waitForChange} without affecting the underlying nonce.
         * @hide
         */
        public void wakeUp() {
            mSem.release();
        }
    }

    /**
     * Return a NonceWatcher for the cache.
     * @hide
     */
    public NonceWatcher getNonceWatcher() {
        return new NonceWatcher(mNonce);
    }

    /**
     * Return a NonceWatcher for the given property.  If a handler does not exist for the
     * property, one is created.  This throws if the property name is not a valid cache key.
     * @hide
     */
    public static NonceWatcher getNonceWatcher(@NonNull String propertyName) {
        return new NonceWatcher(getNonceHandler(propertyName));
    }

    /**
     * Complete key prefixes.
     */
    private static final String PREFIX_TEST = CACHE_KEY_PREFIX + "." + MODULE_TEST + ".";
    private static final String PREFIX_SYSTEM = CACHE_KEY_PREFIX + "." + MODULE_SYSTEM + ".";

    /**
     * A static list of nonce handlers, indexed by name.  NonceHandlers can be safely shared by
     * multiple threads, and can therefore be shared by multiple instances of the same cache, and
     * with static calls (see {@link #invalidateCache}.  Addition and removal are guarded by the
     * global lock, to ensure that duplicates are not created.
     */
    private static final ConcurrentHashMap<String, NonceHandler> sHandlers
            = new ConcurrentHashMap<>();

    // True if shared memory is flag-enabled, false otherwise.  Read the flags exactly once.
    private static final boolean sSharedMemoryAvailable = isSharedMemoryAvailable();

    @android.ravenwood.annotation.RavenwoodReplace
    private static boolean isSharedMemoryAvailable() {
        return com.android.internal.os.Flags.applicationSharedMemoryEnabled()
                && android.app.Flags.picUsesSharedMemory();
    }

    private static boolean isSharedMemoryAvailable$ravenwood() {
        return false; // Always disable shared memory on Ravenwood. (for now)
    }

    /**
     * Keys that cannot be put in shared memory yet.
     */
    private static boolean inSharedMemoryDenyList(@NonNull String name) {
        final String pkginfo = PREFIX_SYSTEM + "package_info";
        return name.equals(pkginfo);
    };

    // Return true if this cache can use shared memory for its nonce.  Shared memory may be used
    // if the module is the system.
    private static boolean sharedMemoryOkay(@NonNull String name) {
        return sSharedMemoryAvailable
                && name.startsWith(PREFIX_SYSTEM)
                && !inSharedMemoryDenyList(name);
    }

    /**
     * Return the proper nonce handler, based on the property name.  A handler is created if
     * necessary.  Before a handler is created, the name is checked, and an exception is thrown if
     * the name is not valid.
     */
    private static NonceHandler getNonceHandler(@NonNull String name) {
        NonceHandler h = sHandlers.get(name);
        if (h == null) {
            synchronized (sGlobalLock) {
                throwIfInvalidCacheKey(name);
                h = sHandlers.get(name);
                if (h == null) {
                    if (sharedMemoryOkay(name)) {
                        h = new NonceSharedMem(name, PREFIX_SYSTEM);
                    } else if (name.startsWith(PREFIX_TEST)) {
                        h = new NonceLocal(name);
                    } else {
                        h = new NonceSysprop(name);
                    }
                    sHandlers.put(name, h);
                }
            }
        }
        return h;
    }

    /**
     * A public argument builder to configure cache behavior.  The root instance requires a
     * module; this is immutable.  New instances are created with member methods.  It is important
     * to note that the member methods create new instances: they do not modify 'this'.  The api
     * is allowed to be null in the record constructor to facility reuse of Args instances.
     * @hide
     */
    public static record Args(@NonNull String mModule, @Nullable String mApi,
            int mMaxEntries, boolean mIsolateUids, boolean mTestMode, boolean mCacheNulls) {

        /**
         * Default values for fields.
         */
        public static final int DEFAULT_MAX_ENTRIES = 32;
        public static final boolean DEFAULT_ISOLATE_UIDS = true;
        public static final boolean DEFAULT_CACHE_NULLS = false;

        // Validation: the module must be one of the known module strings and the maxEntries must
        // be positive.
        public Args {
            throwIfInvalidModule(mModule);
            checkArgumentPositive(mMaxEntries, "max cache size must be positive");
        }

        // The base constructor must include the module.  Modules do not change in a source file,
        // so even if the Args is reused, the module will not/should not change.  The api is null,
        // which is not legal, but there is no reasonable default.  Clients must call the api
        // method to set the field properly.
        public Args(@NonNull String module) {
            this(module,
                    null,       // api
                    DEFAULT_MAX_ENTRIES,
                    DEFAULT_ISOLATE_UIDS,
                    false,      // testMode
                    DEFAULT_CACHE_NULLS
                 );
        }

        public Args api(@NonNull String api) {
            return new Args(mModule, api, mMaxEntries, mIsolateUids, mTestMode, mCacheNulls);
        }

        public Args maxEntries(int val) {
            return new Args(mModule, mApi, val, mIsolateUids, mTestMode, mCacheNulls);
        }

        public Args isolateUids(boolean val) {
            return new Args(mModule, mApi, mMaxEntries, val, mTestMode, mCacheNulls);
        }

        public Args testMode(boolean val) {
            return new Args(mModule, mApi, mMaxEntries, mIsolateUids, val, mCacheNulls);
        }

        public Args cacheNulls(boolean val) {
            return new Args(mModule, mApi, mMaxEntries, mIsolateUids, mTestMode, val);
        }
    }

    /**
     * Make a new property invalidated cache.  The key is computed from the module and api
     * parameters.
     *
     * @param args The cache configuration.
     * @param cacheName Name of this cache in debug and dumpsys
     * @param computer The code to compute values that are not in the cache.
     * @hide
     */
    public PropertyInvalidatedCache(@NonNull Args args, @NonNull String cacheName,
            @Nullable QueryHandler<Query, Result> computer) {
        mPropertyName = createPropertyName(args.mModule, args.mApi);
        mCacheName = cacheName;
        mCacheNullResults = args.mCacheNulls && Flags.picCacheNulls();
        mNonce = getNonceHandler(mPropertyName);
        mMaxEntries = args.mMaxEntries;
        mCache = new CacheMap<>(args.mIsolateUids, args.mTestMode);
        mComputer = (computer != null) ? computer : new DefaultComputer<>(this);
        registerCache();
    }

    /**
     * Burst a property name into module and api.  Throw if the key is invalid.  This method is
     * used in to transition legacy cache constructors to the args constructor.
     */
    private static Args argsFromProperty(@NonNull String name) {
        throwIfInvalidCacheKey(name);
        // Strip off the leading well-known prefix.
        String base = name.substring(CACHE_KEY_PREFIX.length() + 1);
        int dot = base.indexOf(".");
        String module = base.substring(0, dot);
        String api = base.substring(dot + 1);
        return new Args(module).api(api);
    }

    /**
     * Make a new property invalidated cache.  This constructor names the cache after the
     * property name.  New clients should prefer the constructor that takes an explicit
     * cache name.
     *
     * TODO(216112648): deprecate this as a public interface, in favor of the four-argument
     * constructor.
     *
     * @param maxEntries Maximum number of entries to cache; LRU discard
     * @param propertyName Name of the system property holding the cache invalidation nonce.
     *
     * @hide
     */
    @Deprecated
    public PropertyInvalidatedCache(int maxEntries, @NonNull String propertyName) {
        this(argsFromProperty(propertyName).maxEntries(maxEntries), propertyName, null);
    }

    /**
     * Make a new property invalidated cache.
     *
     * TODO(216112648): deprecate this as a public interface, in favor of the four-argument
     * constructor.
     *
     * @param maxEntries Maximum number of entries to cache; LRU discard
     * @param propertyName Name of the system property holding the cache invalidation nonce
     * @param cacheName Name of this cache in debug and dumpsys
     * @hide
     */
    @Deprecated
    public PropertyInvalidatedCache(int maxEntries, @NonNull String propertyName,
            @NonNull String cacheName) {
        this(argsFromProperty(propertyName).maxEntries(maxEntries), cacheName, null);
    }

    /**
     * Make a new property invalidated cache.  The key is computed from the module and api
     * parameters.
     *
     * @param maxEntries Maximum number of entries to cache; LRU discard
     * @param module The module under which the cache key should be placed.
     * @param api The api this cache front-ends.  The api must be a Java identifier but
     * need not be an actual api.
     * @param cacheName Name of this cache in debug and dumpsys
     * @param computer The code to compute values that are not in the cache.
     * @hide
     */
    @TestApi
    public PropertyInvalidatedCache(int maxEntries, @NonNull String module, @NonNull String api,
            @NonNull String cacheName, @NonNull QueryHandler<Query, Result> computer) {
        this(new Args(module).maxEntries(maxEntries).api(api), cacheName, computer);
    }

    /**
     * Register the map in the global list.  If the cache is disabled globally, disable it
     * now.  This method is only ever called from the constructor, which means no other thread has
     * access to the object yet.  It can safely be modified outside any lock.
     */
    private void registerCache() {
        synchronized (sGlobalLock) {
            if (sDisabledKeys.contains(mCacheName)) {
                disableInstance();
            }
            sCaches.put(this, null);
        }
    }

    /**
     * Enable or disable testing.  The protocol requires that the mode toggle: for instance, it is
     * illegal to clear the test mode if the test mode is already off.  The purpose is solely to
     * ensure that test clients do not forget to use the test mode properly, even though the
     * current logic does not care.
     * @hide
     */
    @TestApi
    public static void setTestMode(boolean mode) {
        synchronized (sGlobalLock) {
            if (sTestMode == mode) {
                final String msg = "cannot set test mode redundantly: mode=" + mode;
                if (Flags.enforcePicTestmodeProtocol()) {
                    throw new IllegalStateException(msg);
                } else {
                    Log.e(TAG, msg);
                }
            }
            sTestMode = mode;
            if (mode) {
                // No action when testing begins.
            } else {
                resetAfterTestLocked();
            }
        }
    }

    /**
     * Clean up when testing ends. All handlers are reset out of test mode.  NonceLocal handlers
     * (MODULE_TEST) are reset to the NONCE_UNSET state.  This has no effect on any other handlers
     * that were not originally in test mode.
     */
    @GuardedBy("sGlobalLock")
    private static void resetAfterTestLocked() {
        for (Iterator<String> e = sHandlers.keys().asIterator(); e.hasNext(); ) {
            String s = e.next();
            final NonceHandler h = sHandlers.get(s);
            h.setTestMode(false);
        }
    }

    /**
     * Enable testing the specific cache key.  This API allows a test process to invalidate caches
     * for which it would not otherwise have permission.  Caches in test mode do NOT write their
     * values to the system properties.  The effect is local to the current process.  Test mode
     * must be true when this method is called.
     * @hide
     */
    @TestApi
    public void testPropertyName() {
        synchronized (sGlobalLock) {
            if (sTestMode == false) {
                throw new IllegalStateException("cannot test property name with test mode off");
            }
            mNonce.setTestMode(true);
        }
    }

    // Read the nonce associated with the current cache.
    @GuardedBy("mLock")
    private long getCurrentNonce() {
        return mNonce.getNonce();
    }

    /**
     * Forget all cached values.  This is used by a client when the server exits.  Since the
     * server has exited, the cache values are no longer valid, but the server is no longer
     * present to invalidate the cache.  Note that this is not necessary if the server is
     * system_server, because the entire operating system reboots if that process exits.
     * @hide
     */
    public final void clear() {
        synchronized (mLock) {
            if (DEBUG) {
                Log.d(TAG, "clearing cache for " + mPropertyName);
            }
            mCache.clear();
            mClears++;
        }
    }

    /**
     * Fetch a result from scratch in case it's not in the cache at all.  Called unlocked: may
     * block. If this function returns null, the result of the cache query is null. There is no
     * "negative cache" in the query: we don't cache null results at all.
     * TODO(216112648): deprecate this as a public interface, in favor of an instance of
     * QueryHandler.
     * @hide
     */
    public Result recompute(@NonNull Query query) {
        return mComputer.apply(query);
    }

    /**
     * Return true if the query should bypass the cache.  The default behavior is to
     * always use the cache but the method can be overridden for a specific class.
     * TODO(216112648): deprecate this as a public interface, in favor of an instance of
     * QueryHandler.
     * @hide
     */
    public boolean bypass(@NonNull Query query) {
        return mComputer.shouldBypassCache(query);
    }

    /**
     * Determines if a pair of responses are considered equal. Used to determine whether
     * a cache is inadvertently returning stale results when VERIFY is set to true.
     * @hide
     */
    public boolean resultEquals(Result cachedResult, Result fetchedResult) {
        // If a service crashes and returns a null result, the cached value remains valid.
        if (fetchedResult != null) {
            return Objects.equals(cachedResult, fetchedResult);
        }
        return true;
    }

    /**
     * Make result up-to-date on a cache hit.  Called unlocked;
     * may block.
     *
     * Return either 1) oldResult itself (the same object, by reference equality), in which
     * case we just return oldResult as the result of the cache query, 2) a new object, which
     * replaces oldResult in the cache and which we return as the result of the cache query
     * after performing another property read to make sure that the result hasn't changed in
     * the meantime (if the nonce has changed in the meantime, we drop the cache and try the
     * whole query again), or 3) null, which causes the old value to be removed from the cache
     * and null to be returned as the result of the cache query.
     * @hide
     */
    protected Result refresh(Result oldResult, Query query) {
        return oldResult;
    }

    /**
     * Disable the use of this cache in this process.  This method is used internally and during
     * testing.  To disable a cache in normal code, use disableLocal().  A disabled cache cannot
     * be re-enabled.
     * @hide
     */
    @TestApi
    public final void disableInstance() {
        synchronized (mLock) {
            mDisabled = true;
            clear();
        }
    }

    /**
     * Disable the local use of all caches with the same name.  All currently registered caches
     * with the name will be disabled now, and all future cache instances that use the name will
     * be disabled in their constructor.
     */
    private static final void disableLocal(@NonNull String name) {
        synchronized (sGlobalLock) {
            if (sDisabledKeys.contains(name)) {
                // The key is already in recorded so there is no further work to be done.
                return;
            }
            for (PropertyInvalidatedCache cache : sCaches.keySet()) {
                if (name.equals(cache.mCacheName)) {
                    cache.disableInstance();
                }
            }
            // Record the disabled key after the iteration.  If an exception occurs during the
            // iteration above, and the code is retried, the function should not exit early.
            sDisabledKeys.add(name);
        }
    }

    /**
     * Stop disabling local caches with a particular name.  Any caches that are currently
     * disabled remain disabled (the "disabled" setting is sticky).  However, new caches
     * with this name will not be disabled.  It is not an error if the cache name is not
     * found in the list of disabled caches.
     * @hide
     */
    @TestApi
    public final void forgetDisableLocal() {
        synchronized (sGlobalLock) {
            sDisabledKeys.remove(mCacheName);
        }
    }

    /**
     * Disable this cache in the current process, and all other caches that use the same
     * name.  This does not affect caches that have a different name but use the same
     * property.
     * TODO(216112648) Remove this in favor of disableForCurrentProcess().
     * @hide
     */
    public void disableLocal() {
        disableForCurrentProcess();
    }

    /**
     * Disable this cache in the current process, and all other present and future caches that use
     * the same name.  This does not affect caches that have a different name but use the same
     * property.  Once disabled, a cache cannot be reenabled.
     * @hide
     */
    @TestApi
    public void disableForCurrentProcess() {
        disableLocal(mCacheName);
    }

    /** @hide */
    @TestApi
    public static void disableForCurrentProcess(@NonNull String cacheName) {
        disableLocal(cacheName);
    }

    /**
     * Return whether a cache instance is disabled.
     * @hide
     */
    @TestApi
    public final boolean isDisabled() {
        return mDisabled || !sEnabled;
    }

    /**
     * Get a value from the cache or recompute it.
     * @hide
     */
    @TestApi
    public @Nullable Result query(@NonNull Query query) {
        // Let access to mDisabled race: it's atomic anyway.
        long currentNonce = (!isDisabled()) ? getCurrentNonce() : NONCE_DISABLED;
        if (!isReservedNonce(currentNonce)
            && bypass(query)) {
            currentNonce = NONCE_BYPASS;
        }
        for (;;) {
            if (isReservedNonce(currentNonce)) {
                if (!mDisabled) {
                    // Do not bother collecting statistics if the cache is
                    // locally disabled.
                    synchronized (mLock) {
                        mSkips[(int) currentNonce]++;
                    }
                }

                if (DEBUG) {
                    if (!mDisabled) {
                        Log.d(TAG, formatSimple(
                            "cache %s %s for %s",
                            cacheName(), sNonceName[(int) currentNonce], queryToString(query)));
                    }
                }
                return recompute(query);
            }

            final boolean cacheHit;
            final Result cachedResult;
            synchronized (mLock) {
                if (currentNonce == mLastSeenNonce) {
                    cachedResult = mCache.get(query);
                    if (cachedResult == null) {
                        if (mCacheNullResults) {
                            cacheHit = mCache.containsKey(query);
                        } else {
                            cacheHit = false;
                        }
                    } else {
                        cacheHit = true;
                    }
                    if (cacheHit) {
                        mHits++;
                    }
                } else {
                    if (DEBUG) {
                        Log.d(TAG, formatSimple(
                            "clearing cache %s of %d entries because nonce changed [%s] -> [%s]",
                            cacheName(), mCache.size(),
                            mLastSeenNonce, currentNonce));
                    }
                    clear();
                    mLastSeenNonce = currentNonce;
                    cacheHit = false;
                    cachedResult = null;
                }
            }

            // Cache hit --- but we're not quite done yet.  A value in the cache might need to
            // be augmented in a "refresh" operation.  The refresh operation can combine the
            // old and the new nonce values.  In order to make sure the new parts of the value
            // are consistent with the old, possibly-reused parts, we check the property value
            // again after the refresh and do the whole fetch again if the property invalidated
            // us while we were refreshing.
            if (cacheHit) {
                final Result refreshedResult = refresh(cachedResult, query);
                if (refreshedResult != cachedResult) {
                    if (DEBUG) {
                        Log.d(TAG, "cache refresh for " + cacheName() + " " + queryToString(query));
                    }
                    final long afterRefreshNonce = getCurrentNonce();
                    if (currentNonce != afterRefreshNonce) {
                        currentNonce = afterRefreshNonce;
                        if (DEBUG) {
                            Log.d(TAG, formatSimple(
                                    "restarting %s %s because nonce changed in refresh",
                                    cacheName(),
                                    queryToString(query)));
                        }
                        continue;
                    }
                    synchronized (mLock) {
                        if (currentNonce != mLastSeenNonce) {
                            // Do nothing: cache is already out of date. Just return the value
                            // we already have: there's no guarantee that the contents of mCache
                            // won't become invalid as soon as we return.
                        } else if (refreshedResult == null) {
                            mCache.remove(query);
                        } else {
                            mCache.put(query, refreshedResult);
                        }
                    }
                    return maybeCheckConsistency(query, refreshedResult);
                }
                if (DEBUG) {
                    Log.d(TAG, "cache hit for " + cacheName() + " " + queryToString(query));
                }
                return maybeCheckConsistency(query, cachedResult);
            }

            // Cache miss: make the value from scratch.
            if (DEBUG) {
                Log.d(TAG, "cache miss for " + cacheName() + " " + queryToString(query));
            }
            final Result result = recompute(query);
            synchronized (mLock) {
                // If someone else invalidated the cache while we did the recomputation, don't
                // update the cache with a potentially stale result.
                if (mLastSeenNonce == currentNonce) {
                    if (result != null || mCacheNullResults) {
                        mCache.put(query, result);
                    }
                    if (result == null) {
                        mNulls++;
                    }
                }
                mMisses++;
            }
            return maybeCheckConsistency(query, result);
        }
    }

    // Inner class avoids initialization in processes that don't do any invalidation
    private static final class NoPreloadHolder {
        private static final AtomicLong sNextNonce = new AtomicLong((new Random()).nextLong());
        public static long next() {
            return sNextNonce.getAndIncrement();
        }
    }

    /**
     * Non-static convenience version of disableSystemWide() for situations in which only a
     * single PropertyInvalidatedCache is keyed on a particular property value.
     *
     * When multiple caches share a single property value, using an instance method on one of
     * the cache objects to invalidate all of the cache objects becomes confusing and you should
     * just use the static version of this function.
     * @hide
     */
    @TestApi
    public final void disableSystemWide() {
        disableSystemWide(mPropertyName);
    }

    /**
     * Disable all caches system-wide that are keyed on {@var name}. This
     * function is synchronous: caches are invalidated and disabled upon return.
     *
     * @param name Name of the cache-key property to invalidate
     */
    private static void disableSystemWide(@NonNull String name) {
        getNonceHandler(name).disable();
    }

    /**
     * Non-static version of invalidateCache() for situations in which a cache instance is
     * available.  This is slightly faster than than the static versions because it does not have
     * to look up the NonceHandler for a given property name.
     * @hide
     */
    @TestApi
    public void invalidateCache() {
        mNonce.invalidate();
    }

    /**
     * Non-static version of corkInvalidations() for situations in which the cache instance is
     * available.  This is slightly faster than than the static versions because it does not have
     * to look up the NonceHandler for a given property name.
     * @hide
     */
    public void corkInvalidations() {
        mNonce.cork();
    }

    /**
     * Non-static version of uncorkInvalidations() for situations in which the cache instance is
     * available.  This is slightly faster than than the static versions because it does not have
     * to look up the NonceHandler for a given property name.
     * @hide
     */
    public void uncorkInvalidations() {
        mNonce.uncork();
    }

    /**
     * Invalidate caches in all processes that are keyed for the module and api.
     * @hide
     */
    @TestApi
    public static void invalidateCache(@NonNull String module, @NonNull String api) {
        invalidateCache(createPropertyName(module, api));
    }

    /**
     * Invalidate caches in all processes that have the module and api specified in the args.
     * @hide
     */
    public static void invalidateCache(@NonNull Args args) {
        invalidateCache(createPropertyName(args.mModule, args.mApi));
    }

    /**
     * Invalidate PropertyInvalidatedCache caches in all processes that are keyed on
     * {@var name}. This function is synchronous: caches are invalidated upon return.
     *
     * TODO(216112648) make this method private in favor of the two-argument (module, api)
     * override.
     *
     * @param name Name of the cache-key property to invalidate
     * @hide
     */
    public static void invalidateCache(@NonNull String name) {
        getNonceHandler(name).invalidate();
    }

    /**
     * Temporarily put the cache in the uninitialized state and prevent invalidations from
     * moving it out of that state: useful in cases where we want to avoid the overhead of a
     * large number of cache invalidations in a short time.  While the cache is corked, clients
     * bypass the cache and talk to backing services directly.  This property makes corking
     * correctness-preserving even if corked outside the lock that controls access to the
     * cache's backing service.
     *
     * corkInvalidations() and uncorkInvalidations() must be called in pairs.
     *
     * @param name Name of the cache-key property to cork
     * @hide
     */
    public static void corkInvalidations(@NonNull String name) {
        getNonceHandler(name).cork();
    }

    /**
     * Undo the effect of a cork, allowing cache invalidations to proceed normally.
     * Removing the last cork on a cache name invalidates the cache by side effect,
     * transitioning it to normal operation (unless explicitly disabled system-wide).
     *
     * @param name Name of the cache-key property to uncork
     * @hide
     */
    public static void uncorkInvalidations(@NonNull String name) {
        getNonceHandler(name).uncork();
    }

    /**
     * Time-based automatic corking helper. This class allows providers of cached data to
     * amortize the cost of cache invalidations by corking the cache immediately after a
     * modification (instructing clients to bypass the cache temporarily) and automatically
     * uncork after some period of time has elapsed.
     *
     * It's better to use explicit cork and uncork pairs that tighly surround big batches of
     * invalidations, but it's not always practical to tell where these invalidation batches
     * might occur. AutoCorker's time-based corking is a decent alternative.
     *
     * The auto-cork delay is configurable but it should not be too long.  The purpose of
     * the delay is to minimize the number of times a server writes to the system property
     * when invalidating the cache.  One write every 50ms does not hurt system performance.
     * @hide
     */
    public static final class AutoCorker {
        public static final int DEFAULT_AUTO_CORK_DELAY_MS = 50;

        private final String mPropertyName;
        private final int mAutoCorkDelayMs;
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private long mUncorkDeadlineMs = -1;  // SystemClock.uptimeMillis()
        @GuardedBy("mLock")
        private Handler mHandler;

        private NonceHandler mNonce;

        public AutoCorker(@NonNull String propertyName) {
            this(propertyName, DEFAULT_AUTO_CORK_DELAY_MS);
        }

        public AutoCorker(@NonNull String propertyName, int autoCorkDelayMs) {
            if (separatePermissionNotificationsEnabled()) {
                throw new IllegalStateException("AutoCorking is unavailable");
            }

            mPropertyName = propertyName;
            mAutoCorkDelayMs = autoCorkDelayMs;
            // We can't initialize mHandler here: when we're created, the main loop might not
            // be set up yet! Wait until we have a main loop to initialize our
            // corking callback.
        }

        public void autoCork() {
            synchronized (mLock) {
                if (mNonce == null) {
                    mNonce = getNonceHandler(mPropertyName);
                }
            }

            if (getLooper() == null) {
                // We're not ready to auto-cork yet, so just invalidate the cache immediately.
                if (DEBUG) {
                    Log.w(TAG, "invalidating instead of autocorking early in init: "
                            + mPropertyName);
                }
                mNonce.invalidate();
                return;
            }
            synchronized (mLock) {
                boolean alreadyQueued = mUncorkDeadlineMs >= 0;
                if (DEBUG) {
                    Log.d(TAG, formatSimple(
                            "autoCork %s mUncorkDeadlineMs=%s", mPropertyName,
                            mUncorkDeadlineMs));
                }
                mUncorkDeadlineMs = SystemClock.uptimeMillis() + mAutoCorkDelayMs;
                if (!alreadyQueued) {
                    getHandlerLocked().sendEmptyMessageAtTime(0, mUncorkDeadlineMs);
                    mNonce.cork();
                } else {
                    // Count this as a corked invalidation.
                    mNonce.invalidate();
                }
            }
        }

        private void handleMessage(Message msg) {
            synchronized (mLock) {
                if (DEBUG) {
                    Log.d(TAG, formatSimple(
                            "handleMsesage %s mUncorkDeadlineMs=%s",
                            mPropertyName, mUncorkDeadlineMs));
                }

                if (mUncorkDeadlineMs < 0) {
                    return;  // ???
                }
                long nowMs = SystemClock.uptimeMillis();
                if (mUncorkDeadlineMs > nowMs) {
                    mUncorkDeadlineMs = nowMs + mAutoCorkDelayMs;
                    if (DEBUG) {
                        Log.d(TAG, formatSimple(
                                        "scheduling uncork at %s",
                                        mUncorkDeadlineMs));
                    }
                    getHandlerLocked().sendEmptyMessageAtTime(0, mUncorkDeadlineMs);
                    return;
                }
                if (DEBUG) {
                    Log.d(TAG, "automatic uncorking " + mPropertyName);
                }
                mUncorkDeadlineMs = -1;
                mNonce.uncork();
            }
        }

        @GuardedBy("mLock")
        private Handler getHandlerLocked() {
            if (mHandler == null) {
                mHandler = new Handler(getLooper()) {
                        @Override
                        public void handleMessage(Message msg) {
                            AutoCorker.this.handleMessage(msg);
                        }
                    };
            }
            return mHandler;
        }

        /**
         * Return a looper for auto-uncork messages.  Messages should be processed on the
         * background thread, not on the main thread.
         */
        private static Looper getLooper() {
            return BackgroundThread.getHandler().getLooper();
        }
    }

    /**
     * Return the result generated by a given query to the cache, performing debugging checks when
     * enabled.
     */
    private Result maybeCheckConsistency(Query query, Result proposedResult) {
        if (VERIFY) {
            Result resultToCompare = recompute(query);
            boolean nonceChanged = (getCurrentNonce() != mLastSeenNonce);
            if (!nonceChanged && !resultEquals(proposedResult, resultToCompare)) {
                Log.e(TAG, formatSimple(
                        "cache %s inconsistent for %s is %s should be %s",
                        cacheName(), queryToString(query),
                        proposedResult, resultToCompare));
            }
            // Always return the "true" result in verification mode.
            return resultToCompare;
        }
        return proposedResult;
    }

    /**
     * Return the name of the cache, to be used in debug messages.  This is exposed
     * primarily for testing.
     * @hide
     */
    public final @NonNull String cacheName() {
        return mCacheName;
    }

    /**
     * Return the property used by the cache.  This is primarily for test purposes.
     * @hide
     */
    public final @NonNull String propertyName() {
        return mPropertyName;
    }

    /**
     * Return the query as a string, to be used in debug messages.  New clients should not
     * override this, but should instead add the necessary toString() method to the Query
     * class.
     * TODO(216112648) add a method in the QueryHandler and deprecate this API.
     * @hide
     */
    protected @NonNull String queryToString(@NonNull Query query) {
        return Objects.toString(query);
    }

    /**
     * Disable all caches in the local process.  This is primarily useful for testing when
     * the test needs to bypass the cache or when the test is for a server, and the test
     * process does not have privileges to write SystemProperties. Once disabled it is not
     * possible to re-enable caching in the current process.  If a client wants to
     * temporarily disable caching, use the corking mechanism.
     * @hide
     */
    @TestApi
    public static void disableForTestMode() {
        Log.d(TAG, "disabling all caches in the process");
        sEnabled = false;
    }

    /**
     * Report the disabled status of this cache instance.  The return value does not
     * reflect status of the property key.
     * @hide
     */
    @TestApi
    public boolean getDisabledState() {
        return isDisabled();
    }

    /**
     * Return the number of entries in the cache.  This is used for testing and has package-only
     * visibility.
     * @hide
     */
    public int size() {
        synchronized (mLock) {
            return mCache.size();
        }
    }

    /**
     * Returns a list of caches alive at the current time.
     */
    private static @NonNull ArrayList<PropertyInvalidatedCache> getActiveCaches() {
        synchronized (sGlobalLock) {
            return new ArrayList<PropertyInvalidatedCache>(sCaches.keySet());
        }
    }

    /**
     * Switches that can be used to control the detail emitted by a cache dump.  The
     * "CONTAINS" switches match if the cache (property) name contains the switch
     * argument.  The "LIKE" switches match if the cache (property) name matches the
     * switch argument as a regex.  The regular expression must match the entire name,
     * which generally means it may need leading/trailing "." expressions.
     */
    final static String NAME_CONTAINS = "-name-has=";
    final static String NAME_LIKE = "-name-like=";
    final static String PROPERTY_CONTAINS = "-property-has=";
    final static String PROPERTY_LIKE = "-property-like=";
    final static String BRIEF = "-brief";

    /**
     * Return true if any argument is a detailed specification switch.
     */
    private static boolean anyDetailed(String[] args) {
        for (String a : args) {
            if (a.startsWith(NAME_CONTAINS) || a.startsWith(NAME_LIKE)
                || a.startsWith(PROPERTY_CONTAINS) || a.startsWith(PROPERTY_LIKE)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A helper method to determine if a string matches a switch.
     */
    private static boolean chooses(String arg, String key, String reference, boolean contains) {
        if (arg.startsWith(key)) {
            final String value = arg.substring(key.length());
            if (contains) {
                return reference.contains(value);
            } else {
                return reference.matches(value);
            }
        }
        return false;
    }

    /**
     * Return true if this cache should be dumped in detail.  This method is not called
     * unless it has already been determined that there is at least one match requested.
     */
    private boolean showDetailed(String[] args) {
        for (String a : args) {
            if (chooses(a, NAME_CONTAINS, cacheName(), true)
                || chooses(a, NAME_LIKE, cacheName(), false)
                || chooses(a, PROPERTY_CONTAINS, mPropertyName, true)
                || chooses(a, PROPERTY_LIKE, mPropertyName, false)) {
                return true;
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    private long getSkipsLocked() {
        int sum = 0;
        for (int i = 0; i < mSkips.length; i++) {
            sum += mSkips[i];
        }
        return sum;
    }

    // Return true if this cache has had any activity.  If the hits, misses, and skips are all
    // zero then the client never tried to use the cache.  If invalidations and corks are also
    // zero then the server never tried to use the cache.
    private boolean isActive(NonceHandler.Stats stats) {
        synchronized (mLock) {
            return mHits + mMisses + getSkipsLocked()
                    + stats.invalidated + stats.corkedInvalidates > 0;
        }
    }

    @NeverCompile
    private void dumpContents(PrintWriter pw, boolean detailed, String[] args) {
        // If the user has requested specific caches and this is not one of them, return
        // immediately.
        if (detailed && !showDetailed(args)) {
            return;
        }
        // Does the user want brief output?
        boolean brief = false;
        for (String a : args) brief |= a.equals(BRIEF);

        NonceHandler.Stats stats = mNonce.getStats();

        synchronized (mLock) {
            if (brief && !isActive(stats)) {
                return;
            }

            pw.println(formatSimple("  Cache Name: %s", cacheName()));
            pw.println(formatSimple("    Property: %s", mPropertyName));
            pw.println(formatSimple(
                "    Hits: %d, Misses: %d, Skips: %d, Clears: %d, Nulls: %d",
                mHits, mMisses, getSkipsLocked(), mClears, mNulls));

            // Print all the skip reasons.
            pw.format("    Skip-%s: %d", sNonceName[0], mSkips[0]);
            for (int i = 1; i < mSkips.length; i++) {
                pw.format(", Skip-%s: %d", sNonceName[i], mSkips[i]);
            }
            pw.println();

            pw.println(formatSimple(
                "    Nonce: 0x%016x, Invalidates: %d, Corked: %d",
                mLastSeenNonce, stats.invalidated, stats.corkedInvalidates));
            pw.println(formatSimple(
                "    Current Size: %d, Max Size: %d, HW Mark: %d, Overflows: %d",
                mCache.size(), mMaxEntries, mHighWaterMark, mMissOverflow));
            mCache.dump(pw);
            pw.println(formatSimple("    Enabled: %s", mDisabled ? "false" : "true"));

            // Dump the contents of the cache.
            if (detailed) {
                mCache.dumpDetailed(pw);
            }

            // Separator between caches.
            pw.println("");
        }
    }

    /**
     * Without arguments, this dumps statistics from every cache in the process to the
     * provided ParcelFileDescriptor.  Optional switches allow the caller to choose
     * specific caches (selection is by cache name or property name); if these switches
     * are used then the output includes both cache statistics and cache entries.
     */
    @NeverCompile
    private static void dumpCacheInfo(@NonNull PrintWriter pw, @NonNull String[] args) {
        if (!sEnabled) {
            pw.println("  Caching is disabled in this process.");
            return;
        }

        // See if detailed is requested for any cache.  If there is a specific detailed request,
        // then only that cache is reported.
        boolean detail = anyDetailed(args);

        if (sSharedMemoryAvailable) {
            pw.println("  SharedMemory: enabled");
            NonceStore.getInstance().dump(pw, "    ", detail);
        } else {
            pw.println("  SharedMemory: disabled");
         }
        pw.println();

        ArrayList<PropertyInvalidatedCache> activeCaches = getActiveCaches();
        for (int i = 0; i < activeCaches.size(); i++) {
            PropertyInvalidatedCache currentCache = activeCaches.get(i);
            currentCache.dumpContents(pw, detail, args);
        }
    }

    /**
     * Without arguments, this dumps statistics from every cache in the process to the
     * provided ParcelFileDescriptor.  Optional switches allow the caller to choose
     * specific caches (selection is by cache name or property name); if these switches
     * are used then the output includes both cache statistics and cache entries.
     * @hide
     */
    @NeverCompile
    public static void dumpCacheInfo(@NonNull ParcelFileDescriptor pfd, @NonNull String[] args) {
        // Create a PrintWriter that uses a byte array.  The code can safely write to
        // this array without fear of blocking.  The completed byte array will be sent
        // to the caller after all the data has been collected and all locks have been
        // released.
        ByteArrayOutputStream barray = new ByteArrayOutputStream();
        PrintWriter bout = new PrintWriter(barray);
        dumpCacheInfo(bout, args);
        bout.close();

        try {
            // Send the final byte array to the output.  This happens outside of all locks.
            var out = new FileOutputStream(pfd.getFileDescriptor());
            barray.writeTo(out);
            out.close();
            barray.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to dump PropertyInvalidatedCache instances");
        }
    }

    /**
     * Nonces in shared memory are supported by a string block that acts as a table of contents
     * for nonce names, and an array of nonce values.  There are two key design principles with
     * respect to nonce maps:
     *
     * 1. It is always okay if a nonce value cannot be determined.  If the nonce is UNSET, the
     * cache is bypassed, which is always functionally correct.  Clients do not take extraordinary
     * measures to be current with the nonce map.  Clients must be current with the nonce itself;
     * this is achieved through the shared memory.
     *
     * 2. Once a name is mapped to a nonce index, the mapping is fixed for the lifetime of the
     * system.  It is only necessary to distinguish between the unmapped and mapped states.  Once
     * a client has mapped a nonce, that mapping is known to be good for the lifetime of the
     * system.
     * @hide
     */
    @VisibleForTesting
    public static class NonceStore {

        // A lock for the store.
        private final Object mLock = new Object();

        // The native pointer.  This is not owned by this class.  It is owned by
        // ApplicationSharedMemory, and it disappears when the owning instance is closed.
        private final long mPtr;

        // True if the memory is immutable.
        private final boolean mMutable;

        // The maximum length of a string in the string block.  The maximum length must fit in a
        // byte, but a smaller value has been chosen to limit memory use.  Because strings are
        // run-length encoded, a string consumes at most MAX_STRING_LENGTH+1 bytes in the string
        // block.
        private static final int MAX_STRING_LENGTH = 63;

        // The expected hash code of the string block.  If the hash over the string block equals
        // this value, then the string block is valid.  Otherwise, the block is not valid and
        // should be re-read.  An invalid block generally means that a client has read the shared
        // memory while the server was still writing it.
        @GuardedBy("mLock")
        private int mBlockHash = 0;

        // The number of nonces that the native layer can hold.  This is maintained for debug and
        // logging.
        private final int mMaxNonce;

        // The size of the native byte block.
        private final int mMaxByte;

        /** @hide */
        @VisibleForTesting
        public NonceStore(long ptr, boolean mutable) {
            mPtr = ptr;
            mMutable = mutable;
            mMaxByte = nativeGetMaxByte(ptr);
            mMaxNonce = nativeGetMaxNonce(ptr);
            refreshStringBlockLocked();
        }

        // The static lock for singleton acquisition.
        private static Object sLock = new Object();

        // NonceStore is supposed to be a singleton.
        private static NonceStore sInstance;

        // Return the singleton instance.
        static NonceStore getInstance() {
            synchronized (sLock) {
                if (sInstance == null) {
                    try {
                        ApplicationSharedMemory shmem = ApplicationSharedMemory.getInstance();
                        sInstance = (shmem == null)
                                    ? null
                                    : new NonceStore(shmem.getSystemNonceBlock(),
                                            shmem.isMutable());
                    } catch (IllegalStateException e) {
                        // ApplicationSharedMemory.getInstance() throws if the shared memory is
                        // not yet mapped.  Swallow the exception and leave sInstance null.
                    }
                }
                return sInstance;
            }
        }

        // The index value of an unmapped name.
        public static final int INVALID_NONCE_INDEX = -1;

        // The highest string index extracted from the string block.  -1 means no strings have
        // been seen.  This is used to skip strings that have already been processed, when the
        // string block is updated.
        @GuardedBy("mLock")
        private int mHighestIndex = -1;

        // The number bytes of the string block that has been used.  This is a statistics.
        @GuardedBy("mLock")
        private int mStringBytes = 0;

        // The number of partial reads on the string block.  This is a statistic.
        @GuardedBy("mLock")
        private int mPartialReads = 0;

        // The number of times the string block was updated.  This is a statistic.
        @GuardedBy("mLock")
        private int mStringUpdated = 0;

        // Map a string to a native index.
        @GuardedBy("mLock")
        private final ArrayMap<String, Integer> mStringHandle = new ArrayMap<>();

        // Update the string map from the current string block.  The string block is not modified
        // and the block hash is not checked.  The function skips past strings that have already
        // been read, and then processes any new strings.
        @GuardedBy("mLock")
        private void updateStringMapLocked(byte[] block) {
            int index = 0;
            int offset = 0;
            while (offset < block.length && block[offset] != 0) {
                if (index > mHighestIndex) {
                    // Only record the string if it has not been seen yet.
                    final String s = new String(block, offset+1, block[offset]);
                    mStringHandle.put(s, index);
                    mHighestIndex = index;
                }
                offset += block[offset] + 1;
                index++;
            }
            mStringBytes = offset;
        }

        // Append a string to the string block and update the hash.  This does not write the block
        // to shared memory.
        @GuardedBy("mLock")
        private void appendStringToMapLocked(@NonNull String str, @NonNull byte[] block) {
            int offset = 0;
            while (offset < block.length && block[offset] != 0) {
                offset += block[offset] + 1;
            }
            final byte[] strBytes = str.getBytes();

            if (offset + strBytes.length >= block.length) {
                // Overflow.  Do not add the string to the block; the string will remain undefined.
                return;
            }

            block[offset] = (byte) strBytes.length;
            System.arraycopy(strBytes, 0, block, offset+1, strBytes.length);
            mBlockHash = Arrays.hashCode(block);
        }

        // Possibly update the string block.  If the native shared memory has a new block hash,
        // then read the new string block values from shared memory, as well as the new hash.
        @GuardedBy("mLock")
        private void refreshStringBlockLocked() {
            if (mBlockHash == nativeGetByteBlockHash(mPtr)) {
                // The fastest way to know that the shared memory string block has not changed.
                return;
            }
            byte[] block = new byte[mMaxByte];
            final int hash = nativeGetByteBlock(mPtr, mBlockHash, block);
            if (hash != Arrays.hashCode(block)) {
                // This is a partial read: ignore it.  The next time someone needs this string
                // the memory will be read again and should succeed.  Set the local hash to
                // zero to ensure that the next read attempt will actually read from shared
                // memory.
                mBlockHash = 0;
                mPartialReads++;
                return;
            }
            // The hash has changed.  Update the strings from the byte block.
            mStringUpdated++;
            mBlockHash = hash;
            updateStringMapLocked(block);
        }

        // Throw an exception if the string cannot be stored in the string block.
        private static void throwIfBadString(@NonNull String s) {
            if (s.length() == 0) {
                throw new IllegalArgumentException("cannot store an empty string");
            }
            if (s.length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException("cannot store a string longer than "
                        + MAX_STRING_LENGTH);
            }
        }

        // Throw an exception if the nonce handle is invalid.  The handle is bad if it is out of
        // range of allocated handles.  Note that NONCE_HANDLE_INVALID will throw: this is
        // important for setNonce().
        @GuardedBy("mLock")
        private void throwIfBadHandle(int handle) {
            if (handle < 0 || handle > mHighestIndex) {
                throw new IllegalArgumentException("invalid nonce handle: " + handle);
            }
        }

        // Throw if the memory is immutable (the process does not have write permission).  The
        // exception mimics the permission-denied exception thrown when a process writes to an
        // unauthorized system property.
        private void throwIfImmutable() {
            if (!mMutable) {
                throw new RuntimeException("write permission denied");
            }
        }

        static final AtomicLong sStoreCount = new AtomicLong();


        // Add a string to the local copy of the block and write the block to shared memory.
        // Return the index of the new string.  If the string has already been recorded, the
        // shared memory is not updated but the index of the existing string is returned.
        public int storeName(@NonNull String str) {
            synchronized (mLock) {
                Integer handle = mStringHandle.get(str);
                if (handle == null) {
                    throwIfImmutable();
                    throwIfBadString(str);
                    byte[] block = new byte[mMaxByte];
                    nativeGetByteBlock(mPtr, 0, block);
                    appendStringToMapLocked(str, block);
                    nativeSetByteBlock(mPtr, mBlockHash, block);
                    updateStringMapLocked(block);
                    handle = mStringHandle.get(str);
                }
                return handle;
            }
        }

        // Retrieve the handle for a string.  -1 is returned if the string is not found.
        public int getHandleForName(@NonNull String str) {
            synchronized (mLock) {
                Integer handle = mStringHandle.get(str);
                if (handle == null) {
                    refreshStringBlockLocked();
                    handle  = mStringHandle.get(str);
                }
                return (handle != null) ? handle : INVALID_NONCE_INDEX;
            }
        }

        // Thin wrapper around the native method.
        public boolean setNonce(int handle, long value) {
            synchronized (mLock) {
                throwIfBadHandle(handle);
                throwIfImmutable();
                return nativeSetNonce(mPtr, handle, value);
            }
        }

        public long getNonce(int handle) {
            synchronized (mLock) {
                throwIfBadHandle(handle);
                return nativeGetNonce(mPtr, handle);
            }
        }

        /**
         * Dump the nonce statistics
         */
        public void dump(@NonNull PrintWriter pw, @NonNull String prefix, boolean detailed) {
            synchronized (mLock) {
                pw.println(formatSimple(
                    "%sStringsMapped: %d, BytesUsed: %d",
                    prefix, mHighestIndex, mStringBytes));
                pw.println(formatSimple(
                    "%sPartialReads: %d, StringUpdates: %d",
                    prefix, mPartialReads, mStringUpdated));

                if (detailed) {
                    for (String s: mStringHandle.keySet()) {
                        int h = mStringHandle.get(s);
                        pw.println(formatSimple(
                            "%sHandle:%d Name:%s", prefix, h, s));
                    }
                }
            }
        }
    }

    /**
     * Return the maximum number of nonces supported in the native layer.
     *
     * @param mPtr the pointer to the native shared memory.
     * @return the number of nonces supported by the shared memory.
     */
    @FastNative
    private static native int nativeGetMaxNonce(long mPtr);

    /**
     * Return the maximum number of string bytes supported in the native layer.
     *
     * @param mPtr the pointer to the native shared memory.
     * @return the number of string bytes supported by the shared memory.
     */
    @FastNative
    private static native int nativeGetMaxByte(long mPtr);

    /**
     * Write the byte block and set the hash into shared memory.  The method is relatively
     * forgiving, in that any non-null byte array will be stored without error.  The number of
     * bytes will the lesser of the length of the block parameter and the size of the native
     * array.  The native layer performs no checks on either byte block or the hash.
     *
     * @param mPtr the pointer to the native shared memory.
     * @param hash a value to be stored in the native block hash.
     * @param block the byte array to be store.
     */
    @FastNative
    private static native void nativeSetByteBlock(long mPtr, int hash, @NonNull byte[] block);

    /**
     * Retrieve the string block into the array and return the hash value.  If the incoming hash
     * value is the same as the hash in shared memory, the native function returns immediately
     * without touching the block parameter.  Note that a zero hash value will always cause shared
     * memory to be read.  The number of bytes read is the lesser of the length of the block
     * parameter and the size of the native array.
     *
     * @param mPtr the pointer to the native shared memory.
     * @param hash a value to be compared against the hash in the native layer.
     * @param block an array to receive the bytes from the native layer.
     * @return the hash from the native layer.
     */
    @FastNative
    private static native int nativeGetByteBlock(long mPtr, int hash, @NonNull byte[] block);

    /**
     * Retrieve just the byte block hash from the native layer.  The function is CriticalNative
     * and thus very fast.
     *
     * @param mPtr the pointer to the native shared memory.
     * @return the current native hash value.
     */
    @CriticalNative
    private static native int nativeGetByteBlockHash(long mPtr);

    /**
     * Set a nonce at the specified index.  The index is checked against the size of the native
     * nonce array and the function returns true if the index is valid, and false.  The function
     * is CriticalNative and thus very fast.
     *
     * @param mPtr the pointer to the native shared memory.
     * @param index the index of the nonce to set.
     * @param value the value to set for the nonce.
     * @return true if the index is inside the nonce array and false otherwise.
     */
    @CriticalNative
    private static native boolean nativeSetNonce(long mPtr, int index, long value);

    /**
     * Get the nonce from the specified index.  The index is checked against the size of the
     * native nonce array; the function returns the nonce value if the index is valid, and 0
     * otherwise.  The function is CriticalNative and thus very fast.
     *
     * @param mPtr the pointer to the native shared memory.
     * @param index the index of the nonce to retrieve.
     * @return the value of the specified nonce, of 0 if the index is out of bounds.
     */
    @CriticalNative
    private static native long nativeGetNonce(long mPtr, int index);
}
