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

import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.view.textclassifier.intent.LabeledIntent;
import android.view.textclassifier.intent.TemplateIntentFactory;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.google.android.textclassifier.ActionsSuggestionsModel;
import com.google.android.textclassifier.RemoteActionTemplate;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

    @Test
    public void testDeduplicateActions() {
        Bundle phoneExtras = new Bundle();
        Intent phoneIntent = new Intent();
        phoneIntent.setComponent(new ComponentName("phone", "intent"));
        ExtrasUtils.putActionIntent(phoneExtras, phoneIntent);

        Bundle anotherPhoneExtras = new Bundle();
        Intent anotherPhoneIntent = new Intent();
        anotherPhoneIntent.setComponent(new ComponentName("phone", "another.intent"));
        ExtrasUtils.putActionIntent(anotherPhoneExtras, anotherPhoneIntent);

        Bundle urlExtras = new Bundle();
        Intent urlIntent = new Intent();
        urlIntent.setComponent(new ComponentName("url", "intent"));
        ExtrasUtils.putActionIntent(urlExtras, urlIntent);

        PendingIntent pendingIntent = PendingIntent.getActivity(
                InstrumentationRegistry.getTargetContext(),
                0,
                phoneIntent,
                0);
        Icon icon = Icon.createWithData(new byte[0], 0, 0);
        ConversationAction action =
                new ConversationAction.Builder(ConversationAction.TYPE_CALL_PHONE)
                        .setAction(new RemoteAction(icon, "label", "1", pendingIntent))
                        .setExtras(phoneExtras)
                        .build();
        ConversationAction actionWithSameLabel =
                new ConversationAction.Builder(ConversationAction.TYPE_CALL_PHONE)
                        .setAction(new RemoteAction(
                                icon, "label", "2", pendingIntent))
                        .setExtras(phoneExtras)
                        .build();
        ConversationAction actionWithSamePackageButDifferentClass =
                new ConversationAction.Builder(ConversationAction.TYPE_CALL_PHONE)
                        .setAction(new RemoteAction(
                                icon, "label", "3", pendingIntent))
                        .setExtras(anotherPhoneExtras)
                        .build();
        ConversationAction actionWithDifferentLabel =
                new ConversationAction.Builder(ConversationAction.TYPE_CALL_PHONE)
                        .setAction(new RemoteAction(
                                icon, "another_label", "4", pendingIntent))
                        .setExtras(phoneExtras)
                        .build();
        ConversationAction actionWithDifferentPackage =
                new ConversationAction.Builder(ConversationAction.TYPE_OPEN_URL)
                        .setAction(new RemoteAction(icon, "label", "5", pendingIntent))
                        .setExtras(urlExtras)
                        .build();
        ConversationAction actionWithoutRemoteAction =
                new ConversationAction.Builder(ConversationAction.TYPE_CREATE_REMINDER)
                        .build();

        List<ConversationAction> conversationActions =
                ActionsSuggestionsHelper.removeActionsWithDuplicates(
                        Arrays.asList(action, actionWithSameLabel,
                                actionWithSamePackageButDifferentClass, actionWithDifferentLabel,
                                actionWithDifferentPackage, actionWithoutRemoteAction));

        assertThat(conversationActions).hasSize(3);
        assertThat(conversationActions.get(0).getAction().getContentDescription()).isEqualTo("4");
        assertThat(conversationActions.get(1).getAction().getContentDescription()).isEqualTo("5");
        assertThat(conversationActions.get(2).getAction()).isNull();
    }

    @Test
    public void testDeduplicateActions_nullComponent() {
        Bundle phoneExtras = new Bundle();
        Intent phoneIntent = new Intent(Intent.ACTION_DIAL);
        ExtrasUtils.putActionIntent(phoneExtras, phoneIntent);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                InstrumentationRegistry.getTargetContext(),
                0,
                phoneIntent,
                0);
        Icon icon = Icon.createWithData(new byte[0], 0, 0);
        ConversationAction action =
                new ConversationAction.Builder(ConversationAction.TYPE_CALL_PHONE)
                        .setAction(new RemoteAction(icon, "label", "1", pendingIntent))
                        .setExtras(phoneExtras)
                        .build();
        ConversationAction actionWithSameLabel =
                new ConversationAction.Builder(ConversationAction.TYPE_CALL_PHONE)
                        .setAction(new RemoteAction(
                                icon, "label", "2", pendingIntent))
                        .setExtras(phoneExtras)
                        .build();

        List<ConversationAction> conversationActions =
                ActionsSuggestionsHelper.removeActionsWithDuplicates(
                        Arrays.asList(action, actionWithSameLabel));

        assertThat(conversationActions).isEmpty();
    }

    public void createLabeledIntentResult_null() {
        ActionsSuggestionsModel.ActionSuggestion nativeSuggestion =
                new ActionsSuggestionsModel.ActionSuggestion(
                        "text",
                        ConversationAction.TYPE_OPEN_URL,
                        1.0f,
                        null,
                        null,
                        null
                );

        LabeledIntent.Result labeledIntentResult =
                ActionsSuggestionsHelper.createLabeledIntentResult(
                        InstrumentationRegistry.getTargetContext(),
                        new TemplateIntentFactory(),
                        nativeSuggestion);

        assertThat(labeledIntentResult).isNull();
    }

    @Test
    public void createLabeledIntentResult_emptyList() {
        ActionsSuggestionsModel.ActionSuggestion nativeSuggestion =
                new ActionsSuggestionsModel.ActionSuggestion(
                        "text",
                        ConversationAction.TYPE_OPEN_URL,
                        1.0f,
                        null,
                        null,
                        new RemoteActionTemplate[0]
                );

        LabeledIntent.Result labeledIntentResult =
                ActionsSuggestionsHelper.createLabeledIntentResult(
                        InstrumentationRegistry.getTargetContext(),
                        new TemplateIntentFactory(),
                        nativeSuggestion);

        assertThat(labeledIntentResult).isNull();
    }

    @Test
    public void createLabeledIntentResult() {
        ActionsSuggestionsModel.ActionSuggestion nativeSuggestion =
                new ActionsSuggestionsModel.ActionSuggestion(
                        "text",
                        ConversationAction.TYPE_OPEN_URL,
                        1.0f,
                        null,
                        null,
                        new RemoteActionTemplate[]{
                                new RemoteActionTemplate(
                                        "title",
                                        null,
                                        "description",
                                        null,
                                        Intent.ACTION_VIEW,
                                        Uri.parse("http://www.android.com").toString(),
                                        null,
                                        0,
                                        null,
                                        null,
                                        null,
                                        0)});

        LabeledIntent.Result labeledIntentResult =
                ActionsSuggestionsHelper.createLabeledIntentResult(
                        InstrumentationRegistry.getTargetContext(),
                        new TemplateIntentFactory(),
                        nativeSuggestion);

        assertThat(labeledIntentResult.remoteAction.getTitle()).isEqualTo("title");
        assertThat(labeledIntentResult.resolvedIntent.getAction()).isEqualTo(Intent.ACTION_VIEW);
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
        assertThat(nativeMessage.getDetectedTextLanguageTags()).isEqualTo(LOCALE_TAG);
        assertThat(nativeMessage.getReferenceTimeMsUtc()).isEqualTo(referenceTimeInMsUtc);
    }
}
