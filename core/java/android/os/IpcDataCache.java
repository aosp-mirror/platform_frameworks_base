/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.os;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringDef;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.PropertyInvalidatedCache;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastPrintWriter;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
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
 * The intended use case is caching frequently-read, seldom-changed information normally retrieved
 * across interprocess communication. Imagine that you've written a user birthday information
 * daemon called "birthdayd" that exposes an {@code IUserBirthdayService} interface over
 * binder. That binder interface looks something like this:
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
 * ... and we have a client in frameworks (loaded into every app process) that looks like this:
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
 * With this code, every time an app calls {@code getUserBirthday(uid)}, we make a binder call to
 * the birthdayd process and consult its database of birthdays. If we query user birthdays
 * frequently, we do a lot of work that we don't have to do, since user birthdays change
 * infrequently.
 *
 * IpcDataCache is part of a pattern for optimizing this kind of information-querying code. Using
 * {@code IpcDataCache}, you'd write the client this way:
 *
 * <pre>
 * public class ActivityThread {
 *   ...
 *   private final IpcDataCache.QueryHandler&lt;Integer, Birthday&gt; mBirthdayQuery =
 *       new IpcDataCache.QueryHandler&lt;Integer, Birthday&gt;() {
 *           {@literal @}Override
 *           public Birthday apply(Integer) {
 *              return GetService("birthdayd").getUserBirthday(userId);
 *           }
 *       };
 *   private static final int BDAY_CACHE_MAX = 8;  // Maximum birthdays to cache
 *   private static final String BDAY_API = "getUserBirthday";
 *   private final IpcDataCache&lt;Integer, Birthday%&gt; mBirthdayCache = new
 *     IpcDataCache&lt;Integer, Birthday%&gt;(
 *             BDAY_CACHE_MAX, MODULE_SYSTEM, BDAY_API,  BDAY_API, mBirthdayQuery);
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
 * The call to {@code IpcDataCache.invalidateCache()} guarantees that all clients will re-fetch
 * birthdays from binder during consequent calls to
 * {@code ActivityThread.getUserBirthday()}. Because the invalidate call happens with the lock
 * held, we maintain consistency between different client views of the birthday state. The use of
 * IpcDataCache in this idiomatic way introduces no new race conditions.
 *
 * IpcDataCache has a few other features for doing things like incremental enhancement of cached
 * values and invalidation of multiple caches (that all share the same property key) at once.
 *
 * {@code BDAY_CACHE_KEY} is the name of a property that we set to an opaque unique value each
 * time we update the cache. SELinux configuration must allow everyone to read this property
 * and it must allow any process that needs to invalidate the cache (here, birthdayd) to write
 * the property. (These properties conventionally begin with the "cache_key." prefix.)
 *
 * The {@code UserBirthdayServiceImpl} constructor calls {@code disableUserBirthdayCache()} so
 * that calls to {@code getUserBirthday} from inside birthdayd don't go through the cache. In this
 * local case, there's no IPC, so use of the cache is (depending on exact circumstance)
 * unnecessary.
 *
 * There may be queries for which it is more efficient to bypass the cache than to cache the
 * result.  This would be true, for example, if some queries would require frequent cache
 * invalidation while other queries require infrequent invalidation.  To expand on the birthday
 * example, suppose that there is a userId that signifies "the next birthday".  When passed this
 * userId, the server returns the next birthday among all users - this value changes as time
 * advances.  The userId value can be cached, but the cache must be invalidated whenever a
 * birthday occurs, and this invalidates all birthdays.  If there is a large number of users,
 * invalidation will happen so often that the cache provides no value.
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
 * For security, there is a allowlist of processes that are allowed to invalidate a cache.  The
 * allowlist includes normal runtime processes but does not include test processes.  Test
 * processes must call {@code IpcDataCache.disableForTestMode()} to disable all cache activity in
 * that process.
 *
 * Caching can be disabled completely by initializing {@code sEnabled} to false and rebuilding.
 *
 * To test a binder cache, create one or more tests that exercise the binder method.  This should
 * be done twice: once with production code and once with a special image that sets {@code DEBUG}
 * and {@code VERIFY} true.  In the latter case, verify that no cache inconsistencies are
 * reported.  If a cache inconsistency is reported, however, it might be a false positive.  This
 * happens if the server side data can be read and written non-atomically with respect to cache
 * invalidation.
 *
 * @param <Query> The class used to index cache entries: must be hashable and comparable
 * @param <Result> The class holding cache entries; use a boxed primitive if possible
 * @hide
 */
@TestApi
@SystemApi(client=SystemApi.Client.MODULE_LIBRARIES)
public class IpcDataCache<Query, Result> extends PropertyInvalidatedCache<Query, Result> {
    /**
     * {@inheritDoc}
     * @hide
     */
    @SystemApi(client=SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public static abstract class QueryHandler<Q,R>
            extends PropertyInvalidatedCache.QueryHandler<Q,R> {
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
     * The list of cache namespaces.  Each namespace corresponds to an sepolicy domain.  A
     * namespace is owned by a single process, although a single process can have more
     * than one namespace (system_server, as an example).
     * @hide
     */
    @StringDef(
        prefix = { "MODULE_"
        },
        value = {
            MODULE_TEST,
            MODULE_SYSTEM,
            MODULE_BLUETOOTH,
            MODULE_TELEPHONY
        }
    )
    @Retention(RetentionPolicy.SOURCE)
    public @interface IpcDataCacheModule { }

    /**
     * The module used for unit tests and cts tests.  It is expected that no process in
     * the system has permissions to write properties with this module.
     * @hide
     */
    @TestApi
    public static final String MODULE_TEST = PropertyInvalidatedCache.MODULE_TEST;

    /**
     * The module used for system server/framework caches.  This is not visible outside
     * the system processes.
     * @hide
     */
    @TestApi
    public static final String MODULE_SYSTEM = PropertyInvalidatedCache.MODULE_SYSTEM;

    /**
     * The module used for bluetooth caches.
     * @hide
     */
    @SystemApi(client=SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public static final String MODULE_BLUETOOTH = PropertyInvalidatedCache.MODULE_BLUETOOTH;

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
    @SystemApi(client=SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public IpcDataCache(int maxEntries, @NonNull @IpcDataCacheModule String module,
            @NonNull String api, @NonNull String cacheName,
            @NonNull QueryHandler<Query, Result> computer) {
        super(maxEntries, module, api, cacheName, computer);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SystemApi(client=SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @Override
    public void disableForCurrentProcess() {
        super.disableForCurrentProcess();
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SystemApi(client=SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public static void disableForCurrentProcess(@NonNull String cacheName) {
        PropertyInvalidatedCache.disableForCurrentProcess(cacheName);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SystemApi(client=SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @Override
    public @Nullable Result query(@NonNull Query query) {
        return super.query(query);
    }

    /**
     * {@inheritDoc}
     * @hide
     */
    @SystemApi(client=SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @Override
    public void invalidateCache() {
        super.invalidateCache();
    }

    /**
     * Invalidate caches in all processes that are keyed for the module and api.
     * @hide
     */
    @SystemApi(client=SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public static void invalidateCache(@NonNull @IpcDataCacheModule String module,
            @NonNull String api) {
        PropertyInvalidatedCache.invalidateCache(module, api);
    }

    /**
     * This is a convenience class that encapsulates configuration information for a
     * cache.  It may be supplied to the cache constructors in lieu of the other
     * parameters.  The class captures maximum entry count, the module, the key, and the
     * api.
     *
     * There are three specific use cases supported by this class.
     *
     * 1. Instance-per-cache: create a static instance of this class using the same
     *    parameters as would have been given to IpcDataCache (or
     *    PropertyInvalidatedCache).  This static instance provides a hook for the
     *    invalidateCache() and disableForLocalProcess() calls, which, generally, must
     *    also be static.
     *
     * 2. Short-hand for shared configuration parameters: create an instance of this class
     *    to capture the maximum number of entries and the module to be used by more than
     *    one cache in the class.  Refer to this instance when creating new configs.  Only
     *    the api and (optionally key) for the new cache must be supplied.
     *
     * 3. Tied caches: create a static instance of this class to capture the maximum
     *    number of entries, the module, and the key.  Refer to this instance when
     *    creating a new config that differs in only the api.  The new config can be
     *    created as part of the cache constructor.  All caches that trace back to the
     *    root config share the same key and are invalidated by the invalidateCache()
     *    method of the root config.  All caches that trace back to the root config can be
     *    disabled in the local process by the disableAllForCurrentProcess() method of the
     *    root config.
     *
     * @hide
     */
    public static class Config {
        private final int mMaxEntries;
        @IpcDataCacheModule
        private final String mModule;
        private final String mApi;
        private final String mName;

        /**
         * The list of cache names that were created extending this Config.  If
         * disableForCurrentProcess() is invoked on this config then all children will be
         * disabled.  Furthermore, any new children based off of this config will be
         * disabled.  The construction order guarantees that new caches will be disabled
         * before they are created (the Config must be created before the IpcDataCache is
         * created).
         */
        private ArraySet<String> mChildren;

        /**
         * True if registered children are disabled in the current process.  If this is
         * true then all new children are disabled as they are registered.
         */
        private boolean mDisabled = false;

        public Config(int maxEntries, @NonNull @IpcDataCacheModule String module,
                @NonNull String api, @NonNull String name) {
            mMaxEntries = maxEntries;
            mModule = module;
            mApi = api;
            mName = name;
        }

        /**
         * A short-hand constructor that makes the name the same as the api.
         */
        public Config(int maxEntries, @NonNull @IpcDataCacheModule String module,
                @NonNull String api) {
            this(maxEntries, module, api, api);
        }

        /**
         * Copy the module and max entries from the Config and take the api and name from
         * the parameter list.
         */
        public Config(@NonNull Config root, @NonNull String api, @NonNull String name) {
            this(root.maxEntries(), root.module(), api, name);
        }

        /**
         * Copy the module and max entries from the Config and take the api and name from
         * the parameter list.
         */
        public Config(@NonNull Config root, @NonNull String api) {
            this(root.maxEntries(), root.module(), api, api);
        }

        /**
         * Fetch a config that is a child of <this>.  The child shares the same api as the
         * parent and is registered with the parent for the purposes of disabling in the
         * current process.
         */
        public Config child(@NonNull String name) {
            final Config result = new Config(this, api(), name);
            registerChild(name);
            return result;
        }

        public final int maxEntries() {
            return mMaxEntries;
        }

        @IpcDataCacheModule
        public final @NonNull String module() {
            return mModule;
        }

        public final @NonNull String api() {
            return mApi;
        }

        public final @NonNull String name() {
            return mName;
        }

        /**
         * Register a child cache name.  If disableForCurrentProcess() has been called
         * against this cache, disable th new child.
         */
        private final void registerChild(String name) {
            synchronized (this) {
                if (mChildren == null) {
                    mChildren = new ArraySet<>();
                }
                mChildren.add(name);
                if (mDisabled) {
                    IpcDataCache.disableForCurrentProcess(name);
                }
            }
        }

        /**
         * Invalidate all caches that share this Config's module and api.
         */
        public void invalidateCache() {
            IpcDataCache.invalidateCache(mModule, mApi);
        }

        /**
         * Disable all caches that share this Config's name.
         */
        public void disableForCurrentProcess() {
            IpcDataCache.disableForCurrentProcess(mName);
        }

        /**
         * Disable this cache and all children.  Any child that is added in the future
         * will alwo be disabled.
         */
        public void disableAllForCurrentProcess() {
            synchronized (this) {
                mDisabled = true;
                disableForCurrentProcess();
                if (mChildren != null) {
                    for (String c : mChildren) {
                        IpcDataCache.disableForCurrentProcess(c);
                    }
                }
            }
        }
    }

    /**
     * Create a new cache using a config.
     * @hide
     */
    public IpcDataCache(@NonNull Config config, @NonNull QueryHandler<Query, Result> computer) {
        super(config.maxEntries(), config.module(), config.api(), config.name(), computer);
    }

    /**
     * An interface suitable for a lambda expression instead of a QueryHandler applying remote call.
     * @hide
     */
    public interface RemoteCall<Query, Result> {
        Result apply(Query query) throws RemoteException;
    }

    /**
     * An interface suitable for a lambda expression instead of a QueryHandler bypassing the cache.
     * @hide
     */
    public interface BypassCall<Query> {
        Boolean apply(Query query);
    }

    /**
     * This is a query handler that is created with a lambda expression that is invoked
     * every time the handler is called.  The handler is specifically meant for services
     * hosted by system_server; the handler automatically rethrows RemoteException as a
     * RuntimeException, which is the usual handling for failed binder calls.
     */
    private static class SystemServerCallHandler<Query, Result>
            extends IpcDataCache.QueryHandler<Query, Result> {
        private final RemoteCall<Query, Result> mHandler;
        public SystemServerCallHandler(RemoteCall handler) {
            mHandler = handler;
        }
        @Override
        public Result apply(Query query) {
            try {
                return mHandler.apply(query);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }


    /**
     * Create a cache using a config and a lambda expression.
     * @param config The configuration for the cache.
     * @param remoteCall The lambda expression that will be invoked to fetch the data.
     * @hide
     */
    public IpcDataCache(@NonNull Config config, @NonNull RemoteCall<Query, Result> remoteCall) {
      this(config, android.multiuser.Flags.cachingDevelopmentImprovements() ?
        new QueryHandler<Query, Result>() {
            @Override
            public Result apply(Query query) {
                try {
                    return remoteCall.apply(query);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        } : new SystemServerCallHandler<>(remoteCall));
    }


    /**
     * Create a cache using a config and a lambda expression.
     * @param config The configuration for the cache.
     * @param remoteCall The lambda expression that will be invoked to fetch the data.
     * @param bypass The lambda expression that will be invoked to determine if the cache should be
     *     bypassed.
     * @hide
     */
    @FlaggedApi(android.multiuser.Flags.FLAG_CACHING_DEVELOPMENT_IMPROVEMENTS)
    public IpcDataCache(@NonNull Config config,
            @NonNull RemoteCall<Query, Result> remoteCall,
            @NonNull BypassCall<Query> bypass) {
        this(config, new QueryHandler<Query, Result>() {
            @Override
            public Result apply(Query query) {
                try {
                    return remoteCall.apply(query);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }

            @Override
            public boolean shouldBypassCache(Query query) {
                return bypass.apply(query);
            }
        });
    }
}
