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
package android.hardware.radio.tests.functional;

import static org.junit.Assert.*;
import static org.junit.Assume.*;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.testng.Assert.assertThrows;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.radio.ProgramSelector;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A test for broadcast radio API.
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class RadioTunerTest {
    private static final String TAG = "BroadcastRadioTests.RadioTuner";

    public final Context mContext = InstrumentationRegistry.getContext();

    private final int kConfigCallbackTimeoutMs = 10000;
    private final int kCancelTimeoutMs = 1000;
    private final int kTuneCallbackTimeoutMs = 30000;
    private final int kFullScanTimeoutMs = 60000;

    private RadioManager mRadioManager;
    private RadioTuner mRadioTuner;
    private RadioManager.ModuleProperties mModule;
    private final List<RadioManager.ModuleProperties> mModules = new ArrayList<>();
    @Mock private RadioTuner.Callback mCallback;

    RadioManager.AmBandDescriptor mAmBandDescriptor;
    RadioManager.FmBandDescriptor mFmBandDescriptor;

    RadioManager.BandConfig mAmBandConfig;
    RadioManager.BandConfig mFmBandConfig;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        // check if radio is supported and skip the test if it's not
        PackageManager packageManager = mContext.getPackageManager();
        boolean isRadioSupported = packageManager.hasSystemFeature(
                PackageManager.FEATURE_BROADCAST_RADIO);
        assumeTrue(isRadioSupported);

        // Check radio access permission
        int res = mContext.checkCallingOrSelfPermission(Manifest.permission.ACCESS_BROADCAST_RADIO);
        assertEquals("ACCESS_BROADCAST_RADIO permission not granted",
                PackageManager.PERMISSION_GRANTED, res);

        mRadioManager = (RadioManager)mContext.getSystemService(Context.RADIO_SERVICE);
        assertNotNull(mRadioManager);

        int status = mRadioManager.listModules(mModules);
        assertEquals(RadioManager.STATUS_OK, status);
        assertFalse(mModules.isEmpty());
    }

    @After
    public void tearDown() {
        mRadioManager = null;
        mModules.clear();
        if (mRadioTuner != null) {
            mRadioTuner.close();
            mRadioTuner = null;
        }
        resetCallback();
    }

    private void openTuner() {
        openTuner(true);
    }

    private void resetCallback() {
        verify(mCallback, atLeast(0)).onMetadataChanged(any());
        verify(mCallback, atLeast(0)).onProgramInfoChanged(any());
        verify(mCallback, atLeast(0)).onProgramListChanged();
        verifyNoMoreInteractions(mCallback);
        Mockito.reset(mCallback);
    }

    private void openTuner(boolean withAudio) {
        assertNull(mRadioTuner);

        // find FM band and build its config
        mModule = mModules.get(0);

        for (RadioManager.BandDescriptor band : mModule.getBands()) {
            Log.d(TAG, "Band: " + band);
            int bandType = band.getType();
            if (bandType == RadioManager.BAND_AM || bandType == RadioManager.BAND_AM_HD) {
                mAmBandDescriptor = (RadioManager.AmBandDescriptor)band;
            }
            if (bandType == RadioManager.BAND_FM || bandType == RadioManager.BAND_FM_HD) {
                mFmBandDescriptor = (RadioManager.FmBandDescriptor)band;
            }
        }
        assertNotNull(mAmBandDescriptor);
        assertNotNull(mFmBandDescriptor);
        mAmBandConfig = new RadioManager.AmBandConfig.Builder(mAmBandDescriptor).build();
        mFmBandConfig = new RadioManager.FmBandConfig.Builder(mFmBandDescriptor).build();

        mRadioTuner = mRadioManager.openTuner(mModule.getId(),
                mFmBandConfig, withAudio, mCallback, null);
        if (!withAudio) {
            // non-audio sessions might not be supported - if so, then skip the test
            assumeNotNull(mRadioTuner);
        }
        assertNotNull(mRadioTuner);
        verify(mCallback, timeout(kConfigCallbackTimeoutMs)).onConfigurationChanged(any());
        resetCallback();

        boolean isAntennaConnected = mRadioTuner.isAntennaConnected();
        assertTrue(isAntennaConnected);
    }

    @Test
    public void testOpenTuner() {
        openTuner();
    }

    @Test
    public void testReopenTuner() throws Throwable {
        openTuner();
        mRadioTuner.close();
        mRadioTuner = null;
        Thread.sleep(100);  // TODO(b/36122635): force reopen
        openTuner();
    }

    @Test
    public void testDoubleClose() {
        openTuner();
        mRadioTuner.close();
        mRadioTuner.close();
    }

    @Test
    public void testUseAfterClose() {
        openTuner();
        mRadioTuner.close();
        int ret = mRadioTuner.cancel();
        assertEquals(RadioManager.STATUS_INVALID_OPERATION, ret);
    }

    @Test
    public void testSetAndGetConfiguration() {
        openTuner();

        // set
        int ret = mRadioTuner.setConfiguration(mAmBandConfig);
        assertEquals(RadioManager.STATUS_OK, ret);
        verify(mCallback, timeout(kConfigCallbackTimeoutMs)).onConfigurationChanged(any());

        // get
        RadioManager.BandConfig[] config = new RadioManager.BandConfig[1];
        ret = mRadioTuner.getConfiguration(config);
        assertEquals(RadioManager.STATUS_OK, ret);

        assertEquals(mAmBandConfig, config[0]);
    }

    @Test
    public void testSetBadConfiguration() throws Throwable {
        openTuner();

        // set null config
        int ret = mRadioTuner.setConfiguration(null);
        assertEquals(RadioManager.STATUS_BAD_VALUE, ret);
        verify(mCallback, never()).onConfigurationChanged(any());

        // setting good config should recover
        ret = mRadioTuner.setConfiguration(mAmBandConfig);
        assertEquals(RadioManager.STATUS_OK, ret);
        verify(mCallback, timeout(kConfigCallbackTimeoutMs)).onConfigurationChanged(any());
    }

    @Test
    public void testMute() {
        openTuner();

        boolean isMuted = mRadioTuner.getMute();
        assertFalse(isMuted);

        int ret = mRadioTuner.setMute(true);
        assertEquals(RadioManager.STATUS_OK, ret);
        isMuted = mRadioTuner.getMute();
        assertTrue(isMuted);

        ret = mRadioTuner.setMute(false);
        assertEquals(RadioManager.STATUS_OK, ret);
        isMuted = mRadioTuner.getMute();
        assertFalse(isMuted);
    }

    @Test
    public void testMuteNoAudio() {
        openTuner(false);

        int ret = mRadioTuner.setMute(false);
        assertEquals(RadioManager.STATUS_ERROR, ret);

        boolean isMuted = mRadioTuner.getMute();
        assertTrue(isMuted);
    }

    @Test
    public void testStep() {
        openTuner();

        int ret = mRadioTuner.step(RadioTuner.DIRECTION_DOWN, true);
        assertEquals(RadioManager.STATUS_OK, ret);
        verify(mCallback, timeout(kTuneCallbackTimeoutMs)).onProgramInfoChanged(any());

        resetCallback();

        ret = mRadioTuner.step(RadioTuner.DIRECTION_UP, false);
        assertEquals(RadioManager.STATUS_OK, ret);
        verify(mCallback, timeout(kTuneCallbackTimeoutMs)).onProgramInfoChanged(any());
    }

    @Test
    public void testStepLoop() {
        openTuner();

        for (int i = 0; i < 10; i++) {
            Log.d(TAG, "step loop iteration " + (i + 1));

            int ret = mRadioTuner.step(RadioTuner.DIRECTION_DOWN, true);
            assertEquals(RadioManager.STATUS_OK, ret);
            verify(mCallback, timeout(kTuneCallbackTimeoutMs)).onProgramInfoChanged(any());

            resetCallback();
        }
    }

    @Test
    public void testTuneAndGetPI() {
        openTuner();

        int channel = mFmBandConfig.getLowerLimit() + mFmBandConfig.getSpacing();

        // test tune
        int ret = mRadioTuner.tune(channel, 0);
        assertEquals(RadioManager.STATUS_OK, ret);
        ArgumentCaptor<RadioManager.ProgramInfo> infoc =
                ArgumentCaptor.forClass(RadioManager.ProgramInfo.class);
        verify(mCallback, timeout(kTuneCallbackTimeoutMs))
                .onProgramInfoChanged(infoc.capture());
        assertEquals(channel, infoc.getValue().getChannel());

        // test getProgramInformation
        RadioManager.ProgramInfo[] info = new RadioManager.ProgramInfo[1];
        ret = mRadioTuner.getProgramInformation(info);
        assertEquals(RadioManager.STATUS_OK, ret);
        assertNotNull(info[0]);
        assertEquals(channel, info[0].getChannel());
        Log.d(TAG, "PI: " + info[0].toString());
    }

    @Test
    public void testDummyCancel() {
        openTuner();

        int ret = mRadioTuner.cancel();
        assertEquals(RadioManager.STATUS_OK, ret);
    }

    @Test
    public void testLateCancel() {
        openTuner();

        int ret = mRadioTuner.step(RadioTuner.DIRECTION_DOWN, false);
        assertEquals(RadioManager.STATUS_OK, ret);
        verify(mCallback, timeout(kTuneCallbackTimeoutMs)).onProgramInfoChanged(any());

        int cancelRet = mRadioTuner.cancel();
        assertEquals(RadioManager.STATUS_OK, cancelRet);
    }

    @Test
    public void testScanAndCancel() {
        openTuner();

        /* There is a possible race condition between scan and cancel commands - the scan may finish
         * before cancel command is issued. Thus we accept both outcomes in this test.
         */
        int scanRet = mRadioTuner.scan(RadioTuner.DIRECTION_DOWN, true);
        int cancelRet = mRadioTuner.cancel();

        assertEquals(RadioManager.STATUS_OK, scanRet);
        assertEquals(RadioManager.STATUS_OK, cancelRet);

        verify(mCallback, after(kCancelTimeoutMs).atMost(1)).onError(RadioTuner.ERROR_CANCELLED);
        verify(mCallback, atMost(1)).onProgramInfoChanged(any());
    }

    @Test
    public void testStartBackgroundScan() {
        openTuner();

        boolean ret = mRadioTuner.startBackgroundScan();
        boolean isSupported = mModule.isBackgroundScanningSupported();
        assertEquals(isSupported, ret);
    }

    @Test
    public void testGetProgramList() {
        openTuner();

        try {
            Map<String, String> filter = new HashMap<>();
            filter.put("com.google.dummy", "dummy");
            List<RadioManager.ProgramInfo> list = mRadioTuner.getProgramList(filter);
            assertNotNull(list);
        } catch (IllegalStateException e) {
            // the list may or may not be ready at this point
            Log.i(TAG, "Background list is not ready");
        }
    }

    @Test
    public void testTuneFromProgramList() {
        openTuner();

        List<RadioManager.ProgramInfo> list;

        try {
            list = mRadioTuner.getProgramList(null);
            assertNotNull(list);
        } catch (IllegalStateException e) {
            Log.i(TAG, "Background list is not ready, trying to fix it");

            boolean success = mRadioTuner.startBackgroundScan();
            assertTrue(success);
            verify(mCallback, timeout(kFullScanTimeoutMs)).onBackgroundScanComplete();

            list = mRadioTuner.getProgramList(null);
            assertNotNull(list);
        }

        if (list.isEmpty()) {
            Log.i(TAG, "Program list is empty, can't test tune");
            return;
        }

        ProgramSelector sel = list.get(0).getSelector();
        mRadioTuner.tune(sel);
        ArgumentCaptor<RadioManager.ProgramInfo> infoc =
                ArgumentCaptor.forClass(RadioManager.ProgramInfo.class);
        verify(mCallback, timeout(kTuneCallbackTimeoutMs)).onProgramInfoChanged(infoc.capture());
        assertEquals(sel, infoc.getValue().getSelector());
    }

    @Test
    public void testForcedAnalog() {
        openTuner();

        boolean isSupported = true;
        boolean isForced;
        try {
            isForced = mRadioTuner.isAnalogForced();
            assertFalse(isForced);
        } catch (IllegalStateException ex) {
            Log.i(TAG, "Forced analog switch is not supported by this tuner");
            isSupported = false;
        }

        if (isSupported) {
            mRadioTuner.setAnalogForced(true);
            isForced = mRadioTuner.isAnalogForced();
            assertTrue(isForced);

            mRadioTuner.setAnalogForced(false);
            isForced = mRadioTuner.isAnalogForced();
            assertFalse(isForced);
        } else {
            assertThrows(IllegalStateException.class, () -> mRadioTuner.setAnalogForced(true));
        }
    }
}
