/*
 * Copyright 2020 The Android Open Source Project
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

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Spy;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class AudioServiceTest {
    private static final String TAG = "AudioServiceTest";

    private static final int MAX_MESSAGE_HANDLING_DELAY_MS = 100;

    private Context mContext;
    private AudioSystemAdapter mAudioSystem;
    @Spy private SystemServerAdapter mSpySystemServer;
    private SettingsAdapter mSettingsAdapter;
    // the class being unit-tested here
    private AudioService mAudioService;

    private static boolean sLooperPrepared = false;

    @Before
    public void setUp() throws Exception {
        if (!sLooperPrepared) {
            Looper.prepare();
            sLooperPrepared = true;
        }
        mContext = InstrumentationRegistry.getTargetContext();
        mAudioSystem = new NoOpAudioSystemAdapter();
        mSpySystemServer = spy(new NoOpSystemServerAdapter());
        mSettingsAdapter = new NoOpSettingsAdapter();
        mAudioService = new AudioService(mContext, mAudioSystem, mSpySystemServer,
                mSettingsAdapter);
    }

    /**
     * Test muting the mic reports the expected value, and the corresponding intent was fired
     * @throws Exception
     */
    @Test
    public void testMuteMicrophone() throws Exception {
        Log.i(TAG, "running testMuteMicrophone");
        Assert.assertNotNull(mAudioService);
        final NoOpAudioSystemAdapter testAudioSystem = (NoOpAudioSystemAdapter) mAudioSystem;
        testAudioSystem.configureMuteMicrophoneToFail(false);
        for (boolean muted : new boolean[] { true, false}) {
            testAudioSystem.configureIsMicrophoneMuted(!muted);
            mAudioService.setMicrophoneMute(muted, mContext.getOpPackageName(),
                    UserHandle.getCallingUserId(), null);
            Assert.assertEquals("mic mute reporting wrong value",
                    muted, mAudioService.isMicrophoneMuted());
            // verify the intent for mic mute changed is supposed to be fired
            Thread.sleep(MAX_MESSAGE_HANDLING_DELAY_MS);
            verify(mSpySystemServer, times(1))
                    .sendMicrophoneMuteChangedIntent();
            reset(mSpySystemServer);
        }
    }

    /**
     * Test muting the mic with simulated failure reports the expected value, and the corresponding
     * intent was fired
     * @throws Exception
     */
    @Test
    public void testMuteMicrophoneWhenFail() throws Exception {
        Log.i(TAG, "running testMuteMicrophoneWhenFail");
        Assert.assertNotNull(mAudioService);
        final NoOpAudioSystemAdapter testAudioSystem = (NoOpAudioSystemAdapter) mAudioSystem;
        testAudioSystem.configureMuteMicrophoneToFail(true);
        for (boolean muted : new boolean[] { true, false}) {
            testAudioSystem.configureIsMicrophoneMuted(!muted);
            mAudioService.setMicrophoneMute(muted, mContext.getOpPackageName(),
                    UserHandle.getCallingUserId(), null);
            Assert.assertEquals("mic mute reporting wrong value",
                    !muted, mAudioService.isMicrophoneMuted());
            // verify the intent for mic mute changed is supposed to be fired
            Thread.sleep(MAX_MESSAGE_HANDLING_DELAY_MS);
            verify(mSpySystemServer, times(1))
                    .sendMicrophoneMuteChangedIntent();
            reset(mSpySystemServer);
        }
    }
}
