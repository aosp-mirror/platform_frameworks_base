/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.tuner;

import android.annotation.Nullable;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v14.preference.PreferenceFragment;
import android.support.v14.preference.SwitchPreference;
import android.support.v7.preference.DropDownPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceViewHolder;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QSTileHost;

import java.util.Objects;

public class ColorMatrixFragment extends PreferenceFragment implements TunerService.Tunable {

    private static final String TAG = "ColorMatrixFragment";

    public static final int CUSTOM_INDEX = 2;

    // Night mode ~= 3400 K
    private static final float[] NIGHT_VALUES = new float[] {
        1, 0,     0,     0,
        0, .754f, 0,     0,
        0, 0,     .516f, 0,
        0, 0,     0,     1,
    };
    public static final float[] IDENTITY_MATRIX = new float[]{
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1,
    };
    private static final long RESET_DELAY = 10000;

    private boolean mCustomEnabled;
    private DropDownPreference mSelectPreference;
    private String mCurrentValue;
    private String mCustomValues;
    private SwitchPreference mEnableCustomPreference;
    private MatrixPreference mCustomPreference;
    private SwitchPreference mShowQs;
    private String mTiles;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getContext();
        TunerService.get(context).addTunable(this, ColorMatrixTile.COLOR_MATRIX_CUSTOM_ENABLED,
                ColorMatrixTile.COLOR_MATRIX_CUSTOM_VALUES, QSTileHost.TILES_SETTING,
                Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final Context context = getPreferenceManager().getContext();
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(context));

        mSelectPreference = new DropDownPreference(context);
        mSelectPreference.setTitle(R.string.color_transform);
        mSelectPreference.setSummary("%s");
        mSelectPreference.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                int index = Integer.parseInt((String) newValue);
                Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX, getValues()[index]);
                return true;
            }
        });
        getPreferenceScreen().addPreference(mSelectPreference);

        mShowQs = new SwitchPreference(context);
        mShowQs.setTitle(R.string.color_matrix_show_qs);
        mShowQs.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                boolean showTile = (Boolean) newValue;
                String newTiles;
                if (showTile) {
                    newTiles = mTiles != null ? mTiles + "," + ColorMatrixTile.COLOR_MATRIX_SPEC
                            : "default," + ColorMatrixTile.COLOR_MATRIX_SPEC;
                } else {
                    newTiles =
                            mTiles.replace(mTiles.contains(ColorMatrixTile.COLOR_MATRIX_SPEC+ ",")
                            ? ColorMatrixTile.COLOR_MATRIX_SPEC + ","
                            : "," + ColorMatrixTile.COLOR_MATRIX_SPEC, "");
                }
                Settings.Secure.putString(context.getContentResolver(), QSTileHost.TILES_SETTING,
                        newTiles);
                return true;
            }
        });
        getPreferenceScreen().addPreference(mShowQs);

        mEnableCustomPreference = new SwitchPreference(context);
        mEnableCustomPreference.setTitle(R.string.color_enable_custom);
        mEnableCustomPreference.setOnPreferenceChangeListener(
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                boolean enabled = (Boolean) newValue;
                Settings.Secure.putInt(context.getContentResolver(),
                        ColorMatrixTile.COLOR_MATRIX_CUSTOM_ENABLED, enabled ? 1 : 0);
                return true;
            }
        });
        getPreferenceScreen().addPreference(mEnableCustomPreference);

        mCustomPreference = new MatrixPreference(context);
        getPreferenceScreen().addPreference(mCustomPreference);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        TunerService.get(getContext()).removeTunable(this);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (ColorMatrixTile.COLOR_MATRIX_CUSTOM_ENABLED.equals(key)) {
            mCustomEnabled = newValue != null && Integer.parseInt(newValue) != 0;
            mEnableCustomPreference.setChecked(mCustomEnabled);
            mCustomPreference.setEnabled(mCustomEnabled);
            updateSelectOptions();
        } else if (ColorMatrixTile.COLOR_MATRIX_CUSTOM_VALUES.equals(key)) {
            mCustomValues = newValue;
            mCustomPreference.setValues(mCustomValues);
            updateSelectOptions();
        } else if (QSTileHost.TILES_SETTING.equals(key)) {
            mTiles = newValue;
            boolean hasTile = newValue != null
                    && newValue.contains(ColorMatrixTile.COLOR_MATRIX_SPEC);
            mShowQs.setChecked(hasTile);
        } else {
            mCurrentValue = newValue;
            updateSelectOptions();
        }
    }

    private void updateSelectOptions() {
        final int N = CUSTOM_INDEX + (mCustomEnabled ? 1 : 0);
        String[] values = new String[N];
        CharSequence[] totalNames = getColorTitles(getContext());
        CharSequence[] names = new CharSequence[N];
        for (int i = 0; i < N; i++) {
            values[i] = String.valueOf(i);
            names[i] = totalNames[i];
        }
        mSelectPreference.setEntries(names);
        mSelectPreference.setEntryValues(values);
        String[] entries = getValues();
        for (int i = 0; i < values.length; i++) {
            if (Objects.equals(entries[i], mCurrentValue)) {
                mSelectPreference.setValueIndex(i);
                return;
            }
        }
        mSelectPreference.setSummary(R.string.color_matrix_unknown);
        return;
    }

    private String[] getValues() {
        String[] ret = getColorTransforms();
        // Fill in custom based on tuner settings.
        ret[CUSTOM_INDEX] = mCustomValues;
        return ret;
    }

    private void startRevertTimer() {
        getView().postDelayed(mResetColorMatrix, RESET_DELAY);
    }

    private void onApply() {
        Settings.Secure.putString(getContext().getContentResolver(),
                ColorMatrixTile.COLOR_MATRIX_CUSTOM_VALUES, mCurrentValue);
        getView().removeCallbacks(mResetColorMatrix);
    }

    private void onRevert() {
        getView().removeCallbacks(mResetColorMatrix);
        mResetColorMatrix.run();
    }

    public static String[] getColorTransforms() {
        return new String[] {
                null,
                toString(NIGHT_VALUES),
                null, // Blank spot for custom values
                null, // Unknown
        };
    }

    public static CharSequence[] getColorTitles(Context context) {
        return new CharSequence[] {
                context.getString(R.string.color_matrix_none),
                context.getString(R.string.color_matrix_night),
                context.getString(R.string.color_matrix_custom),
                context.getString(R.string.color_matrix_unknown),
        };
    }

    private static String toString(float[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (builder.length() != 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.toString();
    }

    private final Runnable mResetColorMatrix = new Runnable() {
        @Override
        public void run() {
            ((DialogFragment) getFragmentManager().findFragmentByTag("RevertWarning")).dismiss();
            Settings.Secure.putString(getContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX, null);
        }
    };

    private class MatrixPreference extends Preference implements View.OnClickListener {
        private float[] mValues;

        public MatrixPreference(Context context) {
            super(context);
            setLayoutResource(R.layout.preference_matrix);
        }

        public void setValues(String customValues) {
            if (customValues == null) {
                mValues = IDENTITY_MATRIX;
            } else {
                String[] strValues = customValues.split(",");
                mValues = new float[strValues.length];
                for (int i = 0; i < mValues.length; i++) {
                    mValues[i] = Float.parseFloat(strValues[i]);
                }
            }
            notifyChanged();
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            ViewGroup vg = (ViewGroup) holder.itemView.findViewById(R.id.edit_group);
            if (mValues == null) {
                return;
            }
            int childIndex = 0;
            for (int i = 0; i < mValues.length; i++) {
                final int index = i;
                while (!(vg.getChildAt(childIndex) instanceof EditText)) {
                    childIndex++;
                }
                final EditText editText = (EditText) vg.getChildAt(childIndex++);
                editText.setText(String.valueOf(mValues[i]));
                editText.addTextChangedListener(new TextWatcher() {
                    @Override
                    public void afterTextChanged(Editable s) {
                        if (TextUtils.isEmpty(s.toString())) {
                            return;
                        }
                        try {
                            mValues[index] = Float.parseFloat(s.toString());
                        } catch (NumberFormatException e) {
                            mValues[index] = 0;
                        }
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                    }

                    @Override
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                    }
                });
            }
            ((Button) holder.itemView.findViewById(R.id.apply)).setOnClickListener(this);
        }

        @Override
        public void onClick(View v) {
            Settings.Secure.putString(getContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX,
                    ColorMatrixFragment.toString(mValues));
            RevertWarning.show(ColorMatrixFragment.this);
        }

    }

    public static class RevertWarning extends DialogFragment
            implements DialogInterface.OnClickListener {

        public static void show(ColorMatrixFragment fragment) {
            RevertWarning warning = new RevertWarning();
            warning.setTargetFragment(fragment, 0);
            warning.show(fragment.getFragmentManager(), "RevertWarning");
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            AlertDialog alertDialog = new AlertDialog.Builder(getContext())
                    .setTitle(R.string.color_revert_title)
                    .setMessage(R.string.color_revert_message)
                    .setPositiveButton(R.string.ok, this)
                    .create();
            alertDialog.setCanceledOnTouchOutside(true);
            return alertDialog;
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            super.onCancel(dialog);
            ((ColorMatrixFragment) getTargetFragment()).onRevert();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            ((ColorMatrixFragment) getTargetFragment()).onApply();
        }
    }
}
