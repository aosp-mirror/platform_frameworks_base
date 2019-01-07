/**
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
package android.ext.services.notification;

import static com.google.common.truth.Truth.assertAbout;
import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.Person;
import android.content.Context;
import android.os.Process;
import android.service.notification.NotificationAssistantService;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextClassifierEvent;

import com.google.common.truth.FailureStrategy;
import com.google.common.truth.Subject;
import com.google.common.truth.SubjectFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

@RunWith(AndroidJUnit4.class)
public class SmartActionHelperTest {
    private static final String NOTIFICATION_KEY = "key";
    private static final String RESULT_ID = "id";

    private static final ConversationAction REPLY_ACTION =
            new ConversationAction.Builder(ConversationAction.TYPE_TEXT_REPLY)
            .setTextReply("Home")
            .build();

    private SmartActionsHelper mSmartActionsHelper;
    private Context mContext;
    @Mock private TextClassifier mTextClassifier;
    @Mock private NotificationEntry mNotificationEntry;
    @Mock private StatusBarNotification mStatusBarNotification;
    private Notification.Builder mNotificationBuilder;
    private AssistantSettings mSettings;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mContext = InstrumentationRegistry.getTargetContext();

        mContext.getSystemService(TextClassificationManager.class)
                .setTextClassifier(mTextClassifier);
        when(mTextClassifier.suggestConversationActions(any(ConversationActions.Request.class)))
                .thenReturn(new ConversationActions(Arrays.asList(REPLY_ACTION), RESULT_ID));

        when(mNotificationEntry.getSbn()).thenReturn(mStatusBarNotification);
        // The notification is eligible to have smart suggestions.
        when(mNotificationEntry.hasInlineReply()).thenReturn(true);
        when(mNotificationEntry.isMessaging()).thenReturn(true);
        when(mStatusBarNotification.getPackageName()).thenReturn("random.app");
        when(mStatusBarNotification.getUser()).thenReturn(Process.myUserHandle());
        when(mStatusBarNotification.getKey()).thenReturn(NOTIFICATION_KEY);
        mNotificationBuilder = new Notification.Builder(mContext, "channel");
        mSettings = AssistantSettings.createForTesting(
                null, null, Process.myUserHandle().getIdentifier(), null);
        mSettings.mGenerateActions = true;
        mSettings.mGenerateReplies = true;
        mSmartActionsHelper = new SmartActionsHelper(mContext, mSettings);
    }

    @Test
    public void testSuggestReplies_notMessagingApp() {
        when(mNotificationEntry.isMessaging()).thenReturn(false);
        ArrayList<CharSequence> textReplies =
                mSmartActionsHelper.suggestReplies(mNotificationEntry);
        assertThat(textReplies).isEmpty();
    }

    @Test
    public void testSuggestReplies_noInlineReply() {
        when(mNotificationEntry.hasInlineReply()).thenReturn(false);
        ArrayList<CharSequence> textReplies =
                mSmartActionsHelper.suggestReplies(mNotificationEntry);
        assertThat(textReplies).isEmpty();
    }

    @Test
    public void testSuggestReplies_nonMessageStyle() {
        Notification notification = mNotificationBuilder.setContentText("Where are you?").build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        List<ConversationActions.Message> messages = getMessagesInRequest();
        assertThat(messages).hasSize(1);
        MessageSubject.assertThat(messages.get(0)).hasText("Where are you?");
    }

    @Test
    public void testSuggestReplies_messageStyle() {
        Person me = new Person.Builder().setName("Me").build();
        Person userA = new Person.Builder().setName("A").build();
        Person userB = new Person.Builder().setName("B").build();
        Notification.MessagingStyle style =
                new Notification.MessagingStyle(me)
                        .addMessage("firstMessage", 1000, (Person) null)
                        .addMessage("secondMessage", 2000, me)
                        .addMessage("thirdMessage", 3000, userA)
                        .addMessage("fourthMessage", 4000, userB);
        Notification notification =
                mNotificationBuilder
                        .setContentText("You have three new messages")
                        .setStyle(style)
                        .build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        List<ConversationActions.Message> messages = getMessagesInRequest();
        assertThat(messages).hasSize(3);

        ConversationActions.Message secondMessage = messages.get(0);
        MessageSubject.assertThat(secondMessage).hasText("secondMessage");
        MessageSubject.assertThat(secondMessage)
                .hasPerson(ConversationActions.Message.PERSON_USER_LOCAL);
        MessageSubject.assertThat(secondMessage)
                .hasReferenceTime(createZonedDateTimeFromMsUtc(2000));

        ConversationActions.Message thirdMessage = messages.get(1);
        MessageSubject.assertThat(thirdMessage).hasText("thirdMessage");
        MessageSubject.assertThat(thirdMessage).hasPerson(userA);
        MessageSubject.assertThat(thirdMessage)
                .hasReferenceTime(createZonedDateTimeFromMsUtc(3000));

        ConversationActions.Message fourthMessage = messages.get(2);
        MessageSubject.assertThat(fourthMessage).hasText("fourthMessage");
        MessageSubject.assertThat(fourthMessage).hasPerson(userB);
        MessageSubject.assertThat(fourthMessage)
                .hasReferenceTime(createZonedDateTimeFromMsUtc(4000));
    }

    @Test
    public void testSuggestReplies_messageStyle_noPerson() {
        Person me = new Person.Builder().setName("Me").build();
        Notification.MessagingStyle style =
                new Notification.MessagingStyle(me).addMessage("message", 1000, (Person) null);
        Notification notification =
                mNotificationBuilder
                        .setContentText("You have one new message")
                        .setStyle(style)
                        .build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggestReplies(mNotificationEntry);

        verify(mTextClassifier, never())
                .suggestConversationActions(any(ConversationActions.Request.class));
    }

    @Test
    public void testOnSuggestedReplySent() {
        final String message = "Where are you?";
        Notification notification = mNotificationBuilder.setContentText(message).build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggestReplies(mNotificationEntry);
        mSmartActionsHelper.onSuggestedReplySent(
                NOTIFICATION_KEY, message, NotificationAssistantService.SOURCE_FROM_ASSISTANT);

        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier).onTextClassifierEvent(argumentCaptor.capture());
        TextClassifierEvent textClassifierEvent = argumentCaptor.getValue();
        assertTextClassifierEvent(textClassifierEvent, TextClassifierEvent.TYPE_SMART_ACTION);
    }

    @Test
    public void testOnSuggestedReplySent_anotherNotification() {
        final String message = "Where are you?";
        Notification notification = mNotificationBuilder.setContentText(message).build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggestReplies(mNotificationEntry);
        mSmartActionsHelper.onSuggestedReplySent(
                "something_else", message, NotificationAssistantService.SOURCE_FROM_ASSISTANT);

        verify(mTextClassifier, never())
                .onTextClassifierEvent(Mockito.any(TextClassifierEvent.class));
    }

    @Test
    public void testOnSuggestedReplySent_missingResultId() {
        when(mTextClassifier.suggestConversationActions(any(ConversationActions.Request.class)))
                .thenReturn(new ConversationActions(Collections.emptyList(), null));

        final String message = "Where are you?";
        Notification notification = mNotificationBuilder.setContentText(message).build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggestReplies(mNotificationEntry);
        mSmartActionsHelper.onSuggestedReplySent(
                "something_else", message, NotificationAssistantService.SOURCE_FROM_ASSISTANT);

        verify(mTextClassifier, never())
                .onTextClassifierEvent(Mockito.any(TextClassifierEvent.class));
    }

    @Test
    public void testOnNotificationDirectReply() {
        Notification notification = mNotificationBuilder.setContentText("Where are you?").build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggestReplies(mNotificationEntry);
        mSmartActionsHelper.onNotificationDirectReplied(NOTIFICATION_KEY);

        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier).onTextClassifierEvent(argumentCaptor.capture());
        TextClassifierEvent textClassifierEvent = argumentCaptor.getValue();
        assertTextClassifierEvent(textClassifierEvent, TextClassifierEvent.TYPE_MANUAL_REPLY);
    }

    @Test
    public void testOnNotificationExpansionChanged() {
        final String message = "Where are you?";
        Notification notification = mNotificationBuilder.setContentText(message).build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggestReplies(mNotificationEntry);
        mSmartActionsHelper.onNotificationExpansionChanged(mNotificationEntry, true, true);

        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier).onTextClassifierEvent(argumentCaptor.capture());
        TextClassifierEvent textClassifierEvent = argumentCaptor.getValue();
        assertTextClassifierEvent(textClassifierEvent, TextClassifierEvent.TYPE_ACTIONS_SHOWN);
    }

    @Test
    public void testOnNotificationsSeen_notExpanded() {
        final String message = "Where are you?";
        Notification notification = mNotificationBuilder.setContentText(message).build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggestReplies(mNotificationEntry);
        mSmartActionsHelper.onNotificationExpansionChanged(mNotificationEntry, false, false);

        verify(mTextClassifier, never()).onTextClassifierEvent(
                Mockito.any(TextClassifierEvent.class));
    }

    @Test
    public void testOnNotifications_expanded() {
        final String message = "Where are you?";
        Notification notification = mNotificationBuilder.setContentText(message).build();
        when(mNotificationEntry.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggestReplies(mNotificationEntry);
        mSmartActionsHelper.onNotificationExpansionChanged(mNotificationEntry, false, true);

        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier).onTextClassifierEvent(argumentCaptor.capture());
        TextClassifierEvent textClassifierEvent = argumentCaptor.getValue();
        assertTextClassifierEvent(textClassifierEvent, TextClassifierEvent.TYPE_ACTIONS_SHOWN);
    }

    private ZonedDateTime createZonedDateTimeFromMsUtc(long msUtc) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(msUtc), ZoneOffset.systemDefault());
    }

    private List<ConversationActions.Message> getMessagesInRequest() {
        mSmartActionsHelper.suggestReplies(mNotificationEntry);

        ArgumentCaptor<ConversationActions.Request> argumentCaptor =
                ArgumentCaptor.forClass(ConversationActions.Request.class);
        verify(mTextClassifier).suggestConversationActions(argumentCaptor.capture());
        ConversationActions.Request request = argumentCaptor.getValue();
        return request.getConversation();
    }

    private void assertTextClassifierEvent(
            TextClassifierEvent textClassifierEvent, int expectedEventType) {
        assertThat(textClassifierEvent.getEventCategory())
                .isEqualTo(TextClassifierEvent.CATEGORY_CONVERSATION_ACTIONS);
        assertThat(textClassifierEvent.getEventContext().getPackageName())
                .isEqualTo(InstrumentationRegistry.getTargetContext().getPackageName());
        assertThat(textClassifierEvent.getEventContext().getWidgetType())
                .isEqualTo(TextClassifier.WIDGET_TYPE_NOTIFICATION);
        assertThat(textClassifierEvent.getEventType()).isEqualTo(expectedEventType);
    }

    private static final class MessageSubject
            extends Subject<MessageSubject, ConversationActions.Message> {

        private static final SubjectFactory<MessageSubject, ConversationActions.Message> FACTORY =
                new SubjectFactory<MessageSubject, ConversationActions.Message>() {
                    @Override
                    public MessageSubject getSubject(
                            @NonNull FailureStrategy failureStrategy,
                            @NonNull ConversationActions.Message subject) {
                        return new MessageSubject(failureStrategy, subject);
                    }
                };

        private MessageSubject(
                FailureStrategy failureStrategy, @Nullable ConversationActions.Message subject) {
            super(failureStrategy, subject);
        }

        private void hasText(String text) {
            if (!Objects.equals(text, getSubject().getText().toString())) {
                failWithBadResults("has text", text, "has", getSubject().getText());
            }
        }

        private void hasPerson(Person person) {
            if (!Objects.equals(person, getSubject().getAuthor())) {
                failWithBadResults("has author", person, "has", getSubject().getAuthor());
            }
        }

        private void hasReferenceTime(ZonedDateTime referenceTime) {
            if (!Objects.equals(referenceTime, getSubject().getReferenceTime())) {
                failWithBadResults(
                        "has reference time",
                        referenceTime,
                        "has",
                        getSubject().getReferenceTime());
            }
        }

        private static MessageSubject assertThat(ConversationActions.Message message) {
            return assertAbout(FACTORY).that(message);
        }
    }
}
