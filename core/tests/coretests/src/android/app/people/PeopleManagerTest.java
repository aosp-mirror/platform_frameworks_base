/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.people;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ShortcutInfo;
import android.os.test.TestLooper;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Tests for {@link android.app.people.PeopleManager.ConversationListener} and relevant APIs.
 */
@RunWith(AndroidJUnit4.class)
public class PeopleManagerTest {

    private static final String CONVERSATION_ID_1 = "12";
    private static final String CONVERSATION_ID_2 = "123";

    private Context mContext;

    private final TestLooper mTestLooper = new TestLooper();

    @Mock
    private IPeopleManager mService;
    private PeopleManager mPeopleManager;

    @Before
    public void setUp() throws Exception {
        mContext = InstrumentationRegistry.getContext();
        MockitoAnnotations.initMocks(this);

        mPeopleManager = new PeopleManager(mContext, mService);
    }

    @Test
    public void testCorrectlyMapsToProxyConversationListener() throws Exception {
        PeopleManager.ConversationListener listenerForConversation1 = mock(
                PeopleManager.ConversationListener.class);
        registerListener(CONVERSATION_ID_1, listenerForConversation1);
        PeopleManager.ConversationListener listenerForConversation2 = mock(
                PeopleManager.ConversationListener.class);
        registerListener(CONVERSATION_ID_2, listenerForConversation2);

        Map<PeopleManager.ConversationListener, Pair<Executor, IConversationListener>>
                listenersToProxy =
                mPeopleManager.mConversationListeners;
        Pair<Executor, IConversationListener> listener = listenersToProxy.get(
                listenerForConversation1);
        ConversationChannel conversation = getConversation(CONVERSATION_ID_1);
        listener.second.onConversationUpdate(getConversation(CONVERSATION_ID_1));
        mTestLooper.dispatchAll();

        // Only call the associated listener.
        verify(listenerForConversation2, never()).onConversationUpdate(any());
        // Should update the listeners mapped to the proxy.
        ArgumentCaptor<ConversationChannel> capturedConversation = ArgumentCaptor.forClass(
                ConversationChannel.class);
        verify(listenerForConversation1, times(1)).onConversationUpdate(
                capturedConversation.capture());
        ConversationChannel conversationChannel = capturedConversation.getValue();
        assertEquals(conversationChannel.getShortcutInfo().getId(), CONVERSATION_ID_1);
        assertEquals(conversationChannel.getShortcutInfo().getLabel(),
                conversation.getShortcutInfo().getLabel());
    }

    private ConversationChannel getConversation(String shortcutId) {
        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(mContext,
                shortcutId).setLongLabel(
                "name").build();
        NotificationChannel notificationChannel = new NotificationChannel("123",
                "channel",
                NotificationManager.IMPORTANCE_DEFAULT);
        return new ConversationChannel(shortcutInfo, 0,
                notificationChannel, null,
                123L, false);
    }

    private void registerListener(String conversationId,
            PeopleManager.ConversationListener listener) {
        mPeopleManager.registerConversationListener(mContext.getPackageName(), mContext.getUserId(),
                conversationId, listener,
                mTestLooper.getNewExecutor());
    }
}
