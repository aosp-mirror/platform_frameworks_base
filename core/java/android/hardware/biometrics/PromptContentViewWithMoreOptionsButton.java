/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.Manifest.permission.SET_BIOMETRIC_DIALOG_ADVANCED;
import static android.hardware.biometrics.Flags.FLAG_CUSTOM_BIOMETRIC_PROMPT;

import android.annotation.CallbackExecutor;
import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.DialogInterface;
import android.hardware.biometrics.BiometricPrompt.ButtonInfo;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Executor;

/**
 * Contains the information of the template of content view with a more options button for
 * Biometric Prompt.
 * <p>
 * This button should be used to provide more options for sign in or other purposes, such as when a
 * user needs to select between multiple app-specific accounts or profiles that are available for
 * sign in.
 * <p>
 * Apps should avoid using this when possible because it will create additional steps that the user
 * must navigate through - clicking the more options button will dismiss the prompt, provide the app
 * an opportunity to ask the user for the correct option, and finally allow the app to decide how to
 * proceed once selected.
 *
 * <p>
 * Here's how you'd set a <code>PromptContentViewWithMoreOptionsButton</code> on a Biometric
 * Prompt:
 * <pre class="prettyprint">
 * BiometricPrompt biometricPrompt = new BiometricPrompt.Builder(...)
 *     .setTitle(...)
 *     .setSubTitle(...)
 *     .setContentView(new PromptContentViewWithMoreOptionsButton.Builder()
 *         .setDescription("test description")
 *         .setMoreOptionsButtonListener(executor, listener)
 *         .build())
 *     .build();
 * </pre>
 */
@FlaggedApi(FLAG_CUSTOM_BIOMETRIC_PROMPT)
public final class PromptContentViewWithMoreOptionsButton implements PromptContentViewParcelable {
    @VisibleForTesting
    static final int MAX_DESCRIPTION_CHARACTER_NUMBER = 225;

    private final String mDescription;
    private DialogInterface.OnClickListener mListener;
    private ButtonInfo mButtonInfo;

    private PromptContentViewWithMoreOptionsButton(
            @NonNull String description, @NonNull @CallbackExecutor Executor executor,
            @NonNull DialogInterface.OnClickListener listener) {
        mDescription = description;
        mListener = listener;
        mButtonInfo = new ButtonInfo(executor, listener);
    }

    private PromptContentViewWithMoreOptionsButton(Parcel in) {
        mDescription = in.readString();
    }

    /**
     * Gets the description for the content view, as set by
     * {@link PromptContentViewWithMoreOptionsButton.Builder#setDescription(String)}.
     *
     * @return The description for the content view, or null if the content view has no description.
     */
    @RequiresPermission(SET_BIOMETRIC_DIALOG_ADVANCED)
    @Nullable
    public String getDescription() {
        return mDescription;
    }

    /**
     * Gets the click listener for the more options button on the content view, as set by
     * {@link PromptContentViewWithMoreOptionsButton.Builder#setMoreOptionsButtonListener(Executor,
     * DialogInterface.OnClickListener)}.
     *
     * @return The click listener for the more options button on the content view.
     */
    @RequiresPermission(SET_BIOMETRIC_DIALOG_ADVANCED)
    @NonNull
    public DialogInterface.OnClickListener getMoreOptionsButtonListener() {
        return mListener;
    }

    ButtonInfo getButtonInfo() {
        return mButtonInfo;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mDescription);
    }

    /**
     * @see Parcelable.Creator
     */
    @NonNull
    public static final Creator<PromptContentViewWithMoreOptionsButton> CREATOR = new Creator<>() {
        @Override
        public PromptContentViewWithMoreOptionsButton createFromParcel(Parcel in) {
            return new PromptContentViewWithMoreOptionsButton(in);
        }

        @Override
        public PromptContentViewWithMoreOptionsButton[] newArray(int size) {
            return new PromptContentViewWithMoreOptionsButton[size];
        }
    };

    /**
     * A builder that collects arguments to be shown on the content view with more options button.
     */
    public static final class Builder {
        private String mDescription;
        private Executor mExecutor;
        private DialogInterface.OnClickListener mListener;

        /**
         * Optional: Sets a description that will be shown on the content view.
         *
         * @param description The description to display.
         * @return This builder.
         * @throws IllegalArgumentException If description exceeds certain character limit.
         */
        @NonNull
        @RequiresPermission(SET_BIOMETRIC_DIALOG_ADVANCED)
        public Builder setDescription(@NonNull String description) {
            if (description.length() > MAX_DESCRIPTION_CHARACTER_NUMBER) {
                throw new IllegalArgumentException("The character number of description exceeds "
                        + MAX_DESCRIPTION_CHARACTER_NUMBER);
            }
            mDescription = description;
            return this;
        }

        /**
         * Required: Sets the executor and click listener for the more options button on the
         * prompt content.
         *
         * @param executor Executor that will be used to run the on click callback.
         * @param listener Listener containing a callback to be run when the button is pressed.
         * @return This builder.
         */
        @NonNull
        @RequiresPermission(SET_BIOMETRIC_DIALOG_ADVANCED)
        public Builder setMoreOptionsButtonListener(@NonNull @CallbackExecutor Executor executor,
                @NonNull DialogInterface.OnClickListener listener) {
            mExecutor = executor;
            mListener = listener;
            return this;
        }


        /**
         * Creates a {@link PromptContentViewWithMoreOptionsButton}.
         *
         * @return An instance of {@link PromptContentViewWithMoreOptionsButton}.
         * @throws IllegalArgumentException If the executor of more options button is null, or the
         *                                  listener of more options button is null.
         */
        @NonNull
        @RequiresPermission(SET_BIOMETRIC_DIALOG_ADVANCED)
        public PromptContentViewWithMoreOptionsButton build() {
            if (mExecutor == null) {
                throw new IllegalArgumentException(
                        "The executor for the listener of more options button on prompt content "
                                + "must be set and non-null if "
                                + "PromptContentViewWithMoreOptionsButton is used.");
            }
            if (mListener == null) {
                throw new IllegalArgumentException(
                        "The listener of more options button on prompt content must be set and "
                                + "non-null if PromptContentViewWithMoreOptionsButton is used.");
            }
            return new PromptContentViewWithMoreOptionsButton(mDescription, mExecutor, mListener);
        }
    }
}
