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

package android.service.autofill;

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import android.util.SparseArray;
import android.widget.RemoteViews;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * Defines a custom description for the autofill save UI.
 *
 * <p>This is useful when the autofill service needs to show a detailed view of what would be saved;
 * for example, when the screen contains a credit card, it could display a logo of the credit card
 * bank, the last four digits of the credit card number, and its expiration number.
 *
 * <p>A custom description is made of 2 parts:
 * <ul>
 *   <li>A {@link RemoteViews presentation template} containing children views.
 *   <li>{@link Transformation Transformations} to populate the children views.
 * </ul>
 *
 * <p>For the credit card example mentioned above, the (simplified) template would be:
 *
 * <pre class="prettyprint">
 * &lt;LinearLayout&gt;
 *   &lt;ImageView android:id="@+id/templateccLogo"/&gt;
 *   &lt;TextView android:id="@+id/templateCcNumber"/&gt;
 *   &lt;TextView android:id="@+id/templateExpDate"/&gt;
 * &lt;/LinearLayout&gt;
 * </pre>
 *
 * <p>Which in code translates to:
 *
 * <pre class="prettyprint">
 *   CustomDescription.Builder buider = new Builder(new RemoteViews(pgkName, R.layout.cc_template);
 * </pre>
 *
 * <p>Then the value of each of the 3 children would be changed at runtime based on the the value of
 * the screen fields and the {@link Transformation Transformations}:
 *
 * <pre class="prettyprint">
 * // Image child - different logo for each bank, based on credit card prefix
 * builder.addChild(R.id.templateccLogo,
 *   new ImageTransformation.Builder(ccNumberId)
 *     .addOption(Pattern.compile("^4815.*$"), R.drawable.ic_credit_card_logo1)
 *     .addOption(Pattern.compile("^1623.*$"), R.drawable.ic_credit_card_logo2)
 *     .addOption(Pattern.compile("^42.*$"), R.drawable.ic_credit_card_logo3)
 *     .build();
 * // Masked credit card number (as .....LAST_4_DIGITS)
 * builder.addChild(R.id.templateCcNumber, new CharSequenceTransformation
 *     .Builder(ccNumberId, Pattern.compile("^.*(\\d\\d\\d\\d)$"), "...$1")
 *     .build();
 * // Expiration date as MM / YYYY:
 * builder.addChild(R.id.templateExpDate, new CharSequenceTransformation
 *     .Builder(ccExpMonthId, Pattern.compile("^(\\d\\d)$"), "Exp: $1")
 *     .addField(ccExpYearId, Pattern.compile("^(\\d\\d)$"), "/$1")
 *     .build();
 * </pre>
 *
 * <p>See {@link ImageTransformation}, {@link CharSequenceTransformation} for more info about these
 * transformations.
 */
public final class CustomDescription implements Parcelable {

    private final RemoteViews mPresentation;
    private final ArrayList<Pair<Integer, InternalTransformation>> mTransformations;
    private final ArrayList<Pair<InternalValidator, BatchUpdates>> mUpdates;
    private final SparseArray<InternalOnClickAction> mActions;

    private CustomDescription(Builder builder) {
        mPresentation = builder.mPresentation;
        mTransformations = builder.mTransformations;
        mUpdates = builder.mUpdates;
        mActions = builder.mActions;
    }

    /** @hide */
    @Nullable
    public RemoteViews getPresentation() {
        return mPresentation;
    }

    /** @hide */
    @Nullable
    public ArrayList<Pair<Integer, InternalTransformation>> getTransformations() {
        return mTransformations;
    }

    /** @hide */
    @Nullable
    public ArrayList<Pair<InternalValidator, BatchUpdates>> getUpdates() {
        return mUpdates;
    }

    /** @hide */
    @Nullable
    @TestApi
    public SparseArray<InternalOnClickAction> getActions() {
        return mActions;
    }

    /**
     * Builder for {@link CustomDescription} objects.
     */
    public static class Builder {
        private final RemoteViews mPresentation;

        private boolean mDestroyed;
        private ArrayList<Pair<Integer, InternalTransformation>> mTransformations;
        private ArrayList<Pair<InternalValidator, BatchUpdates>> mUpdates;
        private SparseArray<InternalOnClickAction> mActions;

        /**
         * Default constructor.
         *
         * <p><b>Note:</b> If any child view of presentation triggers a
         * {@link RemoteViews#setOnClickPendingIntent(int, android.app.PendingIntent) pending intent
         * on click}, such {@link PendingIntent} must follow the restrictions below, otherwise
         * it might not be triggered or the autofill save UI might not be shown when its activity
         * is finished:
         * <ul>
         *   <li>It cannot be created with the {@link PendingIntent#FLAG_IMMUTABLE} flag.
         *   <li>It must be a PendingIntent for an {@link Activity}.
         *   <li>The activity must call {@link Activity#finish()} when done.
         *   <li>The activity should not launch other activities.
         * </ul>
         *
         * @param parentPresentation template presentation with (optional) children views.
         * @throws NullPointerException if {@code parentPresentation} is null (on Android
         * {@link android.os.Build.VERSION_CODES#P} or higher).
         */
        public Builder(@NonNull RemoteViews parentPresentation) {
            mPresentation = Preconditions.checkNotNull(parentPresentation);
        }

        /**
         * Adds a transformation to replace the value of a child view with the fields in the
         * screen.
         *
         * <p>When multiple transformations are added for the same child view, they will be applied
         * in the same order as added.
         *
         * @param id view id of the children view.
         * @param transformation an implementation provided by the Android System.
         *
         * @return this builder.
         *
         * @throws IllegalArgumentException if {@code transformation} is not a class provided
         * by the Android System.
         * @throws IllegalStateException if {@link #build()} was already called.
         */
        @NonNull
        public Builder addChild(int id, @NonNull Transformation transformation) {
            throwIfDestroyed();
            Preconditions.checkArgument((transformation instanceof InternalTransformation),
                    "not provided by Android System: %s", transformation);
            if (mTransformations == null) {
                mTransformations = new ArrayList<>();
            }
            mTransformations.add(new Pair<>(id, (InternalTransformation) transformation));
            return this;
        }

        /**
         * Updates the {@link RemoteViews presentation template} when a condition is satisfied by
         * applying a series of remote view operations. This allows dynamic customization of the
         * portion of the save UI that is controlled by the autofill service. Such dynamic
         * customization is based on the content of target views.
         *
         * <p>The updates are applied in the sequence they are added, after the
         * {@link #addChild(int, Transformation) transformations} are applied to the children
         * views.
         *
         * <p>For example, to make children views visible when fields are not empty:
         *
         * <pre class="prettyprint">
         * RemoteViews template = new RemoteViews(pgkName, R.layout.my_full_template);
         *
         * Pattern notEmptyPattern = Pattern.compile(".+");
         * Validator hasAddress = new RegexValidator(addressAutofillId, notEmptyPattern);
         * Validator hasCcNumber = new RegexValidator(ccNumberAutofillId, notEmptyPattern);
         *
         * RemoteViews addressUpdates = new RemoteViews(pgkName, R.layout.my_full_template)
         * addressUpdates.setViewVisibility(R.id.address, View.VISIBLE);
         *
         * // Make address visible
         * BatchUpdates addressBatchUpdates = new BatchUpdates.Builder()
         *     .updateTemplate(addressUpdates)
         *     .build();
         *
         * RemoteViews ccUpdates = new RemoteViews(pgkName, R.layout.my_full_template)
         * ccUpdates.setViewVisibility(R.id.cc_number, View.VISIBLE);
         *
         * // Mask credit card number (as .....LAST_4_DIGITS) and make it visible
         * BatchUpdates ccBatchUpdates = new BatchUpdates.Builder()
         *     .updateTemplate(ccUpdates)
         *     .transformChild(R.id.templateCcNumber, new CharSequenceTransformation
         *                     .Builder(ccNumberId, Pattern.compile("^.*(\\d\\d\\d\\d)$"), "...$1")
         *                     .build())
         *     .build();
         *
         * CustomDescription customDescription = new CustomDescription.Builder(template)
         *     .batchUpdate(hasAddress, addressBatchUpdates)
         *     .batchUpdate(hasCcNumber, ccBatchUpdates)
         *     .build();
         * </pre>
         *
         * <p>Another approach is to add a child first, then apply the transformations. Example:
         *
         * <pre class="prettyprint">
         * RemoteViews template = new RemoteViews(pgkName, R.layout.my_base_template);
         *
         * RemoteViews addressPresentation = new RemoteViews(pgkName, R.layout.address)
         * RemoteViews addressUpdates = new RemoteViews(pgkName, R.layout.my_template)
         * addressUpdates.addView(R.id.parentId, addressPresentation);
         * BatchUpdates addressBatchUpdates = new BatchUpdates.Builder()
         *     .updateTemplate(addressUpdates)
         *     .build();
         *
         * RemoteViews ccPresentation = new RemoteViews(pgkName, R.layout.cc)
         * RemoteViews ccUpdates = new RemoteViews(pgkName, R.layout.my_template)
         * ccUpdates.addView(R.id.parentId, ccPresentation);
         * BatchUpdates ccBatchUpdates = new BatchUpdates.Builder()
         *     .updateTemplate(ccUpdates)
         *     .transformChild(R.id.templateCcNumber, new CharSequenceTransformation
         *                     .Builder(ccNumberId, Pattern.compile("^.*(\\d\\d\\d\\d)$"), "...$1")
         *                     .build())
         *     .build();
         *
         * CustomDescription customDescription = new CustomDescription.Builder(template)
         *     .batchUpdate(hasAddress, addressBatchUpdates)
         *     .batchUpdate(hasCcNumber, ccBatchUpdates)
         *     .build();
         * </pre>
         *
         * @param condition condition used to trigger the updates.
         * @param updates actions to be applied to the
         * {@link #Builder(RemoteViews) template presentation} when the condition
         * is satisfied.
         *
         * @return this builder
         *
         * @throws IllegalArgumentException if {@code condition} is not a class provided
         * by the Android System.
         * @throws IllegalStateException if {@link #build()} was already called.
         */
        @NonNull
        public Builder batchUpdate(@NonNull Validator condition, @NonNull BatchUpdates updates) {
            throwIfDestroyed();
            Preconditions.checkArgument((condition instanceof InternalValidator),
                    "not provided by Android System: %s", condition);
            Preconditions.checkNotNull(updates);
            if (mUpdates == null) {
                mUpdates = new ArrayList<>();
            }
            mUpdates.add(new Pair<>((InternalValidator) condition, updates));
            return this;
        }

        /**
         * Sets an action to be applied to the {@link RemoteViews presentation template} when the
         * child view with the given {@code id} is clicked.
         *
         * <p>Typically used when the presentation uses a masked field (like {@code ****}) for
         * sensitive fields like passwords or credit cards numbers, but offers a an icon that the
         * user can tap to show the value for that field.
         *
         * <p>Example:
         *
         * <pre class="prettyprint">
         * customDescriptionBuilder
         *   .addChild(R.id.password_plain, new CharSequenceTransformation
         *      .Builder(passwordId, Pattern.compile("^(.*)$"), "$1").build())
         *   .addOnClickAction(R.id.showIcon, new VisibilitySetterAction
         *     .Builder(R.id.hideIcon, View.VISIBLE)
         *     .setVisibility(R.id.showIcon, View.GONE)
         *     .setVisibility(R.id.password_plain, View.VISIBLE)
         *     .setVisibility(R.id.password_masked, View.GONE)
         *     .build())
         *   .addOnClickAction(R.id.hideIcon, new VisibilitySetterAction
         *     .Builder(R.id.showIcon, View.VISIBLE)
         *     .setVisibility(R.id.hideIcon, View.GONE)
         *     .setVisibility(R.id.password_masked, View.VISIBLE)
         *     .setVisibility(R.id.password_plain, View.GONE)
         *     .build());
         * </pre>
         *
         * <p><b>Note:</b> Currently only one action can be applied to a child; if this method
         * is called multiple times passing the same {@code id}, only the last call will be used.
         *
         * @param id resource id of the child view.
         * @param action action to be performed. Must be an an implementation provided by the
         * Android System.
         *
         * @return this builder
         *
         * @throws IllegalArgumentException if {@code action} is not a class provided
         * by the Android System.
         * @throws IllegalStateException if {@link #build()} was already called.
         */
        @NonNull
        public Builder addOnClickAction(int id, @NonNull OnClickAction action) {
            throwIfDestroyed();
            Preconditions.checkArgument((action instanceof InternalOnClickAction),
                    "not provided by Android System: %s", action);
            if (mActions == null) {
                mActions = new SparseArray<InternalOnClickAction>();
            }
            mActions.put(id, (InternalOnClickAction) action);

            return this;
        }

        /**
         * Creates a new {@link CustomDescription} instance.
         */
        @NonNull
        public CustomDescription build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new CustomDescription(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return new StringBuilder("CustomDescription: [presentation=")
                .append(mPresentation)
                .append(", transformations=")
                    .append(mTransformations == null ? "N/A" : mTransformations.size())
                .append(", updates=")
                    .append(mUpdates == null ? "N/A" : mUpdates.size())
                .append(", actions=")
                    .append(mActions == null ? "N/A" : mActions.size())
                .append("]").toString();
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mPresentation, flags);
        if (mPresentation == null) return;

        if (mTransformations == null) {
            dest.writeIntArray(null);
        } else {
            final int size = mTransformations.size();
            final int[] ids = new int[size];
            final InternalTransformation[] values = new InternalTransformation[size];
            for (int i = 0; i < size; i++) {
                final Pair<Integer, InternalTransformation> pair = mTransformations.get(i);
                ids[i] = pair.first;
                values[i] = pair.second;
            }
            dest.writeIntArray(ids);
            dest.writeParcelableArray(values, flags);
        }
        if (mUpdates == null) {
            dest.writeParcelableArray(null, flags);
        } else {
            final int size = mUpdates.size();
            final InternalValidator[] conditions = new InternalValidator[size];
            final BatchUpdates[] updates = new BatchUpdates[size];

            for (int i = 0; i < size; i++) {
                final Pair<InternalValidator, BatchUpdates> pair = mUpdates.get(i);
                conditions[i] = pair.first;
                updates[i] = pair.second;
            }
            dest.writeParcelableArray(conditions, flags);
            dest.writeParcelableArray(updates, flags);
        }
        if (mActions == null) {
            dest.writeIntArray(null);
        } else {
            final int size = mActions.size();
            final int[] ids = new int[size];
            final InternalOnClickAction[] values = new InternalOnClickAction[size];
            for (int i = 0; i < size; i++) {
                ids[i] = mActions.keyAt(i);
                values[i] = mActions.valueAt(i);
            }
            dest.writeIntArray(ids);
            dest.writeParcelableArray(values, flags);
        }
    }
    public static final @android.annotation.NonNull Parcelable.Creator<CustomDescription> CREATOR =
            new Parcelable.Creator<CustomDescription>() {
        @Override
        public CustomDescription createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final RemoteViews parentPresentation = parcel.readParcelable(null);
            if (parentPresentation == null) return null;

            final Builder builder = new Builder(parentPresentation);
            final int[] transformationIds = parcel.createIntArray();
            if (transformationIds != null) {
                final InternalTransformation[] values =
                    parcel.readParcelableArray(null, InternalTransformation.class);
                final int size = transformationIds.length;
                for (int i = 0; i < size; i++) {
                    builder.addChild(transformationIds[i], values[i]);
                }
            }
            final InternalValidator[] conditions =
                    parcel.readParcelableArray(null, InternalValidator.class);
            if (conditions != null) {
                final BatchUpdates[] updates = parcel.readParcelableArray(null, BatchUpdates.class);
                final int size = conditions.length;
                for (int i = 0; i < size; i++) {
                    builder.batchUpdate(conditions[i], updates[i]);
                }
            }
            final int[] actionIds = parcel.createIntArray();
            if (actionIds != null) {
                final InternalOnClickAction[] values =
                    parcel.readParcelableArray(null, InternalOnClickAction.class);
                final int size = actionIds.length;
                for (int i = 0; i < size; i++) {
                    builder.addOnClickAction(actionIds[i], values[i]);
                }
            }
            return builder.build();
        }

        @Override
        public CustomDescription[] newArray(int size) {
            return new CustomDescription[size];
        }
    };
}
