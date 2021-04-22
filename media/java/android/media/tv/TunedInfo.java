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
import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;


/**
 * Contains information about a {@link TvInputService.Session} that is currently tuned to a channel
 * or pass-through input.
 * @hide
 */
@SystemApi
public final class TunedInfo implements Parcelable {
    static final String TAG = "TunedInfo";

    /**
     * App tag for {@link #getAppTag()}: the corresponding application of the channel is the same as
     * the caller.
     * <p>{@link #getAppType()} returns {@link #APP_TYPE_SELF} if and only if the app tag is
     * {@link #APP_TAG_SELF}.
     */
    public static final int APP_TAG_SELF = 0;
    /**
     * App tag for {@link #getAppType()}: the corresponding application of the channel is the same
     * as the caller.
     * <p>{@link #getAppType()} returns {@link #APP_TYPE_SELF} if and only if the app tag is
     * {@link #APP_TAG_SELF}.
     */
    public static final int APP_TYPE_SELF = 1;
    /**
     * App tag for {@link #getAppType()}: the corresponding app of the channel is a system
     * application.
     */
    public static final int APP_TYPE_SYSTEM = 2;
    /**
     * App tag for {@link #getAppType()}: the corresponding app of the channel is not a system
     * application.
     */
    public static final int APP_TYPE_NON_SYSTEM = 3;

    /** @hide */
    @IntDef(prefix = "APP_TYPE_", value = {APP_TYPE_SELF, APP_TYPE_SYSTEM, APP_TYPE_NON_SYSTEM})
    @Retention(RetentionPolicy.SOURCE)
    public @interface AppType {}

    public static final @NonNull Parcelable.Creator<TunedInfo> CREATOR =
            new Parcelable.Creator<TunedInfo>() {
                @Override
                public TunedInfo createFromParcel(Parcel source) {
                    try {
                        return new TunedInfo(source);
                    } catch (Exception e) {
                        Log.e(TAG, "Exception creating TunedInfo from parcel", e);
                        return null;
                    }
                }

                @Override
                public TunedInfo[] newArray(int size) {
                    return new TunedInfo[size];
                }
            };


    private final String mInputId;
    @Nullable private final Uri mChannelUri;
    private final boolean mIsRecordingSession;
    private final boolean mIsVisible;
    private final boolean mIsMainSession;
    @AppType private final int mAppType;
    private final int mAppTag;

    /** @hide */
    public TunedInfo(
            String inputId, @Nullable Uri channelUri, boolean isRecordingSession,
            boolean isVisible, boolean isMainSession, @AppType int appType, int appTag) {
        mInputId = inputId;
        mChannelUri = channelUri;
        mIsRecordingSession = isRecordingSession;
        mIsVisible = isVisible;
        mIsMainSession = isMainSession;
        mAppType = appType;
        mAppTag = appTag;
    }


    private TunedInfo(Parcel source) {
        mInputId = source.readString();
        String uriString = source.readString();
        mChannelUri = uriString == null ? null : Uri.parse(uriString);
        mIsRecordingSession = (source.readInt() == 1);
        mIsVisible = (source.readInt() == 1);
        mIsMainSession = (source.readInt() == 1);
        mAppType = source.readInt();
        mAppTag = source.readInt();
    }

    /**
     * Returns the TV input ID of the channel.
     */
    @NonNull
    public String getInputId() {
        return mInputId;
    }

    /**
     * Returns the channel URI of the channel.
     * <p>Returns {@code null} if it's a passthrough input or the permission is not granted.
     */
    @Nullable
    public Uri getChannelUri() {
        return mChannelUri;
    }

    /**
     * Returns {@code true} if the channel session is a recording session.
     * @see TvInputService.RecordingSession
     */
    public boolean isRecordingSession() {
        return mIsRecordingSession;
    }

    /**
     * Returns {@code true} if the corresponding session is visible.
     * <p>The system checks whether the {@link Surface} of the session is {@code null} or not. When
     * it becomes invisible, the surface is destroyed and set to null.
     * @see TvInputService.Session#onSetSurface(Surface)
     * @see android.view.SurfaceView#notifySurfaceDestroyed
     */
    public boolean isVisible() {
        return mIsVisible;
    }

    /**
     * Returns {@code true} if the corresponding session is set as main session.
     * @see TvView#setMain
     * @see TvInputService.Session#onSetMain
     */
    public boolean isMainSession() {
        return mIsMainSession;
    }

    /**
     * Returns the app tag.
     * <p>App tag is used to differentiate one app from another.
     * {@link #APP_TAG_SELF} is for current app.
     */
    public int getAppTag() {
        return mAppTag;
    }

    /**
     * Returns the app type.
     */
    @AppType
    public int getAppType() {
        return mAppType;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mInputId);
        String uriString = mChannelUri == null ? null : mChannelUri.toString();
        dest.writeString(uriString);
        dest.writeInt(mIsRecordingSession ? 1 : 0);
        dest.writeInt(mIsVisible ? 1 : 0);
        dest.writeInt(mIsMainSession ? 1 : 0);
        dest.writeInt(mAppType);
        dest.writeInt(mAppTag);
    }

    @Override
    public String toString() {
        return "inputID=" + mInputId
                + ";channelUri=" + mChannelUri
                + ";isRecording=" + mIsRecordingSession
                + ";isVisible=" + mIsVisible
                + ";isMainSession=" + mIsMainSession
                + ";appType=" + mAppType
                + ";appTag=" + mAppTag;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof TunedInfo)) {
            return false;
        }

        TunedInfo other = (TunedInfo) o;

        return TextUtils.equals(mInputId, other.getInputId())
                && Objects.equals(mChannelUri, other.mChannelUri)
                && mIsRecordingSession == other.mIsRecordingSession
                && mIsVisible == other.mIsVisible
                && mIsMainSession == other.mIsMainSession
                && mAppType == other.mAppType
                && mAppTag == other.mAppTag;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                mInputId, mChannelUri, mIsRecordingSession, mIsVisible, mIsMainSession, mAppType,
                mAppTag);
    }
}
