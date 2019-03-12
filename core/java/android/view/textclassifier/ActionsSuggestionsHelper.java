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

import android.annotation.Nullable;
import android.app.Person;
import android.app.RemoteAction;
import android.content.Context;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;

import com.google.android.textclassifier.ActionsSuggestionsModel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Helper class for action suggestions.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class ActionsSuggestionsHelper {
    private static final int USER_LOCAL = 0;
    private static final int FIRST_NON_LOCAL_USER = 1;

    private ActionsSuggestionsHelper() {}

    /**
     * Converts the messages to a list of native messages object that the model can understand.
     * <p>
     * User id encoding - local user is represented as 0, Other users are numbered according to
     * how far before they spoke last time in the conversation. For example, considering this
     * conversation:
     * <ul>
     * <li> User A: xxx
     * <li> Local user: yyy
     * <li> User B: zzz
     * </ul>
     * User A will be encoded as 2, user B will be encoded as 1 and local user will be encoded as 0.
     */
    public static ActionsSuggestionsModel.ConversationMessage[] toNativeMessages(
            List<ConversationActions.Message> messages,
            Function<CharSequence, String> languageDetector) {
        List<ConversationActions.Message> messagesWithText =
                messages.stream()
                        .filter(message -> !TextUtils.isEmpty(message.getText()))
                        .collect(Collectors.toCollection(ArrayList::new));
        if (messagesWithText.isEmpty()) {
            return new ActionsSuggestionsModel.ConversationMessage[0];
        }
        Deque<ActionsSuggestionsModel.ConversationMessage> nativeMessages = new ArrayDeque<>();
        PersonEncoder personEncoder = new PersonEncoder();
        int size = messagesWithText.size();
        for (int i = size - 1; i >= 0; i--) {
            ConversationActions.Message message = messagesWithText.get(i);
            long referenceTime = message.getReferenceTime() == null
                    ? 0
                    : message.getReferenceTime().toInstant().toEpochMilli();
            nativeMessages.push(new ActionsSuggestionsModel.ConversationMessage(
                    personEncoder.encode(message.getAuthor()),
                    message.getText().toString(), referenceTime,
                    languageDetector.apply(message.getText())));
        }
        return nativeMessages.toArray(
                new ActionsSuggestionsModel.ConversationMessage[nativeMessages.size()]);
    }

    /**
     * Returns the result id for logging.
     */
    public static String createResultId(
            Context context,
            List<ConversationActions.Message> messages,
            int modelVersion,
            List<Locale> modelLocales) {
        final StringJoiner localesJoiner = new StringJoiner(",");
        for (Locale locale : modelLocales) {
            localesJoiner.add(locale.toLanguageTag());
        }
        final String modelName = String.format(
                Locale.US, "%s_v%d", localesJoiner.toString(), modelVersion);
        final int hash = Objects.hash(
                messages.stream().mapToInt(ActionsSuggestionsHelper::hashMessage),
                context.getPackageName(),
                System.currentTimeMillis());
        return SelectionSessionLogger.SignatureParser.createSignature(
                SelectionSessionLogger.CLASSIFIER_ID, modelName, hash);
    }

    /**
     * Returns a {@link android.view.textclassifier.LabeledIntent.TitleChooser} for
     * conversation actions use case.
     */
    @Nullable
    public static LabeledIntent.TitleChooser createTitleChooser(String actionType) {
        if (ConversationAction.TYPE_OPEN_URL.equals(actionType)) {
            return (labeledIntent, resolveInfo) -> {
                if (resolveInfo.handleAllWebDataURI) {
                    return labeledIntent.titleWithEntity;
                }
                if ("android".equals(resolveInfo.activityInfo.packageName)) {
                    return labeledIntent.titleWithEntity;
                }
                return labeledIntent.titleWithoutEntity;
            };
        }
        return null;
    }

    /**
     * Returns a list of {@link ConversationAction}s that have 0 duplicates. Two actions are
     * duplicates if they may look the same to users. This function assumes every
     * ConversationActions with a non-null RemoteAction also have a non-null intent in the extras.
     */
    public static List<ConversationAction> removeActionsWithDuplicates(
            List<ConversationAction> conversationActions) {
        // Ideally, we should compare title and icon here, but comparing icon is expensive and thus
        // we use the component name of the target handler as the heuristic.
        Map<Pair<String, String>, Integer> counter = new ArrayMap<>();
        for (ConversationAction conversationAction : conversationActions) {
            Pair<String, String> representation = getRepresentation(conversationAction);
            if (representation == null) {
                continue;
            }
            Integer existingCount = counter.getOrDefault(representation, 0);
            counter.put(representation, existingCount + 1);
        }
        List<ConversationAction> result = new ArrayList<>();
        for (ConversationAction conversationAction : conversationActions) {
            Pair<String, String> representation = getRepresentation(conversationAction);
            if (representation == null || counter.getOrDefault(representation, 0) == 1) {
                result.add(conversationAction);
            }
        }
        return result;
    }

    @Nullable
    private static Pair<String, String> getRepresentation(
            ConversationAction conversationAction) {
        RemoteAction remoteAction = conversationAction.getAction();
        if (remoteAction == null) {
            return null;
        }
        return new Pair<>(
                conversationAction.getAction().getTitle().toString(),
                ExtrasUtils.getActionIntent(
                        conversationAction.getExtras()).getComponent().getPackageName());
    }

    private static final class PersonEncoder {
        private final Map<Person, Integer> mMapping = new ArrayMap<>();
        private int mNextUserId = FIRST_NON_LOCAL_USER;

        private int encode(Person person) {
            if (ConversationActions.Message.PERSON_USER_SELF.equals(person)) {
                return USER_LOCAL;
            }
            Integer result = mMapping.get(person);
            if (result == null) {
                mMapping.put(person, mNextUserId);
                result = mNextUserId;
                mNextUserId++;
            }
            return result;
        }
    }

    private static int hashMessage(ConversationActions.Message message) {
        return Objects.hash(message.getAuthor(), message.getText(), message.getReferenceTime());
    }
}
