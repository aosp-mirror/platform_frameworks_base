/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.app.RemoteAction;
import android.content.Intent;
import android.os.Bundle;

import java.util.ArrayList;

/**
 * Utility class for inserting and retrieving data in TextClassifier request/response extras.
 * @hide
 */
// TODO: Make this a TestApi for CTS testing.
public final class ExtrasUtils {

    // Keys for response objects.
    private static final String ACTION_INTENT = "action-intent";
    private static final String ACTIONS_INTENTS = "actions-intents";
    private static final String FOREIGN_LANGUAGE = "foreign-language";
    private static final String ENTITY_TYPE = "entity-type";
    private static final String SCORE = "score";
    private static final String MODEL_NAME = "model-name";

    private ExtrasUtils() {
    }

    /**
     * Returns foreign language detection information contained in the TextClassification object.
     * responses.
     */
    @Nullable
    public static Bundle getForeignLanguageExtra(@Nullable TextClassification classification) {
        if (classification == null) {
            return null;
        }
        return classification.getExtras().getBundle(FOREIGN_LANGUAGE);
    }

    /**
     * Returns {@code actionIntent} information contained in a TextClassifier response object.
     */
    @Nullable
    public static Intent getActionIntent(Bundle container) {
        return container.getParcelable(ACTION_INTENT);
    }

    /**
     * Returns {@code actionIntents} information contained in the TextClassification object.
     */
    @Nullable
    public static ArrayList<Intent> getActionsIntents(@Nullable TextClassification classification) {
        if (classification == null) {
            return null;
        }
        return classification.getExtras().getParcelableArrayList(ACTIONS_INTENTS);
    }

    /**
     * Returns the first action found in the {@code classification} object with an intent
     * action string, {@code intentAction}.
     */
    @Nullable
    private static RemoteAction findAction(
            @Nullable TextClassification classification, @Nullable String intentAction) {
        if (classification == null || intentAction == null) {
            return null;
        }
        final ArrayList<Intent> actionIntents = getActionsIntents(classification);
        if (actionIntents != null) {
            final int size = actionIntents.size();
            for (int i = 0; i < size; i++) {
                final Intent intent = actionIntents.get(i);
                if (intent != null && intentAction.equals(intent.getAction())) {
                    return classification.getActions().get(i);
                }
            }
        }
        return null;
    }

    /**
     * Returns the first "translate" action found in the {@code classification} object.
     */
    @Nullable
    public static RemoteAction findTranslateAction(@Nullable TextClassification classification) {
        return findAction(classification, Intent.ACTION_TRANSLATE);
    }

    /**
     * Returns the entity type contained in the {@code extra}.
     */
    @Nullable
    public static String getEntityType(@Nullable Bundle extra) {
        if (extra == null) {
            return null;
        }
        return extra.getString(ENTITY_TYPE);
    }

    /**
     * Returns the score contained in the {@code extra}.
     */
    @Nullable
    public static float getScore(Bundle extra) {
        final int defaultValue = -1;
        if (extra == null) {
            return defaultValue;
        }
        return extra.getFloat(SCORE, defaultValue);
    }

    /**
     * Returns the model name contained in the {@code extra}.
     */
    @Nullable
    public static String getModelName(@Nullable Bundle extra) {
        if (extra == null) {
            return null;
        }
        return extra.getString(MODEL_NAME);
    }
}