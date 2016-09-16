package android.app;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import android.annotation.SystemApi;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.io.IOException;

/**
 * A representation of settings that apply to a collection of similarly themed notifications.
 */
public final class NotificationChannel implements Parcelable {

    /**
     * The id of the default channel for an app. All notifications posted without a notification
     * channel specified are posted to this channel.
     */
    public static final String DEFAULT_CHANNEL_ID = "miscellaneous";

    private static final String TAG_CHANNEL = "channel";
    private static final String ATT_NAME = "name";
    private static final String ATT_ID = "id";
    private static final String ATT_PRIORITY = "priority";
    private static final String ATT_VISIBILITY = "visibility";
    private static final String ATT_IMPORTANCE = "importance";
    private static final String ATT_LIGHTS = "lights";
    private static final String ATT_VIBRATION = "vibration";
    private static final String ATT_DEFAULT_RINGTONE = "ringtone";

    private static final int DEFAULT_VISIBILITY =
            NotificationListenerService.Ranking.VISIBILITY_NO_OVERRIDE;
    private static final int DEFAULT_IMPORTANCE =
            NotificationListenerService.Ranking.IMPORTANCE_UNSPECIFIED;

    private final String mId;
    private CharSequence mName;
    private int mImportance = DEFAULT_IMPORTANCE;
    private boolean mBypassDnd;
    private int mLockscreenVisibility = DEFAULT_VISIBILITY;
    private Uri mRingtone;
    private boolean mLights;
    private boolean mVibration;

    /**
     * Creates a notification channel.
     *
     * @param id The id of the channel. Must be unique per package.
     * @param name The user visible name of the channel.
     */
    public NotificationChannel(String id, CharSequence name) {
        this.mId = id;
        this.mName = name;
    }

    protected NotificationChannel(Parcel in) {
        if (in.readByte() != 0) {
            mId = in.readString();
        } else {
            mId = null;
        }
        mName = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mImportance = in.readInt();
        mBypassDnd = in.readByte() != 0;
        mLockscreenVisibility = in.readInt();
        if (in.readByte() != 0) {
            mRingtone = Uri.CREATOR.createFromParcel(in);
        } else {
            mRingtone = null;
        }
        mLights = in.readByte() != 0;
        mVibration = in.readByte() != 0;
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
        dest.writeInt(mImportance);
        dest.writeByte(mBypassDnd ? (byte) 1 : (byte) 0);
        dest.writeInt(mLockscreenVisibility);
        if (mRingtone != null) {
            dest.writeByte((byte) 1);
            mRingtone.writeToParcel(dest, 0);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeByte(mLights ? (byte) 1 : (byte) 0);
        dest.writeByte(mVibration ? (byte) 1 : (byte) 0);
    }

    // Only modifiable by users.
    /**
     * @hide
     */
    @SystemApi
    public void setName(CharSequence name) {
        this.mName = name;
    }

    /**
     * @hide
     */
    @SystemApi
    public void setImportance(int importance) {
        this.mImportance = importance;
    }

    /**
     * @hide
     */
    @SystemApi
    public void setBypassDnd(boolean bypassDnd) {
        this.mBypassDnd = bypassDnd;
    }

    /**
     * @hide
     */
    @SystemApi
    public void setLockscreenVisibility(int lockscreenVisibility) {
        this.mLockscreenVisibility = lockscreenVisibility;
    }

    // Modifiable by apps.

    /**
     * Sets the ringtone that should be played for notifications posted to this channel if
     * the notifications don't supply a ringtone.
     */
    public void setDefaultRingtone(Uri defaultRingtone) {
        this.mRingtone = defaultRingtone;
    }

    /**
     * Sets whether notifications posted to this channel should display notification lights,
     * on devices that support that feature.
     */
    public void setLights(boolean lights) {
        this.mLights = lights;
    }

    /**
     * Sets whether notification posted to this channel should vibrate, even if individual
     * notifications are marked as having vibration.
     */
    public void setVibration(boolean vibration) {
        this.mVibration = vibration;
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
     * Returns the user specified importance {e.g. @link NotificationManager#IMPORTANCE_LOW} for
     * notifications posted to this channel.
     */
    public int getImportance() {
        return mImportance;
    }

    /**
     * Whether or not notifications posted to this channel can bypass the Do Not Disturb
     * {@link NotificationManager#INTERRUPTION_FILTER_PRIORITY} mode.
     */
    public boolean canBypassDnd() {
        return mBypassDnd;
    }

    /**
     * Returns the notification sound for this channel.
     */
    public Uri getDefaultRingtone() {
        return mRingtone;
    }

    /**
     * Returns whether notifications posted to this channel trigger notification lights.
     */
    public boolean shouldShowLights() {
        return mLights;
    }

    /**
     * Returns whether notifications posted to this channel always vibrate.
     */
    public boolean shouldVibrate() {
        return mVibration;
    }

    /**
     * @hide
     */
    @SystemApi
    public int getLockscreenVisibility() {
        return mLockscreenVisibility;
    }

    /**
     * @hide
     */
    @SystemApi
    public void populateFromXml(XmlPullParser parser) {
        // Name and id are set in the constructor.
        setImportance(safeInt(parser, ATT_IMPORTANCE, DEFAULT_IMPORTANCE));
        setBypassDnd(Notification.PRIORITY_DEFAULT
                != safeInt(parser, ATT_PRIORITY, Notification.PRIORITY_DEFAULT));
        setLockscreenVisibility(safeInt(parser, ATT_VISIBILITY, DEFAULT_VISIBILITY));
        setDefaultRingtone(safeUri(parser, ATT_DEFAULT_RINGTONE));
        setLights(safeBool(parser, ATT_LIGHTS, false));
        setVibration(safeBool(parser, ATT_VIBRATION, false));
    }

    /**
     * @hide
     */
    @SystemApi
    public void writeXml(XmlSerializer out) throws IOException {
        out.startTag(null, TAG_CHANNEL);
        out.attribute(null, ATT_ID, getId());
        out.attribute(null, ATT_NAME, getName().toString());
        if (getImportance() != DEFAULT_IMPORTANCE) {
            out.attribute(
                    null, ATT_IMPORTANCE, Integer.toString(getImportance()));
        }
        if (canBypassDnd()) {
            out.attribute(
                    null, ATT_PRIORITY, Integer.toString(Notification.PRIORITY_MAX));
        }
        if (getLockscreenVisibility() != DEFAULT_VISIBILITY) {
            out.attribute(null, ATT_VISIBILITY,
                    Integer.toString(getLockscreenVisibility()));
        }
        if (getDefaultRingtone() != null) {
            out.attribute(null, ATT_DEFAULT_RINGTONE, getDefaultRingtone().toString());
        }
        if (shouldShowLights()) {
            out.attribute(null, ATT_LIGHTS, Boolean.toString(shouldShowLights()));
        }
        if (shouldVibrate()) {
            out.attribute(null, ATT_VIBRATION, Boolean.toString(shouldVibrate()));
        }
        out.endTag(null, TAG_CHANNEL);
    }

    /**
     * @hide
     */
    @SystemApi
    public JSONObject toJson() throws JSONException {
        JSONObject record = new JSONObject();
        record.put(ATT_ID, getId());
        record.put(ATT_NAME, getName());
        if (getImportance() != DEFAULT_IMPORTANCE) {
            record.put(ATT_IMPORTANCE,
                    NotificationListenerService.Ranking.importanceToString(getImportance()));
        }
        if (canBypassDnd()) {
            record.put(ATT_PRIORITY, Notification.PRIORITY_MAX);
        }
        if (getLockscreenVisibility() != DEFAULT_VISIBILITY) {
            record.put(ATT_VISIBILITY, Notification.visibilityToString(getLockscreenVisibility()));
        }
        if (getDefaultRingtone() != null) {
            record.put(ATT_DEFAULT_RINGTONE, getDefaultRingtone().toString());
        }
        record.put(ATT_LIGHTS, Boolean.toString(shouldShowLights()));
        record.put(ATT_VIBRATION, Boolean.toString(shouldVibrate()));

        return record;
    }

    private static Uri safeUri(XmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        return val == null ? null : Uri.parse(val);
    }

    private static int safeInt(XmlPullParser parser, String att, int defValue) {
        final String val = parser.getAttributeValue(null, att);
        return tryParseInt(val, defValue);
    }

    private static int tryParseInt(String value, int defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    private static boolean safeBool(XmlPullParser parser, String att, boolean defValue) {
        final String value = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(value)) return defValue;
        return Boolean.parseBoolean(value);
    }

    public static final Creator<NotificationChannel> CREATOR = new Creator<NotificationChannel>() {
        @Override
        public NotificationChannel createFromParcel(Parcel in) {
            return new NotificationChannel(in);
        }

        @Override
        public NotificationChannel[] newArray(int size) {
            return new NotificationChannel[size];
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

        NotificationChannel that = (NotificationChannel) o;

        if (mImportance != that.mImportance) return false;
        if (mBypassDnd != that.mBypassDnd) return false;
        if (mLockscreenVisibility != that.mLockscreenVisibility) return false;
        if (mLights != that.mLights) return false;
        if (mVibration != that.mVibration) return false;
        if (!mId.equals(that.mId)) return false;
        if (mName != null ? !mName.equals(that.mName) : that.mName != null) return false;
        return mRingtone != null ? mRingtone.equals(
                that.mRingtone) : that.mRingtone == null;
    }

    @Override
    public String toString() {
        return "NotificationChannel{" +
                "mId='" + mId + '\'' +
                ", mName=" + mName +
                ", mImportance=" + mImportance +
                ", mBypassDnd=" + mBypassDnd +
                ", mLockscreenVisibility=" + mLockscreenVisibility +
                ", mRingtone='" + mRingtone + '\'' +
                ", mLights=" + mLights +
                ", mVibration=" + mVibration +
                '}';
    }

    @Override
    public int hashCode() {
        int result = mId.hashCode();
        result = 31 * result + (mName != null ? mName.hashCode() : 0);
        result = 31 * result + mImportance;
        result = 31 * result + (mBypassDnd ? 1 : 0);
        result = 31 * result + mLockscreenVisibility;
        result = 31 * result + (mRingtone != null ? mRingtone.hashCode() : 0);
        result = 31 * result + (mLights ? 1 : 0);
        result = 31 * result + (mVibration ? 1 : 0);
        return result;
    }
}
