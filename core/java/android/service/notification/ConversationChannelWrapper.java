/**
 * Copyright (c) 2020, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.service.notification;

import android.annotation.Nullable;
import android.app.NotificationChannel;
import android.content.pm.ShortcutInfo;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * @hide
 */
public final class ConversationChannelWrapper implements Parcelable {

    private NotificationChannel mNotificationChannel;
    private CharSequence mGroupLabel;
    private CharSequence mParentChannelLabel;
    private ShortcutInfo mShortcutInfo;
    private String mPkg;
    private int mUid;

    public ConversationChannelWrapper() {}

    protected ConversationChannelWrapper(Parcel in) {
        mNotificationChannel = in.readParcelable(NotificationChannel.class.getClassLoader());
        mGroupLabel = in.readCharSequence();
        mParentChannelLabel = in.readCharSequence();
        mShortcutInfo = in.readParcelable(ShortcutInfo.class.getClassLoader());
        mPkg = in.readStringNoHelper();
        mUid = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mNotificationChannel, flags);
        dest.writeCharSequence(mGroupLabel);
        dest.writeCharSequence(mParentChannelLabel);
        dest.writeParcelable(mShortcutInfo, flags);
        dest.writeStringNoHelper(mPkg);
        dest.writeInt(mUid);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ConversationChannelWrapper> CREATOR =
            new Creator<ConversationChannelWrapper>() {
                @Override
                public ConversationChannelWrapper createFromParcel(Parcel in) {
                    return new ConversationChannelWrapper(in);
                }

                @Override
                public ConversationChannelWrapper[] newArray(int size) {
                    return new ConversationChannelWrapper[size];
                }
            };


    public NotificationChannel getNotificationChannel() {
        return mNotificationChannel;
    }

    public void setNotificationChannel(
            NotificationChannel notificationChannel) {
        mNotificationChannel = notificationChannel;
    }

    public CharSequence getGroupLabel() {
        return mGroupLabel;
    }

    public void setGroupLabel(CharSequence groupLabel) {
        mGroupLabel = groupLabel;
    }

    public CharSequence getParentChannelLabel() {
        return mParentChannelLabel;
    }

    public void setParentChannelLabel(CharSequence parentChannelLabel) {
        mParentChannelLabel = parentChannelLabel;
    }

    public ShortcutInfo getShortcutInfo() {
        return mShortcutInfo;
    }

    public void setShortcutInfo(ShortcutInfo shortcutInfo) {
        mShortcutInfo = shortcutInfo;
    }

    public String getPkg() {
        return mPkg;
    }

    public void setPkg(String pkg) {
        mPkg = pkg;
    }

    public int getUid() {
        return mUid;
    }

    public void setUid(int uid) {
        mUid = uid;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ConversationChannelWrapper that = (ConversationChannelWrapper) o;
        return Objects.equals(getNotificationChannel(), that.getNotificationChannel()) &&
                Objects.equals(getGroupLabel(), that.getGroupLabel()) &&
                Objects.equals(getParentChannelLabel(), that.getParentChannelLabel()) &&
                Objects.equals(getShortcutInfo(), that.getShortcutInfo()) &&
                Objects.equals(getPkg(), that.getPkg()) &&
                getUid() == that.getUid();
    }

    @Override
    public int hashCode() {
        return Objects.hash(getNotificationChannel(), getGroupLabel(), getParentChannelLabel(),
                getShortcutInfo(), getPkg(), getUid());
    }
}
