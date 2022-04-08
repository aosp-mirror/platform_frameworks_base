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
 * distributed under the License is distriZenbuted on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static android.app.NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CALLS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS;
import static android.app.NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM;
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_ANY;
import static android.app.NotificationManager.Policy.PRIORITY_SENDERS_STARRED;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS;
import static android.app.NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK;
import static android.provider.Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS;

import static com.android.internal.util.FrameworkStatsLog.ANNOTATION_ID_IS_UID;
import static com.android.internal.util.FrameworkStatsLog.DND_MODE_RULE;
import static com.android.os.AtomsProto.DNDModeProto.CHANNELS_BYPASSING_FIELD_NUMBER;
import static com.android.os.AtomsProto.DNDModeProto.ENABLED_FIELD_NUMBER;
import static com.android.os.AtomsProto.DNDModeProto.ID_FIELD_NUMBER;
import static com.android.os.AtomsProto.DNDModeProto.UID_FIELD_NUMBER;
import static com.android.os.AtomsProto.DNDModeProto.ZEN_MODE_FIELD_NUMBER;
import static com.android.server.notification.ZenModeHelper.RULE_LIMIT_PER_PACKAGE;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.TestCase.assertTrue;
import static junit.framework.TestCase.fail;

import static org.junit.Assert.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AutomaticZenRule;
import android.app.NotificationManager;
import android.app.NotificationManager.Policy;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioManagerInternal;
import android.media.AudioSystem;
import android.media.VolumePolicy;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.service.notification.Condition;
import android.service.notification.DNDModeProto;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ScheduleInfo;
import android.service.notification.ZenPolicy;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.ArrayMap;
import android.util.Log;
import android.util.StatsEvent;
import android.util.Xml;

import com.android.internal.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.util.FastXmlSerializer;
import com.android.server.UiServiceTestCase;
import com.android.server.notification.ManagedServices.UserProfiles;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ZenModeHelperTest extends UiServiceTestCase {

    private static final String EVENTS_DEFAULT_RULE_ID = "EVENTS_DEFAULT_RULE";
    private static final String SCHEDULE_DEFAULT_RULE_ID = "EVERY_NIGHT_DEFAULT_RULE";
    private static final int ZEN_MODE_FOR_TESTING = 99;
    private static final String CUSTOM_PKG_NAME = "not.android";
    private static final int CUSTOM_PKG_UID = 1;

    ConditionProviders mConditionProviders;
    @Mock NotificationManager mNotificationManager;
    @Mock PackageManager mPackageManager;
    private Resources mResources;
    private TestableLooper mTestableLooper;
    private ZenModeHelper mZenModeHelperSpy;
    private Context mContext;
    private ContentResolver mContentResolver;
    @Mock AppOpsManager mAppOps;
    private WrappedSysUiStatsEvent.WrappedBuilderFactory mStatsEventBuilderFactory;

    @Before
    public void setUp() throws PackageManager.NameNotFoundException {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mContext = spy(getContext());
        mContentResolver = mContext.getContentResolver();
        mResources = spy(mContext.getResources());
        try {
            when(mResources.getXml(R.xml.default_zen_mode_config)).thenReturn(
                    getDefaultConfigParser());
        } catch (Exception e) {
            Log.d("ZenModeHelperTest", "Couldn't mock default zen mode config xml file err=" +
                    e.toString());
        }
        mStatsEventBuilderFactory = new WrappedSysUiStatsEvent.WrappedBuilderFactory();

        when(mContext.getSystemService(AppOpsManager.class)).thenReturn(mAppOps);
        when(mContext.getSystemService(NotificationManager.class)).thenReturn(mNotificationManager);
        mConditionProviders = new ConditionProviders(mContext, new UserProfiles(),
                AppGlobals.getPackageManager());
        mConditionProviders.addSystemProvider(new CountdownConditionProvider());
        mZenModeHelperSpy = spy(new ZenModeHelper(mContext, mTestableLooper.getLooper(),
                mConditionProviders, mStatsEventBuilderFactory));

        ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = new ActivityInfo();
        when(mPackageManager.queryIntentActivitiesAsUser(any(), anyInt(), anyInt())).thenReturn(
                ImmutableList.of(ri));
        when(mPackageManager.getPackageUidAsUser(eq(CUSTOM_PKG_NAME), anyInt()))
                .thenReturn(CUSTOM_PKG_UID);
        when(mPackageManager.getPackagesForUid(anyInt())).thenReturn(
                new String[] {getContext().getPackageName()});
        mZenModeHelperSpy.mPm = mPackageManager;
    }

    private XmlResourceParser getDefaultConfigParser() throws IOException, XmlPullParserException {
        String xml = "<zen version=\"8\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" conversations=\"true\""
                + " conversationsFrom=\"2\"/>\n"
                + "<automatic ruleId=\"" + EVENTS_DEFAULT_RULE_ID
                + "\" enabled=\"false\" snoozing=\"false\""
                + " name=\"Event\" zen=\"1\""
                + " component=\"android/com.android.server.notification.EventConditionProvider\""
                + " conditionId=\"condition://android/event?userId=-10000&amp;calendar=&amp;"
                + "reply=1\"/>\n"
                + "<automatic ruleId=\"" + SCHEDULE_DEFAULT_RULE_ID + "\" enabled=\"false\""
                + " snoozing=\"false\" name=\"Sleeping\" zen=\"1\""
                + " component=\"android/com.android.server.notification.ScheduleConditionProvider\""
                + " conditionId=\"condition://android/schedule?days=1.2.3.4.5.6.7 &amp;start=22.0"
                + "&amp;end=7.0&amp;exitAtAlarm=true\"/>"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        return new XmlResourceParserImpl(parser);
    }

    private ByteArrayOutputStream writeXmlAndPurge(Integer version) throws Exception {
        XmlSerializer serializer = new FastXmlSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mZenModeHelperSpy.writeXml(serializer, false, version, UserHandle.USER_ALL);
        serializer.endDocument();
        serializer.flush();
        mZenModeHelperSpy.setConfig(new ZenModeConfig(), null, "writing xml");
        return baos;
    }

    private ByteArrayOutputStream writeXmlAndPurgeForUser(Integer version, int userId)
            throws Exception {
        XmlSerializer serializer = new FastXmlSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.setOutput(new BufferedOutputStream(baos), "utf-8");
        serializer.startDocument(null, true);
        mZenModeHelperSpy.writeXml(serializer, true, version, userId);
        serializer.endDocument();
        serializer.flush();
        ZenModeConfig newConfig = new ZenModeConfig();
        newConfig.user = userId;
        mZenModeHelperSpy.setConfig(newConfig, null, "writing xml");
        return baos;
    }

    private XmlPullParser getParserForByteStream(ByteArrayOutputStream baos) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(
                new BufferedInputStream(new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        return parser;
    }

    private ArrayMap<String, ZenModeConfig.ZenRule> getCustomAutomaticRules() {
        return getCustomAutomaticRules(ZEN_MODE_IMPORTANT_INTERRUPTIONS);
    }

    private ArrayMap<String, ZenModeConfig.ZenRule> getCustomAutomaticRules(int zenMode) {
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = true;
        customRule.creationTime = 0;
        customRule.id = "customRule";
        customRule.name = "Custom Rule";
        customRule.zenMode = zenMode;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.configurationActivity =
                new ComponentName("not.android", "ScheduleConditionProvider");
        customRule.pkg = customRule.configurationActivity.getPackageName();
        automaticRules.put("customRule", customRule);
        return automaticRules;
    }

    @Test
    public void testZenOff_NoMuteApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS |
                PRIORITY_CATEGORY_MEDIA, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();

        doNothing().when(mZenModeHelperSpy).applyRestrictions(eq(false), anyBoolean(), anyInt());
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, false,
                AudioAttributes.USAGE_ALARM);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, false,
                AudioAttributes.USAGE_MEDIA);
    }

    @Test
    public void testZenOn_NotificationApplied() {
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        // The most permissive policy
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS |
                PRIORITY_CATEGORY_MEDIA | PRIORITY_CATEGORY_MESSAGES
                | PRIORITY_CATEGORY_CONVERSATIONS | PRIORITY_CATEGORY_CALLS
                | PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_EVENTS | PRIORITY_CATEGORY_REMINDERS
                | PRIORITY_CATEGORY_REPEAT_CALLERS | PRIORITY_CATEGORY_SYSTEM, PRIORITY_SENDERS_ANY,
                PRIORITY_SENDERS_ANY, 0, CONVERSATION_SENDERS_ANYONE);
        mZenModeHelperSpy.applyRestrictions();

        doNothing().when(mZenModeHelperSpy).applyRestrictions(anyBoolean(), anyBoolean(), anyInt());
        verify(mZenModeHelperSpy).applyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION);
        verify(mZenModeHelperSpy).applyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION_EVENT);
        verify(mZenModeHelperSpy).applyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED);
        verify(mZenModeHelperSpy).applyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT);
    }

    @Test
    public void testZenOn_StarredCallers_CallTypesBlocked() {
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        // The most permissive policy
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS |
                PRIORITY_CATEGORY_MEDIA | PRIORITY_CATEGORY_MESSAGES
                | PRIORITY_CATEGORY_CONVERSATIONS | PRIORITY_CATEGORY_CALLS
                | PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_EVENTS | PRIORITY_CATEGORY_REMINDERS
                | PRIORITY_CATEGORY_SYSTEM,
                PRIORITY_SENDERS_STARRED,
                PRIORITY_SENDERS_ANY, 0, CONVERSATION_SENDERS_ANYONE);
        mZenModeHelperSpy.applyRestrictions();

        doNothing().when(mZenModeHelperSpy).applyRestrictions(anyBoolean(), anyBoolean(), anyInt());
        verify(mZenModeHelperSpy).applyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        verify(mZenModeHelperSpy).applyRestrictions(true, true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST);
    }

    @Test
    public void testZenOn_AllCallers_CallTypesAllowed() {
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        // The most permissive policy
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS |
                PRIORITY_CATEGORY_MEDIA | PRIORITY_CATEGORY_MESSAGES
                | PRIORITY_CATEGORY_CONVERSATIONS | PRIORITY_CATEGORY_CALLS
                | PRIORITY_CATEGORY_ALARMS | PRIORITY_CATEGORY_EVENTS | PRIORITY_CATEGORY_REMINDERS
                | PRIORITY_CATEGORY_REPEAT_CALLERS | PRIORITY_CATEGORY_SYSTEM,
                PRIORITY_SENDERS_ANY,
                PRIORITY_SENDERS_ANY, 0, CONVERSATION_SENDERS_ANYONE);
        mZenModeHelperSpy.applyRestrictions();

        doNothing().when(mZenModeHelperSpy).applyRestrictions(anyBoolean(), anyBoolean(), anyInt());
        verify(mZenModeHelperSpy).applyRestrictions(true, false,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        verify(mZenModeHelperSpy).applyRestrictions(true, false,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST);
    }

    @Test
    public void testZenOn_AllowAlarmsMedia_NoAlarmMediaMuteApplied() {
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS |
                PRIORITY_CATEGORY_MEDIA, 0, 0, 0, 0, 0);

        mZenModeHelperSpy.applyRestrictions();
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true, false,
                AudioAttributes.USAGE_ALARM);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true, false,
                AudioAttributes.USAGE_MEDIA);
    }

    @Test
    public void testZenOn_DisallowAlarmsMedia_AlarmMediaMuteApplied() {
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true, true,
                AudioAttributes.USAGE_ALARM);

        // Media is a catch-all that includes games
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true, true,
                AudioAttributes.USAGE_MEDIA);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true, true,
                AudioAttributes.USAGE_GAME);
    }

    @Test
    public void testTotalSilence() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(Policy.PRIORITY_CATEGORY_ALARMS |
                PRIORITY_CATEGORY_MEDIA, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();

        // Total silence will silence alarms, media and system noises (but not vibrations)
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, true,
                AudioAttributes.USAGE_ALARM);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, true,
                AudioAttributes.USAGE_MEDIA);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, true,
                AudioAttributes.USAGE_GAME);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, true,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AppOpsManager.OP_PLAY_AUDIO);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, false,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AppOpsManager.OP_VIBRATE);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, true,
                AudioAttributes.USAGE_UNKNOWN);
    }

    @Test
    public void testAlarmsOnly_alarmMediaMuteNotApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();

        // Alarms only mode will not silence alarms
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, false,
                AudioAttributes.USAGE_ALARM);

        // Alarms only mode will not silence media
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, false,
                AudioAttributes.USAGE_MEDIA);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, false,
                AudioAttributes.USAGE_GAME);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, false,
                AudioAttributes.USAGE_UNKNOWN);

        // Alarms only will silence system noises (but not vibrations)
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, true,
                AudioAttributes.USAGE_ASSISTANCE_SONIFICATION, AppOpsManager.OP_PLAY_AUDIO);
    }

    @Test
    public void testAlarmsOnly_callsMuteApplied() {
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();

        // Alarms only mode will silence calls despite priority-mode config
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, true,
                AudioAttributes.USAGE_NOTIFICATION_RINGTONE);
        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, true,
                AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST);
    }

    @Test
    public void testAlarmsOnly_allZenConfigToggledCannotBypass_alarmMuteNotApplied() {
        // Only audio attributes with SUPPRESIBLE_NEVER can bypass
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();

        verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(false, false,
                AudioAttributes.USAGE_ALARM);
    }

    @Test
    public void testZenAllCannotBypass() {
        // Only audio attributes with SUPPRESIBLE_NEVER can bypass
        // with special case USAGE_ASSISTANCE_SONIFICATION
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();

        for (int usage : AudioAttributes.SDK_USAGES) {
            if (usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION) {
                // only mute audio, not vibrations
                verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true, true, usage,
                        AppOpsManager.OP_PLAY_AUDIO);
                verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true, false, usage,
                        AppOpsManager.OP_VIBRATE);
            } else {
                boolean shouldMute = AudioAttributes.SUPPRESSIBLE_USAGES.get(usage)
                        != AudioAttributes.SUPPRESSIBLE_NEVER;
                verify(mZenModeHelperSpy, atLeastOnce()).applyRestrictions(true, shouldMute, usage);
            }
        }
    }

    @Test
    public void testApplyRestrictions_whitelist_priorityOnlyMode() {
        mZenModeHelperSpy.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();

        for (int usage : AudioAttributes.SDK_USAGES) {
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_PLAY_AUDIO), eq(usage), anyInt(), eq(new String[]{PKG_O}));
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_VIBRATE), eq(usage), anyInt(), eq(new String[]{PKG_O}));
        }
    }

    @Test
    public void testApplyRestrictions_whitelist_alarmsOnlyMode() {
        mZenModeHelperSpy.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_ALARMS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();

        for (int usage : AudioAttributes.SDK_USAGES) {
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_PLAY_AUDIO), eq(usage), anyInt(), eq(null));
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_VIBRATE), eq(usage), anyInt(), eq(null));
        }
    }

    @Test
    public void testApplyRestrictions_whitelist_totalSilenceMode() {
        mZenModeHelperSpy.setPriorityOnlyDndExemptPackages(new String[] {PKG_O});
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_NO_INTERRUPTIONS;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.applyRestrictions();

        for (int usage : AudioAttributes.SDK_USAGES) {
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_PLAY_AUDIO), eq(usage), anyInt(), eq(null));
            verify(mAppOps).setRestriction(
                    eq(AppOpsManager.OP_VIBRATE), eq(usage), anyInt(), eq(null));
        }
    }

    @Test
    public void testZenUpgradeNotification() {
        // shows zen upgrade notification if stored settings says to shows,
        // zen has not been updated, boot is completed
        // and we're setting zen mode on
        Settings.Secure.putInt(mContentResolver, Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 1);
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_SETTINGS_UPDATED, 0);
        mZenModeHelperSpy.mIsBootComplete = true;
        mZenModeHelperSpy.mConsolidatedPolicy = new Policy(0, 0, 0, 0, 0, 0);
        mZenModeHelperSpy.setZenModeSetting(ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        verify(mZenModeHelperSpy, times(1)).createZenUpgradeNotification();
        verify(mNotificationManager, times(1)).notify(eq(ZenModeHelper.TAG),
                eq(SystemMessage.NOTE_ZEN_UPGRADE), any());
        assertEquals(0, Settings.Secure.getInt(mContentResolver,
                Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, -1));
    }

    @Test
    public void testNoZenUpgradeNotification() {
        // doesn't show upgrade notification if stored settings says don't show
        Settings.Secure.putInt(mContentResolver, Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 0);
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_SETTINGS_UPDATED, 0);
        mZenModeHelperSpy.mIsBootComplete = true;
        mZenModeHelperSpy.setZenModeSetting(ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        verify(mZenModeHelperSpy, never()).createZenUpgradeNotification();
        verify(mNotificationManager, never()).notify(eq(ZenModeHelper.TAG),
                eq(SystemMessage.NOTE_ZEN_UPGRADE), any());
    }

    @Test
    public void testNoZenUpgradeNotificationZenUpdated() {
        // doesn't show upgrade notification since zen was already updated
        Settings.Secure.putInt(mContentResolver, Settings.Secure.SHOW_ZEN_UPGRADE_NOTIFICATION, 0);
        Settings.Secure.putInt(mContentResolver, Settings.Secure.ZEN_SETTINGS_UPDATED, 1);
        mZenModeHelperSpy.mIsBootComplete = true;
        mZenModeHelperSpy.setZenModeSetting(ZEN_MODE_IMPORTANT_INTERRUPTIONS);

        verify(mZenModeHelperSpy, never()).createZenUpgradeNotification();
        verify(mNotificationManager, never()).notify(eq(ZenModeHelper.TAG),
                eq(SystemMessage.NOTE_ZEN_UPGRADE), any());
    }

    @Test
    public void testZenSetInternalRinger_AllPriorityNotificationSoundsMuted() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_NORMAL));

        // 1. Current ringer is normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        // Set zen to priority-only with all notification sounds muted (so ringer will be muted)
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowReminders = false;
        mZenModeHelperSpy.mConfig.allowCalls = false;
        mZenModeHelperSpy.mConfig.allowMessages = false;
        mZenModeHelperSpy.mConfig.allowEvents = false;
        mZenModeHelperSpy.mConfig.allowRepeatCallers = false;
        mZenModeHelperSpy.mConfig.allowConversations = false;

        // 2. apply priority only zen - verify ringer is unchanged
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelperSpy.TAG);

        // 3. apply zen off - verify zen is set to previous ringer (normal)
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testRingerAffectedStreamsTotalSilence() {
        // in total silence:
        // ringtone, notification, system, alarm, streams, music are affected by ringer mode
        mZenModeHelperSpy.mZenMode = Settings.Global.ZEN_MODE_NO_INTERRUPTIONS;
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelperSpy.new RingerModeDelegate();
        int ringerModeAffectedStreams = ringerModeDelegate.getRingerModeAffectedStreams(0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_RING)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_NOTIFICATION))
                != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_SYSTEM)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ALARM)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_MUSIC)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ASSISTANT)) != 0);
    }

    @Test
    public void testRingerAffectedStreamsPriorityOnly() {
        // in priority only mode:
        // ringtone, notification and system streams are affected by ringer mode
        mZenModeHelperSpy.mConfig.allowAlarms = true;
        mZenModeHelperSpy.mConfig.allowReminders = true;
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        ZenModeHelper.RingerModeDelegate ringerModeDelegateRingerMuted =
                mZenModeHelperSpy.new RingerModeDelegate();

        int ringerModeAffectedStreams =
                ringerModeDelegateRingerMuted.getRingerModeAffectedStreams(0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_RING)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_NOTIFICATION))
                != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_SYSTEM)) != 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ALARM)) == 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_MUSIC)) == 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ASSISTANT)) == 0);

        // even when ringer is muted (since all ringer sounds cannot bypass DND),
        // system stream is still affected by ringer mode
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowReminders = false;
        mZenModeHelperSpy.mConfig.allowCalls = false;
        mZenModeHelperSpy.mConfig.allowMessages = false;
        mZenModeHelperSpy.mConfig.allowEvents = false;
        mZenModeHelperSpy.mConfig.allowRepeatCallers = false;
        mZenModeHelperSpy.mConfig.allowConversations = false;
        ZenModeHelper.RingerModeDelegate ringerModeDelegateRingerNotMuted =
                mZenModeHelperSpy.new RingerModeDelegate();

        int ringerMutedRingerModeAffectedStreams =
                ringerModeDelegateRingerNotMuted.getRingerModeAffectedStreams(0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_RING)) != 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_NOTIFICATION))
                != 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_SYSTEM))
                != 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_ALARM)) == 0);
        assertTrue((ringerMutedRingerModeAffectedStreams & (1 << AudioSystem.STREAM_MUSIC)) == 0);
        assertTrue((ringerModeAffectedStreams & (1 << AudioSystem.STREAM_ASSISTANT)) == 0);
    }

    @Test
    public void testZenSetInternalRinger_NotAllPriorityNotificationSoundsMuted_StartNormal() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_NORMAL));

        // 1. Current ringer is normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowReminders = true;

        // 2. apply priority only zen - verify ringer is normal
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);

        // 3.  apply zen off - verify ringer remains normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testZenSetInternalRinger_NotAllPriorityNotificationSoundsMuted_StartSilent() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_SILENT));

        // 1. Current ringer is silent
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowReminders = true;

        // 2. apply priority only zen - verify ringer is silent
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelperSpy.TAG);

        // 3. apply zen-off - verify ringer is still silent
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testZenSetInternalRinger_NotAllPriorityNotificationSoundsMuted_RingerChanges() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        Global.putString(mContext.getContentResolver(), Global.ZEN_MODE_RINGER_LEVEL,
                Integer.toString(AudioManager.RINGER_MODE_NORMAL));

        // 1. Current ringer is normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_NORMAL);
        // Set zen to priority-only with all notification sounds muted (so ringer will be muted)
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowReminders = true;

        // 2. apply priority only zen - verify zen will still be normal
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);

        // 3. change ringer from normal to silent, verify previous ringer set to new ringer (silent)
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelperSpy.new RingerModeDelegate();
        ringerModeDelegate.onSetRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_SILENT, "test", AudioManager.RINGER_MODE_NORMAL,
                VolumePolicy.DEFAULT);
        assertEquals(AudioManager.RINGER_MODE_SILENT, Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL));

        // 4.  apply zen off - verify ringer still silenced
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.applyZenToRingerMode();
        verify(mAudioManager, atLeastOnce()).setRingerModeInternal(AudioManager.RINGER_MODE_SILENT,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testSilentRingerSavedInZenOff_startsZenOff() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mConfig = new ZenModeConfig();
        mZenModeHelperSpy.mAudioManager = mAudioManager;

        // apply zen off multiple times - verify ringer is not set to normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.mConfig = null; // will evaluate config to zen mode off
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelperSpy.evaluateZenMode("test", true);
        }
        verify(mZenModeHelperSpy, never()).applyZenToRingerMode();
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testSilentRingerSavedOnZenOff_startsZenOn() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.mConfig = new ZenModeConfig();

        // previously set silent ringer
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelperSpy.new RingerModeDelegate();
        ringerModeDelegate.onSetRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_SILENT, "test", AudioManager.RINGER_MODE_NORMAL,
                VolumePolicy.DEFAULT);
        assertEquals(AudioManager.RINGER_MODE_SILENT, Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL));

        // apply zen off multiple times - verify ringer is not set to normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_SILENT);
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelperSpy.evaluateZenMode("test", true);
        }
        verify(mZenModeHelperSpy, times(1)).applyZenToRingerMode();
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testVibrateRingerSavedOnZenOff_startsZenOn() {
        AudioManagerInternal mAudioManager = mock(AudioManagerInternal.class);
        mZenModeHelperSpy.mAudioManager = mAudioManager;
        mZenModeHelperSpy.mZenMode = Global.ZEN_MODE_OFF;
        mZenModeHelperSpy.mConfig = new ZenModeConfig();

        // previously set silent ringer
        ZenModeHelper.RingerModeDelegate ringerModeDelegate =
                mZenModeHelperSpy.new RingerModeDelegate();
        ringerModeDelegate.onSetRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                AudioManager.RINGER_MODE_VIBRATE, "test", AudioManager.RINGER_MODE_NORMAL,
                VolumePolicy.DEFAULT);
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, Global.getInt(mContext.getContentResolver(),
                Global.ZEN_MODE_RINGER_LEVEL, AudioManager.RINGER_MODE_NORMAL));

        // apply zen off multiple times - verify ringer is not set to normal
        when(mAudioManager.getRingerModeInternal()).thenReturn(AudioManager.RINGER_MODE_VIBRATE);
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        for (int i = 0; i < 3; i++) {
            // if zen doesn't change, zen should not reapply itself to the ringer
            mZenModeHelperSpy.evaluateZenMode("test", true);
        }
        verify(mZenModeHelperSpy, times(1)).applyZenToRingerMode();
        verify(mAudioManager, never()).setRingerModeInternal(AudioManager.RINGER_MODE_NORMAL,
                mZenModeHelperSpy.TAG);
    }

    @Test
    public void testParcelConfig() {
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowReminders = true;
        mZenModeHelperSpy.mConfig.allowCalls = true;
        mZenModeHelperSpy.mConfig.allowMessages = true;
        mZenModeHelperSpy.mConfig.allowEvents = true;
        mZenModeHelperSpy.mConfig.allowRepeatCallers = true;
        mZenModeHelperSpy.mConfig.allowConversations = true;
        mZenModeHelperSpy.mConfig.allowConversationsFrom = ZenPolicy.CONVERSATION_SENDERS_ANYONE;
        mZenModeHelperSpy.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;
        mZenModeHelperSpy.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelperSpy.mConfig.manualRule.component = new ComponentName("a", "a");
        mZenModeHelperSpy.mConfig.manualRule.enabled = true;
        mZenModeHelperSpy.mConfig.manualRule.snoozing = true;

        ZenModeConfig actual = mZenModeHelperSpy.mConfig.copy();

        assertEquals(mZenModeHelperSpy.mConfig, actual);
    }

    @Test
    public void testWriteXml() throws Exception {
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowReminders = true;
        mZenModeHelperSpy.mConfig.allowCalls = true;
        mZenModeHelperSpy.mConfig.allowMessages = true;
        mZenModeHelperSpy.mConfig.allowEvents = true;
        mZenModeHelperSpy.mConfig.allowRepeatCallers = true;
        mZenModeHelperSpy.mConfig.allowConversations = true;
        mZenModeHelperSpy.mConfig.allowConversationsFrom = ZenPolicy.CONVERSATION_SENDERS_ANYONE;
        mZenModeHelperSpy.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;
        mZenModeHelperSpy.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelperSpy.mConfig.manualRule.zenMode =
                ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.manualRule.component = new ComponentName("a", "a");
        mZenModeHelperSpy.mConfig.manualRule.pkg = "a";
        mZenModeHelperSpy.mConfig.manualRule.enabled = true;

        ZenModeConfig expected = mZenModeHelperSpy.mConfig.copy();

        ByteArrayOutputStream baos = writeXmlAndPurge(null);
        XmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals("Config mismatch: current vs expected: "
                + mZenModeHelperSpy.mConfig.diff(expected), expected, mZenModeHelperSpy.mConfig);
    }

    @Test
    public void testProto() {
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.manualRule = new ZenModeConfig.ZenRule();

        int n = mZenModeHelperSpy.mConfig.automaticRules.size();
        List<String> ids = new ArrayList<>(n);
        for (ZenModeConfig.ZenRule rule : mZenModeHelperSpy.mConfig.automaticRules.values()) {
            ids.add(rule.id);
        }
        ids.add("");

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelperSpy.pullRules(events);
        assertEquals(n + 1, events.size());
        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == DND_MODE_RULE) {
                if (builder.getInt(ZEN_MODE_FIELD_NUMBER) == DNDModeProto.ROOT_CONFIG) {
                    assertTrue(builder.getBoolean(ENABLED_FIELD_NUMBER));
                    assertFalse(builder.getBoolean(CHANNELS_BYPASSING_FIELD_NUMBER));
                }
                assertEquals(Process.SYSTEM_UID, builder.getInt(UID_FIELD_NUMBER));
                assertTrue(builder.getBooleanAnnotation(UID_FIELD_NUMBER, ANNOTATION_ID_IS_UID));
                String name = (String) builder.getValue(ID_FIELD_NUMBER);
                assertTrue("unexpected rule id", ids.contains(name));
                ids.remove(name);
            } else {
                fail("unexpected atom id: " + builder.getAtomId());
            }
        }
        assertEquals("extra rule in output", 0, ids.size());
    }

    @Test
    public void testProtoWithAutoRule() throws Exception {
        setupZenConfig();
        // one enabled automatic rule
        mZenModeHelperSpy.mConfig.automaticRules = getCustomAutomaticRules(ZEN_MODE_FOR_TESTING);

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelperSpy.pullRules(events);

        boolean foundCustomEvent = false;
        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == DND_MODE_RULE) {
                if (ZEN_MODE_FOR_TESTING == builder.getInt(ZEN_MODE_FIELD_NUMBER)) {
                    foundCustomEvent = true;
                    assertEquals(0, builder.getInt(UID_FIELD_NUMBER));
                    assertTrue(builder.getBoolean(ENABLED_FIELD_NUMBER));
                }
            } else {
                fail("unexpected atom id: " + builder.getAtomId());
            }
        }
        assertTrue("couldn't find custom rule", foundCustomEvent);
    }

    @Test
    public void testProtoRedactsIds() throws Exception {
        setupZenConfig();
        // one enabled automatic rule
        mZenModeHelperSpy.mConfig.automaticRules = getCustomAutomaticRules();

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelperSpy.pullRules(events);

        boolean foundCustomEvent = false;
        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == DND_MODE_RULE
                    && "customRule".equals(builder.getString(ID_FIELD_NUMBER))) {
                fail("non-default IDs should be redacted");
            }
        }
    }

    @Test
    public void testProtoWithManualRule() throws Exception {
        setupZenConfig();
        mZenModeHelperSpy.mConfig.automaticRules = getCustomAutomaticRules();
        mZenModeHelperSpy.mConfig.manualRule = new ZenModeConfig.ZenRule();
        mZenModeHelperSpy.mConfig.manualRule.enabled = true;
        mZenModeHelperSpy.mConfig.manualRule.enabler = "com.enabler";

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelperSpy.pullRules(events);

        boolean foundManualRule = false;
        for (WrappedSysUiStatsEvent.WrappedBuilder builder : mStatsEventBuilderFactory.builders) {
            if (builder.getAtomId() == DND_MODE_RULE
                    && ZenModeConfig.MANUAL_RULE_ID.equals(builder.getString(ID_FIELD_NUMBER))) {
                assertEquals(0, builder.getInt(UID_FIELD_NUMBER));
                foundManualRule = true;
            }
        }
        assertTrue("couldn't find manual rule", foundManualRule);    }

    @Test
    public void testWriteXml_onlyBackupsTargetUser() throws Exception {
        // Setup configs for user 10 and 11.
        setupZenConfig();
        ZenModeConfig config10 = mZenModeHelperSpy.mConfig.copy();
        config10.user = 10;
        config10.allowAlarms = true;
        config10.allowMedia = true;
        mZenModeHelperSpy.setConfig(config10, null, "writeXml");
        ZenModeConfig config11 = mZenModeHelperSpy.mConfig.copy();
        config11.user = 11;
        config11.allowAlarms = false;
        config11.allowMedia = false;
        mZenModeHelperSpy.setConfig(config11, null, "writeXml");

        // Backup user 10 and reset values.
        ByteArrayOutputStream baos = writeXmlAndPurgeForUser(null, 10);
        ZenModeConfig newConfig11 = new ZenModeConfig();
        newConfig11.user = 11;
        mZenModeHelperSpy.mConfigs.put(11, newConfig11);

        // Parse backup data.
        XmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelperSpy.readXml(parser, true, 10);
        mZenModeHelperSpy.readXml(parser, true, 11);

        ZenModeConfig actual = mZenModeHelperSpy.mConfigs.get(10);
        assertEquals(
                "Config mismatch: current vs expected: " + actual.diff(config10), config10, actual);
        assertNotEquals("Expected config mismatch", config11, mZenModeHelperSpy.mConfigs.get(11));
    }

    @Test
    public void testReadXmlRestore_forSystemUser() throws Exception {
        setupZenConfig();
        // one enabled automatic rule
        mZenModeHelperSpy.mConfig.automaticRules = getCustomAutomaticRules();
        ZenModeConfig original = mZenModeHelperSpy.mConfig.copy();

        ByteArrayOutputStream baos = writeXmlAndPurgeForUser(null, UserHandle.USER_SYSTEM);
        XmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelperSpy.readXml(parser, true, UserHandle.USER_SYSTEM);

        assertEquals("Config mismatch: current vs original: "
                + mZenModeHelperSpy.mConfig.diff(original), original, mZenModeHelperSpy.mConfig);
        assertEquals(original.hashCode(), mZenModeHelperSpy.mConfig.hashCode());
    }

    /** Restore should ignore the data's user id and restore for the target user. */
    @Test
    public void testReadXmlRestore_forNonSystemUser() throws Exception {
        // Setup config.
        setupZenConfig();
        mZenModeHelperSpy.mConfig.automaticRules = getCustomAutomaticRules();
        ZenModeConfig expected = mZenModeHelperSpy.mConfig.copy();

        // Backup data for user 0.
        ByteArrayOutputStream baos = writeXmlAndPurgeForUser(null, UserHandle.USER_SYSTEM);

        // Restore data for user 10.
        XmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelperSpy.readXml(parser, true, 10);

        ZenModeConfig actual = mZenModeHelperSpy.mConfigs.get(10);
        expected.user = 10;
        assertEquals(
                "Config mismatch: current vs original: " + actual.diff(expected), expected, actual);
        assertEquals(expected.hashCode(), actual.hashCode());
        expected.user = 0;
        assertNotEquals(expected, mZenModeHelperSpy.mConfig);
    }

    @Test
    public void testWriteXmlWithZenPolicy() throws Exception {
        final String ruleId = "customRule";
        setupZenConfig();

        // one enabled automatic rule with zen policy
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = true;
        customRule.creationTime = 0;
        customRule.id = "customRule";
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.configurationActivity =
                new ComponentName("android", "ScheduleConditionProvider");
        customRule.pkg = customRule.configurationActivity.getPackageName();
        customRule.zenPolicy = new ZenPolicy.Builder()
                .allowAlarms(false)
                .allowMedia(false)
                .allowRepeatCallers(false)
                .allowCalls(ZenPolicy.PEOPLE_TYPE_NONE)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_CONTACTS)
                .allowEvents(true)
                .allowReminders(false)
                .build();
        automaticRules.put("customRule", customRule);
        mZenModeHelperSpy.mConfig.automaticRules = automaticRules;

        ZenModeConfig expected = mZenModeHelperSpy.mConfig.copy();

        ByteArrayOutputStream baos = writeXmlAndPurge(null);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        ZenModeConfig.ZenRule original = expected.automaticRules.get(ruleId);
        ZenModeConfig.ZenRule current = mZenModeHelperSpy.mConfig.automaticRules.get(ruleId);

        assertEquals("Automatic rules mismatch", original, current);
    }

    @Test
    public void testReadXmlRestoreWithZenPolicy_forSystemUser() throws Exception {
        final String ruleId = "customRule";
        setupZenConfig();

        // one enabled automatic rule with zen policy
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = true;
        customRule.creationTime = 0;
        customRule.id = ruleId;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.configurationActivity =
                new ComponentName("android", "ScheduleConditionProvider");
        customRule.pkg = customRule.configurationActivity.getPackageName();
        customRule.zenPolicy = new ZenPolicy.Builder()
                .allowSystem(true)
                .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .allowReminders(true)
                .build();
        automaticRules.put(ruleId, customRule);
        mZenModeHelperSpy.mConfig.automaticRules = automaticRules;

        ZenModeConfig expected = mZenModeHelperSpy.mConfig.copy();

        ByteArrayOutputStream baos = writeXmlAndPurgeForUser(null, UserHandle.USER_SYSTEM);
        XmlPullParser parser = getParserForByteStream(baos);
        mZenModeHelperSpy.readXml(parser, true, UserHandle.USER_SYSTEM);

        ZenModeConfig.ZenRule original = expected.automaticRules.get(ruleId);
        ZenModeConfig.ZenRule current = mZenModeHelperSpy.mConfig.automaticRules.get(ruleId);

        assertEquals("Automatic rules mismatch", original, current);
    }

    @Test
    public void testReadXmlRulesNotOverriden() throws Exception {
        setupZenConfig();

        // automatic zen rule is enabled on upgrade so rules should not be overriden to default
        ArrayMap<String, ZenModeConfig.ZenRule> enabledAutoRule = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo weeknights = new ScheduleInfo();
        customRule.enabled = true;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(weeknights);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        enabledAutoRule.put("customRule", customRule);
        mZenModeHelperSpy.mConfig.automaticRules = enabledAutoRule;

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        assertTrue(mZenModeHelperSpy.mConfig.automaticRules.containsKey("customRule"));
        setupZenConfigMaintained();
    }

    @Test
    public void testMigrateSuppressedVisualEffects_oneExistsButOff() throws Exception {
        String xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(0, mZenModeHelperSpy.mConfig.suppressedVisualEffects);

        xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOn=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(0, mZenModeHelperSpy.mConfig.suppressedVisualEffects);
    }

    @Test
    public void testMigrateSuppressedVisualEffects_bothExistButOff() throws Exception {
        String xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"true\" visualScreenOn=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(0, mZenModeHelperSpy.mConfig.suppressedVisualEffects);
    }

    @Test
    public void testMigrateSuppressedVisualEffects_bothExistButOn() throws Exception {
        String xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"false\" visualScreenOn=\"false\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(SUPPRESSED_EFFECT_FULL_SCREEN_INTENT
                        | SUPPRESSED_EFFECT_LIGHTS
                        | SUPPRESSED_EFFECT_PEEK,
                mZenModeHelperSpy.mConfig.suppressedVisualEffects);

        xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"true\" visualScreenOn=\"false\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(SUPPRESSED_EFFECT_PEEK, mZenModeHelperSpy.mConfig.suppressedVisualEffects);

        xml = "<zen version=\"6\" user=\"0\">\n"
                + "<allow calls=\"false\" repeatCallers=\"false\" messages=\"true\" "
                + "reminders=\"false\" events=\"false\" callsFrom=\"1\" messagesFrom=\"2\" "
                + "visualScreenOff=\"false\" visualScreenOn=\"true\" alarms=\"true\" "
                + "media=\"true\" system=\"false\" />\n"
                + "<disallow visualEffects=\"511\" />"
                + "</zen>";

        parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(xml.getBytes())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        assertEquals(SUPPRESSED_EFFECT_FULL_SCREEN_INTENT | SUPPRESSED_EFFECT_LIGHTS,
                mZenModeHelperSpy.mConfig.suppressedVisualEffects);
    }

    @Test
    public void testReadXmlResetDefaultRules() throws Exception {
        setupZenConfig();

        // no enabled automatic zen rules and no default rules
        // so rules should be overriden by default rules
        mZenModeHelperSpy.mConfig.automaticRules = new ArrayMap<>();

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelperSpy.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }

        setupZenConfigMaintained();
    }


    @Test
    public void testReadXmlAllDisabledRulesResetDefaultRules() throws Exception {
        setupZenConfig();

        // all automatic zen rules are disabled on upgrade (and default rules don't already exist)
        // so rules should be overriden by default rules
        ArrayMap<String, ZenModeConfig.ZenRule> disabledAutoRule = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo weeknights = new ScheduleInfo();
        customRule.enabled = false;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(weeknights);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        disabledAutoRule.put("customRule", customRule);
        mZenModeHelperSpy.mConfig.automaticRules = disabledAutoRule;

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelperSpy.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }
        assertFalse(rules.containsKey("customRule"));

        setupZenConfigMaintained();
    }

    @Test
    public void testReadXmlOnlyOneDefaultRuleExists() throws Exception {
        setupZenConfig();

        // all automatic zen rules are disabled on upgrade and only one default rule exists
        // so rules should be overriden to the default rules
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = false;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        customRule.zenPolicy = new ZenPolicy.Builder()
                .allowReminders(true)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .build();
        automaticRules.put("customRule", customRule);

        ZenModeConfig.ZenRule defaultScheduleRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo defaultScheduleRuleInfo = new ScheduleInfo();
        defaultScheduleRule.enabled = false;
        defaultScheduleRule.name = "Default Schedule Rule";
        defaultScheduleRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        defaultScheduleRule.conditionId = ZenModeConfig.toScheduleConditionId(
                defaultScheduleRuleInfo);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        defaultScheduleRule.id = ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID;
        automaticRules.put(ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID, defaultScheduleRule);

        mZenModeHelperSpy.mConfig.automaticRules = automaticRules;

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelperSpy.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }
        assertFalse(rules.containsKey("customRule"));

        setupZenConfigMaintained();
    }

    @Test
    public void testReadXmlDefaultRulesExist() throws Exception {
        setupZenConfig();

        // Default rules exist so rules should not be overridden by defaults
        ArrayMap<String, ZenModeConfig.ZenRule> automaticRules = new ArrayMap<>();
        ZenModeConfig.ZenRule customRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo customRuleInfo = new ScheduleInfo();
        customRule.enabled = false;
        customRule.name = "Custom Rule";
        customRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customRule.conditionId = ZenModeConfig.toScheduleConditionId(customRuleInfo);
        customRule.component = new ComponentName("android", "ScheduleConditionProvider");
        customRule.zenPolicy = new ZenPolicy.Builder()
                .allowReminders(true)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .build();
        automaticRules.put("customRule", customRule);

        ZenModeConfig.ZenRule defaultScheduleRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo defaultScheduleRuleInfo = new ScheduleInfo();
        defaultScheduleRule.enabled = false;
        defaultScheduleRule.name = "Default Schedule Rule";
        defaultScheduleRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        defaultScheduleRule.conditionId = ZenModeConfig.toScheduleConditionId(
                defaultScheduleRuleInfo);
        defaultScheduleRule.id = ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID;
        defaultScheduleRule.zenPolicy = new ZenPolicy.Builder()
                .allowEvents(true)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_ANYONE)
                .build();
        automaticRules.put(ZenModeConfig.EVERY_NIGHT_DEFAULT_RULE_ID, defaultScheduleRule);

        ZenModeConfig.ZenRule defaultEventRule = new ZenModeConfig.ZenRule();
        final ScheduleInfo defaultEventRuleInfo = new ScheduleInfo();
        defaultEventRule.enabled = false;
        defaultEventRule.name = "Default Event Rule";
        defaultEventRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        defaultEventRule.conditionId = ZenModeConfig.toScheduleConditionId(
                defaultEventRuleInfo);
        defaultEventRule.id = ZenModeConfig.EVENTS_DEFAULT_RULE_ID;
        defaultScheduleRule.zenPolicy = new ZenPolicy.Builder()
                .allowAlarms(false)
                .allowMedia(false)
                .allowRepeatCallers(false)
                .build();
        automaticRules.put(ZenModeConfig.EVENTS_DEFAULT_RULE_ID, defaultEventRule);

        mZenModeHelperSpy.mConfig.automaticRules = automaticRules;

        // set previous version
        ByteArrayOutputStream baos = writeXmlAndPurge(5);
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        mZenModeHelperSpy.readXml(parser, false, UserHandle.USER_ALL);

        // check default rules
        ArrayMap<String, ZenModeConfig.ZenRule> rules = mZenModeHelperSpy.mConfig.automaticRules;
        assertTrue(rules.size() != 0);
        for (String defaultId : ZenModeConfig.DEFAULT_RULE_IDS) {
            assertTrue(rules.containsKey(defaultId));
        }
        assertTrue(rules.containsKey("customRule"));

        setupZenConfigMaintained();

        List<StatsEvent> events = new LinkedList<>();
        mZenModeHelperSpy.pullRules(events);
        assertEquals(4, events.size());
    }

    @Test
    public void testCountdownConditionSubscription() throws Exception {
        ZenModeConfig config = new ZenModeConfig();
        mZenModeHelperSpy.mConfig = config;
        mZenModeHelperSpy.mConditions.evaluateConfig(mZenModeHelperSpy.mConfig, null, true);
        assertEquals(0, mZenModeHelperSpy.mConditions.mSubscriptions.size());

        mZenModeHelperSpy.mConfig.manualRule = new ZenModeConfig.ZenRule();
        Uri conditionId = ZenModeConfig.toCountdownConditionId(9000000, false);
        mZenModeHelperSpy.mConfig.manualRule.conditionId = conditionId;
        mZenModeHelperSpy.mConfig.manualRule.component = new ComponentName("android",
                CountdownConditionProvider.class.getName());
        mZenModeHelperSpy.mConfig.manualRule.condition = new Condition(conditionId, "", "", "", 0,
                Condition.STATE_TRUE, Condition.FLAG_RELEVANT_NOW);
        mZenModeHelperSpy.mConfig.manualRule.enabled = true;
        ZenModeConfig originalConfig = mZenModeHelperSpy.mConfig.copy();

        mZenModeHelperSpy.mConditions.evaluateConfig(mZenModeHelperSpy.mConfig, null, true);

        assertEquals(true, ZenModeConfig.isValidCountdownConditionId(conditionId));
        assertEquals(originalConfig, mZenModeHelperSpy.mConfig);
        assertEquals(1, mZenModeHelperSpy.mConditions.mSubscriptions.size());
    }

    @Test
    public void testEmptyDefaultRulesMap() {
        List<StatsEvent> events = new LinkedList<>();
        ZenModeConfig config = new ZenModeConfig();
        config.automaticRules = new ArrayMap<>();
        mZenModeHelperSpy.mConfig = config;
        mZenModeHelperSpy.updateDefaultZenRules(); // shouldn't throw null pointer
        mZenModeHelperSpy.pullRules(events); // shouldn't throw null pointer
    }

    @Test
    public void testDoNotUpdateModifiedDefaultAutoRule() {
        // mDefaultConfig is set to default config in setup by getDefaultConfigParser
        when(mContext.checkCallingPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        // shouldn't update rule that's been modified
        ZenModeConfig.ZenRule updatedDefaultRule = new ZenModeConfig.ZenRule();
        updatedDefaultRule.modified = true;
        updatedDefaultRule.enabled = false;
        updatedDefaultRule.creationTime = 0;
        updatedDefaultRule.id = SCHEDULE_DEFAULT_RULE_ID;
        updatedDefaultRule.name = "Schedule Default Rule";
        updatedDefaultRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        updatedDefaultRule.conditionId = ZenModeConfig.toScheduleConditionId(new ScheduleInfo());
        updatedDefaultRule.component = new ComponentName("android", "ScheduleConditionProvider");

        ArrayMap<String, ZenModeConfig.ZenRule> autoRules = new ArrayMap<>();
        autoRules.put(SCHEDULE_DEFAULT_RULE_ID, updatedDefaultRule);
        mZenModeHelperSpy.mConfig.automaticRules = autoRules;

        mZenModeHelperSpy.updateDefaultZenRules();
        assertEquals(updatedDefaultRule,
                mZenModeHelperSpy.mConfig.automaticRules.get(SCHEDULE_DEFAULT_RULE_ID));
    }

    @Test
    public void testDoNotUpdateEnabledDefaultAutoRule() {
        // mDefaultConfig is set to default config in setup by getDefaultConfigParser
        when(mContext.checkCallingPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        // shouldn't update the rule that's enabled
        ZenModeConfig.ZenRule updatedDefaultRule = new ZenModeConfig.ZenRule();
        updatedDefaultRule.enabled = true;
        updatedDefaultRule.modified = false;
        updatedDefaultRule.creationTime = 0;
        updatedDefaultRule.id = SCHEDULE_DEFAULT_RULE_ID;
        updatedDefaultRule.name = "Schedule Default Rule";
        updatedDefaultRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        updatedDefaultRule.conditionId = ZenModeConfig.toScheduleConditionId(new ScheduleInfo());
        updatedDefaultRule.component = new ComponentName("android", "ScheduleConditionProvider");

        ArrayMap<String, ZenModeConfig.ZenRule> autoRules = new ArrayMap<>();
        autoRules.put(SCHEDULE_DEFAULT_RULE_ID, updatedDefaultRule);
        mZenModeHelperSpy.mConfig.automaticRules = autoRules;

        mZenModeHelperSpy.updateDefaultZenRules();
        assertEquals(updatedDefaultRule,
                mZenModeHelperSpy.mConfig.automaticRules.get(SCHEDULE_DEFAULT_RULE_ID));
    }

    @Test
    public void testUpdateDefaultAutoRule() {
        // mDefaultConfig is set to default config in setup by getDefaultConfigParser
        final String defaultRuleName = "rule name test";
        when(mContext.checkCallingPermission(anyString()))
                .thenReturn(PackageManager.PERMISSION_GRANTED);

        // will update rule that is not enabled and modified
        ZenModeConfig.ZenRule customDefaultRule = new ZenModeConfig.ZenRule();
        customDefaultRule.enabled = false;
        customDefaultRule.modified = false;
        customDefaultRule.creationTime = 0;
        customDefaultRule.id = SCHEDULE_DEFAULT_RULE_ID;
        customDefaultRule.name = "Schedule Default Rule";
        customDefaultRule.zenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        customDefaultRule.conditionId = ZenModeConfig.toScheduleConditionId(new ScheduleInfo());
        customDefaultRule.component = new ComponentName("android", "ScheduleConditionProvider");

        ArrayMap<String, ZenModeConfig.ZenRule> autoRules = new ArrayMap<>();
        autoRules.put(SCHEDULE_DEFAULT_RULE_ID, customDefaultRule);
        mZenModeHelperSpy.mConfig.automaticRules = autoRules;

        mZenModeHelperSpy.updateDefaultZenRules();
        ZenModeConfig.ZenRule ruleAfterUpdating =
                mZenModeHelperSpy.mConfig.automaticRules.get(SCHEDULE_DEFAULT_RULE_ID);
        assertEquals(customDefaultRule.enabled, ruleAfterUpdating.enabled);
        assertEquals(customDefaultRule.modified, ruleAfterUpdating.modified);
        assertEquals(customDefaultRule.id, ruleAfterUpdating.id);
        assertEquals(customDefaultRule.conditionId, ruleAfterUpdating.conditionId);
        assertFalse(Objects.equals(defaultRuleName, ruleAfterUpdating.name)); // update name
    }

    @Test
    public void testAddAutomaticZenRule() {
        AutomaticZenRule zenRule = new AutomaticZenRule("name",
                new ComponentName("android", "ScheduleConditionProvider"),
                ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
        String id = mZenModeHelperSpy.addAutomaticZenRule(zenRule, "test");

        assertTrue(id != null);
        ZenModeConfig.ZenRule ruleInConfig = mZenModeHelperSpy.mConfig.automaticRules.get(id);
        assertTrue(ruleInConfig != null);
        assertEquals(zenRule.isEnabled(), ruleInConfig.enabled);
        assertEquals(zenRule.isModified(), ruleInConfig.modified);
        assertEquals(zenRule.getConditionId(), ruleInConfig.conditionId);
        assertEquals(NotificationManager.zenModeFromInterruptionFilter(
                zenRule.getInterruptionFilter(), -1), ruleInConfig.zenMode);
        assertEquals(zenRule.getName(), ruleInConfig.name);
    }

    @Test
    public void testAddAutomaticZenRule_beyondSystemLimit() {
        for (int i = 0; i < RULE_LIMIT_PER_PACKAGE; i++) {
            ScheduleInfo si = new ScheduleInfo();
            si.startHour = i;
            AutomaticZenRule zenRule = new AutomaticZenRule("name" + i,
                    null,
                    new ComponentName("android", "ScheduleConditionProvider"),
                    ZenModeConfig.toScheduleConditionId(si),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelperSpy.addAutomaticZenRule(zenRule, "test");
            assertNotNull(id);
        }
        try {
            AutomaticZenRule zenRule = new AutomaticZenRule("name",
                    null,
                    new ComponentName("android", "ScheduleConditionProvider"),
                    ZenModeConfig.toScheduleConditionId(new ScheduleInfo()),
                    new ZenPolicy.Builder().build(),
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY, true);
            String id = mZenModeHelperSpy.addAutomaticZenRule(zenRule, "test");
            fail("allowed too many rules to be created");
        } catch (IllegalArgumentException e) {
            // yay
        }
    }

    private void setupZenConfig() {
        mZenModeHelperSpy.mZenMode = ZEN_MODE_IMPORTANT_INTERRUPTIONS;
        mZenModeHelperSpy.mConfig.allowAlarms = false;
        mZenModeHelperSpy.mConfig.allowMedia = false;
        mZenModeHelperSpy.mConfig.allowSystem = false;
        mZenModeHelperSpy.mConfig.allowReminders = true;
        mZenModeHelperSpy.mConfig.allowCalls = true;
        mZenModeHelperSpy.mConfig.allowMessages = true;
        mZenModeHelperSpy.mConfig.allowEvents = true;
        mZenModeHelperSpy.mConfig.allowRepeatCallers= true;
        mZenModeHelperSpy.mConfig.suppressedVisualEffects = SUPPRESSED_EFFECT_BADGE;
        mZenModeHelperSpy.mConfig.manualRule = null;
    }

    private void setupZenConfigMaintained() {
        // config is still the same as when it was setup (setupZenConfig)
        assertFalse(mZenModeHelperSpy.mConfig.allowAlarms);
        assertFalse(mZenModeHelperSpy.mConfig.allowMedia);
        assertFalse(mZenModeHelperSpy.mConfig.allowSystem);
        assertTrue(mZenModeHelperSpy.mConfig.allowReminders);
        assertTrue(mZenModeHelperSpy.mConfig.allowCalls);
        assertTrue(mZenModeHelperSpy.mConfig.allowMessages);
        assertTrue(mZenModeHelperSpy.mConfig.allowEvents);
        assertTrue(mZenModeHelperSpy.mConfig.allowRepeatCallers);
        assertEquals(SUPPRESSED_EFFECT_BADGE, mZenModeHelperSpy.mConfig.suppressedVisualEffects);
    }

    /**
     * Wrapper to use XmlPullParser as XmlResourceParser for Resources.getXml()
     */
    final class XmlResourceParserImpl implements XmlResourceParser {
        private XmlPullParser parser;

        public XmlResourceParserImpl(XmlPullParser parser) {
            this.parser = parser;
        }

        public int getEventType() throws XmlPullParserException {
            return parser.getEventType();
        }

        @Override
        public void setFeature(String name, boolean state) throws XmlPullParserException {
            parser.setFeature(name, state);
        }

        @Override
        public boolean getFeature(String name) {
            return false;
        }

        @Override
        public void setProperty(String name, Object value) throws XmlPullParserException {
            parser.setProperty(name, value);
        }

        @Override
        public Object getProperty(String name) {
            return parser.getProperty(name);
        }

        @Override
        public void setInput(Reader in) throws XmlPullParserException {
            parser.setInput(in);
        }

        @Override
        public void setInput(InputStream inputStream, String inputEncoding)
                throws XmlPullParserException {
            parser.setInput(inputStream, inputEncoding);
        }

        @Override
        public String getInputEncoding() {
            return parser.getInputEncoding();
        }

        @Override
        public void defineEntityReplacementText(String entityName, String replacementText)
                throws XmlPullParserException {
            parser.defineEntityReplacementText(entityName, replacementText);
        }

        @Override
        public int getNamespaceCount(int depth) throws XmlPullParserException {
            return parser.getNamespaceCount(depth);
        }

        @Override
        public String getNamespacePrefix(int pos) throws XmlPullParserException {
            return parser.getNamespacePrefix(pos);
        }

        @Override
        public String getNamespaceUri(int pos) throws XmlPullParserException {
            return parser.getNamespaceUri(pos);
        }

        @Override
        public String getNamespace(String prefix) {
            return parser.getNamespace(prefix);
        }

        @Override
        public int getDepth() {
            return parser.getDepth();
        }

        @Override
        public String getPositionDescription() {
            return parser.getPositionDescription();
        }

        @Override
        public int getLineNumber() {
            return parser.getLineNumber();
        }

        @Override
        public int getColumnNumber() {
            return parser.getColumnNumber();
        }

        @Override
        public boolean isWhitespace() throws XmlPullParserException {
            return parser.isWhitespace();
        }

        @Override
        public String getText() {
            return parser.getText();
        }

        @Override
        public char[] getTextCharacters(int[] holderForStartAndLength) {
            return parser.getTextCharacters(holderForStartAndLength);
        }

        @Override
        public String getNamespace() {
            return parser.getNamespace();
        }

        @Override
        public String getName() {
            return parser.getName();
        }

        @Override
        public String getPrefix() {
            return parser.getPrefix();
        }

        @Override
        public boolean isEmptyElementTag() throws XmlPullParserException {
            return false;
        }

        @Override
        public int getAttributeCount() {
            return parser.getAttributeCount();
        }

        public int next() throws IOException, XmlPullParserException {
            return parser.next();
        }

        @Override
        public int nextToken() throws XmlPullParserException, IOException {
            return parser.next();
        }

        @Override
        public void require(int type, String namespace, String name)
                throws XmlPullParserException, IOException {
            parser.require(type, namespace, name);
        }

        @Override
        public String nextText() throws XmlPullParserException, IOException {
            return parser.nextText();
        }

        @Override
        public String getAttributeNamespace(int index) {
            return "";
        }

        @Override
        public String getAttributeName(int index) {
            return parser.getAttributeName(index);
        }

        @Override
        public String getAttributePrefix(int index) {
            return parser.getAttributePrefix(index);
        }

        @Override
        public String getAttributeType(int index) {
            return parser.getAttributeType(index);
        }

        @Override
        public boolean isAttributeDefault(int index) {
            return parser.isAttributeDefault(index);
        }

        @Override
        public String getAttributeValue(int index) {
            return parser.getAttributeValue(index);
        }

        @Override
        public String getAttributeValue(String namespace, String name) {
            return parser.getAttributeValue(namespace, name);
        }

        @Override
        public int getAttributeNameResource(int index) {
            return 0;
        }

        @Override
        public int getAttributeListValue(String namespace, String attribute, String[] options,
                int defaultValue) {
            return 0;
        }

        @Override
        public boolean getAttributeBooleanValue(String namespace, String attribute,
                boolean defaultValue) {
            return false;
        }

        @Override
        public int getAttributeResourceValue(String namespace, String attribute, int defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeIntValue(String namespace, String attribute, int defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeUnsignedIntValue(String namespace, String attribute,
                int defaultValue) {
            return 0;
        }

        @Override
        public float getAttributeFloatValue(String namespace, String attribute,
                float defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeListValue(int index, String[] options, int defaultValue) {
            return 0;
        }

        @Override
        public boolean getAttributeBooleanValue(int index, boolean defaultValue) {
            return false;
        }

        @Override
        public int getAttributeResourceValue(int index, int defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeIntValue(int index, int defaultValue) {
            return 0;
        }

        @Override
        public int getAttributeUnsignedIntValue(int index, int defaultValue) {
            return 0;
        }

        @Override
        public float getAttributeFloatValue(int index, float defaultValue) {
            return 0;
        }

        @Override
        public String getIdAttribute() {
            return null;
        }

        @Override
        public String getClassAttribute() {
            return null;
        }

        @Override
        public int getIdAttributeResourceValue(int defaultValue) {
            return 0;
        }

        @Override
        public int getStyleAttribute() {
            return 0;
        }

        @Override
        public void close() {
        }

        @Override
        public int nextTag() throws IOException, XmlPullParserException {
            return parser.nextTag();
        }
    }
}
