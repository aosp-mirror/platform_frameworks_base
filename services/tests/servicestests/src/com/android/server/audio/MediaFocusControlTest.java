/*
 * Copyright 2024 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.os.Binder;
import android.os.IBinder;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class MediaFocusControlTest {
    private static final String TAG = "MediaFocusControlTest";

    private Context mContext;
    private MediaFocusControl mMediaFocusControl;
    private final IBinder mICallBack = new Binder();


    private static class NoopPlayerFocusEnforcer implements PlayerFocusEnforcer {
        public boolean duckPlayers(@NonNull FocusRequester winner, @NonNull FocusRequester loser,
                boolean forceDuck) {
            return true;
        }

        public void restoreVShapedPlayers(@NonNull FocusRequester winner) {
        }

        public void mutePlayersForCall(int[] usagesToMute) {
        }

        public void unmutePlayersForCall() {
        }

        public boolean fadeOutPlayers(@NonNull FocusRequester winner,
                @NonNull FocusRequester loser) {
            return true;
        }

        public void forgetUid(int uid) {
        }

        public long getFadeOutDurationMillis(@NonNull AudioAttributes aa) {
            return 100;
        }

        public long getFadeInDelayForOffendersMillis(@NonNull AudioAttributes aa) {
            return 100;
        }

        public boolean shouldEnforceFade() {
            return false;
        }
    }

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getTargetContext();
        mMediaFocusControl = new MediaFocusControl(mContext, new NoopPlayerFocusEnforcer());
    }

    private static final AudioAttributes MEDIA_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build();
    private static final AudioAttributes ALARM_ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM).build();
    private static final int MEDIA_UID = 10300;
    private static final int ALARM_UID = 10301;

    /**
     * Test {@link MediaFocusControl#sendFocusLossAndUpdate(AudioFocusInfo)}
     */
    @Test
    public void testSendFocusLossAndUpdate() throws Exception {
        // simulate a media app requesting focus, followed by an alarm
        mMediaFocusControl.requestAudioFocus(MEDIA_ATTRIBUTES, AudioManager.AUDIOFOCUS_GAIN,
                mICallBack, null /*focusDispatcher*/, "clientMedia", "packMedia",
                AudioManager.AUDIOFOCUS_FLAG_TEST /*flags*/, 35 /*sdk*/, false/*forceDuck*/,
                MEDIA_UID, true /*permissionOverridesCheck*/);
        final AudioFocusInfo alarm = new AudioFocusInfo(ALARM_ATTRIBUTES, ALARM_UID,
                "clientAlarm", "packAlarm",
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, 0/*lossReceived*/,
                AudioManager.AUDIOFOCUS_FLAG_TEST /*flags*/, 35 /*sdk*/);
        mMediaFocusControl.requestAudioFocus(alarm.getAttributes(), alarm.getGainRequest(),
                mICallBack, null /*focusDispatcher*/, alarm.getClientId(), alarm.getPackageName(),
                alarm.getFlags(), alarm.getSdkTarget(), false/*forceDuck*/,
                alarm.getClientUid(), true /*permissionOverridesCheck*/);
        // verify stack is in expected state
        List<AudioFocusInfo> stack = mMediaFocusControl.getFocusStack();
        Assert.assertEquals("focus stack should have 2 entries", 2, stack.size());
        Assert.assertEquals("focus loser should have received LOSS_TRANSIENT_CAN_DUCK",
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK, stack.get(0).getLossReceived());

        // make alarm app lose focus and check stack
        mMediaFocusControl.sendFocusLossAndUpdate(alarm);
        stack = mMediaFocusControl.getFocusStack();
        Assert.assertEquals("focus stack should have 1 entry after sendFocusLossAndUpdate",
                1, stack.size());
        Assert.assertEquals("new top of stack should be media app",
                MEDIA_UID, stack.get(0).getClientUid());
    }
}
