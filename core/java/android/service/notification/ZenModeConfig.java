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

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.Objects;

/**
 * Persisted configuration for zen mode.
 *
 * @hide
 */
public class ZenModeConfig implements Parcelable {

    public static final String SLEEP_MODE_NIGHTS = "nights";
    public static final String SLEEP_MODE_WEEKNIGHTS = "weeknights";

    private static final int XML_VERSION = 1;
    private static final String ZEN_TAG = "zen";
    private static final String ZEN_ATT_VERSION = "version";
    private static final String ALLOW_TAG = "allow";
    private static final String ALLOW_ATT_CALLS = "calls";
    private static final String ALLOW_ATT_MESSAGES = "messages";
    private static final String SLEEP_TAG = "sleep";
    private static final String SLEEP_ATT_MODE = "mode";

    private static final String SLEEP_ATT_START_HR = "startHour";
    private static final String SLEEP_ATT_START_MIN = "startMin";
    private static final String SLEEP_ATT_END_HR = "endHour";
    private static final String SLEEP_ATT_END_MIN = "endMin";

    public boolean allowCalls;
    public boolean allowMessages;

    public String sleepMode;
    public int sleepStartHour;
    public int sleepStartMinute;
    public int sleepEndHour;
    public int sleepEndMinute;

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
    }

    @Override
    public String toString() {
        return new StringBuilder(ZenModeConfig.class.getSimpleName()).append('[')
            .append("allowCalls=").append(allowCalls)
            .append(",allowMessages=").append(allowMessages)
            .append(",sleepMode=").append(sleepMode)
            .append(",sleepStart=").append(sleepStartHour).append('.').append(sleepStartMinute)
            .append(",sleepEnd=").append(sleepEndHour).append('.').append(sleepEndMinute)
            .append(']').toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ZenModeConfig)) return false;
        if (o == this) return true;
        final ZenModeConfig other = (ZenModeConfig) o;
        return other.allowCalls == allowCalls
                && other.allowMessages == allowMessages
                && Objects.equals(other.sleepMode, sleepMode)
                && other.sleepStartHour == sleepStartHour
                && other.sleepStartMinute == sleepStartMinute
                && other.sleepEndHour == sleepEndHour
                && other.sleepEndMinute == sleepEndMinute;
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowCalls, allowMessages, sleepMode, sleepStartHour,
                sleepStartMinute, sleepEndHour, sleepEndMinute);
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
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            tag = parser.getName();
            if (type == XmlPullParser.END_TAG && ZEN_TAG.equals(tag)) return rt;
            if (type == XmlPullParser.START_TAG) {
                if (ALLOW_TAG.equals(tag)) {
                    rt.allowCalls = safeBoolean(parser, ALLOW_ATT_CALLS, false);
                    rt.allowMessages = safeBoolean(parser, ALLOW_ATT_MESSAGES, false);
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
                }
            }
        }
        return rt;
    }

    public void writeXml(XmlSerializer out) throws IOException {
        out.startTag(null, ZEN_TAG);
        out.attribute(null, ZEN_ATT_VERSION, Integer.toString(XML_VERSION));

        out.startTag(null, ALLOW_TAG);
        out.attribute(null, ALLOW_ATT_CALLS, Boolean.toString(allowCalls));
        out.attribute(null, ALLOW_ATT_MESSAGES, Boolean.toString(allowMessages));
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
