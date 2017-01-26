/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.util.Slog;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A grouping of related notification channels. e.g., channels that all belong to a single account.
 */
public final class NotificationChannelGroup implements Parcelable {

    private static final String TAG_GROUP = "channelGroup";
    private static final String ATT_NAME = "name";
    private static final String ATT_ID = "id";

    private final String mId;
    private CharSequence mName;
    private List<NotificationChannel> mChannels = new ArrayList<>();

    /**
     * Creates a notification channel.
     *
     * @param id The id of the group. Must be unique per package.
     * @param name The user visible name of the group.
     */
    public NotificationChannelGroup(String id, CharSequence name) {
        this.mId = id;
        this.mName = name;
    }

    protected NotificationChannelGroup(Parcel in) {
        if (in.readByte() != 0) {
            mId = in.readString();
        } else {
            mId = null;
        }
        mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        in.readList(mChannels, NotificationChannel.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (mId != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mId);
        } else {
            dest.writeByte((byte) 0);
        }
        TextUtils.writeToParcel(mName, dest, flags);
        dest.writeParcelableList(mChannels, flags);
    }

    /**
     * Returns the id of this channel.
     */
    public String getId() {
        return mId;
    }

    /**
     * Returns the user visible name of this channel.
     */
    public CharSequence getName() {
        return mName;
    }

    /*
     * Returns the list of channels that belong to this group
     *
     * @hide
     */
    @SystemApi
    public List<NotificationChannel> getChannels() {
        return mChannels;
    }

    /**
     * @hide
     */
    @SystemApi
    public void addChannel(NotificationChannel channel) {
        mChannels.add(channel);
    }

    /**
     * @hide
     */
    @SystemApi
    public void writeXml(XmlSerializer out) throws IOException {
        out.startTag(null, TAG_GROUP);

        out.attribute(null, ATT_ID, getId());
        out.attribute(null, ATT_NAME, getName().toString());

        out.endTag(null, TAG_GROUP);
    }

    /**
     * @hide
     */
    @SystemApi
    public JSONObject toJson() throws JSONException {
        JSONObject record = new JSONObject();
        record.put(ATT_ID, getId());
        record.put(ATT_NAME, getName());
        return record;
    }

    public static final Creator<NotificationChannelGroup> CREATOR =
            new Creator<NotificationChannelGroup>() {
        @Override
        public NotificationChannelGroup createFromParcel(Parcel in) {
            return new NotificationChannelGroup(in);
        }

        @Override
        public NotificationChannelGroup[] newArray(int size) {
            return new NotificationChannelGroup[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        NotificationChannelGroup that = (NotificationChannelGroup) o;

        if (getId() != null ? !getId().equals(that.getId()) : that.getId() != null) return false;
        if (getName() != null ? !getName().equals(that.getName()) : that.getName() != null) {
            return false;
        }
        return getChannels() != null ? getChannels().equals(that.getChannels())
                : that.getChannels() == null;

    }

    @Override
    public int hashCode() {
        int result = getId() != null ? getId().hashCode() : 0;
        result = 31 * result + (getName() != null ? getName().hashCode() : 0);
        result = 31 * result + (getChannels() != null ? getChannels().hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "NotificationChannelGroup{" +
                "mId='" + mId + '\'' +
                ", mName=" + mName +
                '}';
    }
}
