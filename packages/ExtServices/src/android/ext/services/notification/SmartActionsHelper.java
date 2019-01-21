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
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.service.notification.NotificationAssistantService;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextClassifierEvent;
import android.view.textclassifier.TextLinks;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

public class SmartActionsHelper {
    private static final ArrayList<Notification.Action> EMPTY_ACTION_LIST = new ArrayList<>();
    private static final ArrayList<CharSequence> EMPTY_REPLY_LIST = new ArrayList<>();

    private static final String KEY_ACTION_TYPE = "action_type";
    // If a notification has any of these flags set, it's inelgibile for actions being added.
    private static final int FLAG_MASK_INELGIBILE_FOR_ACTIONS =
            Notification.FLAG_ONGOING_EVENT
                    | Notification.FLAG_FOREGROUND_SERVICE
                    | Notification.FLAG_GROUP_SUMMARY
                    | Notification.FLAG_NO_CLEAR;
    private static final int MAX_ACTION_EXTRACTION_TEXT_LENGTH = 400;
    private static final int MAX_ACTIONS_PER_LINK = 1;
    private static final int MAX_SMART_ACTIONS = 3;
    private static final int MAX_SUGGESTED_REPLIES = 3;
    // TODO: Make this configurable.
    private static final int MAX_MESSAGES_TO_EXTRACT = 5;
    private static final int MAX_RESULT_ID_TO_CACHE = 20;

    private static final TextClassifier.EntityConfig TYPE_CONFIG =
            new TextClassifier.EntityConfig.Builder().setIncludedTypes(
                    Collections.singletonList(ConversationAction.TYPE_TEXT_REPLY))
                    .includeTypesFromTextClassifier(false)
                    .build();
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

    /**
     * Adds action adjustments based on the notification contents.
     */
    @NonNull
    ArrayList<Notification.Action> suggestActions(@NonNull NotificationEntry entry) {
        if (!mSettings.mGenerateActions) {
            return EMPTY_ACTION_LIST;
        }
        if (!isEligibleForActionAdjustment(entry)) {
            return EMPTY_ACTION_LIST;
        }
        if (mTextClassifier == null) {
            return EMPTY_ACTION_LIST;
        }
        List<ConversationActions.Message> messages = extractMessages(entry.getNotification());
        if (messages.isEmpty()) {
            return EMPTY_ACTION_LIST;
        }
        // TODO: Move to TextClassifier.suggestConversationActions once it is ready.
        return suggestActionsFromText(
                messages.get(messages.size() - 1).getText(), MAX_SMART_ACTIONS);
    }

    ArrayList<CharSequence> suggestReplies(@NonNull NotificationEntry entry) {
        if (!mSettings.mGenerateReplies) {
            return EMPTY_REPLY_LIST;
        }
        if (!isEligibleForReplyAdjustment(entry)) {
            return EMPTY_REPLY_LIST;
        }
        if (mTextClassifier == null) {
            return EMPTY_REPLY_LIST;
        }
        List<ConversationActions.Message> messages = extractMessages(entry.getNotification());
        if (messages.isEmpty()) {
            return EMPTY_REPLY_LIST;
        }
        ConversationActions.Request request =
                new ConversationActions.Request.Builder(messages)
                        .setMaxSuggestions(MAX_SUGGESTED_REPLIES)
                        .setHints(HINTS)
                        .setTypeConfig(TYPE_CONFIG)
                        .build();

        ConversationActions conversationActionsResult =
                mTextClassifier.suggestConversationActions(request);
        List<ConversationAction> conversationActions =
                conversationActionsResult.getConversationActions();
        ArrayList<CharSequence> replies = conversationActions.stream()
                .map(conversationAction -> conversationAction.getTextReply())
                .filter(textReply -> !TextUtils.isEmpty(textReply))
                .collect(Collectors.toCollection(ArrayList::new));

        String resultId = conversationActionsResult.getId();
        if (resultId != null && !replies.isEmpty()) {
            mNotificationKeyToResultIdCache.put(entry.getSbn().getKey(), resultId);
        }
        return replies;
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
                        .setEntityType(ConversationAction.TYPE_TEXT_REPLY)
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
                        .setEntityType(actionType)
                        .build();
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
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
            return Arrays.asList(new ConversationActions.Message.Builder(
                    ConversationActions.Message.PERSON_USER_REMOTE)
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
            Person senderPerson = message.getSenderPerson();
            // Skip encoding once the sender is missing as it is important to distinguish
            // local user and remote user when generating replies.
            if (senderPerson == null) {
                break;
            }
            Person author = localUser != null && localUser.equals(senderPerson)
                    ? ConversationActions.Message.PERSON_USER_LOCAL : senderPerson;
            extractMessages.push(new ConversationActions.Message.Builder(author)
                    .setText(message.getText())
                    .setReferenceTime(
                            ZonedDateTime.ofInstant(Instant.ofEpochMilli(message.getTimestamp()),
                                    ZoneOffset.systemDefault()))
                    .build());
            if (extractMessages.size() >= MAX_MESSAGES_TO_EXTRACT) {
                break;
            }
        }
        return new ArrayList<>(extractMessages);
    }

    /** Returns a list of actions to act on entities in a given piece of text. */
    @NonNull
    private ArrayList<Notification.Action> suggestActionsFromText(
            @Nullable CharSequence text, int maxSmartActions) {
        if (TextUtils.isEmpty(text)) {
            return EMPTY_ACTION_LIST;
        }
        // We want to process only text visible to the user to avoid confusing suggestions, so we
        // truncate the text to a reasonable length. This is particularly important for e.g.
        // email apps that sometimes include the text for the entire thread.
        text = text.subSequence(0, Math.min(text.length(), MAX_ACTION_EXTRACTION_TEXT_LENGTH));

        // Extract all entities.
        TextLinks.Request textLinksRequest = new TextLinks.Request.Builder(text)
                .setEntityConfig(
                        TextClassifier.EntityConfig.createWithHints(
                                Collections.singletonList(
                                        TextClassifier.HINT_TEXT_IS_NOT_EDITABLE)))
                .build();
        TextLinks links = mTextClassifier.generateLinks(textLinksRequest);
        EntityTypeCounter entityTypeCounter = EntityTypeCounter.fromTextLinks(links);

        ArrayList<Notification.Action> actions = new ArrayList<>();
        for (TextLinks.TextLink link : links.getLinks()) {
            // Ignore any entity type for which we have too many entities. This is to handle the
            // case where a notification contains e.g. a list of phone numbers. In such cases, the
            // user likely wants to act on the whole list rather than an individual entity.
            if (link.getEntityCount() == 0
                    || entityTypeCounter.getCount(link.getEntity(0)) != 1) {
                continue;
            }

            // Generate the actions, and add the most prominent ones to the action bar.
            TextClassification classification =
                    mTextClassifier.classifyText(
                            new TextClassification.Request.Builder(
                                    text, link.getStart(), link.getEnd()).build());
            if (classification.getEntityCount() == 0) {
                continue;
            }
            int numOfActions = Math.min(
                    MAX_ACTIONS_PER_LINK, classification.getActions().size());
            for (int i = 0; i < numOfActions; ++i) {
                RemoteAction remoteAction = classification.getActions().get(i);
                Notification.Action action = new Notification.Action.Builder(
                        remoteAction.getIcon(),
                        remoteAction.getTitle(),
                        remoteAction.getActionIntent())
                        .setContextual(true)
                        .addExtras(Bundle.forPair(KEY_ACTION_TYPE, classification.getEntity(0)))
                        .build();
                actions.add(action);

                // We have enough smart actions.
                if (actions.size() >= maxSmartActions) {
                    return actions;
                }
            }
        }
        return actions;
    }
}
