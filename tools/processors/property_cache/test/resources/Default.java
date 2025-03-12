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

import com.android.internal.annotations.CacheModifier;
import com.android.internal.annotations.CachedProperty;
import com.android.internal.annotations.CachedPropertyDefaults;

import java.util.Date;

@CachedPropertyDefaults()
public class Default {
    BirthdayManagerService mService = new BirthdayManagerService();
    Object mCache = new DefaultCache();

    /** Testing default class values to generate static IpcDataCache
     *
     * @param userId - user Id
     * @return birthday date of given user Id
     */
    @CachedProperty()
    public Date getBirthday(int userId) {
        return DefaultCache.getBirthday(mService::getBirthday, userId);
    }

    /** Testing default class values to generate static IpcDataCache
     *
     * @param userId - user Id
     * @return number of days till birthday of given user Id
     */
    @CachedProperty(modsFlagOnOrNone = {CacheModifier.STATIC})
    public int getDaysTillBirthday(int userId) {
        return DefaultCache.getDaysTillBirthday(mService::getDaysTillBirthday, userId);
    }

    /** Testing generate non-static IpcDataCache
     *
     * @param userId - user Id
     * @return number of days since birthday of given user Id
     */
    @CachedProperty(modsFlagOnOrNone = {})
    public int getDaysSinceBirthday(int userId) {
        return ((DefaultCache) mCache).getDaysSinceBirthday(mService::getDaysSinceBirthday, userId);
    }

    /** Testing default class values to generate static IpcDataCache with max capacity of 1
     *
     * @return number of days till birthay of current user
     */
    @CachedProperty(
            modsFlagOnOrNone = {CacheModifier.STATIC},
            max = 1)
    public int getDaysTillMyBirthday() {
        return DefaultCache.getDaysTillMyBirthday((Void) -> mService.getDaysTillMyBirthday());
    }

    /** Testing default class values to generate static IpcDataCache with max capacity of 1 and
    custom api
     *
     * @return number of days since birthay of current user
     */
    @CachedProperty(
            modsFlagOnOrNone = {},
            max = 1,
            api = "my_unique_key")
    public int getDaysSinceMyBirthday() {
        return ((DefaultCache) mCache).getDaysSinceMyBirthday(
                (Void) -> mService.getDaysSinceMyBirthday());
    }

    /** Testing default class values to generate static IpcDataCache with custom module name
     *
     * @return birthday wishes of given user Id
     */
    @CachedProperty(module = "telephony")
    public String getBirthdayWishesFromUser(int userId) {
        return DefaultCache.getBirthdayWishesFromUser(mService::getBirthdayWishesFromUser,
                userId);
    }

    class BirthdayManagerService {

        BirthdayManagerService() {
            DefaultCache.initCache();
        }

        public Date getBirthday(int userId) {
            return new Date();
        }

        public int getDaysTillBirthday(int userId) {
            return 0;
        }

        public int getDaysSinceBirthday(int userId) {
            return 0;
        }

        public int getDaysTillMyBirthday() {
            return 0;
        }

        public int getDaysSinceMyBirthday() {
            return 0;
        }

        public String getBirthdayWishesFromUser(int userId) {
            return "Happy Birthday!\n- " + userId;
        }
    }
}
