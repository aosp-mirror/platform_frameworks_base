/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.audio;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.NonNull;
import android.content.Context;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.IDeviceVolumeBehaviorDispatcher;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for AudioService's tracking and reporting of device volume behaviors.
 */
public class DeviceVolumeBehaviorTest {
    private static final String TAG = "DeviceVolumeBehaviorTest";

    private static final String PACKAGE_NAME = "";
    private static final AudioDeviceAttributes DEVICE_SPEAKER_OUT = new AudioDeviceAttributes(
            AudioDeviceAttributes.ROLE_OUTPUT, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "");

    private Context mContext;
    private AudioSystemAdapter mAudioSystem;
    private SystemServerAdapter mSystemServer;
    private SettingsAdapter mSettingsAdapter;
    private TestLooper mTestLooper;

    private AudioService mAudioService;

    /**
     * Volume behaviors that can be set using AudioService#setDeviceVolumeBehavior
     */
    public static final int[] BASIC_VOLUME_BEHAVIORS = {
            AudioManager.DEVICE_VOLUME_BEHAVIOR_VARIABLE,
            AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL,
            AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED
    };

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContext = InstrumentationRegistry.getTargetContext();
        mAudioSystem = new NoOpAudioSystemAdapter();
        mSystemServer = new NoOpSystemServerAdapter();
        mSettingsAdapter = new NoOpSettingsAdapter();
        mAudioService = new AudioService(mContext, mAudioSystem, mSystemServer,
                mSettingsAdapter, mTestLooper.getLooper());
        mTestLooper.dispatchAll();
    }

    @Test
    public void setDeviceVolumeBehavior_changesDeviceVolumeBehavior() {
        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, PACKAGE_NAME);
        mTestLooper.dispatchAll();

        for (int behavior : BASIC_VOLUME_BEHAVIORS) {
            mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT, behavior, PACKAGE_NAME);
            mTestLooper.dispatchAll();

            int actualBehavior = mAudioService.getDeviceVolumeBehavior(DEVICE_SPEAKER_OUT);

            assertWithMessage("Expected volume behavior to be " + behavior
                    + " but was instead " + actualBehavior)
                    .that(actualBehavior).isEqualTo(behavior);
        }
    }

    @Test
    public void setToNewBehavior_triggersDeviceVolumeBehaviorDispatcher() {
        TestDeviceVolumeBehaviorDispatcherStub dispatcher =
                new TestDeviceVolumeBehaviorDispatcherStub();
        mAudioService.registerDeviceVolumeBehaviorDispatcher(true, dispatcher);

        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, PACKAGE_NAME);
        mTestLooper.dispatchAll();

        for (int behavior : BASIC_VOLUME_BEHAVIORS) {
            dispatcher.reset();
            mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT, behavior, PACKAGE_NAME);
            mTestLooper.dispatchAll();

            assertThat(dispatcher.mTimesCalled).isEqualTo(1);
            assertThat(dispatcher.mDevice).isEqualTo(DEVICE_SPEAKER_OUT);
            assertWithMessage("Expected dispatched volume behavior to be " + behavior
                    + " but was instead " + dispatcher.mVolumeBehavior)
                    .that(dispatcher.mVolumeBehavior).isEqualTo(behavior);
        }
    }

    @Test
    public void setToSameBehavior_doesNotTriggerDeviceVolumeBehaviorDispatcher() {
        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, PACKAGE_NAME);
        mTestLooper.dispatchAll();

        TestDeviceVolumeBehaviorDispatcherStub dispatcher =
                new TestDeviceVolumeBehaviorDispatcherStub();
        mAudioService.registerDeviceVolumeBehaviorDispatcher(true, dispatcher);

        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, PACKAGE_NAME);
        mTestLooper.dispatchAll();
        assertThat(dispatcher.mTimesCalled).isEqualTo(0);
    }

    @Test
    public void unregisterDeviceVolumeBehaviorDispatcher_noLongerTriggered() {
        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FIXED, PACKAGE_NAME);
        mTestLooper.dispatchAll();

        TestDeviceVolumeBehaviorDispatcherStub dispatcher =
                new TestDeviceVolumeBehaviorDispatcherStub();
        mAudioService.registerDeviceVolumeBehaviorDispatcher(true, dispatcher);
        mAudioService.registerDeviceVolumeBehaviorDispatcher(false, dispatcher);

        mAudioService.setDeviceVolumeBehavior(DEVICE_SPEAKER_OUT,
                AudioManager.DEVICE_VOLUME_BEHAVIOR_FULL, PACKAGE_NAME);
        mTestLooper.dispatchAll();
        assertThat(dispatcher.mTimesCalled).isEqualTo(0);
    }

    private static class TestDeviceVolumeBehaviorDispatcherStub
            extends IDeviceVolumeBehaviorDispatcher.Stub {

        private AudioDeviceAttributes mDevice;
        private int mVolumeBehavior;
        private int mTimesCalled;

        @Override
        public void dispatchDeviceVolumeBehaviorChanged(@NonNull AudioDeviceAttributes device,
                @AudioManager.DeviceVolumeBehavior int volumeBehavior) {
            mDevice = device;
            mVolumeBehavior = volumeBehavior;
            mTimesCalled++;
        }

        public void reset() {
            mTimesCalled = 0;
        }
    }
}
