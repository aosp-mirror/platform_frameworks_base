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

package com.android.settingslib.widget.settingsspinner;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Spinner;

import com.android.settingslib.widget.R;

/**
 * A {@link Spinner} with settings style.
 *
 * The items in the SettingsSpinner come from the {@link SettingsSpinnerAdapter} associated with
 * this view.
 */
public class SettingsSpinner extends Spinner {

    /**
     * Constructs a new SettingsSpinner with the given context's theme.
     * And it also set a background resource with settings style.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     */
    public SettingsSpinner(Context context) {
        super(context);
        setBackgroundResource(R.drawable.settings_spinner_background);
    }

    /**
     * Constructs a new SettingsSpinner with the given context's theme and the supplied
     * mode of displaying choices. <code>mode</code> may be one of
     * {@link Spinner#MODE_DIALOG} or {@link Spinner#MODE_DROPDOWN}.
     * And it also set a background resource with settings style.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param mode Constant describing how the user will select choices from
     *             the spinner.
     *
     * @see Spinner#MODE_DIALOG
     * @see Spinner#MODE_DROPDOWN
     */
    public SettingsSpinner(Context context, int mode) {
        super(context, mode);
        setBackgroundResource(R.drawable.settings_spinner_background);
    }

    /**
     * Constructs a new SettingsSpinner with the given context's theme and the supplied
     * attribute set.
     * And it also set a background resource with settings style.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     */
    public SettingsSpinner(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundResource(R.drawable.settings_spinner_background);
    }

    /**
     * Constructs a new SettingsSpinner with the given context's theme, the supplied
     * attribute set, and default style attribute.
     * And it also set a background resource with settings style.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *                     reference to a style resource that supplies default
     *                     values for the view. Can be 0 to not look for
     *                     defaults.
     */
    public SettingsSpinner(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setBackgroundResource(R.drawable.settings_spinner_background);
    }

    /**
     * Constructs a new SettingsSpinner with the given context's theme, the supplied
     * attribute set, and default styles. <code>mode</code> may be one of
     * {@link Spinner#MODE_DIALOG} or {@link Spinner#MODE_DROPDOWN} and determines how the
     * user will select choices from the spinner.
     * And it also set a background resource with settings style.
     *
     * @param context The Context the view is running in, through which it can
     *                access the current theme, resources, etc.
     * @param attrs The attributes of the XML tag that is inflating the view.
     * @param defStyleAttr An attribute in the current theme that contains a
     *                     reference to a style resource that supplies default
     *                     values for the view. Can be 0 to not look for
     *                     defaults.
     * @param defStyleRes A resource identifier of a style resource that
     *                    supplies default values for the view, used only if
     *                    defStyleAttr is 0 or can not be found in the theme.
     *                    Can be 0 to not look for defaults.
     * @param mode Constant describing how the user will select choices from
     *             the spinner.
     *
     * @see Spinner#MODE_DIALOG
     * @see Spinner#MODE_DROPDOWN
     */
    public SettingsSpinner(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes,
            int mode) {
        super(context, attrs, defStyleAttr, defStyleRes, mode, null);
    }
}