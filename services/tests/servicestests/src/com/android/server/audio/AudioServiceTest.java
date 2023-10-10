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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.media.AudioSystem;
import android.os.Looper;
import android.os.PermissionEnforcer;
import android.os.UserHandle;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.Mock;
import org.mockito.Spy;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class AudioServiceTest {
    private static final String TAG = "AudioServiceTest";

    private static final int MAX_MESSAGE_HANDLING_DELAY_MS = 100;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    private Context mContext;
    private AudioSystemAdapter mAudioSystem;
    private SettingsAdapter mSettingsAdapter;

    @Spy private NoOpSystemServerAdapter mSpySystemServer;
    @Mock private AppOpsManager mMockAppOpsManager;
    @Mock private AudioPolicyFacade mMockAudioPolicy;
    @Mock private PermissionEnforcer mMockPermissionEnforcer;

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
        mSettingsAdapter = new NoOpSettingsAdapter();
        when(mMockAppOpsManager.noteOp(anyInt(), anyInt(), anyString(), anyString(), anyString()))
                .thenReturn(AppOpsManager.MODE_ALLOWED);
        mAudioService = new AudioService(mContext, mAudioSystem, mSpySystemServer,
                mSettingsAdapter, mMockAudioPolicy, null, mMockAppOpsManager,
                mMockPermissionEnforcer);
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

    @Test
    public void testRingNotifAlias() throws Exception {
        Log.i(TAG, "running testRingNotifAlias");
        Assert.assertNotNull(mAudioService);
        // TODO add initialization message that can be caught here instead of sleeping
        Thread.sleep(MAX_MESSAGE_HANDLING_DELAY_MS); // wait for full AudioService initialization

        // test with aliasing RING and NOTIFICATION
        mAudioService.setNotifAliasRingForTest(true);
        final int ringMaxVol = mAudioService.getStreamMaxVolume(AudioSystem.STREAM_RING);
        final int ringMinVol = mAudioService.getStreamMinVolume(AudioSystem.STREAM_RING);
        final int ringVol = ringMinVol + 1;
        // set a value for NOTIFICATION so it's not at the target test value (ringMaxVol)
        mAudioService.setStreamVolume(AudioSystem.STREAM_NOTIFICATION,
                ringVol, 0, "bla");
        mAudioService.setStreamVolume(AudioSystem.STREAM_RING, ringMaxVol, 0, "bla");
        Thread.sleep(MAX_MESSAGE_HANDLING_DELAY_MS);
        Assert.assertEquals(ringMaxVol,
                mAudioService.getStreamVolume(AudioSystem.STREAM_NOTIFICATION));

        // test with no aliasing between RING and NOTIFICATION
        mAudioService.setNotifAliasRingForTest(false);
        mAudioService.setStreamVolume(AudioSystem.STREAM_RING, ringVol, 0, "bla");
        mAudioService.setStreamVolume(AudioSystem.STREAM_NOTIFICATION, ringMaxVol, 0, "bla");
        Assert.assertEquals(ringVol, mAudioService.getStreamVolume(AudioSystem.STREAM_RING));
        Assert.assertEquals(ringMaxVol, mAudioService.getStreamVolume(
                AudioSystem.STREAM_NOTIFICATION));
    }

    @Test
    public void testAudioPolicyException() throws Exception {
        Log.i(TAG, "running testAudioPolicyException");
        Assert.assertNotNull(mAudioService);
        // Ensure that AudioPolicy inavailability doesn't bring down SystemServer
        when(mMockAudioPolicy.isHotwordStreamSupported(anyBoolean())).thenThrow(
                    new IllegalStateException(), new IllegalStateException());
        Assert.assertEquals(false, mAudioService.isHotwordStreamSupported(false));
        Assert.assertEquals(false, mAudioService.isHotwordStreamSupported(true));
    }
}
