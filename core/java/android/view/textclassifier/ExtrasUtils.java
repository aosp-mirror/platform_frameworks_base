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
public final class ExtrasUtils {

    private static final String ACTIONS_INTENTS = "actions-intents";
    private static final String FOREIGN_LANGUAGE = "foreign-language";
    private static final String ENTITY_TYPE = "entity-type";
    private static final String SCORE = "score";
    private static final String MODEL_VERSION = "model-version";
    private static final String MODEL_NAME = "model-name";

    private ExtrasUtils() {}

    /**
     * Bundles and returns foreign language detection information for TextClassifier responses.
     */
    static Bundle createForeignLanguageExtra(
            String language, float score, int modelVersion) {
        final Bundle bundle = new Bundle();
        bundle.putString(ENTITY_TYPE, language);
        bundle.putFloat(SCORE, score);
        bundle.putInt(MODEL_VERSION, modelVersion);
        bundle.putString(MODEL_NAME, "langId_v" + modelVersion);
        return bundle;
    }

    /**
     * Stores {@code extra} as foreign language information in TextClassifier response object's
     * extras {@code container}.
     */
    static void putForeignLanguageExtra(Bundle container, Bundle extra) {
        container.putParcelable(FOREIGN_LANGUAGE, extra);
    }

    /**
     * Returns foreign language detection information contained in the TextClassification object.
     * responses.
     */
    @Nullable
    public static Bundle getForeignLanguageExtra(TextClassification classification) {
        return classification.getExtras().getBundle(FOREIGN_LANGUAGE);
    }

    /**
     * Stores {@code actionIntents} information in TextClassifier response object's extras
     * {@code container}.
     */
    static void putActionsIntents(Bundle container, ArrayList<Intent> actionsIntents) {
        container.putParcelableArrayList(ACTIONS_INTENTS, actionsIntents);
    }

    /**
     * Returns {@code actionIntents} information contained in the TextClassification object.
     */
    @Nullable
    public static ArrayList<Intent> getActionsIntents(TextClassification classification) {
        return classification.getExtras().getParcelableArrayList(ACTIONS_INTENTS);
    }

    /**
     * Returns the first "translate" action found in the {@code classification} object.
     */
    @Nullable
    public static RemoteAction findTranslateAction(TextClassification classification) {
        final ArrayList<Intent> actionIntents = getActionsIntents(classification);
        if (actionIntents != null) {
            final int size = actionIntents.size();
            for (int i = 0; i < size; i++) {
                if (Intent.ACTION_TRANSLATE.equals(actionIntents.get(i).getAction())) {
                    return classification.getActions().get(i);
                }
            }
        }
        return null;
    }

    /**
     * Returns the entity type contained in the {@code extra}.
     */
    @Nullable
    public static String getEntityType(Bundle extra) {
        return extra.getString(ENTITY_TYPE);
    }

    /**
     * Returns the score contained in the {@code extra}.
     */
    @Nullable
    public static float getScore(Bundle extra) {
        return extra.getFloat(SCORE, -1);
    }

    /**
     * Returns the model name contained in the {@code extra}.
     */
    @Nullable
    public static String getModelName(Bundle extra) {
        return extra.getString(MODEL_NAME);
    }
}
