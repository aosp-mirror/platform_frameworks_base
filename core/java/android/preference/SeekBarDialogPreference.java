/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.preference;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;

import com.android.internal.R;

/**
 * @hide
 *
 * @deprecated Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 *      <a href="{@docRoot}reference/androidx/preference/package-summary.html">
 *      Preference Library</a> for consistent behavior across all devices. For more information on
 *      using the AndroidX Preference Library see
 *      <a href="{@docRoot}guide/topics/ui/settings.html">Settings</a>.
 */
@Deprecated
public class SeekBarDialogPreference extends DialogPreference {
    private final Drawable mMyIcon;

    public SeekBarDialogPreference(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        createActionButtons();

        // Steal the XML dialogIcon attribute's value
        mMyIcon = getDialogIcon();

        setDialogIcon(null);
    }

    public SeekBarDialogPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    @UnsupportedAppUsage
    public SeekBarDialogPreference(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.seekBarDialogPreferenceStyle);
    }

    public SeekBarDialogPreference(Context context) {
        this(context, null);
    }

    // Allow subclasses to override the action buttons
    public void createActionButtons() {
        setPositiveButtonText(R.string.ok);
        setNegativeButtonText(R.string.cancel);
    }

    @Override
    protected void onBindDialogView(View view) {
        super.onBindDialogView(view);

        final ImageView iconView = (ImageView) view.findViewById(R.id.icon);
        if (mMyIcon != null) {
            iconView.setImageDrawable(mMyIcon);
        } else {
            iconView.setVisibility(View.GONE);
        }
    }

    protected static SeekBar getSeekBar(View dialogView) {
        return (SeekBar) dialogView.findViewById(R.id.seekbar);
    }
}
