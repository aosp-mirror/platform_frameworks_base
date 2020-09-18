/*
 * Copyright 2020 The Android Open Source Project
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

package android.media.tv;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * @hide
 */
public final class TvChannelInfo implements Parcelable {
    static final String TAG = "TvChannelInfo";
    public static final int APP_TAG_SELF = 0;
    public static final int APP_TYPE_SELF = 1;
    public static final int APP_TYPE_SYSTEM = 2;
    public static final int APP_TYPE_NON_SYSTEM = 3;

    /** @hide */
    @IntDef(prefix = "APP_TYPE_", value = {APP_TYPE_SELF, APP_TYPE_SYSTEM, APP_TYPE_NON_SYSTEM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AppType {}

    public static final @NonNull Parcelable.Creator<TvChannelInfo> CREATOR =
            new Parcelable.Creator<TvChannelInfo>() {
                @Override
                public TvChannelInfo createFromParcel(Parcel source) {
                    try {
                        return new TvChannelInfo(source);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception creating TvChannelInfo from parcel", e);
                        return null;
                    }
                }

                @Override
                public TvChannelInfo[] newArray(int size) {
                    return new TvChannelInfo[size];
                }
            };


    private final String mInputId;
    @Nullable private final Uri mChannelUri;
    private final boolean mIsRecordingSession;
    private final boolean mIsForeground;
    @AppType private final int mAppType;
    private final int mAppTag;

    public TvChannelInfo(
            String inputId, @Nullable Uri channelUri, boolean isRecordingSession,
            boolean isForeground, @AppType int appType, int appTag) {
        mInputId = inputId;
        mChannelUri = channelUri;
        mIsRecordingSession = isRecordingSession;
        mIsForeground = isForeground;
        mAppType = appType;
        mAppTag = appTag;
    }


    private TvChannelInfo(Parcel source) {
        mInputId = source.readString();
        String uriString = source.readString();
        mChannelUri = uriString == null ? null : Uri.parse(uriString);
        mIsRecordingSession = (source.readInt() == 1);
        mIsForeground = (source.readInt() == 1);
        mAppType = source.readInt();
        mAppTag = source.readInt();
    }

    public String getInputId() {
        return mInputId;
    }

    public Uri getChannelUri() {
        return mChannelUri;
    }

    public boolean isRecordingSession() {
        return mIsRecordingSession;
    }

    public boolean isForeground() {
        return mIsForeground;
    }

    /**
     * Gets app tag.
     * <p>App tag is used to differentiate one app from another.
     * {@link #APP_TAG_SELF} is for current app.
     */
    public int getAppTag() {
        return mAppTag;
    }

    @AppType
    public int getAppType() {
        return mAppType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mInputId);
        String uriString = mChannelUri == null ? null : mChannelUri.toString();
        dest.writeString(uriString);
        dest.writeInt(mIsRecordingSession ? 1 : 0);
        dest.writeInt(mIsForeground ? 1 : 0);
        dest.writeInt(mAppType);
        dest.writeInt(mAppTag);
    }

    @Override
    public String toString() {
        return "inputID=" + mInputId
                + ";channelUri=" + mChannelUri
                + ";isRecording=" + mIsRecordingSession
                + ";isForeground=" + mIsForeground
                + ";appType=" + mAppType
                + ";appTag=" + mAppTag;
    }
}
