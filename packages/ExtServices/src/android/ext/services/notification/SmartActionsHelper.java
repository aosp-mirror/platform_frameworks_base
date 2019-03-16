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

import android.annotation.Nullable;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.RemoteAction;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.ext.services.R;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.service.notification.NotificationAssistantService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.LruCache;
import android.util.Pair;
import android.view.textclassifier.ConversationAction;
import android.view.textclassifier.ConversationActions;
import android.view.textclassifier.TextClassificationContext;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextClassifierEvent;

import com.android.internal.util.ArrayUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SmartActionsHelper {
    static final String ENTITIES_EXTRAS = "entities-extras";
    static final String KEY_ACTION_TYPE = "action_type";
    static final String KEY_ACTION_SCORE = "action_score";
    static final String KEY_TEXT = "text";
    // If a notification has any of these flags set, it's inelgibile for actions being added.
    private static final int FLAG_MASK_INELGIBILE_FOR_ACTIONS =
            Notification.FLAG_ONGOING_EVENT
                    | Notification.FLAG_FOREGROUND_SERVICE
                    | Notification.FLAG_GROUP_SUMMARY
                    | Notification.FLAG_NO_CLEAR;
    private static final int MAX_RESULT_ID_TO_CACHE = 20;

    private static final List<String> HINTS =
            Collections.singletonList(ConversationActions.Request.HINT_FOR_NOTIFICATION);
    private static final ConversationActions EMPTY_CONVERSATION_ACTIONS =
            new ConversationActions(Collections.emptyList(), null);

    private Context mContext;
    private TextClassifier mTextClassifier;
    private AssistantSettings mSettings;
    private LruCache<String, Session> mSessionCache = new LruCache<>(MAX_RESULT_ID_TO_CACHE);

    SmartActionsHelper(Context context, AssistantSettings settings) {
        mContext = context;
        TextClassificationManager textClassificationManager =
                mContext.getSystemService(TextClassificationManager.class);
        mTextClassifier = textClassificationManager.getTextClassifier();
        mSettings = settings;
    }

    SmartSuggestions suggest(NotificationEntry entry) {
        // Whenever suggest() is called on a notification, its previous session is ended.
        mSessionCache.remove(entry.getSbn().getKey());

        boolean eligibleForReplyAdjustment =
                mSettings.mGenerateReplies && isEligibleForReplyAdjustment(entry);
        boolean eligibleForActionAdjustment =
                mSettings.mGenerateActions && isEligibleForActionAdjustment(entry);

        ConversationActions conversationActionsResult =
                suggestConversationActions(
                        entry,
                        eligibleForReplyAdjustment,
                        eligibleForActionAdjustment);

        String resultId = conversationActionsResult.getId();
        List<ConversationAction> conversationActions =
                conversationActionsResult.getConversationActions();

        ArrayList<CharSequence> replies = new ArrayList<>();
        Map<CharSequence, Float> repliesScore = new ArrayMap<>();
        for (ConversationAction conversationAction : conversationActions) {
            CharSequence textReply = conversationAction.getTextReply();
            if (TextUtils.isEmpty(textReply)) {
                continue;
            }
            replies.add(textReply);
            repliesScore.put(textReply, conversationAction.getConfidenceScore());
        }

        ArrayList<Notification.Action> actions = new ArrayList<>();
        for (ConversationAction conversationAction : conversationActions) {
            if (!TextUtils.isEmpty(conversationAction.getTextReply())) {
                continue;
            }
            Notification.Action notificationAction;
            if (conversationAction.getAction() == null) {
                notificationAction =
                        createNotificationActionWithoutRemoteAction(conversationAction);
            } else {
                notificationAction = createNotificationActionFromRemoteAction(
                        conversationAction.getAction(),
                        conversationAction.getType(),
                        conversationAction.getConfidenceScore());
            }
            if (notificationAction != null) {
                actions.add(notificationAction);
            }
        }

        // Start a new session for logging if necessary.
        if (!TextUtils.isEmpty(resultId)
                && !conversationActions.isEmpty()
                && suggestionsMightBeUsedInNotification(
                entry, !actions.isEmpty(), !replies.isEmpty())) {
            mSessionCache.put(entry.getSbn().getKey(), new Session(resultId, repliesScore));
        }

        return new SmartSuggestions(replies, actions);
    }

    /**
     * Creates notification action from ConversationAction that does not come up a RemoteAction.
     * It could happen because we don't have common intents for some actions, like copying text.
     */
    @Nullable
    private Notification.Action createNotificationActionWithoutRemoteAction(
            ConversationAction conversationAction) {
        if (ConversationAction.TYPE_COPY.equals(conversationAction.getType())) {
            return createCopyCodeAction(conversationAction);
        }
        return null;
    }

    @Nullable
    private Notification.Action createCopyCodeAction(ConversationAction conversationAction) {
        Bundle extras = conversationAction.getExtras();
        if (extras == null) {
            return null;
        }
        Bundle entitiesExtas = extras.getParcelable(ENTITIES_EXTRAS);
        if (entitiesExtas == null) {
            return null;
        }
        String code = entitiesExtas.getString(KEY_TEXT);
        if (TextUtils.isEmpty(code)) {
            return null;
        }
        String contentDescription = mContext.getString(R.string.copy_code_desc, code);
        Intent intent = new Intent(mContext, CopyCodeActivity.class);
        intent.putExtra(Intent.EXTRA_TEXT, code);

        RemoteAction remoteAction = new RemoteAction(Icon.createWithResource(
                mContext.getResources(),
                com.android.internal.R.drawable.ic_menu_copy_material),
                code,
                contentDescription,
                PendingIntent.getActivity(
                        mContext,
                        code.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                ));

        return createNotificationActionFromRemoteAction(
                remoteAction,
                ConversationAction.TYPE_COPY,
                conversationAction.getConfidenceScore());
    }

    /**
     * Returns whether the suggestion might be used in the notifications in SysUI.
     * <p>
     * Currently, NAS has no idea if suggestions will actually be used in the notification, and thus
     * this function tries to make a heuristic. This function tries to optimize the precision,
     * that means when it is unsure, it will return false. The objective is to avoid false positive,
     * which could pollute the log and CTR as we are logging click rate of suggestions that could
     * be never visible to users. On the other hand, it is fine to have false negative because
     * it would be just like sampling.
     */
    private boolean suggestionsMightBeUsedInNotification(
            NotificationEntry notificationEntry, boolean hasSmartAction, boolean hasSmartReply) {
        Notification notification = notificationEntry.getNotification();
        boolean hasAppGeneratedContextualActions = !notification.getContextualActions().isEmpty();

        Pair<RemoteInput, Notification.Action> freeformRemoteInputAndAction =
                notification.findRemoteInputActionPair(/* requiresFreeform */ true);
        boolean hasAppGeneratedReplies = false;
        boolean allowGeneratedReplies = false;
        if (freeformRemoteInputAndAction != null) {
            RemoteInput freeformRemoteInput = freeformRemoteInputAndAction.first;
            Notification.Action actionWithFreeformRemoteInput = freeformRemoteInputAndAction.second;
            hasAppGeneratedReplies = !ArrayUtils.isEmpty(freeformRemoteInput.getChoices());
            allowGeneratedReplies = actionWithFreeformRemoteInput.getAllowGeneratedReplies();
        }

        if (hasAppGeneratedReplies || hasAppGeneratedContextualActions) {
            return false;
        }
        return hasSmartAction && notification.getAllowSystemGeneratedContextualActions()
                || hasSmartReply && allowGeneratedReplies;
    }

    private void reportActionsGenerated(
            String resultId, List<ConversationAction> conversationActions) {
        if (TextUtils.isEmpty(resultId)) {
            return;
        }
        TextClassifierEvent textClassifierEvent =
                createTextClassifierEventBuilder(
                        TextClassifierEvent.TYPE_ACTIONS_GENERATED, resultId)
                        .setEntityTypes(conversationActions.stream()
                                .map(ConversationAction::getType)
                                .toArray(String[]::new))
                        .build();
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
    }

    /**
     * Adds action adjustments based on the notification contents.
     */
    private ConversationActions suggestConversationActions(
            NotificationEntry entry,
            boolean includeReplies,
            boolean includeActions) {
        if (!includeReplies && !includeActions) {
            return EMPTY_CONVERSATION_ACTIONS;
        }
        List<ConversationActions.Message> messages = extractMessages(entry.getNotification());
        if (messages.isEmpty()) {
            return EMPTY_CONVERSATION_ACTIONS;
        }
        // Do not generate smart actions if the last message is from the local user.
        ConversationActions.Message lastMessage = messages.get(messages.size() - 1);
        if (arePersonsEqual(
                ConversationActions.Message.PERSON_USER_SELF, lastMessage.getAuthor())) {
            return EMPTY_CONVERSATION_ACTIONS;
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
        ConversationActions conversationActions =
                mTextClassifier.suggestConversationActions(request);
        reportActionsGenerated(
                conversationActions.getId(), conversationActions.getConversationActions());
        return conversationActions;
    }

    void onNotificationExpansionChanged(NotificationEntry entry, boolean isUserAction,
            boolean isExpanded) {
        if (!isExpanded) {
            return;
        }
        Session session = mSessionCache.get(entry.getSbn().getKey());
        if (session == null) {
            return;
        }
        // Only report if this is the first time the user sees these suggestions.
        if (entry.isShowActionEventLogged()) {
            return;
        }
        entry.setShowActionEventLogged();
        TextClassifierEvent textClassifierEvent =
                createTextClassifierEventBuilder(
                        TextClassifierEvent.TYPE_ACTIONS_SHOWN, session.resultId)
                        .build();
        // TODO: If possible, report which replies / actions are actually seen by user.
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
    }

    void onNotificationDirectReplied(String key) {
        Session session = mSessionCache.get(key);
        if (session == null) {
            return;
        }
        TextClassifierEvent textClassifierEvent =
                createTextClassifierEventBuilder(
                        TextClassifierEvent.TYPE_MANUAL_REPLY, session.resultId)
                        .build();
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
    }

    void onSuggestedReplySent(String key, CharSequence reply,
            @NotificationAssistantService.Source int source) {
        if (source != NotificationAssistantService.SOURCE_FROM_ASSISTANT) {
            return;
        }
        Session session = mSessionCache.get(key);
        if (session == null) {
            return;
        }
        TextClassifierEvent textClassifierEvent =
                createTextClassifierEventBuilder(
                        TextClassifierEvent.TYPE_SMART_ACTION, session.resultId)
                        .setEntityTypes(ConversationAction.TYPE_TEXT_REPLY)
                        .setScore(session.repliesScores.getOrDefault(reply, 0f))
                        .build();
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
    }

    void onActionClicked(String key, Notification.Action action,
            @NotificationAssistantService.Source int source) {
        if (source != NotificationAssistantService.SOURCE_FROM_ASSISTANT) {
            return;
        }
        Session session = mSessionCache.get(key);
        if (session == null) {
            return;
        }
        String actionType = action.getExtras().getString(KEY_ACTION_TYPE);
        if (actionType == null) {
            return;
        }
        TextClassifierEvent textClassifierEvent =
                createTextClassifierEventBuilder(
                        TextClassifierEvent.TYPE_SMART_ACTION, session.resultId)
                        .setEntityTypes(actionType)
                        .build();
        mTextClassifier.onTextClassifierEvent(textClassifierEvent);
    }

    private Notification.Action createNotificationActionFromRemoteAction(
            RemoteAction remoteAction, String actionType, float score) {
        Icon icon = remoteAction.shouldShowIcon()
                ? remoteAction.getIcon()
                : Icon.createWithResource(mContext, com.android.internal.R.drawable.ic_action_open);
        Bundle extras = new Bundle();
        extras.putString(KEY_ACTION_TYPE, actionType);
        extras.putFloat(KEY_ACTION_SCORE, score);
        return new Notification.Action.Builder(
                icon,
                remoteAction.getTitle(),
                remoteAction.getActionIntent())
                .setContextual(true)
                .addExtras(extras)
                .build();
    }

    private TextClassifierEvent.Builder createTextClassifierEventBuilder(
            int eventType, String resultId) {
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
    private boolean isEligibleForActionAdjustment(NotificationEntry entry) {
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

    private boolean isEligibleForReplyAdjustment(NotificationEntry entry) {
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
    private List<ConversationActions.Message> extractMessages(Notification notification) {
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

    private static boolean arePersonsEqual(Person left, Person right) {
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

    private static class Session {
        public final String resultId;
        public final Map<CharSequence, Float> repliesScores;

        Session(String resultId, Map<CharSequence, Float> repliesScores) {
            this.resultId = resultId;
            this.repliesScores = repliesScores;
        }
    }
}
