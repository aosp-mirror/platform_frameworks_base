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
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Pair;
import android.widget.RemoteViews;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * Defines actions to be applied to a {@link RemoteViews template presentation}.
 *
 *
 * <p>It supports 2 types of actions:
 *
 * <ol>
 *   <li>{@link RemoteViews Actions} to be applied to the template.
 *   <li>{@link Transformation Transformations} to be applied on child views.
 * </ol>
 *
 * <p>Typically used on {@link CustomDescription custom descriptions} to conditionally display
 * differents views based on user input - see
 * {@link CustomDescription.Builder#batchUpdate(Validator, BatchUpdates)} for more information.
 */
public final class BatchUpdates implements Parcelable {

    private final ArrayList<Pair<Integer, InternalTransformation>> mTransformations;
    private final RemoteViews mUpdates;

    private BatchUpdates(Builder builder) {
        mTransformations = builder.mTransformations;
        mUpdates = builder.mUpdates;
    }

    /** @hide */
    @Nullable
    public ArrayList<Pair<Integer, InternalTransformation>> getTransformations() {
        return mTransformations;
    }

    /** @hide */
    @Nullable
    public RemoteViews getUpdates() {
        return mUpdates;
    }

    /**
     * Builder for {@link BatchUpdates} objects.
     */
    public static class Builder {
        private RemoteViews mUpdates;

        private boolean mDestroyed;
        private ArrayList<Pair<Integer, InternalTransformation>> mTransformations;

        /**
         * Applies the {@code updates} in the underlying presentation template.
         *
         * <p><b>Note:</b> The updates are applied before the
         * {@link #transformChild(int, Transformation) transformations} are applied to the children
         * views.
         *
         * <p>Theme does not work with RemoteViews layout. Avoid hardcoded text color
         * or background color: Autofill on different platforms may have different themes.
         *
         * @param updates a {@link RemoteViews} with the updated actions to be applied in the
         * underlying presentation template.
         *
         * @return this builder
         * @throws IllegalArgumentException if {@code condition} is not a class provided
         * by the Android System.
         */
        public Builder updateTemplate(@NonNull RemoteViews updates) {
            throwIfDestroyed();
            mUpdates = Preconditions.checkNotNull(updates);
            return this;
        }

        /**
         * Adds a transformation to replace the value of a child view with the fields in the
         * screen.
         *
         * <p>When multiple transformations are added for the same child view, they are applied
         * in the same order as added.
         *
         * <p><b>Note:</b> The transformations are applied after the
         * {@link #updateTemplate(RemoteViews) updates} are applied to the presentation template.
         *
         * @param id view id of the children view.
         * @param transformation an implementation provided by the Android System.
         * @return this builder.
         * @throws IllegalArgumentException if {@code transformation} is not a class provided
         * by the Android System.
         */
        public Builder transformChild(int id, @NonNull Transformation transformation) {
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
         * Creates a new {@link BatchUpdates} instance.
         *
         * @throws IllegalStateException if {@link #build()} was already called before or no call
         * to {@link #updateTemplate(RemoteViews)} or {@link #transformChild(int, Transformation)}
         * has been made.
         */
        public BatchUpdates build() {
            throwIfDestroyed();
            Preconditions.checkState(mUpdates != null || mTransformations != null,
                    "must call either updateTemplate() or transformChild() at least once");
            mDestroyed = true;
            return new BatchUpdates(this);
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

        return new StringBuilder("BatchUpdates: [")
                .append(", transformations=")
                    .append(mTransformations == null ? "N/A" : mTransformations.size())
                .append(", updates=").append(mUpdates)
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
        dest.writeParcelable(mUpdates, flags);
    }
    public static final @android.annotation.NonNull Parcelable.Creator<BatchUpdates> CREATOR =
            new Parcelable.Creator<BatchUpdates>() {
        @Override
        public BatchUpdates createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder();
            final int[] ids = parcel.createIntArray();
            if (ids != null) {
                final InternalTransformation[] values =
                    parcel.readParcelableArray(null, InternalTransformation.class);
                final int size = ids.length;
                for (int i = 0; i < size; i++) {
                    builder.transformChild(ids[i], values[i]);
                }
            }
            final RemoteViews updates = parcel.readParcelable(null);
            if (updates != null) {
                builder.updateTemplate(updates);
            }
            return builder.build();
        }

        @Override
        public BatchUpdates[] newArray(int size) {
            return new BatchUpdates[size];
        }
    };
}
