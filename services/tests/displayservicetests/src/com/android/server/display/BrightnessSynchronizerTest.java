/*
 * Copyright 2022 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManager.DisplayListener;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.os.test.TestLooper;
import android.provider.Settings;
import android.test.mock.MockContentResolver;
import android.view.Display;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.display.BrightnessSynchronizer;
import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.testutils.OffsettableClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class BrightnessSynchronizerTest {
    private static final float EPSILON = 0.00001f;
    private static final Uri BRIGHTNESS_URI =
            Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS);

    private Context mContext;
    private MockContentResolver mContentResolverSpy;
    private OffsettableClock mClock;
    private DisplayListener mDisplayListener;
    private ContentObserver mContentObserver;
    private TestLooper mTestLooper;

    @Mock private DisplayManager mDisplayManagerMock;
    @Captor private ArgumentCaptor<DisplayListener> mDisplayListenerCaptor;
    @Captor private ArgumentCaptor<ContentObserver> mContentObserverCaptor;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContext = spy(new ContextWrapper(ApplicationProvider.getApplicationContext()));
        mContentResolverSpy = spy(new MockContentResolver(mContext));
        mContentResolverSpy.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolverSpy);
        when(mContext.getSystemService(DisplayManager.class)).thenReturn(mDisplayManagerMock);
        mClock = new OffsettableClock.Stopped();
        mTestLooper = new TestLooper(mClock::now);
    }

    @Test
    public void testSetFloat() throws Exception {
        putFloatSetting(0.5f);
        putIntSetting(128);
        start();

        // Set float brightness to 0.4
        putFloatSetting(0.4f);
        advanceTime(10);
        verifyIntWasSetTo(fToI(0.4f));
    }

    @Test
    public void testSetInt() {
        putFloatSetting(0.5f);
        putIntSetting(128);
        start();

        // Set int brightness to 64
        putIntSetting(64);
        advanceTime(10);
        verifyFloatWasSetTo(iToF(64));
    }

    @Test
    public void testSetIntQuickSuccession() {
        putFloatSetting(0.5f);
        putIntSetting(128);
        start();

        putIntSetting(50);
        putIntSetting(40);
        advanceTime(10);

        verifyFloatWasSetTo(iToF(50));

        // now confirm the first value (via callback) so that we can process the second one.
        putFloatSetting(iToF(50));
        advanceTime(10);
        verifyFloatWasSetTo(iToF(40));
    }

    @Test
    public void testSetSameIntValue_nothingUpdated() {
        putFloatSetting(0.5f);
        putIntSetting(128);
        start();

        putIntSetting(128);
        advanceTime(10);
        verify(mDisplayManagerMock, times(0)).setBrightness(
                eq(Display.DEFAULT_DISPLAY), eq(iToF(128)));
    }

    @Test
    public void testUpdateDuringResponseIsNotOverwritten() {
        putFloatSetting(0.5f);
        putIntSetting(128);
        start();

        // First, change the float to 0.4f
        putFloatSetting(0.4f);
        advanceTime(10);

        // Now set the int to something else (not equal to 0.4f)
        putIntSetting(20);
        advanceTime(10);

        // Verify that this update did not get sent to float, because synchronizer
        // is still waiting for confirmation of its first value.
        verify(mDisplayManagerMock, times(0)).setBrightness(
                eq(Display.DEFAULT_DISPLAY), eq(iToF(20)));

        // Send the confirmation of the initial change. This should trigger the new value to
        // finally be processed and we can verify that the new value (20) is sent.
        putIntSetting(fToI(0.4f));
        advanceTime(10);
        verify(mDisplayManagerMock).setBrightness(
                eq(Display.DEFAULT_DISPLAY), eq(iToF(20)));

    }

    @Test
    public void testSetFloat_outOfTimeForResponse() {
        putFloatSetting(0.5f);
        putIntSetting(128);
        start();
        advanceTime(210);

        // First, change the float to 0.4f
        putFloatSetting(0.4f);
        advanceTime(10);

        // Now set the int to something else (not equal to 0.4f)
        putIntSetting(20);

        // Now, go beyond the timeout so that the last 20 event gets executed.
        advanceTime(200);

        // Verify that the new value gets sent because the timeout expired.
        verify(mDisplayManagerMock).setBrightness(
                eq(Display.DEFAULT_DISPLAY), eq(iToF(20)));

        // Send a confirmation of the initial event, BrightnessSynchronizer should treat this as a
        // new event because the timeout had already expired
        putIntSetting(fToI(0.4f));
        // Because the previous setting will be treated as a new event, we actually want to send
        // confirmation of the setBrightness() we just verified so that it can be executed as well.
        putFloatSetting(iToF(20));
        advanceTime(10);

        // Verify we sent what would have been the confirmation as a new event to displaymanager.
        // We do both fToI and iToF because the conversions are not symmetric.
        verify(mDisplayManagerMock).setBrightness(
                eq(Display.DEFAULT_DISPLAY), eq(iToF(fToI(0.4f))));
    }

    private BrightnessSynchronizer start() {
        BrightnessSynchronizer bs = new BrightnessSynchronizer(mContext, mTestLooper.getLooper(),
                mClock::now);
        bs.startSynchronizing();
        verify(mDisplayManagerMock).registerDisplayListener(mDisplayListenerCaptor.capture(),
                isA(Handler.class), eq(DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));
        mDisplayListener = mDisplayListenerCaptor.getValue();

        verify(mContentResolverSpy).registerContentObserver(eq(BRIGHTNESS_URI), eq(false),
                mContentObserverCaptor.capture(), eq(UserHandle.USER_ALL));
        mContentObserver = mContentObserverCaptor.getValue();
        return bs;
    }

    private int getIntSetting() throws Exception {
        return Settings.System.getInt(mContentResolverSpy, Settings.System.SCREEN_BRIGHTNESS);
    }

    private void putIntSetting(int brightness) {
        Settings.System.putInt(mContentResolverSpy, Settings.System.SCREEN_BRIGHTNESS, brightness);
        if (mContentObserver != null) {
            mContentObserver.onChange(false /*=selfChange*/, BRIGHTNESS_URI);
        }
    }

    private void putFloatSetting(float brightness) {
        when(mDisplayManagerMock.getBrightness(eq(Display.DEFAULT_DISPLAY))).thenReturn(brightness);
        if (mDisplayListener != null) {
            mDisplayListener.onDisplayChanged(Display.DEFAULT_DISPLAY);
        }
    }

    private void verifyIntWasSetTo(int brightness) throws Exception {
        assertEquals(brightness, getIntSetting());
    }

    private void verifyFloatWasSetTo(float brightness) {
        verify(mDisplayManagerMock).setBrightness(eq(Display.DEFAULT_DISPLAY), eq(brightness));
    }

    private int fToI(float brightness) {
        return BrightnessSynchronizer.brightnessFloatToInt(brightness);
    }

    private float iToF(int brightness) {
        return BrightnessSynchronizer.brightnessIntToFloat(brightness);
    }

    private void advanceTime(long timeMs) {
        mClock.fastForward(timeMs);
        mTestLooper.dispatchAll();
    }
}
