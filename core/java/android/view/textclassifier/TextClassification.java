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
    @Nullable private final Drawable mIcon;
    @Nullable private final String mLabel;
    @Nullable private final Intent mIntent;
    @Nullable private final OnClickListener mOnClickListener;
    @NonNull private final EntityConfidence<String> mEntityConfidence;
    @NonNull private final List<String> mEntities;
    private int mLogType;
    @NonNull private final String mVersionInfo;

    private TextClassification(
            @Nullable String text,
            @Nullable Drawable icon,
            @Nullable String label,
            @Nullable Intent intent,
            @Nullable OnClickListener onClickListener,
            @NonNull EntityConfidence<String> entityConfidence,
            int logType,
            @NonNull String versionInfo) {
        mText = text;
        mIcon = icon;
        mLabel = label;
        mIntent = intent;
        mOnClickListener = onClickListener;
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
     * Returns an icon that may be rendered on a widget used to act on the classified text.
     */
    @Nullable
    public Drawable getIcon() {
        return mIcon;
    }

    /**
     * Returns a label that may be rendered on a widget used to act on the classified text.
     */
    @Nullable
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Returns an intent that may be fired to act on the classified text.
     */
    @Nullable
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * Returns an OnClickListener that may be triggered to act on the classified text.
     */
    @Nullable
    public OnClickListener getOnClickListener() {
        return mOnClickListener;
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
                        + "text=%s, entities=%s, label=%s, intent=%s}",
                mText, mEntityConfidence, mLabel, mIntent);
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
        @Nullable private Drawable mIcon;
        @Nullable private String mLabel;
        @Nullable private Intent mIntent;
        @Nullable private OnClickListener mOnClickListener;
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
         * Sets an icon that may be rendered on a widget used to act on the classified text.
         */
        public Builder setIcon(@Nullable Drawable icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets a label that may be rendered on a widget used to act on the classified text.
         */
        public Builder setLabel(@Nullable String label) {
            mLabel = label;
            return this;
        }

        /**
         * Sets an intent that may be fired to act on the classified text.
         */
        public Builder setIntent(@Nullable Intent intent) {
            mIntent = intent;
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
         * Sets an OnClickListener that may be triggered to act on the classified text.
         */
        public Builder setOnClickListener(@Nullable OnClickListener onClickListener) {
            mOnClickListener = onClickListener;
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
         * Builds and returns a {@link TextClassification} object.
         */
        public TextClassification build() {
            return new TextClassification(
                    mText, mIcon, mLabel, mIntent, mOnClickListener, mEntityConfidence,
                    mLogType, mVersionInfo);
        }
    }
}
