/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.view;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Arrays;
import java.util.StringJoiner;

/**
 * A collection of visibilities of insets. This is used for carrying the requested visibilities.
 * @hide
 */
public class InsetsVisibilities implements Parcelable {

    private static final int UNSPECIFIED = 0;
    private static final int VISIBLE = 1;
    private static final int INVISIBLE = -1;

    private final int[] mVisibilities = new int[InsetsState.SIZE];

    public InsetsVisibilities() {
    }

    public InsetsVisibilities(InsetsVisibilities other) {
        set(other);
    }

    public InsetsVisibilities(Parcel in) {
        in.readIntArray(mVisibilities);
    }

    /**
     * Copies from another {@link InsetsVisibilities}.
     *
     * @param other an instance of {@link InsetsVisibilities}.
     */
    public void set(InsetsVisibilities other) {
        System.arraycopy(other.mVisibilities, InsetsState.FIRST_TYPE, mVisibilities,
                InsetsState.FIRST_TYPE, InsetsState.SIZE);
    }

    /**
     * Sets a visibility to a type.
     *
     * @param type The {@link @InsetsState.InternalInsetsType}.
     * @param visible {@code true} represents visible; {@code false} represents invisible.
     */
    public void setVisibility(@InsetsState.InternalInsetsType int type, boolean visible) {
        mVisibilities[type] = visible ? VISIBLE : INVISIBLE;
    }

    /**
     * Returns the specified insets visibility of the type. If it has never been specified,
     * this returns the default visibility.
     *
     * @param type The {@link @InsetsState.InternalInsetsType}.
     * @return The specified visibility or the default one if it is not specified.
     */
    public boolean getVisibility(@InsetsState.InternalInsetsType int type) {
        final int visibility = mVisibilities[type];
        return visibility == UNSPECIFIED
                ? InsetsState.getDefaultVisibility(type)
                : visibility == VISIBLE;
    }

    @Override
    public String toString() {
        StringJoiner joiner = new StringJoiner(", ");
        for (int type = InsetsState.FIRST_TYPE; type <= InsetsState.LAST_TYPE; type++) {
            final int visibility = mVisibilities[type];
            if (visibility != UNSPECIFIED) {
                joiner.add(InsetsState.typeToString(type) + ": "
                        + (visibility == VISIBLE ? "visible" : "invisible"));
            }
        }
        return joiner.toString();
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mVisibilities);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof InsetsVisibilities)) {
            return false;
        }
        return Arrays.equals(mVisibilities, ((InsetsVisibilities) other).mVisibilities);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeIntArray(mVisibilities);
    }

    public void readFromParcel(@NonNull Parcel in) {
        in.readIntArray(mVisibilities);
    }

    public static final @NonNull Creator<InsetsVisibilities> CREATOR =
            new Creator<InsetsVisibilities>() {

        public InsetsVisibilities createFromParcel(Parcel in) {
            return new InsetsVisibilities(in);
        }

        public InsetsVisibilities[] newArray(int size) {
            return new InsetsVisibilities[size];
        }
    };
}
