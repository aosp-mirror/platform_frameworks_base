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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Process;
import android.service.notification.NotificationAssistantService;
import android.service.notification.StatusBarNotification;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextClassifierEvent;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.truth.FailureStrategy;
import com.google.common.truth.Subject;
import com.google.common.truth.SubjectFactory;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

@RunWith(AndroidJUnit4.class)
public class SmartActionsHelperTest {
    private static final String NOTIFICATION_KEY = "key";
    private static final String RESULT_ID = "id";
    private static final float SCORE = 0.7f;
    private static final CharSequence SMART_REPLY = "Home";
    private static final ConversationAction REPLY_ACTION =
            new ConversationAction.Builder(ConversationAction.TYPE_TEXT_REPLY)
                    .setTextReply(SMART_REPLY)
                    .setConfidenceScore(SCORE)
                    .build();
    private static final String MESSAGE = "Where are you?";

    @Mock
    IPackageManager mIPackageManager;
    @Mock
    private TextClassifier mTextClassifier;
    @Mock
    private StatusBarNotification mStatusBarNotification;
    @Mock
    private SmsHelper mSmsHelper;

    private SmartActionsHelper mSmartActionsHelper;
    private Context mContext;
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
    public void testSuggest_notMessageNotification() {
        Notification notification = mNotificationBuilder.setContentText(MESSAGE).build();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());

        verify(mTextClassifier, never())
                .suggestConversationActions(any(ConversationActions.Request.class));
    }

    @Test
    public void testSuggest_noInlineReply() {
        Notification notification =
                mNotificationBuilder
                        .setContentText(MESSAGE)
                        .setCategory(Notification.CATEGORY_MESSAGE)
                        .build();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        ConversationActions.Request request = runSuggestAndCaptureRequest();

        // actions are enabled, but replies are not.
        assertThat(
                request.getTypeConfig().resolveEntityListModifications(
                        Arrays.asList(ConversationAction.TYPE_TEXT_REPLY,
                                ConversationAction.TYPE_OPEN_URL)))
                .containsExactly(ConversationAction.TYPE_OPEN_URL);
    }

    @Test
    public void testSuggest_settingsOff() {
        mSettings.mGenerateActions = false;
        mSettings.mGenerateReplies = false;
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());

        verify(mTextClassifier, never())
                .suggestConversationActions(any(ConversationActions.Request.class));
    }

    @Test
    public void testSuggest_settings_repliesOnActionsOff() {
        mSettings.mGenerateReplies = true;
        mSettings.mGenerateActions = false;
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        ConversationActions.Request request = runSuggestAndCaptureRequest();

        // replies are enabled, but actions are not.
        assertThat(
                request.getTypeConfig().resolveEntityListModifications(
                        Arrays.asList(ConversationAction.TYPE_TEXT_REPLY,
                                ConversationAction.TYPE_OPEN_URL)))
                .containsExactly(ConversationAction.TYPE_TEXT_REPLY);
    }

    @Test
    public void testSuggest_settings_repliesOffActionsOn() {
        mSettings.mGenerateReplies = false;
        mSettings.mGenerateActions = true;
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        ConversationActions.Request request = runSuggestAndCaptureRequest();

        // actions are enabled, but replies are not.
        assertThat(
                request.getTypeConfig().resolveEntityListModifications(
                        Arrays.asList(ConversationAction.TYPE_TEXT_REPLY,
                                ConversationAction.TYPE_OPEN_URL)))
                .containsExactly(ConversationAction.TYPE_OPEN_URL);
    }


    @Test
    public void testSuggest_nonMessageStyleMessageNotification() {
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        List<ConversationActions.Message> messages =
                runSuggestAndCaptureRequest().getConversation();

        assertThat(messages).hasSize(1);
        MessageSubject.assertThat(messages.get(0)).hasText(MESSAGE);
        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier).onTextClassifierEvent(argumentCaptor.capture());
        TextClassifierEvent textClassifierEvent = argumentCaptor.getValue();
        assertTextClassifierEvent(textClassifierEvent, TextClassifierEvent.TYPE_ACTIONS_GENERATED);
        assertThat(textClassifierEvent.getEntityTypes()).asList()
                .containsExactly(ConversationAction.TYPE_TEXT_REPLY);
    }

    @Test
    public void testSuggest_messageStyle() {
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
                        .setActions(createReplyAction())
                        .build();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        List<ConversationActions.Message> messages =
                runSuggestAndCaptureRequest().getConversation();
        assertThat(messages).hasSize(4);

        ConversationActions.Message firstMessage = messages.get(0);
        MessageSubject.assertThat(firstMessage).hasText("firstMessage");
        MessageSubject.assertThat(firstMessage)
                .hasPerson(ConversationActions.Message.PERSON_USER_SELF);
        MessageSubject.assertThat(firstMessage)
                .hasReferenceTime(createZonedDateTimeFromMsUtc(1000));

        ConversationActions.Message secondMessage = messages.get(1);
        MessageSubject.assertThat(secondMessage).hasText("secondMessage");
        MessageSubject.assertThat(secondMessage)
                .hasPerson(ConversationActions.Message.PERSON_USER_SELF);
        MessageSubject.assertThat(secondMessage)
                .hasReferenceTime(createZonedDateTimeFromMsUtc(2000));

        ConversationActions.Message thirdMessage = messages.get(2);
        MessageSubject.assertThat(thirdMessage).hasText("thirdMessage");
        MessageSubject.assertThat(thirdMessage).hasPerson(userA);
        MessageSubject.assertThat(thirdMessage)
                .hasReferenceTime(createZonedDateTimeFromMsUtc(3000));

        ConversationActions.Message fourthMessage = messages.get(3);
        MessageSubject.assertThat(fourthMessage).hasText("fourthMessage");
        MessageSubject.assertThat(fourthMessage).hasPerson(userB);
        MessageSubject.assertThat(fourthMessage)
                .hasReferenceTime(createZonedDateTimeFromMsUtc(4000));

        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier).onTextClassifierEvent(argumentCaptor.capture());
        TextClassifierEvent textClassifierEvent = argumentCaptor.getValue();
        assertTextClassifierEvent(textClassifierEvent, TextClassifierEvent.TYPE_ACTIONS_GENERATED);
        assertThat(textClassifierEvent.getEntityTypes()).asList()
                .containsExactly(ConversationAction.TYPE_TEXT_REPLY);
    }

    @Test
    public void testSuggest_lastMessageLocalUser() {
        Person me = new Person.Builder().setName("Me").build();
        Person userA = new Person.Builder().setName("A").build();
        Notification.MessagingStyle style =
                new Notification.MessagingStyle(me)
                        .addMessage("firstMessage", 1000, userA)
                        .addMessage("secondMessage", 2000, me);
        Notification notification =
                mNotificationBuilder
                        .setContentText("You have two new messages")
                        .setStyle(style)
                        .setActions(createReplyAction())
                        .build();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());

        verify(mTextClassifier, never())
                .suggestConversationActions(any(ConversationActions.Request.class));
    }

    @Test
    public void testSuggest_messageStyle_noPerson() {
        Person me = new Person.Builder().setName("Me").build();
        Notification.MessagingStyle style =
                new Notification.MessagingStyle(me).addMessage("message", 1000, (Person) null);
        Notification notification =
                mNotificationBuilder
                        .setContentText("You have one new message")
                        .setStyle(style)
                        .setActions(createReplyAction())
                        .build();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());

        verify(mTextClassifier, never())
                .suggestConversationActions(any(ConversationActions.Request.class));
    }

    @Test
    public void testOnSuggestedReplySent() {
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());
        mSmartActionsHelper.onSuggestedReplySent(
                NOTIFICATION_KEY, SMART_REPLY, NotificationAssistantService.SOURCE_FROM_ASSISTANT);

        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier, times(2)).onTextClassifierEvent(argumentCaptor.capture());
        List<TextClassifierEvent> events = argumentCaptor.getAllValues();
        assertTextClassifierEvent(events.get(0), TextClassifierEvent.TYPE_ACTIONS_GENERATED);
        assertTextClassifierEvent(events.get(1), TextClassifierEvent.TYPE_SMART_ACTION);
        assertThat(events.get(1).getScore()).isEqualTo(SCORE);
    }

    @Test
    public void testOnSuggestedReplySent_anotherNotification() {
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());
        mSmartActionsHelper.onSuggestedReplySent(
                "something_else", MESSAGE, NotificationAssistantService.SOURCE_FROM_ASSISTANT);

        verify(mTextClassifier, never()).onTextClassifierEvent(
                argThat(new TextClassifierEventMatcher(TextClassifierEvent.TYPE_SMART_ACTION)));
    }

    @Test
    public void testOnSuggestedReplySent_missingResultId() {
        when(mTextClassifier.suggestConversationActions(any(ConversationActions.Request.class)))
                .thenReturn(new ConversationActions(Collections.singletonList(REPLY_ACTION), null));
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());
        mSmartActionsHelper.onSuggestedReplySent(
                NOTIFICATION_KEY, SMART_REPLY, NotificationAssistantService.SOURCE_FROM_ASSISTANT);

        verify(mTextClassifier, never()).onTextClassifierEvent(any(TextClassifierEvent.class));
    }

    @Test
    public void testOnNotificationDirectReply() {
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());
        mSmartActionsHelper.onNotificationDirectReplied(NOTIFICATION_KEY);

        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier, times(2)).onTextClassifierEvent(argumentCaptor.capture());
        List<TextClassifierEvent> events = argumentCaptor.getAllValues();
        assertTextClassifierEvent(events.get(0), TextClassifierEvent.TYPE_ACTIONS_GENERATED);
        assertTextClassifierEvent(events.get(1), TextClassifierEvent.TYPE_MANUAL_REPLY);
    }

    @Test
    public void testOnNotificationExpansionChanged() {
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());
        mSmartActionsHelper.onNotificationExpansionChanged(createNotificationEntry(), true, true);

        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier, times(2)).onTextClassifierEvent(argumentCaptor.capture());
        List<TextClassifierEvent> events = argumentCaptor.getAllValues();
        assertTextClassifierEvent(events.get(0), TextClassifierEvent.TYPE_ACTIONS_GENERATED);
        assertTextClassifierEvent(events.get(1), TextClassifierEvent.TYPE_ACTIONS_SHOWN);
    }

    @Test
    public void testOnNotificationsSeen_notExpanded() {
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());
        mSmartActionsHelper.onNotificationExpansionChanged(createNotificationEntry(), false, false);

        verify(mTextClassifier, never()).onTextClassifierEvent(
                argThat(new TextClassifierEventMatcher(TextClassifierEvent.TYPE_ACTIONS_SHOWN)));
    }

    @Test
    public void testOnNotifications_expanded() {
        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);

        mSmartActionsHelper.suggest(createNotificationEntry());
        mSmartActionsHelper.onNotificationExpansionChanged(createNotificationEntry(), false, true);

        ArgumentCaptor<TextClassifierEvent> argumentCaptor =
                ArgumentCaptor.forClass(TextClassifierEvent.class);
        verify(mTextClassifier, times(2)).onTextClassifierEvent(argumentCaptor.capture());
        List<TextClassifierEvent> events = argumentCaptor.getAllValues();
        assertTextClassifierEvent(events.get(0), TextClassifierEvent.TYPE_ACTIONS_GENERATED);
        assertTextClassifierEvent(events.get(1), TextClassifierEvent.TYPE_ACTIONS_SHOWN);
    }

    @Test
    public void testCopyAction() {
        Bundle extras = new Bundle();
        Bundle entitiesExtras = new Bundle();
        entitiesExtras.putString(SmartActionsHelper.KEY_TEXT, "12345");
        extras.putParcelable(SmartActionsHelper.ENTITIES_EXTRAS, entitiesExtras);
        ConversationAction conversationAction =
                new ConversationAction.Builder(ConversationAction.TYPE_COPY)
                        .setExtras(extras)
                        .build();
        when(mTextClassifier.suggestConversationActions(any(ConversationActions.Request.class)))
                .thenReturn(
                        new ConversationActions(
                                Collections.singletonList(conversationAction), null));

        Notification notification = createMessageNotification();
        when(mStatusBarNotification.getNotification()).thenReturn(notification);
        SmartActionsHelper.SmartSuggestions suggestions =
                mSmartActionsHelper.suggest(createNotificationEntry());

        assertThat(suggestions.actions).hasSize(1);
        Notification.Action action = suggestions.actions.get(0);
        assertThat(action.title).isEqualTo("12345");
    }

    private ZonedDateTime createZonedDateTimeFromMsUtc(long msUtc) {
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(msUtc), ZoneOffset.systemDefault());
    }

    private ConversationActions.Request runSuggestAndCaptureRequest() {
        mSmartActionsHelper.suggest(createNotificationEntry());

        ArgumentCaptor<ConversationActions.Request> argumentCaptor =
                ArgumentCaptor.forClass(ConversationActions.Request.class);
        verify(mTextClassifier).suggestConversationActions(argumentCaptor.capture());
        return argumentCaptor.getValue();
    }

    private Notification.Action createReplyAction() {
        PendingIntent pendingIntent =
                PendingIntent.getActivity(mContext, 0, new Intent(mContext, this.getClass()), 0);
        RemoteInput remoteInput = new RemoteInput.Builder("result")
                .setAllowFreeFormInput(true)
                .build();
        return new Notification.Action.Builder(
                Icon.createWithResource(mContext.getResources(),
                        android.R.drawable.stat_sys_warning),
                "Reply", pendingIntent)
                .addRemoteInput(remoteInput)
                .build();
    }

    private NotificationEntry createNotificationEntry() {
        NotificationChannel channel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_DEFAULT);
        return new NotificationEntry(mIPackageManager, mStatusBarNotification, channel, mSmsHelper);
    }

    private Notification createMessageNotification() {
        return mNotificationBuilder
                .setContentText(MESSAGE)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setActions(createReplyAction())
                .build();
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

    private final class TextClassifierEventMatcher implements ArgumentMatcher<TextClassifierEvent> {

        private int mType;

        private TextClassifierEventMatcher(int type) {
            mType = type;
        }

        @Override
        public boolean matches(TextClassifierEvent textClassifierEvent) {
            if (textClassifierEvent == null) {
                return false;
            }
            return mType == textClassifierEvent.getEventType();
        }
    }
}
