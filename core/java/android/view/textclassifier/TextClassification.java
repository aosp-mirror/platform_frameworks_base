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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.view.View.OnClickListener;
import android.view.textclassifier.TextClassifier.EntityType;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Calendar;
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
 *   button.setOnClickListener(v -> context.startActivity(classification.getIntent()));
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
 */
public final class TextClassification implements Parcelable {

    /**
     * @hide
     */
    static final TextClassification EMPTY = new TextClassification.Builder().build();

    // TODO(toki): investigate a way to derive this based on device properties.
    private static final int MAX_PRIMARY_ICON_SIZE = 192;
    private static final int MAX_SECONDARY_ICON_SIZE = 144;

    @NonNull private final String mText;
    @Nullable private final Drawable mPrimaryIcon;
    @Nullable private final String mPrimaryLabel;
    @Nullable private final Intent mPrimaryIntent;
    @Nullable private final OnClickListener mPrimaryOnClickListener;
    @NonNull private final List<Drawable> mSecondaryIcons;
    @NonNull private final List<String> mSecondaryLabels;
    @NonNull private final List<Intent> mSecondaryIntents;
    @NonNull private final EntityConfidence mEntityConfidence;
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
            @NonNull Map<String, Float> entityConfidence,
            @NonNull String signature) {
        Preconditions.checkArgument(secondaryLabels.size() == secondaryIntents.size());
        Preconditions.checkArgument(secondaryIcons.size() == secondaryIntents.size());
        mText = text;
        mPrimaryIcon = primaryIcon;
        mPrimaryLabel = primaryLabel;
        mPrimaryIntent = primaryIntent;
        mPrimaryOnClickListener = primaryOnClickListener;
        mSecondaryIcons = secondaryIcons;
        mSecondaryLabels = secondaryLabels;
        mSecondaryIntents = secondaryIntents;
        mEntityConfidence = new EntityConfidence(entityConfidence);
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
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getSecondaryActionsCount() for the number of actions available.
     * @see #getSecondaryIntent(int)
     * @see #getSecondaryLabel(int)
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
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getSecondaryActionsCount()
     * @see #getSecondaryIntent(int)
     * @see #getSecondaryIcon(int)
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
     * @throws IndexOutOfBoundsException if the specified index is out of range.
     * @see #getSecondaryActionsCount()
     * @see #getSecondaryLabel(int)
     * @see #getSecondaryIcon(int)
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
     * Returns the <i>primary</i> OnClickListener that may be triggered to act on the classified
     * text. This field is not parcelable and will be null for all objects read from a parcel.
     * Instead, call Context#startActivity(Intent) with the result of #getSecondaryIntent(int).
     * Note that this may fail if the activity doesn't have permission to send the intent.
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
     * Returns a Bitmap representation of the Drawable
     *
     * @param drawable The drawable to convert.
     * @param maxDims The maximum edge length of the resulting bitmap (in pixels).
     */
    @Nullable
    private static Bitmap drawableToBitmap(@Nullable Drawable drawable, int maxDims) {
        if (drawable == null) {
            return null;
        }
        final int actualWidth = Math.max(1, drawable.getIntrinsicWidth());
        final int actualHeight = Math.max(1, drawable.getIntrinsicHeight());
        final double scaleWidth = ((double) maxDims) / actualWidth;
        final double scaleHeight = ((double) maxDims) / actualHeight;
        final double scale = Math.min(1.0, Math.min(scaleWidth, scaleHeight));
        final int width = (int) (actualWidth * scale);
        final int height = (int) (actualHeight * scale);
        if (drawable instanceof BitmapDrawable) {
            final BitmapDrawable bitmapDrawable = (BitmapDrawable) drawable;
            if (actualWidth != width || actualHeight != height) {
                return Bitmap.createScaledBitmap(
                        bitmapDrawable.getBitmap(), width, height, /*filter=*/false);
            } else {
                return bitmapDrawable.getBitmap();
            }
        } else {
            final Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            drawable.draw(canvas);
            return bitmap;
        }
    }

    /**
     * Returns a list of drawables converted to Bitmaps
     *
     * @param drawables The drawables to convert.
     * @param maxDims The maximum edge length of the resulting bitmaps (in pixels).
     */
    private static List<Bitmap> drawablesToBitmaps(List<Drawable> drawables, int maxDims) {
        final List<Bitmap> bitmaps = new ArrayList<>(drawables.size());
        for (Drawable drawable : drawables) {
            bitmaps.add(drawableToBitmap(drawable, maxDims));
        }
        return bitmaps;
    }

    /** Returns a list of drawable wrappers for a list of bitmaps. */
    private static List<Drawable> bitmapsToDrawables(List<Bitmap> bitmaps) {
        final List<Drawable> drawables = new ArrayList<>(bitmaps.size());
        for (Bitmap bitmap : bitmaps) {
            if (bitmap != null) {
                drawables.add(new BitmapDrawable(Resources.getSystem(), bitmap));
            } else {
                drawables.add(null);
            }
        }
        return drawables;
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
     *          .setPrimaryAction(intent, label, icon)
     *          .addSecondaryAction(intent1, label1, icon1)
     *          .addSecondaryAction(intent2, label2, icon2)
     *          .build();
     * }</pre>
     */
    public static final class Builder {

        @NonNull private String mText;
        @NonNull private final List<Drawable> mSecondaryIcons = new ArrayList<>();
        @NonNull private final List<String> mSecondaryLabels = new ArrayList<>();
        @NonNull private final List<Intent> mSecondaryIntents = new ArrayList<>();
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
         * @see #setPrimaryAction(Intent, String, Drawable)
         */
        public Builder addSecondaryAction(
                @Nullable Intent intent, @Nullable String label, @Nullable Drawable icon) {
            if (intent != null || label != null || icon != null) {
                mSecondaryIntents.add(intent);
                mSecondaryLabels.add(label);
                mSecondaryIcons.add(icon);
            }
            return this;
        }

        /**
         * Removes all the <i>secondary</i> actions.
         */
        public Builder clearSecondaryActions() {
            mSecondaryIntents.clear();
            mSecondaryLabels.clear();
            mSecondaryIcons.clear();
            return this;
        }

        /**
         * Sets the <i>primary</i> action that may be performed on the classified text. This is
         * equivalent to calling {@code setIntent(intent).setLabel(label).setIcon(icon)}.
         *
         * <p><strong>Note: </strong>If all input parameters are null, there will be no
         * <i>primary</i> action but there may still be <i>secondary</i> actions.
         *
         * @see #addSecondaryAction(Intent, String, Drawable)
         */
        public Builder setPrimaryAction(
                @Nullable Intent intent, @Nullable String label, @Nullable Drawable icon) {
            return setIntent(intent).setLabel(label).setIcon(icon);
        }

        /**
         * Sets the icon for the <i>primary</i> action that may be rendered on a widget used to act
         * on the classified text.
         *
         * @see #setPrimaryAction(Intent, String, Drawable)
         */
        public Builder setIcon(@Nullable Drawable icon) {
            mPrimaryIcon = icon;
            return this;
        }

        /**
         * Sets the label for the <i>primary</i> action that may be rendered on a widget used to
         * act on the classified text.
         *
         * @see #setPrimaryAction(Intent, String, Drawable)
         */
        public Builder setLabel(@Nullable String label) {
            mPrimaryLabel = label;
            return this;
        }

        /**
         * Sets the intent for the <i>primary</i> action that may be fired to act on the classified
         * text.
         *
         * @see #setPrimaryAction(Intent, String, Drawable)
         */
        public Builder setIntent(@Nullable Intent intent) {
            mPrimaryIntent = intent;
            return this;
        }

        /**
         * Sets the OnClickListener for the <i>primary</i> action that may be triggered to act on
         * the classified text. This field is not parcelable and will always be null when the
         * object is read from a parcel.
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
                    mPrimaryIcon, mPrimaryLabel, mPrimaryIntent, mPrimaryOnClickListener,
                    mSecondaryIcons, mSecondaryLabels, mSecondaryIntents,
                    mEntityConfidence, mSignature);
        }
    }

    /**
     * Optional input parameters for generating TextClassification.
     */
    public static final class Options implements Parcelable {

        private @Nullable LocaleList mDefaultLocales;
        private @Nullable Calendar mReferenceTime;

        public Options() {}

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
         * @param referenceTime reference time based on which relative dates (e.g. "tomorrow" should
         *      be interpreted. This should usually be the time when the text was originally
         *      composed. If no reference time is set, now is used.
         */
        public Options setReferenceTime(Calendar referenceTime) {
            mReferenceTime = referenceTime;
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

        /**
         * @return reference time based on which relative dates (e.g. "tomorrow") should be
         *      interpreted.
         */
        @Nullable
        public Calendar getReferenceTime() {
            return mReferenceTime;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mDefaultLocales != null ? 1 : 0);
            if (mDefaultLocales != null) {
                mDefaultLocales.writeToParcel(dest, flags);
            }
            dest.writeInt(mReferenceTime != null ? 1 : 0);
            if (mReferenceTime != null) {
                dest.writeSerializable(mReferenceTime);
            }
        }

        public static final Parcelable.Creator<Options> CREATOR =
                new Parcelable.Creator<Options>() {
                    @Override
                    public Options createFromParcel(Parcel in) {
                        return new Options(in);
                    }

                    @Override
                    public Options[] newArray(int size) {
                        return new Options[size];
                    }
                };

        private Options(Parcel in) {
            if (in.readInt() > 0) {
                mDefaultLocales = LocaleList.CREATOR.createFromParcel(in);
            }
            if (in.readInt() > 0) {
                mReferenceTime = (Calendar) in.readSerializable();
            }
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mText);
        final Bitmap primaryIconBitmap = drawableToBitmap(mPrimaryIcon, MAX_PRIMARY_ICON_SIZE);
        dest.writeInt(primaryIconBitmap != null ? 1 : 0);
        if (primaryIconBitmap != null) {
            primaryIconBitmap.writeToParcel(dest, flags);
        }
        dest.writeString(mPrimaryLabel);
        dest.writeInt(mPrimaryIntent != null ? 1 : 0);
        if (mPrimaryIntent != null) {
            mPrimaryIntent.writeToParcel(dest, flags);
        }
        // mPrimaryOnClickListener is not parcelable.
        dest.writeTypedList(drawablesToBitmaps(mSecondaryIcons, MAX_SECONDARY_ICON_SIZE));
        dest.writeStringList(mSecondaryLabels);
        dest.writeTypedList(mSecondaryIntents);
        mEntityConfidence.writeToParcel(dest, flags);
        dest.writeString(mSignature);
    }

    public static final Parcelable.Creator<TextClassification> CREATOR =
            new Parcelable.Creator<TextClassification>() {
                @Override
                public TextClassification createFromParcel(Parcel in) {
                    return new TextClassification(in);
                }

                @Override
                public TextClassification[] newArray(int size) {
                    return new TextClassification[size];
                }
            };

    private TextClassification(Parcel in) {
        mText = in.readString();
        mPrimaryIcon = in.readInt() == 0
                ? null
                : new BitmapDrawable(Resources.getSystem(), Bitmap.CREATOR.createFromParcel(in));
        mPrimaryLabel = in.readString();
        mPrimaryIntent = in.readInt() == 0 ? null : Intent.CREATOR.createFromParcel(in);
        mPrimaryOnClickListener = null;  // not parcelable
        mSecondaryIcons = bitmapsToDrawables(in.createTypedArrayList(Bitmap.CREATOR));
        mSecondaryLabels = in.createStringArrayList();
        mSecondaryIntents = in.createTypedArrayList(Intent.CREATOR);
        mEntityConfidence = EntityConfidence.CREATOR.createFromParcel(in);
        mSignature = in.readString();
    }
}
