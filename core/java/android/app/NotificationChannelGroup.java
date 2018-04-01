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
import android.annotation.TestApi;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.proto.ProtoOutputStream;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
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
    private static final String ATT_DESC = "desc";
    private static final String ATT_ID = "id";
    private static final String ATT_BLOCKED = "blocked";

    private final String mId;
    private CharSequence mName;
    private String mDescription;
    private boolean mBlocked;
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
        if (in.readByte() != 0) {
            mDescription = in.readString();
        } else {
            mDescription = null;
        }
        in.readParcelableList(mChannels, NotificationChannel.class.getClassLoader());
        mBlocked = in.readBoolean();
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
        if (mDescription != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mDescription);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeParcelableList(mChannels, flags);
        dest.writeBoolean(mBlocked);
    }

    /**
     * Returns the id of this group.
     */
    public String getId() {
        return mId;
    }

    /**
     * Returns the user visible name of this group.
     */
    public CharSequence getName() {
        return mName;
    }

    /**
     * Returns the user visible description of this group.
     */
    public String getDescription() {
        return mDescription;
    }

    /**
     * Returns the list of channels that belong to this group
     */
    public List<NotificationChannel> getChannels() {
        return mChannels;
    }

    /**
     * Returns whether or not notifications posted to {@link NotificationChannel channels} belonging
     * to this group are blocked. This value is independent of
     * {@link NotificationManager#areNotificationsEnabled()} and
     * {@link NotificationChannel#getImportance()}.
     */
    public boolean isBlocked() {
        return mBlocked;
    }

    /**
     * Sets the user visible description of this group.
     *
     * <p>The recommended maximum length is 300 characters; the value may be truncated if it is too
     * long.
     */
    public void setDescription(String description) {
        mDescription = getTrimmedString(description);
    }

    /**
     * @hide
     */
    @TestApi
    public void setBlocked(boolean blocked) {
        mBlocked = blocked;
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
    public void setChannels(List<NotificationChannel> channels) {
        mChannels = channels;
    }

    /**
     * @hide
     */
    public void populateFromXml(XmlPullParser parser) {
        // Name, id, and importance are set in the constructor.
        setDescription(parser.getAttributeValue(null, ATT_DESC));
        setBlocked(safeBool(parser, ATT_BLOCKED, false));
    }

    private static boolean safeBool(XmlPullParser parser, String att, boolean defValue) {
        final String value = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(value)) return defValue;
        return Boolean.parseBoolean(value);
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
        if (getDescription() != null) {
            out.attribute(null, ATT_DESC, getDescription().toString());
        }
        out.attribute(null, ATT_BLOCKED, Boolean.toString(isBlocked()));

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
        record.put(ATT_DESC, getDescription());
        record.put(ATT_BLOCKED, isBlocked());
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

        if (isBlocked() != that.isBlocked()) return false;
        if (getId() != null ? !getId().equals(that.getId()) : that.getId() != null) return false;
        if (getName() != null ? !getName().equals(that.getName()) : that.getName() != null) {
            return false;
        }
        if (getDescription() != null ? !getDescription().equals(that.getDescription())
                : that.getDescription() != null) {
            return false;
        }
        return getChannels() != null ? getChannels().equals(that.getChannels())
                : that.getChannels() == null;
    }

    @Override
    public int hashCode() {
        int result = getId() != null ? getId().hashCode() : 0;
        result = 31 * result + (getName() != null ? getName().hashCode() : 0);
        result = 31 * result + (getDescription() != null ? getDescription().hashCode() : 0);
        result = 31 * result + (isBlocked() ? 1 : 0);
        result = 31 * result + (getChannels() != null ? getChannels().hashCode() : 0);
        return result;
    }

    @Override
    public NotificationChannelGroup clone() {
        NotificationChannelGroup cloned = new NotificationChannelGroup(getId(), getName());
        cloned.setDescription(getDescription());
        cloned.setBlocked(isBlocked());
        cloned.setChannels(getChannels());
        return cloned;
    }

    @Override
    public String toString() {
        return "NotificationChannelGroup{"
                + "mId='" + mId + '\''
                + ", mName=" + mName
                + ", mDescription=" + (!TextUtils.isEmpty(mDescription) ? "hasDescription " : "")
                + ", mBlocked=" + mBlocked
                + ", mChannels=" + mChannels
                + '}';
    }

    /** @hide */
    public void writeToProto(ProtoOutputStream proto, long fieldId) {
        final long token = proto.start(fieldId);

        proto.write(NotificationChannelGroupProto.ID, mId);
        proto.write(NotificationChannelGroupProto.NAME, mName.toString());
        proto.write(NotificationChannelGroupProto.DESCRIPTION, mDescription);
        proto.write(NotificationChannelGroupProto.IS_BLOCKED, mBlocked);
        for (NotificationChannel channel : mChannels) {
            channel.writeToProto(proto, NotificationChannelGroupProto.CHANNELS);
        }

        proto.end(token);
    }
}
