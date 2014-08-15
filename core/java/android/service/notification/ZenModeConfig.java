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
import android.util.Slog;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Objects;

/**
 * Persisted configuration for zen mode.
 *
 * @hide
 */
public class ZenModeConfig implements Parcelable {
    private static String TAG = "ZenModeConfig";

    public static final String SLEEP_MODE_NIGHTS = "nights";
    public static final String SLEEP_MODE_WEEKNIGHTS = "weeknights";
    public static final String SLEEP_MODE_DAYS_PREFIX = "days:";

    public static final int SOURCE_ANYONE = 0;
    public static final int SOURCE_CONTACT = 1;
    public static final int SOURCE_STAR = 2;
    public static final int MAX_SOURCE = SOURCE_STAR;

    public static final int[] ALL_DAYS = { Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
            Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY };
    public static final int[] WEEKNIGHT_DAYS = { Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
            Calendar.WEDNESDAY, Calendar.THURSDAY };

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
    private static final String CONDITION_ATT_SUMMARY = "summary";
    private static final String CONDITION_ATT_LINE1 = "line1";
    private static final String CONDITION_ATT_LINE2 = "line2";
    private static final String CONDITION_ATT_ICON = "icon";
    private static final String CONDITION_ATT_STATE = "state";
    private static final String CONDITION_ATT_FLAGS = "flags";

    private static final String EXIT_CONDITION_TAG = "exitCondition";
    private static final String EXIT_CONDITION_ATT_COMPONENT = "component";

    public boolean allowCalls;
    public boolean allowMessages;
    public int allowFrom = SOURCE_ANYONE;

    public String sleepMode;
    public int sleepStartHour;   // 0-23
    public int sleepStartMinute; // 0-59
    public int sleepEndHour;
    public int sleepEndMinute;
    public ComponentName[] conditionComponents;
    public Uri[] conditionIds;
    public Condition exitCondition;
    public ComponentName exitConditionComponent;

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
        exitCondition = source.readParcelable(null);
        exitConditionComponent = source.readParcelable(null);
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
        dest.writeParcelable(exitCondition, 0);
        dest.writeParcelable(exitConditionComponent, 0);
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
            .append(",exitCondition=").append(exitCondition)
            .append(",exitConditionComponent=").append(exitConditionComponent)
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
                && Objects.deepEquals(other.conditionIds, conditionIds)
                && Objects.equals(other.exitCondition, exitCondition)
                && Objects.equals(other.exitConditionComponent, exitConditionComponent);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowCalls, allowMessages, allowFrom, sleepMode,
                sleepStartHour, sleepStartMinute, sleepEndHour, sleepEndMinute,
                Arrays.hashCode(conditionComponents), Arrays.hashCode(conditionIds),
                exitCondition, exitConditionComponent);
    }

    public boolean isValid() {
        return isValidHour(sleepStartHour) && isValidMinute(sleepStartMinute)
                && isValidHour(sleepEndHour) && isValidMinute(sleepEndMinute)
                && isValidSleepMode(sleepMode);
    }

    public static boolean isValidSleepMode(String sleepMode) {
        return sleepMode == null || sleepMode.equals(SLEEP_MODE_NIGHTS)
                || sleepMode.equals(SLEEP_MODE_WEEKNIGHTS) || tryParseDays(sleepMode) != null;
    }

    public static int[] tryParseDays(String sleepMode) {
        if (sleepMode == null) return null;
        sleepMode = sleepMode.trim();
        if (SLEEP_MODE_NIGHTS.equals(sleepMode)) return ALL_DAYS;
        if (SLEEP_MODE_WEEKNIGHTS.equals(sleepMode)) return WEEKNIGHT_DAYS;
        if (!sleepMode.startsWith(SLEEP_MODE_DAYS_PREFIX)) return null;
        if (sleepMode.equals(SLEEP_MODE_DAYS_PREFIX)) return null;
        final String[] tokens = sleepMode.substring(SLEEP_MODE_DAYS_PREFIX.length()).split(",");
        if (tokens.length == 0) return null;
        final int[] rt = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            final int day = tryParseInt(tokens[i], -1);
            if (day == -1) return null;
            rt[i] = day;
        }
        return rt;
    }

    private static int tryParseInt(String value, int defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    public static ZenModeConfig readXml(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return null;
        String tag = parser.getName();
        if (!ZEN_TAG.equals(tag)) return null;
        final ZenModeConfig rt = new ZenModeConfig();
        final int version = safeInt(parser, ZEN_ATT_VERSION, XML_VERSION);
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
                    rt.sleepMode = isValidSleepMode(mode)? mode : null;
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
                } else if (EXIT_CONDITION_TAG.equals(tag)) {
                    rt.exitCondition = readConditionXml(parser);
                    if (rt.exitCondition != null) {
                        rt.exitConditionComponent =
                                safeComponentName(parser, EXIT_CONDITION_ATT_COMPONENT);
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
        if (exitCondition != null && exitConditionComponent != null) {
            out.startTag(null, EXIT_CONDITION_TAG);
            out.attribute(null, EXIT_CONDITION_ATT_COMPONENT,
                    exitConditionComponent.flattenToString());
            writeConditionXml(exitCondition, out);
            out.endTag(null, EXIT_CONDITION_TAG);
        }
        out.endTag(null, ZEN_TAG);
    }

    public static Condition readConditionXml(XmlPullParser parser) {
        final Uri id = safeUri(parser, CONDITION_ATT_ID);
        final String summary = parser.getAttributeValue(null, CONDITION_ATT_SUMMARY);
        final String line1 = parser.getAttributeValue(null, CONDITION_ATT_LINE1);
        final String line2 = parser.getAttributeValue(null, CONDITION_ATT_LINE2);
        final int icon = safeInt(parser, CONDITION_ATT_ICON, -1);
        final int state = safeInt(parser, CONDITION_ATT_STATE, -1);
        final int flags = safeInt(parser, CONDITION_ATT_FLAGS, -1);
        try {
            return new Condition(id, summary, line1, line2, icon, state, flags);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Unable to read condition xml", e);
            return null;
        }
    }

    public static void writeConditionXml(Condition c, XmlSerializer out) throws IOException {
        out.attribute(null, CONDITION_ATT_ID, c.id.toString());
        out.attribute(null, CONDITION_ATT_SUMMARY, c.summary);
        out.attribute(null, CONDITION_ATT_LINE1, c.line1);
        out.attribute(null, CONDITION_ATT_LINE2, c.line2);
        out.attribute(null, CONDITION_ATT_ICON, Integer.toString(c.icon));
        out.attribute(null, CONDITION_ATT_STATE, Integer.toString(c.state));
        out.attribute(null, CONDITION_ATT_FLAGS, Integer.toString(c.flags));
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
        return tryParseInt(val, defValue);
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

    public DowntimeInfo toDowntimeInfo() {
        final DowntimeInfo downtime = new DowntimeInfo();
        downtime.startHour = sleepStartHour;
        downtime.startMinute = sleepStartMinute;
        downtime.endHour = sleepEndHour;
        downtime.endMinute = sleepEndMinute;
        return downtime;
    }

    // For built-in conditions
    private static final String SYSTEM_AUTHORITY = "android";

    // Built-in countdown conditions, e.g. condition://android/countdown/1399917958951
    private static final String COUNTDOWN_PATH = "countdown";

    public static Uri toCountdownConditionId(long time) {
        return new Uri.Builder().scheme(Condition.SCHEME)
                .authority(SYSTEM_AUTHORITY)
                .appendPath(COUNTDOWN_PATH)
                .appendPath(Long.toString(time))
                .build();
    }

    public static long tryParseCountdownConditionId(Uri conditionId) {
        if (!Condition.isValidId(conditionId, SYSTEM_AUTHORITY)) return 0;
        if (conditionId.getPathSegments().size() != 2
                || !COUNTDOWN_PATH.equals(conditionId.getPathSegments().get(0))) return 0;
        try {
            return Long.parseLong(conditionId.getPathSegments().get(1));
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error parsing countdown condition: " + conditionId, e);
            return 0;
        }
    }

    public static boolean isValidCountdownConditionId(Uri conditionId) {
        return tryParseCountdownConditionId(conditionId) != 0;
    }

    // Built-in downtime conditions, e.g. condition://android/downtime?start=10.00&end=7.00
    private static final String DOWNTIME_PATH = "downtime";

    public static Uri toDowntimeConditionId(DowntimeInfo downtime) {
        return new Uri.Builder().scheme(Condition.SCHEME)
                .authority(SYSTEM_AUTHORITY)
                .appendPath(DOWNTIME_PATH)
                .appendQueryParameter("start", downtime.startHour + "." + downtime.startMinute)
                .appendQueryParameter("end", downtime.endHour + "." + downtime.endMinute)
                .build();
    }

    public static DowntimeInfo tryParseDowntimeConditionId(Uri conditionId) {
        if (!Condition.isValidId(conditionId, SYSTEM_AUTHORITY)
                || conditionId.getPathSegments().size() != 1
                || !DOWNTIME_PATH.equals(conditionId.getPathSegments().get(0))) {
            return null;
        }
        final int[] start = tryParseHourAndMinute(conditionId.getQueryParameter("start"));
        final int[] end = tryParseHourAndMinute(conditionId.getQueryParameter("end"));
        if (start == null || end == null) return null;
        final DowntimeInfo downtime = new DowntimeInfo();
        downtime.startHour = start[0];
        downtime.startMinute = start[1];
        downtime.endHour = end[0];
        downtime.endMinute = end[1];
        return downtime;
    }

    private static int[] tryParseHourAndMinute(String value) {
        if (TextUtils.isEmpty(value)) return null;
        final int i = value.indexOf('.');
        if (i < 1 || i >= value.length() - 1) return null;
        final int hour = tryParseInt(value.substring(0, i), -1);
        final int minute = tryParseInt(value.substring(i + 1), -1);
        return isValidHour(hour) && isValidMinute(minute) ? new int[] { hour, minute } : null;
    }

    public static boolean isValidDowntimeConditionId(Uri conditionId) {
        return tryParseDowntimeConditionId(conditionId) != null;
    }

    public static class DowntimeInfo {
        public int startHour;   // 0-23
        public int startMinute; // 0-59
        public int endHour;
        public int endMinute;

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof DowntimeInfo)) return false;
            final DowntimeInfo other = (DowntimeInfo) o;
            return startHour == other.startHour
                    && startMinute == other.startMinute
                    && endHour == other.endHour
                    && endMinute == other.endMinute;
        }
    }
}
