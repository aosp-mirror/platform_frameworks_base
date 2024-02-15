/*
 * Copyright 2023 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.audiofx.AudioEffect;
import android.os.Message;
import android.util.Log;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class MusicFxHelperTest {
    private static final String TAG = "MusicFxHelperTest";

    @Mock private AudioService mMockAudioService;
    @Mock private Context mMockContext;
    @Mock private PackageManager mMockPackageManager;

    private ResolveInfo mResolveInfo1 = new ResolveInfo();
    private ResolveInfo mResolveInfo2 = new ResolveInfo();
    private final String mTestPkg1 = "testPkg1", mTestPkg2 = "testPkg2", mTestPkg3 = "testPkg3";
    private final String mMusicFxPkgName = "com.android.musicfx";
    private final int mTestUid1 = 1, mTestUid2 = 2, mTestUid3 = 3, mMusicFxUid = 78;
    private final int mTestSession1 = 11, mTestSession2 = 22, mTestSession3 = 33;

    private List<ResolveInfo> mEmptyList = new ArrayList<>();
    private List<ResolveInfo> mSingleList = new ArrayList<>();
    private List<ResolveInfo> mDoubleList = new ArrayList<>();

    // the class being unit-tested here
    @InjectMocks private MusicFxHelper mMusicFxHelper;

    @Before
    @SuppressWarnings("DirectInvocationOnMock")
    public void setUp() throws Exception {
        mMockAudioService = mock(AudioService.class);
        mMusicFxHelper = mMockAudioService.getMusicFxHelper();
        MockitoAnnotations.initMocks(this);

        mResolveInfo1.activityInfo = new ActivityInfo();
        mResolveInfo1.activityInfo.packageName = mTestPkg1;
        mResolveInfo2.activityInfo = new ActivityInfo();
        mResolveInfo2.activityInfo.packageName = mTestPkg2;

        mSingleList.add(mResolveInfo1);
        mDoubleList.add(mResolveInfo1);
        mDoubleList.add(mResolveInfo2);

        Assert.assertNotNull(mMusicFxHelper);
    }

    private Intent newIntent(String action, String packageName, int sessionId) {
        Intent intent = new Intent(action);
        if (packageName != null) {
            intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName);
        }
        intent.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId);
        return intent;
    }

    /**
     * Helper function to send ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION intent with verification.
     *
     * @throws NameNotFoundException if no such package is available to the caller.
     */
    private void openSessionWithResList(
            List<ResolveInfo> list, int bind, int broadcast, String packageName, int audioSession,
            int uid) {
        doReturn(mMockPackageManager).when(mMockContext).getPackageManager();
        doReturn(list).when(mMockPackageManager).queryBroadcastReceivers(anyObject(), anyInt());
        if (list != null && list.size() != 0) {
            try {
                doReturn(uid).when(mMockPackageManager)
                        .getPackageUidAsUser(eq(packageName), anyObject(), anyInt());
                doReturn(mMusicFxUid).when(mMockPackageManager)
                        .getPackageUidAsUser(eq(mMusicFxPkgName), anyObject(), anyInt());
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "NameNotFoundException: " + e);
            }
        }

        Intent intent = newIntent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION,
                packageName, audioSession);
        mMusicFxHelper.handleAudioEffectBroadcast(mMockContext, intent);
        verify(mMockContext, times(bind))
                .bindServiceAsUser(anyObject(), anyObject(), anyInt(), anyObject());
        verify(mMockContext, times(broadcast)).sendBroadcastAsUser(anyObject(), anyObject());
    }

    /**
     * Helper function to send ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION intent with verification.
     *
     * @throws NameNotFoundException if no such package is available to the caller.
     */
    private void closeSessionWithResList(
                List<ResolveInfo> list, int unBind, int broadcast, String packageName,
                int audioSession, int uid) {
        doReturn(mMockPackageManager).when(mMockContext).getPackageManager();
        doReturn(list).when(mMockPackageManager).queryBroadcastReceivers(anyObject(), anyInt());
        if (list != null && list.size() != 0) {
            try {
                doReturn(uid).when(mMockPackageManager)
                        .getPackageUidAsUser(eq(packageName), anyObject(), anyInt());
                doReturn(mMusicFxUid).when(mMockPackageManager)
                        .getPackageUidAsUser(eq(mMusicFxPkgName), anyObject(), anyInt());
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "NameNotFoundException: " + e);
            }
        }

        Intent intent = newIntent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION,
                                packageName, audioSession);
        mMusicFxHelper.handleAudioEffectBroadcast(mMockContext, intent);
        verify(mMockContext, times(unBind)).unbindService(anyObject());
        verify(mMockContext, times(broadcast)).sendBroadcastAsUser(anyObject(), anyObject());
    }

    /**
     * Helper function to send MSG_EFFECT_CLIENT_GONE message with verification.
     */
    private void sendMessage(int msgId, int uid, int unBinds, int broadcasts) {
        mMusicFxHelper.handleMessage(Message.obtain(null, msgId, uid /* arg1 */, 0 /* arg2 */));
        verify(mMockContext, times(broadcasts)).sendBroadcastAsUser(anyObject(), anyObject());
        verify(mMockContext, times(unBinds)).unbindService(anyObject());
    }

    /**
     * Send invalid message to MusicFxHelper.
     */
    @Test
    public void testInvalidMessage() {
        Log.i(TAG, "running testInvalidMessage");

        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE - 1, 0, 0, 0);
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE + 1, 0, 0, 0);
    }

    /**
     * Send client gone message to MusicFxHelper when no client exist.
     */
    @Test
    public void testGoneMessageWhenNoClient() {
        Log.i(TAG, "running testGoneMessageWhenNoClient");

        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, 0, 0, 0);
    }

    /**
     * Send ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION intent to MusicFxHelper when no session exist.
     */
    @Test
    public void testCloseBroadcastIntent() {
        Log.i(TAG, "running testCloseBroadcastIntent");

        closeSessionWithResList(null, 0, 0, null, mTestSession1, mTestUid1);
    }

    /**
     * OPEN/CLOSE AUDIO_EFFECT_CONTROL_SESSION intent when target application package was set.
     * When the target application package was set for an intent, it means this intent is limited
     * to a specific target application, as a result MusicFxHelper will not handle this intent.
     */
    @Test
    public void testBroadcastIntentWithPackage() {
        Log.i(TAG, "running testBroadcastIntentWithPackage");

        Intent intent = newIntent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION, null, 1);
        intent.setPackage(mTestPkg1);
        mMusicFxHelper.handleAudioEffectBroadcast(mMockContext, intent);
        verify(mMockContext, times(0))
                .bindServiceAsUser(anyObject(), anyObject(), anyInt(), anyObject());
        verify(mMockContext, times(0)).sendBroadcastAsUser(anyObject(), anyObject());

        intent = newIntent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION, null, 1);
        intent.setPackage(mTestPkg2);
        mMusicFxHelper.handleAudioEffectBroadcast(mMockContext, intent);
        verify(mMockContext, times(0))
                .bindServiceAsUser(anyObject(), anyObject(), anyInt(), anyObject());
        verify(mMockContext, times(0)).sendBroadcastAsUser(anyObject(), anyObject());
    }

    /**
     * OPEN/CLOSE AUDIO_EFFECT_CONTROL_SESSION with no broadcast receiver.
     */
    @Test
    public void testBroadcastIntentWithNoPackageAndNoBroadcastReceiver() {
        Log.i(TAG, "running testBroadcastIntentWithNoPackageAndNoBroadcastReceiver");

        openSessionWithResList(mEmptyList, 0, 0, null, mTestSession1, mTestUid1);
        closeSessionWithResList(mEmptyList, 0, 0, null, mTestSession1, mTestUid1);
    }

    /**
     * OPEN/CLOSE AUDIO_EFFECT_CONTROL_SESSION with one broadcast receiver.
     */
    @Test
    public void testBroadcastIntentWithNoPackageAndOneBroadcastReceiver() {
        Log.i(TAG, "running testBroadcastIntentWithNoPackageAndOneBroadcastReceiver");

        int broadcasts = 1, bind = 1, unbind = 1;
        openSessionWithResList(mSingleList, bind, broadcasts, null, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mSingleList, unbind, broadcasts, null, mTestSession1, mTestUid1);

        // repeat with different session ID
        broadcasts = broadcasts + 1;
        bind = bind + 1;
        unbind = unbind + 1;
        openSessionWithResList(mSingleList, bind, broadcasts, null, mTestSession2, mTestUid1);
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mSingleList, unbind, broadcasts, null, mTestSession2, mTestUid1);

        // repeat with different UID
        broadcasts = broadcasts + 1;
        bind = bind + 1;
        unbind = unbind + 1;
        openSessionWithResList(mSingleList, bind, broadcasts, null, mTestSession1, mTestUid2);
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mSingleList, unbind, broadcasts, null, mTestSession1, mTestUid2);
    }

    /**
     * OPEN/CLOSE AUDIO_EFFECT_CONTROL_SESSION with two broadcast receivers.
     */
    @Test
    public void testBroadcastIntentWithNoPackageAndTwoBroadcastReceivers() {
        Log.i(TAG, "running testBroadcastIntentWithNoPackageAndTwoBroadcastReceivers");

        openSessionWithResList(mDoubleList, 1, 1, null, mTestSession1, mTestUid1);
        closeSessionWithResList(mDoubleList, 1, 2, null, mTestSession1, mTestUid1);
    }

    /**
     * Open/close session UID not matching.
     * No broadcast for mismatching sessionID/UID/packageName.
     */
    @Test
    public void testBroadcastBadIntents() {
        Log.i(TAG, "running testBroadcastBadIntents");

        int broadcasts = 1;
        openSessionWithResList(mSingleList, 1, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        // mismatch UID
        closeSessionWithResList(mSingleList, 0, broadcasts, mTestPkg1, mTestSession1, mTestUid2);
        // mismatch AudioSession
        closeSessionWithResList(mSingleList, 0, broadcasts, mTestPkg1, mTestSession2, mTestUid1);
        // mismatch packageName
        closeSessionWithResList(mSingleList, 0, broadcasts, mTestPkg2, mTestSession1, mTestUid1);

        // cleanup with correct UID and session ID
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mSingleList, 1, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
    }

    /**
     * Open/close sessions with one UID, some with correct intents some with illegal intents.
     * No broadcast for mismatching sessionID/UID/packageName.
     */
    @Test
    public void testBroadcastGoodAndBadIntents() {
        Log.i(TAG, "running testBroadcastGoodAndBadIntents");

        int broadcasts = 1, bind = 1, unbind = 0;
        openSessionWithResList(mSingleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        // mismatch packageName, session ID and UID
        closeSessionWithResList(mSingleList, unbind, broadcasts, mTestPkg2, mTestSession2,
                                mTestUid2);
        // mismatch session ID and mismatch UID
        closeSessionWithResList(mSingleList, unbind, broadcasts, mTestPkg1, mTestSession2,
                                mTestUid2);
        // mismatch packageName and mismatch UID
        closeSessionWithResList(mSingleList, unbind, broadcasts, mTestPkg2, mTestSession1,
                                mTestUid2);
        // mismatch packageName and sessionID
        closeSessionWithResList(mSingleList, unbind, broadcasts, mTestPkg2, mTestSession2,
                                mTestUid1);
        // inconsistency package name for same UID
        openSessionWithResList(mSingleList, bind, broadcasts, mTestPkg2, mTestSession2, mTestUid1);
        // open session2 with good intent
        broadcasts = broadcasts + 1;
        openSessionWithResList(mSingleList, bind, broadcasts, mTestPkg1, mTestSession2, mTestUid1);

        // cleanup with correct UID and session ID
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mSingleList, unbind, broadcasts, mTestPkg1, mTestSession1,
                                mTestUid1);
        broadcasts = broadcasts + 1;
        unbind = unbind + 1;
        closeSessionWithResList(mSingleList, unbind, broadcasts, mTestPkg1, mTestSession2,
                                mTestUid1);
    }

    /**
     * Send ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION when there is no listener.
     */
    @Test
    public void testBroadcastOpenSessionWithValidPackageNameAndNoListener() {
        Log.i(TAG, "running testBroadcastOpenSessionWithValidPackageNameAndNoListener");

        // null listener list should not trigger any action
        openSessionWithResList(null, 0, 0, mTestPkg1, mTestSession1, mTestUid1);
        // empty listener list should not trigger any action
        openSessionWithResList(mEmptyList, 0, 0, mTestPkg1, mTestSession1, mTestUid1);
    }

    /**
     * One MusicFx client, open session and close.
     */
    @Test
    public void testOpenCloseAudioSession() {
        Log.i(TAG, "running testOpenCloseAudioSession");

        int broadcasts = 1;
        openSessionWithResList(mDoubleList, 1, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, 1, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
    }

    /**
     * One MusicFx client, open session and close, then gone.
     */
    @Test
    public void testOpenCloseAudioSessionAndGone() {
        Log.i(TAG, "running testOpenCloseAudioSessionAndGone");

        int broadcasts = 1;
        openSessionWithResList(mDoubleList, 1, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, 1, broadcasts, mTestPkg1, mTestSession2, mTestUid1);
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, 0, broadcasts, mTestPkg1, mTestSession1, mTestUid1);

        broadcasts = broadcasts + 1; // 1 open session left
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid1, 1, broadcasts);
    }

    /**
     * One MusicFx client, open session, then UID gone without close.
     */
    @Test
    public void testOpenOneSessionAndGo() {
        Log.i(TAG, "running testOpenOneSessionAndGo");

        int broadcasts = 1;
        openSessionWithResList(mDoubleList, 1, broadcasts, mTestPkg1, mTestSession1, mTestUid1);

        broadcasts = broadcasts + 1;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid1, 1, broadcasts);
    }

    /**
     * Two MusicFx clients open and close sessions.
     */
    @Test
    public void testOpenTwoSessionsAndClose() {
        Log.i(TAG, "running testOpenTwoSessionsAndClose");

        int broadcasts = 1, bind = 1, unbind = 0;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession2, mTestUid2);

        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg2, mTestSession2,
                mTestUid2);
        broadcasts = broadcasts + 1;
        unbind = unbind + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession1,
                mTestUid1);

        broadcasts = broadcasts + 1;
        bind = bind + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession2, mTestUid2);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);

        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession1,
                mTestUid1);
        broadcasts = broadcasts + 1;
        unbind = unbind + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg2, mTestSession2,
                mTestUid2);
    }

    /**
     * Two MusicFx clients open sessions, then both UID gone without close.
     */
    @Test
    public void testOpenTwoSessionsAndGo() {
        Log.i(TAG, "running testOpenTwoSessionsAndGo");

        int broadcasts = 1, bind = 1, unbind = 0;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession2, mTestUid2);

        broadcasts = broadcasts + 1;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid1, unbind, broadcasts);

        broadcasts = broadcasts + 1;
        unbind = unbind + 1;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid2, unbind, broadcasts);
    }

    /**
     * Two MusicFx clients open sessions, one close but not gone, the other one gone without close.
     */
    @Test
    public void testTwoSessionsOpenOneCloseOneGo() {
        Log.i(TAG, "running testTwoSessionsOpneAndOneCloseOneGo");

        int broadcasts = 1, bind = 1, unbind = 0;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession2, mTestUid2);

        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession1,
                                mTestUid1);

        broadcasts = broadcasts + 1;
        unbind = unbind + 1;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid2, unbind, broadcasts);
    }

    /**
     * One MusicFx client, open multiple audio sessions, and close all sessions.
     */
    @Test
    public void testTwoSessionsInSameUidOpenClose() {
        Log.i(TAG, "running testTwoSessionsOpneAndOneCloseOneGo");

        int broadcasts = 1, bind = 1, unbind = 0;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession2, mTestUid1);

        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession1,
                                mTestUid1);
        broadcasts = broadcasts + 1;
        unbind = unbind + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession2,
                                mTestUid1);
    }

    /**
     * Three MusicFx clients, each with multiple audio sessions, and close all sessions.
     */
    @Test
    public void testThreeSessionsInThreeUidOpenClose() {
        Log.i(TAG, "running testThreeSessionsInThreeUidOpenClose");

        int broadcasts = 1, bind = 1, unbind = 0;
        //client1
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession2, mTestUid1);
        // client2
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession3, mTestUid2);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession2, mTestUid2);
        // client3
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg3, mTestSession1, mTestUid3);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg3, mTestSession3, mTestUid3);

        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession1,
                                mTestUid1);
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg3, mTestSession3,
                                mTestUid3);
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg2, mTestSession2,
                                mTestUid2);
        // all sessions of client1 closed
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession2,
                                mTestUid1);
        // all sessions of client3 closed
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg3, mTestSession1,
                                mTestUid3);
        // all sessions of client2 closed
        broadcasts = broadcasts + 1;
        // now expect unbind to happen
        unbind = unbind + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg2, mTestSession3,
                                mTestUid2);
    }

    /**
     * Two MusicFx clients, with multiple audio sessions, one close all sessions, and other gone.
     */
    @Test
    public void testTwoUidOneCloseOneGo() {
        Log.i(TAG, "running testTwoUidOneCloseOneGo");

        int broadcasts = 1, bind = 1, unbind = 0;
        //client1
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession2, mTestUid1);
        // client2
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession1, mTestUid2);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession2, mTestUid2);

        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession1,
                                mTestUid1);
        // client2 gone
        broadcasts = broadcasts + 2;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid2, unbind, broadcasts);
        // client 1 close all sessions
        broadcasts = broadcasts + 1;
        unbind = unbind + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession2,
                                mTestUid1);
    }

    /**
     * Three MusicFx clients, with multiple audio sessions, all UID gone.
     */
    @Test
    public void testThreeUidAllGo() {
        Log.i(TAG, "running testThreeUidAllGo");

        int broadcasts = 1, bind = 1, unbind = 0;
        //client1
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession2, mTestUid1);
        // client2
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession2, mTestUid2);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession3, mTestUid2);
        // client3
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg3, mTestSession3, mTestUid3);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg3, mTestSession1, mTestUid3);

        // client2 gone
        broadcasts = broadcasts + 2;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid2, unbind, broadcasts);
        // client3 gone
        broadcasts = broadcasts + 2;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid3, unbind, broadcasts);
        // client 1 gone
        broadcasts = broadcasts + 2;
        // now expect unbindService to happen
        unbind = unbind + 1;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid1, unbind, broadcasts);
    }

    /**
     * Three MusicFx clients, multiple audio sessions, open and UID gone in difference sequence.
     */
    @Test
    public void testThreeUidDiffSequence() {
        Log.i(TAG, "running testThreeUidDiffSequence");

        int broadcasts = 1, bind = 1, unbind = 0;
        //client1
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession1, mTestUid1);
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg1, mTestSession2, mTestUid1);
        // client2
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession2, mTestUid2);
        // client1 close one session
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession1,
                                mTestUid1);
        // client2 open another session
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg2, mTestSession3, mTestUid2);
        // client3 open one session
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg3, mTestSession3, mTestUid3);
        // client2 gone
        broadcasts = broadcasts + 2;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid2, unbind, broadcasts);
        // client3 open another session
        broadcasts = broadcasts + 1;
        openSessionWithResList(mDoubleList, bind, broadcasts, mTestPkg3, mTestSession1, mTestUid3);
        // client1 close another session, and gone
        broadcasts = broadcasts + 1;
        closeSessionWithResList(mDoubleList, unbind, broadcasts, mTestPkg1, mTestSession2,
                                mTestUid1);
        // last UID client3 gone, unbind
        broadcasts = broadcasts + 2;
        unbind = unbind + 1;
        sendMessage(MusicFxHelper.MSG_EFFECT_CLIENT_GONE, mTestUid3, unbind, broadcasts);
    }
}
