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
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A grouping of related notification channels. e.g., channels that all belong to a single account.
 */
public final class NotificationChannelGroup implements Parcelable {

    /**
     * The maximum length for text fields in a NotificationChannelGroup. Fields will be truncated at
     * this limit.
     */
    private static final int MAX_TEXT_LENGTH = 1000;

    private static final String TAG_GROUP = "channelGroup";
    private static final String ATT_NAME = "name";
    private static final String ATT_ID = "id";

    private final String mId;
    private CharSequence mName;
    private List<NotificationChannel> mChannels = new ArrayList<>();

    /**
     * Creates a notification channel group.
     *
     * @param id The id of the group. Must be unique per package.  the value may be truncated if
     *           it is too long.
     * @param name The user visible name of the group. You can rename this group when the system
     *             locale changes by listening for the {@link Intent#ACTION_LOCALE_CHANGED}
     *             broadcast. <p>The recommended maximum length is 40 characters; the value may be
     *             truncated if it is too long.
     */
    public NotificationChannelGroup(String id, CharSequence name) {
        this.mId = getTrimmedString(id);
        this.mName = name != null ? getTrimmedString(name.toString()) : null;
    }

    /**
     * @hide
     */
    protected NotificationChannelGroup(Parcel in) {
        if (in.readByte() != 0) {
            mId = in.readString();
        } else {
            mId = null;
        }
        mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        in.readParcelableList(mChannels, NotificationChannel.class.getClassLoader());
    }

    private String getTrimmedString(String input) {
        if (input != null && input.length() > MAX_TEXT_LENGTH) {
            return input.substring(0, MAX_TEXT_LENGTH);
        }
        return input;
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

    /**
     * Returns the list of channels that belong to this group
     */
    public List<NotificationChannel> getChannels() {
        return mChannels;
    }

    /**
     * @hide
     */
    public void addChannel(NotificationChannel channel) {
        mChannels.add(channel);
    }

    /**
     * @hide
     */
    public void writeXml(XmlSerializer out) throws IOException {
        out.startTag(null, TAG_GROUP);

        out.attribute(null, ATT_ID, getId());
        if (getName() != null) {
            out.attribute(null, ATT_NAME, getName().toString());
        }

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
        return true;
    }

    @Override
    public NotificationChannelGroup clone() {
        return new NotificationChannelGroup(getId(), getName());
    }

    @Override
    public int hashCode() {
        int result = getId() != null ? getId().hashCode() : 0;
        result = 31 * result + (getName() != null ? getName().hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "NotificationChannelGroup{" +
                "mId='" + mId + '\'' +
                ", mName=" + mName +
                ", mChannels=" + mChannels +
                '}';
    }
}
