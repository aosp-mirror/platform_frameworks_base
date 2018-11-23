/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view.textclassifier;

import static com.google.common.truth.Truth.assertThat;

import android.app.Person;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.google.android.textclassifier.ActionsSuggestionsModel;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ActionsSuggestionsHelperTest {
    @Test
    public void testToNativeMessages_emptyInput() {
        ActionsSuggestionsModel.ConversationMessage[] conversationMessages =
                ActionsSuggestionsHelper.toNativeMessages(Collections.emptyList());

        assertThat(conversationMessages).isEmpty();
    }

    @Test
    public void testToNativeMessages_noTextMessages() {
        ConversationActions.Message messageWithoutText =
                new ConversationActions.Message.Builder().build();

        ActionsSuggestionsModel.ConversationMessage[] conversationMessages =
                ActionsSuggestionsHelper.toNativeMessages(
                        Collections.singletonList(messageWithoutText));

        assertThat(conversationMessages).isEmpty();
    }

    @Test
    public void testToNativeMessages_missingPersonInFirstMessage() {
        ConversationActions.Message firstMessage =
                new ConversationActions.Message.Builder()
                        .setText("first")
                        .build();
        ConversationActions.Message secondMessage =
                new ConversationActions.Message.Builder()
                        .setText("second")
                        .setAuthor(new Person.Builder().build())
                        .build();
        ConversationActions.Message thirdMessage =
                new ConversationActions.Message.Builder()
                        .setText("third")
                        .setAuthor(ConversationActions.Message.PERSON_USER_LOCAL)
                        .build();

        ActionsSuggestionsModel.ConversationMessage[] conversationMessages =
                ActionsSuggestionsHelper.toNativeMessages(
                        Arrays.asList(firstMessage, secondMessage, thirdMessage));

        assertThat(conversationMessages).hasLength(2);
        assertNativeMessage(conversationMessages[0], secondMessage.getText(), 1);
        assertNativeMessage(conversationMessages[1], thirdMessage.getText(), 0);
    }

    @Test
    public void testToNativeMessages_missingPersonInMiddleOfConversation() {
        ConversationActions.Message firstMessage =
                new ConversationActions.Message.Builder()
                        .setText("first")
                        .setAuthor(new Person.Builder().setName("first").build())
                        .build();
        ConversationActions.Message secondMessage =
                new ConversationActions.Message.Builder()
                        .setText("second")
                        .build();
        ConversationActions.Message thirdMessage =
                new ConversationActions.Message.Builder()
                        .setText("third")
                        .setAuthor(new Person.Builder().setName("third").build())
                        .build();
        ConversationActions.Message fourthMessage =
                new ConversationActions.Message.Builder()
                        .setText("fourth")
                        .setAuthor(new Person.Builder().setName("fourth").build())
                        .build();

        ActionsSuggestionsModel.ConversationMessage[] conversationMessages =
                ActionsSuggestionsHelper.toNativeMessages(
                        Arrays.asList(firstMessage, secondMessage, thirdMessage, fourthMessage));

        assertThat(conversationMessages).hasLength(2);
        assertNativeMessage(conversationMessages[0], thirdMessage.getText(), 2);
        assertNativeMessage(conversationMessages[1], fourthMessage.getText(), 1);
    }

    @Test
    public void testToNativeMessages_userIdEncoding() {
        Person userA = new Person.Builder().setName("userA").build();
        Person userB = new Person.Builder().setName("userB").build();

        ConversationActions.Message firstMessage =
                new ConversationActions.Message.Builder()
                        .setText("first")
                        .setAuthor(userB)
                        .build();
        ConversationActions.Message secondMessage =
                new ConversationActions.Message.Builder()
                        .setText("second")
                        .setAuthor(userA)
                        .build();
        ConversationActions.Message thirdMessage =
                new ConversationActions.Message.Builder()
                        .setText("third")
                        .setAuthor(ConversationActions.Message.PERSON_USER_LOCAL)
                        .build();
        ConversationActions.Message fourthMessage =
                new ConversationActions.Message.Builder()
                        .setText("fourth")
                        .setAuthor(userA)
                        .build();

        ActionsSuggestionsModel.ConversationMessage[] conversationMessages =
                ActionsSuggestionsHelper.toNativeMessages(
                        Arrays.asList(firstMessage, secondMessage, thirdMessage, fourthMessage));

        assertThat(conversationMessages).hasLength(4);
        assertNativeMessage(conversationMessages[0], firstMessage.getText(), 2);
        assertNativeMessage(conversationMessages[1], secondMessage.getText(), 1);
        assertNativeMessage(conversationMessages[2], thirdMessage.getText(), 0);
        assertNativeMessage(conversationMessages[3], fourthMessage.getText(), 1);
    }

    private static void assertNativeMessage(
            ActionsSuggestionsModel.ConversationMessage nativeMessage,
            CharSequence text,
            int userId) {
        assertThat(nativeMessage.getText()).isEqualTo(text.toString());
        assertThat(nativeMessage.getUserId()).isEqualTo(userId);
    }
}
