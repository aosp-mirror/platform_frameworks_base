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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastPrintWriter;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LRU cache that's invalidated when an opaque value in a property changes. Self-synchronizing,
 * but doesn't hold a lock across data fetches on query misses.
 *
 * The intended use case is caching frequently-read, seldom-changed information normally
 * retrieved across interprocess communication. Imagine that you've written a user birthday
 * information daemon called "birthdayd" that exposes an {@code IUserBirthdayService} interface
 * over binder. That binder interface looks something like this:
 *
 * <pre>
 * parcelable Birthday {
 *   int month;
 *   int day;
 * }
 * interface IUserBirthdayService {
 *   Birthday getUserBirthday(int userId);
 * }
 * </pre>
 *
 * Suppose the service implementation itself looks like this...
 *
 * <pre>
 * public class UserBirthdayServiceImpl implements IUserBirthdayService {
 *   private final HashMap&lt;Integer, Birthday%&gt; mUidToBirthday;
 *   {@literal @}Override
 *   public synchronized Birthday getUserBirthday(int userId) {
 *     return mUidToBirthday.get(userId);
 *   }
 *   private synchronized void updateBirthdays(Map&lt;Integer, Birthday%&gt; uidToBirthday) {
 *     mUidToBirthday.clear();
 *     mUidToBirthday.putAll(uidToBirthday);
 *   }
 * }
 * </pre>
 *
 * ... and we have a client in frameworks (loaded into every app process) that looks
 * like this:
 *
 * <pre>
 * public class ActivityThread {
 *   ...
 *   public Birthday getUserBirthday(int userId) {
 *     return GetService("birthdayd").getUserBirthday(userId);
 *   }
 *   ...
 * }
 * </pre>
 *
 * With this code, every time an app calls {@code getUserBirthday(uid)}, we make a binder call
 * to the birthdayd process and consult its database of birthdays. If we query user birthdays
 * frequently, we do a lot of work that we don't have to do, since user birthdays
 * change infrequently.
 *
 * PropertyInvalidatedCache is part of a pattern for optimizing this kind of
 * information-querying code. Using {@code PropertyInvalidatedCache}, you'd write the client
 * this way:
 *
 * <pre>
 * public class ActivityThread {
 *   ...
 *   private final PropertyInvalidatedCache.QueryHandler&lt;Integer, Birthday&gt; mBirthdayQuery =
 *       new PropertyInvalidatedCache.QueryHandler&lt;Integer, Birthday&gt;() {
 *           {@literal @}Override
 *           public Birthday apply(Integer) {
 *              return GetService("birthdayd").getUserBirthday(userId);
 *           }
 *       };
 *   private static final int BDAY_CACHE_MAX = 8;  // Maximum birthdays to cache
 *   private static final String BDAY_CACHE_KEY = "cache_key.birthdayd";
 *   private final PropertyInvalidatedCache&lt;Integer, Birthday%&gt; mBirthdayCache = new
 *     PropertyInvalidatedCache&lt;Integer, Birthday%&gt;(
 *             BDAY_CACHE_MAX, MODULE_SYSTEM, "getUserBirthday", mBirthdayQuery);
 *
 *   public void disableUserBirthdayCache() {
 *     mBirthdayCache.disableForCurrentProcess();
 *   }
 *   public void invalidateUserBirthdayCache() {
 *     mBirthdayCache.invalidateCache();
 *   }
 *   public Birthday getUserBirthday(int userId) {
 *     return mBirthdayCache.query(userId);
 *   }
 *   ...
 * }
 * </pre>
 *
 * With this cache, clients perform a binder call to birthdayd if asking for a user's birthday
 * for the first time; on subsequent queries, we return the already-known Birthday object.
 *
 * The second parameter to the IpcDataCache constructor is a string that identifies the "module"
 * that owns the cache. There are some well-known modules (such as {@code MODULE_SYSTEM} but any
 * string is permitted.  The third parameters is the name of the API being cached; this, too, can
 * any value.  The fourth is the name of the cache.  The cache is usually named after th API.
 * Some things you must know about the three strings:
 * <list>
 * <ul> The system property that controls the cache is named {@code cache_key.<module>.<api>}.
 * Usually, the SELinux rules permit a process to write a system property (and therefore
 * invalidate a cache) based on the wildcard {@code cache_key.<module>.*}.  This means that
 * although the cache can be constructed with any module string, whatever string is chosen must be
 * consistent with the SELinux configuration.
 * <ul> The API name can be any string of alphanumeric characters.  All caches with the same API
 * are invalidated at the same time.  If a server supports several caches and all are invalidated
 * in common, then it is most efficient to assign the same API string to every cache.
 * <ul> The cache name can be any string.  In debug output, the name is used to distiguish between
 * caches with the same API name.  The cache name is also used when disabling caches in the
 * current process.  So, invalidation is based on the module+api but disabling (which is generally
 * a once-per-process operation) is based on the cache name.
 * </list>
 *
 * User birthdays do occasionally change, so we have to modify the server to invalidate this
 * cache when necessary. That invalidation code looks like this:
 *
 * <pre>
 * public class UserBirthdayServiceImpl {
 *   ...
 *   public UserBirthdayServiceImpl() {
 *     ...
 *     ActivityThread.currentActivityThread().disableUserBirthdayCache();
 *     ActivityThread.currentActivityThread().invalidateUserBirthdayCache();
 *   }
 *
 *   private synchronized void updateBirthdays(Map&lt;Integer, Birthday%&gt; uidToBirthday) {
 *     mUidToBirthday.clear();
 *     mUidToBirthday.putAll(uidToBirthday);
 *     ActivityThread.currentActivityThread().invalidateUserBirthdayCache();
 *   }
 *   ...
 * }
 * </pre>
 *
 * The call to {@code PropertyInvalidatedCache.invalidateCache()} guarantees that all clients
 * will re-fetch birthdays from binder during consequent calls to
 * {@code ActivityThread.getUserBirthday()}. Because the invalidate call happens with the lock
 * held, we maintain consistency between different client views of the birthday state. The use
 * of PropertyInvalidatedCache in this idiomatic way introduces no new race conditions.
 *
 * PropertyInvalidatedCache has a few other features for doing things like incremental
 * enhancement of cached values and invalidation of multiple caches (that all share the same
 * property key) at once.
 *
 * {@code BDAY_CACHE_KEY} is the name of a property that we set to an opaque unique value each
 * time we update the cache. SELinux configuration must allow everyone to read this property
 * and it must allow any process that needs to invalidate the cache (here, birthdayd) to write
 * the property. (These properties conventionally begin with the "cache_key." prefix.)
 *
 * The {@code UserBirthdayServiceImpl} constructor calls {@code disableUserBirthdayCache()} so
 * that calls to {@code getUserBirthday} from inside birthdayd don't go through the cache. In
 * this local case, there's no IPC, so use of the cache is (depending on exact
 * circumstance) unnecessary.
 *
 * There may be queries for which it is more efficient to bypass the cache than to cache
 * the result.  This would be true, for example, if some queries would require frequent
 * cache invalidation while other queries require infrequent invalidation.  To expand on
 * the birthday example, suppose that there is a userId that signifies "the next
 * birthday".  When passed this userId, the server returns the next birthday among all
 * users - this value changes as time advances.  The userId value can be cached, but the
 * cache must be invalidated whenever a birthday occurs, and this invalidates all
 * birthdays.  If there is a large number of users, invalidation will happen so often that
 * the cache provides no value.
 *
 * The class provides a bypass mechanism to handle this situation.
 * <pre>
 * public class ActivityThread {
 *   ...
 *   private final IpcDataCache.QueryHandler&lt;Integer, Birthday&gt; mBirthdayQuery =
 *       new IpcDataCache.QueryHandler&lt;Integer, Birthday&gt;() {
 *           {@literal @}Override
 *           public Birthday apply(Integer) {
 *              return GetService("birthdayd").getUserBirthday(userId);
 *           }
 *           {@literal @}Override
 *           public boolean shouldBypassQuery(Integer userId) {
 *               return userId == NEXT_BIRTHDAY;
 *           }
 *       };
 *   ...
 * }
 * </pre>
 *
 * If the {@code shouldBypassQuery()} method returns true then the cache is not used for that
 * particular query.  The {@code shouldBypassQuery()} method is not abstract and the default
 * implementation returns false.
 *
 * For security, there is a allowlist of processes that are allowed to invalidate a cache.
 * The allowlist includes normal runtime processes but does not include test processes.
 * Test processes must call {@code PropertyInvalidatedCache.disableForTestMode()} to disable
 * all cache activity in that process.
 *
 * Caching can be disabled completely by initializing {@code sEnabled} to false and rebuilding.
 *
 * To test a binder cache, create one or more tests that exercise the binder method.  This
 * should be done twice: once with production code and once with a special image that sets
 * {@code DEBUG} and {@code VERIFY} true.  In the latter case, verify that no cache
 * inconsistencies are reported.  If a cache inconsistency is reported, however, it might be a
 * false positive.  This happens if the server side data can be read and written non-atomically
 * with respect to cache invalidation.
 *
 * @param <Query> The class used to index cache entries: must be hashable and comparable
 * @param <Result> The class holding cache entries; use a boxed primitive if possible
 * @hide
 */
@TestApi
public class PropertyInvalidatedCache<Query, Result> {
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
         * Return true if a query should not use the cache.  The default implementation
         * always uses the cache.
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

        return "cache_key." + module + "." + new String(suffix);
    }

    /**
     * Reserved nonce values.  Use isReservedNonce() to test for a reserved value.  Note
     * that all values cause the cache to be skipped.
     */
    private static final int NONCE_UNSET = 0;
    private static final int NONCE_DISABLED = 1;
    private static final int NONCE_CORKED = 2;
    private static final int NONCE_BYPASS = 3;

    private static boolean isReservedNonce(long n) {
        return n >= NONCE_UNSET && n <= NONCE_BYPASS;
    }

    /**
     * The names of the nonces
     */
    private static final String[] sNonceName =
            new String[]{ "unset", "disabled", "corked", "bypass" };

    private static final String TAG = "PropertyInvalidatedCache";
    private static final boolean DEBUG = false;
    private static final boolean VERIFY = false;

    /**
     * The object-private lock.
     */
    private final Object mLock = new Object();

    // Per-Cache performance counters.
    @GuardedBy("mLock")
    private long mHits = 0;

    @GuardedBy("mLock")
    private long mMisses = 0;

    @GuardedBy("mLock")
    private long[] mSkips = new long[]{ 0, 0, 0, 0 };

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
     * Record the number of invalidate or cork calls that were nops because the cache was already
     * corked.  This is static because invalidation is done in a static context.  Entries are
     * indexed by the cache property.
     */
    @GuardedBy("sCorkLock")
    private static final HashMap<String, Long> sCorkedInvalidates = new HashMap<>();

    /**
     * A map of cache keys that we've "corked". (The values are counts.)  When a cache key is
     * corked, we skip the cache invalidate when the cache key is in the unset state --- that
     * is, when a cache key is corked, an invalidation does not enable the cache if somebody
     * else hasn't disabled it.
     */
    @GuardedBy("sCorkLock")
    private static final HashMap<String, Integer> sCorks = new HashMap<>();

    /**
     * A lock for the global list of caches and cache keys.  This must never be taken inside mLock
     * or sCorkLock.
     */
    private static final Object sGlobalLock = new Object();

    /**
     * A map of cache keys that have been disabled in the local process.  When a key is
     * disabled locally, existing caches are disabled and the key is saved in this map.
     * Future cache instances that use the same key will be disabled in their constructor.
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
     * Counts of the number of times a cache key was invalidated.  Invalidation occurs in a static
     * context with no cache object available, so this is a static map.  Entries are indexed by
     * the cache property.
     */
    @GuardedBy("sGlobalLock")
    private static final HashMap<String, Long> sInvalidates = new HashMap<>();

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
     * Handle to the {@code mPropertyName} property, transitioning to non-{@code null} once the
     * property exists on the system.
     */
    private volatile SystemProperties.Handle mPropertyHandle;

    /**
     * The name by which this cache is known.  This should normally be the
     * binder call that is being cached, but the constructors default it to
     * the property name.
     */
    private final String mCacheName;

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

    @GuardedBy("mLock")
    private final LinkedHashMap<Query, Result> mCache;

    /**
     * The last value of the {@code mPropertyHandle} that we observed.
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
    public PropertyInvalidatedCache(int maxEntries, @NonNull String propertyName) {
        this(maxEntries, propertyName, propertyName);
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
    public PropertyInvalidatedCache(int maxEntries, @NonNull String propertyName,
            @NonNull String cacheName) {
        mPropertyName = propertyName;
        mCacheName = cacheName;
        mMaxEntries = maxEntries;
        mComputer = new DefaultComputer<>(this);
        mCache = createMap();
        registerCache();
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
        mPropertyName = createPropertyName(module, api);
        mCacheName = cacheName;
        mMaxEntries = maxEntries;
        mComputer = computer;
        mCache = createMap();
        registerCache();
    }

    // Create a map.  This should be called only from the constructor.
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
     * SystemProperties are protected and cannot be written (or read, usually) by random
     * processes.  So, for testing purposes, the methods have a bypass mode that reads and
     * writes to a HashMap and does not go out to the SystemProperties at all.
     */

    // If true, the cache might be under test.  If false, there is no testing in progress.
    private static volatile boolean sTesting = false;

    // If sTesting is true then keys that are under test are in this map.
    private static final HashMap<String, Long> sTestingPropertyMap = new HashMap<>();

    /**
     * Enable or disable testing.  The testing property map is cleared every time this
     * method is called.
     * @hide
     */
    @TestApi
    public static void setTestMode(boolean mode) {
        sTesting = mode;
        synchronized (sTestingPropertyMap) {
            sTestingPropertyMap.clear();
        }
    }

    /**
     * Enable testing the specific cache key.  Only keys in the map are subject to testing.
     * There is no method to stop testing a property name.  Just disable the test mode.
     */
    private static void testPropertyName(@NonNull String name) {
        synchronized (sTestingPropertyMap) {
            sTestingPropertyMap.put(name, (long) NONCE_UNSET);
        }
    }

    /**
     * Enable testing the specific cache key.  Only keys in the map are subject to testing.
     * There is no method to stop testing a property name.  Just disable the test mode.
     * @hide
     */
    @TestApi
    public void testPropertyName() {
        testPropertyName(mPropertyName);
    }

    // Read the system property associated with the current cache.  This method uses the
    // handle for faster reading.
    private long getCurrentNonce() {
        if (sTesting) {
            synchronized (sTestingPropertyMap) {
                Long n = sTestingPropertyMap.get(mPropertyName);
                if (n != null) {
                    return n;
                }
            }
        }

        SystemProperties.Handle handle = mPropertyHandle;
        if (handle == null) {
            handle = SystemProperties.find(mPropertyName);
            if (handle == null) {
                return NONCE_UNSET;
            }
            mPropertyHandle = handle;
        }
        return handle.getLong(NONCE_UNSET);
    }

    // Write the nonce in a static context.  No handle is available.
    private static void setNonce(String name, long val) {
        if (sTesting) {
            synchronized (sTestingPropertyMap) {
                Long n = sTestingPropertyMap.get(name);
                if (n != null) {
                    sTestingPropertyMap.put(name, val);
                    return;
                }
            }
        }
        RuntimeException failure = null;
        for (int attempt = 0; attempt < PROPERTY_FAILURE_RETRY_LIMIT; attempt++) {
            try {
                SystemProperties.set(name, Long.toString(val));
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

    // Set the nonce in a static context.  No handle is available.
    private static long getNonce(String name) {
        if (sTesting) {
            synchronized (sTestingPropertyMap) {
                Long n = sTestingPropertyMap.get(name);
                if (n != null) {
                    return n;
                }
            }
        }
        return SystemProperties.getLong(name, NONCE_UNSET);
    }

    /**
     * Forget all cached values.
     * TODO(216112648) remove this as a public API.  Clients should invalidate caches, not clear
     * them.
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
     * Disable the use of this cache in this process.  This method is using internally and during
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
        if (bypass(query)) {
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
                        Log.d(TAG, TextUtils.formatSimple(
                            "cache %s %s for %s",
                            cacheName(), sNonceName[(int) currentNonce], queryToString(query)));
                    }
                }
                return recompute(query);
            }
            final Result cachedResult;
            synchronized (mLock) {
                if (currentNonce == mLastSeenNonce) {
                    cachedResult = mCache.get(query);

                    if (cachedResult != null) mHits++;
                } else {
                    if (DEBUG) {
                        Log.d(TAG, TextUtils.formatSimple(
                            "clearing cache %s of %d entries because nonce changed [%s] -> [%s]",
                            cacheName(), mCache.size(),
                            mLastSeenNonce, currentNonce));
                    }
                    clear();
                    mLastSeenNonce = currentNonce;
                    cachedResult = null;
                }
            }
            // Cache hit --- but we're not quite done yet.  A value in the cache might need to
            // be augmented in a "refresh" operation.  The refresh operation can combine the
            // old and the new nonce values.  In order to make sure the new parts of the value
            // are consistent with the old, possibly-reused parts, we check the property value
            // again after the refresh and do the whole fetch again if the property invalidated
            // us while we were refreshing.
            if (cachedResult != null) {
                final Result refreshedResult = refresh(cachedResult, query);
                if (refreshedResult != cachedResult) {
                    if (DEBUG) {
                        Log.d(TAG, "cache refresh for " + cacheName() + " " + queryToString(query));
                    }
                    final long afterRefreshNonce = getCurrentNonce();
                    if (currentNonce != afterRefreshNonce) {
                        currentNonce = afterRefreshNonce;
                        if (DEBUG) {
                            Log.d(TAG, TextUtils.formatSimple(
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
                if (mLastSeenNonce == currentNonce && result != null) {
                    mCache.put(query, result);
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
        if (!sEnabled) {
            return;
        }
        setNonce(name, NONCE_DISABLED);
    }

    /**
     * Non-static convenience version of invalidateCache() for situations in which only a single
     * PropertyInvalidatedCache is keyed on a particular property value.
     * @hide
     */
    @TestApi
    public void invalidateCache() {
        invalidateCache(mPropertyName);
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
        if (!sEnabled) {
            if (DEBUG) {
                Log.w(TAG, TextUtils.formatSimple(
                    "cache invalidate %s suppressed", name));
            }
            return;
        }

        // Take the cork lock so invalidateCache() racing against corkInvalidations() doesn't
        // clobber a cork-written NONCE_UNSET with a cache key we compute before the cork.
        // The property service is single-threaded anyway, so we don't lose any concurrency by
        // taking the cork lock around cache invalidations.  If we see contention on this lock,
        // we're invalidating too often.
        synchronized (sCorkLock) {
            Integer numberCorks = sCorks.get(name);
            if (numberCorks != null && numberCorks > 0) {
                if (DEBUG) {
                    Log.d(TAG, "ignoring invalidation due to cork: " + name);
                }
                final long count = sCorkedInvalidates.getOrDefault(name, (long) 0);
                sCorkedInvalidates.put(name, count + 1);
                return;
            }
            invalidateCacheLocked(name);
        }
    }

    @GuardedBy("sCorkLock")
    private static void invalidateCacheLocked(@NonNull String name) {
        // There's no race here: we don't require that values strictly increase, but instead
        // only that each is unique in a single runtime-restart session.
        final long nonce = getNonce(name);
        if (nonce == NONCE_DISABLED) {
            if (DEBUG) {
                Log.d(TAG, "refusing to invalidate disabled cache: " + name);
            }
            return;
        }

        long newValue;
        do {
            newValue = NoPreloadHolder.next();
        } while (isReservedNonce(newValue));
        if (DEBUG) {
            Log.d(TAG, TextUtils.formatSimple(
                    "invalidating cache [%s]: [%s] -> [%s]",
                    name, nonce, Long.toString(newValue)));
        }
        // There is a small race with concurrent disables here.  A compare-and-exchange
        // property operation would be required to eliminate the race condition.
        setNonce(name, newValue);
        long invalidateCount = sInvalidates.getOrDefault(name, (long) 0);
        sInvalidates.put(name, ++invalidateCount);
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
        if (!sEnabled) {
            if (DEBUG) {
                Log.w(TAG, TextUtils.formatSimple(
                    "cache cork %s suppressed", name));
            }
            return;
        }

        synchronized (sCorkLock) {
            int numberCorks = sCorks.getOrDefault(name, 0);
            if (DEBUG) {
                Log.d(TAG, TextUtils.formatSimple(
                        "corking %s: numberCorks=%s", name, numberCorks));
            }

            // If we're the first ones to cork this cache, set the cache to the corked state so
            // existing caches talk directly to their services while we've corked updates.
            // Make sure we don't clobber a disabled cache value.

            // TODO(dancol): we can skip this property write and leave the cache enabled if the
            // caller promises not to make observable changes to the cache backing state before
            // uncorking the cache, e.g., by holding a read lock across the cork-uncork pair.
            // Implement this more dangerous mode of operation if necessary.
            if (numberCorks == 0) {
                final long nonce = getNonce(name);
                if (nonce != NONCE_UNSET && nonce != NONCE_DISABLED) {
                    setNonce(name, NONCE_CORKED);
                }
            } else {
                final long count = sCorkedInvalidates.getOrDefault(name, (long) 0);
                sCorkedInvalidates.put(name, count + 1);
            }
            sCorks.put(name, numberCorks + 1);
            if (DEBUG) {
                Log.d(TAG, "corked: " + name);
            }
        }
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
        if (!sEnabled) {
            if (DEBUG) {
                Log.w(TAG, TextUtils.formatSimple(
                        "cache uncork %s suppressed", name));
            }
            return;
        }

        synchronized (sCorkLock) {
            int numberCorks = sCorks.getOrDefault(name, 0);
            if (DEBUG) {
                Log.d(TAG, TextUtils.formatSimple(
                        "uncorking %s: numberCorks=%s", name, numberCorks));
            }

            if (numberCorks < 1) {
                throw new AssertionError("cork underflow: " + name);
            }
            if (numberCorks == 1) {
                sCorks.remove(name);
                invalidateCacheLocked(name);
                if (DEBUG) {
                    Log.d(TAG, "uncorked: " + name);
                }
            } else {
                sCorks.put(name, numberCorks - 1);
            }
        }
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

        public AutoCorker(@NonNull String propertyName) {
            this(propertyName, DEFAULT_AUTO_CORK_DELAY_MS);
        }

        public AutoCorker(@NonNull String propertyName, int autoCorkDelayMs) {
            mPropertyName = propertyName;
            mAutoCorkDelayMs = autoCorkDelayMs;
            // We can't initialize mHandler here: when we're created, the main loop might not
            // be set up yet! Wait until we have a main loop to initialize our
            // corking callback.
        }

        public void autoCork() {
            if (Looper.getMainLooper() == null) {
                // We're not ready to auto-cork yet, so just invalidate the cache immediately.
                if (DEBUG) {
                    Log.w(TAG, "invalidating instead of autocorking early in init: "
                            + mPropertyName);
                }
                PropertyInvalidatedCache.invalidateCache(mPropertyName);
                return;
            }
            synchronized (mLock) {
                boolean alreadyQueued = mUncorkDeadlineMs >= 0;
                if (DEBUG) {
                    Log.w(TAG, TextUtils.formatSimple(
                            "autoCork %s mUncorkDeadlineMs=%s", mPropertyName,
                            mUncorkDeadlineMs));
                }
                mUncorkDeadlineMs = SystemClock.uptimeMillis() + mAutoCorkDelayMs;
                if (!alreadyQueued) {
                    getHandlerLocked().sendEmptyMessageAtTime(0, mUncorkDeadlineMs);
                    PropertyInvalidatedCache.corkInvalidations(mPropertyName);
                } else {
                    synchronized (sCorkLock) {
                        final long count = sCorkedInvalidates.getOrDefault(mPropertyName, (long) 0);
                        sCorkedInvalidates.put(mPropertyName, count + 1);
                    }
                }
            }
        }

        private void handleMessage(Message msg) {
            synchronized (mLock) {
                if (DEBUG) {
                    Log.w(TAG, TextUtils.formatSimple(
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
                        Log.w(TAG, TextUtils.formatSimple(
                                        "scheduling uncork at %s",
                                        mUncorkDeadlineMs));
                    }
                    getHandlerLocked().sendEmptyMessageAtTime(0, mUncorkDeadlineMs);
                    return;
                }
                if (DEBUG) {
                    Log.w(TAG, "automatic uncorking " + mPropertyName);
                }
                mUncorkDeadlineMs = -1;
                PropertyInvalidatedCache.uncorkInvalidations(mPropertyName);
            }
        }

        @GuardedBy("mLock")
        private Handler getHandlerLocked() {
            if (mHandler == null) {
                mHandler = new Handler(Looper.getMainLooper()) {
                        @Override
                        public void handleMessage(Message msg) {
                            AutoCorker.this.handleMessage(msg);
                        }
                    };
            }
            return mHandler;
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
                Log.e(TAG, TextUtils.formatSimple(
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
     * Returns a list of caches alive at the current time.
     */
    @GuardedBy("sGlobalLock")
    private static @NonNull ArrayList<PropertyInvalidatedCache> getActiveCaches() {
        return new ArrayList<PropertyInvalidatedCache>(sCaches.keySet());
    }

    /**
     * Returns a list of the active corks in a process.
     */
    private static @NonNull ArrayList<Map.Entry<String, Integer>> getActiveCorks() {
        synchronized (sCorkLock) {
            return new ArrayList<Map.Entry<String, Integer>>(sCorks.entrySet());
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

    private void dumpContents(PrintWriter pw, boolean detailed, String[] args) {
        // If the user has requested specific caches and this is not one of them, return
        // immediately.
        if (detailed && !showDetailed(args)) {
            return;
        }

        long invalidateCount;
        long corkedInvalidates;
        synchronized (sCorkLock) {
            invalidateCount = sInvalidates.getOrDefault(mPropertyName, (long) 0);
            corkedInvalidates = sCorkedInvalidates.getOrDefault(mPropertyName, (long) 0);
        }

        synchronized (mLock) {
            pw.println(TextUtils.formatSimple("  Cache Name: %s", cacheName()));
            pw.println(TextUtils.formatSimple("    Property: %s", mPropertyName));
            final long skips = mSkips[NONCE_CORKED] + mSkips[NONCE_UNSET] + mSkips[NONCE_DISABLED]
                    + mSkips[NONCE_BYPASS];
            pw.println(TextUtils.formatSimple(
                    "    Hits: %d, Misses: %d, Skips: %d, Clears: %d",
                    mHits, mMisses, skips, mClears));
            pw.println(TextUtils.formatSimple(
                    "    Skip-corked: %d, Skip-unset: %d, Skip-bypass: %d, Skip-other: %d",
                    mSkips[NONCE_CORKED], mSkips[NONCE_UNSET],
                    mSkips[NONCE_BYPASS], mSkips[NONCE_DISABLED]));
            pw.println(TextUtils.formatSimple(
                    "    Nonce: 0x%016x, Invalidates: %d, CorkedInvalidates: %d",
                    mLastSeenNonce, invalidateCount, corkedInvalidates));
            pw.println(TextUtils.formatSimple(
                    "    Current Size: %d, Max Size: %d, HW Mark: %d, Overflows: %d",
                    mCache.size(), mMaxEntries, mHighWaterMark, mMissOverflow));
            pw.println(TextUtils.formatSimple("    Enabled: %s", mDisabled ? "false" : "true"));
            pw.println("");

            // No specific cache was requested.  This is the default, and no details
            // should be dumped.
            if (!detailed) {
                return;
            }
            Set<Map.Entry<Query, Result>> cacheEntries = mCache.entrySet();
            if (cacheEntries.size() == 0) {
                return;
            }

            pw.println("    Contents:");
            for (Map.Entry<Query, Result> entry : cacheEntries) {
                String key = Objects.toString(entry.getKey());
                String value = Objects.toString(entry.getValue());

                pw.println(TextUtils.formatSimple("      Key: %s\n      Value: %s\n", key, value));
            }
        }
    }

    /**
     * Dump the corking status.
     */
    @GuardedBy("sCorkLock")
    private static void dumpCorkInfo(PrintWriter pw) {
        ArrayList<Map.Entry<String, Integer>> activeCorks = getActiveCorks();
        if (activeCorks.size() > 0) {
            pw.println("  Corking Status:");
            for (int i = 0; i < activeCorks.size(); i++) {
                Map.Entry<String, Integer> entry = activeCorks.get(i);
                pw.println(TextUtils.formatSimple("    Property Name: %s Count: %d",
                                entry.getKey(), entry.getValue()));
            }
        }
    }

    /**
     * Without arguments, this dumps statistics from every cache in the process to the
     * provided ParcelFileDescriptor.  Optional switches allow the caller to choose
     * specific caches (selection is by cache name or property name); if these switches
     * are used then the output includes both cache statistics and cache entries.
     */
    private static void dumpCacheInfo(@NonNull PrintWriter pw, @NonNull String[] args) {
        if (!sEnabled) {
            pw.println("  Caching is disabled in this process.");
            return;
        }

        // See if detailed is requested for any cache.  If there is a specific detailed request,
        // then only that cache is reported.
        boolean detail = anyDetailed(args);

        ArrayList<PropertyInvalidatedCache> activeCaches;
        synchronized (sGlobalLock) {
            activeCaches = getActiveCaches();
            if (!detail) {
                dumpCorkInfo(pw);
            }
        }

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
     * Trim memory by clearing all the caches.
     * @hide
     */
    public static void onTrimMemory() {
        for (PropertyInvalidatedCache pic : getActiveCaches()) {
            pic.clear();
        }
    }
}
