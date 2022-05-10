/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.autofill.AutofillValue;

import com.android.internal.util.DataClass;

import java.util.regex.Pattern;

/**
 * This class is used to set all information of a field. Such as the {@link AutofillValue}
 * to be autofilled, a <a href="#Filtering">explicit filter</a>, and presentations to be
 * visualized, etc.
 */
public final class Field {

    /**
     * The value to be autofilled. Pass {@code null} if you do not have the value
     * but the target view is a logical part of the dataset. For example, if the
     * dataset needs authentication and you have no access to the value.
     */
    private @Nullable AutofillValue mValue;

    /**
     * Regex used to determine if the dataset should be shown in the autofill UI;
     * when {@code null}, it disables filtering on that dataset (this is the recommended
     * approach when {@code value} is not {@code null} and field contains sensitive data
     * such as passwords).
     *
     * @see Dataset.DatasetFieldFilter
     * @hide
     */
    private @Nullable Dataset.DatasetFieldFilter mFilter;

    /**
     * The presentations used to visualize this field in Autofill UI.
     */
    private @Nullable Presentations mPresentations;


    /* package-private */ Field(
            @Nullable AutofillValue value,
            @Nullable Dataset.DatasetFieldFilter filter,
            @Nullable Presentations presentations) {
        this.mValue = value;
        this.mFilter = filter;
        this.mPresentations = presentations;
    }

    /**
     * The value to be autofilled. Pass {@code null} if you do not have the value
     * but the target view is a logical part of the dataset. For example, if the
     * dataset needs authentication and you have no access to the value.
     */
    @DataClass.Generated.Member
    public @Nullable AutofillValue getValue() {
        return mValue;
    }

    /**
     * Regex used to determine if the dataset should be shown in the autofill UI;
     * when {@code null}, it disables filtering on that dataset (this is the recommended
     * approach when {@code value} is not {@code null} and field contains sensitive data
     * such as passwords).
     *
     * @see Dataset.DatasetFieldFilter
     * @hide
     */
    public @Nullable Dataset.DatasetFieldFilter getDatasetFieldFilter() {
        return mFilter;
    }

    /**
     * Regex used to determine if the dataset should be shown in the autofill UI;
     * when {@code null}, it disables filtering on that dataset (this is the recommended
     * approach when {@code value} is not {@code null} and field contains sensitive data
     * such as passwords).
     */
    public @Nullable Pattern getFilter() {
        return mFilter == null ? null : mFilter.pattern;
    }

    /**
     * The presentations used to visualize this field in Autofill UI.
     */
    public @Nullable Presentations getPresentations() {
        return mPresentations;
    }

    /**
     * A builder for {@link Field}
     */
    public static final class Builder {

        private @Nullable AutofillValue mValue = null;
        private @Nullable Dataset.DatasetFieldFilter mFilter = null;
        private @Nullable Presentations mPresentations = null;
        private boolean mDestroyed = false;

        public Builder() {
        }

        /**
         * The value to be autofilled. Pass {@code null} if you do not have the value
         * but the target view is a logical part of the dataset. For example, if the
         * dataset needs authentication and you have no access to the value.
         */
        public @NonNull Builder setValue(@NonNull AutofillValue value) {
            checkNotUsed();
            mValue = value;
            return this;
        }

        /**
         * Regex used to determine if the dataset should be shown in the autofill UI;
         * when {@code null}, it disables filtering on that dataset (this is the recommended
         * approach when {@code value} is not {@code null} and field contains sensitive data
         * such as passwords).
         */
        public @NonNull Builder setFilter(@Nullable Pattern value) {
            checkNotUsed();
            mFilter = new Dataset.DatasetFieldFilter(value);
            return this;
        }

        /**
         * The presentations used to visualize this field in Autofill UI.
         */
        public @NonNull Builder setPresentations(@NonNull Presentations value) {
            checkNotUsed();
            mPresentations = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull Field build() {
            checkNotUsed();
            mDestroyed = true; // Mark builder used

            Field o = new Field(
                    mValue,
                    mFilter,
                    mPresentations);
            return o;
        }

        private void checkNotUsed() {
            if (mDestroyed) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
