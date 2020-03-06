/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.volume;

import static org.junit.Assert.assertEquals;

import android.media.AudioManager;
import android.media.AudioSystem;
import android.metrics.LogMaker;
import android.provider.Settings;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.testing.FakeMetricsLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;
import java.util.Queue;

/**
 * Parameterized unit test for Events.logEvent.
 *
 * This test captures a translation table between the Event class tags, the debugging logs,
 * the event-buffer logs, and the statsd logs.
 *
 * This test works as a straight JUnit4 test, but is declared as a SysuiTestCase because
 * AAAPlusPlusVerifySysuiRequiredTestPropertiesTest requires all tests in SystemUiTest extend
 * either SysuiTestCase or SysUiBaseFragmentTest.
 *
 */
@RunWith(Parameterized.class)
@SmallTest
public class EventsTest extends SysuiTestCase {
    private FakeMetricsLogger mLegacyLogger;
    private UiEventLoggerFake mUiEventLogger;

    @Before
    public void setFakeLoggers() {
        mLegacyLogger = new FakeMetricsLogger();
        Events.sLegacyLogger = mLegacyLogger;
        mUiEventLogger = new UiEventLoggerFake();
        Events.sUiEventLogger = mUiEventLogger;
    }

    // Parameters for calling writeEvent with arbitrary args.
    @Parameterized.Parameter
    public int mTag;

    @Parameterized.Parameter(1)
    public Object[] mArgs;

    // Expect returned string exactly matches.
    @Parameterized.Parameter(2)
    public String mExpectedMessage;

    // Expect these MetricsLogger calls.

    @Parameterized.Parameter(3)
    public int[] mExpectedMetrics;

    // Expect this UiEvent (use null if there isn't one).
    @Parameterized.Parameter(4)
    public UiEventLogger.UiEventEnum mUiEvent;

    @Test
    public void testLogEvent() {
        String result = Events.logEvent(mTag, mArgs);
        assertEquals("Show Dialog", mExpectedMessage, result);

        Queue<LogMaker> logs = mLegacyLogger.getLogs();
        if (mExpectedMetrics == null) {
            assertEquals(0, logs.size());
        } else {
            assertEquals(mExpectedMetrics.length, logs.size());
            if (mExpectedMetrics.length > 0) {
                assertEquals(mExpectedMetrics[0], logs.remove().getCategory());
            }
            if (mExpectedMetrics.length > 1) {
                assertEquals(mExpectedMetrics[1], logs.remove().getCategory());
            }
        }
        if (mUiEvent != null) {
            assertEquals(1, mUiEventLogger.numLogs());
            assertEquals(mUiEvent.getId(), mUiEventLogger.eventId(0));
        }
    }

    @Parameterized.Parameters(name = "{index}: {2}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{
                {Events.EVENT_SETTINGS_CLICK, null,
                        "writeEvent settings_click",
                        new int[]{MetricsEvent.ACTION_VOLUME_SETTINGS},
                        Events.VolumeDialogEvent.VOLUME_DIALOG_SETTINGS_CLICK},
                {Events.EVENT_SHOW_DIALOG, new Object[]{Events.SHOW_REASON_VOLUME_CHANGED, false},
                        "writeEvent show_dialog volume_changed keyguard=false",
                        new int[]{MetricsEvent.VOLUME_DIALOG,
                                MetricsEvent.RESERVED_FOR_LOGBUILDER_HISTOGRAM},
                        Events.VolumeDialogOpenEvent.VOLUME_DIALOG_SHOW_VOLUME_CHANGED},
                {Events.EVENT_EXPAND, new Object[]{true},
                        "writeEvent expand true",
                        new int[]{MetricsEvent.VOLUME_DIALOG_DETAILS},
                        Events.VolumeDialogEvent.VOLUME_DIALOG_EXPAND_DETAILS},
                {Events.EVENT_DISMISS_DIALOG,
                        new Object[]{Events.DISMISS_REASON_TOUCH_OUTSIDE, true},
                        "writeEvent dismiss_dialog touch_outside",
                        new int[]{MetricsEvent.VOLUME_DIALOG},
                        Events.VolumeDialogCloseEvent.VOLUME_DIALOG_DISMISS_TOUCH_OUTSIDE},
                {Events.EVENT_ACTIVE_STREAM_CHANGED, new Object[]{AudioSystem.STREAM_ACCESSIBILITY},
                        "writeEvent active_stream_changed STREAM_ACCESSIBILITY",
                        new int[]{MetricsEvent.ACTION_VOLUME_STREAM},
                        Events.VolumeDialogEvent.VOLUME_DIALOG_ACTIVE_STREAM_CHANGED},
                {Events.EVENT_ICON_CLICK,
                        new Object[]{AudioSystem.STREAM_MUSIC, Events.ICON_STATE_MUTE},
                        "writeEvent icon_click STREAM_MUSIC mute",
                        new int[]{MetricsEvent.ACTION_VOLUME_ICON},
                        Events.VolumeDialogEvent.VOLUME_DIALOG_MUTE_STREAM},
                {Events.EVENT_TOUCH_LEVEL_DONE,
                        new Object[]{AudioSystem.STREAM_MUSIC, /* volume */ 0},
                        "writeEvent touch_level_done STREAM_MUSIC 0",
                        new int[]{MetricsEvent.ACTION_VOLUME_SLIDER},
                        Events.VolumeDialogEvent.VOLUME_DIALOG_SLIDER_TO_ZERO},
                {Events.EVENT_TOUCH_LEVEL_DONE,
                        new Object[]{AudioSystem.STREAM_MUSIC, /* volume */ 1},
                        "writeEvent touch_level_done STREAM_MUSIC 1",
                        new int[]{MetricsEvent.ACTION_VOLUME_SLIDER},
                        Events.VolumeDialogEvent.VOLUME_DIALOG_SLIDER},
                {Events.EVENT_TOUCH_LEVEL_CHANGED,
                        new Object[]{AudioSystem.STREAM_MUSIC, /* volume */ 0},
                        "writeEvent touch_level_changed STREAM_MUSIC 0",
                        null, null},
                {Events.EVENT_LEVEL_CHANGED,
                        new Object[]{AudioSystem.STREAM_MUSIC, /* volume */ 0},
                        "writeEvent level_changed STREAM_MUSIC 0",
                        null, null},
                {Events.EVENT_MUTE_CHANGED,
                        new Object[]{AudioSystem.STREAM_MUSIC, /* volume */ 0},
                        "writeEvent mute_changed STREAM_MUSIC 0",
                        null, null},
                {Events.EVENT_KEY,
                        new Object[]{AudioSystem.STREAM_MUSIC, /* volume */ 0},
                        "writeEvent key STREAM_MUSIC 0",
                        new int[]{MetricsEvent.ACTION_VOLUME_KEY},
                        Events.VolumeDialogEvent.VOLUME_KEY_TO_ZERO},
                {Events.EVENT_KEY,
                        new Object[]{AudioSystem.STREAM_MUSIC, /* volume */ 1},
                        "writeEvent key STREAM_MUSIC 1",
                        new int[]{MetricsEvent.ACTION_VOLUME_KEY},
                        Events.VolumeDialogEvent.VOLUME_KEY},
                {Events.EVENT_RINGER_TOGGLE, new Object[]{AudioManager.RINGER_MODE_NORMAL},
                        "writeEvent ringer_toggle normal",
                        new int[]{MetricsEvent.ACTION_VOLUME_RINGER_TOGGLE},
                        Events.VolumeDialogEvent.RINGER_MODE_NORMAL},
                {Events.EVENT_EXTERNAL_RINGER_MODE_CHANGED,
                        new Object[]{AudioManager.RINGER_MODE_NORMAL},
                        "writeEvent external_ringer_mode_changed normal",
                        new int[]{MetricsEvent.ACTION_RINGER_MODE},
                        null},
                {Events.EVENT_INTERNAL_RINGER_MODE_CHANGED,
                        new Object[]{AudioManager.RINGER_MODE_NORMAL},
                        "writeEvent internal_ringer_mode_changed normal",
                        null, null},
                {Events.EVENT_ZEN_MODE_CHANGED,
                        new Object[]{Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS},
                        "writeEvent zen_mode_changed important_interruptions",
                        null, Events.ZenModeEvent.ZEN_MODE_IMPORTANT_ONLY},
                {Events.EVENT_ZEN_MODE_CHANGED,
                        new Object[]{Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS},
                        "writeEvent zen_mode_changed important_interruptions",
                        null, Events.ZenModeEvent.ZEN_MODE_IMPORTANT_ONLY},
                {Events.EVENT_SUPPRESSOR_CHANGED,
                        new Object[]{"component", "name"},
                        "writeEvent suppressor_changed component name",
                        null, null},
                {Events.EVENT_SHOW_USB_OVERHEAT_ALARM,
                        new Object[]{Events.SHOW_REASON_USB_OVERHEAD_ALARM_CHANGED, true},
                        "writeEvent show_usb_overheat_alarm usb_temperature_above_threshold "
                                + "keyguard=true",
                        new int[]{MetricsEvent.POWER_OVERHEAT_ALARM,
                                MetricsEvent.RESERVED_FOR_LOGBUILDER_HISTOGRAM},
                        Events.VolumeDialogEvent.USB_OVERHEAT_ALARM},
                {Events.EVENT_DISMISS_USB_OVERHEAT_ALARM,
                        new Object[]{Events.DISMISS_REASON_USB_OVERHEAD_ALARM_CHANGED, true},
                        "writeEvent dismiss_usb_overheat_alarm usb_temperature_below_threshold "
                                + "keyguard=true",
                        new int[]{MetricsEvent.POWER_OVERHEAT_ALARM,
                                MetricsEvent.RESERVED_FOR_LOGBUILDER_HISTOGRAM},
                        Events.VolumeDialogEvent.USB_OVERHEAT_ALARM_DISMISSED},
        });
    }
}

