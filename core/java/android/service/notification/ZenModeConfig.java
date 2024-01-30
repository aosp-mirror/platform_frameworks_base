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
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_SCREEN_OFF;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AutomaticZenRule;
import android.app.Flags;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.ArrayMap;
import android.util.PluralsMessageFormatter;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Persisted configuration for zen mode.
 *
 * @hide
 */
public class ZenModeConfig implements Parcelable {
    private static final String TAG = "ZenModeConfig";

    /**
     * The {@link ZenModeConfig} is being updated because of an unknown reason.
     */
    public static final int UPDATE_ORIGIN_UNKNOWN = 0;

    /**
     * The {@link ZenModeConfig} is being updated because of system initialization (i.e. load from
     * storage, on device boot).
     */
    public static final int UPDATE_ORIGIN_INIT = 1;

    /** The {@link ZenModeConfig} is being updated (replaced) because of a user switch or unlock. */
    public static final int UPDATE_ORIGIN_INIT_USER = 2;

    /** The {@link ZenModeConfig} is being updated because of a user action, for example:
     * <ul>
     *     <li>{@link NotificationManager#setAutomaticZenRuleState} with a
     *     {@link Condition#source} equal to {@link Condition#SOURCE_USER_ACTION}.</li>
     *     <li>Adding, updating, or removing a rule from Settings.</li>
     *     <li>Directly activating or deactivating/snoozing a rule through some UI affordance (e.g.
     *     Quick Settings).</li>
     * </ul>
     */
    public static final int UPDATE_ORIGIN_USER = 3;

    /**
     * The {@link ZenModeConfig} is being "independently" updated by an app, and not as a result of
     * a user's action inside that app (for example, activating an {@link AutomaticZenRule} based on
     * a previously set schedule).
     */
    public static final int UPDATE_ORIGIN_APP = 4;

    /**
     * The {@link ZenModeConfig} is being updated by the System or SystemUI. Note that this only
     * includes cases where the call is coming from the System/SystemUI but the change is not due to
     * a user action (e.g. automatically activating a schedule-based rule). If the change is a
     * result of a user action (e.g. activating a rule by tapping on its QS tile) then
     * {@link #UPDATE_ORIGIN_USER} is used instead.
     */
    public static final int UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI = 5;

    /**
     * The {@link ZenModeConfig} is being updated (replaced) because the user's DND configuration
     * is being restored from a backup.
     */
    public static final int UPDATE_ORIGIN_RESTORE_BACKUP = 6;

    @IntDef(prefix = { "UPDATE_ORIGIN_" }, value = {
            UPDATE_ORIGIN_UNKNOWN,
            UPDATE_ORIGIN_INIT,
            UPDATE_ORIGIN_INIT_USER,
            UPDATE_ORIGIN_USER,
            UPDATE_ORIGIN_APP,
            UPDATE_ORIGIN_SYSTEM_OR_SYSTEMUI,
            UPDATE_ORIGIN_RESTORE_BACKUP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ConfigChangeOrigin {}

    public static final int SOURCE_ANYONE = Policy.PRIORITY_SENDERS_ANY;
    public static final int SOURCE_CONTACT = Policy.PRIORITY_SENDERS_CONTACTS;
    public static final int SOURCE_STAR = Policy.PRIORITY_SENDERS_STARRED;
    public static final int MAX_SOURCE = SOURCE_STAR;
    private static final int DEFAULT_SOURCE = SOURCE_STAR;
    private static final int DEFAULT_CALLS_SOURCE = SOURCE_STAR;

    public static final String MANUAL_RULE_ID = "MANUAL_RULE";
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
    private static final boolean DEFAULT_ALLOW_MESSAGES = true;
    private static final boolean DEFAULT_ALLOW_REMINDERS = false;
    private static final boolean DEFAULT_ALLOW_EVENTS = false;
    private static final boolean DEFAULT_ALLOW_REPEAT_CALLERS = true;
    private static final boolean DEFAULT_ALLOW_CONV = true;
    private static final int DEFAULT_ALLOW_CONV_FROM = ZenPolicy.CONVERSATION_SENDERS_IMPORTANT;
    private static final boolean DEFAULT_ALLOW_PRIORITY_CHANNELS = true;
    private static final boolean DEFAULT_CHANNELS_BYPASSING_DND = false;
    // Default setting here is 010011101 = 157
    private static final int DEFAULT_SUPPRESSED_VISUAL_EFFECTS =
            SUPPRESSED_EFFECT_SCREEN_OFF | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
                    | SUPPRESSED_EFFECT_LIGHTS | SUPPRESSED_EFFECT_PEEK | SUPPRESSED_EFFECT_AMBIENT;

    // ZenModeConfig XML versions distinguishing key changes.
    public static final int XML_VERSION_ZEN_UPGRADE = 8;
    public static final int XML_VERSION_MODES_API = 11;

    // TODO: b/310620812 - Update XML_VERSION and update default_zen_config.xml accordingly when
    //       modes_api is inlined.
    private static final int XML_VERSION = 10;
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
    private static final String ALLOW_ATT_CHANNELS = "priorityChannels";
    private static final String POLICY_USER_MODIFIED_FIELDS = "policyUserModifiedFields";
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
    private static final String CONDITION_ATT_SOURCE = "source";
    private static final String CONDITION_ATT_FLAGS = "flags";

    private static final String ZEN_POLICY_TAG = "zen_policy";

    private static final String MANUAL_TAG = "manual";
    private static final String AUTOMATIC_TAG = "automatic";
    private static final String AUTOMATIC_DELETED_TAG = "deleted";

    private static final String RULE_ATT_ID = "ruleId";
    private static final String RULE_ATT_ENABLED = "enabled";
    private static final String RULE_ATT_SNOOZING = "snoozing";
    private static final String RULE_ATT_NAME = "name";
    private static final String RULE_ATT_PKG = "pkg";
    private static final String RULE_ATT_COMPONENT = "component";
    private static final String RULE_ATT_CONFIG_ACTIVITY = "configActivity";
    private static final String RULE_ATT_ZEN = "zen";
    private static final String RULE_ATT_CONDITION_ID = "conditionId";
    private static final String RULE_ATT_CREATION_TIME = "creationTime";
    private static final String RULE_ATT_ENABLER = "enabler";
    private static final String RULE_ATT_MODIFIED = "modified";
    private static final String RULE_ATT_ALLOW_MANUAL = "userInvokable";
    private static final String RULE_ATT_TYPE = "type";
    private static final String RULE_ATT_USER_MODIFIED_FIELDS = "userModifiedFields";
    private static final String RULE_ATT_ICON = "rule_icon";
    private static final String RULE_ATT_TRIGGER_DESC = "triggerDesc";
    private static final String RULE_ATT_DELETION_INSTANT = "deletionInstant";

    private static final String DEVICE_EFFECT_DISPLAY_GRAYSCALE = "zdeDisplayGrayscale";
    private static final String DEVICE_EFFECT_SUPPRESS_AMBIENT_DISPLAY =
            "zdeSuppressAmbientDisplay";
    private static final String DEVICE_EFFECT_DIM_WALLPAPER = "zdeDimWallpaper";
    private static final String DEVICE_EFFECT_USE_NIGHT_MODE = "zdeUseNightMode";
    private static final String DEVICE_EFFECT_DISABLE_AUTO_BRIGHTNESS = "zdeDisableAutoBrightness";
    private static final String DEVICE_EFFECT_DISABLE_TAP_TO_WAKE = "zdeDisableTapToWake";
    private static final String DEVICE_EFFECT_DISABLE_TILT_TO_WAKE = "zdeDisableTiltToWake";
    private static final String DEVICE_EFFECT_DISABLE_TOUCH = "zdeDisableTouch";
    private static final String DEVICE_EFFECT_MINIMIZE_RADIO_USAGE = "zdeMinimizeRadioUsage";
    private static final String DEVICE_EFFECT_MAXIMIZE_DOZE = "zdeMaximizeDoze";
    private static final String DEVICE_EFFECT_USER_MODIFIED_FIELDS = "zdeUserModifiedFields";

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
    // Note that when the modes_api flag is true, the areChannelsBypassingDnd boolean only tracks
    // whether the current user has any priority channels. These channels may bypass DND when
    // allowPriorityChannels is true.
    // TODO: b/310620812 - Rename to be more accurate when modes_api flag is inlined.
    public boolean areChannelsBypassingDnd = DEFAULT_CHANNELS_BYPASSING_DND;
    public boolean allowPriorityChannels = DEFAULT_ALLOW_PRIORITY_CHANNELS;
    public int version;

    public ZenRule manualRule;
    @UnsupportedAppUsage
    public ArrayMap<String, ZenRule> automaticRules = new ArrayMap<>();

    // Note: Map is *pkg|conditionId* (see deletedRuleKey()) -> ZenRule,
    // unlike automaticRules (which is id -> rule).
    public final ArrayMap<String, ZenRule> deletedRules = new ArrayMap<>();

    @UnsupportedAppUsage
    public ZenModeConfig() {
    }

    public ZenModeConfig(Parcel source) {
        allowCalls = source.readInt() == 1;
        allowRepeatCallers = source.readInt() == 1;
        allowMessages = source.readInt() == 1;
        allowReminders = source.readInt() == 1;
        allowEvents = source.readInt() == 1;
        allowCallsFrom = source.readInt();
        allowMessagesFrom = source.readInt();
        user = source.readInt();
        manualRule = source.readParcelable(null, ZenRule.class);
        readRulesFromParcel(automaticRules, source);
        if (Flags.modesApi()) {
            readRulesFromParcel(deletedRules, source);
        }
        allowAlarms = source.readInt() == 1;
        allowMedia = source.readInt() == 1;
        allowSystem = source.readInt() == 1;
        suppressedVisualEffects = source.readInt();
        areChannelsBypassingDnd = source.readInt() == 1;
        allowConversations = source.readBoolean();
        allowConversationsFrom = source.readInt();
        if (Flags.modesApi()) {
            allowPriorityChannels = source.readBoolean();
        }
    }

    private static void readRulesFromParcel(ArrayMap<String, ZenRule> ruleMap, Parcel source) {
        final int len = source.readInt();
        if (len > 0) {
            final String[] ids = new String[len];
            final ZenRule[] rules = new ZenRule[len];
            source.readStringArray(ids);
            source.readTypedArray(rules, ZenRule.CREATOR);
            for (int i = 0; i < len; i++) {
                ruleMap.put(ids[i], rules[i]);
            }
        }
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
        writeRulesToParcel(automaticRules, dest);
        if (Flags.modesApi()) {
            writeRulesToParcel(deletedRules, dest);
        }
        dest.writeInt(allowAlarms ? 1 : 0);
        dest.writeInt(allowMedia ? 1 : 0);
        dest.writeInt(allowSystem ? 1 : 0);
        dest.writeInt(suppressedVisualEffects);
        dest.writeInt(areChannelsBypassingDnd ? 1 : 0);
        dest.writeBoolean(allowConversations);
        dest.writeInt(allowConversationsFrom);
        if (Flags.modesApi()) {
            dest.writeBoolean(allowPriorityChannels);
        }
    }

    private static void writeRulesToParcel(ArrayMap<String, ZenRule> ruleMap, Parcel dest) {
        if (!ruleMap.isEmpty()) {
            final int len = ruleMap.size();
            final String[] ids = new String[len];
            final ZenRule[] rules = new ZenRule[len];
            for (int i = 0; i < len; i++) {
                ids[i] = ruleMap.keyAt(i);
                rules[i] = ruleMap.valueAt(i);
            }
            dest.writeInt(len);
            dest.writeStringArray(ids);
            dest.writeTypedArray(rules, 0);
        } else {
            dest.writeInt(0);
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(ZenModeConfig.class.getSimpleName()).append('[')
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
                .append(",suppressedVisualEffects=").append(suppressedVisualEffects);
        if (Flags.modesApi()) {
            sb.append(",hasPriorityChannels=").append(areChannelsBypassingDnd);
            sb.append(",allowPriorityChannels=").append(allowPriorityChannels);
        } else {
            sb.append(",areChannelsBypassingDnd=").append(areChannelsBypassingDnd);
        }
        sb.append(",\nautomaticRules=").append(rulesToString(automaticRules))
                .append(",\nmanualRule=").append(manualRule);
        if (Flags.modesApi()) {
            sb.append(",\ndeletedRules=").append(rulesToString(deletedRules));
        }
        return sb.append(']').toString();
    }

    private static String rulesToString(ArrayMap<String, ZenRule> ruleList) {
        if (ruleList.isEmpty()) {
            return "{}";
        }

        StringBuilder buffer = new StringBuilder(ruleList.size() * 28);
        buffer.append("{\n");
        for (int i = 0; i < ruleList.size(); i++) {
            if (i > 0) {
                buffer.append(",\n");
            }
            Object value = ruleList.valueAt(i);
            buffer.append(value);
        }
        buffer.append('}');
        return buffer.toString();
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
    public boolean equals(@Nullable Object o) {
        if (!(o instanceof ZenModeConfig)) return false;
        if (o == this) return true;
        final ZenModeConfig other = (ZenModeConfig) o;
        boolean eq = other.allowAlarms == allowAlarms
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
        if (Flags.modesApi()) {
            return eq
                    && Objects.equals(other.deletedRules, deletedRules)
                    && other.allowPriorityChannels == allowPriorityChannels;
        }
        return eq;
    }

    @Override
    public int hashCode() {
        if (Flags.modesApi()) {
            return Objects.hash(allowAlarms, allowMedia, allowSystem, allowCalls,
                    allowRepeatCallers, allowMessages,
                    allowCallsFrom, allowMessagesFrom, allowReminders, allowEvents,
                    user, automaticRules, manualRule,
                    suppressedVisualEffects, areChannelsBypassingDnd, allowConversations,
                    allowConversationsFrom, allowPriorityChannels);
        }
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

    public static int getCurrentXmlVersion() {
        return Flags.modesApi() ? XML_VERSION_MODES_API : XML_VERSION;
    }

    public static ZenModeConfig readXml(TypedXmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type = parser.getEventType();
        if (type != XmlPullParser.START_TAG) return null;
        String tag = parser.getName();
        if (!ZEN_TAG.equals(tag)) return null;
        final ZenModeConfig rt = new ZenModeConfig();
        rt.version = safeInt(parser, ZEN_ATT_VERSION, getCurrentXmlVersion());
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
                    if (Flags.modesApi()) {
                        rt.allowPriorityChannels = safeBoolean(parser, ALLOW_ATT_CHANNELS,
                                DEFAULT_ALLOW_PRIORITY_CHANNELS);
                    }

                    // migrate old suppressed visual effects fields, if they still exist in the xml
                    Boolean allowWhenScreenOff = unsafeBoolean(parser, ALLOW_ATT_SCREEN_OFF);
                    Boolean allowWhenScreenOn = unsafeBoolean(parser, ALLOW_ATT_SCREEN_ON);
                    if (allowWhenScreenOff != null || allowWhenScreenOn != null) {
                        // If either setting exists, then reset the suppressed visual effects field
                        // to 0 (all allowed) so that only the relevant bits are disallowed by
                        // the migrated settings.
                        readSuppressedEffects = true;
                        rt.suppressedVisualEffects = 0;
                    }
                    if (allowWhenScreenOff != null) {
                        if (!allowWhenScreenOff) {
                            rt.suppressedVisualEffects |= SUPPRESSED_EFFECT_LIGHTS
                                    | SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
                                    | SUPPRESSED_EFFECT_AMBIENT;
                        }
                    }
                    if (allowWhenScreenOn != null) {
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
                } else if (AUTOMATIC_TAG.equals(tag)
                        || (Flags.modesApi() && AUTOMATIC_DELETED_TAG.equals(tag))) {
                    final String id = parser.getAttributeValue(null, RULE_ATT_ID);
                    final ZenRule automaticRule = readRuleXml(parser);
                    if (id != null && automaticRule != null) {
                        automaticRule.id = id;
                        if (Flags.modesApi() && AUTOMATIC_DELETED_TAG.equals(tag)) {
                            String deletedRuleKey = deletedRuleKey(automaticRule);
                            if (deletedRuleKey != null) {
                                rt.deletedRules.put(deletedRuleKey, automaticRule);
                            }
                        } else if (AUTOMATIC_TAG.equals(tag)) {
                            rt.automaticRules.put(id, automaticRule);
                        }
                    }
                } else if (STATE_TAG.equals(tag)) {
                    rt.areChannelsBypassingDnd = safeBoolean(parser,
                            STATE_ATT_CHANNELS_BYPASSING_DND, DEFAULT_CHANNELS_BYPASSING_DND);
                }
            }
        }
        throw new IllegalStateException("Failed to reach END_DOCUMENT");
    }

    /** Generates the map key used for a {@link ZenRule} in {@link #deletedRules}. */
    @Nullable
    public static String deletedRuleKey(ZenRule rule) {
        if (rule.pkg != null && rule.conditionId != null) {
            return rule.pkg + "|" + rule.conditionId.toString();
        } else {
            return null;
        }
    }

    /**
     * Writes XML of current ZenModeConfig
     * @param out serializer
     * @param version uses the current XML version if version is null
     * @throws IOException
     */

    public void writeXml(TypedXmlSerializer out, Integer version, boolean forBackup)
            throws IOException {
        int xmlVersion = getCurrentXmlVersion();
        out.startTag(null, ZEN_TAG);
        out.attribute(null, ZEN_ATT_VERSION, version == null
                ? Integer.toString(xmlVersion) : Integer.toString(version));
        out.attributeInt(null, ZEN_ATT_USER, user);
        out.startTag(null, ALLOW_TAG);
        out.attributeBoolean(null, ALLOW_ATT_CALLS, allowCalls);
        out.attributeBoolean(null, ALLOW_ATT_REPEAT_CALLERS, allowRepeatCallers);
        out.attributeBoolean(null, ALLOW_ATT_MESSAGES, allowMessages);
        out.attributeBoolean(null, ALLOW_ATT_REMINDERS, allowReminders);
        out.attributeBoolean(null, ALLOW_ATT_EVENTS, allowEvents);
        out.attributeInt(null, ALLOW_ATT_CALLS_FROM, allowCallsFrom);
        out.attributeInt(null, ALLOW_ATT_MESSAGES_FROM, allowMessagesFrom);
        out.attributeBoolean(null, ALLOW_ATT_ALARMS, allowAlarms);
        out.attributeBoolean(null, ALLOW_ATT_MEDIA, allowMedia);
        out.attributeBoolean(null, ALLOW_ATT_SYSTEM, allowSystem);
        out.attributeBoolean(null, ALLOW_ATT_CONV, allowConversations);
        out.attributeInt(null, ALLOW_ATT_CONV_FROM, allowConversationsFrom);
        if (Flags.modesApi()) {
            out.attributeBoolean(null, ALLOW_ATT_CHANNELS, allowPriorityChannels);
        }
        out.endTag(null, ALLOW_TAG);

        out.startTag(null, DISALLOW_TAG);
        out.attributeInt(null, DISALLOW_ATT_VISUAL_EFFECTS, suppressedVisualEffects);
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
        if (Flags.modesApi() && !forBackup) {
            for (int i = 0; i < deletedRules.size(); i++) {
                final ZenRule deletedRule = deletedRules.valueAt(i);
                out.startTag(null, AUTOMATIC_DELETED_TAG);
                out.attribute(null, RULE_ATT_ID, deletedRule.id);
                writeRuleXml(deletedRule, out);
                out.endTag(null, AUTOMATIC_DELETED_TAG);
            }
        }

        out.startTag(null, STATE_TAG);
        out.attributeBoolean(null, STATE_ATT_CHANNELS_BYPASSING_DND, areChannelsBypassingDnd);
        out.endTag(null, STATE_TAG);

        out.endTag(null, ZEN_TAG);
    }

    public static ZenRule readRuleXml(TypedXmlPullParser parser) {
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
        rt.pkg = XmlUtils.readStringAttribute(parser, RULE_ATT_PKG);
        if (rt.pkg == null) {
            // backfill from component, if present. configActivity is not safe to backfill from
            rt.pkg = rt.component != null ? rt.component.getPackageName() : null;
        }
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
        if (Flags.modesApi()) {
            rt.zenDeviceEffects = readZenDeviceEffectsXml(parser);
            rt.allowManualInvocation = safeBoolean(parser, RULE_ATT_ALLOW_MANUAL, false);
            rt.iconResName = parser.getAttributeValue(null, RULE_ATT_ICON);
            rt.triggerDescription = parser.getAttributeValue(null, RULE_ATT_TRIGGER_DESC);
            rt.type = safeInt(parser, RULE_ATT_TYPE, AutomaticZenRule.TYPE_UNKNOWN);
            rt.userModifiedFields = safeInt(parser, RULE_ATT_USER_MODIFIED_FIELDS, 0);
            rt.zenPolicyUserModifiedFields = safeInt(parser, POLICY_USER_MODIFIED_FIELDS, 0);
            rt.zenDeviceEffectsUserModifiedFields = safeInt(parser,
                    DEVICE_EFFECT_USER_MODIFIED_FIELDS, 0);
            Long deletionInstant = tryParseLong(
                    parser.getAttributeValue(null, RULE_ATT_DELETION_INSTANT), null);
            if (deletionInstant != null) {
                rt.deletionInstant = Instant.ofEpochMilli(deletionInstant);
            }
        }
        return rt;
    }

    public static void writeRuleXml(ZenRule rule, TypedXmlSerializer out) throws IOException {
        out.attributeBoolean(null, RULE_ATT_ENABLED, rule.enabled);
        if (rule.name != null) {
            out.attribute(null, RULE_ATT_NAME, rule.name);
        }
        out.attributeInt(null, RULE_ATT_ZEN, rule.zenMode);
        if (rule.pkg != null) {
            out.attribute(null, RULE_ATT_PKG, rule.pkg);
        }
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
        out.attributeLong(null, RULE_ATT_CREATION_TIME, rule.creationTime);
        if (rule.enabler != null) {
            out.attribute(null, RULE_ATT_ENABLER, rule.enabler);
        }
        if (rule.condition != null) {
            writeConditionXml(rule.condition, out);
        }
        if (rule.zenPolicy != null) {
            writeZenPolicyXml(rule.zenPolicy, out);
        }
        if (Flags.modesApi() && rule.zenDeviceEffects != null) {
            writeZenDeviceEffectsXml(rule.zenDeviceEffects, out);
        }
        out.attributeBoolean(null, RULE_ATT_MODIFIED, rule.modified);
        if (Flags.modesApi()) {
            out.attributeBoolean(null, RULE_ATT_ALLOW_MANUAL, rule.allowManualInvocation);
            if (rule.iconResName != null) {
                out.attribute(null, RULE_ATT_ICON, rule.iconResName);
            }
            if (rule.triggerDescription != null) {
                out.attribute(null, RULE_ATT_TRIGGER_DESC, rule.triggerDescription);
            }
            out.attributeInt(null, RULE_ATT_TYPE, rule.type);
            out.attributeInt(null, RULE_ATT_USER_MODIFIED_FIELDS, rule.userModifiedFields);
            out.attributeInt(null, POLICY_USER_MODIFIED_FIELDS, rule.zenPolicyUserModifiedFields);
            out.attributeInt(null, DEVICE_EFFECT_USER_MODIFIED_FIELDS,
                    rule.zenDeviceEffectsUserModifiedFields);
            if (rule.deletionInstant != null) {
                out.attributeLong(null, RULE_ATT_DELETION_INSTANT,
                        rule.deletionInstant.toEpochMilli());
            }
        }
    }

    public static Condition readConditionXml(TypedXmlPullParser parser) {
        final Uri id = safeUri(parser, CONDITION_ATT_ID);
        if (id == null) return null;
        final String summary = parser.getAttributeValue(null, CONDITION_ATT_SUMMARY);
        final String line1 = parser.getAttributeValue(null, CONDITION_ATT_LINE1);
        final String line2 = parser.getAttributeValue(null, CONDITION_ATT_LINE2);
        final int icon = safeInt(parser, CONDITION_ATT_ICON, -1);
        final int state = safeInt(parser, CONDITION_ATT_STATE, -1);
        final int flags = safeInt(parser, CONDITION_ATT_FLAGS, -1);
        try {
            if (Flags.modesApi()) {
                final int source = safeInt(parser, CONDITION_ATT_SOURCE, Condition.SOURCE_UNKNOWN);
                return new Condition(id, summary, line1, line2, icon, state, source, flags);
            } else {
                return new Condition(id, summary, line1, line2, icon, state, flags);
            }
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Unable to read condition xml", e);
            return null;
        }
    }

    public static void writeConditionXml(Condition c, TypedXmlSerializer out) throws IOException {
        out.attribute(null, CONDITION_ATT_ID, c.id.toString());
        out.attribute(null, CONDITION_ATT_SUMMARY, c.summary);
        out.attribute(null, CONDITION_ATT_LINE1, c.line1);
        out.attribute(null, CONDITION_ATT_LINE2, c.line2);
        out.attributeInt(null, CONDITION_ATT_ICON, c.icon);
        out.attributeInt(null, CONDITION_ATT_STATE, c.state);
        if (Flags.modesApi()) {
            out.attributeInt(null, CONDITION_ATT_SOURCE, c.source);
        }
        out.attributeInt(null, CONDITION_ATT_FLAGS, c.flags);
    }

    /**
     * Read the zen policy from xml
     * Returns null if no zen policy exists
     */
    public static ZenPolicy readZenPolicyXml(TypedXmlPullParser parser) {
        boolean policySet = false;

        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        final int calls = safeInt(parser, ALLOW_ATT_CALLS_FROM, ZenPolicy.PEOPLE_TYPE_UNSET);
        final int messages = safeInt(parser, ALLOW_ATT_MESSAGES_FROM, ZenPolicy.PEOPLE_TYPE_UNSET);
        final int repeatCallers = safeInt(parser, ALLOW_ATT_REPEAT_CALLERS, ZenPolicy.STATE_UNSET);
        final int conversations = safeInt(parser, ALLOW_ATT_CONV_FROM,
                ZenPolicy.CONVERSATION_SENDERS_UNSET);
        final int alarms = safeInt(parser, ALLOW_ATT_ALARMS, ZenPolicy.STATE_UNSET);
        final int media = safeInt(parser, ALLOW_ATT_MEDIA, ZenPolicy.STATE_UNSET);
        final int system = safeInt(parser, ALLOW_ATT_SYSTEM, ZenPolicy.STATE_UNSET);
        final int events = safeInt(parser, ALLOW_ATT_EVENTS, ZenPolicy.STATE_UNSET);
        final int reminders = safeInt(parser, ALLOW_ATT_REMINDERS, ZenPolicy.STATE_UNSET);
        if (Flags.modesApi()) {
            final int channels = safeInt(parser, ALLOW_ATT_CHANNELS, ZenPolicy.STATE_UNSET);
            if (channels != ZenPolicy.STATE_UNSET) {
                builder.allowPriorityChannels(channels == ZenPolicy.STATE_ALLOW);
                policySet = true;
            }
        }

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
        if (conversations != ZenPolicy.CONVERSATION_SENDERS_UNSET) {
            builder.allowConversations(conversations);
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
    public static void writeZenPolicyXml(ZenPolicy policy, TypedXmlSerializer out)
            throws IOException {
        writeZenPolicyState(ALLOW_ATT_CALLS_FROM, policy.getPriorityCallSenders(), out);
        writeZenPolicyState(ALLOW_ATT_MESSAGES_FROM, policy.getPriorityMessageSenders(), out);
        writeZenPolicyState(ALLOW_ATT_REPEAT_CALLERS, policy.getPriorityCategoryRepeatCallers(),
                out);
        writeZenPolicyState(ALLOW_ATT_CONV_FROM, policy.getPriorityConversationSenders(), out);
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

        if (Flags.modesApi()) {
            writeZenPolicyState(ALLOW_ATT_CHANNELS, policy.getPriorityChannelsAllowed(), out);
        }
    }

    private static void writeZenPolicyState(String attr, int val, TypedXmlSerializer out)
            throws IOException {
        if (Objects.equals(attr, ALLOW_ATT_CALLS_FROM)
                || Objects.equals(attr, ALLOW_ATT_MESSAGES_FROM)) {
            if (val != ZenPolicy.PEOPLE_TYPE_UNSET) {
                out.attributeInt(null, attr, val);
            }
        } else if (Objects.equals(attr, ALLOW_ATT_CONV_FROM)) {
            if (val != ZenPolicy.CONVERSATION_SENDERS_UNSET) {
                out.attributeInt(null, attr, val);
            }
        } else if (Flags.modesApi() && Objects.equals(attr, ALLOW_ATT_CHANNELS)) {
            if (val != ZenPolicy.STATE_UNSET) {
                out.attributeInt(null, attr, val);
            }
        } else {
            if (val != ZenPolicy.STATE_UNSET) {
                out.attributeInt(null, attr, val);
            }
        }
    }

    @FlaggedApi(Flags.FLAG_MODES_API)
    @Nullable
    private static ZenDeviceEffects readZenDeviceEffectsXml(TypedXmlPullParser parser) {
        ZenDeviceEffects deviceEffects = new ZenDeviceEffects.Builder()
                .setShouldDisplayGrayscale(
                        safeBoolean(parser, DEVICE_EFFECT_DISPLAY_GRAYSCALE, false))
                .setShouldSuppressAmbientDisplay(
                        safeBoolean(parser, DEVICE_EFFECT_SUPPRESS_AMBIENT_DISPLAY, false))
                .setShouldDimWallpaper(safeBoolean(parser, DEVICE_EFFECT_DIM_WALLPAPER, false))
                .setShouldUseNightMode(safeBoolean(parser, DEVICE_EFFECT_USE_NIGHT_MODE, false))
                .setShouldDisableAutoBrightness(
                        safeBoolean(parser, DEVICE_EFFECT_DISABLE_AUTO_BRIGHTNESS, false))
                .setShouldDisableTapToWake(
                        safeBoolean(parser, DEVICE_EFFECT_DISABLE_TAP_TO_WAKE, false))
                .setShouldDisableTiltToWake(
                        safeBoolean(parser, DEVICE_EFFECT_DISABLE_TILT_TO_WAKE, false))
                .setShouldDisableTouch(safeBoolean(parser, DEVICE_EFFECT_DISABLE_TOUCH, false))
                .setShouldMinimizeRadioUsage(
                        safeBoolean(parser, DEVICE_EFFECT_MINIMIZE_RADIO_USAGE, false))
                .setShouldMaximizeDoze(safeBoolean(parser, DEVICE_EFFECT_MAXIMIZE_DOZE, false))
                .build();

        return deviceEffects.hasEffects() ? deviceEffects : null;
    }

    @FlaggedApi(Flags.FLAG_MODES_API)
    private static void writeZenDeviceEffectsXml(ZenDeviceEffects deviceEffects,
            TypedXmlSerializer out) throws IOException {
        writeBooleanIfTrue(out, DEVICE_EFFECT_DISPLAY_GRAYSCALE,
                deviceEffects.shouldDisplayGrayscale());
        writeBooleanIfTrue(out, DEVICE_EFFECT_SUPPRESS_AMBIENT_DISPLAY,
                deviceEffects.shouldSuppressAmbientDisplay());
        writeBooleanIfTrue(out, DEVICE_EFFECT_DIM_WALLPAPER, deviceEffects.shouldDimWallpaper());
        writeBooleanIfTrue(out, DEVICE_EFFECT_USE_NIGHT_MODE, deviceEffects.shouldUseNightMode());
        writeBooleanIfTrue(out, DEVICE_EFFECT_DISABLE_AUTO_BRIGHTNESS,
                deviceEffects.shouldDisableAutoBrightness());
        writeBooleanIfTrue(out, DEVICE_EFFECT_DISABLE_TAP_TO_WAKE,
                deviceEffects.shouldDisableTapToWake());
        writeBooleanIfTrue(out, DEVICE_EFFECT_DISABLE_TILT_TO_WAKE,
                deviceEffects.shouldDisableTiltToWake());
        writeBooleanIfTrue(out, DEVICE_EFFECT_DISABLE_TOUCH, deviceEffects.shouldDisableTouch());
        writeBooleanIfTrue(out, DEVICE_EFFECT_MINIMIZE_RADIO_USAGE,
                deviceEffects.shouldMinimizeRadioUsage());
        writeBooleanIfTrue(out, DEVICE_EFFECT_MAXIMIZE_DOZE, deviceEffects.shouldMaximizeDoze());
    }

    private static void writeBooleanIfTrue(TypedXmlSerializer out, String att, boolean value)
            throws IOException {
        if (value) {
            out.attributeBoolean(null, att, true);
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

    private static Boolean unsafeBoolean(TypedXmlPullParser parser, String att) {
        try {
            return parser.getAttributeBoolean(null, att);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean safeBoolean(TypedXmlPullParser parser, String att, boolean defValue) {
        return parser.getAttributeBoolean(null, att, defValue);
    }

    private static boolean safeBoolean(String val, boolean defValue) {
        if (TextUtils.isEmpty(val)) return defValue;
        return Boolean.parseBoolean(val);
    }

    private static int safeInt(TypedXmlPullParser parser, String att, int defValue) {
        return parser.getAttributeInt(null, att, defValue);
    }

    private static ComponentName safeComponentName(TypedXmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        if (TextUtils.isEmpty(val)) return null;
        return ComponentName.unflattenFromString(val);
    }

    private static Uri safeUri(TypedXmlPullParser parser, String att) {
        final String val = parser.getAttributeValue(null, att);
        if (val == null) return null;
        return Uri.parse(val);
    }

    private static long safeLong(TypedXmlPullParser parser, String att, long defValue) {
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
     * Converts a ZenModeConfig to a ZenPolicy
     */
    public ZenPolicy toZenPolicy() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder()
                .allowCalls(allowCalls
                        ? ZenModeConfig.getZenPolicySenders(allowCallsFrom)
                        : ZenPolicy.PEOPLE_TYPE_NONE)
                .allowRepeatCallers(allowRepeatCallers)
                .allowMessages(allowMessages
                        ? ZenModeConfig.getZenPolicySenders(allowMessagesFrom)
                        : ZenPolicy.PEOPLE_TYPE_NONE)
                .allowReminders(allowReminders)
                .allowEvents(allowEvents)
                .allowAlarms(allowAlarms)
                .allowMedia(allowMedia)
                .allowSystem(allowSystem)
                .allowConversations(allowConversations ? allowConversationsFrom
                        : ZenPolicy.CONVERSATION_SENDERS_NONE);
        if (suppressedVisualEffects == 0) {
            builder.showAllVisualEffects();
        } else {
            // configs don't have an unset state: wither true or false.
            builder.showFullScreenIntent(
                    (suppressedVisualEffects & Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT) == 0);
            builder.showLights(
                    (suppressedVisualEffects & SUPPRESSED_EFFECT_LIGHTS) == 0);
            builder.showPeeking(
                    (suppressedVisualEffects & SUPPRESSED_EFFECT_PEEK) == 0);
            builder.showStatusBarIcons(
                    (suppressedVisualEffects & Policy.SUPPRESSED_EFFECT_STATUS_BAR) == 0);
            builder.showBadges(
                    (suppressedVisualEffects & Policy.SUPPRESSED_EFFECT_BADGE) == 0);
            builder.showInAmbientDisplay(
                    (suppressedVisualEffects & SUPPRESSED_EFFECT_AMBIENT) == 0);
            builder.showInNotificationList(
                    (suppressedVisualEffects & Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST) == 0);
        }

        if (Flags.modesApi()) {
            builder.allowPriorityChannels(allowPriorityChannels);
        }
        return builder.build();
    }

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
            conversationSenders = getConversationSendersWithDefault(
                    zenPolicy.getPriorityConversationSenders(), conversationSenders);
        } else {
            conversationSenders = CONVERSATION_SENDERS_NONE;
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
                isVisualEffectAllowed(SUPPRESSED_EFFECT_AMBIENT,
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
            suppressedVisualEffects |= SUPPRESSED_EFFECT_AMBIENT;
        }

        if (!zenPolicy.isVisualEffectAllowed(ZenPolicy.VISUAL_EFFECT_NOTIFICATION_LIST,
                isVisualEffectAllowed(Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST,
                        defaultPolicy))) {
            suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST;
        }

        int state = defaultPolicy.state;
        if (Flags.modesApi()) {
            state = Policy.policyState(defaultPolicy.hasPriorityChannels(),
                    ZenPolicy.stateToBoolean(zenPolicy.getPriorityChannelsAllowed(),
                            DEFAULT_ALLOW_PRIORITY_CHANNELS));
        }

        return new NotificationManager.Policy(priorityCategories, callSenders,
                messageSenders, suppressedVisualEffects, state, conversationSenders);
    }

    private boolean isPriorityCategoryEnabled(int categoryType, Policy policy) {
        return (policy.priorityCategories & categoryType) != 0;
    }

    private boolean isVisualEffectAllowed(int visualEffect, Policy policy) {
        return (policy.suppressedVisualEffects & visualEffect) == 0;
    }

    private static int getNotificationPolicySenders(@ZenPolicy.PeopleType int senders,
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

    private static int getConversationSendersWithDefault(@ZenPolicy.ConversationSenders int senders,
            int defaultPolicySender) {
        switch (senders) {
            case ZenPolicy.CONVERSATION_SENDERS_ANYONE:
            case ZenPolicy.CONVERSATION_SENDERS_IMPORTANT:
            case ZenPolicy.CONVERSATION_SENDERS_NONE:
                return senders;
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
        priorityConversationSenders = getConversationSendersWithDefault(
                allowConversationsFrom, priorityConversationSenders);

        int state = areChannelsBypassingDnd ? Policy.STATE_CHANNELS_BYPASSING_DND : 0;
        if (Flags.modesApi()) {
            state = Policy.policyState(areChannelsBypassingDnd, allowPriorityChannels);
        }

        return new Policy(priorityCategories, priorityCallSenders, priorityMessageSenders,
                suppressedVisualEffects, state, priorityConversationSenders);
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
            if (Flags.modesApi()) {
                allowPriorityChannels = policy.allowPriorityChannels();
            }
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
        final Map<String, Object> arguments = new HashMap<>();
        if (minutes < 60) {
            // display as minutes
            num = minutes;
            int summaryResId = shortVersion ? R.string.zen_mode_duration_minutes_summary_short
                    : R.string.zen_mode_duration_minutes_summary;
            arguments.put("count", num);
            arguments.put("formattedTime", formattedTime);
            summary = PluralsMessageFormatter.format(res, arguments, summaryResId);
            int line1ResId = shortVersion ? R.string.zen_mode_duration_minutes_short
                    : R.string.zen_mode_duration_minutes;
            line1 = PluralsMessageFormatter.format(res, arguments, line1ResId);
            line2 = res.getString(R.string.zen_mode_until, formattedTime);
        } else if (minutes < DAY_MINUTES) {
            // display as hours
            num =  Math.round(minutes / 60f);
            int summaryResId = shortVersion ? R.string.zen_mode_duration_hours_summary_short
                    : R.string.zen_mode_duration_hours_summary;
            arguments.put("count", num);
            arguments.put("formattedTime", formattedTime);
            summary = PluralsMessageFormatter.format(res, arguments, summaryResId);
            int line1ResId = shortVersion ? R.string.zen_mode_duration_hours_short
                    : R.string.zen_mode_duration_hours;
            line1 = PluralsMessageFormatter.format(res, arguments, line1ResId);
            line2 = res.getString(R.string.zen_mode_until, formattedTime);
        } else {
            // display as day/time
            summary = line1 = line2 = res.getString(R.string.zen_mode_until_next_day,
                    formattedTime);
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
        public boolean equals(@Nullable Object o) {
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
        public boolean equals(@Nullable Object o) {
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
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public boolean snoozing;         // user manually disabled this instance
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public String name;              // required for automatic
        @UnsupportedAppUsage
        public int zenMode;             // ie: Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
        @UnsupportedAppUsage
        public Uri conditionId;          // required for automatic
        public Condition condition;      // optional
        public ComponentName component;  // optional
        public ComponentName configurationActivity; // optional
        public String id;                // required for automatic (unique)
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long creationTime;        // required for automatic
        // package name, only used for manual rules when they have turned DND on.
        public String enabler;
        public ZenPolicy zenPolicy;
        @FlaggedApi(Flags.FLAG_MODES_API)
        @Nullable public ZenDeviceEffects zenDeviceEffects;
        public boolean modified;    // rule has been modified from initial creation
        public String pkg;
        @AutomaticZenRule.Type
        public int type = AutomaticZenRule.TYPE_UNKNOWN;
        public String triggerDescription;
        public String iconResName;
        public boolean allowManualInvocation;
        @AutomaticZenRule.ModifiableField public int userModifiedFields;
        @ZenPolicy.ModifiableField public int zenPolicyUserModifiedFields;
        @ZenDeviceEffects.ModifiableField public int zenDeviceEffectsUserModifiedFields;
        @Nullable public Instant deletionInstant; // Only set on deleted rules.

        public ZenRule() { }

        public ZenRule(Parcel source) {
            enabled = source.readInt() == 1;
            snoozing = source.readInt() == 1;
            if (source.readInt() == 1) {
                name = source.readString();
            }
            zenMode = source.readInt();
            conditionId = source.readParcelable(null, android.net.Uri.class);
            condition = source.readParcelable(null, android.service.notification.Condition.class);
            component = source.readParcelable(null, android.content.ComponentName.class);
            configurationActivity = source.readParcelable(null, android.content.ComponentName.class);
            if (source.readInt() == 1) {
                id = source.readString();
            }
            creationTime = source.readLong();
            if (source.readInt() == 1) {
                enabler = source.readString();
            }
            zenPolicy = source.readParcelable(null, android.service.notification.ZenPolicy.class);
            if (Flags.modesApi()) {
                zenDeviceEffects = source.readParcelable(null, ZenDeviceEffects.class);
            }
            modified = source.readInt() == 1;
            pkg = source.readString();
            if (Flags.modesApi()) {
                allowManualInvocation = source.readBoolean();
                iconResName = source.readString();
                triggerDescription = source.readString();
                type = source.readInt();
                userModifiedFields = source.readInt();
                zenPolicyUserModifiedFields = source.readInt();
                zenDeviceEffectsUserModifiedFields = source.readInt();
                if (source.readInt() == 1) {
                    deletionInstant = Instant.ofEpochMilli(source.readLong());
                }
            }
        }

        /**
         * Whether this ZenRule can be updated by an app. In general, rules that have been
         * customized by the user cannot be further updated by an app, with some exceptions:
         * <ul>
         *     <li>Non user-configurable fields, like type, icon, configurationActivity, etc.
         *     <li>Name, if the name was not specifically modified by the user (to support language
         *          switches).
         * </ul>
         */
        @FlaggedApi(Flags.FLAG_MODES_API)
        public boolean canBeUpdatedByApp() {
            // The rule is considered updateable if its bitmask has no user modifications, and
            // the bitmasks of the policy and device effects have no modification.
            return userModifiedFields == 0
                    && zenPolicyUserModifiedFields == 0
                    && zenDeviceEffectsUserModifiedFields == 0;
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
            if (Flags.modesApi()) {
                dest.writeParcelable(zenDeviceEffects, 0);
            }
            dest.writeInt(modified ? 1 : 0);
            dest.writeString(pkg);
            if (Flags.modesApi()) {
                dest.writeBoolean(allowManualInvocation);
                dest.writeString(iconResName);
                dest.writeString(triggerDescription);
                dest.writeInt(type);
                dest.writeInt(userModifiedFields);
                dest.writeInt(zenPolicyUserModifiedFields);
                dest.writeInt(zenDeviceEffectsUserModifiedFields);
                if (deletionInstant != null) {
                    dest.writeInt(1);
                    dest.writeLong(deletionInstant.toEpochMilli());
                } else {
                    dest.writeInt(0);
                }
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(ZenRule.class.getSimpleName()).append('[')
                    .append("id=").append(id)
                    .append(",state=").append(condition == null ? "STATE_FALSE"
                            : Condition.stateToString(condition.state))
                    .append(",enabled=").append(String.valueOf(enabled).toUpperCase())
                    .append(",snoozing=").append(snoozing)
                    .append(",name=").append(name)
                    .append(",zenMode=").append(Global.zenModeToString(zenMode))
                    .append(",conditionId=").append(conditionId)
                    .append(",pkg=").append(pkg)
                    .append(",component=").append(component)
                    .append(",configActivity=").append(configurationActivity)
                    .append(",creationTime=").append(creationTime)
                    .append(",enabler=").append(enabler)
                    .append(",zenPolicy=").append(zenPolicy)
                    .append(",modified=").append(modified)
                    .append(",condition=").append(condition);

            if (Flags.modesApi()) {
                sb.append(",deviceEffects=").append(zenDeviceEffects)
                        .append(",allowManualInvocation=").append(allowManualInvocation)
                        .append(",iconResName=").append(iconResName)
                        .append(",triggerDescription=").append(triggerDescription)
                        .append(",type=").append(type);
                if (userModifiedFields != 0) {
                    sb.append(",userModifiedFields=")
                            .append(AutomaticZenRule.fieldsToString(userModifiedFields));
                }
                if (zenPolicyUserModifiedFields != 0) {
                    sb.append(",zenPolicyUserModifiedFields=")
                            .append(ZenPolicy.fieldsToString(zenPolicyUserModifiedFields));
                }
                if (zenDeviceEffectsUserModifiedFields != 0) {
                    sb.append(",zenDeviceEffectsUserModifiedFields=")
                            .append(ZenDeviceEffects.fieldsToString(
                                    zenDeviceEffectsUserModifiedFields));
                }
                if (deletionInstant != null) {
                    sb.append(",deletionInstant=").append(deletionInstant);
                }
            }

            return sb.append(']').toString();
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

        @Override
        public boolean equals(@Nullable Object o) {
            if (!(o instanceof ZenRule)) return false;
            if (o == this) return true;
            final ZenRule other = (ZenRule) o;
            boolean finalEquals = other.enabled == enabled
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

            if (Flags.modesApi()) {
                return finalEquals
                        && Objects.equals(other.zenDeviceEffects, zenDeviceEffects)
                        && other.allowManualInvocation == allowManualInvocation
                        && Objects.equals(other.iconResName, iconResName)
                        && Objects.equals(other.triggerDescription, triggerDescription)
                        && other.type == type
                        && other.userModifiedFields == userModifiedFields
                        && other.zenPolicyUserModifiedFields == zenPolicyUserModifiedFields
                        && other.zenDeviceEffectsUserModifiedFields
                            == zenDeviceEffectsUserModifiedFields
                        && Objects.equals(other.deletionInstant, deletionInstant);
            }

            return finalEquals;
        }

        @Override
        public int hashCode() {
            if (Flags.modesApi()) {
                return Objects.hash(enabled, snoozing, name, zenMode, conditionId, condition,
                        component, configurationActivity, pkg, id, enabler, zenPolicy,
                        zenDeviceEffects, modified, allowManualInvocation, iconResName,
                        triggerDescription, type, userModifiedFields, zenPolicyUserModifiedFields,
                        zenDeviceEffectsUserModifiedFields, deletionInstant);
            }
            return Objects.hash(enabled, snoozing, name, zenMode, conditionId, condition,
                    component, configurationActivity, pkg, id, enabler, zenPolicy, modified);
        }

        /** Returns a deep copy of the {@link ZenRule}. */
        public ZenRule copy() {
            final Parcel parcel = Parcel.obtain();
            try {
                writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                return new ZenRule(parcel);
            } finally {
                parcel.recycle();
            }
        }

        public boolean isAutomaticActive() {
            return enabled && !snoozing && getPkg() != null && isTrueOrUnknown();
        }

        public String getPkg() {
            return !TextUtils.isEmpty(pkg)
                    ? pkg
                    : (component != null)
                            ? component.getPackageName()
                            : (configurationActivity != null)
                                    ? configurationActivity.getPackageName()
                                    : null;
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
        if (Flags.modesApi()) {
            areChannelsBypassingDnd = policy.hasPriorityChannels()
                    && policy.allowPriorityChannels();
        }
        boolean allowSystem = (policy.priorityCategories & Policy.PRIORITY_CATEGORY_SYSTEM) != 0;
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
        boolean areChannelsBypassingDnd = config.areChannelsBypassingDnd;
        if (Flags.modesApi()) {
            areChannelsBypassingDnd = config.areChannelsBypassingDnd
                    && config.allowPriorityChannels;
        }
        return !config.allowReminders && !config.allowCalls && !config.allowMessages
                && !config.allowEvents && !config.allowRepeatCallers
                && !areChannelsBypassingDnd && !config.allowSystem;
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
