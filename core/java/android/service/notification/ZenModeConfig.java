/**
 * Copyright (c) 2014, The Android Open Source Project
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

import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/**
 * Persisted configuration for zen mode.
 *
 * @hide
 */
public class ZenModeConfig implements Parcelable {

    public static final String SLEEP_MODE_NIGHTS = "nights";
    public static final String SLEEP_MODE_WEEKNIGHTS = "weeknights";

    public static final int SOURCE_ANYONE = 0;
    public static final int SOURCE_CONTACT = 1;
    public static final int SOURCE_STAR = 2;
    public static final int MAX_SOURCE = SOURCE_STAR;

    private static final int XML_VERSION = 1;
    private static final String ZEN_TAG = "zen";
    private static final String ZEN_ATT_VERSION = "version";
    private static final String ALLOW_TAG = "allow";
    private static final String ALLOW_ATT_CALLS = "calls";
    private static final String ALLOW_ATT_MESSAGES = "messages";
    private static final String ALLOW_ATT_FROM = "from";
    private static final String SLEEP_TAG = "sleep";
    private static final String SLEEP_ATT_MODE = "mode";

    private static final String SLEEP_ATT_START_HR = "startHour";
    private static final String SLEEP_ATT_START_MIN = "startMin";
    private static final String SLEEP_ATT_END_HR = "endHour";
    private static final String SLEEP_ATT_END_MIN = "endMin";

    private static final String CONDITION_TAG = "condition";
    private static final String CONDITION_ATT_COMPONENT = "component";
    private static final String CONDITION_ATT_ID = "id";

    public boolean allowCalls;
    public boolean allowMessages;
    public int allowFrom = SOURCE_ANYONE;

    public String sleepMode;
    public int sleepStartHour;
    public int sleepStartMinute;
    public int sleepEndHour;
    public int sleepEndMinute;
    public ComponentName[] conditionComponents;
    public Uri[] conditionIds;

    public ZenModeConfig() { }

    public ZenModeConfig(Parcel source) {
        allowCalls = source.readInt() == 1;
        allowMessages = source.readInt() == 1;
        if (source.readInt() == 1) {
            sleepMode = source.readString();
        }
        sleepStartHour = source.readInt();
        sleepStartMinute = source.readInt();
        sleepEndHour = source.readInt();
        sleepEndMinute = source.readInt();
        int len = source.readInt();
        if (len > 0) {
            conditionComponents = new ComponentName[len];
            source.readTypedArray(conditionComponents, ComponentName.CREATOR);
        }
        len = source.readInt();
        if (len > 0) {
            conditionIds = new Uri[len];
            source.readTypedArray(conditionIds, Uri.CREATOR);
        }
        allowFrom = source.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(allowCalls ? 1 : 0);
        dest.writeInt(allowMessages ? 1 : 0);
        if (sleepMode != null) {
            dest.writeInt(1);
            dest.writeString(sleepMode);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(sleepStartHour);
        dest.writeInt(sleepStartMinute);
        dest.writeInt(sleepEndHour);
        dest.writeInt(sleepEndMinute);
        if (conditionComponents != null && conditionComponents.length > 0) {
            dest.writeInt(conditionComponents.length);
            dest.writeTypedArray(conditionComponents, 0);
        } else {
            dest.writeInt(0);
        }
        if (conditionIds != null && conditionIds.length > 0) {
            dest.writeInt(conditionIds.length);
            dest.writeTypedArray(conditionIds, 0);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(allowFrom);
    }

    @Override
    public String toString() {
        return new StringBuilder(ZenModeConfig.class.getSimpleName()).append('[')
            .append("allowCalls=").append(allowCalls)
            .append(",allowMessages=").append(allowMessages)
            .append(",allowFrom=").append(sourceToString(allowFrom))
            .append(",sleepMode=").append(sleepMode)
            .append(",sleepStart=").append(sleepStartHour).append('.').append(sleepStartMinute)
            .append(",sleepEnd=").append(sleepEndHour).append('.').append(sleepEndMinute)
            .append(",conditionComponents=")
            .append(conditionComponents == null ? null : TextUtils.join(",", conditionComponents))
            .append(",conditionIds=")
            .append(conditionIds == null ? null : TextUtils.join(",", conditionIds))
            .append(']').toString();
    }

    public static String sourceToString(int source) {
        switch (source) {
            case SOURCE_ANYONE:
                return "anyone";
            case SOURCE_CONTACT:
                return "contacts";
            case SOURCE_STAR:
                return "stars";
            default:
                return "UNKNOWN";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ZenModeConfig)) return false;
        if (o == this) return true;
        final ZenModeConfig other = (ZenModeConfig) o;
        return other.allowCalls == allowCalls
                && other.allowMessages == allowMessages
                && other.allowFrom == allowFrom
                && Objects.equals(other.sleepMode, sleepMode)
                && other.sleepStartHour == sleepStartHour
                && other.sleepStartMinute == sleepStartMinute
                && other.sleepEndHour == sleepEndHour
                && other.sleepEndMinute == sleepEndMinute
                && Objects.deepEquals(other.conditionComponents, conditionComponents)
                && Objects.deepEquals(other.conditionIds, conditionIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowCalls, allowMessages, allowFrom, sleepMode,
                sleepStartHour, sleepStartMinute, sleepEndHour, sleepEndMinute,
                Arrays.hashCode(conditionComponents), Arrays.hashCode(conditionIds));
    }

    public boolean isValid() {
        return isValidHour(sleepStartHour) && isValidMinute(sleepStartMinute)
                && isValidHour(sleepEndHour) && isValidMinute(sleepEndMinute)
                && (sleepMode == null || sleepMode.equals(SLEEP_MODE_NIGHTS)
                    || sleepMode.equals(SLEEP_MODE_WEEKNIGHTS));
    }

    public static ZenModeConfig readXml(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return null;
        String tag = parser.getName();
        if (!ZEN_TAG.equals(tag)) return null;
        final ZenModeConfig rt = new ZenModeConfig();
        final int version = Integer.parseInt(parser.getAttributeValue(null, ZEN_ATT_VERSION));
        final ArrayList<ComponentName> conditionComponents = new ArrayList<ComponentName>();
        final ArrayList<Uri> conditionIds = new ArrayList<Uri>();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            tag = parser.getName();
            if (type == XmlPullParser.END_TAG && ZEN_TAG.equals(tag)) {
                if (!conditionComponents.isEmpty()) {
                    rt.conditionComponents = conditionComponents
                            .toArray(new ComponentName[conditionComponents.size()]);
                    rt.conditionIds = conditionIds.toArray(new Uri[conditionIds.size()]);
                }
                return rt;
            }
            if (type == XmlPullParser.START_TAG) {
                if (ALLOW_TAG.equals(tag)) {
                    rt.allowCalls = safeBoolean(parser, ALLOW_ATT_CALLS, false);
                    rt.allowMessages = safeBoolean(parser, ALLOW_ATT_MESSAGES, false);
                    rt.allowFrom = safeInt(parser, ALLOW_ATT_FROM, SOURCE_ANYONE);
                    if (rt.allowFrom < SOURCE_ANYONE || rt.allowFrom > MAX_SOURCE) {
                        throw new IndexOutOfBoundsException("bad source in config:" + rt.allowFrom);
                    }
                } else if (SLEEP_TAG.equals(tag)) {
                    final String mode = parser.getAttributeValue(null, SLEEP_ATT_MODE);
                    rt.sleepMode = (SLEEP_MODE_NIGHTS.equals(mode)
                            || SLEEP_MODE_WEEKNIGHTS.equals(mode)) ? mode : null;
                    final int startHour = safeInt(parser, SLEEP_ATT_START_HR, 0);
                    final int startMinute = safeInt(parser, SLEEP_ATT_START_MIN, 0);
                    final int endHour = safeInt(parser, SLEEP_ATT_END_HR, 0);
                    final int endMinute = safeInt(parser, SLEEP_ATT_END_MIN, 0);
                    rt.sleepStartHour = isValidHour(startHour) ? startHour : 0;
                    rt.sleepStartMinute = isValidMinute(startMinute) ? startMinute : 0;
                    rt.sleepEndHour = isValidHour(endHour) ? endHour : 0;
                    rt.sleepEndMinute = isValidMinute(endMinute) ? endMinute : 0;
                } else if (CONDITION_TAG.equals(tag)) {
                    final ComponentName component =
                            safeComponentName(parser, CONDITION_ATT_COMPONENT);
                    final Uri conditionId = safeUri(parser, CONDITION_ATT_ID);
                    if (component != null && conditionId != null) {
                        conditionComponents.add(component);
                        conditionIds.add(conditionId);
                    }
                }
            }
        }
        throw new IllegalStateException("Failed to reach END_DOCUMENT");
    }

    public void writeXml(XmlSerializer out) throws IOException {
        out.startTag(null, ZEN_TAG);
        out.attribute(null, ZEN_ATT_VERSION, Integer.toString(XML_VERSION));

        out.startTag(null, ALLOW_TAG);
        out.attribute(null, ALLOW_ATT_CALLS, Boolean.toString(allowCalls));
        out.attribute(null, ALLOW_ATT_MESSAGES, Boolean.toString(allowMessages));
        out.attribute(null, ALLOW_ATT_FROM, Integer.toString(allowFrom));
        out.endTag(null, ALLOW_TAG);

        out.startTag(null, SLEEP_TAG);
        if (sleepMode != null) {
            out.attribute(null, SLEEP_ATT_MODE, sleepMode);
        }
        out.attribute(null, SLEEP_ATT_START_HR, Integer.toString(sleepStartHour));
        out.attribute(null, SLEEP_ATT_START_MIN, Integer.toString(sleepStartMinute));
        out.attribute(null, SLEEP_ATT_END_HR, Integer.toString(sleepEndHour));
        out.attribute(null, SLEEP_ATT_END_MIN, Integer.toString(sleepEndMinute));
        out.endTag(null, SLEEP_TAG);

        if (conditionComponents != null && conditionIds != null
                && conditionComponents.length == conditionIds.length) {
            for (int i = 0; i < conditionComponents.length; i++) {
                out.startTag(null, CONDITION_TAG);
                out.attribute(null, CONDITION_ATT_COMPONENT,
                        conditionComponents[i].flattenToString());
                out.attribute(null, CONDITION_ATT_ID, conditionIds[i].toString());
                out.endTag(null, CONDITION_TAG);
            }
        }
        out.endTag(null, ZEN_TAG);
    }

    public static boolean isValidHour(int val) {
        return val >= 0 && val < 24;
    }

    public static boolean isValidMinute(int val) {
        return val >= 0 && val < 60;
    }

    private static boolean safeBoolean(XmlPullParser parser, String att, boolean defValue) {
        final String val = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(val)) return defValue;
        return Boolean.valueOf(val);
    }

    private static int safeInt(XmlPullParser parser, String att, int defValue) {
        final String val = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(val)) return defValue;
        return Integer.valueOf(val);
    }

    private static ComponentName safeComponentName(XmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(val)) return null;
        return ComponentName.unflattenFromString(val);
    }

    private static Uri safeUri(XmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(val)) return null;
        return Uri.parse(val);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public ZenModeConfig copy() {
        final Parcel parcel = Parcel.obtain();
        try {
            writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return new ZenModeConfig(parcel);
        } finally {
            parcel.recycle();
        }
    }

    public static final Parcelable.Creator<ZenModeConfig> CREATOR
            = new Parcelable.Creator<ZenModeConfig>() {
        @Override
        public ZenModeConfig createFromParcel(Parcel source) {
            return new ZenModeConfig(source);
        }

        @Override
        public ZenModeConfig[] newArray(int size) {
            return new ZenModeConfig[size];
        }
    };
}
