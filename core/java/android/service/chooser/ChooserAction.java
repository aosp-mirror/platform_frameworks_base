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

package android.service.chooser;

import android.annotation.NonNull;
import android.app.PendingIntent;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * A ChooserAction is an app-defined action that can be provided to the Android Sharesheet to
 * be shown to the user when {@link android.content.Intent#ACTION_CHOOSER} is invoked.
 *
 * @see android.content.Intent#EXTRA_CHOOSER_CUSTOM_ACTIONS
 */
public final class ChooserAction implements Parcelable {
    private final Icon mIcon;
    private final CharSequence mLabel;
    private final PendingIntent mAction;

    private ChooserAction(
            Icon icon,
            CharSequence label,
            PendingIntent action) {
        mIcon = icon;
        mLabel = label;
        mAction = action;
    }

    /**
     * Return a user-readable label for this action.
     */
    @NonNull
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * Return an {@link Icon} representing this action.
     */
    @NonNull
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * Return the action intent.
     */
    @NonNull
    public PendingIntent getAction() {
        return mAction;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        mIcon.writeToParcel(dest, flags);
        TextUtils.writeToParcel(mLabel, dest, flags);
        mAction.writeToParcel(dest, flags);
    }

    @Override
    public String toString() {
        return "ChooserAction {" + "label=" + mLabel + ", intent=" + mAction + "}";
    }

    @NonNull
    public static final Parcelable.Creator<ChooserAction> CREATOR =
            new Creator<ChooserAction>() {
                @Override
                public ChooserAction createFromParcel(Parcel source) {
                    return new ChooserAction(
                            Icon.CREATOR.createFromParcel(source),
                            TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source),
                            PendingIntent.CREATOR.createFromParcel(source));
                }

                @Override
                public ChooserAction[] newArray(int size) {
                    return new ChooserAction[size];
                }
            };

    /**
     * Builder class for {@link ChooserAction} objects
     */
    public static final class Builder {
        private final Icon mIcon;
        private final CharSequence mLabel;
        private final PendingIntent mAction;

        /**
         * Construct a new builder for {@link ChooserAction} object.
         *
         * @param icon an {@link Icon} representing this action, consisting of a white foreground
         * atop a transparent background.
         * @param label label the user-readable label for this action.
         * @param action {@link PendingIntent} to be invoked when the action is selected.
         */
        public Builder(
                @NonNull Icon icon,
                @NonNull CharSequence label,
                @NonNull PendingIntent action) {
            Objects.requireNonNull(icon, "icon can not be null");
            Objects.requireNonNull(label, "label can not be null");
            Objects.requireNonNull(action, "pending intent can not be null");
            mIcon = icon;
            mLabel = label;
            mAction = action;
        }

        /**
         * Combine all of the options that have been set and return a new {@link ChooserAction}
         * object.
         * @return the built action
         */
        @NonNull
        public ChooserAction build() {
            return new ChooserAction(mIcon, mLabel, mAction);
        }
    }
}
