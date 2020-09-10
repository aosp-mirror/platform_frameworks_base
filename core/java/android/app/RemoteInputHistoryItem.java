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

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Stores historical input from a RemoteInput attached to a Notification.
 *
 * History items represent either a text message (specified by providing a CharSequence,
 * or a media message (specified by providing a URI and a MIME type). Media messages must also
 * include text to insert when the image cannot be loaded, ex. when URI read permission has not been
 * granted correctly.
 *
 * @hide
 */
public class RemoteInputHistoryItem implements Parcelable {
    private CharSequence mText;
    private String mMimeType;
    private Uri mUri;

    public RemoteInputHistoryItem(String mimeType, Uri uri, CharSequence backupText) {
        this.mMimeType = mimeType;
        this.mUri = uri;
        this.mText = Notification.safeCharSequence(backupText);
    }

    public RemoteInputHistoryItem(CharSequence text) {
        this.mText = Notification.safeCharSequence(text);
    }

    protected RemoteInputHistoryItem(Parcel in) {
        mText = in.readCharSequence();
        mMimeType = in.readStringNoHelper();
        mUri = in.readParcelable(Uri.class.getClassLoader());
    }

    public static final Creator<RemoteInputHistoryItem> CREATOR =
            new Creator<RemoteInputHistoryItem>() {
                @Override
                public RemoteInputHistoryItem createFromParcel(Parcel in) {
                    return new RemoteInputHistoryItem(in);
                }

                @Override
                public RemoteInputHistoryItem[] newArray(int size) {
                    return new RemoteInputHistoryItem[size];
                }
            };

    public CharSequence getText() {
        return mText;
    }

    public String getMimeType() {
        return mMimeType;
    }

    public Uri getUri() {
        return mUri;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeCharSequence(mText);
        dest.writeStringNoHelper(mMimeType);
        dest.writeParcelable(mUri, flags);
    }
}
