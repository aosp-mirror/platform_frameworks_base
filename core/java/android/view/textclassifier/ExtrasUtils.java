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
import android.icu.util.ULocale;
import android.os.Bundle;

import com.android.internal.util.ArrayUtils;

import com.google.android.textclassifier.AnnotatorModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for inserting and retrieving data in TextClassifier request/response extras.
 * @hide
 */
// TODO: Make this a TestApi for CTS testing.
public final class ExtrasUtils {

    // Keys for response objects.
    private static final String SERIALIZED_ENTITIES_DATA = "serialized-entities-data";
    private static final String ENTITIES_EXTRAS = "entities-extras";
    private static final String ACTION_INTENT = "action-intent";
    private static final String ACTIONS_INTENTS = "actions-intents";
    private static final String FOREIGN_LANGUAGE = "foreign-language";
    private static final String ENTITY_TYPE = "entity-type";
    private static final String SCORE = "score";
    private static final String MODEL_VERSION = "model-version";
    private static final String MODEL_NAME = "model-name";
    private static final String TEXT_LANGUAGES = "text-languages";
    private static final String ENTITIES = "entities";

    // Keys for request objects.
    private static final String IS_SERIALIZED_ENTITY_DATA_ENABLED =
            "is-serialized-entity-data-enabled";

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
     *
     * @see #getForeignLanguageExtra(TextClassification)
     */
    static void putForeignLanguageExtra(Bundle container, Bundle extra) {
        container.putParcelable(FOREIGN_LANGUAGE, extra);
    }

    /**
     * Returns foreign language detection information contained in the TextClassification object.
     * responses.
     *
     * @see #putForeignLanguageExtra(Bundle, Bundle)
     */
    @Nullable
    public static Bundle getForeignLanguageExtra(@Nullable TextClassification classification) {
        if (classification == null) {
            return null;
        }
        return classification.getExtras().getBundle(FOREIGN_LANGUAGE);
    }

    /**
     * @see #getTopLanguage(Intent)
     */
    static void putTopLanguageScores(Bundle container, EntityConfidence languageScores) {
        final int maxSize = Math.min(3, languageScores.getEntities().size());
        final String[] languages = languageScores.getEntities().subList(0, maxSize)
                .toArray(new String[0]);
        final float[] scores = new float[languages.length];
        for (int i = 0; i < languages.length; i++) {
            scores[i] = languageScores.getConfidenceScore(languages[i]);
        }
        container.putStringArray(ENTITY_TYPE, languages);
        container.putFloatArray(SCORE, scores);
    }

    /**
     * @see #putTopLanguageScores(Bundle, EntityConfidence)
     */
    @Nullable
    public static ULocale getTopLanguage(@Nullable Intent intent) {
        if (intent == null) {
            return null;
        }
        final Bundle tcBundle = intent.getBundleExtra(TextClassifier.EXTRA_FROM_TEXT_CLASSIFIER);
        if (tcBundle == null) {
            return null;
        }
        final Bundle textLanguagesExtra = tcBundle.getBundle(TEXT_LANGUAGES);
        if (textLanguagesExtra == null) {
            return null;
        }
        final String[] languages = textLanguagesExtra.getStringArray(ENTITY_TYPE);
        final float[] scores = textLanguagesExtra.getFloatArray(SCORE);
        if (languages == null || scores == null
                || languages.length == 0 || languages.length != scores.length) {
            return null;
        }
        int highestScoringIndex = 0;
        for (int i = 1; i < languages.length; i++) {
            if (scores[highestScoringIndex] < scores[i]) {
                highestScoringIndex = i;
            }
        }
        return ULocale.forLanguageTag(languages[highestScoringIndex]);
    }

    public static void putTextLanguagesExtra(Bundle container, Bundle extra) {
        container.putBundle(TEXT_LANGUAGES, extra);
    }

    /**
     * Stores {@code actionIntents} information in TextClassifier response object's extras
     * {@code container}.
     */
    static void putActionsIntents(Bundle container, ArrayList<Intent> actionsIntents) {
        container.putParcelableArrayList(ACTIONS_INTENTS, actionsIntents);
    }

    /**
     * Stores {@code actionIntents} information in TextClassifier response object's extras
     * {@code container}.
     */
    public static void putActionIntent(Bundle container, @Nullable Intent actionIntent) {
        container.putParcelable(ACTION_INTENT, actionIntent);
    }

    /**
     * Returns {@code actionIntent} information contained in a TextClassifier response object.
     */
    @Nullable
    public static Intent getActionIntent(Bundle container) {
        return container.getParcelable(ACTION_INTENT);
    }

    /**
     * Stores serialized entity data information in TextClassifier response object's extras
     * {@code container}.
     */
    public static void putSerializedEntityData(
            Bundle container, @Nullable byte[] serializedEntityData) {
        container.putByteArray(SERIALIZED_ENTITIES_DATA, serializedEntityData);
    }

    /**
     * Returns serialized entity data information contained in a TextClassifier response
     * object.
     */
    @Nullable
    public static byte[] getSerializedEntityData(Bundle container) {
        return container.getByteArray(SERIALIZED_ENTITIES_DATA);
    }

    /**
     * Stores {@code entities} information in TextClassifier response object's extras
     * {@code container}.
     *
     * @see {@link #getCopyText(Bundle)}
     */
    public static void putEntitiesExtras(Bundle container, @Nullable Bundle entitiesExtras) {
        container.putParcelable(ENTITIES_EXTRAS, entitiesExtras);
    }

    /**
     * Returns {@code entities} information contained in a TextClassifier response object.
     *
     * @see {@link #putEntitiesExtras(Bundle, Bundle)}
     */
    @Nullable
    public static String getCopyText(Bundle container) {
        Bundle entitiesExtras = container.getParcelable(ENTITIES_EXTRAS);
        if (entitiesExtras == null) {
            return null;
        }
        return entitiesExtras.getString("text");
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
    public static RemoteAction findAction(
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

    /**
     * Stores the entities from {@link AnnotatorModel.ClassificationResult} in {@code container}.
     */
    public static void putEntities(
            Bundle container,
            @Nullable AnnotatorModel.ClassificationResult[] classifications) {
        if (ArrayUtils.isEmpty(classifications)) {
            return;
        }
        ArrayList<Bundle> entitiesBundle = new ArrayList<>();
        for (AnnotatorModel.ClassificationResult classification : classifications) {
            if (classification == null) {
                continue;
            }
            Bundle entityBundle = new Bundle();
            entityBundle.putString(ENTITY_TYPE, classification.getCollection());
            entityBundle.putByteArray(
                    SERIALIZED_ENTITIES_DATA,
                    classification.getSerializedEntityData());
            entitiesBundle.add(entityBundle);
        }
        if (!entitiesBundle.isEmpty()) {
            container.putParcelableArrayList(ENTITIES, entitiesBundle);
        }
    }

    /**
     * Returns a list of entities contained in the {@code extra}.
     */
    @Nullable
    public static List<Bundle> getEntities(Bundle container) {
        return container.getParcelableArrayList(ENTITIES);
    }

    /**
     * Whether the annotator should populate serialized entity data into the result object.
     */
    public static boolean isSerializedEntityDataEnabled(TextLinks.Request request) {
        return request.getExtras().getBoolean(IS_SERIALIZED_ENTITY_DATA_ENABLED);
    }

    /**
     * To indicate whether the annotator should populate serialized entity data in the result
     * object.
     */
    public static void putIsSerializedEntityDataEnabled(Bundle bundle, boolean isEnabled) {
        bundle.putBoolean(IS_SERIALIZED_ENTITY_DATA_ENABLED, isEnabled);
    }
}
