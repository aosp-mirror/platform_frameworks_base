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

import static android.view.autofill.Helper.DEBUG;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;

import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;

/**
 * Information used to indicate that an {@link AutofillService} is interested on saving the
 * user-inputed data for future use, through a
 * {@link AutofillService#onSaveRequest(android.app.assist.AssistStructure, Bundle, SaveCallback)}
 * call.
 *
 * <p>A {@link SaveInfo} is always associated with a {@link FillResponse}, and it contains at least
 * two pieces of information:
 *
 * <ol>
 *   <li>The type of user data that would be saved (like passoword or credit card info).
 *   <li>The minimum set of views (represented by their {@link AutofillId}) that need to be changed
 *       to trigger a save request.
 * </ol>
 *
 *  Typically, the {@link SaveInfo} contains the same {@code id}s as the {@link Dataset}:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder(createPresentation())
 *          .setValue(id1, AutofillValue.forText("homer"))
 *          .setValue(id2, AutofillValue.forText("D'OH!"))
 *          .build())
 *      .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_INFO_TYPE_PASSWORD, new int[] {id1, id2})
 *                  .build())
 *      .build();
 * </pre>
 *
 * There might be cases where the {@link AutofillService} knows how to fill the
 * {@link android.app.Activity}, but the user has no data for it. In that case, the
 * {@link FillResponse} should contain just the {@link SaveInfo}, but no {@link Dataset}s:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_INFO_TYPE_PASSWORD, new int[] {id1, id2})
 *                  .build())
 *      .build();
 * </pre>
 *
 * <p>There might be cases where the user data in the {@link AutofillService} is enough
 * to populate some fields but not all, and the service would still be interested on saving the
 * other fields. In this scenario, the service could set the
 * {@link SaveInfo.Builder#setOptionalIds(AutofillId[])} as well:
 *
 * <pre class="prettyprint">
 *   new FillResponse.Builder()
 *       .add(new Dataset.Builder(createPresentation())
 *          .setValue(id1, AutofillValue.forText("742 Evergreen Terrace"))  // street
 *          .setValue(id2, AutofillValue.forText("Springfield"))            // city
 *          .build())
 *       .setSaveInfo(new SaveInfo.Builder(SaveInfo.SAVE_INFO_TYPE_ADDRESS, new int[] {id1, id2})
 *                   .setOptionalIds(new int[] {id3, id4}) // state and zipcode
 *                   .build())
 *       .build();
 * </pre>
 *
 * The
 * {@link AutofillService#onSaveRequest(android.app.assist.AssistStructure, Bundle, SaveCallback)}
 * is triggered after a call to {@link AutofillManager#commit()}, but only when all conditions
 * below are met:
 *
 * <ol>
 *   <li>The {@link SaveInfo} associated with the {@link FillResponse} is not {@code null}.
 *   <li>The {@link AutofillValue} of all required views (as set by the {@code requiredIds} passed
 *       to {@link SaveInfo.Builder} constructor are not empty.
 *   <li>The {@link AutofillValue} of at least one view (be it required or optional) has changed
 *       (i.e., it's not the same value passed in a {@link Dataset}).
 *   <li>The user explicitly tapped the affordance asking to save data for autofill.
 * </ol>
 */
public final class SaveInfo implements Parcelable {

    /**
     * Type used on when the service can save the contents of an activity, but cannot describe what
     * the content is for.
     */
    public static final int SAVE_DATA_TYPE_GENERIC = 0;

    /**
     * Type used when the {@link FillResponse} represents user credentials that have a password.
     */
    public static final int SAVE_DATA_TYPE_PASSWORD = 1;

    /**
     * Type used on when the {@link FillResponse} represents a physical address (such as street,
     * city, state, etc).
     */
    public static final int SAVE_DATA_TYPE_ADDRESS = 2;

    /**
     * Type used when the {@link FillResponse} represents a credit card.
     */
    public static final int SAVE_DATA_TYPE_CREDIT_CARD = 3;

    /**
     * Type used when the {@link FillResponse} represents just an username, without a password.
     */
    public static final int SAVE_DATA_TYPE_USERNAME = 4;

    /**
     * Type used when the {@link FillResponse} represents just an email address, without a password.
     */
    public static final int SAVE_DATA_TYPE_EMAIL_ADDRESS = 5;

    private final @SaveDataType int mType;
    private final CharSequence mNegativeActionTitle;
    private final IntentSender mNegativeActionListener;
    private final AutofillId[] mRequiredIds;
    private final AutofillId[] mOptionalIds;
    private final CharSequence mDescription;

    /** @hide */
    @IntDef({
        SAVE_DATA_TYPE_GENERIC,
        SAVE_DATA_TYPE_PASSWORD,
        SAVE_DATA_TYPE_ADDRESS,
        SAVE_DATA_TYPE_CREDIT_CARD
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SaveDataType {
    }

    private SaveInfo(Builder builder) {
        mType = builder.mType;
        mNegativeActionTitle = builder.mNegativeActionTitle;
        mNegativeActionListener = builder.mNegativeActionListener;
        mRequiredIds = builder.mRequiredIds;
        mOptionalIds = builder.mOptionalIds;
        mDescription = builder.mDescription;
    }

    /** @hide */
    public @Nullable CharSequence getNegativeActionTitle() {
        return mNegativeActionTitle;
    }

    /** @hide */
    public @Nullable IntentSender getNegativeActionListener() {
        return mNegativeActionListener;
    }

    /** @hide */
    public AutofillId[] getRequiredIds() {
        return mRequiredIds;
    }

    /** @hide */
    public @Nullable AutofillId[] getOptionalIds() {
        return mOptionalIds;
    }

    /** @hide */
    public int getType() {
        return mType;
    }

    /** @hide */
    public CharSequence getDescription() {
        return mDescription;
    }

    /**
     * A builder for {@link SaveInfo} objects.
     */
    public static final class Builder {

        private final @SaveDataType int mType;
        private CharSequence mNegativeActionTitle;
        private IntentSender mNegativeActionListener;
        // TODO(b/33197203): make mRequiredIds final once addSavableIds() is gone
        private AutofillId[] mRequiredIds;
        private AutofillId[] mOptionalIds;
        private CharSequence mDescription;
        private boolean mDestroyed;

        /**
         * Creates a new builder.
         *
         * @param type the type of information the associated {@link FillResponse} represents. Must
         * be {@link SaveInfo#SAVE_DATA_TYPE_GENERIC}, {@link SaveInfo#SAVE_DATA_TYPE_PASSWORD},
         * {@link SaveInfo#SAVE_DATA_TYPE_ADDRESS}, or {@link SaveInfo#SAVE_DATA_TYPE_CREDIT_CARD};
         * otherwise it will assume {@link SaveInfo#SAVE_DATA_TYPE_GENERIC}.
         * @param requiredIds ids of all required views that will trigger a save request.
         *
         * <p>See {@link SaveInfo} for more info.
         *
         * @throws IllegalArgumentException if {@code requiredIds} is {@code null} or empty.
         */
        public Builder(@SaveDataType int type, @NonNull AutofillId[] requiredIds) {
            if (false) {// TODO(b/33197203): re-move when clients use it
            Preconditions.checkArgument(requiredIds != null && requiredIds.length > 0,
                    "must have at least one required id: " + Arrays.toString(requiredIds));
            }
            switch (type) {
                case SAVE_DATA_TYPE_PASSWORD:
                case SAVE_DATA_TYPE_ADDRESS:
                case SAVE_DATA_TYPE_CREDIT_CARD:
                case SAVE_DATA_TYPE_USERNAME:
                case SAVE_DATA_TYPE_EMAIL_ADDRESS:
                    mType = type;
                    break;
                default:
                    mType = SAVE_DATA_TYPE_GENERIC;
            }
            mRequiredIds = requiredIds;
        }

        /**
         * @hide
         * @deprecated
         * // TODO(b/33197203): make sure is removed when clients migrated
         */
        @Deprecated
        public Builder(@SaveDataType int type) {
            this(type, null);
        }

        /**
         * @hide
         * @deprecated
         * // TODO(b/33197203): make sure is removed when clients migrated
         */
        @Deprecated
        public @NonNull Builder addSavableIds(@Nullable AutofillId... ids) {
            throwIfDestroyed();
            mRequiredIds = ids;
            return this;
        }

        /**
         * Sets the ids of additional, optional views the service would be interested to save.
         *
         * <p>See {@link SaveInfo} for more info.
         *
         * @param ids The ids of the optional views.
         * @return This builder.
         */
        public @NonNull Builder setOptionalIds(@Nullable AutofillId[] ids) {
            throwIfDestroyed();
            if (ids != null && ids.length != 0) {
                mOptionalIds = ids;
            }
            return this;
        }

        /**
         * Sets an optional description to be shown in the UI when the user is asked to save.
         *
         * <p>Typically, it describes how the data will be stored by the service, so it can help
         * users to decide whether they can trust the service to save their data.
         *
         * @param description a succint description.
         * @return This Builder.
         */
        public @NonNull Builder setDescription(@Nullable CharSequence description) {
            throwIfDestroyed();
            mDescription = description;
            return this;
        }

        /**
         * Sets the title and listener for the negative save action.
         *
         * <p>This allows a fill-provider to customize the text and be
         * notified when the user selects the negative action in the save
         * UI. Note that selecting the negative action regardless of its text
         * and listener being customized would dismiss the save UI and if a
         * custom listener intent is provided then this intent will be
         * started.</p>
         *
         * <p>This customization could be useful for providing additional
         * semantics to the negative action. For example, a fill-provider
         * can use this mechanism to add a "Disable" function or a "More info"
         * function, etc. Note that the save action is exclusively controlled
         * by the platform to ensure user consent is collected to release
         * data from the filled app to the fill-provider.</p>
         *
         * @param title The action title.
         * @param listener The action listener.
         * @return This builder.
         *
         * @throws IllegalArgumentException If the title and the listener
         *     are not both either null or non-null.
         */
        public @NonNull Builder setNegativeAction(@Nullable CharSequence title,
                @Nullable IntentSender listener) {
            throwIfDestroyed();
            if (title == null ^ listener == null) {
                throw new IllegalArgumentException("title and listener"
                        + " must be both non-null or null");
            }
            mNegativeActionTitle = title;
            mNegativeActionListener = listener;
            return this;
        }

        /**
         * Builds a new {@link SaveInfo} instance.
         */
        public SaveInfo build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new SaveInfo(this);
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
        if (!DEBUG) return super.toString();

        return new StringBuilder("SaveInfo: [type=").append(mType)
                .append(", requiredIds=").append(Arrays.toString(mRequiredIds))
                .append(", optionalIds=").append(Arrays.toString(mOptionalIds))
                .append(", description=").append(mDescription)
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
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mType);
        parcel.writeParcelableArray(mRequiredIds, flags);
        parcel.writeCharSequence(mNegativeActionTitle);
        parcel.writeParcelable(mNegativeActionListener, flags);
        parcel.writeParcelableArray(mOptionalIds, flags);
        parcel.writeCharSequence(mDescription);
    }

    public static final Parcelable.Creator<SaveInfo> CREATOR = new Parcelable.Creator<SaveInfo>() {
        @Override
        public SaveInfo createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder(parcel.readInt(),
                    parcel.readParcelableArray(null, AutofillId.class));
            builder.setNegativeAction(parcel.readCharSequence(), parcel.readParcelable(null));
            builder.setOptionalIds(parcel.readParcelableArray(null, AutofillId.class));
            builder.setDescription(parcel.readCharSequence());
            return builder.build();
        }

        @Override
        public SaveInfo[] newArray(int size) {
            return new SaveInfo[size];
        }
    };
}
