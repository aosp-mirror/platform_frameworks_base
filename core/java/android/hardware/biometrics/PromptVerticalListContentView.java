/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.hardware.biometrics;

import static android.hardware.biometrics.Flags.FLAG_CUSTOM_BIOMETRIC_PROMPT;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;


/**
 * Contains the information of the template of vertical list content view for Biometric Prompt.
 * <p>
 * Here's how you'd set a <code>PromptVerticalListContentView</code> on a Biometric Prompt:
 * <pre class="prettyprint">
 * BiometricPrompt biometricPrompt = new BiometricPrompt.Builder(...)
 *     .setTitle(...)
 *     .setSubTitle(...)
 *     .setContentView(new PromptVerticalListContentView.Builder()
 *         .setDescription("test description")
 *         .addListItem(new PromptContentItemPlainText("test item 1"))
 *         .addListItem(new PromptContentItemPlainText("test item 2"))
 *         .addListItem(new PromptContentItemBulletedText("test item 3"))
 *         .build())
 *     .build();
 * </pre>
 */
@FlaggedApi(FLAG_CUSTOM_BIOMETRIC_PROMPT)
public final class PromptVerticalListContentView implements PromptContentViewParcelable {
    @VisibleForTesting
    static final int MAX_ITEM_NUMBER = 20;
    @VisibleForTesting
    static final int MAX_EACH_ITEM_CHARACTER_NUMBER = 640;
    @VisibleForTesting
    static final int MAX_DESCRIPTION_CHARACTER_NUMBER = 225;

    private final List<PromptContentItemParcelable> mContentList;
    private final String mDescription;

    private PromptVerticalListContentView(
            @NonNull List<PromptContentItemParcelable> contentList,
            @NonNull String description) {
        mContentList = contentList;
        mDescription = description;
    }

    private PromptVerticalListContentView(Parcel in) {
        mContentList = in.readArrayList(
                PromptContentItemParcelable.class.getClassLoader(),
                PromptContentItemParcelable.class);
        mDescription = in.readString();
    }

    /**
     * Returns the maximum count of the list items.
     */
    public static int getMaxItemCount() {
        return MAX_ITEM_NUMBER;
    }

    /**
     * Returns the maximum number of characters allowed for each item's text.
     */
    public static int getMaxEachItemCharacterNumber() {
        return MAX_EACH_ITEM_CHARACTER_NUMBER;
    }

    /**
     * Gets the description for the content view, as set by
     * {@link PromptVerticalListContentView.Builder#setDescription(String)}.
     *
     * @return The description for the content view, or null if the content view has no description.
     */
    @Nullable
    public String getDescription() {
        return mDescription;
    }

    /**
     * Gets the list of items on the content view, as set by
     * {@link PromptVerticalListContentView.Builder#addListItem(PromptContentItem)}.
     *
     * @return The item list on the content view.
     */
    @NonNull
    public List<PromptContentItem> getListItems() {
        return new ArrayList<>(mContentList);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeToParcel(@androidx.annotation.NonNull Parcel dest, int flags) {
        dest.writeList(mContentList);
        dest.writeString(mDescription);
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<PromptVerticalListContentView> CREATOR = new Creator<>() {
        @Override
        public PromptVerticalListContentView createFromParcel(Parcel in) {
            return new PromptVerticalListContentView(in);
        }

        @Override
        public PromptVerticalListContentView[] newArray(int size) {
            return new PromptVerticalListContentView[size];
        }
    };


    /**
     * A builder that collects arguments to be shown on the vertical list view.
     */
    public static final class Builder {
        private final List<PromptContentItemParcelable> mContentList = new ArrayList<>();
        private String mDescription;

        /**
         * Optional: Sets a description that will be shown on the content view.
         *
         * @param description The description to display.
         * @return This builder.
         * @throws IllegalArgumentException If description exceeds certain character limit.
         */
        @NonNull
        public Builder setDescription(@NonNull String description) {
            if (description.length() > MAX_DESCRIPTION_CHARACTER_NUMBER) {
                throw new IllegalArgumentException("The character number of description exceeds "
                        + MAX_DESCRIPTION_CHARACTER_NUMBER);
            }
            mDescription = description;
            return this;
        }

        /**
         * Optional: Adds a list item in the current row.
         *
         * @param listItem The list item view to display
         * @return This builder.
         * @throws IllegalArgumentException If this list item exceeds certain character limits or
         *                                  the number of list items exceeds certain limit.
         */
        @NonNull
        public Builder addListItem(@NonNull PromptContentItem listItem) {
            mContentList.add((PromptContentItemParcelable) listItem);
            checkItemLimits(listItem);
            return this;
        }

        /**
         * Optional: Adds a list item in the current row.
         *
         * @param listItem The list item view to display
         * @param index    The position at which to add the item
         * @return This builder.
         * @throws IllegalArgumentException If this list item exceeds certain character limits or
         *                                  the number of list items exceeds certain limit.
         */
        @NonNull
        public Builder addListItem(@NonNull PromptContentItem listItem, int index) {
            mContentList.add(index, (PromptContentItemParcelable) listItem);
            checkItemLimits(listItem);
            return this;
        }

        private void checkItemLimits(@NonNull PromptContentItem listItem) {
            if (doesListItemExceedsCharLimit(listItem)) {
                throw new IllegalArgumentException(
                        "The character number of list item exceeds "
                                + MAX_EACH_ITEM_CHARACTER_NUMBER);
            }
            if (mContentList.size() > MAX_ITEM_NUMBER) {
                throw new IllegalArgumentException(
                        "The number of list items exceeds " + MAX_ITEM_NUMBER);
            }
        }

        private boolean doesListItemExceedsCharLimit(PromptContentItem listItem) {
            if (listItem instanceof PromptContentItemPlainText) {
                return ((PromptContentItemPlainText) listItem).getText().length()
                        > MAX_EACH_ITEM_CHARACTER_NUMBER;
            } else if (listItem instanceof PromptContentItemBulletedText) {
                return ((PromptContentItemBulletedText) listItem).getText().length()
                        > MAX_EACH_ITEM_CHARACTER_NUMBER;
            } else {
                return false;
            }
        }


        /**
         * Creates a {@link PromptVerticalListContentView}.
         *
         * @return An instance of {@link PromptVerticalListContentView}.
         */
        @NonNull
        public PromptVerticalListContentView build() {
            return new PromptVerticalListContentView(mContentList, mDescription);
        }
    }
}
