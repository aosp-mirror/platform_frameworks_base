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

package com.android.server.display;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.doReturn;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.provider.Settings.System;
import android.test.mock.MockContentResolver;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.ColorDisplayController;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class ColorDisplayServiceTest {

    private Context mContext;
    private int mUserId;

    private MockTwilightManager mTwilightManager;

    private ColorDisplayController mColorDisplayController;
    private ColorDisplayService mColorDisplayService;

    @Before
    public void setUp() {
        mContext = Mockito.spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        mUserId = ActivityManager.getCurrentUser();

        doReturn(mContext).when(mContext).getApplicationContext();

        final MockContentResolver cr = new MockContentResolver(mContext);
        cr.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        doReturn(cr).when(mContext).getContentResolver();

        final AlarmManager am = Mockito.mock(AlarmManager.class);
        doReturn(am).when(mContext).getSystemService(Context.ALARM_SERVICE);

        final DisplayTransformManager dtm = Mockito.mock(DisplayTransformManager.class);
        LocalServices.addService(DisplayTransformManager.class, dtm);

        mTwilightManager = new MockTwilightManager();
        LocalServices.addService(TwilightManager.class, mTwilightManager);

        mColorDisplayController = new ColorDisplayController(mContext, mUserId);
        mColorDisplayService = new ColorDisplayService(mContext);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(DisplayTransformManager.class);
        LocalServices.removeServiceForTest(TwilightManager.class);

        mColorDisplayService = null;
        mColorDisplayController = null;

        mTwilightManager = null;

        mUserId = UserHandle.USER_NULL;
        mContext = null;
    }

    @Test
    public void customSchedule_whenStartedAfterNight_ifOffAfterNight_turnsOff() {
        setAutoModeCustom(-120 /* startTimeOffset */, -60 /* endTimeOffset */);
        setActivated(false /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedAfterNight_ifOffBeforeNight_turnsOff() {
        setAutoModeCustom(-120 /* startTimeOffset */, -60 /* endTimeOffset */);
        setActivated(false /* activated */, -180 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedAfterNight_ifOffDuringNight_turnsOff() {
        setAutoModeCustom(-120 /* startTimeOffset */, -60 /* endTimeOffset */);
        setActivated(false /* activated */, -90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedAfterNight_ifOffInFuture_turnsOff() {
        setAutoModeCustom(-120 /* startTimeOffset */, -60 /* endTimeOffset */);
        setActivated(false /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedAfterNight_ifOnAfterNight_turnsOn() {
        setAutoModeCustom(-120 /* startTimeOffset */, -60 /* endTimeOffset */);
        setActivated(true /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void customSchedule_whenStartedAfterNight_ifOnBeforeNight_turnsOff() {
        setAutoModeCustom(-120 /* startTimeOffset */, -60 /* endTimeOffset */);
        setActivated(true /* activated */, -180 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedAfterNight_ifOnDuringNight_turnsOff() {
        setAutoModeCustom(-120 /* startTimeOffset */, -60 /* endTimeOffset */);
        setActivated(true /* activated */, -90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedAfterNight_ifOnInFuture_turnsOff() {
        setAutoModeCustom(-120 /* startTimeOffset */, -60 /* endTimeOffset */);
        setActivated(true /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedBeforeNight_ifOffAfterNight_turnsOff() {
        setAutoModeCustom(60 /* startTimeOffset */, 120 /* endTimeOffset */);
        setActivated(false /* activated */, 180 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedBeforeNight_ifOffBeforeNight_turnsOff() {
        setAutoModeCustom(60 /* startTimeOffset */, 120 /* endTimeOffset */);
        setActivated(false /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedBeforeNight_ifOffDuringNight_turnsOff() {
        setAutoModeCustom(60 /* startTimeOffset */, 120 /* endTimeOffset */);
        setActivated(false /* activated */, 90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedBeforeNight_ifOffInPast_turnsOff() {
        setAutoModeCustom(60 /* startTimeOffset */, 120 /* endTimeOffset */);
        setActivated(false /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedBeforeNight_ifOnAfterNight_turnsOff() {
        setAutoModeCustom(60 /* startTimeOffset */, 120 /* endTimeOffset */);
        setActivated(true /* activated */, 180 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedBeforeNight_ifOnBeforeNight_turnsOff() {
        setAutoModeCustom(60 /* startTimeOffset */, 120 /* endTimeOffset */);
        setActivated(true /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedBeforeNight_ifOnDuringNight_turnsOff() {
        setAutoModeCustom(60 /* startTimeOffset */, 120 /* endTimeOffset */);
        setActivated(true /* activated */, 90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedBeforeNight_ifOnInPast_turnsOn() {
        setAutoModeCustom(60 /* startTimeOffset */, 120 /* endTimeOffset */);
        setActivated(true /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void customSchedule_whenStartedDuringNight_ifOffAfterNight_turnsOn() {
        setAutoModeCustom(-60 /* startTimeOffset */, 60 /* endTimeOffset */);
        setActivated(false /* activated */, 90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void customSchedule_whenStartedDuringNight_ifOffBeforeNight_turnsOn() {
        setAutoModeCustom(-60 /* startTimeOffset */, 60 /* endTimeOffset */);
        setActivated(false /* activated */, -90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void customSchedule_whenStartedDuringNight_ifOffDuringNightInFuture_turnsOn() {
        setAutoModeCustom(-60 /* startTimeOffset */, 60 /* endTimeOffset */);
        setActivated(false /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void customSchedule_whenStartedDuringNight_ifOffDuringNightInPast_turnsOff() {
        setAutoModeCustom(-60 /* startTimeOffset */, 60 /* endTimeOffset */);
        setActivated(false /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void customSchedule_whenStartedDuringNight_ifOnAfterNight_turnsOn() {
        setAutoModeCustom(-60 /* startTimeOffset */, 60 /* endTimeOffset */);
        setActivated(true /* activated */, 90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void customSchedule_whenStartedDuringNight_ifOnBeforeNight_turnsOn() {
        setAutoModeCustom(-60 /* startTimeOffset */, 60 /* endTimeOffset */);
        setActivated(true /* activated */, -90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void customSchedule_whenStartedDuringNight_ifOnDuringNightInFuture_turnsOn() {
        setAutoModeCustom(-60 /* startTimeOffset */, 60 /* endTimeOffset */);
        setActivated(true /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void customSchedule_whenStartedDuringNight_ifOnDuringNightInPast_turnsOn() {
        setAutoModeCustom(-60 /* startTimeOffset */, 60 /* endTimeOffset */);
        setActivated(true /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedAfterNight_ifOffAfterNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(false /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedAfterNight_ifOffBeforeNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(false /* activated */, -180 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedAfterNight_ifOffDuringNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(false /* activated */, -90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedAfterNight_ifOffInFuture_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(false /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedAfterNight_ifOnAfterNight_turnsOn() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(true /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedAfterNight_ifOnBeforeNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(true /* activated */, -180 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedAfterNight_ifOnDuringNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(true /* activated */, -90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedAfterNight_ifOnInFuture_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(true /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedBeforeNight_ifOffAfterNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(false /* activated */, 180 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedBeforeNight_ifOffBeforeNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(false /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedBeforeNight_ifOffDuringNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(false /* activated */, 90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedBeforeNight_ifOffInPast_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(false /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedBeforeNight_ifOnAfterNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(true /* activated */, 180 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedBeforeNight_ifOnBeforeNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(true /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedBeforeNight_ifOnDuringNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(true /* activated */, 90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedBeforeNight_ifOnInPast_turnsOn() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(true /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedDuringNight_ifOffAfterNight_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(false /* activated */, 90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedDuringNight_ifOffBeforeNight_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(false /* activated */, -90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedDuringNight_ifOffDuringNightInFuture_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(false /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedDuringNight_ifOffDuringNightInPast_turnsOff() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(false /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedDuringNight_ifOnAfterNight_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(true /* activated */, 90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedDuringNight_ifOnBeforeNight_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(true /* activated */, -90 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedDuringNight_ifOnDuringNightInFuture_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(true /* activated */, 30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenStartedDuringNight_ifOnDuringNightInPast_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(true /* activated */, -30 /* lastActivatedTimeOffset */);

        startService();
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedAfterNight_ifOffAfterNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(false /* activated */, -30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedAfterNight_ifOffBeforeNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(false /* activated */, -180 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedAfterNight_ifOffDuringNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(false /* activated */, -90 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedAfterNight_ifOffInFuture_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(false /* activated */, 30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedAfterNight_ifOnAfterNight_turnsOn() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(true /* activated */, -30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedAfterNight_ifOnBeforeNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(true /* activated */, -180 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedAfterNight_ifOnDuringNight_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(true /* activated */, -90 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedAfterNight_ifOnInFuture_turnsOff() {
        setAutoModeTwilight(-120 /* sunsetOffset */, -60 /* sunriseOffset */);
        setActivated(true /* activated */, 30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedBeforeNight_ifOffAfterNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(false /* activated */, 180 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedBeforeNight_ifOffBeforeNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(false /* activated */, 30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedBeforeNight_ifOffDuringNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(false /* activated */, 90 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedBeforeNight_ifOffInPast_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(false /* activated */, -30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedBeforeNight_ifOnAfterNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(true /* activated */, 180 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedBeforeNight_ifOnBeforeNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(true /* activated */, 30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedBeforeNight_ifOnDuringNight_turnsOff() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(true /* activated */, 90 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedBeforeNight_ifOnInPast_turnsOn() {
        setAutoModeTwilight(60 /* sunsetOffset */, 120 /* sunriseOffset */);
        setActivated(true /* activated */, -30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedDuringNight_ifOffAfterNight_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(false /* activated */, 90 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedDuringNight_ifOffBeforeNight_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(false /* activated */, -90 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedDuringNight_ifOffDuringNightInFuture_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(false /* activated */, 30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedDuringNight_ifOffDuringNightInPast_turnsOff() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(false /* activated */, -30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(false /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(false /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedDuringNight_ifOnAfterNight_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(true /* activated */, 90 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedDuringNight_ifOnBeforeNight_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(true /* activated */, -90 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedDuringNight_ifOnDuringNightInFuture_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(true /* activated */, 30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(true /* activated */);
    }

    @Test
    public void twilightSchedule_whenRebootedDuringNight_ifOnDuringNightInPast_turnsOn() {
        setAutoModeTwilight(-60 /* sunsetOffset */, 60 /* sunriseOffset */);
        setActivated(true /* activated */, -30 /* lastActivatedTimeOffset */);

        final TwilightState state = mTwilightManager.getLastTwilightState();
        mTwilightManager.setTwilightState(null);

        startService();
        assertActivated(true /* activated */);

        mTwilightManager.setTwilightState(state);
        assertActivated(true /* activated */);
    }

    @Test
    public void accessibility_colorInversion_transformActivated() {
        if (!mContext.getResources().getConfiguration().isScreenWideColorGamut()) {
            return;
        }

        setAccessibilityColorInversion(true);
        setColorMode(ColorDisplayController.COLOR_MODE_NATURAL);

        startService();
        assertAccessibilityTransformActivated(true /* activated */ );
        assertUserColorMode(ColorDisplayController.COLOR_MODE_NATURAL);
        assertActiveColorMode(ColorDisplayController.COLOR_MODE_SATURATED);
    }

    @Test
    public void accessibility_colorCorrection_transformActivated() {
        if (!mContext.getResources().getConfiguration().isScreenWideColorGamut()) {
            return;
        }

        setAccessibilityColorCorrection(true);
        setColorMode(ColorDisplayController.COLOR_MODE_NATURAL);

        startService();
        assertAccessibilityTransformActivated(true /* activated */ );
        assertUserColorMode(ColorDisplayController.COLOR_MODE_NATURAL);
        assertActiveColorMode(ColorDisplayController.COLOR_MODE_SATURATED);
    }

    @Test
    public void accessibility_all_transformActivated() {
        if (!mContext.getResources().getConfiguration().isScreenWideColorGamut()) {
            return;
        }

        setAccessibilityColorCorrection(true);
        setAccessibilityColorInversion(true);
        setColorMode(ColorDisplayController.COLOR_MODE_NATURAL);

        startService();
        assertAccessibilityTransformActivated(true /* activated */ );
        assertUserColorMode(ColorDisplayController.COLOR_MODE_NATURAL);
        assertActiveColorMode(ColorDisplayController.COLOR_MODE_SATURATED);
    }

    @Test
    public void accessibility_none_transformActivated() {
        if (!mContext.getResources().getConfiguration().isScreenWideColorGamut()) {
            return;
        }

        setAccessibilityColorCorrection(false);
        setAccessibilityColorInversion(false);
        setColorMode(ColorDisplayController.COLOR_MODE_NATURAL);

        startService();
        assertAccessibilityTransformActivated(false /* activated */ );
        assertUserColorMode(ColorDisplayController.COLOR_MODE_NATURAL);
        assertActiveColorMode(ColorDisplayController.COLOR_MODE_NATURAL);
    }

    /**
     * Configures Night display to use a custom schedule.
     *
     * @param startTimeOffset the offset relative to now to activate Night display (in minutes)
     * @param endTimeOffset the offset relative to now to deactivate Night display (in minutes)
     */
    private void setAutoModeCustom(int startTimeOffset, int endTimeOffset) {
        mColorDisplayController.setAutoMode(ColorDisplayController.AUTO_MODE_CUSTOM);
        mColorDisplayController.setCustomStartTime(getLocalTimeRelativeToNow(startTimeOffset));
        mColorDisplayController.setCustomEndTime(getLocalTimeRelativeToNow(endTimeOffset));
    }

    /**
     * Configures Night display to use the twilight schedule.
     *
     * @param sunsetOffset the offset relative to now for sunset (in minutes)
     * @param sunriseOffset the offset relative to now for sunrise (in minutes)
     */
    private void setAutoModeTwilight(int sunsetOffset, int sunriseOffset) {
        mColorDisplayController.setAutoMode(ColorDisplayController.AUTO_MODE_TWILIGHT);
        mTwilightManager.setTwilightState(
                getTwilightStateRelativeToNow(sunsetOffset, sunriseOffset));
    }

    /**
     * Configures the Night display activated state.
     *
     * @param activated {@code true} if Night display should be activated
     * @param lastActivatedTimeOffset the offset relative to now to record that Night display was
     * activated (in minutes)
     */
    private void setActivated(boolean activated, int lastActivatedTimeOffset) {
        mColorDisplayController.setActivated(activated);
        Secure.putStringForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME,
                LocalDateTime.now().plusMinutes(lastActivatedTimeOffset).toString(),
                mUserId);
    }

    /**
     * Configures the Accessibility color correction setting state.
     *
     * @param state {@code true} if color inversion should be activated
     */
    private void setAccessibilityColorCorrection(boolean state) {
        Secure.putIntForUser(mContext.getContentResolver(),
                Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, state ? 1 : 0, mUserId);
    }

    /**
     * Configures the Accessibility color inversion setting state.
     *
     * @param state {@code true} if color inversion should be activated
     */
    private void setAccessibilityColorInversion(boolean state) {
        Secure.putIntForUser(mContext.getContentResolver(),
                Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, state ? 1 : 0, mUserId);
    }

    /**
     * Configures color mode via ColorDisplayController.
     *
     * @param mode the color mode to set
     */
    private void setColorMode(int mode) {
        mColorDisplayController.setColorMode(mode);
    }

    /**
     * Convenience method to start {@link #mColorDisplayService}.
     */
    private void startService() {
        Secure.putIntForUser(mContext.getContentResolver(), Secure.USER_SETUP_COMPLETE, 1, mUserId);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mColorDisplayService.onStart();
                mColorDisplayService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
                mColorDisplayService.onStartUser(mUserId);
            }
        });
    }

    /**
     * Convenience method for asserting whether Night display should be activated.
     *
     * @param activated the expected activated state of Night display
     */
    private void assertActivated(boolean activated) {
        assertWithMessage("Invalid Night display activated state")
                .that(mColorDisplayController.isActivated())
                .isEqualTo(activated);
    }

    /**
     * Convenience method for asserting that Accessibility color transform is detected.
     *
     * @param state {@code true} if any Accessibility transform should be activated
     */
    private void assertAccessibilityTransformActivated(boolean state) {
        assertWithMessage("Unexpected Accessibility color transform state")
                .that(mColorDisplayController.getAccessibilityTransformActivated())
                .isEqualTo(state);
    }

    /**
     * Convenience method for asserting that the active color mode matches expectation.
     *
     * @param mode the expected active color mode.
     */
    private void assertActiveColorMode(int mode) {
        assertWithMessage("Unexpected color mode setting")
                .that(mColorDisplayController.getColorMode())
                .isEqualTo(mode);
    }

    /**
     * Convenience method for asserting that the user chosen color mode matches expectation.
     *
     * @param mode the expected color mode setting.
     */
    private void assertUserColorMode(int mode) {
        final int actualMode = System.getIntForUser(mContext.getContentResolver(),
                System.DISPLAY_COLOR_MODE, -1, mUserId);
        assertWithMessage("Unexpected color mode setting")
                .that(actualMode)
                .isEqualTo(mode);
    }

    /**
     * Convenience for making a {@link LocalTime} instance with an offset relative to now.
     *
     * @param offsetMinutes the offset relative to now (in minutes)
     * @return the LocalTime instance
     */
    private static LocalTime getLocalTimeRelativeToNow(int offsetMinutes) {
        final Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, offsetMinutes);
        return LocalTime.of(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }

    /**
     * Convenience for making a {@link TwilightState} instance with sunrise/sunset relative to now.
     *
     * @param sunsetOffset the offset relative to now for sunset (in minutes)
     * @param sunriseOffset the offset relative to now for sunrise (in minutes)
     * @return the TwilightState instance
     */
    private static TwilightState getTwilightStateRelativeToNow(int sunsetOffset,
            int sunriseOffset) {
        final LocalTime sunset = getLocalTimeRelativeToNow(sunsetOffset);
        final LocalTime sunrise = getLocalTimeRelativeToNow(sunriseOffset);

        final LocalDateTime now = LocalDateTime.now();
        final ZoneId zoneId = ZoneId.systemDefault();

        long sunsetMillis = ColorDisplayService.getDateTimeBefore(sunset, now)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli();
        long sunriseMillis = ColorDisplayService.getDateTimeBefore(sunrise, now)
                .atZone(zoneId)
                .toInstant()
                .toEpochMilli();
        if (sunsetMillis < sunriseMillis) {
            sunsetMillis = ColorDisplayService.getDateTimeAfter(sunset, now)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli();
        } else {
            sunriseMillis = ColorDisplayService.getDateTimeAfter(sunrise, now)
                    .atZone(zoneId)
                    .toInstant()
                    .toEpochMilli();
        }

        return new TwilightState(sunriseMillis, sunsetMillis);
    }

    private static class MockTwilightManager implements TwilightManager {

        private final Map<TwilightListener, Handler> mListeners = new HashMap<>();
        private TwilightState mTwilightState;

        /**
         * Updates the TwilightState and notifies any registered listeners.
         *
         * @param state the new TwilightState to use
         */
        void setTwilightState(TwilightState state) {
            synchronized (mListeners) {
                mTwilightState = state;

                final CountDownLatch latch = new CountDownLatch(mListeners.size());
                for (Map.Entry<TwilightListener, Handler> entry : mListeners.entrySet()) {
                    entry.getValue().post(new Runnable() {
                        @Override
                        public void run() {
                            entry.getKey().onTwilightStateChanged(state);
                            latch.countDown();
                        }
                    });
                }

                try {
                    latch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        @Override
        public void registerListener(@NonNull TwilightListener listener, @NonNull Handler handler) {
            synchronized (mListeners) {
                mListeners.put(listener, handler);
            }
        }

        @Override
        public void unregisterListener(@NonNull TwilightListener listener) {
            synchronized (mListeners) {
                mListeners.remove(listener);
            }
        }

        @Override
        public TwilightState getLastTwilightState() {
            return mTwilightState;
        }
    }
}
