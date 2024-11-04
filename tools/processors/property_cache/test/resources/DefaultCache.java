/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.processor.property_cache.test;

import android.os.IpcDataCache;

/**
 * This class is auto-generated
 *
 * @hide
 **/
public class DefaultCache {
    private static final Object sBirthdayLock = new Object();
    private static IpcDataCache<java.lang.Integer, java.util.Date> sBirthday;


    /**
     * This method is auto-generated
     *
     * @param binderCall      - lambda for remote call
     *                        {@link  android.processor.property_cache.test.Default#getBirthday }
     * @param bypassPredicate - lambda to bypass remote call
     * @param query           - parameter to call remote lambda
     * @hide
     */
    public static java.util.Date getBirthday(
            IpcDataCache.RemoteCall<java.lang.Integer, java.util.Date> binderCall,
            IpcDataCache.BypassCall<java.lang.Integer> bypassPredicate, java.lang.Integer query
    ) {
        if (sBirthday != null) {
            return sBirthday.query(query);
        }
        synchronized (sBirthdayLock) {
            if (sBirthday == null) {
                sBirthday = new IpcDataCache(
                        new IpcDataCache.Config(32, "system_server",
                                "default_birthday", "Birthday"),
                        binderCall, bypassPredicate);

            }
        }
        return sBirthday.query(query);
    }


    /**
     * This method is auto-generated
     *
     * @param binderCall - lambda for remote call
     *                   {@link  android.processor.property_cache.test.Default#getBirthday }
     * @param query      - parameter to call remote lambda
     * @hide
     */
    public static java.util.Date getBirthday(
            IpcDataCache.RemoteCall<java.lang.Integer, java.util.Date> binderCall,
            java.lang.Integer query
    ) {
        if (sBirthday != null) {
            return sBirthday.query(query);
        }
        synchronized (sBirthdayLock) {
            if (sBirthday == null) {
                sBirthday = new IpcDataCache(
                        new IpcDataCache.Config(32, "system_server",
                                "default_birthday", "Birthday"),
                        binderCall);

            }
        }
        return sBirthday.query(query);
    }

    /**
     * This method is auto-generated- invalidate cache for
     * {@link  android.processor.property_cache.test.Default#getBirthday}
     *
     * @hide
     */
    public static final void invalidateBirthday() {
        IpcDataCache.invalidateCache("system_server", "default_birthday");
    }

    private static final Object sDaysTillBirthdayLock = new Object();
    private static IpcDataCache<java.lang.Integer, java.lang.Integer> sDaysTillBirthday;


    /**
     * This method is auto-generated
     *
     * @param binderCall      - lambda for remote call
     *                        {@link
     *                        android.processor.property_cache.test.Default#getDaysTillBirthday }
     * @param bypassPredicate - lambda to bypass remote call
     * @param query           - parameter to call remote lambda
     * @hide
     */
    public static java.lang.Integer getDaysTillBirthday(
            IpcDataCache.RemoteCall<java.lang.Integer, java.lang.Integer> binderCall,
            IpcDataCache.BypassCall<java.lang.Integer> bypassPredicate, java.lang.Integer query
    ) {
        if (sDaysTillBirthday != null) {
            return sDaysTillBirthday.query(query);
        }
        synchronized (sDaysTillBirthdayLock) {
            if (sDaysTillBirthday == null) {
                sDaysTillBirthday = new IpcDataCache(
                        new IpcDataCache.Config(32, "system_server", "default_days_till_birthday",
                                "DaysTillBirthday"), binderCall, bypassPredicate);

            }
        }
        return sDaysTillBirthday.query(query);
    }


    /**
     * This method is auto-generated
     *
     * @param binderCall - lambda for remote call
     *                   {@link  android.processor.property_cache.test.Default#getDaysTillBirthday
     *                   }
     * @param query      - parameter to call remote lambda
     * @hide
     */
    public static java.lang.Integer getDaysTillBirthday(
            IpcDataCache.RemoteCall<java.lang.Integer, java.lang.Integer> binderCall,
            java.lang.Integer query
    ) {
        if (sDaysTillBirthday != null) {
            return sDaysTillBirthday.query(query);
        }
        synchronized (sDaysTillBirthdayLock) {
            if (sDaysTillBirthday == null) {
                sDaysTillBirthday = new IpcDataCache(
                        new IpcDataCache.Config(32, "system_server", "default_days_till_birthday",
                                "DaysTillBirthday"), binderCall);

            }
        }
        return sDaysTillBirthday.query(query);
    }

    /**
     * This method is auto-generated- invalidate cache for
     * {@link  android.processor.property_cache.test.Default#getDaysTillBirthday}
     *
     * @hide
     */
    public static final void invalidateDaysTillBirthday() {
        IpcDataCache.invalidateCache("system_server", "default_days_till_birthday");
    }

    private final Object mDaysSinceBirthdayLock = new Object();
    private IpcDataCache<java.lang.Integer, java.lang.Integer> mDaysSinceBirthday;


    /**
     * This method is auto-generated
     *
     * @param binderCall      - lambda for remote call
     *                        {@link
     *                        android.processor.property_cache.test.Default#getDaysSinceBirthday }
     * @param bypassPredicate - lambda to bypass remote call
     * @param query           - parameter to call remote lambda
     * @hide
     */
    public java.lang.Integer getDaysSinceBirthday(
            IpcDataCache.RemoteCall<java.lang.Integer, java.lang.Integer> binderCall,
            IpcDataCache.BypassCall<java.lang.Integer> bypassPredicate, java.lang.Integer query
    ) {
        if (mDaysSinceBirthday != null) {
            return mDaysSinceBirthday.query(query);
        }
        synchronized (mDaysSinceBirthdayLock) {
            if (mDaysSinceBirthday == null) {
                mDaysSinceBirthday = new IpcDataCache(
                        new IpcDataCache.Config(32, "system_server", "default_days_since_birthday",
                                "DaysSinceBirthday"), binderCall, bypassPredicate);

            }
        }
        return mDaysSinceBirthday.query(query);
    }


    /**
     * This method is auto-generated
     *
     * @param binderCall - lambda for remote call
     *                   {@link  android.processor.property_cache.test.Default#getDaysSinceBirthday
     *                   }
     * @param query      - parameter to call remote lambda
     * @hide
     */
    public java.lang.Integer getDaysSinceBirthday(
            IpcDataCache.RemoteCall<java.lang.Integer, java.lang.Integer> binderCall,
            java.lang.Integer query
    ) {
        if (mDaysSinceBirthday != null) {
            return mDaysSinceBirthday.query(query);
        }
        synchronized (mDaysSinceBirthdayLock) {
            if (mDaysSinceBirthday == null) {
                mDaysSinceBirthday = new IpcDataCache(
                        new IpcDataCache.Config(32, "system_server", "default_days_since_birthday",
                                "DaysSinceBirthday"), binderCall);

            }
        }
        return mDaysSinceBirthday.query(query);
    }

    /**
     * This method is auto-generated- invalidate cache for
     * {@link  android.processor.property_cache.test.Default#getDaysSinceBirthday}
     *
     * @hide
     */
    public static final void invalidateDaysSinceBirthday() {
        IpcDataCache.invalidateCache("system_server", "default_days_since_birthday");
    }

    private static final Object sDaysTillMyBirthdayLock = new Object();
    private static IpcDataCache<java.lang.Void, java.lang.Integer> sDaysTillMyBirthday;


    /**
     * This method is auto-generated
     *
     * @param binderCall - lambda for remote call
     *                   {@link  android.processor.property_cache.test.Default#getDaysTillMyBirthday
     *                   }
     * @hide
     */
    public static java.lang.Integer getDaysTillMyBirthday(
            IpcDataCache.RemoteCall<java.lang.Void, java.lang.Integer> binderCall
    ) {
        if (sDaysTillMyBirthday != null) {
            return sDaysTillMyBirthday.query(null);
        }
        synchronized (sDaysTillMyBirthdayLock) {
            if (sDaysTillMyBirthday == null) {
                sDaysTillMyBirthday = new IpcDataCache(
                        new IpcDataCache.Config(1, "system_server", "default_days_till_my_birthday",
                                "DaysTillMyBirthday"), binderCall);

            }
        }
        return sDaysTillMyBirthday.query(null);
    }

    /**
     * This method is auto-generated- invalidate cache for
     * {@link  android.processor.property_cache.test.Default#getDaysTillMyBirthday}
     *
     * @hide
     */
    public static final void invalidateDaysTillMyBirthday() {
        IpcDataCache.invalidateCache("system_server", "default_days_till_my_birthday");
    }

    private final Object mDaysSinceMyBirthdayLock = new Object();
    private IpcDataCache<java.lang.Void, java.lang.Integer> mDaysSinceMyBirthday;


    /**
     * This method is auto-generated
     *
     * @param binderCall - lambda for remote call
     *                   {@link
     *                   android.processor.property_cache.test.Default#getDaysSinceMyBirthday }
     * @hide
     */
    public java.lang.Integer getDaysSinceMyBirthday(
            IpcDataCache.RemoteCall<java.lang.Void, java.lang.Integer> binderCall
    ) {
        if (mDaysSinceMyBirthday != null) {
            return mDaysSinceMyBirthday.query(null);
        }
        synchronized (mDaysSinceMyBirthdayLock) {
            if (mDaysSinceMyBirthday == null) {
                mDaysSinceMyBirthday = new IpcDataCache(
                        new IpcDataCache.Config(1, "system_server", "my_unique_key",
                                "DaysSinceMyBirthday"), binderCall);

            }
        }
        return mDaysSinceMyBirthday.query(null);
    }

    /**
     * This method is auto-generated- invalidate cache for
     * {@link  android.processor.property_cache.test.Default#getDaysSinceMyBirthday}
     *
     * @hide
     */
    public static final void invalidateDaysSinceMyBirthday() {
        IpcDataCache.invalidateCache("system_server", "my_unique_key");
    }

    private static final Object sBirthdayWishesFromUserLock = new Object();
    private static IpcDataCache<java.lang.Integer, java.lang.String> sBirthdayWishesFromUser;


    /**
     * This method is auto-generated
     *
     * @param binderCall      - lambda for remote call
     *                        {@link
     *
     *                       android.processor.property_cache.test.Default#getBirthdayWishesFromUser
     *                        }
     * @param bypassPredicate - lambda to bypass remote call
     * @param query           - parameter to call remote lambda
     * @hide
     */
    public static java.lang.String getBirthdayWishesFromUser(
            IpcDataCache.RemoteCall<java.lang.Integer, java.lang.String> binderCall,
            IpcDataCache.BypassCall<java.lang.Integer> bypassPredicate, java.lang.Integer query
    ) {
        if (sBirthdayWishesFromUser != null) {
            return sBirthdayWishesFromUser.query(query);
        }
        synchronized (sBirthdayWishesFromUserLock) {
            if (sBirthdayWishesFromUser == null) {
                sBirthdayWishesFromUser = new IpcDataCache(
                        new IpcDataCache.Config(32, "telephony",
                                "default_birthday_wishes_from_user",
                                "BirthdayWishesFromUser"), binderCall, bypassPredicate);

            }
        }
        return sBirthdayWishesFromUser.query(query);
    }


    /**
     * This method is auto-generated
     *
     * @param binderCall - lambda for remote call
     *                   {@link
     *                   android.processor.property_cache.test.Default#getBirthdayWishesFromUser }
     * @param query      - parameter to call remote lambda
     * @hide
     */
    public static java.lang.String getBirthdayWishesFromUser(
            IpcDataCache.RemoteCall<java.lang.Integer, java.lang.String> binderCall,
            java.lang.Integer query
    ) {
        if (sBirthdayWishesFromUser != null) {
            return sBirthdayWishesFromUser.query(query);
        }
        synchronized (sBirthdayWishesFromUserLock) {
            if (sBirthdayWishesFromUser == null) {
                sBirthdayWishesFromUser = new IpcDataCache(
                        new IpcDataCache.Config(32, "telephony",
                                "default_birthday_wishes_from_user",
                                "BirthdayWishesFromUser"), binderCall);

            }
        }
        return sBirthdayWishesFromUser.query(query);
    }

    /**
     * This method is auto-generated- invalidate cache for
     * {@link  android.processor.property_cache.test.Default#getBirthdayWishesFromUser}
     *
     * @hide
     */
    public static final void invalidateBirthdayWishesFromUser() {
        IpcDataCache.invalidateCache("telephony", "default_birthday_wishes_from_user");
    }


    /**
     * This method is auto-generated - initialise all caches for class DefaultCache
     *
     * @hide
     */
    public static void initCache() {
        DefaultCache.invalidateBirthday();
        DefaultCache.invalidateDaysTillBirthday();
        DefaultCache.invalidateDaysSinceBirthday();
        DefaultCache.invalidateDaysTillMyBirthday();
        DefaultCache.invalidateDaysSinceMyBirthday();
        DefaultCache.invalidateBirthdayWishesFromUser();
    }
}
