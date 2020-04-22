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
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
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
 *   private final HashMap<Integer, Birthday> mUidToBirthday;
 *   @Override
 *   public synchronized Birthday getUserBirthday(int userId) {
 *     return mUidToBirthday.get(userId);
 *   }
 *   private synchronized void updateBirthdays(Map<Integer, Birthday> uidToBirthday) {
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
 *   private static final int BDAY_CACHE_MAX = 8;  // Maximum birthdays to cache
 *   private static final String BDAY_CACHE_KEY = "cache_key.birthdayd";
 *   private final PropertyInvalidatedCache<Integer, Birthday> mBirthdayCache = new
 *     PropertyInvalidatedCache<Integer, Birthday>(BDAY_CACHE_MAX, BDAY_CACHE_KEY) {
 *       @Override
 *       protected Birthday recompute(Integer userId) {
 *         return GetService("birthdayd").getUserBirthday(userId);
 *       }
 *     };
 *   public void disableUserBirthdayCache() {
 *     mBirthdayCache.disableLocal();
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
 *   private synchronized void updateBirthdays(Map<Integer, Birthday> uidToBirthday) {
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
 * For security, there is a whitelist of processes that are allowed to invalidate a cache.
 * The whitelist includes normal runtime processes but does not include test processes.
 * Test processes must call {@code PropertyInvalidatedCache.disableForTestMode()} to disable
 * all cache activity in that process.
 *
 * Caching can be disabled completely by initializing {@code sEnabled} to false and rebuilding.
 *
 * @param <Query> The class used to index cache entries: must be hashable and comparable
 * @param <Result> The class holding cache entries; use a boxed primitive if possible
 *
 * {@hide}
 */
public abstract class PropertyInvalidatedCache<Query, Result> {
    private static final long NONCE_UNSET = 0;
    private static final long NONCE_DISABLED = -1;

    private static final String TAG = "PropertyInvalidatedCache";
    private static final boolean DEBUG = false;
    private static final boolean VERIFY = false;

    /**
     * If sEnabled is false then all cache operations are stubbed out.  Set
     * it to false inside test processes.
     */
    private static boolean sEnabled = true;

    private static final Object sCorkLock = new Object();

    /**
     * A map of cache keys that we've "corked". (The values are counts.)  When a cache key is
     * corked, we skip the cache invalidate when the cache key is in the unset state --- that
     * is, when a cache key is corked, an invalidation does not enable the cache if somebody
     * else hasn't disabled it.
     */
    @GuardedBy("sCorkLock")
    private static final HashMap<String, Integer> sCorks = new HashMap<>();

    private final Object mLock = new Object();

    /**
     * Name of the property that holds the unique value that we use to invalidate the cache.
     */
    private final String mPropertyName;

    /**
     * Handle to the {@code mPropertyName} property, transitioning to non-{@code null} once the
     * property exists on the system.
     */
    private volatile SystemProperties.Handle mPropertyHandle;

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
     * Make a new property invalidated cache.
     *
     * @param maxEntries Maximum number of entries to cache; LRU discard
     * @param propertyName Name of the system property holding the cache invalidation nonce
     */
    public PropertyInvalidatedCache(int maxEntries, @NonNull String propertyName) {
        mPropertyName = propertyName;
        mCache = new LinkedHashMap<Query, Result>(
            2 /* start small */,
            0.75f /* default load factor */,
            true /* LRU access order */) {
                @Override
                protected boolean removeEldestEntry(Map.Entry eldest) {
                    return size() > maxEntries;
                }
            };
    }

    /**
     * Forget all cached values.
     */
    public final void clear() {
        synchronized (mLock) {
            mCache.clear();
        }
    }

    /**
     * Fetch a result from scratch in case it's not in the cache at all.  Called unlocked: may
     * block. If this function returns null, the result of the cache query is null. There is no
     * "negative cache" in the query: we don't cache null results at all.
     */
    protected abstract Result recompute(Query query);

    /**
     * Determines if a pair of responses are considered equal. Used to determine whether
     * a cache is inadvertently returning stale results when VERIFY is set to true.
     */
    protected boolean debugCompareQueryResults(Result cachedResult, Result fetchedResult) {
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
     */
    protected Result refresh(Result oldResult, Query query) {
        return oldResult;
    }

    private long getCurrentNonce() {
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

    /**
     * Disable the use of this cache in this process.
     */
    public final void disableLocal() {
        synchronized (mLock) {
            mDisabled = true;
            mCache.clear();
        }
    }

    /**
     * Return whether the cache is disabled in this process.
     */
    public final boolean isDisabledLocal() {
        return mDisabled || !sEnabled;
    }

    /**
     * Get a value from the cache or recompute it.
     */
    public Result query(Query query) {
        // Let access to mDisabled race: it's atomic anyway.
        long currentNonce = (!isDisabledLocal()) ? getCurrentNonce() : NONCE_DISABLED;
        for (;;) {
            if (currentNonce == NONCE_DISABLED || currentNonce == NONCE_UNSET) {
                if (DEBUG) {
                    Log.d(TAG,
                            String.format("cache %s %s for %s",
                                cacheName(),
                                currentNonce == NONCE_DISABLED ? "disabled" : "unset",
                                queryToString(query)));
                }
                return recompute(query);
            }
            final Result cachedResult;
            synchronized (mLock) {
                if (currentNonce == mLastSeenNonce) {
                    cachedResult = mCache.get(query);
                } else {
                    if (DEBUG) {
                        Log.d(TAG,
                                String.format("clearing cache %s because nonce changed [%s] -> [%s]",
                                        cacheName(),
                                        mLastSeenNonce, currentNonce));
                    }
                    mCache.clear();
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
                            Log.d(TAG, String.format("restarting %s %s because nonce changed in refresh",
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
     */
    public final void disableSystemWide() {
        disableSystemWide(mPropertyName);
    }

    /**
     * Disable all caches system-wide that are keyed on {@var name}. This
     * function is synchronous: caches are invalidated and disabled upon return.
     *
     * @param name Name of the cache-key property to invalidate
     */
    public static void disableSystemWide(@NonNull String name) {
        if (!sEnabled) {
            return;
        }
        SystemProperties.set(name, Long.toString(NONCE_DISABLED));
    }

    /**
     * Non-static convenience version of invalidateCache() for situations in which only a single
     * PropertyInvalidatedCache is keyed on a particular property value.
     */
    public final void invalidateCache() {
        invalidateCache(mPropertyName);
    }

    /**
     * Invalidate PropertyInvalidatedCache caches in all processes that are keyed on
     * {@var name}. This function is synchronous: caches are invalidated upon return.
     *
     * @param name Name of the cache-key property to invalidate
     */
    public static void invalidateCache(@NonNull String name) {
        if (!sEnabled) {
            if (DEBUG) {
                Log.w(TAG, String.format(
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
                return;
            }
            invalidateCacheLocked(name);
        }
    }

    @GuardedBy("sCorkLock")
    private static void invalidateCacheLocked(@NonNull String name) {
        // There's no race here: we don't require that values strictly increase, but instead
        // only that each is unique in a single runtime-restart session.
        final long nonce = SystemProperties.getLong(name, NONCE_UNSET);
        if (nonce == NONCE_DISABLED) {
            if (DEBUG) {
                Log.d(TAG, "refusing to invalidate disabled cache: " + name);
            }
            return;
        }

        long newValue;
        do {
            newValue = NoPreloadHolder.next();
        } while (newValue == NONCE_UNSET || newValue == NONCE_DISABLED);
        final String newValueString = Long.toString(newValue);
        if (DEBUG) {
            Log.d(TAG,
                    String.format("invalidating cache [%s]: [%s] -> [%s]",
                            name,
                            nonce,
                            newValueString));
        }
        SystemProperties.set(name, newValueString);
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
     */
    public static void corkInvalidations(@NonNull String name) {
        synchronized (sCorkLock) {
            int numberCorks = sCorks.getOrDefault(name, 0);
            if (DEBUG) {
                Log.d(TAG, String.format("corking %s: numberCorks=%s", name, numberCorks));
            }

            // If we're the first ones to cork this cache, set the cache to the unset state so
            // existing caches talk directly to their services while we've corked updates.
            // Make sure we don't clobber a disabled cache value.

            // TODO(dancol): we can skip this property write and leave the cache enabled if the
            // caller promises not to make observable changes to the cache backing state before
            // uncorking the cache, e.g., by holding a read lock across the cork-uncork pair.
            // Implement this more dangerous mode of operation if necessary.
            if (numberCorks == 0) {
                final long nonce = SystemProperties.getLong(name, NONCE_UNSET);
                if (nonce != NONCE_UNSET && nonce != NONCE_DISABLED) {
                    SystemProperties.set(name, Long.toString(NONCE_UNSET));
                }
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
     */
    public static void uncorkInvalidations(@NonNull String name) {
        synchronized (sCorkLock) {
            int numberCorks = sCorks.getOrDefault(name, 0);
            if (DEBUG) {
                Log.d(TAG, String.format("uncorking %s: numberCorks=%s", name, numberCorks));
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
     */
    public static final class AutoCorker {
        public static final int DEFAULT_AUTO_CORK_DELAY_MS = 2000;

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
                    Log.w(TAG, String.format(
                                    "autoCork mUncorkDeadlineMs=%s", mUncorkDeadlineMs));
                }
                mUncorkDeadlineMs = SystemClock.uptimeMillis() + mAutoCorkDelayMs;
                if (!alreadyQueued) {
                    getHandlerLocked().sendEmptyMessageAtTime(0, mUncorkDeadlineMs);
                    PropertyInvalidatedCache.corkInvalidations(mPropertyName);
                }
            }
        }

        private void handleMessage(Message msg) {
            synchronized (mLock) {
                if (DEBUG) {
                    Log.w(TAG, String.format(
                                    "handleMsesage mUncorkDeadlineMs=%s", mUncorkDeadlineMs));
                }

                if (mUncorkDeadlineMs < 0) {
                    return;  // ???
                }
                long nowMs = SystemClock.uptimeMillis();
                if (mUncorkDeadlineMs > nowMs) {
                    mUncorkDeadlineMs = nowMs + mAutoCorkDelayMs;
                    if (DEBUG) {
                        Log.w(TAG, String.format(
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

    protected Result maybeCheckConsistency(Query query, Result proposedResult) {
        if (VERIFY) {
            Result resultToCompare = recompute(query);
            boolean nonceChanged = (getCurrentNonce() != mLastSeenNonce);
            if (!nonceChanged && !debugCompareQueryResults(proposedResult, resultToCompare)) {
                throw new AssertionError("cache returned out of date response for " + query);
            }
        }
        return proposedResult;
    }

    /**
     * Return the name of the cache, to be used in debug messages.  The
     * method is public so clients can use it.
     */
    public String cacheName() {
        return mPropertyName;
    }

    /**
     * Return the query as a string, to be used in debug messages.  The
     * method is public so clients can use it in external debug messages.
     */
    public String queryToString(Query query) {
        return Objects.toString(query);
    }

    /**
     * Disable all caches in the local process.  Once disabled it is not
     * possible to re-enable caching in the current process.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    public static void disableForTestMode() {
        Log.d(TAG, "disabling all caches in the process");
        sEnabled = false;
    }
}
