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

import com.android.internal.util.Preconditions;

/**
 * @hide
 */
public final class ToggleRangeTemplate extends ControlTemplate {

    private static final @TemplateType int TYPE = TYPE_TOGGLE_RANGE;
    private static final String KEY_BUTTON = "key_button";
    private static final String KEY_RANGE = "key_range";

    private @NonNull final ControlButton mControlButton;
    private @NonNull final RangeTemplate mRangeTemplate;


    ToggleRangeTemplate(@NonNull Bundle b) {
        super(b);
        mControlButton = b.getParcelable(KEY_BUTTON);
        mRangeTemplate = b.getParcelable(KEY_RANGE);
    }

    public ToggleRangeTemplate(@NonNull String templateId,
            @NonNull ControlButton button,
            @NonNull RangeTemplate range) {
        super(templateId);
        Preconditions.checkNotNull(button);
        Preconditions.checkNotNull(range);
        mControlButton = button;
        mRangeTemplate = range;
    }

    public ToggleRangeTemplate(@NonNull String templateId,
            boolean checked,
            @NonNull CharSequence actionDescription,
            @NonNull RangeTemplate range) {
        this(templateId,
                new ControlButton(checked, actionDescription),
                range);
    }

    @Override
    protected Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putParcelable(KEY_BUTTON, mControlButton);
        b.putParcelable(KEY_RANGE, mRangeTemplate);
        return b;
    }

    @NonNull
    public RangeTemplate getRange() {
        return mRangeTemplate;
    }

    public boolean isChecked() {
        return mControlButton.isChecked();
    }

    @NonNull
    public CharSequence getActionDescription() {
        return mControlButton.getActionDescription();
    }

    @Override
    public int getTemplateType() {
        return TYPE;
    }

    public static final Creator<ToggleRangeTemplate> CREATOR = new Creator<ToggleRangeTemplate>() {

        @Override
        public ToggleRangeTemplate createFromParcel(Parcel source) {
            int type = source.readInt();
            verifyType(type, TYPE);
            return new ToggleRangeTemplate(source.readBundle());
        }

        @Override
        public ToggleRangeTemplate[] newArray(int size) {
            return new ToggleRangeTemplate[size];
        }
    };
}
