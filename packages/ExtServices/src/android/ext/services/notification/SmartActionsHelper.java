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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.Person;
import android.app.RemoteAction;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.service.notification.NotificationAssistantService;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextClassifierEvent;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public class SmartActionsHelper {
    private static final String KEY_ACTION_TYPE = "action_type";
    // If a notification has any of these flags set, it's inelgibile for actions being added.
    private static final int FLAG_MASK_INELGIBILE_FOR_ACTIONS =
            Notification.FLAG_ONGOING_EVENT
                    | Notification.FLAG_FOREGROUND_SERVICE
                    | Notification.FLAG_GROUP_SUMMARY
                    | Notification.FLAG_NO_CLEAR;
    private static final int MAX_RESULT_ID_TO_CACHE = 20;

    private static final List<String> HINTS =
            Collections.singletonList(ConversationActions.Request.HINT_FOR_NOTIFICATION);

    private Context mContext;
    @Nullable
    private TextClassifier mTextClassifier;
    @NonNull
    private AssistantSettings mSettings;
    private LruCache<String, String> mNotificationKeyToResultIdCache =
            new LruCache<>(MAX_RESULT_ID_TO_CACHE);

    SmartActionsHelper(Context context, AssistantSettings settings) {
        mContext = context;
        TextClassificationManager textClassificationManager =
                mContext.getSystemService(TextClassificationManager.class);
        if (textClassificationManager != null) {
            mTextClassifier = textClassificationManager.getTextClassifier();
        }
        mSettings = settings;
    }

    SmartSuggestions suggest(@NonNull NotificationEntry entry) {
        // Whenever suggest() is called on a notification, its previous session is ended.
        mNotificationKeyToResultIdCache.remove(entry.getSbn().getKey());

        boolean eligibleForReplyAdjustment =
                mSettings.mGenerateReplies && isEligibleForReplyAdjustment(entry);
        boolean eligibleForActionAdjustment =
                mSettings.mGenerateActions && isEligibleForActionAdjustment(entry);

        List<ConversationAction> conversationActions =
                suggestConversationActions(
                        entry,
                        eligibleForReplyAdjustment,
                        eligibleForActionAdjustment);

        ArrayList<CharSequence> replies = conversationActions.stream()
                .map(ConversationAction::getTextReply)
                .filter(textReply -> !TextUtils.isEmpty(textReply))
                .collect(Collectors.toCollection(ArrayList::new));

        ArrayList<Notification.Action> actions = conversationActions.stream()
                .filter(conversationAction -> conversationAction.getAction() != null)
                .map(action -> createNotificationAction(action.getAction(), action.getType()))
                .collect(Collectors.toCollection(ArrayList::new));
        return new SmartSuggestions(replies, actions);
    }

    /**
     * Adds action adjustments based on the notification contents.
     */
    @NonNull
    private List<ConversationAction> suggestConversationActions(
            @NonNull NotificationEntry entry,
            boolean includeReplies,
            boolean includeActions) {
        if (!includeReplies && !includeActions) {
            return Collections.emptyList();
        }
        if (mTextClassifier == null) {
            return Collections.emptyList();
        }
        List<ConversationActions.Message> messages = extractMessages(entry.getNotification());
        if (messages.isEmpty()) {
            return Collections.emptyList();
        }
        // Do not generate smart actions if the last message is from the local user.
        ConversationActions.Message lastMessage = messages.get(messages.size() - 1);
        if (arePersonsEqual(
                ConversationActions.Message.PERSON_USER_SELF, lastMessage.getAuthor())) {
            return Collections.emptyList();
        }

        TextClassifier.EntityConfig.Builder typeConfigBuilder =
                new TextClassifier.EntityConfig.Builder();
        if (!includeReplies) {
            typeConfigBuilder.setExcludedTypes(
                    Collections.singletonList(ConversationAction.TYPE_TEXT_REPLY));
        } else if (!includeActions) {
            typeConfigBuilder
                    .setIncludedTypes(
                            Collections.singletonList(ConversationAction.TYPE_TEXT_REPLY))
                    .includeTypesFromTextClassifier(false);
        }
        ConversationActions.Request request =
                new ConversationActions.Request.Builder(messages)
                        .setMaxSuggestions(mSettings.mMaxSuggestions)
                        .setHints(HINTS)
                        .setTypeConfig(typeConfigBuilder.build())
                        .build();

        ConversationActions conversationActionsResult =
                mTextClassifier.suggestConversationActions(request);

        String resultId = conversationActionsResult.getId();
        if (!TextUtils.isEmpty(resultId)
                && !conversationActionsResult.getConversationActions().isEmpty()) {
            mNotificationKeyToResultIdCache.put(entry.getSbn().getKey(), resultId);
        }
        return conversationActionsResult.getConversationActions();
    }

    void onNotificationExpansionChanged(@NonNull NotificationEntry entry, boolean isUserAction,
            boolean isExpanded) {
        if (!isExpanded) {
            return;
        }
        String resultId = mNotificationKeyToResultIdCache.get(entry.getSbn().getKey());
        if (resultId == null) {
            return;
        }
        // Only report if this is the first time the user sees these suggestions.
        if (entry.isShowActionEventLogged()) {
            return;
        }
        entry.setShowActionEventLogged();
        TextClassifierEvent textClassifierEvent =
                createTextClassifierEventBuilder(TextClassifierEvent.TYPE_ACTIONS_SHOWN,
                        resultId)
                        .build();
        // TODO: If possible, report which replies / actions are actually seen by user.
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
    }

    void onNotificationDirectReplied(@NonNull String key) {
        if (mTextClassifier == null) {
            return;
        }
        String resultId = mNotificationKeyToResultIdCache.get(key);
        if (resultId == null) {
            return;
        }
        TextClassifierEvent textClassifierEvent =
                createTextClassifierEventBuilder(TextClassifierEvent.TYPE_MANUAL_REPLY, resultId)
                        .build();
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
    }

    void onSuggestedReplySent(@NonNull String key, @NonNull CharSequence reply,
            @NotificationAssistantService.Source int source) {
        if (mTextClassifier == null) {
            return;
        }
        if (source != NotificationAssistantService.SOURCE_FROM_ASSISTANT) {
            return;
        }
        String resultId = mNotificationKeyToResultIdCache.get(key);
        if (resultId == null) {
            return;
        }
        TextClassifierEvent textClassifierEvent =
                createTextClassifierEventBuilder(TextClassifierEvent.TYPE_SMART_ACTION, resultId)
                        .setEntityTypes(ConversationAction.TYPE_TEXT_REPLY)
                        .build();
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
    }

    void onActionClicked(@NonNull String key, @NonNull Notification.Action action,
            @NotificationAssistantService.Source int source) {
        if (mTextClassifier == null) {
            return;
        }
        if (source != NotificationAssistantService.SOURCE_FROM_ASSISTANT) {
            return;
        }
        String resultId = mNotificationKeyToResultIdCache.get(key);
        if (resultId == null) {
            return;
        }
        String actionType = action.getExtras().getString(KEY_ACTION_TYPE);
        if (actionType == null) {
            return;
        }
        TextClassifierEvent textClassifierEvent =
                createTextClassifierEventBuilder(TextClassifierEvent.TYPE_SMART_ACTION, resultId)
                        .setEntityTypes(actionType)
                        .build();
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
    }

    private Notification.Action createNotificationAction(
            RemoteAction remoteAction, String actionType) {
        Icon icon = remoteAction.shouldShowIcon()
                ? remoteAction.getIcon()
                : Icon.createWithResource(mContext, com.android.internal.R.drawable.ic_action_open);
        return new Notification.Action.Builder(
                icon,
                remoteAction.getTitle(),
                remoteAction.getActionIntent())
                .setContextual(true)
                .addExtras(Bundle.forPair(KEY_ACTION_TYPE, actionType))
                .build();
    }

    private TextClassifierEvent.Builder createTextClassifierEventBuilder(
            int eventType, @NonNull String resultId) {
        return new TextClassifierEvent.Builder(
                TextClassifierEvent.CATEGORY_CONVERSATION_ACTIONS, eventType)
                .setEventTime(System.currentTimeMillis())
                .setEventContext(
                        new TextClassificationContext.Builder(
                                mContext.getPackageName(), TextClassifier.WIDGET_TYPE_NOTIFICATION)
                        .build())
                .setResultId(resultId);
    }

    /**
     * Returns whether a notification is eligible for action adjustments.
     *
     * <p>We exclude system notifications, those that get refreshed frequently, or ones that relate
     * to fundamental phone functionality where any error would result in a very negative user
     * experience.
     */
    private boolean isEligibleForActionAdjustment(@NonNull NotificationEntry entry) {
        Notification notification = entry.getNotification();
        String pkg = entry.getSbn().getPackageName();
        if (!Process.myUserHandle().equals(entry.getSbn().getUser())) {
            return false;
        }
        if ((notification.flags & FLAG_MASK_INELGIBILE_FOR_ACTIONS) != 0) {
            return false;
        }
        if (TextUtils.isEmpty(pkg) || pkg.equals("android")) {
            return false;
        }
        // For now, we are only interested in messages.
        return entry.isMessaging();
    }

    private boolean isEligibleForReplyAdjustment(@NonNull NotificationEntry entry) {
        if (!Process.myUserHandle().equals(entry.getSbn().getUser())) {
            return false;
        }
        String pkg = entry.getSbn().getPackageName();
        if (TextUtils.isEmpty(pkg) || pkg.equals("android")) {
            return false;
        }
        // For now, we are only interested in messages.
        if (!entry.isMessaging()) {
            return false;
        }
        // Does not make sense to provide suggested replies if it is not something that can be
        // replied.
        if (!entry.hasInlineReply()) {
            return false;
        }
        return true;
    }

    /** Returns the text most salient for action extraction in a notification. */
    @Nullable
    private List<ConversationActions.Message> extractMessages(@NonNull Notification notification) {
        Parcelable[] messages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages == null || messages.length == 0) {
            return Collections.singletonList(new ConversationActions.Message.Builder(
                    ConversationActions.Message.PERSON_USER_OTHERS)
                    .setText(notification.extras.getCharSequence(Notification.EXTRA_TEXT))
                    .build());
        }
        Person localUser = notification.extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON);
        Deque<ConversationActions.Message> extractMessages = new ArrayDeque<>();
        for (int i = messages.length - 1; i >= 0; i--) {
            Notification.MessagingStyle.Message message =
                    Notification.MessagingStyle.Message.getMessageFromBundle((Bundle) messages[i]);
            if (message == null) {
                continue;
            }
            // As per the javadoc of Notification.addMessage, null means local user.
            Person senderPerson = message.getSenderPerson();
            if (senderPerson == null) {
                senderPerson = localUser;
            }
            Person author = localUser != null && arePersonsEqual(localUser, senderPerson)
                    ? ConversationActions.Message.PERSON_USER_SELF : senderPerson;
            extractMessages.push(new ConversationActions.Message.Builder(author)
                    .setText(message.getText())
                    .setReferenceTime(
                            ZonedDateTime.ofInstant(Instant.ofEpochMilli(message.getTimestamp()),
                                    ZoneOffset.systemDefault()))
                    .build());
            if (extractMessages.size() >= mSettings.mMaxMessagesToExtract) {
                break;
            }
        }
        return new ArrayList<>(extractMessages);
    }

    private static boolean arePersonsEqual(@NonNull Person left, @NonNull Person right) {
        return Objects.equals(left.getKey(), right.getKey())
                && Objects.equals(left.getName(), right.getName())
                && Objects.equals(left.getUri(), right.getUri());
    }

    static class SmartSuggestions {
        public final ArrayList<CharSequence> replies;
        public final ArrayList<Notification.Action> actions;

        SmartSuggestions(
                ArrayList<CharSequence> replies, ArrayList<Notification.Action> actions) {
            this.replies = replies;
            this.actions = actions;
        }
    }
}
