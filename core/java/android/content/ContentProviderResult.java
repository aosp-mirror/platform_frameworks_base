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

import android.content.ContentProvider;
import android.net.Uri;
import android.os.UserHandle;
import android.os.Parcelable;
import android.os.Parcel;

/**
 * Contains the result of the application of a {@link ContentProviderOperation}. It is guaranteed
 * to have exactly one of {@link #uri} or {@link #count} set.
 */
public class ContentProviderResult implements Parcelable {
    public final Uri uri;
    public final Integer count;

    public ContentProviderResult(Uri uri) {
        if (uri == null) throw new IllegalArgumentException("uri must not be null");
        this.uri = uri;
        this.count = null;
    }

    public ContentProviderResult(int count) {
        this.count = count;
        this.uri = null;
    }

    public ContentProviderResult(Parcel source) {
        int type = source.readInt();
        if (type == 1) {
            count = source.readInt();
            uri = null;
        } else {
            count = null;
            uri = Uri.CREATOR.createFromParcel(source);
        }
    }

    /** @hide */
    public ContentProviderResult(ContentProviderResult cpr, int userId) {
        uri = ContentProvider.maybeAddUserId(cpr.uri, userId);
        count = cpr.count;
    }

    public void writeToParcel(Parcel dest, int flags) {
        if (uri == null) {
            dest.writeInt(1);
            dest.writeInt(count);
        } else {
            dest.writeInt(2);
            uri.writeToParcel(dest, 0);
        }
    }

    public int describeContents() {
        return 0;
    }

    public static final Creator<ContentProviderResult> CREATOR =
            new Creator<ContentProviderResult>() {
        public ContentProviderResult createFromParcel(Parcel source) {
            return new ContentProviderResult(source);
        }

        public ContentProviderResult[] newArray(int size) {
            return new ContentProviderResult[size];
        }
    };

    public String toString() {
        if (uri != null) {
            return "ContentProviderResult(uri=" + uri.toString() + ")";
        }
        return "ContentProviderResult(count=" + count + ")";
    }
}
