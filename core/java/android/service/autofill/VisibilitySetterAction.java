/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static android.view.autofill.Helper.sVerbose;

import android.annotation.IdRes;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Slog;
import android.util.SparseIntArray;
import android.view.View;
import android.view.View.Visibility;
import android.view.ViewGroup;
import android.widget.RemoteViews;

import com.android.internal.util.Preconditions;

/**
 * Action used to change the visibility of other child view in a {@link CustomDescription}
 * {@link RemoteViews presentation template}.
 *
 * <p>See {@link CustomDescription.Builder#addOnClickAction(int, OnClickAction)} for more details.
 */
public final class VisibilitySetterAction extends InternalOnClickAction implements
        OnClickAction, Parcelable {
    private static final String TAG = "VisibilitySetterAction";

    @NonNull private final SparseIntArray mVisibilities;

    private VisibilitySetterAction(@NonNull Builder builder) {
        mVisibilities = builder.mVisibilities;
    }

    /** @hide */
    @Override
    public void onClick(@NonNull ViewGroup rootView) {
        for (int i = 0; i < mVisibilities.size(); i++) {
            final int id = mVisibilities.keyAt(i);
            final View child = rootView.findViewById(id);
            if (child == null) {
                Slog.w(TAG, "Skipping view id " + id + " because it's not found on " + rootView);
                continue;
            }
            final int visibility = mVisibilities.valueAt(i);
            if (sVerbose) {
                Slog.v(TAG, "Changing visibility of view " + child + " from "
                        + child.getVisibility() + " to  " + visibility);
            }
            child.setVisibility(visibility);
        }
    }

    /**
     * Builder for {@link VisibilitySetterAction} objects.
     */
    public static final class Builder {
        private final SparseIntArray mVisibilities = new SparseIntArray();
        private boolean mDestroyed;

        /**
         * Creates a new builder for an action that change the visibility of one child view.
         *
         * @param id view resource id of the children view.
         * @param visibility one of {@link View#VISIBLE}, {@link View#INVISIBLE}, or
         *            {@link View#GONE}.
         * @throws IllegalArgumentException if visibility is not one of {@link View#VISIBLE},
         * {@link View#INVISIBLE}, or {@link View#GONE}.
         */
        public Builder(@IdRes int id, @Visibility int visibility) {
            setVisibility(id, visibility);
        }

        /**
         * Sets the action to changes the visibility of a child view.
         *
         * @param id view resource id of the children view.
         * @param visibility one of {@link View#VISIBLE}, {@link View#INVISIBLE}, or
         *            {@link View#GONE}.
         * @throws IllegalArgumentException if visibility is not one of {@link View#VISIBLE},
         * {@link View#INVISIBLE}, or {@link View#GONE}.
         */
        @NonNull
        public Builder setVisibility(@IdRes int id, @Visibility int visibility) {
            throwIfDestroyed();
            switch (visibility) {
                case View.VISIBLE:
                case View.INVISIBLE:
                case View.GONE:
                    mVisibilities.put(id, visibility);
                    return this;
            }
            throw new IllegalArgumentException("Invalid visibility: " + visibility);
        }

        /**
         * Creates a new {@link VisibilitySetterAction} instance.
         */
        @NonNull
        public VisibilitySetterAction build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new VisibilitySetterAction(this);
        }

        private void throwIfDestroyed() {
            Preconditions.checkState(!mDestroyed, "Already called build()");
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "VisibilitySetterAction: [" + mVisibilities + "]";
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
        parcel.writeSparseIntArray(mVisibilities);
    }

    public static final Parcelable.Creator<VisibilitySetterAction> CREATOR =
            new Parcelable.Creator<VisibilitySetterAction>() {

        @NonNull
        @Override
        public VisibilitySetterAction createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            final SparseIntArray visibilities = parcel.readSparseIntArray();
            Builder builder = null;
            for (int i = 0; i < visibilities.size(); i++) {
                final int id = visibilities.keyAt(i);
                final int visibility = visibilities.valueAt(i);
                if (builder == null) {
                    builder = new Builder(id, visibility);
                } else {
                    builder.setVisibility(id, visibility);
                }
            }
            return builder == null ? null : builder.build();
        }

        @NonNull
        @Override
        public VisibilitySetterAction[] newArray(int size) {
            return new VisibilitySetterAction[size];
        }
    };
}
