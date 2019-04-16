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

package android.content;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Contains the result of the application of a {@link ContentProviderOperation}. It is guaranteed
 * to have exactly one of {@link #uri} or {@link #count} set.
 */
public class ContentProviderResult implements Parcelable {
    public final Uri uri;
    public final Integer count;
    /** {@hide} */
    public final String failure;

    public ContentProviderResult(Uri uri) {
        this(Preconditions.checkNotNull(uri), null, null);
    }

    public ContentProviderResult(int count) {
        this(null, count, null);
    }

    /** {@hide} */
    public ContentProviderResult(String failure) {
        this(null, null, failure);
    }

    /** {@hide} */
    public ContentProviderResult(Uri uri, Integer count, String failure) {
        this.uri = uri;
        this.count = count;
        this.failure = failure;
    }

    public ContentProviderResult(Parcel source) {
        if (source.readInt() != 0) {
            uri = Uri.CREATOR.createFromParcel(source);
        } else {
            uri = null;
        }
        if (source.readInt() != 0) {
            count = source.readInt();
        } else {
            count = null;
        }
        if (source.readInt() != 0) {
            failure = source.readString();
        } else {
            failure = null;
        }
    }

    /** @hide */
    public ContentProviderResult(ContentProviderResult cpr, int userId) {
        uri = ContentProvider.maybeAddUserId(cpr.uri, userId);
        count = cpr.count;
        failure = cpr.failure;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (uri != null) {
            dest.writeInt(1);
            uri.writeToParcel(dest, flags);
        } else {
            dest.writeInt(0);
        }
        if (count != null) {
            dest.writeInt(1);
            dest.writeInt(count);
        } else {
            dest.writeInt(0);
        }
        if (failure != null) {
            dest.writeInt(1);
            dest.writeString(failure);
        } else {
            dest.writeInt(0);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @android.annotation.NonNull Creator<ContentProviderResult> CREATOR =
            new Creator<ContentProviderResult>() {
        @Override
        public ContentProviderResult createFromParcel(Parcel source) {
            return new ContentProviderResult(source);
        }

        @Override
        public ContentProviderResult[] newArray(int size) {
            return new ContentProviderResult[size];
        }
    };

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ContentProviderResult(");
        if (uri != null) {
            sb.append("uri=" + uri + " ");
        }
        if (count != null) {
            sb.append("count=" + count + " ");
        }
        if (uri != null) {
            sb.append("failure=" + failure + " ");
        }
        sb.deleteCharAt(sb.length() - 1);
        sb.append(")");
        return sb.toString();
    }
}
