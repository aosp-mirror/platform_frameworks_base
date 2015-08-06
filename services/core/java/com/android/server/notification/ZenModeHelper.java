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

package com.android.server.notification;

import static android.media.AudioAttributes.USAGE_ALARM;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;
import static android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE;

import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.VolumePolicy;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.service.notification.IConditionListener;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.EventInfo;
import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.service.notification.ZenModeConfig.ZenRule;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.logging.MetricsLogger;
import com.android.server.LocalServices;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;

/**
 * NotificationManagerService helper for functionality related to zen mode.
 */
public class ZenModeHelper {
    static final String TAG = "ZenModeHelper";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final H mHandler;
    private final SettingsObserver mSettingsObserver;
    private final AppOpsManager mAppOps;
    private final ZenModeConfig mDefaultConfig;
    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();
    private final ZenModeFiltering mFiltering;
    private final RingerModeDelegate mRingerModeDelegate = new RingerModeDelegate();
    private final ZenModeConditions mConditions;
    private final SparseArray<ZenModeConfig> mConfigs = new SparseArray<>();
    private final Metrics mMetrics = new Metrics();

    private int mZenMode;
    private int mUser = UserHandle.USER_OWNER;
    private ZenModeConfig mConfig;
    private AudioManagerInternal mAudioManager;
    private boolean mEffectsSuppressed;

    public ZenModeHelper(Context context, Looper looper, ConditionProviders conditionProviders) {
        mContext = context;
        mHandler = new H(looper);
        addCallback(mMetrics);
        mAppOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        mDefaultConfig = readDefaultConfig(context.getResources());
        appendDefaultScheduleRules(mDefaultConfig);
        appendDefaultEventRules(mDefaultConfig);
        mConfig = mDefaultConfig;
        mConfigs.put(UserHandle.USER_OWNER, mConfig);
        mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
        mFiltering = new ZenModeFiltering(mContext);
        mConditions = new ZenModeConditions(this, conditionProviders);
    }

    public Looper getLooper() {
        return mHandler.getLooper();
    }

    @Override
    public String toString() {
        return TAG;
    }

    public boolean matchesCallFilter(UserHandle userHandle, Bundle extras,
            ValidateNotificationPeople validator, int contactsTimeoutMs, float timeoutAffinity) {
        return ZenModeFiltering.matchesCallFilter(mContext, mZenMode, mConfig, userHandle, extras,
                validator, contactsTimeoutMs, timeoutAffinity);
    }

    public boolean isCall(NotificationRecord record) {
        return mFiltering.isCall(record);
    }

    public boolean shouldIntercept(NotificationRecord record) {
        return mFiltering.shouldIntercept(mZenMode, mConfig, record);
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    public void initZenMode() {
        if (DEBUG) Log.d(TAG, "initZenMode");
        evaluateZenMode("init", true /*setRingerMode*/);
    }

    public void onSystemReady() {
        if (DEBUG) Log.d(TAG, "onSystemReady");
        mAudioManager = LocalServices.getService(AudioManagerInternal.class);
        if (mAudioManager != null) {
            mAudioManager.setRingerModeDelegate(mRingerModeDelegate);
        }
        mHandler.postMetricsTimer();
    }

    public void onUserSwitched(int user) {
        if (mUser == user || user < UserHandle.USER_OWNER) return;
        mUser = user;
        if (DEBUG) Log.d(TAG, "onUserSwitched u=" + user);
        ZenModeConfig config = mConfigs.get(user);
        if (config == null) {
            if (DEBUG) Log.d(TAG, "onUserSwitched: generating default config for user " + user);
            config = mDefaultConfig.copy();
            config.user = user;
        }
        setConfig(config, "onUserSwitched");
    }

    public void onUserRemoved(int user) {
        if (user < UserHandle.USER_OWNER) return;
        if (DEBUG) Log.d(TAG, "onUserRemoved u=" + user);
        mConfigs.remove(user);
    }

    public void requestZenModeConditions(IConditionListener callback, int relevance) {
        mConditions.requestConditions(callback, relevance);
    }

    public int getZenModeListenerInterruptionFilter() {
        return NotificationManager.zenModeToInterruptionFilter(mZenMode);
    }

    public void requestFromListener(ComponentName name, int filter) {
        final int newZen = NotificationManager.zenModeFromInterruptionFilter(filter, -1);
        if (newZen != -1) {
            setManualZenMode(newZen, null,
                    "listener:" + (name != null ? name.flattenToShortString() : null));
        }
    }

    public void setEffectsSuppressed(boolean effectsSuppressed) {
        if (mEffectsSuppressed == effectsSuppressed) return;
        mEffectsSuppressed = effectsSuppressed;
        applyRestrictions();
    }

    public int getZenMode() {
        return mZenMode;
    }

    public void setManualZenMode(int zenMode, Uri conditionId, String reason) {
        setManualZenMode(zenMode, conditionId, reason, true /*setRingerMode*/);
    }

    private void setManualZenMode(int zenMode, Uri conditionId, String reason,
            boolean setRingerMode) {
        if (mConfig == null) return;
        if (!Global.isValidZenMode(zenMode)) return;
        if (DEBUG) Log.d(TAG, "setManualZenMode " + Global.zenModeToString(zenMode)
                + " conditionId=" + conditionId + " reason=" + reason
                + " setRingerMode=" + setRingerMode);
        final ZenModeConfig newConfig = mConfig.copy();
        if (zenMode == Global.ZEN_MODE_OFF) {
            newConfig.manualRule = null;
            for (ZenRule automaticRule : newConfig.automaticRules.values()) {
                if (automaticRule.isAutomaticActive()) {
                    automaticRule.snoozing = true;
                }
            }
        } else {
            final ZenRule newRule = new ZenRule();
            newRule.enabled = true;
            newRule.zenMode = zenMode;
            newRule.conditionId = conditionId;
            newConfig.manualRule = newRule;
        }
        setConfig(newConfig, reason, setRingerMode);
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.print(prefix); pw.print("mZenMode=");
        pw.println(Global.zenModeToString(mZenMode));
        dump(pw, prefix, "mDefaultConfig", mDefaultConfig);
        final int N = mConfigs.size();
        for (int i = 0; i < N; i++) {
            dump(pw, prefix, "mConfigs[u=" + mConfigs.keyAt(i) + "]", mConfigs.valueAt(i));
        }
        pw.print(prefix); pw.print("mUser="); pw.println(mUser);
        dump(pw, prefix, "mConfig", mConfig);
        pw.print(prefix); pw.print("mEffectsSuppressed="); pw.println(mEffectsSuppressed);
        mFiltering.dump(pw, prefix);
        mConditions.dump(pw, prefix);
    }

    private static void dump(PrintWriter pw, String prefix, String var, ZenModeConfig config) {
        pw.print(prefix); pw.print(var); pw.print('=');
        if (config == null) {
            pw.println(config);
            return;
        }
        pw.printf("allow(calls=%s,callsFrom=%s,repeatCallers=%s,messages=%s,messagesFrom=%s,"
                + "events=%s,reminders=%s)\n",
                config.allowCalls, config.allowCallsFrom, config.allowRepeatCallers,
                config.allowMessages, config.allowMessagesFrom,
                config.allowEvents, config.allowReminders);
        pw.print(prefix); pw.print("  manualRule="); pw.println(config.manualRule);
        if (config.automaticRules.isEmpty()) return;
        final int N = config.automaticRules.size();
        for (int i = 0; i < N; i++) {
            pw.print(prefix); pw.print(i == 0 ? "  automaticRules=" : "                 ");
            pw.println(config.automaticRules.valueAt(i));
        }
    }

    public void readXml(XmlPullParser parser, boolean forRestore)
            throws XmlPullParserException, IOException {
        final ZenModeConfig config = ZenModeConfig.readXml(parser, mConfigMigration);
        if (config != null) {
            if (forRestore) {
                if (config.user != UserHandle.USER_OWNER) {
                    return;
                }
                config.manualRule = null;  // don't restore the manual rule
                if (config.automaticRules != null) {
                    for (ZenModeConfig.ZenRule automaticRule : config.automaticRules.values()) {
                        // don't restore transient state from restored automatic rules
                        automaticRule.snoozing = false;
                        automaticRule.condition = null;
                    }
                }
            }
            if (DEBUG) Log.d(TAG, "readXml");
            setConfig(config, "readXml");
        }
    }

    public void writeXml(XmlSerializer out, boolean forBackup) throws IOException {
        final int N = mConfigs.size();
        for (int i = 0; i < N; i++) {
            if (forBackup && mConfigs.keyAt(i) != UserHandle.USER_OWNER) {
                continue;
            }
            mConfigs.valueAt(i).writeXml(out);
        }
    }

    public Policy getNotificationPolicy() {
        return getNotificationPolicy(mConfig);
    }

    private static Policy getNotificationPolicy(ZenModeConfig config) {
        return config == null ? null : config.toNotificationPolicy();
    }

    public void setNotificationPolicy(Policy policy) {
        if (policy == null || mConfig == null) return;
        final ZenModeConfig newConfig = mConfig.copy();
        newConfig.applyNotificationPolicy(policy);
        setConfig(newConfig, "setNotificationPolicy");
    }

    public ZenModeConfig getConfig() {
        return mConfig;
    }

    public boolean setConfig(ZenModeConfig config, String reason) {
        return setConfig(config, reason, true /*setRingerMode*/);
    }

    private boolean setConfig(ZenModeConfig config, String reason, boolean setRingerMode) {
        if (config == null || !config.isValid()) {
            Log.w(TAG, "Invalid config in setConfig; " + config);
            return false;
        }
        if (config.user != mUser) {
            // simply store away for background users
            mConfigs.put(config.user, config);
            if (DEBUG) Log.d(TAG, "setConfig: store config for user " + config.user);
            return true;
        }
        mConditions.evaluateConfig(config, false /*processSubscriptions*/);  // may modify config
        mConfigs.put(config.user, config);
        if (config.equals(mConfig)) return true;
        if (DEBUG) Log.d(TAG, "setConfig reason=" + reason, new Throwable());
        ZenLog.traceConfig(reason, mConfig, config);
        final boolean policyChanged = !Objects.equals(getNotificationPolicy(mConfig),
                getNotificationPolicy(config));
        mConfig = config;
        dispatchOnConfigChanged();
        if (policyChanged){
            dispatchOnPolicyChanged();
        }
        final String val = Integer.toString(mConfig.hashCode());
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_CONFIG_ETAG, val);
        if (!evaluateZenMode(reason, setRingerMode)) {
            applyRestrictions();  // evaluateZenMode will also apply restrictions if changed
        }
        mConditions.evaluateConfig(config, true /*processSubscriptions*/);
        return true;
    }

    private int getZenModeSetting() {
        return Global.getInt(mContext.getContentResolver(), Global.ZEN_MODE, Global.ZEN_MODE_OFF);
    }

    private void setZenModeSetting(int zen) {
        Global.putInt(mContext.getContentResolver(), Global.ZEN_MODE, zen);
    }

    private int getPreviousRingerModeSetting() {
        return Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL);
    }

    private void setPreviousRingerModeSetting(Integer previousRingerLevel) {
        Global.putString(
                mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                previousRingerLevel == null ? null : Integer.toString(previousRingerLevel));
    }

    private boolean evaluateZenMode(String reason, boolean setRingerMode) {
        if (DEBUG) Log.d(TAG, "evaluateZenMode");
        final ArraySet<ZenRule> automaticRules = new ArraySet<ZenRule>();
        final int zen = computeZenMode(automaticRules);
        if (zen == mZenMode) return false;
        ZenLog.traceSetZenMode(zen, reason);
        mZenMode = zen;
        updateRingerModeAffectedStreams();
        setZenModeSetting(mZenMode);
        if (setRingerMode) {
            applyZenToRingerMode();
        }
        applyRestrictions();
        mHandler.postDispatchOnZenModeChanged();
        return true;
    }

    private void updateRingerModeAffectedStreams() {
        if (mAudioManager != null) {
            mAudioManager.updateRingerModeAffectedStreamsInternal();
        }
    }

    private int computeZenMode(ArraySet<ZenRule> automaticRulesOut) {
        if (mConfig == null) return Global.ZEN_MODE_OFF;
        if (mConfig.manualRule != null) return mConfig.manualRule.zenMode;
        int zen = Global.ZEN_MODE_OFF;
        for (ZenRule automaticRule : mConfig.automaticRules.values()) {
            if (automaticRule.isAutomaticActive()) {
                if (zenSeverity(automaticRule.zenMode) > zenSeverity(zen)) {
                    zen = automaticRule.zenMode;
                }
            }
        }
        return zen;
    }

    private void applyRestrictions() {
        final boolean zen = mZenMode != Global.ZEN_MODE_OFF;

        // notification restrictions
        final boolean muteNotifications = mEffectsSuppressed;
        applyRestrictions(muteNotifications, USAGE_NOTIFICATION);

        // call restrictions
        final boolean muteCalls = zen && !mConfig.allowCalls && !mConfig.allowRepeatCallers
                || mEffectsSuppressed;
        applyRestrictions(muteCalls, USAGE_NOTIFICATION_RINGTONE);

        // alarm restrictions
        final boolean muteAlarms = mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS;
        applyRestrictions(muteAlarms, USAGE_ALARM);
    }

    private void applyRestrictions(boolean mute, int usage) {
        final String[] exceptionPackages = null; // none (for now)
        mAppOps.setRestriction(AppOpsManager.OP_VIBRATE, usage,
                mute ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);
        mAppOps.setRestriction(AppOpsManager.OP_PLAY_AUDIO, usage,
                mute ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED,
                exceptionPackages);
    }

    private void applyZenToRingerMode() {
        if (mAudioManager == null) return;
        // force the ringer mode into compliance
        final int ringerModeInternal = mAudioManager.getRingerModeInternal();
        int newRingerModeInternal = ringerModeInternal;
        switch (mZenMode) {
            case Global.ZEN_MODE_NO_INTERRUPTIONS:
            case Global.ZEN_MODE_ALARMS:
                if (ringerModeInternal != AudioManager.RINGER_MODE_SILENT) {
                    setPreviousRingerModeSetting(ringerModeInternal);
                    newRingerModeInternal = AudioManager.RINGER_MODE_SILENT;
                }
                break;
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
            case Global.ZEN_MODE_OFF:
                if (ringerModeInternal == AudioManager.RINGER_MODE_SILENT) {
                    newRingerModeInternal = getPreviousRingerModeSetting();
                    setPreviousRingerModeSetting(null);
                }
                break;
        }
        if (newRingerModeInternal != -1) {
            mAudioManager.setRingerModeInternal(newRingerModeInternal, TAG);
        }
    }

    private void dispatchOnConfigChanged() {
        for (Callback callback : mCallbacks) {
            callback.onConfigChanged();
        }
    }

    private void dispatchOnPolicyChanged() {
        for (Callback callback : mCallbacks) {
            callback.onPolicyChanged();
        }
    }

    private void dispatchOnZenModeChanged() {
        for (Callback callback : mCallbacks) {
            callback.onZenModeChanged();
        }
    }

    private ZenModeConfig readDefaultConfig(Resources resources) {
        XmlResourceParser parser = null;
        try {
            parser = resources.getXml(R.xml.default_zen_mode_config);
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                final ZenModeConfig config = ZenModeConfig.readXml(parser, mConfigMigration);
                if (config != null) return config;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error reading default zen mode config from resource", e);
        } finally {
            IoUtils.closeQuietly(parser);
        }
        return new ZenModeConfig();
    }

    private void appendDefaultScheduleRules(ZenModeConfig config) {
        if (config == null) return;

        final ScheduleInfo weeknights = new ScheduleInfo();
        weeknights.days = ZenModeConfig.WEEKNIGHT_DAYS;
        weeknights.startHour = 22;
        weeknights.endHour = 7;
        final ZenRule rule1 = new ZenRule();
        rule1.enabled = false;
        rule1.name = mContext.getResources()
                .getString(R.string.zen_mode_default_weeknights_name);
        rule1.conditionId = ZenModeConfig.toScheduleConditionId(weeknights);
        rule1.zenMode = Global.ZEN_MODE_ALARMS;
        config.automaticRules.put(config.newRuleId(), rule1);

        final ScheduleInfo weekends = new ScheduleInfo();
        weekends.days = ZenModeConfig.WEEKEND_DAYS;
        weekends.startHour = 23;
        weekends.startMinute = 30;
        weekends.endHour = 10;
        final ZenRule rule2 = new ZenRule();
        rule2.enabled = false;
        rule2.name = mContext.getResources()
                .getString(R.string.zen_mode_default_weekends_name);
        rule2.conditionId = ZenModeConfig.toScheduleConditionId(weekends);
        rule2.zenMode = Global.ZEN_MODE_ALARMS;
        config.automaticRules.put(config.newRuleId(), rule2);
    }

    private void appendDefaultEventRules(ZenModeConfig config) {
        if (config == null) return;

        final EventInfo events = new EventInfo();
        events.calendar = null; // any calendar
        events.reply = EventInfo.REPLY_YES_OR_MAYBE;
        final ZenRule rule = new ZenRule();
        rule.enabled = false;
        rule.name = mContext.getResources().getString(R.string.zen_mode_default_events_name);
        rule.conditionId = ZenModeConfig.toEventConditionId(events);
        rule.zenMode = Global.ZEN_MODE_ALARMS;
        config.automaticRules.put(config.newRuleId(), rule);
    }

    private static int zenSeverity(int zen) {
        switch (zen) {
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS: return 1;
            case Global.ZEN_MODE_ALARMS: return 2;
            case Global.ZEN_MODE_NO_INTERRUPTIONS: return 3;
            default: return 0;
        }
    }

    private final ZenModeConfig.Migration mConfigMigration = new ZenModeConfig.Migration() {
        @Override
        public ZenModeConfig migrate(ZenModeConfig.XmlV1 v1) {
            if (v1 == null) return null;
            final ZenModeConfig rt = new ZenModeConfig();
            rt.allowCalls = v1.allowCalls;
            rt.allowEvents = v1.allowEvents;
            rt.allowCallsFrom = v1.allowFrom;
            rt.allowMessages = v1.allowMessages;
            rt.allowMessagesFrom = v1.allowFrom;
            rt.allowReminders = v1.allowReminders;
            // don't migrate current exit condition
            final int[] days = ZenModeConfig.XmlV1.tryParseDays(v1.sleepMode);
            if (days != null && days.length > 0) {
                Log.i(TAG, "Migrating existing V1 downtime to single schedule");
                final ScheduleInfo schedule = new ScheduleInfo();
                schedule.days = days;
                schedule.startHour = v1.sleepStartHour;
                schedule.startMinute = v1.sleepStartMinute;
                schedule.endHour = v1.sleepEndHour;
                schedule.endMinute = v1.sleepEndMinute;
                final ZenRule rule = new ZenRule();
                rule.enabled = true;
                rule.name = mContext.getResources()
                        .getString(R.string.zen_mode_downtime_feature_name);
                rule.conditionId = ZenModeConfig.toScheduleConditionId(schedule);
                rule.zenMode = v1.sleepNone ? Global.ZEN_MODE_NO_INTERRUPTIONS
                        : Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;
                rt.automaticRules.put(rt.newRuleId(), rule);
            } else {
                Log.i(TAG, "No existing V1 downtime found, generating default schedules");
                appendDefaultScheduleRules(rt);
            }
            appendDefaultEventRules(rt);
            return rt;
        }
    };

    private final class RingerModeDelegate implements AudioManagerInternal.RingerModeDelegate {
        @Override
        public String toString() {
            return TAG;
        }

        @Override
        public int onSetRingerModeInternal(int ringerModeOld, int ringerModeNew, String caller,
                int ringerModeExternal, VolumePolicy policy) {
            final boolean isChange = ringerModeOld != ringerModeNew;

            int ringerModeExternalOut = ringerModeNew;

            int newZen = -1;
            switch (ringerModeNew) {
                case AudioManager.RINGER_MODE_SILENT:
                    if (isChange && policy.doNotDisturbWhenSilent) {
                        if (mZenMode != Global.ZEN_MODE_NO_INTERRUPTIONS
                                && mZenMode != Global.ZEN_MODE_ALARMS) {
                            newZen = Global.ZEN_MODE_ALARMS;
                        }
                        setPreviousRingerModeSetting(ringerModeOld);
                    }
                    break;
                case AudioManager.RINGER_MODE_VIBRATE:
                case AudioManager.RINGER_MODE_NORMAL:
                    if (isChange && ringerModeOld == AudioManager.RINGER_MODE_SILENT
                            && (mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS
                                    || mZenMode == Global.ZEN_MODE_ALARMS)) {
                        newZen = Global.ZEN_MODE_OFF;
                    } else if (mZenMode != Global.ZEN_MODE_OFF) {
                        ringerModeExternalOut = AudioManager.RINGER_MODE_SILENT;
                    }
                    break;
            }
            if (newZen != -1) {
                setManualZenMode(newZen, null, "ringerModeInternal", false /*setRingerMode*/);
            }

            if (isChange || newZen != -1 || ringerModeExternal != ringerModeExternalOut) {
                ZenLog.traceSetRingerModeInternal(ringerModeOld, ringerModeNew, caller,
                        ringerModeExternal, ringerModeExternalOut);
            }
            return ringerModeExternalOut;
        }

        @Override
        public int onSetRingerModeExternal(int ringerModeOld, int ringerModeNew, String caller,
                int ringerModeInternal, VolumePolicy policy) {
            int ringerModeInternalOut = ringerModeNew;
            final boolean isChange = ringerModeOld != ringerModeNew;
            final boolean isVibrate = ringerModeInternal == AudioManager.RINGER_MODE_VIBRATE;

            int newZen = -1;
            switch (ringerModeNew) {
                case AudioManager.RINGER_MODE_SILENT:
                    if (isChange) {
                        if (mZenMode == Global.ZEN_MODE_OFF) {
                            newZen = Global.ZEN_MODE_ALARMS;
                        }
                        ringerModeInternalOut = isVibrate ? AudioManager.RINGER_MODE_VIBRATE
                                : AudioManager.RINGER_MODE_SILENT;
                    } else {
                        ringerModeInternalOut = ringerModeInternal;
                    }
                    break;
                case AudioManager.RINGER_MODE_VIBRATE:
                case AudioManager.RINGER_MODE_NORMAL:
                    if (mZenMode != Global.ZEN_MODE_OFF) {
                        newZen = Global.ZEN_MODE_OFF;
                    }
                    break;
            }
            if (newZen != -1) {
                setManualZenMode(newZen, null, "ringerModeExternal", false /*setRingerMode*/);
            }

            ZenLog.traceSetRingerModeExternal(ringerModeOld, ringerModeNew, caller,
                    ringerModeInternal, ringerModeInternalOut);
            return ringerModeInternalOut;
        }

        @Override
        public boolean canVolumeDownEnterSilent() {
            return mZenMode == Global.ZEN_MODE_OFF;
        }

        @Override
        public int getRingerModeAffectedStreams(int streams) {
            // ringtone, notification and system streams are always affected by ringer mode
            streams |= (1 << AudioSystem.STREAM_RING) |
                       (1 << AudioSystem.STREAM_NOTIFICATION) |
                       (1 << AudioSystem.STREAM_SYSTEM);

            // alarm and music streams are only affected by ringer mode when in total silence
            if (mZenMode == Global.ZEN_MODE_NO_INTERRUPTIONS) {
                streams |= (1 << AudioSystem.STREAM_ALARM) |
                           (1 << AudioSystem.STREAM_MUSIC);
            } else {
                streams &= ~((1 << AudioSystem.STREAM_ALARM) |
                             (1 << AudioSystem.STREAM_MUSIC));
            }
            return streams;
        }
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri ZEN_MODE = Global.getUriFor(Global.ZEN_MODE);

        public SettingsObserver(Handler handler) {
            super(handler);
        }

        public void observe() {
            final ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(ZEN_MODE, false /*notifyForDescendents*/, this);
            update(null);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            update(uri);
        }

        public void update(Uri uri) {
            if (ZEN_MODE.equals(uri)) {
                if (mZenMode != getZenModeSetting()) {
                    if (DEBUG) Log.d(TAG, "Fixing zen mode setting");
                    setZenModeSetting(mZenMode);
                }
            }
        }
    }

    private final class Metrics extends Callback {
        private static final String COUNTER_PREFIX = "dnd_mode_";
        private static final long MINIMUM_LOG_PERIOD_MS = 60 * 1000;

        private int mPreviousZenMode = -1;
        private long mBeginningMs = 0L;

        @Override
        void onZenModeChanged() {
            emit();
        }

        private void emit() {
            mHandler.postMetricsTimer();
            final long now = SystemClock.elapsedRealtime();
            final long since = (now - mBeginningMs);
            if (mPreviousZenMode != mZenMode || since > MINIMUM_LOG_PERIOD_MS) {
                if (mPreviousZenMode != -1) {
                    MetricsLogger.count(mContext, COUNTER_PREFIX + mPreviousZenMode, (int) since);
                }
                mPreviousZenMode = mZenMode;
                mBeginningMs = now;
            }
        }
    }

    private final class H extends Handler {
        private static final int MSG_DISPATCH = 1;
        private static final int MSG_METRICS = 2;

        private static final long METRICS_PERIOD_MS = 6 * 60 * 60 * 1000;

        private H(Looper looper) {
            super(looper);
        }

        private void postDispatchOnZenModeChanged() {
            removeMessages(MSG_DISPATCH);
            sendEmptyMessage(MSG_DISPATCH);
        }

        private void postMetricsTimer() {
            removeMessages(MSG_METRICS);
            sendEmptyMessageDelayed(MSG_METRICS, METRICS_PERIOD_MS);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_DISPATCH:
                    dispatchOnZenModeChanged();
                    break;
                case MSG_METRICS:
                    mMetrics.emit();
                    break;
            }
        }
    }

    public static class Callback {
        void onConfigChanged() {}
        void onZenModeChanged() {}
        void onPolicyChanged() {}
    }

}
