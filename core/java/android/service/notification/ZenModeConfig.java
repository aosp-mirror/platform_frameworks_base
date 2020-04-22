/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License,  2.0 (the "License");
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

import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_IMPORTANT;
import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_NONE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Persisted configuration for zen mode.
 *
 * @hide
 */
public class ZenModeConfig implements Parcelable {
    private static String TAG = "ZenModeConfig";

    public static final int SOURCE_ANYONE = Policy.PRIORITY_SENDERS_ANY;
    public static final int SOURCE_CONTACT = Policy.PRIORITY_SENDERS_CONTACTS;
    public static final int SOURCE_STAR = Policy.PRIORITY_SENDERS_STARRED;
    public static final int MAX_SOURCE = SOURCE_STAR;
    private static final int DEFAULT_SOURCE = SOURCE_CONTACT;
    private static final int DEFAULT_CALLS_SOURCE = SOURCE_STAR;

    public static final String EVENTS_DEFAULT_RULE_ID = "EVENTS_DEFAULT_RULE";
    public static final String EVERY_NIGHT_DEFAULT_RULE_ID = "EVERY_NIGHT_DEFAULT_RULE";
    public static final List<String> DEFAULT_RULE_IDS = Arrays.asList(EVERY_NIGHT_DEFAULT_RULE_ID,
            EVENTS_DEFAULT_RULE_ID);

    public static final int[] ALL_DAYS = { Calendar.SUNDAY, Calendar.MONDAY, Calendar.TUESDAY,
            Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY };

    public static final int[] MINUTE_BUCKETS = generateMinuteBuckets();
    private static final int SECONDS_MS = 1000;
    private static final int MINUTES_MS = 60 * SECONDS_MS;
    private static final int DAY_MINUTES = 24 * 60;
    private static final int ZERO_VALUE_MS = 10 * SECONDS_MS;

    // Default allow categories set in readXml() from default_zen_mode_config.xml,
    // fallback/upgrade values:
    private static final boolean DEFAULT_ALLOW_ALARMS = true;
    private static final boolean DEFAULT_ALLOW_MEDIA = true;
    private static final boolean DEFAULT_ALLOW_SYSTEM = false;
    private static final boolean DEFAULT_ALLOW_CALLS = true;
    private static final boolean DEFAULT_ALLOW_MESSAGES = false;
    private static final boolean DEFAULT_ALLOW_REMINDERS = false;
    private static final boolean DEFAULT_ALLOW_EVENTS = false;
    private static final boolean DEFAULT_ALLOW_REPEAT_CALLERS = true;
    private static final boolean DEFAULT_ALLOW_CONV = false;
    private static final int DEFAULT_ALLOW_CONV_FROM = ZenPolicy.CONVERSATION_SENDERS_NONE;
    private static final boolean DEFAULT_CHANNELS_BYPASSING_DND = false;
    private static final int DEFAULT_SUPPRESSED_VISUAL_EFFECTS = 0;

    public static final int XML_VERSION = 8;
    public static final String ZEN_TAG = "zen";
    private static final String ZEN_ATT_VERSION = "version";
    private static final String ZEN_ATT_USER = "user";
    private static final String ALLOW_TAG = "allow";
    private static final String ALLOW_ATT_ALARMS = "alarms";
    private static final String ALLOW_ATT_MEDIA = "media";
    private static final String ALLOW_ATT_SYSTEM = "system";
    private static final String ALLOW_ATT_CALLS = "calls";
    private static final String ALLOW_ATT_REPEAT_CALLERS = "repeatCallers";
    private static final String ALLOW_ATT_MESSAGES = "messages";
    private static final String ALLOW_ATT_FROM = "from";
    private static final String ALLOW_ATT_CALLS_FROM = "callsFrom";
    private static final String ALLOW_ATT_MESSAGES_FROM = "messagesFrom";
    private static final String ALLOW_ATT_REMINDERS = "reminders";
    private static final String ALLOW_ATT_EVENTS = "events";
    private static final String ALLOW_ATT_SCREEN_OFF = "visualScreenOff";
    private static final String ALLOW_ATT_SCREEN_ON = "visualScreenOn";
    private static final String ALLOW_ATT_CONV = "convos";
    private static final String ALLOW_ATT_CONV_FROM = "convosFrom";
    private static final String DISALLOW_TAG = "disallow";
    private static final String DISALLOW_ATT_VISUAL_EFFECTS = "visualEffects";
    private static final String STATE_TAG = "state";
    private static final String STATE_ATT_CHANNELS_BYPASSING_DND = "areChannelsBypassingDnd";

    // zen policy visual effects attributes
    private static final String SHOW_ATT_FULL_SCREEN_INTENT = "showFullScreenIntent";
    private static final String SHOW_ATT_LIGHTS = "showLights";
    private static final String SHOW_ATT_PEEK = "shoePeek";
    private static final String SHOW_ATT_STATUS_BAR_ICONS = "showStatusBarIcons";
    private static final String SHOW_ATT_BADGES = "showBadges";
    private static final String SHOW_ATT_AMBIENT = "showAmbient";
    private static final String SHOW_ATT_NOTIFICATION_LIST = "showNotificationList";

    private static final String CONDITION_ATT_ID = "id";
    private static final String CONDITION_ATT_SUMMARY = "summary";
    private static final String CONDITION_ATT_LINE1 = "line1";
    private static final String CONDITION_ATT_LINE2 = "line2";
    private static final String CONDITION_ATT_ICON = "icon";
    private static final String CONDITION_ATT_STATE = "state";
    private static final String CONDITION_ATT_FLAGS = "flags";

    private static final String ZEN_POLICY_TAG = "zen_policy";

    private static final String MANUAL_TAG = "manual";
    private static final String AUTOMATIC_TAG = "automatic";

    private static final String RULE_ATT_ID = "ruleId";
    private static final String RULE_ATT_ENABLED = "enabled";
    private static final String RULE_ATT_SNOOZING = "snoozing";
    private static final String RULE_ATT_NAME = "name";
    private static final String RULE_ATT_COMPONENT = "component";
    private static final String RULE_ATT_CONFIG_ACTIVITY = "configActivity";
    private static final String RULE_ATT_ZEN = "zen";
    private static final String RULE_ATT_CONDITION_ID = "conditionId";
    private static final String RULE_ATT_CREATION_TIME = "creationTime";
    private static final String RULE_ATT_ENABLER = "enabler";
    private static final String RULE_ATT_MODIFIED = "modified";

    @UnsupportedAppUsage
    public boolean allowAlarms = DEFAULT_ALLOW_ALARMS;
    public boolean allowMedia = DEFAULT_ALLOW_MEDIA;
    public boolean allowSystem = DEFAULT_ALLOW_SYSTEM;
    public boolean allowCalls = DEFAULT_ALLOW_CALLS;
    public boolean allowRepeatCallers = DEFAULT_ALLOW_REPEAT_CALLERS;
    public boolean allowMessages = DEFAULT_ALLOW_MESSAGES;
    public boolean allowReminders = DEFAULT_ALLOW_REMINDERS;
    public boolean allowEvents = DEFAULT_ALLOW_EVENTS;
    public int allowCallsFrom = DEFAULT_CALLS_SOURCE;
    public int allowMessagesFrom = DEFAULT_SOURCE;
    public boolean allowConversations = DEFAULT_ALLOW_CONV;
    public int allowConversationsFrom = DEFAULT_ALLOW_CONV_FROM;
    public int user = UserHandle.USER_SYSTEM;
    public int suppressedVisualEffects = DEFAULT_SUPPRESSED_VISUAL_EFFECTS;
    public boolean areChannelsBypassingDnd = DEFAULT_CHANNELS_BYPASSING_DND;
    public int version;

    public ZenRule manualRule;
    @UnsupportedAppUsage
    public ArrayMap<String, ZenRule> automaticRules = new ArrayMap<>();

    @UnsupportedAppUsage
    public ZenModeConfig() { }

    public ZenModeConfig(Parcel source) {
        allowCalls = source.readInt() == 1;
        allowRepeatCallers = source.readInt() == 1;
        allowMessages = source.readInt() == 1;
        allowReminders = source.readInt() == 1;
        allowEvents = source.readInt() == 1;
        allowCallsFrom = source.readInt();
        allowMessagesFrom = source.readInt();
        user = source.readInt();
        manualRule = source.readParcelable(null);
        final int len = source.readInt();
        if (len > 0) {
            final String[] ids = new String[len];
            final ZenRule[] rules = new ZenRule[len];
            source.readStringArray(ids);
            source.readTypedArray(rules, ZenRule.CREATOR);
            for (int i = 0; i < len; i++) {
                automaticRules.put(ids[i], rules[i]);
            }
        }
        allowAlarms = source.readInt() == 1;
        allowMedia = source.readInt() == 1;
        allowSystem = source.readInt() == 1;
        suppressedVisualEffects = source.readInt();
        areChannelsBypassingDnd = source.readInt() == 1;
        allowConversations = source.readBoolean();
        allowConversationsFrom = source.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(allowCalls ? 1 : 0);
        dest.writeInt(allowRepeatCallers ? 1 : 0);
        dest.writeInt(allowMessages ? 1 : 0);
        dest.writeInt(allowReminders ? 1 : 0);
        dest.writeInt(allowEvents ? 1 : 0);
        dest.writeInt(allowCallsFrom);
        dest.writeInt(allowMessagesFrom);
        dest.writeInt(user);
        dest.writeParcelable(manualRule, 0);
        if (!automaticRules.isEmpty()) {
            final int len = automaticRules.size();
            final String[] ids = new String[len];
            final ZenRule[] rules = new ZenRule[len];
            for (int i = 0; i < len; i++) {
                ids[i] = automaticRules.keyAt(i);
                rules[i] = automaticRules.valueAt(i);
            }
            dest.writeInt(len);
            dest.writeStringArray(ids);
            dest.writeTypedArray(rules, 0);
        } else {
            dest.writeInt(0);
        }
        dest.writeInt(allowAlarms ? 1 : 0);
        dest.writeInt(allowMedia ? 1 : 0);
        dest.writeInt(allowSystem ? 1 : 0);
        dest.writeInt(suppressedVisualEffects);
        dest.writeInt(areChannelsBypassingDnd ? 1 : 0);
        dest.writeBoolean(allowConversations);
        dest.writeInt(allowConversationsFrom);
    }

    @Override
    public String toString() {
        return new StringBuilder(ZenModeConfig.class.getSimpleName()).append('[')
                .append("user=").append(user)
                .append(",allowAlarms=").append(allowAlarms)
                .append(",allowMedia=").append(allowMedia)
                .append(",allowSystem=").append(allowSystem)
                .append(",allowReminders=").append(allowReminders)
                .append(",allowEvents=").append(allowEvents)
                .append(",allowCalls=").append(allowCalls)
                .append(",allowRepeatCallers=").append(allowRepeatCallers)
                .append(",allowMessages=").append(allowMessages)
                .append(",allowConversations=").append(allowConversations)
                .append(",allowCallsFrom=").append(sourceToString(allowCallsFrom))
                .append(",allowMessagesFrom=").append(sourceToString(allowMessagesFrom))
                .append(",allowConvFrom=").append(ZenPolicy.conversationTypeToString
                        (allowConversationsFrom))
                .append(",suppressedVisualEffects=").append(suppressedVisualEffects)
                .append(",areChannelsBypassingDnd=").append(areChannelsBypassingDnd)
                .append(",\nautomaticRules=").append(rulesToString())
                .append(",\nmanualRule=").append(manualRule)
                .append(']').toString();
    }

    private String rulesToString() {
        if (automaticRules.isEmpty()) {
            return "{}";
        }

        StringBuilder buffer = new StringBuilder(automaticRules.size() * 28);
        buffer.append('{');
        for (int i = 0; i < automaticRules.size(); i++) {
            if (i > 0) {
                buffer.append(",\n");
            }
            Object value = automaticRules.valueAt(i);
            buffer.append(value);
        }
        buffer.append('}');
        return buffer.toString();
    }

    public Diff diff(ZenModeConfig to) {
        final Diff d = new Diff();
        if (to == null) {
            return d.addLine("config", "delete");
        }
        if (user != to.user) {
            d.addLine("user", user, to.user);
        }
        if (allowAlarms != to.allowAlarms) {
            d.addLine("allowAlarms", allowAlarms, to.allowAlarms);
        }
        if (allowMedia != to.allowMedia) {
            d.addLine("allowMedia", allowMedia, to.allowMedia);
        }
        if (allowSystem != to.allowSystem) {
            d.addLine("allowSystem", allowSystem, to.allowSystem);
        }
        if (allowCalls != to.allowCalls) {
            d.addLine("allowCalls", allowCalls, to.allowCalls);
        }
        if (allowReminders != to.allowReminders) {
            d.addLine("allowReminders", allowReminders, to.allowReminders);
        }
        if (allowEvents != to.allowEvents) {
            d.addLine("allowEvents", allowEvents, to.allowEvents);
        }
        if (allowRepeatCallers != to.allowRepeatCallers) {
            d.addLine("allowRepeatCallers", allowRepeatCallers, to.allowRepeatCallers);
        }
        if (allowMessages != to.allowMessages) {
            d.addLine("allowMessages", allowMessages, to.allowMessages);
        }
        if (allowCallsFrom != to.allowCallsFrom) {
            d.addLine("allowCallsFrom", allowCallsFrom, to.allowCallsFrom);
        }
        if (allowMessagesFrom != to.allowMessagesFrom) {
            d.addLine("allowMessagesFrom", allowMessagesFrom, to.allowMessagesFrom);
        }
        if (suppressedVisualEffects != to.suppressedVisualEffects) {
            d.addLine("suppressedVisualEffects", suppressedVisualEffects,
                    to.suppressedVisualEffects);
        }
        final ArraySet<String> allRules = new ArraySet<>();
        addKeys(allRules, automaticRules);
        addKeys(allRules, to.automaticRules);
        final int N = allRules.size();
        for (int i = 0; i < N; i++) {
            final String rule = allRules.valueAt(i);
            final ZenRule fromRule = automaticRules != null ? automaticRules.get(rule) : null;
            final ZenRule toRule = to.automaticRules != null ? to.automaticRules.get(rule) : null;
            ZenRule.appendDiff(d, "automaticRule[" + rule + "]", fromRule, toRule);
        }
        ZenRule.appendDiff(d, "manualRule", manualRule, to.manualRule);

        if (areChannelsBypassingDnd != to.areChannelsBypassingDnd) {
            d.addLine("areChannelsBypassingDnd", areChannelsBypassingDnd,
                    to.areChannelsBypassingDnd);
        }
        return d;
    }

    public static Diff diff(ZenModeConfig from, ZenModeConfig to) {
        if (from == null) {
            final Diff d = new Diff();
            if (to != null) {
                d.addLine("config", "insert");
            }
            return d;
        }
        return from.diff(to);
    }

    private static <T> void addKeys(ArraySet<T> set, ArrayMap<T, ?> map) {
        if (map != null) {
            for (int i = 0; i < map.size(); i++) {
                set.add(map.keyAt(i));
            }
        }
    }

    public boolean isValid() {
        if (!isValidManualRule(manualRule)) return false;
        final int N = automaticRules.size();
        for (int i = 0; i < N; i++) {
            if (!isValidAutomaticRule(automaticRules.valueAt(i))) return false;
        }
        return true;
    }

    private static boolean isValidManualRule(ZenRule rule) {
        return rule == null || Global.isValidZenMode(rule.zenMode) && sameCondition(rule);
    }

    private static boolean isValidAutomaticRule(ZenRule rule) {
        return rule != null && !TextUtils.isEmpty(rule.name) && Global.isValidZenMode(rule.zenMode)
                && rule.conditionId != null && sameCondition(rule);
    }

    private static boolean sameCondition(ZenRule rule) {
        if (rule == null) return false;
        if (rule.conditionId == null) {
            return rule.condition == null;
        } else {
            return rule.condition == null || rule.conditionId.equals(rule.condition.id);
        }
    }

    private static int[] generateMinuteBuckets() {
        final int maxHrs = 12;
        final int[] buckets = new int[maxHrs + 3];
        buckets[0] = 15;
        buckets[1] = 30;
        buckets[2] = 45;
        for (int i = 1; i <= maxHrs; i++) {
            buckets[2 + i] = 60 * i;
        }
        return buckets;
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
        return other.allowAlarms == allowAlarms
                && other.allowMedia == allowMedia
                && other.allowSystem == allowSystem
                && other.allowCalls == allowCalls
                && other.allowRepeatCallers == allowRepeatCallers
                && other.allowMessages == allowMessages
                && other.allowCallsFrom == allowCallsFrom
                && other.allowMessagesFrom == allowMessagesFrom
                && other.allowReminders == allowReminders
                && other.allowEvents == allowEvents
                && other.user == user
                && Objects.equals(other.automaticRules, automaticRules)
                && Objects.equals(other.manualRule, manualRule)
                && other.suppressedVisualEffects == suppressedVisualEffects
                && other.areChannelsBypassingDnd == areChannelsBypassingDnd
                && other.allowConversations == allowConversations
                && other.allowConversationsFrom == allowConversationsFrom;
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowAlarms, allowMedia, allowSystem, allowCalls,
                allowRepeatCallers, allowMessages,
                allowCallsFrom, allowMessagesFrom, allowReminders, allowEvents,
                user, automaticRules, manualRule,
                suppressedVisualEffects, areChannelsBypassingDnd, allowConversations,
                allowConversationsFrom);
    }

    private static String toDayList(int[] days) {
        if (days == null || days.length == 0) return "";
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < days.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(days[i]);
        }
        return sb.toString();
    }

    private static int[] tryParseDayList(String dayList, String sep) {
        if (dayList == null) return null;
        final String[] tokens = dayList.split(sep);
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
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    private static long tryParseLong(String value, long defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defValue;
        }
    }

    private static Long tryParseLong(String value, Long defValue) {
        if (TextUtils.isEmpty(value)) return defValue;
        try {
            return Long.parseLong(value);
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
        rt.version = safeInt(parser, ZEN_ATT_VERSION, XML_VERSION);
        rt.user = safeInt(parser, ZEN_ATT_USER, rt.user);
        boolean readSuppressedEffects = false;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            tag = parser.getName();
            if (type == XmlPullParser.END_TAG && ZEN_TAG.equals(tag)) {
                return rt;
            }
            if (type == XmlPullParser.START_TAG) {
                if (ALLOW_TAG.equals(tag)) {
                    rt.allowCalls = safeBoolean(parser, ALLOW_ATT_CALLS,
                            DEFAULT_ALLOW_CALLS);
                    rt.allowRepeatCallers = safeBoolean(parser, ALLOW_ATT_REPEAT_CALLERS,
                            DEFAULT_ALLOW_REPEAT_CALLERS);
                    rt.allowMessages = safeBoolean(parser, ALLOW_ATT_MESSAGES,
                            DEFAULT_ALLOW_MESSAGES);
                    rt.allowReminders = safeBoolean(parser, ALLOW_ATT_REMINDERS,
                            DEFAULT_ALLOW_REMINDERS);
                    rt.allowConversations = safeBoolean(parser, ALLOW_ATT_CONV, DEFAULT_ALLOW_CONV);
                    rt.allowEvents = safeBoolean(parser, ALLOW_ATT_EVENTS, DEFAULT_ALLOW_EVENTS);
                    final int from = safeInt(parser, ALLOW_ATT_FROM, -1);
                    final int callsFrom = safeInt(parser, ALLOW_ATT_CALLS_FROM, -1);
                    final int messagesFrom = safeInt(parser, ALLOW_ATT_MESSAGES_FROM, -1);
                    rt.allowConversationsFrom = safeInt(parser, ALLOW_ATT_CONV_FROM,
                            DEFAULT_ALLOW_CONV_FROM);
                    if (isValidSource(callsFrom) && isValidSource(messagesFrom)) {
                        rt.allowCallsFrom = callsFrom;
                        rt.allowMessagesFrom = messagesFrom;
                    } else if (isValidSource(from)) {
                        Slog.i(TAG, "Migrating existing shared 'from': " + sourceToString(from));
                        rt.allowCallsFrom = from;
                        rt.allowMessagesFrom = from;
                    } else {
                        rt.allowCallsFrom = DEFAULT_CALLS_SOURCE;
                        rt.allowMessagesFrom = DEFAULT_SOURCE;
                    }
                    rt.allowAlarms = safeBoolean(parser, ALLOW_ATT_ALARMS, DEFAULT_ALLOW_ALARMS);
                    rt.allowMedia = safeBoolean(parser, ALLOW_ATT_MEDIA,
                            DEFAULT_ALLOW_MEDIA);
                    rt.allowSystem = safeBoolean(parser, ALLOW_ATT_SYSTEM, DEFAULT_ALLOW_SYSTEM);

                    // migrate old suppressed visual effects fields, if they still exist in the xml
                    Boolean allowWhenScreenOff = unsafeBoolean(parser, ALLOW_ATT_SCREEN_OFF);
                    if (allowWhenScreenOff != null) {
                        readSuppressedEffects = true;
                        if (!allowWhenScreenOff) {
                            rt.suppressedVisualEffects |= SUPPRESSED_EFFECT_LIGHTS
                                    | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
                        }
                    }
                    Boolean allowWhenScreenOn = unsafeBoolean(parser, ALLOW_ATT_SCREEN_ON);
                    if (allowWhenScreenOn != null) {
                        readSuppressedEffects = true;
                        if (!allowWhenScreenOn) {
                            rt.suppressedVisualEffects |= SUPPRESSED_EFFECT_PEEK;
                        }
                    }
                    if (readSuppressedEffects) {
                        Slog.d(TAG, "Migrated visual effects to " + rt.suppressedVisualEffects);
                    }
                } else if (DISALLOW_TAG.equals(tag) && !readSuppressedEffects) {
                    // only read from suppressed visual effects field if we haven't just migrated
                    // the values from allowOn/allowOff, lest we wipe out those settings
                    rt.suppressedVisualEffects = safeInt(parser, DISALLOW_ATT_VISUAL_EFFECTS,
                            DEFAULT_SUPPRESSED_VISUAL_EFFECTS);
                } else if (MANUAL_TAG.equals(tag)) {
                    rt.manualRule = readRuleXml(parser);
                } else if (AUTOMATIC_TAG.equals(tag)) {
                    final String id = parser.getAttributeValue(null, RULE_ATT_ID);
                    final ZenRule automaticRule = readRuleXml(parser);
                    if (id != null && automaticRule != null) {
                        automaticRule.id = id;
                        rt.automaticRules.put(id, automaticRule);
                    }
                } else if (STATE_TAG.equals(tag)) {
                    rt.areChannelsBypassingDnd = safeBoolean(parser,
                            STATE_ATT_CHANNELS_BYPASSING_DND, DEFAULT_CHANNELS_BYPASSING_DND);
                }
            }
        }
        throw new IllegalStateException("Failed to reach END_DOCUMENT");
    }

    /**
     * Writes XML of current ZenModeConfig
     * @param out serializer
     * @param version uses XML_VERSION if version is null
     * @throws IOException
     */
    public void writeXml(XmlSerializer out, Integer version) throws IOException {
        out.startTag(null, ZEN_TAG);
        out.attribute(null, ZEN_ATT_VERSION, version == null
                ? Integer.toString(XML_VERSION) : Integer.toString(version));
        out.attribute(null, ZEN_ATT_USER, Integer.toString(user));
        out.startTag(null, ALLOW_TAG);
        out.attribute(null, ALLOW_ATT_CALLS, Boolean.toString(allowCalls));
        out.attribute(null, ALLOW_ATT_REPEAT_CALLERS, Boolean.toString(allowRepeatCallers));
        out.attribute(null, ALLOW_ATT_MESSAGES, Boolean.toString(allowMessages));
        out.attribute(null, ALLOW_ATT_REMINDERS, Boolean.toString(allowReminders));
        out.attribute(null, ALLOW_ATT_EVENTS, Boolean.toString(allowEvents));
        out.attribute(null, ALLOW_ATT_CALLS_FROM, Integer.toString(allowCallsFrom));
        out.attribute(null, ALLOW_ATT_MESSAGES_FROM, Integer.toString(allowMessagesFrom));
        out.attribute(null, ALLOW_ATT_ALARMS, Boolean.toString(allowAlarms));
        out.attribute(null, ALLOW_ATT_MEDIA, Boolean.toString(allowMedia));
        out.attribute(null, ALLOW_ATT_SYSTEM, Boolean.toString(allowSystem));
        out.attribute(null, ALLOW_ATT_CONV, Boolean.toString(allowConversations));
        out.attribute(null, ALLOW_ATT_CONV_FROM, Integer.toString(allowConversationsFrom));
        out.endTag(null, ALLOW_TAG);

        out.startTag(null, DISALLOW_TAG);
        out.attribute(null, DISALLOW_ATT_VISUAL_EFFECTS, Integer.toString(suppressedVisualEffects));
        out.endTag(null, DISALLOW_TAG);

        if (manualRule != null) {
            out.startTag(null, MANUAL_TAG);
            writeRuleXml(manualRule, out);
            out.endTag(null, MANUAL_TAG);
        }
        final int N = automaticRules.size();
        for (int i = 0; i < N; i++) {
            final String id = automaticRules.keyAt(i);
            final ZenRule automaticRule = automaticRules.valueAt(i);
            out.startTag(null, AUTOMATIC_TAG);
            out.attribute(null, RULE_ATT_ID, id);
            writeRuleXml(automaticRule, out);
            out.endTag(null, AUTOMATIC_TAG);
        }

        out.startTag(null, STATE_TAG);
        out.attribute(null, STATE_ATT_CHANNELS_BYPASSING_DND,
                Boolean.toString(areChannelsBypassingDnd));
        out.endTag(null, STATE_TAG);

        out.endTag(null, ZEN_TAG);
    }

    public static ZenRule readRuleXml(XmlPullParser parser) {
        final ZenRule rt = new ZenRule();
        rt.enabled = safeBoolean(parser, RULE_ATT_ENABLED, true);
        rt.name = parser.getAttributeValue(null, RULE_ATT_NAME);
        final String zen = parser.getAttributeValue(null, RULE_ATT_ZEN);
        rt.zenMode = tryParseZenMode(zen, -1);
        if (rt.zenMode == -1) {
            Slog.w(TAG, "Bad zen mode in rule xml:" + zen);
            return null;
        }
        rt.conditionId = safeUri(parser, RULE_ATT_CONDITION_ID);
        rt.component = safeComponentName(parser, RULE_ATT_COMPONENT);
        rt.configurationActivity = safeComponentName(parser, RULE_ATT_CONFIG_ACTIVITY);
        rt.pkg = (rt.component != null)
                ? rt.component.getPackageName()
                : (rt.configurationActivity != null)
                        ? rt.configurationActivity.getPackageName()
                        : null;
        rt.creationTime = safeLong(parser, RULE_ATT_CREATION_TIME, 0);
        rt.enabler = parser.getAttributeValue(null, RULE_ATT_ENABLER);
        rt.condition = readConditionXml(parser);

        // all default rules and user created rules updated to zenMode important interruptions
        if (rt.zenMode != Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                && Condition.isValidId(rt.conditionId, SYSTEM_AUTHORITY)) {
            Slog.i(TAG, "Updating zenMode of automatic rule " + rt.name);
            rt.zenMode = Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        }
        rt.modified = safeBoolean(parser, RULE_ATT_MODIFIED, false);
        rt.zenPolicy = readZenPolicyXml(parser);
        return rt;
    }

    public static void writeRuleXml(ZenRule rule, XmlSerializer out) throws IOException {
        out.attribute(null, RULE_ATT_ENABLED, Boolean.toString(rule.enabled));
        if (rule.name != null) {
            out.attribute(null, RULE_ATT_NAME, rule.name);
        }
        out.attribute(null, RULE_ATT_ZEN, Integer.toString(rule.zenMode));
        if (rule.component != null) {
            out.attribute(null, RULE_ATT_COMPONENT, rule.component.flattenToString());
        }
        if (rule.configurationActivity != null) {
            out.attribute(null, RULE_ATT_CONFIG_ACTIVITY,
                    rule.configurationActivity.flattenToString());
        }
        if (rule.conditionId != null) {
            out.attribute(null, RULE_ATT_CONDITION_ID, rule.conditionId.toString());
        }
        out.attribute(null, RULE_ATT_CREATION_TIME, Long.toString(rule.creationTime));
        if (rule.enabler != null) {
            out.attribute(null, RULE_ATT_ENABLER, rule.enabler);
        }
        if (rule.condition != null) {
            writeConditionXml(rule.condition, out);
        }
        if (rule.zenPolicy != null) {
            writeZenPolicyXml(rule.zenPolicy, out);
        }
        out.attribute(null, RULE_ATT_MODIFIED, Boolean.toString(rule.modified));
    }

    public static Condition readConditionXml(XmlPullParser parser) {
        final Uri id = safeUri(parser, CONDITION_ATT_ID);
        if (id == null) return null;
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

    /**
     * Read the zen policy from xml
     * Returns null if no zen policy exists
     */
    public static ZenPolicy readZenPolicyXml(XmlPullParser parser) {
        boolean policySet = false;

        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        final int calls = safeInt(parser, ALLOW_ATT_CALLS_FROM, ZenPolicy.PEOPLE_TYPE_UNSET);
        final int messages = safeInt(parser, ALLOW_ATT_MESSAGES_FROM, ZenPolicy.PEOPLE_TYPE_UNSET);
        final int repeatCallers = safeInt(parser, ALLOW_ATT_REPEAT_CALLERS, ZenPolicy.STATE_UNSET);
        final int alarms = safeInt(parser, ALLOW_ATT_ALARMS, ZenPolicy.STATE_UNSET);
        final int media = safeInt(parser, ALLOW_ATT_MEDIA, ZenPolicy.STATE_UNSET);
        final int system = safeInt(parser, ALLOW_ATT_SYSTEM, ZenPolicy.STATE_UNSET);
        final int events = safeInt(parser, ALLOW_ATT_EVENTS, ZenPolicy.STATE_UNSET);
        final int reminders = safeInt(parser, ALLOW_ATT_REMINDERS, ZenPolicy.STATE_UNSET);

        if (calls != ZenPolicy.PEOPLE_TYPE_UNSET) {
            builder.allowCalls(calls);
            policySet = true;
        }
        if (messages != ZenPolicy.PEOPLE_TYPE_UNSET) {
            builder.allowMessages(messages);
            policySet = true;
        }
        if (repeatCallers != ZenPolicy.STATE_UNSET) {
            builder.allowRepeatCallers(repeatCallers == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (alarms != ZenPolicy.STATE_UNSET) {
            builder.allowAlarms(alarms == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (media != ZenPolicy.STATE_UNSET) {
            builder.allowMedia(media == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (system != ZenPolicy.STATE_UNSET) {
            builder.allowSystem(system == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (events != ZenPolicy.STATE_UNSET) {
            builder.allowEvents(events == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (reminders != ZenPolicy.STATE_UNSET) {
            builder.allowReminders(reminders == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }

        final int fullScreenIntent = safeInt(parser, SHOW_ATT_FULL_SCREEN_INTENT,
                ZenPolicy.STATE_UNSET);
        final int lights = safeInt(parser, SHOW_ATT_LIGHTS, ZenPolicy.STATE_UNSET);
        final int peek = safeInt(parser, SHOW_ATT_PEEK, ZenPolicy.STATE_UNSET);
        final int statusBar = safeInt(parser, SHOW_ATT_STATUS_BAR_ICONS, ZenPolicy.STATE_UNSET);
        final int badges = safeInt(parser, SHOW_ATT_BADGES, ZenPolicy.STATE_UNSET);
        final int ambient = safeInt(parser, SHOW_ATT_AMBIENT, ZenPolicy.STATE_UNSET);
        final int notificationList = safeInt(parser, SHOW_ATT_NOTIFICATION_LIST,
                ZenPolicy.STATE_UNSET);

        if (fullScreenIntent != ZenPolicy.STATE_UNSET) {
            builder.showFullScreenIntent(fullScreenIntent == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (lights != ZenPolicy.STATE_UNSET) {
            builder.showLights(lights == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (peek != ZenPolicy.STATE_UNSET) {
            builder.showPeeking(peek == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (statusBar != ZenPolicy.STATE_UNSET) {
            builder.showStatusBarIcons(statusBar == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (badges != ZenPolicy.STATE_UNSET) {
            builder.showBadges(badges == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (ambient != ZenPolicy.STATE_UNSET) {
            builder.showInAmbientDisplay(ambient == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }
        if (notificationList != ZenPolicy.STATE_UNSET) {
            builder.showInNotificationList(notificationList == ZenPolicy.STATE_ALLOW);
            policySet = true;
        }

        if (policySet) {
            return builder.build();
        }
        return null;
    }

    /**
     * Writes zen policy to xml
     */
    public static void writeZenPolicyXml(ZenPolicy policy, XmlSerializer out)
            throws IOException {
        writeZenPolicyState(ALLOW_ATT_CALLS_FROM, policy.getPriorityCallSenders(), out);
        writeZenPolicyState(ALLOW_ATT_MESSAGES_FROM, policy.getPriorityMessageSenders(), out);
        writeZenPolicyState(ALLOW_ATT_REPEAT_CALLERS, policy.getPriorityCategoryRepeatCallers(),
                out);
        writeZenPolicyState(ALLOW_ATT_ALARMS, policy.getPriorityCategoryAlarms(), out);
        writeZenPolicyState(ALLOW_ATT_MEDIA, policy.getPriorityCategoryMedia(), out);
        writeZenPolicyState(ALLOW_ATT_SYSTEM, policy.getPriorityCategorySystem(), out);
        writeZenPolicyState(ALLOW_ATT_REMINDERS, policy.getPriorityCategoryReminders(), out);
        writeZenPolicyState(ALLOW_ATT_EVENTS, policy.getPriorityCategoryEvents(), out);

        writeZenPolicyState(SHOW_ATT_FULL_SCREEN_INTENT, policy.getVisualEffectFullScreenIntent(),
                out);
        writeZenPolicyState(SHOW_ATT_LIGHTS, policy.getVisualEffectLights(), out);
        writeZenPolicyState(SHOW_ATT_PEEK, policy.getVisualEffectPeek(), out);
        writeZenPolicyState(SHOW_ATT_STATUS_BAR_ICONS, policy.getVisualEffectStatusBar(), out);
        writeZenPolicyState(SHOW_ATT_BADGES, policy.getVisualEffectBadge(), out);
        writeZenPolicyState(SHOW_ATT_AMBIENT, policy.getVisualEffectAmbient(), out);
        writeZenPolicyState(SHOW_ATT_NOTIFICATION_LIST, policy.getVisualEffectNotificationList(),
                out);
    }

    private static void writeZenPolicyState(String attr, int val, XmlSerializer out)
            throws IOException {
        if (Objects.equals(attr, ALLOW_ATT_CALLS_FROM)
                || Objects.equals(attr, ALLOW_ATT_MESSAGES_FROM)) {
            if (val != ZenPolicy.PEOPLE_TYPE_UNSET) {
                out.attribute(null, attr, Integer.toString(val));
            }
        } else {
            if (val != ZenPolicy.STATE_UNSET) {
                out.attribute(null, attr, Integer.toString(val));
            }
        }
    }

    public static boolean isValidHour(int val) {
        return val >= 0 && val < 24;
    }

    public static boolean isValidMinute(int val) {
        return val >= 0 && val < 60;
    }

    private static boolean isValidSource(int source) {
        return source >= SOURCE_ANYONE && source <= MAX_SOURCE;
    }

    private static Boolean unsafeBoolean(XmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(val)) return null;
        return Boolean.parseBoolean(val);
    }

    private static boolean safeBoolean(XmlPullParser parser, String att, boolean defValue) {
        final String val = parser.getAttributeValue(null, att);
        return safeBoolean(val, defValue);
    }

    private static boolean safeBoolean(String val, boolean defValue) {
        if (TextUtils.isEmpty(val)) return defValue;
        return Boolean.parseBoolean(val);
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

    private static long safeLong(XmlPullParser parser, String att, long defValue) {
        final String val = parser.getAttributeValue(null, att);
        return tryParseLong(val, defValue);
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

    public static final @android.annotation.NonNull Parcelable.Creator<ZenModeConfig> CREATOR
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

    /**
     * Converts a zenPolicy to a notificationPolicy using this ZenModeConfig's values as its
     * defaults for all unset values in zenPolicy
     */
    public Policy toNotificationPolicy(ZenPolicy zenPolicy) {
        NotificationManager.Policy defaultPolicy = toNotificationPolicy();
        int priorityCategories = 0;
        int suppressedVisualEffects = 0;
        int callSenders = defaultPolicy.priorityCallSenders;
        int messageSenders = defaultPolicy.priorityMessageSenders;
        int conversationSenders = defaultPolicy.priorityConversationSenders;

        if (zenPolicy.isCategoryAllowed(ZenPolicy.PRIORITY_CATEGORY_REMINDERS,
                isPriorityCategoryEnabled(Policy.PRIORITY_CATEGORY_REMINDERS, defaultPolicy))) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_REMINDERS;
        }

        if (zenPolicy.isCategoryAllowed(ZenPolicy.PRIORITY_CATEGORY_EVENTS,
                isPriorityCategoryEnabled(Policy.PRIORITY_CATEGORY_EVENTS, defaultPolicy))) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_EVENTS;
        }

        if (zenPolicy.isCategoryAllowed(ZenPolicy.PRIORITY_CATEGORY_MESSAGES,
                isPriorityCategoryEnabled(Policy.PRIORITY_CATEGORY_MESSAGES, defaultPolicy))) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_MESSAGES;
            messageSenders = getNotificationPolicySenders(zenPolicy.getPriorityMessageSenders(),
                    messageSenders);
        }

        if (zenPolicy.isCategoryAllowed(ZenPolicy.PRIORITY_CATEGORY_CONVERSATIONS,
                isPriorityCategoryEnabled(Policy.PRIORITY_CATEGORY_CONVERSATIONS, defaultPolicy))) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_CONVERSATIONS;
            conversationSenders = getNotificationPolicySenders(
                    zenPolicy.getPriorityConversationSenders(),
                    conversationSenders);
        }

        if (zenPolicy.isCategoryAllowed(ZenPolicy.PRIORITY_CATEGORY_CALLS,
                isPriorityCategoryEnabled(Policy.PRIORITY_CATEGORY_CALLS, defaultPolicy))) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_CALLS;
            callSenders = getNotificationPolicySenders(zenPolicy.getPriorityCallSenders(),
                    callSenders);
        }

        if (zenPolicy.isCategoryAllowed(ZenPolicy.PRIORITY_CATEGORY_REPEAT_CALLERS,
                isPriorityCategoryEnabled(Policy.PRIORITY_CATEGORY_REPEAT_CALLERS,
                        defaultPolicy))) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_REPEAT_CALLERS;
        }

        if (zenPolicy.isCategoryAllowed(ZenPolicy.PRIORITY_CATEGORY_ALARMS,
                isPriorityCategoryEnabled(Policy.PRIORITY_CATEGORY_ALARMS, defaultPolicy))) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_ALARMS;
        }

        if (zenPolicy.isCategoryAllowed(ZenPolicy.PRIORITY_CATEGORY_MEDIA,
                isPriorityCategoryEnabled(Policy.PRIORITY_CATEGORY_MEDIA, defaultPolicy))) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_MEDIA;
        }

        if (zenPolicy.isCategoryAllowed(ZenPolicy.PRIORITY_CATEGORY_SYSTEM,
                isPriorityCategoryEnabled(Policy.PRIORITY_CATEGORY_SYSTEM, defaultPolicy))) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_SYSTEM;
        }

        boolean suppressFullScreenIntent = !zenPolicy.isVisualEffectAllowed(
                ZenPolicy.VISUAL_EFFECT_FULL_SCREEN_INTENT,
                isVisualEffectAllowed(Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT,
                        defaultPolicy));

        boolean suppressLights = !zenPolicy.isVisualEffectAllowed(
                ZenPolicy.VISUAL_EFFECT_LIGHTS,
                isVisualEffectAllowed(Policy.SUPPRESSED_EFFECT_LIGHTS,
                        defaultPolicy));

        boolean suppressAmbient = !zenPolicy.isVisualEffectAllowed(
                ZenPolicy.VISUAL_EFFECT_AMBIENT,
                isVisualEffectAllowed(Policy.SUPPRESSED_EFFECT_AMBIENT,
                        defaultPolicy));

        if (suppressFullScreenIntent && suppressLights && suppressAmbient) {
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_SCREEN_OFF;
        }

        if (suppressFullScreenIntent) {
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
        }

        if (suppressLights) {
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_LIGHTS;
        }

        if (!zenPolicy.isVisualEffectAllowed(ZenPolicy.VISUAL_EFFECT_PEEK,
                isVisualEffectAllowed(Policy.SUPPRESSED_EFFECT_PEEK,
                        defaultPolicy))) {
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_PEEK;
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_SCREEN_ON;
        }

        if (!zenPolicy.isVisualEffectAllowed(ZenPolicy.VISUAL_EFFECT_STATUS_BAR,
                isVisualEffectAllowed(Policy.SUPPRESSED_EFFECT_STATUS_BAR,
                        defaultPolicy))) {
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_STATUS_BAR;
        }

        if (!zenPolicy.isVisualEffectAllowed(ZenPolicy.VISUAL_EFFECT_BADGE,
                isVisualEffectAllowed(Policy.SUPPRESSED_EFFECT_BADGE,
                        defaultPolicy))) {
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_BADGE;
        }

        if (suppressAmbient) {
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_AMBIENT;
        }

        if (!zenPolicy.isVisualEffectAllowed(ZenPolicy.VISUAL_EFFECT_NOTIFICATION_LIST,
                isVisualEffectAllowed(Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST,
                        defaultPolicy))) {
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
        }

        return new NotificationManager.Policy(priorityCategories, callSenders,
                messageSenders, suppressedVisualEffects, defaultPolicy.state, conversationSenders);
    }

    private boolean isPriorityCategoryEnabled(int categoryType, Policy policy) {
        return (policy.priorityCategories & categoryType) != 0;
    }

    private boolean isVisualEffectAllowed(int visualEffect, Policy policy) {
        return (policy.suppressedVisualEffects & visualEffect) == 0;
    }

    private int getNotificationPolicySenders(@ZenPolicy.PeopleType int senders,
            int defaultPolicySender) {
        switch (senders) {
            case ZenPolicy.PEOPLE_TYPE_ANYONE:
                return Policy.PRIORITY_SENDERS_ANY;
            case ZenPolicy.PEOPLE_TYPE_CONTACTS:
                return Policy.PRIORITY_SENDERS_CONTACTS;
            case ZenPolicy.PEOPLE_TYPE_STARRED:
                return Policy.PRIORITY_SENDERS_STARRED;
            default:
                return defaultPolicySender;
        }
    }


    /**
     * Maps NotificationManager.Policy senders type to ZenPolicy.PeopleType
     */
    public static @ZenPolicy.PeopleType int getZenPolicySenders(int senders) {
        switch (senders) {
            case Policy.PRIORITY_SENDERS_ANY:
                return ZenPolicy.PEOPLE_TYPE_ANYONE;
            case Policy.PRIORITY_SENDERS_CONTACTS:
                return ZenPolicy.PEOPLE_TYPE_CONTACTS;
            case Policy.PRIORITY_SENDERS_STARRED:
            default:
                return ZenPolicy.PEOPLE_TYPE_STARRED;
        }
    }

    public Policy toNotificationPolicy() {
        int priorityCategories = 0;
        int priorityCallSenders = Policy.PRIORITY_SENDERS_CONTACTS;
        int priorityMessageSenders = Policy.PRIORITY_SENDERS_CONTACTS;
        int priorityConversationSenders = Policy.CONVERSATION_SENDERS_IMPORTANT;
        if (allowConversations) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_CONVERSATIONS;
        }
        if (allowCalls) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_CALLS;
        }
        if (allowMessages) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_MESSAGES;
        }
        if (allowEvents) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_EVENTS;
        }
        if (allowReminders) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_REMINDERS;
        }
        if (allowRepeatCallers) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_REPEAT_CALLERS;
        }
        if (allowAlarms) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_ALARMS;
        }
        if (allowMedia) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_MEDIA;
        }
        if (allowSystem) {
            priorityCategories |= Policy.PRIORITY_CATEGORY_SYSTEM;
        }
        priorityCallSenders = sourceToPrioritySenders(allowCallsFrom, priorityCallSenders);
        priorityMessageSenders = sourceToPrioritySenders(allowMessagesFrom, priorityMessageSenders);
        priorityConversationSenders = allowConversationsFrom;

        return new Policy(priorityCategories, priorityCallSenders, priorityMessageSenders,
                suppressedVisualEffects, areChannelsBypassingDnd
                ? Policy.STATE_CHANNELS_BYPASSING_DND : 0,
                priorityConversationSenders);
    }

    /**
     * Creates scheduleCalendar from a condition id
     * @param conditionId
     * @return ScheduleCalendar with info populated with conditionId
     */
    public static ScheduleCalendar toScheduleCalendar(Uri conditionId) {
        final ScheduleInfo schedule = ZenModeConfig.tryParseScheduleConditionId(conditionId);
        if (schedule == null || schedule.days == null || schedule.days.length == 0) return null;
        final ScheduleCalendar sc = new ScheduleCalendar();
        sc.setSchedule(schedule);
        sc.setTimeZone(TimeZone.getDefault());
        return sc;
    }

    private static int sourceToPrioritySenders(int source, int def) {
        switch (source) {
            case SOURCE_ANYONE: return Policy.PRIORITY_SENDERS_ANY;
            case SOURCE_CONTACT: return Policy.PRIORITY_SENDERS_CONTACTS;
            case SOURCE_STAR: return Policy.PRIORITY_SENDERS_STARRED;
            default: return def;
        }
    }

    private static int prioritySendersToSource(int prioritySenders, int def) {
        switch (prioritySenders) {
            case Policy.PRIORITY_SENDERS_CONTACTS: return SOURCE_CONTACT;
            case Policy.PRIORITY_SENDERS_STARRED: return SOURCE_STAR;
            case Policy.PRIORITY_SENDERS_ANY: return SOURCE_ANYONE;
            default: return def;
        }
    }

    private static int normalizePrioritySenders(int prioritySenders, int def) {
        if (!(prioritySenders == Policy.PRIORITY_SENDERS_CONTACTS
                || prioritySenders == Policy.PRIORITY_SENDERS_STARRED
                || prioritySenders == Policy.PRIORITY_SENDERS_ANY)) {
            return def;
        }
        return prioritySenders;
    }

    private static int normalizeConversationSenders(boolean allowed, int senders, int def) {
        if (!allowed) {
            return CONVERSATION_SENDERS_NONE;
        }
        if (!(senders == CONVERSATION_SENDERS_ANYONE
                || senders == CONVERSATION_SENDERS_IMPORTANT
                || senders == CONVERSATION_SENDERS_NONE)) {
            return def;
        }
        return senders;
    }

    public void applyNotificationPolicy(Policy policy) {
        if (policy == null) return;
        allowAlarms = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_ALARMS) != 0;
        allowMedia = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_MEDIA) != 0;
        allowSystem = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_SYSTEM) != 0;
        allowEvents = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_EVENTS) != 0;
        allowReminders = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_REMINDERS) != 0;
        allowCalls = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_CALLS) != 0;
        allowMessages = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_MESSAGES) != 0;
        allowRepeatCallers = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_REPEAT_CALLERS)
                != 0;
        allowCallsFrom = normalizePrioritySenders(policy.priorityCallSenders, allowCallsFrom);
        allowMessagesFrom = normalizePrioritySenders(policy.priorityMessageSenders,
                allowMessagesFrom);
        if (policy.suppressedVisualEffects != Policy.SUPPRESSED_EFFECTS_UNSET) {
            suppressedVisualEffects = policy.suppressedVisualEffects;
        }
        allowConversations = (policy.priorityCategories
                & Policy.PRIORITY_CATEGORY_CONVERSATIONS) != 0;
        allowConversationsFrom = normalizeConversationSenders(allowConversations,
                policy.priorityConversationSenders,
                allowConversationsFrom);
        if (policy.state != Policy.STATE_UNSET) {
            areChannelsBypassingDnd = (policy.state & Policy.STATE_CHANNELS_BYPASSING_DND) != 0;
        }
    }

    public static Condition toTimeCondition(Context context, int minutesFromNow, int userHandle) {
        return toTimeCondition(context, minutesFromNow, userHandle, false /*shortVersion*/);
    }

    public static Condition toTimeCondition(Context context, int minutesFromNow, int userHandle,
            boolean shortVersion) {
        final long now = System.currentTimeMillis();
        final long millis = minutesFromNow == 0 ? ZERO_VALUE_MS : minutesFromNow * MINUTES_MS;
        return toTimeCondition(context, now + millis, minutesFromNow, userHandle, shortVersion);
    }

    public static Condition toTimeCondition(Context context, long time, int minutes,
            int userHandle, boolean shortVersion) {
        final int num;
        String summary, line1, line2;
        final CharSequence formattedTime =
                getFormattedTime(context, time, isToday(time), userHandle);
        final Resources res = context.getResources();
        if (minutes < 60) {
            // display as minutes
            num = minutes;
            int summaryResId = shortVersion ? R.plurals.zen_mode_duration_minutes_summary_short
                    : R.plurals.zen_mode_duration_minutes_summary;
            summary = res.getQuantityString(summaryResId, num, num, formattedTime);
            int line1ResId = shortVersion ? R.plurals.zen_mode_duration_minutes_short
                    : R.plurals.zen_mode_duration_minutes;
            line1 = res.getQuantityString(line1ResId, num, num, formattedTime);
            line2 = res.getString(R.string.zen_mode_until, formattedTime);
        } else if (minutes < DAY_MINUTES) {
            // display as hours
            num =  Math.round(minutes / 60f);
            int summaryResId = shortVersion ? R.plurals.zen_mode_duration_hours_summary_short
                    : R.plurals.zen_mode_duration_hours_summary;
            summary = res.getQuantityString(summaryResId, num, num, formattedTime);
            int line1ResId = shortVersion ? R.plurals.zen_mode_duration_hours_short
                    : R.plurals.zen_mode_duration_hours;
            line1 = res.getQuantityString(line1ResId, num, num, formattedTime);
            line2 = res.getString(R.string.zen_mode_until, formattedTime);
        } else {
            // display as day/time
            summary = line1 = line2 = res.getString(R.string.zen_mode_until, formattedTime);
        }
        final Uri id = toCountdownConditionId(time, false);
        return new Condition(id, summary, line1, line2, 0, Condition.STATE_TRUE,
                Condition.FLAG_RELEVANT_NOW);
    }

    /**
     * Converts countdown to alarm parameters into a condition with user facing summary
     */
    public static Condition toNextAlarmCondition(Context context, long alarm,
            int userHandle) {
        boolean isSameDay = isToday(alarm);
        final CharSequence formattedTime = getFormattedTime(context, alarm, isSameDay, userHandle);
        final Resources res = context.getResources();
        final String line1 = res.getString(R.string.zen_mode_until, formattedTime);
        final Uri id = toCountdownConditionId(alarm, true);
        return new Condition(id, "", line1, "", 0, Condition.STATE_TRUE,
                Condition.FLAG_RELEVANT_NOW);
    }

    /**
     * Creates readable time from time in milliseconds
     */
    public static CharSequence getFormattedTime(Context context, long time, boolean isSameDay,
            int userHandle) {
        String skeleton = (!isSameDay ? "EEE " : "")
                + (DateFormat.is24HourFormat(context, userHandle) ? "Hm" : "hma");
        final String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, time);
    }

    /**
     * Determines whether a time in milliseconds is today or not
     */
    public static boolean isToday(long time) {
        GregorianCalendar now = new GregorianCalendar();
        GregorianCalendar endTime = new GregorianCalendar();
        endTime.setTimeInMillis(time);
        if (now.get(Calendar.YEAR) == endTime.get(Calendar.YEAR)
                && now.get(Calendar.MONTH) == endTime.get(Calendar.MONTH)
                && now.get(Calendar.DATE) == endTime.get(Calendar.DATE)) {
            return true;
        }
        return false;
    }

    // ==== Built-in system conditions ====

    public static final String SYSTEM_AUTHORITY = "android";

    // ==== Built-in system condition: countdown ====

    public static final String COUNTDOWN_PATH = "countdown";

    public static final String IS_ALARM_PATH = "alarm";

    /**
     * Converts countdown condition parameters into a condition id.
     */
    public static Uri toCountdownConditionId(long time, boolean alarm) {
        return new Uri.Builder().scheme(Condition.SCHEME)
                .authority(SYSTEM_AUTHORITY)
                .appendPath(COUNTDOWN_PATH)
                .appendPath(Long.toString(time))
                .appendPath(IS_ALARM_PATH)
                .appendPath(Boolean.toString(alarm))
                .build();
    }

    public static long tryParseCountdownConditionId(Uri conditionId) {
        if (!Condition.isValidId(conditionId, SYSTEM_AUTHORITY)) return 0;
        if (conditionId.getPathSegments().size() < 2
                || !COUNTDOWN_PATH.equals(conditionId.getPathSegments().get(0))) return 0;
        try {
            return Long.parseLong(conditionId.getPathSegments().get(1));
        } catch (RuntimeException e) {
            Slog.w(TAG, "Error parsing countdown condition: " + conditionId, e);
            return 0;
        }
    }

    /**
     * Returns whether this condition is a countdown condition.
     */
    public static boolean isValidCountdownConditionId(Uri conditionId) {
        return tryParseCountdownConditionId(conditionId) != 0;
    }

    /**
     * Returns whether this condition is a countdown to an alarm.
     */
    public static boolean isValidCountdownToAlarmConditionId(Uri conditionId) {
        if (tryParseCountdownConditionId(conditionId) != 0) {
            if (conditionId.getPathSegments().size() < 4
                    || !IS_ALARM_PATH.equals(conditionId.getPathSegments().get(2))) {
                return false;
            }
            try {
                return Boolean.parseBoolean(conditionId.getPathSegments().get(3));
            } catch (RuntimeException e) {
                Slog.w(TAG, "Error parsing countdown alarm condition: " + conditionId, e);
                return false;
            }
        }
        return false;
    }

    // ==== Built-in system condition: schedule ====

    public static final String SCHEDULE_PATH = "schedule";

    public static Uri toScheduleConditionId(ScheduleInfo schedule) {
        return new Uri.Builder().scheme(Condition.SCHEME)
                .authority(SYSTEM_AUTHORITY)
                .appendPath(SCHEDULE_PATH)
                .appendQueryParameter("days", toDayList(schedule.days))
                .appendQueryParameter("start", schedule.startHour + "." + schedule.startMinute)
                .appendQueryParameter("end", schedule.endHour + "." + schedule.endMinute)
                .appendQueryParameter("exitAtAlarm", String.valueOf(schedule.exitAtAlarm))
                .build();
    }

    public static boolean isValidScheduleConditionId(Uri conditionId) {
        ScheduleInfo info;
        try {
            info = tryParseScheduleConditionId(conditionId);
        } catch (NullPointerException | ArrayIndexOutOfBoundsException e) {
            return false;
        }

        if (info == null || info.days == null || info.days.length == 0) {
            return false;
        }
        return true;
    }

    /**
     * Returns whether the conditionId is a valid ScheduleCondition.
     * If allowNever is true, this will return true even if the ScheduleCondition never occurs.
     */
    public static boolean isValidScheduleConditionId(Uri conditionId, boolean allowNever) {
        ScheduleInfo info;
        try {
            info = tryParseScheduleConditionId(conditionId);
        } catch (NullPointerException | ArrayIndexOutOfBoundsException e) {
            return false;
        }

        if (info == null || (!allowNever && (info.days == null || info.days.length == 0))) {
            return false;
        }
        return true;
    }

    @UnsupportedAppUsage
    public static ScheduleInfo tryParseScheduleConditionId(Uri conditionId) {
        final boolean isSchedule =  conditionId != null
                && Condition.SCHEME.equals(conditionId.getScheme())
                && ZenModeConfig.SYSTEM_AUTHORITY.equals(conditionId.getAuthority())
                && conditionId.getPathSegments().size() == 1
                && ZenModeConfig.SCHEDULE_PATH.equals(conditionId.getPathSegments().get(0));
        if (!isSchedule) return null;
        final int[] start = tryParseHourAndMinute(conditionId.getQueryParameter("start"));
        final int[] end = tryParseHourAndMinute(conditionId.getQueryParameter("end"));
        if (start == null || end == null) return null;
        final ScheduleInfo rt = new ScheduleInfo();
        rt.days = tryParseDayList(conditionId.getQueryParameter("days"), "\\.");
        rt.startHour = start[0];
        rt.startMinute = start[1];
        rt.endHour = end[0];
        rt.endMinute = end[1];
        rt.exitAtAlarm = safeBoolean(conditionId.getQueryParameter("exitAtAlarm"), false);
        return rt;
    }

    public static ComponentName getScheduleConditionProvider() {
        return new ComponentName(SYSTEM_AUTHORITY, "ScheduleConditionProvider");
    }

    public static class ScheduleInfo {
        @UnsupportedAppUsage
        public int[] days;
        @UnsupportedAppUsage
        public int startHour;
        @UnsupportedAppUsage
        public int startMinute;
        @UnsupportedAppUsage
        public int endHour;
        @UnsupportedAppUsage
        public int endMinute;
        public boolean exitAtAlarm;
        public long nextAlarm;

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ScheduleInfo)) return false;
            final ScheduleInfo other = (ScheduleInfo) o;
            return toDayList(days).equals(toDayList(other.days))
                    && startHour == other.startHour
                    && startMinute == other.startMinute
                    && endHour == other.endHour
                    && endMinute == other.endMinute
                    && exitAtAlarm == other.exitAtAlarm;
        }

        public ScheduleInfo copy() {
            final ScheduleInfo rt = new ScheduleInfo();
            if (days != null) {
                rt.days = new int[days.length];
                System.arraycopy(days, 0, rt.days, 0, days.length);
            }
            rt.startHour = startHour;
            rt.startMinute = startMinute;
            rt.endHour = endHour;
            rt.endMinute = endMinute;
            rt.exitAtAlarm = exitAtAlarm;
            rt.nextAlarm = nextAlarm;
            return rt;
        }

        @Override
        public String toString() {
            return "ScheduleInfo{" +
                    "days=" + Arrays.toString(days) +
                    ", startHour=" + startHour +
                    ", startMinute=" + startMinute +
                    ", endHour=" + endHour +
                    ", endMinute=" + endMinute +
                    ", exitAtAlarm=" + exitAtAlarm +
                    ", nextAlarm=" + ts(nextAlarm) +
                    '}';
        }

        protected static String ts(long time) {
            return new Date(time) + " (" + time + ")";
        }
    }

    // ==== Built-in system condition: event ====

    public static final String EVENT_PATH = "event";

    public static Uri toEventConditionId(EventInfo event) {
        return new Uri.Builder().scheme(Condition.SCHEME)
                .authority(SYSTEM_AUTHORITY)
                .appendPath(EVENT_PATH)
                .appendQueryParameter("userId", Long.toString(event.userId))
                .appendQueryParameter("calendar", event.calName != null ? event.calName : "")
                .appendQueryParameter("calendarId", event.calendarId != null
                        ? event.calendarId.toString() : "")
                .appendQueryParameter("reply", Integer.toString(event.reply))
                .build();
    }

    public static boolean isValidEventConditionId(Uri conditionId) {
        return tryParseEventConditionId(conditionId) != null;
    }

    public static EventInfo tryParseEventConditionId(Uri conditionId) {
        final boolean isEvent = conditionId != null
                && Condition.SCHEME.equals(conditionId.getScheme())
                && ZenModeConfig.SYSTEM_AUTHORITY.equals(conditionId.getAuthority())
                && conditionId.getPathSegments().size() == 1
                && EVENT_PATH.equals(conditionId.getPathSegments().get(0));
        if (!isEvent) return null;
        final EventInfo rt = new EventInfo();
        rt.userId = tryParseInt(conditionId.getQueryParameter("userId"), UserHandle.USER_NULL);
        rt.calName = conditionId.getQueryParameter("calendar");
        if (TextUtils.isEmpty(rt.calName)) {
            rt.calName = null;
        }
        rt.calendarId = tryParseLong(conditionId.getQueryParameter("calendarId"), null);
        rt.reply = tryParseInt(conditionId.getQueryParameter("reply"), 0);
        return rt;
    }

    public static ComponentName getEventConditionProvider() {
        return new ComponentName(SYSTEM_AUTHORITY, "EventConditionProvider");
    }

    public static class EventInfo {
        public static final int REPLY_ANY_EXCEPT_NO = 0;
        public static final int REPLY_YES_OR_MAYBE = 1;
        public static final int REPLY_YES = 2;

        public int userId = UserHandle.USER_NULL;  // USER_NULL = unspecified - use current user
        public String calName;  // CalendarContract.Calendars.DISPLAY_NAME, or null for any
        public Long calendarId; // Calendars._ID, or null if restored from < Q calendar
        public int reply;

        @Override
        public int hashCode() {
            return Objects.hash(userId, calName, calendarId, reply);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof EventInfo)) return false;
            final EventInfo other = (EventInfo) o;
            return userId == other.userId
                    && Objects.equals(calName, other.calName)
                    && reply == other.reply
                    && Objects.equals(calendarId, other.calendarId);
        }

        public EventInfo copy() {
            final EventInfo rt = new EventInfo();
            rt.userId = userId;
            rt.calName = calName;
            rt.reply = reply;
            rt.calendarId = calendarId;
            return rt;
        }

        public static int resolveUserId(int userId) {
            return userId == UserHandle.USER_NULL ? ActivityManager.getCurrentUser() : userId;
        }
    }

    // ==== End built-in system conditions ====

    private static int[] tryParseHourAndMinute(String value) {
        if (TextUtils.isEmpty(value)) return null;
        final int i = value.indexOf('.');
        if (i < 1 || i >= value.length() - 1) return null;
        final int hour = tryParseInt(value.substring(0, i), -1);
        final int minute = tryParseInt(value.substring(i + 1), -1);
        return isValidHour(hour) && isValidMinute(minute) ? new int[] { hour, minute } : null;
    }

    private static int tryParseZenMode(String value, int defValue) {
        final int rt = tryParseInt(value, defValue);
        return Global.isValidZenMode(rt) ? rt : defValue;
    }

    public static String newRuleId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Gets the name of the app associated with owner
     */
    public static String getOwnerCaption(Context context, String owner) {
        final PackageManager pm = context.getPackageManager();
        try {
            final ApplicationInfo info = pm.getApplicationInfo(owner, 0);
            if (info != null) {
                final CharSequence seq = info.loadLabel(pm);
                if (seq != null) {
                    final String str = seq.toString().trim();
                    if (str.length() > 0) {
                        return str;
                    }
                }
            }
        } catch (Throwable e) {
            Slog.w(TAG, "Error loading owner caption", e);
        }
        return "";
    }

    public static String getConditionSummary(Context context, ZenModeConfig config,
            int userHandle, boolean shortVersion) {
        return getConditionLine(context, config, userHandle, false /*useLine1*/, shortVersion);
    }

    private static String getConditionLine(Context context, ZenModeConfig config,
            int userHandle, boolean useLine1, boolean shortVersion) {
        if (config == null) return "";
        String summary = "";
        if (config.manualRule != null) {
            final Uri id = config.manualRule.conditionId;
            if (config.manualRule.enabler != null) {
                summary = getOwnerCaption(context, config.manualRule.enabler);
            } else {
                if (id == null) {
                    summary = context.getString(com.android.internal.R.string.zen_mode_forever);
                } else {
                    final long time = tryParseCountdownConditionId(id);
                    Condition c = config.manualRule.condition;
                    if (time > 0) {
                        final long now = System.currentTimeMillis();
                        final long span = time - now;
                        c = toTimeCondition(context, time, Math.round(span / (float) MINUTES_MS),
                                userHandle, shortVersion);
                    }
                    final String rt = c == null ? "" : useLine1 ? c.line1 : c.summary;
                    summary = TextUtils.isEmpty(rt) ? "" : rt;
                }
            }
        }
        for (ZenRule automaticRule : config.automaticRules.values()) {
            if (automaticRule.isAutomaticActive()) {
                if (summary.isEmpty()) {
                    summary = automaticRule.name;
                } else {
                    summary = context.getResources()
                            .getString(R.string.zen_mode_rule_name_combination, summary,
                                    automaticRule.name);
                }

            }
        }
        return summary;
    }

    public static class ZenRule implements Parcelable {
        @UnsupportedAppUsage
        public boolean enabled;
        @UnsupportedAppUsage
        public boolean snoozing;         // user manually disabled this instance
        @UnsupportedAppUsage
        public String name;              // required for automatic
        @UnsupportedAppUsage
        public int zenMode;             // ie: Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
        @UnsupportedAppUsage
        public Uri conditionId;          // required for automatic
        public Condition condition;      // optional
        public ComponentName component;  // optional
        public ComponentName configurationActivity; // optional
        public String id;                // required for automatic (unique)
        @UnsupportedAppUsage
        public long creationTime;        // required for automatic
        // package name, only used for manual rules when they have turned DND on.
        public String enabler;
        public ZenPolicy zenPolicy;
        public boolean modified;    // rule has been modified from initial creation
        public String pkg;

        public ZenRule() { }

        public ZenRule(Parcel source) {
            enabled = source.readInt() == 1;
            snoozing = source.readInt() == 1;
            if (source.readInt() == 1) {
                name = source.readString();
            }
            zenMode = source.readInt();
            conditionId = source.readParcelable(null);
            condition = source.readParcelable(null);
            component = source.readParcelable(null);
            configurationActivity = source.readParcelable(null);
            if (source.readInt() == 1) {
                id = source.readString();
            }
            creationTime = source.readLong();
            if (source.readInt() == 1) {
                enabler = source.readString();
            }
            zenPolicy = source.readParcelable(null);
            modified = source.readInt() == 1;
            pkg = source.readString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(enabled ? 1 : 0);
            dest.writeInt(snoozing ? 1 : 0);
            if (name != null) {
                dest.writeInt(1);
                dest.writeString(name);
            } else {
                dest.writeInt(0);
            }
            dest.writeInt(zenMode);
            dest.writeParcelable(conditionId, 0);
            dest.writeParcelable(condition, 0);
            dest.writeParcelable(component, 0);
            dest.writeParcelable(configurationActivity, 0);
            if (id != null) {
                dest.writeInt(1);
                dest.writeString(id);
            } else {
                dest.writeInt(0);
            }
            dest.writeLong(creationTime);
            if (enabler != null) {
                dest.writeInt(1);
                dest.writeString(enabler);
            } else {
                dest.writeInt(0);
            }
            dest.writeParcelable(zenPolicy, 0);
            dest.writeInt(modified ? 1 : 0);
            dest.writeString(pkg);
        }

        @Override
        public String toString() {
            return new StringBuilder(ZenRule.class.getSimpleName()).append('[')
                    .append("id=").append(id)
                    .append(",enabled=").append(String.valueOf(enabled).toUpperCase())
                    .append(",snoozing=").append(snoozing)
                    .append(",name=").append(name)
                    .append(",zenMode=").append(Global.zenModeToString(zenMode))
                    .append(",conditionId=").append(conditionId)
                    .append(",condition=").append(condition)
                    .append(",pkg=").append(pkg)
                    .append(",component=").append(component)
                    .append(",configActivity=").append(configurationActivity)
                    .append(",creationTime=").append(creationTime)
                    .append(",enabler=").append(enabler)
                    .append(",zenPolicy=").append(zenPolicy)
                    .append(",modified=").append(modified)
                    .append(']').toString();
        }

        /** @hide */
        // TODO: add configuration activity
        public void dumpDebug(ProtoOutputStream proto, long fieldId) {
            final long token = proto.start(fieldId);

            proto.write(ZenRuleProto.ID, id);
            proto.write(ZenRuleProto.NAME, name);
            proto.write(ZenRuleProto.CREATION_TIME_MS, creationTime);
            proto.write(ZenRuleProto.ENABLED, enabled);
            proto.write(ZenRuleProto.ENABLER, enabler);
            proto.write(ZenRuleProto.IS_SNOOZING, snoozing);
            proto.write(ZenRuleProto.ZEN_MODE, zenMode);
            if (conditionId != null) {
                proto.write(ZenRuleProto.CONDITION_ID, conditionId.toString());
            }
            if (condition != null) {
                condition.dumpDebug(proto, ZenRuleProto.CONDITION);
            }
            if (component != null) {
                component.dumpDebug(proto, ZenRuleProto.COMPONENT);
            }
            if (zenPolicy != null) {
                zenPolicy.dumpDebug(proto, ZenRuleProto.ZEN_POLICY);
            }
            proto.write(ZenRuleProto.MODIFIED, modified);
            proto.end(token);
        }

        private static void appendDiff(Diff d, String item, ZenRule from, ZenRule to) {
            if (d == null) return;
            if (from == null) {
                if (to != null) {
                    d.addLine(item, "insert");
                }
                return;
            }
            from.appendDiff(d, item, to);
        }

        private void appendDiff(Diff d, String item, ZenRule to) {
            if (to == null) {
                d.addLine(item, "delete");
                return;
            }
            if (enabled != to.enabled) {
                d.addLine(item, "enabled", enabled, to.enabled);
            }
            if (snoozing != to.snoozing) {
                d.addLine(item, "snoozing", snoozing, to.snoozing);
            }
            if (!Objects.equals(name, to.name)) {
                d.addLine(item, "name", name, to.name);
            }
            if (zenMode != to.zenMode) {
                d.addLine(item, "zenMode", zenMode, to.zenMode);
            }
            if (!Objects.equals(conditionId, to.conditionId)) {
                d.addLine(item, "conditionId", conditionId, to.conditionId);
            }
            if (!Objects.equals(condition, to.condition)) {
                d.addLine(item, "condition", condition, to.condition);
            }
            if (!Objects.equals(component, to.component)) {
                d.addLine(item, "component", component, to.component);
            }
            if (!Objects.equals(configurationActivity, to.configurationActivity)) {
                d.addLine(item, "configActivity", configurationActivity, to.configurationActivity);
            }
            if (!Objects.equals(id, to.id)) {
                d.addLine(item, "id", id, to.id);
            }
            if (creationTime != to.creationTime) {
                d.addLine(item, "creationTime", creationTime, to.creationTime);
            }
            if (!Objects.equals(enabler, to.enabler)) {
                d.addLine(item, "enabler", enabler, to.enabler);
            }
            if (!Objects.equals(zenPolicy, to.zenPolicy)) {
                d.addLine(item, "zenPolicy", zenPolicy, to.zenPolicy);
            }
            if (modified != to.modified) {
                d.addLine(item, "modified", modified, to.modified);
            }
            if (!Objects.equals(pkg, to.pkg)) {
                d.addLine(item, "pkg", pkg, to.pkg);
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof ZenRule)) return false;
            if (o == this) return true;
            final ZenRule other = (ZenRule) o;
            return other.enabled == enabled
                    && other.snoozing == snoozing
                    && Objects.equals(other.name, name)
                    && other.zenMode == zenMode
                    && Objects.equals(other.conditionId, conditionId)
                    && Objects.equals(other.condition, condition)
                    && Objects.equals(other.component, component)
                    && Objects.equals(other.configurationActivity, configurationActivity)
                    && Objects.equals(other.id, id)
                    && Objects.equals(other.enabler, enabler)
                    && Objects.equals(other.zenPolicy, zenPolicy)
                    && Objects.equals(other.pkg, pkg)
                    && other.modified == modified;
        }

        @Override
        public int hashCode() {
            return Objects.hash(enabled, snoozing, name, zenMode, conditionId, condition,
                    component, configurationActivity, pkg, id, enabler, zenPolicy, modified);
        }

        public boolean isAutomaticActive() {
            return enabled && !snoozing && pkg != null && isTrueOrUnknown();
        }

        public boolean isTrueOrUnknown() {
            return condition != null && (condition.state == Condition.STATE_TRUE
                    || condition.state == Condition.STATE_UNKNOWN);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<ZenRule> CREATOR
                = new Parcelable.Creator<ZenRule>() {
            @Override
            public ZenRule createFromParcel(Parcel source) {
                return new ZenRule(source);
            }
            @Override
            public ZenRule[] newArray(int size) {
                return new ZenRule[size];
            }
        };
    }

    public static class Diff {
        private final ArrayList<String> lines = new ArrayList<>();

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder("Diff[");
            final int N = lines.size();
            for (int i = 0; i < N; i++) {
                if (i > 0) {
                    sb.append(",\n");
                }
                sb.append(lines.get(i));
            }
            return sb.append(']').toString();
        }

        private Diff addLine(String item, String action) {
            lines.add(item + ":" + action);
            return this;
        }

        public Diff addLine(String item, String subitem, Object from, Object to) {
            return addLine(item + "." + subitem, from, to);
        }

        public Diff addLine(String item, Object from, Object to) {
            return addLine(item, from + "->" + to);
        }
    }

    /**
     * Determines whether dnd behavior should mute all ringer-controlled sounds
     * This includes notification, ringer and system sounds
     */
    public static boolean areAllPriorityOnlyRingerSoundsMuted(NotificationManager.Policy
            policy) {
        boolean allowReminders = (policy.priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS) != 0;
        boolean allowCalls = (policy.priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_CALLS) != 0;
        boolean allowMessages = (policy.priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES) != 0;
        boolean allowEvents = (policy.priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS) != 0;
        boolean allowRepeatCallers = (policy.priorityCategories
                & NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS) != 0;
        boolean allowConversations = (policy.priorityConversationSenders
                & Policy.PRIORITY_CATEGORY_CONVERSATIONS) != 0;
        boolean areChannelsBypassingDnd = (policy.state & Policy.STATE_CHANNELS_BYPASSING_DND) != 0;
        boolean allowSystem =  (policy.priorityCategories & Policy.PRIORITY_CATEGORY_SYSTEM) != 0;
        return !allowReminders && !allowCalls && !allowMessages && !allowEvents
                && !allowRepeatCallers && !areChannelsBypassingDnd && !allowSystem
                && !allowConversations;
    }

    /**
     * Determines whether dnd behavior should mute all sounds
     */
    public static boolean areAllZenBehaviorSoundsMuted(NotificationManager.Policy
            policy) {
        boolean allowAlarms = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_ALARMS) != 0;
        boolean allowMedia = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_MEDIA) != 0;
        return !allowAlarms && !allowMedia && areAllPriorityOnlyRingerSoundsMuted(policy);
    }

    /**
     * Determines if DND is currently overriding the ringer
     */
    public static boolean isZenOverridingRinger(int zen, Policy consolidatedPolicy) {
        return zen == Global.ZEN_MODE_NO_INTERRUPTIONS
                || zen == Global.ZEN_MODE_ALARMS
                || (zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                && ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(consolidatedPolicy));
    }

    /**
     * Determines whether dnd behavior should mute all ringer-controlled sounds
     * This includes notification, ringer and system sounds
     */
    public static boolean areAllPriorityOnlyRingerSoundsMuted(ZenModeConfig config) {
        return !config.allowReminders && !config.allowCalls && !config.allowMessages
                && !config.allowEvents && !config.allowRepeatCallers
                && !config.areChannelsBypassingDnd && !config.allowSystem;
    }

    /**
     * Determines whether dnd mutes all sounds
     */
    public static boolean areAllZenBehaviorSoundsMuted(ZenModeConfig config) {
        return !config.allowAlarms  && !config.allowMedia
                && areAllPriorityOnlyRingerSoundsMuted(config);
    }

    /**
     * Returns a description of the current do not disturb settings from config.
     * - If turned on manually and end time is known, returns end time.
     * - If turned on manually and end time is on forever until turned off, return null if
     * describeForeverCondition is false, else return String describing indefinite behavior
     * - If turned on by an automatic rule, returns the automatic rule name.
     * - If on due to an app, returns the app name.
     * - If there's a combination of rules/apps that trigger, then shows the one that will
     *  last the longest if applicable.
     * @return null if DND is off or describeForeverCondition is false and
     * DND is on forever (until turned off)
     */
    public static String getDescription(Context context, boolean zenOn, ZenModeConfig config,
            boolean describeForeverCondition) {
        if (!zenOn || config == null) {
            return null;
        }

        String secondaryText = "";
        long latestEndTime = -1;

        // DND turned on by manual rule
        if (config.manualRule != null) {
            final Uri id = config.manualRule.conditionId;
            if (config.manualRule.enabler != null) {
                // app triggered manual rule
                String appName = getOwnerCaption(context, config.manualRule.enabler);
                if (!appName.isEmpty()) {
                    secondaryText = appName;
                }
            } else {
                if (id == null) {
                    // Do not disturb manually triggered to remain on forever until turned off
                    if (describeForeverCondition) {
                        return context.getString(R.string.zen_mode_forever);
                    } else {
                        return null;
                    }
                } else {
                    latestEndTime = tryParseCountdownConditionId(id);
                    if (latestEndTime > 0) {
                        final CharSequence formattedTime = getFormattedTime(context,
                                latestEndTime, isToday(latestEndTime),
                                context.getUserId());
                        secondaryText = context.getString(R.string.zen_mode_until, formattedTime);
                    }
                }
            }
        }

        // DND turned on by an automatic rule
        for (ZenRule automaticRule : config.automaticRules.values()) {
            if (automaticRule.isAutomaticActive()) {
                if (isValidEventConditionId(automaticRule.conditionId)
                        || isValidScheduleConditionId(automaticRule.conditionId)) {
                    // set text if automatic rule end time is the latest active rule end time
                    long endTime = parseAutomaticRuleEndTime(context, automaticRule.conditionId);
                    if (endTime > latestEndTime) {
                        latestEndTime = endTime;
                        secondaryText = automaticRule.name;
                    }
                } else {
                    // set text if 3rd party rule
                    return automaticRule.name;
                }
            }
        }

        return !secondaryText.equals("") ? secondaryText : null;
    }

    private static long parseAutomaticRuleEndTime(Context context, Uri id) {
        if (isValidEventConditionId(id)) {
            // cannot look up end times for events
            return Long.MAX_VALUE;
        }

        if (isValidScheduleConditionId(id)) {
            ScheduleCalendar schedule = toScheduleCalendar(id);
            long endTimeMs = schedule.getNextChangeTime(System.currentTimeMillis());

            // check if automatic rule will end on next alarm
            if (schedule.exitAtAlarm()) {
                long nextAlarm = getNextAlarm(context);
                schedule.maybeSetNextAlarm(System.currentTimeMillis(), nextAlarm);
                if (schedule.shouldExitForAlarm(endTimeMs)) {
                    return nextAlarm;
                }
            }

            return endTimeMs;
        }

        return -1;
    }

    private static long getNextAlarm(Context context) {
        final AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        final AlarmManager.AlarmClockInfo info = alarms.getNextAlarmClock(context.getUserId());
        return info != null ? info.getTriggerTime() : 0;
    }
}
