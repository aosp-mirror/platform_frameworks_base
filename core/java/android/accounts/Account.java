/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Set;

/**
 * Value type that represents an Account in the {@link AccountManager}. This object is
 * {@link Parcelable} and also overrides {@link #equals} and {@link #hashCode}, making it
 * suitable for use as the key of a {@link java.util.Map}
 */
public class Account implements Parcelable {
    @UnsupportedAppUsage
    private static final String TAG = "Account";

    @GuardedBy("sAccessedAccounts")
    private static final Set<Account> sAccessedAccounts = new ArraySet<>();

    public final String name;
    public final String type;
    private String mSafeName;
    @UnsupportedAppUsage
    private final @Nullable String accessId;

    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof Account)) return false;
        final Account other = (Account)o;
        return name.equals(other.name) && type.equals(other.type);
    }

    public int hashCode() {
        int result = 17;
        result = 31 * result + name.hashCode();
        result = 31 * result + type.hashCode();
        return result;
    }

    public Account(String name, String type) {
        this(name, type, null);
    }

    /**
     * @hide
     */
    public Account(@NonNull Account other, @NonNull String accessId) {
        this(other.name, other.type, accessId);
    }

    /**
     * @hide
     */
    public Account(String name, String type, String accessId) {
        if (TextUtils.isEmpty(name)) {
            throw new IllegalArgumentException("the name must not be empty: " + name);
        }
        if (TextUtils.isEmpty(type)) {
            throw new IllegalArgumentException("the type must not be empty: " + type);
        }
        this.name = name;
        this.type = type;
        this.accessId = accessId;
    }

    public Account(Parcel in) {
        this.name = in.readString();
        this.type = in.readString();
        if (TextUtils.isEmpty(name)) {
            throw new android.os.BadParcelableException("the name must not be empty: " + name);
        }
        if (TextUtils.isEmpty(type)) {
            throw new android.os.BadParcelableException("the type must not be empty: " + type);
        }
        this.accessId = in.readString();
        if (accessId != null) {
            synchronized (sAccessedAccounts) {
                if (sAccessedAccounts.add(this)) {
                    try {
                        IAccountManager accountManager = IAccountManager.Stub.asInterface(
                                ServiceManager.getService(Context.ACCOUNT_SERVICE));
                        accountManager.onAccountAccessed(accessId);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Error noting account access", e);
                    }
                }
            }
        }
    }

    /** @hide */
    public String getAccessId() {
        return accessId;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(type);
        dest.writeString(accessId);
    }

    public static final @android.annotation.NonNull Creator<Account> CREATOR = new Creator<Account>() {
        public Account createFromParcel(Parcel source) {
            return new Account(source);
        }

        public Account[] newArray(int size) {
            return new Account[size];
        }
    };

    public String toString() {
        return "Account {name=" + name + ", type=" + type + "}";
    }

    /**
     * Return a string representation of the account that is safe to print
     * to logs and other places where PII should be avoided.
     * @hide
     */
    public String toSafeString() {
        if (mSafeName == null) {
            mSafeName = toSafeName(name, 'x');
        }
        return "Account {name=" + mSafeName + ", type=" + type + "}";
    }

    /**
     * Given a name, replace all letter or digits with the replacement char.
     * @param name The input name string.
     * @param replacement the replacement character.
     * @return the string after replacement.
     * @hide
     */
    public static String toSafeName(String name, char replacement) {
        final StringBuilder builder = new StringBuilder(64);
        final int len = name.length();
        for (int i = 0; i < len; i++) {
            final char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(replacement);
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
