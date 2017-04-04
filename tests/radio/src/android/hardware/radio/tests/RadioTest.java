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
package android.hardware.radio.tests;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.radio.RadioManager;
import android.hardware.radio.RadioTuner;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import java.util.ArrayList;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

/**
 * A test for broadcast radio API.
 */
@RunWith(AndroidJUnit4.class)
public class RadioTest {

    public final Context mContext = InstrumentationRegistry.getContext();

    private RadioManager mRadioManager;
    private RadioTuner mRadioTuner;
    private final List<RadioManager.ModuleProperties> mModules = new ArrayList<>();

    @Before
    public void setup() {
        // check if radio is supported and skip the test if it's not
        PackageManager packageManager = mContext.getPackageManager();
        boolean isRadioSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_RADIO);
        assumeTrue(isRadioSupported);

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
    }

    private void openTuner(RadioTuner.Callback callback) {
        assertNull(mRadioTuner);

        // find FM band and build its config
        RadioManager.ModuleProperties module = mModules.get(0);
        RadioManager.FmBandDescriptor fmBandDescriptor = null;
        for (RadioManager.BandDescriptor band : module.getBands()) {
            if (band.getType() == RadioManager.BAND_FM) {
                fmBandDescriptor = (RadioManager.FmBandDescriptor)band;
                break;
            }
        }
        assertNotNull(fmBandDescriptor);
        RadioManager.BandConfig fmBandConfig =
            new RadioManager.FmBandConfig.Builder(fmBandDescriptor).build();

        mRadioTuner = mRadioManager.openTuner(module.getId(), fmBandConfig, true, callback, null);
        assertNotNull(mRadioTuner);
    }

    @Test
    public void testOpenTuner() {
        openTuner(new RadioTuner.Callback() {});
    }
}
