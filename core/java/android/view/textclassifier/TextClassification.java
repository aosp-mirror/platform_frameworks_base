/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.FloatRange;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.view.View.OnClickListener;
import android.view.textclassifier.TextClassifier.EntityType;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * Information for generating a widget to handle classified text.
 */
public final class TextClassification {

    /**
     * @hide
     */
    static final TextClassification EMPTY = new TextClassification.Builder().build();

    @NonNull private final String mText;
    @NonNull private final List<Drawable> mIcons;
    @NonNull private final List<String> mLabels;
    @NonNull private final List<Intent> mIntents;
    @NonNull private final List<OnClickListener> mOnClickListeners;
    @NonNull private final EntityConfidence<String> mEntityConfidence;
    @NonNull private final List<String> mEntities;
    private int mLogType;
    @NonNull private final String mVersionInfo;

    private TextClassification(
            @Nullable String text,
            @NonNull List<Drawable> icons,
            @NonNull List<String> labels,
            @NonNull List<Intent> intents,
            @NonNull List<OnClickListener> onClickListeners,
            @NonNull EntityConfidence<String> entityConfidence,
            int logType,
            @NonNull String versionInfo) {
        Preconditions.checkArgument(labels.size() == intents.size());
        Preconditions.checkArgument(icons.size() == intents.size());
        Preconditions.checkArgument(onClickListeners.size() == intents.size());
        mText = text;
        mIcons = icons;
        mLabels = labels;
        mIntents = intents;
        mOnClickListeners = onClickListeners;
        mEntityConfidence = new EntityConfidence<>(entityConfidence);
        mEntities = mEntityConfidence.getEntities();
        mLogType = logType;
        mVersionInfo = versionInfo;
    }

    /**
     * Gets the classified text.
     */
    @Nullable
    public String getText() {
        return mText;
    }

    /**
     * Returns the number of entities found in the classified text.
     */
    @IntRange(from = 0)
    public int getEntityCount() {
        return mEntities.size();
    }

    /**
     * Returns the entity at the specified index. Entities are ordered from high confidence
     * to low confidence.
     *
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getEntityCount() for the number of entities available.
     */
    @NonNull
    public @EntityType String getEntity(int index) {
        return mEntities.get(index);
    }

    /**
     * Returns the confidence score for the specified entity. The value ranges from
     * 0 (low confidence) to 1 (high confidence). 0 indicates that the entity was not found for the
     * classified text.
     */
    @FloatRange(from = 0.0, to = 1.0)
    public float getConfidenceScore(@EntityType String entity) {
        return mEntityConfidence.getConfidenceScore(entity);
    }

    /**
     * Returns the number of actions that are available to act on the classified text.
     * @see #getIntent(int)
     * @see #getLabel(int)
     * @see #getIcon(int)
     * @see #getOnClickListener(int)
     */
    @IntRange(from = 0)
    public int getActionCount() {
        return mIntents.size();
    }

    /**
     * Returns one of the icons that maybe rendered on a widget used to act on the classified text.
     * @param index Index of the action to get the icon for.
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getActionCount() for the number of entities available.
     * @see #getIntent(int)
     * @see #getLabel(int)
     * @see #getOnClickListener(int)
     */
    @Nullable
    public Drawable getIcon(int index) {
        return mIcons.get(index);
    }

    /**
     * Returns an icon for the default intent that may be rendered on a widget used to act on the
     * classified text.
     */
    @Nullable
    public Drawable getIcon() {
        return mIcons.isEmpty() ? null : mIcons.get(0);
    }

    /**
     * Returns one of the labels that may be rendered on a widget used to act on the classified
     * text.
     * @param index Index of the action to get the label for.
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getActionCount()
     * @see #getIntent(int)
     * @see #getIcon(int)
     * @see #getOnClickListener(int)
     */
    @Nullable
    public CharSequence getLabel(int index) {
        return mLabels.get(index);
    }

    /**
     * Returns a label for the default intent that may be rendered on a widget used to act on the
     * classified text.
     */
    @Nullable
    public CharSequence getLabel() {
        return mLabels.isEmpty() ? null : mLabels.get(0);
    }

    /**
     * Returns one of the intents that may be fired to act on the classified text.
     * @param index Index of the action to get the intent for.
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getActionCount()
     * @see #getLabel(int)
     * @see #getIcon(int)
     * @see #getOnClickListener(int)
     */
    @Nullable
    public Intent getIntent(int index) {
        return mIntents.get(index);
    }

    /**
     * Returns the default intent that may be fired to act on the classified text.
     */
    @Nullable
    public Intent getIntent() {
        return mIntents.isEmpty() ? null : mIntents.get(0);
    }

    /**
     * Returns one of the OnClickListeners that may be triggered to act on the classified text.
     * @param index Index of the action to get the click listener for.
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getActionCount()
     * @see #getIntent(int)
     * @see #getLabel(int)
     * @see #getIcon(int)
     */
    @Nullable
    public OnClickListener getOnClickListener(int index) {
        return mOnClickListeners.get(index);
    }

    /**
     * Returns the default OnClickListener that may be triggered to act on the classified text.
     */
    @Nullable
    public OnClickListener getOnClickListener() {
        return mOnClickListeners.isEmpty() ? null : mOnClickListeners.get(0);
    }

    /**
     * Returns the MetricsLogger subtype for the action that is performed for this result.
     * @hide
     */
    public int getLogType() {
        return mLogType;
    }

    /**
     * Returns information about the classifier model used to generate this TextClassification.
     * @hide
     */
    @NonNull
    public String getVersionInfo() {
        return mVersionInfo;
    }

    @Override
    public String toString() {
        return String.format("TextClassification {"
                        + "text=%s, entities=%s, labels=%s, intents=%s}",
                mText, mEntityConfidence, mLabels, mIntents);
    }

    /**
     * Creates an OnClickListener that starts an activity with the specified intent.
     *
     * @throws IllegalArgumentException if context or intent is null
     * @hide
     */
    @NonNull
    public static OnClickListener createStartActivityOnClickListener(
            @NonNull final Context context, @NonNull final Intent intent) {
        Preconditions.checkArgument(context != null);
        Preconditions.checkArgument(intent != null);
        return v -> context.startActivity(intent);
    }

    /**
     * Builder for building {@link TextClassification} objects.
     */
    public static final class Builder {

        @NonNull private String mText;
        @NonNull private final List<Drawable> mIcons = new ArrayList<>();
        @NonNull private final List<String> mLabels = new ArrayList<>();
        @NonNull private final List<Intent> mIntents = new ArrayList<>();
        @NonNull private final List<OnClickListener> mOnClickListeners = new ArrayList<>();
        @NonNull private final EntityConfidence<String> mEntityConfidence =
                new EntityConfidence<>();
        private int mLogType;
        @NonNull private String mVersionInfo = "";

        /**
         * Sets the classified text.
         */
        public Builder setText(@Nullable String text) {
            mText = text;
            return this;
        }

        /**
         * Sets an entity type for the classification result and assigns a confidence score.
         *
         * @param confidenceScore a value from 0 (low confidence) to 1 (high confidence).
         *      0 implies the entity does not exist for the classified text.
         *      Values greater than 1 are clamped to 1.
         */
        public Builder setEntityType(
                @NonNull @EntityType String type,
                @FloatRange(from = 0.0, to = 1.0) float confidenceScore) {
            mEntityConfidence.setEntityType(type, confidenceScore);
            return this;
        }

        /**
         * Adds an action that may be performed on the classified text. The label and icon are used
         * for rendering of widgets that offer the intent. Actions should be added in order of
         * priority and the first one will be treated as the default.
         */
        public Builder addAction(
                Intent intent, @Nullable String label, @Nullable Drawable icon,
                @Nullable OnClickListener onClickListener) {
            mIntents.add(intent);
            mLabels.add(label);
            mIcons.add(icon);
            mOnClickListeners.add(onClickListener);
            return this;
        }

        /**
         * Removes all actions.
         */
        public Builder clearActions() {
            mIntents.clear();
            mOnClickListeners.clear();
            mLabels.clear();
            mIcons.clear();
            return this;
        }

        /**
         * Sets the icon for the default action that may be rendered on a widget used to act on the
         * classified text.
         */
        public Builder setIcon(@Nullable Drawable icon) {
            ensureDefaultActionAvailable();
            mIcons.set(0, icon);
            return this;
        }

        /**
         * Sets the label for the default action that may be rendered on a widget used to act on the
         * classified text.
         */
        public Builder setLabel(@Nullable String label) {
            ensureDefaultActionAvailable();
            mLabels.set(0, label);
            return this;
        }

        /**
         * Sets the intent for the default action that may be fired to act on the classified text.
         */
        public Builder setIntent(@Nullable Intent intent) {
            ensureDefaultActionAvailable();
            mIntents.set(0, intent);
            return this;
        }

        /**
         * Sets the MetricsLogger subtype for the action that is performed for this result.
         * @hide
         */
        public Builder setLogType(int type) {
            mLogType = type;
            return this;
        }

        /**
         * Sets the OnClickListener for the default action that may be triggered to act on the
         * classified text.
         */
        public Builder setOnClickListener(@Nullable OnClickListener onClickListener) {
            ensureDefaultActionAvailable();
            mOnClickListeners.set(0, onClickListener);
            return this;
        }

        /**
         * Sets information about the classifier model used to generate this TextClassification.
         * @hide
         */
        Builder setVersionInfo(@NonNull String versionInfo) {
            mVersionInfo = Preconditions.checkNotNull(versionInfo);
            return this;
        }

        /**
         * Ensures that we have at we have storage for the default action.
         */
        private void ensureDefaultActionAvailable() {
            if (mIntents.isEmpty()) mIntents.add(null);
            if (mLabels.isEmpty()) mLabels.add(null);
            if (mIcons.isEmpty()) mIcons.add(null);
            if (mOnClickListeners.isEmpty()) mOnClickListeners.add(null);
        }

        /**
         * Builds and returns a {@link TextClassification} object.
         */
        public TextClassification build() {
            return new TextClassification(
                    mText, mIcons, mLabels, mIntents, mOnClickListeners, mEntityConfidence,
                    mLogType, mVersionInfo);
        }
    }
}
