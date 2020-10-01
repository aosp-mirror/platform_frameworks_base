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
import android.service.controls.Control;

import com.android.internal.util.Preconditions;

/**
 * A template for a {@link Control} supporting toggling and a range.
 *
 * @see ToggleTemplate
 * @see RangeTemplate
 */
public final class ToggleRangeTemplate extends ControlTemplate {

    private static final @TemplateType int TYPE = TYPE_TOGGLE_RANGE;
    private static final String KEY_BUTTON = "key_button";
    private static final String KEY_RANGE = "key_range";

    private @NonNull final ControlButton mControlButton;
    private @NonNull final RangeTemplate mRangeTemplate;

    /**
     * @param b
     * @hide
     */
    ToggleRangeTemplate(@NonNull Bundle b) {
        super(b);
        mControlButton = b.getParcelable(KEY_BUTTON);
        mRangeTemplate = new RangeTemplate(b.getBundle(KEY_RANGE));
    }

    /**
     * Constructs a new {@link ToggleRangeTemplate}.
     * @param templateId the identifier for this template.
     * @param button a {@link ControlButton} to use for the toggle interface
     * @param range a {@link RangeTemplate} to use for the range interface
     */
    public ToggleRangeTemplate(@NonNull String templateId,
            @NonNull ControlButton button,
            @NonNull RangeTemplate range) {
        super(templateId);
        Preconditions.checkNotNull(button);
        Preconditions.checkNotNull(range);
        mControlButton = button;
        mRangeTemplate = range;
    }

    /**
     * Constructs a new {@link ToggleRangeTemplate}.
     * @param templateId the identifier for this template.
     * @param checked true if the toggle should be rendered as active.
     * @param actionDescription action description for the button.
     * @param range  {@link RangeTemplate} to use for the range interface
     * @see ControlButton
     */
    public ToggleRangeTemplate(@NonNull String templateId,
            boolean checked,
            @NonNull CharSequence actionDescription,
            @NonNull RangeTemplate range) {
        this(templateId,
                new ControlButton(checked, actionDescription),
                range);
    }

    /**
     * @return
     * @hide
     */
    @Override
    @NonNull
    Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putParcelable(KEY_BUTTON, mControlButton);
        b.putBundle(KEY_RANGE, mRangeTemplate.getDataBundle());
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

    /**
     * @return {@link ControlTemplate#TYPE_TOGGLE_RANGE}
     */
    @Override
    public int getTemplateType() {
        return TYPE;
    }
}
