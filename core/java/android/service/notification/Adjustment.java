/*
 * Copyright (C) 2016 The Android Open Source Project
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
package android.service.notification;

import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Ranking updates from the Ranker.
 *
 * @hide
 */
@SystemApi
public final class Adjustment implements Parcelable {
    private final String mPackage;
    private final String mKey;
    private final int mImportance;
    private final CharSequence mExplanation;
    private final Uri mReference;
    private final Bundle mSignals;
    private final int mUser;

    public static final String GROUP_KEY_OVERRIDE_KEY = "group_key_override";
    public static final String NEEDS_AUTOGROUPING_KEY = "autogroup_needed";

    /**
     * Create a notification adjustment.
     *
     * @param pkg The package of the notification.
     * @param key The notification key.
     * @param importance The recommended importance of the notification.
     * @param signals A bundle of signals that should inform notification grouping and ordering.
     * @param explanation A human-readable justification for the adjustment.
     * @param reference A reference to an external object that augments the
     *                  explanation, such as a
     *                  {@link android.provider.ContactsContract.Contacts#CONTENT_LOOKUP_URI},
     *                  or null.
     */
    public Adjustment(String pkg, String key, int importance, Bundle signals,
            CharSequence explanation, Uri reference, int user) {
        mPackage = pkg;
        mKey = key;
        mImportance = importance;
        mSignals = signals;
        mExplanation = explanation;
        mReference = reference;
        mUser = user;
    }

    protected Adjustment(Parcel in) {
        if (in.readInt() == 1) {
            mPackage = in.readString();
        } else {
            mPackage = null;
        }
        if (in.readInt() == 1) {
            mKey = in.readString();
        } else {
            mKey = null;
        }
        mImportance = in.readInt();
        if (in.readInt() == 1) {
            mExplanation = in.readCharSequence();
        } else {
            mExplanation = null;
        }
        mReference = in.readParcelable(Uri.class.getClassLoader());
        mSignals = in.readBundle();
        mUser = in.readInt();
    }

    public static final Creator<Adjustment> CREATOR = new Creator<Adjustment>() {
        @Override
        public Adjustment createFromParcel(Parcel in) {
            return new Adjustment(in);
        }

        @Override
        public Adjustment[] newArray(int size) {
            return new Adjustment[size];
        }
    };

    public String getPackage() {
        return mPackage;
    }

    public String getKey() {
        return mKey;
    }

    public int getImportance() {
        return mImportance;
    }

    public CharSequence getExplanation() {
        return mExplanation;
    }

    public Uri getReference() {
        return mReference;
    }

    public Bundle getSignals() {
        return mSignals;
    }

    public int getUser() {
        return mUser;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mPackage != null) {
            dest.writeInt(1);
            dest.writeString(mPackage);
        } else {
            dest.writeInt(0);
        }
        if (mKey != null) {
            dest.writeInt(1);
            dest.writeString(mKey);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(mImportance);
        if (mExplanation != null) {
            dest.writeInt(1);
            dest.writeCharSequence(mExplanation);
        } else {
            dest.writeInt(0);
        }
        dest.writeParcelable(mReference, flags);
        dest.writeBundle(mSignals);
        dest.writeInt(mUser);
    }
}
