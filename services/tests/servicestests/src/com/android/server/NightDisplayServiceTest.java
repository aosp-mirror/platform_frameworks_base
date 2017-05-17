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

package com.android.server;

import android.annotation.NonNull;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import com.android.internal.app.NightDisplayController;
import com.android.internal.app.NightDisplayController.LocalTime;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.display.DisplayTransformManager;
import com.android.server.display.NightDisplayService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertWithMessage;
import static org.mockito.Mockito.doReturn;

@RunWith(AndroidJUnit4.class)
public class NightDisplayServiceTest {

    private Context mContext;
    private int mUserId;

    private MockTwilightManager mTwilightManager;

    private NightDisplayController mNightDisplayController;
    private NightDisplayService mNightDisplayService;

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

        mNightDisplayController = new NightDisplayController(mContext, mUserId);
        mNightDisplayService = new NightDisplayService(mContext);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(DisplayTransformManager.class);
        LocalServices.removeServiceForTest(TwilightManager.class);

        mNightDisplayService = null;
        mNightDisplayController = null;

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

    /**
     * Configures Night display to use a custom schedule.
     *
     * @param startTimeOffset the offset relative to now to activate Night display (in minutes)
     * @param endTimeOffset the offset relative to now to deactivate Night display (in minutes)
     */
    private void setAutoModeCustom(int startTimeOffset, int endTimeOffset) {
        mNightDisplayController.setAutoMode(NightDisplayController.AUTO_MODE_CUSTOM);
        mNightDisplayController.setCustomStartTime(getLocalTimeRelativeToNow(startTimeOffset));
        mNightDisplayController.setCustomEndTime(getLocalTimeRelativeToNow(endTimeOffset));
    }

    /**
     * Configures Night display to use the twilight schedule.
     *
     * @param sunsetOffset the offset relative to now for sunset (in minutes)
     * @param sunriseOffset the offset relative to now for sunrise (in minutes)
     */
    private void setAutoModeTwilight(int sunsetOffset, int sunriseOffset) {
        mNightDisplayController.setAutoMode(NightDisplayController.AUTO_MODE_TWILIGHT);
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
        mNightDisplayController.setActivated(activated);

        final Calendar c = Calendar.getInstance();
        c.add(Calendar.MINUTE, lastActivatedTimeOffset);
        Secure.putLongForUser(mContext.getContentResolver(),
                Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME, c.getTimeInMillis(), mUserId);
    }

    /**
     * Convenience method to start {@link #mNightDisplayService}.
     */
    private void startService() {
        Secure.putIntForUser(mContext.getContentResolver(), Secure.USER_SETUP_COMPLETE, 1, mUserId);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            @Override
            public void run() {
                mNightDisplayService.onStart();
                mNightDisplayService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
                mNightDisplayService.onStartUser(mUserId);
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
                .that(mNightDisplayController.isActivated())
                .isEqualTo(activated);
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
        return new LocalTime(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
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

        final Calendar now = Calendar.getInstance();
        long sunsetMillis = sunset.getDateTimeBefore(now).getTimeInMillis();
        long sunriseMillis = sunrise.getDateTimeBefore(now).getTimeInMillis();
        if (sunsetMillis < sunriseMillis) {
            sunsetMillis = sunset.getDateTimeAfter(now).getTimeInMillis();
        } else {
            sunriseMillis = sunrise.getDateTimeAfter(now).getTimeInMillis();
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
