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
import android.os.LocaleList;
import android.util.ArrayMap;
import android.view.View.OnClickListener;
import android.view.textclassifier.TextClassifier.EntityType;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Information for generating a widget to handle classified text.
 *
 * <p>A TextClassification object contains icons, labels, onClickListeners and intents that may
 * be used to build a widget that can be used to act on classified text. There is the concept of a
 * <i>primary action</i> and other <i>secondary actions</i>.
 *
 * <p>e.g. building a view that, when clicked, shares the classified text with the preferred app:
 *
 * <pre>{@code
 *   // Called preferably outside the UiThread.
 *   TextClassification classification = textClassifier.classifyText(allText, 10, 25);
 *
 *   // Called on the UiThread.
 *   Button button = new Button(context);
 *   button.setCompoundDrawablesWithIntrinsicBounds(classification.getIcon(), null, null, null);
 *   button.setText(classification.getLabel());
 *   button.setOnClickListener(classification.getOnClickListener());
 * }</pre>
 *
 * <p>e.g. starting an action mode with menu items that can handle the classified text:
 *
 * <pre>{@code
 *   // Called preferably outside the UiThread.
 *   final TextClassification classification = textClassifier.classifyText(allText, 10, 25);
 *
 *   // Called on the UiThread.
 *   view.startActionMode(new ActionMode.Callback() {
 *
 *       public boolean onCreateActionMode(ActionMode mode, Menu menu) {
 *           // Add the "primary" action.
 *           if (thisAppHasPermissionToInvokeIntent(classification.getIntent())) {
 *              menu.add(Menu.NONE, 0, 20, classification.getLabel())
 *                 .setIcon(classification.getIcon())
 *                 .setIntent(classification.getIntent());
 *           }
 *           // Add the "secondary" actions.
 *           for (int i = 0; i < classification.getSecondaryActionsCount(); i++) {
 *               if (thisAppHasPermissionToInvokeIntent(classification.getSecondaryIntent(i))) {
 *                   menu.add(Menu.NONE, i + 1, 20, classification.getSecondaryLabel(i))
 *                      .setIcon(classification.getSecondaryIcon(i))
 *                      .setIntent(classification.getSecondaryIntent(i));
 *               }
 *           }
 *           return true;
 *       }
 *
 *       public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
 *           context.startActivity(item.getIntent());
 *           return true;
 *       }
 *
 *       ...
 *   });
 * }</pre>
 *
 */
public final class TextClassification {

    /**
     * @hide
     */
    static final TextClassification EMPTY = new TextClassification.Builder().build();

    @NonNull private final String mText;
    @Nullable private final Drawable mPrimaryIcon;
    @Nullable private final String mPrimaryLabel;
    @Nullable private final Intent mPrimaryIntent;
    @Nullable private final OnClickListener mPrimaryOnClickListener;
    @NonNull private final List<Drawable> mSecondaryIcons;
    @NonNull private final List<String> mSecondaryLabels;
    @NonNull private final List<Intent> mSecondaryIntents;
    @NonNull private final List<OnClickListener> mSecondaryOnClickListeners;
    @NonNull private final EntityConfidence<String> mEntityConfidence;
    @NonNull private final String mSignature;

    private TextClassification(
            @Nullable String text,
            @Nullable Drawable primaryIcon,
            @Nullable String primaryLabel,
            @Nullable Intent primaryIntent,
            @Nullable OnClickListener primaryOnClickListener,
            @NonNull List<Drawable> secondaryIcons,
            @NonNull List<String> secondaryLabels,
            @NonNull List<Intent> secondaryIntents,
            @NonNull List<OnClickListener> secondaryOnClickListeners,
            @NonNull Map<String, Float> entityConfidence,
            @NonNull String signature) {
        Preconditions.checkArgument(secondaryLabels.size() == secondaryIntents.size());
        Preconditions.checkArgument(secondaryIcons.size() == secondaryIntents.size());
        Preconditions.checkArgument(secondaryOnClickListeners.size() == secondaryIntents.size());
        mText = text;
        mPrimaryIcon = primaryIcon;
        mPrimaryLabel = primaryLabel;
        mPrimaryIntent = primaryIntent;
        mPrimaryOnClickListener = primaryOnClickListener;
        mSecondaryIcons = secondaryIcons;
        mSecondaryLabels = secondaryLabels;
        mSecondaryIntents = secondaryIntents;
        mSecondaryOnClickListeners = secondaryOnClickListeners;
        mEntityConfidence = new EntityConfidence<>(entityConfidence);
        mSignature = signature;
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
        return mEntityConfidence.getEntities().size();
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
        return mEntityConfidence.getEntities().get(index);
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
     * Returns the number of <i>secondary</i> actions that are available to act on the classified
     * text.
     *
     * <p><strong>Note: </strong> that there may or may not be a <i>primary</i> action.
     *
     * @see #getSecondaryIntent(int)
     * @see #getSecondaryLabel(int)
     * @see #getSecondaryIcon(int)
     * @see #getSecondaryOnClickListener(int)
     */
    @IntRange(from = 0)
    public int getSecondaryActionsCount() {
        return mSecondaryIntents.size();
    }

    /**
     * Returns one of the <i>secondary</i> icons that maybe rendered on a widget used to act on the
     * classified text.
     *
     * @param index Index of the action to get the icon for.
     *
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     *
     * @see #getSecondaryActionsCount() for the number of actions available.
     * @see #getSecondaryIntent(int)
     * @see #getSecondaryLabel(int)
     * @see #getSecondaryOnClickListener(int)
     * @see #getIcon()
     */
    @Nullable
    public Drawable getSecondaryIcon(int index) {
        return mSecondaryIcons.get(index);
    }

    /**
     * Returns an icon for the <i>primary</i> intent that may be rendered on a widget used to act
     * on the classified text.
     *
     * @see #getSecondaryIcon(int)
     */
    @Nullable
    public Drawable getIcon() {
        return mPrimaryIcon;
    }

    /**
     * Returns one of the <i>secondary</i> labels that may be rendered on a widget used to act on
     * the classified text.
     *
     * @param index Index of the action to get the label for.
     *
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     *
     * @see #getSecondaryActionsCount()
     * @see #getSecondaryIntent(int)
     * @see #getSecondaryIcon(int)
     * @see #getSecondaryOnClickListener(int)
     * @see #getLabel()
     */
    @Nullable
    public CharSequence getSecondaryLabel(int index) {
        return mSecondaryLabels.get(index);
    }

    /**
     * Returns a label for the <i>primary</i> intent that may be rendered on a widget used to act
     * on the classified text.
     *
     * @see #getSecondaryLabel(int)
     */
    @Nullable
    public CharSequence getLabel() {
        return mPrimaryLabel;
    }

    /**
     * Returns one of the <i>secondary</i> intents that may be fired to act on the classified text.
     *
     * @param index Index of the action to get the intent for.
     *
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     *
     * @see #getSecondaryActionsCount()
     * @see #getSecondaryLabel(int)
     * @see #getSecondaryIcon(int)
     * @see #getSecondaryOnClickListener(int)
     * @see #getIntent()
     */
    @Nullable
    public Intent getSecondaryIntent(int index) {
        return mSecondaryIntents.get(index);
    }

    /**
     * Returns the <i>primary</i> intent that may be fired to act on the classified text.
     *
     * @see #getSecondaryIntent(int)
     */
    @Nullable
    public Intent getIntent() {
        return mPrimaryIntent;
    }

    /**
     * Returns one of the <i>secondary</i> OnClickListeners that may be triggered to act on the
     * classified text.
     *
     * @param index Index of the action to get the click listener for.
     *
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     *
     * @see #getSecondaryActionsCount()
     * @see #getSecondaryIntent(int)
     * @see #getSecondaryLabel(int)
     * @see #getSecondaryIcon(int)
     * @see #getOnClickListener()
     */
    @Nullable
    public OnClickListener getSecondaryOnClickListener(int index) {
        return mSecondaryOnClickListeners.get(index);
    }

    /**
     * Returns the <i>primary</i> OnClickListener that may be triggered to act on the classified
     * text.
     *
     * @see #getSecondaryOnClickListener(int)
     */
    @Nullable
    public OnClickListener getOnClickListener() {
        return mPrimaryOnClickListener;
    }

    /**
     * Returns the signature for this object.
     * The TextClassifier that generates this object may use it as a way to internally identify
     * this object.
     */
    @NonNull
    public String getSignature() {
        return mSignature;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "TextClassification {"
                        + "text=%s, entities=%s, "
                        + "primaryLabel=%s, secondaryLabels=%s, "
                        + "primaryIntent=%s, secondaryIntents=%s, "
                        + "signature=%s}",
                mText, mEntityConfidence,
                mPrimaryLabel, mSecondaryLabels,
                mPrimaryIntent, mSecondaryIntents,
                mSignature);
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
     *
     * <p>e.g.
     *
     * <pre>{@code
     *   TextClassification classification = new TextClassification.Builder()
     *          .setText(classifiedText)
     *          .setEntityType(TextClassifier.TYPE_EMAIL, 0.9)
     *          .setEntityType(TextClassifier.TYPE_OTHER, 0.1)
     *          .setPrimaryAction(intent, label, icon, onClickListener)
     *          .addSecondaryAction(intent1, label1, icon1, onClickListener1)
     *          .addSecondaryAction(intent2, label2, icon2, onClickListener2)
     *          .build();
     * }</pre>
     */
    public static final class Builder {

        @NonNull private String mText;
        @NonNull private final List<Drawable> mSecondaryIcons = new ArrayList<>();
        @NonNull private final List<String> mSecondaryLabels = new ArrayList<>();
        @NonNull private final List<Intent> mSecondaryIntents = new ArrayList<>();
        @NonNull private final List<OnClickListener> mSecondaryOnClickListeners = new ArrayList<>();
        @NonNull private final Map<String, Float> mEntityConfidence = new ArrayMap<>();
        @Nullable Drawable mPrimaryIcon;
        @Nullable String mPrimaryLabel;
        @Nullable Intent mPrimaryIntent;
        @Nullable OnClickListener mPrimaryOnClickListener;
        @NonNull private String mSignature = "";

        /**
         * Sets the classified text.
         */
        public Builder setText(@Nullable String text) {
            mText = text;
            return this;
        }

        /**
         * Sets an entity type for the classification result and assigns a confidence score.
         * If a confidence score had already been set for the specified entity type, this will
         * override that score.
         *
         * @param confidenceScore a value from 0 (low confidence) to 1 (high confidence).
         *      0 implies the entity does not exist for the classified text.
         *      Values greater than 1 are clamped to 1.
         */
        public Builder setEntityType(
                @NonNull @EntityType String type,
                @FloatRange(from = 0.0, to = 1.0) float confidenceScore) {
            mEntityConfidence.put(type, confidenceScore);
            return this;
        }

        /**
         * Adds an <i>secondary</i> action that may be performed on the classified text.
         * Secondary actions are in addition to the <i>primary</i> action which may or may not
         * exist.
         *
         * <p>The label and icon are used for rendering of widgets that offer the intent.
         * Actions should be added in order of priority.
         *
         * <p><stong>Note: </stong> If all input parameters are set to null, this method will be a
         * no-op.
         *
         * @see #setPrimaryAction(Intent, String, Drawable, OnClickListener)
         */
        public Builder addSecondaryAction(
                @Nullable Intent intent, @Nullable String label, @Nullable Drawable icon,
                @Nullable OnClickListener onClickListener) {
            if (intent != null || label != null || icon != null || onClickListener != null) {
                mSecondaryIntents.add(intent);
                mSecondaryLabels.add(label);
                mSecondaryIcons.add(icon);
                mSecondaryOnClickListeners.add(onClickListener);
            }
            return this;
        }

        /**
         * Removes all the <i>secondary</i> actions.
         */
        public Builder clearSecondaryActions() {
            mSecondaryIntents.clear();
            mSecondaryOnClickListeners.clear();
            mSecondaryLabels.clear();
            mSecondaryIcons.clear();
            return this;
        }

        /**
         * Sets the <i>primary</i> action that may be performed on the classified text. This is
         * equivalent to calling {@code
         * setIntent(intent).setLabel(label).setIcon(icon).setOnClickListener(onClickListener)}.
         *
         * <p><strong>Note: </strong>If all input parameters are null, there will be no
         * <i>primary</i> action but there may still be <i>secondary</i> actions.
         *
         * @see #addSecondaryAction(Intent, String, Drawable, OnClickListener)
         */
        public Builder setPrimaryAction(
                @Nullable Intent intent, @Nullable String label, @Nullable Drawable icon,
                @Nullable OnClickListener onClickListener) {
            return setIntent(intent).setLabel(label).setIcon(icon)
                    .setOnClickListener(onClickListener);
        }

        /**
         * Sets the icon for the <i>primary</i> action that may be rendered on a widget used to act
         * on the classified text.
         *
         * @see #setPrimaryAction(Intent, String, Drawable, OnClickListener)
         */
        public Builder setIcon(@Nullable Drawable icon) {
            mPrimaryIcon = icon;
            return this;
        }

        /**
         * Sets the label for the <i>primary</i> action that may be rendered on a widget used to
         * act on the classified text.
         *
         * @see #setPrimaryAction(Intent, String, Drawable, OnClickListener)
         */
        public Builder setLabel(@Nullable String label) {
            mPrimaryLabel = label;
            return this;
        }

        /**
         * Sets the intent for the <i>primary</i> action that may be fired to act on the classified
         * text.
         *
         * @see #setPrimaryAction(Intent, String, Drawable, OnClickListener)
         */
        public Builder setIntent(@Nullable Intent intent) {
            mPrimaryIntent = intent;
            return this;
        }

        /**
         * Sets the OnClickListener for the <i>primary</i> action that may be triggered to act on
         * the classified text.
         *
         * @see #setPrimaryAction(Intent, String, Drawable, OnClickListener)
         */
        public Builder setOnClickListener(@Nullable OnClickListener onClickListener) {
            mPrimaryOnClickListener = onClickListener;
            return this;
        }

        /**
         * Sets a signature for the TextClassification object.
         * The TextClassifier that generates the TextClassification object may use it as a way to
         * internally identify the TextClassification object.
         */
        public Builder setSignature(@NonNull String signature) {
            mSignature = Preconditions.checkNotNull(signature);
            return this;
        }

        /**
         * Builds and returns a {@link TextClassification} object.
         */
        public TextClassification build() {
            return new TextClassification(
                    mText,
                    mPrimaryIcon, mPrimaryLabel,
                    mPrimaryIntent, mPrimaryOnClickListener,
                    mSecondaryIcons, mSecondaryLabels,
                    mSecondaryIntents, mSecondaryOnClickListeners,
                    mEntityConfidence, mSignature);
        }
    }

    /**
     * Optional input parameters for generating TextClassification.
     */
    public static final class Options {

        private LocaleList mDefaultLocales;

        /**
         * @param defaultLocales ordered list of locale preferences that may be used to disambiguate
         *      the provided text. If no locale preferences exist, set this to null or an empty
         *      locale list.
         */
        public Options setDefaultLocales(@Nullable LocaleList defaultLocales) {
            mDefaultLocales = defaultLocales;
            return this;
        }

        /**
         * @return ordered list of locale preferences that can be used to disambiguate
         *      the provided text.
         */
        @Nullable
        public LocaleList getDefaultLocales() {
            return mDefaultLocales;
        }
    }
}
