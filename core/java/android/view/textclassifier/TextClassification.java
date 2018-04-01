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
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
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

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
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
 *           for (int i = 0; i < classification.getActions().size(); ++i) {
 *              RemoteAction action = classification.getActions().get(i);
 *              menu.add(Menu.NONE, i, 20, action.getTitle())
 *                 .setIcon(action.getIcon());
 *           }
 *           return true;
 *       }
 *
 *       public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
 *           classification.getActions().get(item.getItemId()).getActionIntent().send();
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

    private static final String LOG_TAG = "TextClassification";
    // TODO(toki): investigate a way to derive this based on device properties.
    private static final int MAX_LEGACY_ICON_SIZE = 192;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {IntentType.UNSUPPORTED, IntentType.ACTIVITY, IntentType.SERVICE})
    private @interface IntentType {
        int UNSUPPORTED = -1;
        int ACTIVITY = 0;
        int SERVICE = 1;
    }

    @NonNull private final String mText;
    @Nullable private final Drawable mLegacyIcon;
    @Nullable private final String mLegacyLabel;
    @Nullable private final Intent mLegacyIntent;
    @Nullable private final OnClickListener mLegacyOnClickListener;
    @NonNull private final List<RemoteAction> mActions;
    @NonNull private final EntityConfidence mEntityConfidence;
    @NonNull private final String mSignature;

    private TextClassification(
            @Nullable String text,
            @Nullable Drawable legacyIcon,
            @Nullable String legacyLabel,
            @Nullable Intent legacyIntent,
            @Nullable OnClickListener legacyOnClickListener,
            @NonNull List<RemoteAction> actions,
            @NonNull Map<String, Float> entityConfidence,
            @NonNull String signature) {
        mText = text;
        mLegacyIcon = legacyIcon;
        mLegacyLabel = legacyLabel;
        mLegacyIntent = legacyIntent;
        mLegacyOnClickListener = legacyOnClickListener;
        mActions = Collections.unmodifiableList(actions);
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
     * Returns a list of actions that may be performed on the text. The list is ordered based on
     * the likelihood that a user will use the action, with the most likely action appearing first.
     */
    public List<RemoteAction> getActions() {
        return mActions;
    }

    /**
     * Returns an icon that may be rendered on a widget used to act on the classified text.
     *
     * @deprecated Use {@link #getActions()} instead.
     */
    @Deprecated
    @Nullable
    public Drawable getIcon() {
        return mLegacyIcon;
    }

    /**
     * Returns a label that may be rendered on a widget used to act on the classified text.
     *
     * @deprecated Use {@link #getActions()} instead.
     */
    @Deprecated
    @Nullable
    public CharSequence getLabel() {
        return mLegacyLabel;
    }

    /**
     * Returns an intent that may be fired to act on the classified text.
     *
     * @deprecated Use {@link #getActions()} instead.
     */
    @Deprecated
    @Nullable
    public Intent getIntent() {
        return mLegacyIntent;
    }

    /**
     * Returns the OnClickListener that may be triggered to act on the classified text. This field
     * is not parcelable and will be null for all objects read from a parcel. Instead, call
     * Context#startActivity(Intent) with the result of #getSecondaryIntent(int). Note that this may
     * fail if the activity doesn't have permission to send the intent.
     *
     * @deprecated Use {@link #getActions()} instead.
     */
    @Nullable
    public OnClickListener getOnClickListener() {
        return mLegacyOnClickListener;
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
        return String.format(Locale.US,
                "TextClassification {text=%s, entities=%s, actions=%s, signature=%s}",
                mText, mEntityConfidence, mActions, mSignature);
    }

    /**
     * Creates an OnClickListener that triggers the specified PendingIntent.
     *
     * @hide
     */
    public static OnClickListener createIntentOnClickListener(@NonNull final PendingIntent intent) {
        Preconditions.checkNotNull(intent);
        return v -> {
            try {
                intent.send();
            } catch (PendingIntent.CanceledException e) {
                Log.e(LOG_TAG, "Error creating OnClickListener from PendingIntent", e);
            }
        };
    }

    /**
     * Creates a PendingIntent for the specified intent.
     * Returns null if the intent is not supported for the specified context.
     *
     * @throws IllegalArgumentException if context or intent is null
     * @hide
     */
    @Nullable
    public static PendingIntent createPendingIntent(
            @NonNull final Context context, @NonNull final Intent intent) {
        switch (getIntentType(intent, context)) {
            case IntentType.ACTIVITY:
                return PendingIntent.getActivity(context, 0, intent, 0);
            case IntentType.SERVICE:
                return PendingIntent.getService(context, 0, intent, 0);
            default:
                return null;
        }
    }

    /**
     * Triggers the specified intent.
     *
     * @throws IllegalArgumentException if context or intent is null
     * @hide
     */
    public static void fireIntent(@NonNull final Context context, @NonNull final Intent intent) {
        switch (getIntentType(intent, context)) {
            case IntentType.ACTIVITY:
                context.startActivity(intent);
                return;
            case IntentType.SERVICE:
                context.startService(intent);
                return;
            default:
                return;
        }
    }

    @IntentType
    private static int getIntentType(@NonNull Intent intent, @NonNull Context context) {
        Preconditions.checkArgument(context != null);
        Preconditions.checkArgument(intent != null);

        final ResolveInfo activityRI = context.getPackageManager().resolveActivity(intent, 0);
        if (activityRI != null) {
            if (context.getPackageName().equals(activityRI.activityInfo.packageName)) {
                return IntentType.ACTIVITY;
            }
            final boolean exported = activityRI.activityInfo.exported;
            if (exported && hasPermission(context, activityRI.activityInfo.permission)) {
                return IntentType.ACTIVITY;
            }
        }

        final ResolveInfo serviceRI = context.getPackageManager().resolveService(intent, 0);
        if (serviceRI != null) {
            if (context.getPackageName().equals(serviceRI.serviceInfo.packageName)) {
                return IntentType.SERVICE;
            }
            final boolean exported = serviceRI.serviceInfo.exported;
            if (exported && hasPermission(context, serviceRI.serviceInfo.permission)) {
                return IntentType.SERVICE;
            }
        }

        return IntentType.UNSUPPORTED;
    }

    private static boolean hasPermission(@NonNull Context context, @NonNull String permission) {
        return permission == null
                || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
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
     * Builder for building {@link TextClassification} objects.
     *
     * <p>e.g.
     *
     * <pre>{@code
     *   TextClassification classification = new TextClassification.Builder()
     *          .setText(classifiedText)
     *          .setEntityType(TextClassifier.TYPE_EMAIL, 0.9)
     *          .setEntityType(TextClassifier.TYPE_OTHER, 0.1)
     *          .addAction(remoteAction1)
     *          .addAction(remoteAction2)
     *          .build();
     * }</pre>
     */
    public static final class Builder {

        @NonNull private String mText;
        @NonNull private List<RemoteAction> mActions = new ArrayList<>();
        @NonNull private final Map<String, Float> mEntityConfidence = new ArrayMap<>();
        @Nullable Drawable mLegacyIcon;
        @Nullable String mLegacyLabel;
        @Nullable Intent mLegacyIntent;
        @Nullable OnClickListener mLegacyOnClickListener;
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
         * Adds an action that may be performed on the classified text. Actions should be added in
         * order of likelihood that the user will use them, with the most likely action being added
         * first.
         */
        public Builder addAction(@NonNull RemoteAction action) {
            Preconditions.checkArgument(action != null);
            mActions.add(action);
            return this;
        }

        /**
         * Sets the icon for the <i>primary</i> action that may be rendered on a widget used to act
         * on the classified text.
         *
         * @deprecated Use {@link #addAction(RemoteAction)} instead.
         */
        @Deprecated
        public Builder setIcon(@Nullable Drawable icon) {
            mLegacyIcon = icon;
            return this;
        }

        /**
         * Sets the label for the <i>primary</i> action that may be rendered on a widget used to
         * act on the classified text.
         *
         * @deprecated Use {@link #addAction(RemoteAction)} instead.
         */
        @Deprecated
        public Builder setLabel(@Nullable String label) {
            mLegacyLabel = label;
            return this;
        }

        /**
         * Sets the intent for the <i>primary</i> action that may be fired to act on the classified
         * text.
         *
         * @deprecated Use {@link #addAction(RemoteAction)} instead.
         */
        @Deprecated
        public Builder setIntent(@Nullable Intent intent) {
            mLegacyIntent = intent;
            return this;
        }

        /**
         * Sets the OnClickListener for the <i>primary</i> action that may be triggered to act on
         * the classified text. This field is not parcelable and will always be null when the
         * object is read from a parcel.
         *
         * @deprecated Use {@link #addAction(RemoteAction)} instead.
         */
        public Builder setOnClickListener(@Nullable OnClickListener onClickListener) {
            mLegacyOnClickListener = onClickListener;
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
            return new TextClassification(mText, mLegacyIcon, mLegacyLabel, mLegacyIntent,
                    mLegacyOnClickListener, mActions, mEntityConfidence, mSignature);
        }
    }

    /**
     * Optional input parameters for generating TextClassification.
     */
    public static final class Options implements Parcelable {

        private @Nullable LocaleList mDefaultLocales;
        private @Nullable ZonedDateTime mReferenceTime;

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
        public Options setReferenceTime(ZonedDateTime referenceTime) {
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
        public ZonedDateTime getReferenceTime() {
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
                dest.writeString(mReferenceTime.toString());
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
                mReferenceTime = ZonedDateTime.parse(in.readString());
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
        final Bitmap legacyIconBitmap = drawableToBitmap(mLegacyIcon, MAX_LEGACY_ICON_SIZE);
        dest.writeInt(legacyIconBitmap != null ? 1 : 0);
        if (legacyIconBitmap != null) {
            legacyIconBitmap.writeToParcel(dest, flags);
        }
        dest.writeString(mLegacyLabel);
        dest.writeInt(mLegacyIntent != null ? 1 : 0);
        if (mLegacyIntent != null) {
            mLegacyIntent.writeToParcel(dest, flags);
        }
        // mOnClickListener is not parcelable.
        dest.writeTypedList(mActions);
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
        mLegacyIcon = in.readInt() == 0
                ? null
                : new BitmapDrawable(Resources.getSystem(), Bitmap.CREATOR.createFromParcel(in));
        mLegacyLabel = in.readString();
        if (in.readInt() == 0) {
            mLegacyIntent = null;
        } else {
            mLegacyIntent = Intent.CREATOR.createFromParcel(in);
            mLegacyIntent.removeFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        }
        mLegacyOnClickListener = null;  // not parcelable
        mActions = in.createTypedArrayList(RemoteAction.CREATOR);
        mEntityConfidence = EntityConfidence.CREATOR.createFromParcel(in);
        mSignature = in.readString();
    }
}
