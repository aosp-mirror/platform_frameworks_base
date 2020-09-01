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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ParcelableException;

import java.util.Objects;

/**
 * Contains the result of the application of a {@link ContentProviderOperation}.
 * <p>
 * It is guaranteed to have exactly one of {@link #uri}, {@link #count},
 * {@link #extras}, or {@link #exception} set.
 */
public class ContentProviderResult implements Parcelable {
    public final @Nullable Uri uri;
    public final @Nullable Integer count;
    public final @Nullable Bundle extras;
    public final @Nullable Throwable exception;

    public ContentProviderResult(@NonNull Uri uri) {
        this(Objects.requireNonNull(uri), null, null, null);
    }

    public ContentProviderResult(int count) {
        this(null, count, null, null);
    }

    public ContentProviderResult(@NonNull Bundle extras) {
        this(null, null, Objects.requireNonNull(extras), null);
    }

    public ContentProviderResult(@NonNull Throwable exception) {
        this(null, null, null, exception);
    }

    /** {@hide} */
    public ContentProviderResult(Uri uri, Integer count, Bundle extras, Throwable exception) {
        this.uri = uri;
        this.count = count;
        this.extras = extras;
        this.exception = exception;
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
            extras = source.readBundle();
        } else {
            extras = null;
        }
        if (source.readInt() != 0) {
            exception = ParcelableException.readFromParcel(source);
        } else {
            exception = null;
        }
    }

    /** @hide */
    public ContentProviderResult(ContentProviderResult cpr, int userId) {
        uri = ContentProvider.maybeAddUserId(cpr.uri, userId);
        count = cpr.count;
        extras = cpr.extras;
        exception = cpr.exception;
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
        if (extras != null) {
            dest.writeInt(1);
            dest.writeBundle(extras);
        } else {
            dest.writeInt(0);
        }
        if (exception != null) {
            dest.writeInt(1);
            ParcelableException.writeToParcel(dest, exception);
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
        if (extras != null) {
            sb.append("extras=" + extras + " ");
        }
        if (exception != null) {
            sb.append("exception=" + exception + " ");
        }
        sb.deleteCharAt(sb.length() - 1);
        sb.append(")");
        return sb.toString();
    }
}
