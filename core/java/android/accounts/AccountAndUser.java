/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.accounts;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;

/**
 * Used to store the Account and the UserId this account is associated with.
 *
 * @hide
 */
public class AccountAndUser {
    @UnsupportedAppUsage
    public Account account;
    @UnsupportedAppUsage
    public int userId;

    @UnsupportedAppUsage
    public AccountAndUser(Account account, int userId) {
        this.account = account;
        this.userId = userId;
    }

    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof AccountAndUser)) return false;
        final AccountAndUser other = (AccountAndUser) o;
        return this.account.equals(other.account)
                && this.userId == other.userId;
    }

    @Override
    public int hashCode() {
        return account.hashCode() + userId;
    }

    public String toString() {
        return account.toString() + " u" + userId;
    }
}
