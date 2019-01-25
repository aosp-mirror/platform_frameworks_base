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

import static android.view.textclassifier.ConversationActions.Message.PERSON_USER_OTHERS;
import static android.view.textclassifier.ConversationActions.Message.PERSON_USER_SELF;

import static com.google.common.truth.Truth.assertThat;

import android.app.Person;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.android.textclassifier.ActionsSuggestionsModel;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.function.Function;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ActionsSuggestionsHelperTest {
    private static final String LOCALE_TAG = Locale.US.toLanguageTag();
    private static final Function<CharSequence, String> LANGUAGE_DETECTOR =
            charSequence -> LOCALE_TAG;

    @Test
    public void testToNativeMessages_emptyInput() {
        ActionsSuggestionsModel.ConversationMessage[] conversationMessages =
                ActionsSuggestionsHelper.toNativeMessages(
                        Collections.emptyList(), LANGUAGE_DETECTOR);

        assertThat(conversationMessages).isEmpty();
    }

    @Test
    public void testToNativeMessages_noTextMessages() {
        ConversationActions.Message messageWithoutText =
                new ConversationActions.Message.Builder(PERSON_USER_OTHERS).build();

        ActionsSuggestionsModel.ConversationMessage[] conversationMessages =
                ActionsSuggestionsHelper.toNativeMessages(
                        Collections.singletonList(messageWithoutText), LANGUAGE_DETECTOR);

        assertThat(conversationMessages).isEmpty();
    }

    @Test
    public void testToNativeMessages_userIdEncoding() {
        Person userA = new Person.Builder().setName("userA").build();
        Person userB = new Person.Builder().setName("userB").build();

        ConversationActions.Message firstMessage =
                new ConversationActions.Message.Builder(userB)
                        .setText("first")
                        .build();
        ConversationActions.Message secondMessage =
                new ConversationActions.Message.Builder(userA)
                        .setText("second")
                        .build();
        ConversationActions.Message thirdMessage =
                new ConversationActions.Message.Builder(PERSON_USER_SELF)
                        .setText("third")
                        .build();
        ConversationActions.Message fourthMessage =
                new ConversationActions.Message.Builder(userA)
                        .setText("fourth")
                        .build();

        ActionsSuggestionsModel.ConversationMessage[] conversationMessages =
                ActionsSuggestionsHelper.toNativeMessages(
                        Arrays.asList(firstMessage, secondMessage, thirdMessage, fourthMessage),
                        LANGUAGE_DETECTOR);

        assertThat(conversationMessages).hasLength(4);
        assertNativeMessage(conversationMessages[0], firstMessage.getText(), 2, 0);
        assertNativeMessage(conversationMessages[1], secondMessage.getText(), 1, 0);
        assertNativeMessage(conversationMessages[2], thirdMessage.getText(), 0, 0);
        assertNativeMessage(conversationMessages[3], fourthMessage.getText(), 1, 0);
    }

    @Test
    public void testToNativeMessages_referenceTime() {
        ConversationActions.Message firstMessage =
                new ConversationActions.Message.Builder(PERSON_USER_OTHERS)
                        .setText("first")
                        .setReferenceTime(createZonedDateTimeFromMsUtc(1000))
                        .build();
        ConversationActions.Message secondMessage =
                new ConversationActions.Message.Builder(PERSON_USER_OTHERS)
                        .setText("second")
                        .build();
        ConversationActions.Message thirdMessage =
                new ConversationActions.Message.Builder(PERSON_USER_OTHERS)
                        .setText("third")
                        .setReferenceTime(createZonedDateTimeFromMsUtc(2000))
                        .build();

        ActionsSuggestionsModel.ConversationMessage[] conversationMessages =
                ActionsSuggestionsHelper.toNativeMessages(
                        Arrays.asList(firstMessage, secondMessage, thirdMessage),
                        LANGUAGE_DETECTOR);

        assertThat(conversationMessages).hasLength(3);
        assertNativeMessage(conversationMessages[0], firstMessage.getText(), 1, 1000);
        assertNativeMessage(conversationMessages[1], secondMessage.getText(), 1, 0);
        assertNativeMessage(conversationMessages[2], thirdMessage.getText(), 1, 2000);
    }

    private ZonedDateTime createZonedDateTimeFromMsUtc(long msUtc) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(msUtc), ZoneId.of("UTC"));
    }

    private static void assertNativeMessage(
            ActionsSuggestionsModel.ConversationMessage nativeMessage,
            CharSequence text,
            int userId,
            long referenceTimeInMsUtc) {
        assertThat(nativeMessage.getText()).isEqualTo(text.toString());
        assertThat(nativeMessage.getUserId()).isEqualTo(userId);
        assertThat(nativeMessage.getLocales()).isEqualTo(LOCALE_TAG);
        assertThat(nativeMessage.getReferenceTimeMsUtc()).isEqualTo(referenceTimeInMsUtc);
    }
}
