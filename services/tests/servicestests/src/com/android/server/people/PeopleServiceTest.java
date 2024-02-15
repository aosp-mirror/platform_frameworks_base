/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.people;

import static android.app.people.ConversationStatus.ACTIVITY_GAME;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.people.ConversationChannel;
import android.app.people.ConversationStatus;
import android.app.people.IConversationListener;
import android.app.people.IPeopleManager;
import android.app.people.PeopleManager;
import android.app.prediction.AppPredictionContext;
import android.app.prediction.AppPredictionSessionId;
import android.app.prediction.AppTarget;
import android.app.prediction.IPredictionCallback;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ParceledListSlice;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutServiceInternal;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.provider.DeviceConfig;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;

import androidx.test.InstrumentationRegistry;

import com.android.internal.config.sysui.SystemUiDeviceConfigFlags;
import com.android.server.LocalServices;
import com.android.server.notification.NotificationManagerInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public final class PeopleServiceTest {
    private static final String APP_PREDICTION_SHARE_UI_SURFACE = "share";
    private static final int APP_PREDICTION_TARGET_COUNT = 4;
    private static final String TEST_PACKAGE_NAME = "com.example";
    private static final int USER_ID = 0;
    private static final String CONVERSATION_ID_1 = "12";
    private static final String CONVERSATION_ID_2 = "123";

    private PeopleServiceInternal mServiceInternal;
    private PeopleService.LocalService mLocalService;
    private AppPredictionSessionId mSessionId;
    private AppPredictionContext mPredictionContext;

    @Mock
    ShortcutServiceInternal mShortcutServiceInternal;
    @Mock
    PackageManagerInternal mPackageManagerInternal;
    @Mock
    NotificationManagerInternal mNotificationManagerInternal;

    @Mock
    private Context mMockContext;

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    protected TestableContext getContext() {
        return mContext;
    }

    @Mock
    private IPredictionCallback mCallback;
    private TestableLooper mTestableLooper;
    private final TestLooper mTestLooper = new TestLooper();

    private TestablePeopleService mPeopleService;
    private IPeopleManager mIPeopleManager;
    private PeopleManager mPeopleManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        LocalServices.addService(ShortcutServiceInternal.class, mShortcutServiceInternal);
        LocalServices.addService(PackageManagerInternal.class, mPackageManagerInternal);
        LocalServices.addService(NotificationManagerInternal.class, mNotificationManagerInternal);

        mPeopleService = new TestablePeopleService(mContext);
        mTestableLooper = TestableLooper.get(this);
        mIPeopleManager = ((IPeopleManager) mPeopleService.mService);
        mPeopleManager = new PeopleManager(mContext, mIPeopleManager);
        when(mMockContext.getPackageName()).thenReturn(TEST_PACKAGE_NAME);
        when(mCallback.asBinder()).thenReturn(new Binder());
        PeopleService service = new PeopleService(mContext);
        service.onStart(/* isForTesting= */ true);

        mServiceInternal = LocalServices.getService(PeopleServiceInternal.class);
        mLocalService = (PeopleService.LocalService) mServiceInternal;

        mSessionId = new AppPredictionSessionId("abc", USER_ID);
        mPredictionContext = new AppPredictionContext.Builder(mMockContext)
                .setUiSurface(APP_PREDICTION_SHARE_UI_SURFACE)
                .setPredictedTargetCount(APP_PREDICTION_TARGET_COUNT)
                .setExtras(new Bundle())
                .build();
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_SYSTEMUI,
                SystemUiDeviceConfigFlags.DARK_LAUNCH_REMOTE_PREDICTION_SERVICE_ENABLED,
                Boolean.toString(false),
                true /* makeDefault*/);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(PeopleServiceInternal.class);
        LocalServices.removeServiceForTest(ShortcutServiceInternal.class);
        LocalServices.removeServiceForTest(PackageManagerInternal.class);
        LocalServices.removeServiceForTest(NotificationManagerInternal.class);
    }

    @Test
    public void testRegisterCallbacks() throws RemoteException {
        mServiceInternal.onCreatePredictionSession(mPredictionContext, mSessionId);

        SessionInfo sessionInfo = mLocalService.getSessionInfo(mSessionId);

        mServiceInternal.registerPredictionUpdates(mSessionId, mCallback);

        Consumer<List<AppTarget>> updatePredictionMethod =
                sessionInfo.getPredictor().getUpdatePredictionsMethod();
        updatePredictionMethod.accept(new ArrayList<>());
        updatePredictionMethod.accept(new ArrayList<>());

        verify(mCallback, times(2)).onResult(any(ParceledListSlice.class));

        mServiceInternal.unregisterPredictionUpdates(mSessionId, mCallback);

        updatePredictionMethod.accept(new ArrayList<>());

        // After the un-registration, the callback should no longer be called.
        verify(mCallback, times(2)).onResult(any(ParceledListSlice.class));

        mServiceInternal.onDestroyPredictionSession(mSessionId);
    }

    @Test
    public void testRegisterConversationListener() throws Exception {
        assertEquals(0,
                mPeopleService.getConversationListenerHelper()
                        .mListeners.getRegisteredCallbackCount());

        mIPeopleManager.registerConversationListener(TEST_PACKAGE_NAME, 0, CONVERSATION_ID_1,
                new TestableConversationListener());
        mTestableLooper.processAllMessages();
        assertEquals(1,
                mPeopleService.getConversationListenerHelper()
                        .mListeners.getRegisteredCallbackCount());

        mIPeopleManager.registerConversationListener(TEST_PACKAGE_NAME, 0, CONVERSATION_ID_1,
                new TestableConversationListener());
        mTestableLooper.processAllMessages();
        assertEquals(2,
                mPeopleService.getConversationListenerHelper()
                        .mListeners.getRegisteredCallbackCount());

        mIPeopleManager.registerConversationListener(TEST_PACKAGE_NAME, 0, CONVERSATION_ID_2,
                new TestableConversationListener());
        mTestableLooper.processAllMessages();
        assertEquals(3,
                mPeopleService.getConversationListenerHelper()
                        .mListeners.getRegisteredCallbackCount());
    }

    @Test
    public void testUnregisterConversationListener() throws Exception {
        TestableConversationListener listener1 = new TestableConversationListener();
        mIPeopleManager.registerConversationListener(TEST_PACKAGE_NAME, 0, CONVERSATION_ID_1,
                listener1);
        TestableConversationListener listener2 = new TestableConversationListener();
        mIPeopleManager.registerConversationListener(TEST_PACKAGE_NAME, 0, CONVERSATION_ID_1,
                listener2);
        TestableConversationListener listener3 = new TestableConversationListener();
        mIPeopleManager.registerConversationListener(TEST_PACKAGE_NAME, 0, CONVERSATION_ID_2,
                listener3);
        mTestableLooper.processAllMessages();
        assertEquals(3,
                mPeopleService.getConversationListenerHelper()
                        .mListeners.getRegisteredCallbackCount());

        mIPeopleManager.unregisterConversationListener(
                listener2);
        assertEquals(2,
                mPeopleService.getConversationListenerHelper()
                        .mListeners.getRegisteredCallbackCount());
        mIPeopleManager.unregisterConversationListener(
                listener1);
        assertEquals(1,
                mPeopleService.getConversationListenerHelper()
                        .mListeners.getRegisteredCallbackCount());
        mIPeopleManager.unregisterConversationListener(
                listener3);
        assertEquals(0,
                mPeopleService.getConversationListenerHelper()
                        .mListeners.getRegisteredCallbackCount());
    }

    @Test
    public void testOnlyTriggersConversationListenersForRegisteredConversation() {
        PeopleManager.ConversationListener listenerForConversation1 = mock(
                PeopleManager.ConversationListener.class);
        registerListener(CONVERSATION_ID_1, listenerForConversation1);
        PeopleManager.ConversationListener secondListenerForConversation1 = mock(
                PeopleManager.ConversationListener.class);
        registerListener(CONVERSATION_ID_1, secondListenerForConversation1);
        PeopleManager.ConversationListener listenerForConversation2 = mock(
                PeopleManager.ConversationListener.class);
        registerListener(CONVERSATION_ID_2, listenerForConversation2);
        assertEquals(3,
                mPeopleService.getConversationListenerHelper()
                        .mListeners.getRegisteredCallbackCount());

        // Update conversation with two listeners.
        ConversationStatus status = new ConversationStatus.Builder(CONVERSATION_ID_1,
                ACTIVITY_GAME).build();
        mPeopleService.getConversationListenerHelper().onConversationsUpdate(
                Arrays.asList(getConversation(CONVERSATION_ID_1, status)));
        mTestLooper.dispatchAll();

        // Never update listeners for other conversations.
        verify(listenerForConversation2, never()).onConversationUpdate(any());
        // Should update both listeners for the conversation.
        ArgumentCaptor<ConversationChannel> capturedConversation = ArgumentCaptor.forClass(
                ConversationChannel.class);
        verify(listenerForConversation1, times(1)).onConversationUpdate(
                capturedConversation.capture());
        ConversationChannel conversationChannel = capturedConversation.getValue();
        verify(secondListenerForConversation1, times(1)).onConversationUpdate(
                eq(conversationChannel));
        assertEquals(conversationChannel.getShortcutInfo().getId(), CONVERSATION_ID_1);
        assertThat(conversationChannel.getStatuses()).containsExactly(status);
    }

    private void registerListener(String conversationId,
            PeopleManager.ConversationListener listener) {
        mPeopleManager.registerConversationListener(mContext.getPackageName(), mContext.getUserId(),
                conversationId, listener,
                mTestLooper.getNewExecutor());
    }

    private ConversationChannel getConversation(String shortcutId, ConversationStatus status) {
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mContext,
                shortcutId).setLongLabel(
                "name").build();
        NotificationChannel notificationChannel = new NotificationChannel("123",
                "channel",
                NotificationManager.IMPORTANCE_DEFAULT);
        return new ConversationChannel(shortcutInfo, 0,
                notificationChannel, null,
                123L, false, false, Arrays.asList(status));
    }

    private class TestableConversationListener extends IConversationListener.Stub {
        @Override
        public void onConversationUpdate(ConversationChannel conversation) {
        }
    }

    // Use a Testable subclass so we can simulate calls from the system without failing.
    private static class TestablePeopleService extends PeopleService {
        TestablePeopleService(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            super.onStart(true);
        }

        @Override
        protected void enforceSystemRootOrSystemUI(Context context, String message) {
            return;
        }
    }
}
