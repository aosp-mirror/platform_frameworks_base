/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.controls.templates;

import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Parcel;
import android.service.controls.Control;
import android.service.controls.actions.BooleanAction;

import com.android.internal.util.Preconditions;

/**
 * A template for a {@link Control} with two discrete inputs.
 *
 * The two inputs represent a <i>Negative</i> input and a <i>Positive</i> input.
 * <p>
 * When one of the buttons is actioned, a {@link BooleanAction} will be sent.
 * {@link BooleanAction#getNewState} will be {@code false} if the button was
 * {@link DiscreteToggleTemplate#getNegativeButton} and {@code true} if the button was
 * {@link DiscreteToggleTemplate#getPositiveButton}.
 * @hide
 */
public class DiscreteToggleTemplate extends ControlTemplate {

    private static final @TemplateType int TYPE = TYPE_DISCRETE_TOGGLE;
    private static final String KEY_NEGATIVE_BUTTON = "key_negative_button";
    private static final String KEY_POSITIVE_BUTTON = "key_positive_button";

    private final @NonNull ControlButton mPositiveButton;
    private final @NonNull ControlButton mNegativeButton;

    /**
     * @param templateId the identifier for this template object
     * @param negativeButton a {@ControlButton} for the <i>Negative</i> input
     * @param positiveButton a {@ControlButton} for the <i>Positive</i> input
     */
    public DiscreteToggleTemplate(@NonNull String templateId,
            @NonNull ControlButton negativeButton,
            @NonNull ControlButton positiveButton) {
        super(templateId);
        Preconditions.checkNotNull(negativeButton);
        Preconditions.checkNotNull(positiveButton);
        mNegativeButton = negativeButton;
        mPositiveButton = positiveButton;
    }

    DiscreteToggleTemplate(Bundle b) {
        super(b);
        mNegativeButton = b.getParcelable(KEY_NEGATIVE_BUTTON);
        mPositiveButton = b.getParcelable(KEY_POSITIVE_BUTTON);
    }

    /**
     * The {@link ControlButton} associated with the <i>Negative</i> action.
     */
    @NonNull
    public ControlButton getNegativeButton() {
        return mNegativeButton;
    }

    /**
     * The {@link ControlButton} associated with the <i>Positive</i> action.
     */
    @NonNull
    public ControlButton getPositiveButton() {
        return mPositiveButton;
    }

    /**
     * @return {@link ControlTemplate#TYPE_DISCRETE_TOGGLE}
     */
    @Override
    public int getTemplateType() {
        return TYPE;
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    protected Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putParcelable(KEY_NEGATIVE_BUTTON, mNegativeButton);
        b.putParcelable(KEY_POSITIVE_BUTTON, mPositiveButton);
        return b;
    }

    public static final Creator<DiscreteToggleTemplate> CREATOR =
            new Creator<DiscreteToggleTemplate>() {
                @Override
                public DiscreteToggleTemplate createFromParcel(Parcel source) {
                    int type = source.readInt();
                    verifyType(type, TYPE);
                    return new DiscreteToggleTemplate(source.readBundle());
                }

                @Override
                public DiscreteToggleTemplate[] newArray(int size) {
                    return new DiscreteToggleTemplate[size];
                }
            };
}
