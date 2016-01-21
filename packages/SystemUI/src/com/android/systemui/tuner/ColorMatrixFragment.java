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
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v14.preference.PreferenceFragment;
import android.support.v14.preference.SwitchPreference;
import android.support.v7.preference.DropDownPreference;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceViewHolder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.Switch;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QSTileHost;
import com.android.systemui.statusbar.policy.DisplayController;

import java.util.Objects;

public class ColorMatrixFragment extends PreferenceFragment implements TunerService.Tunable {

    private static final String TAG = "ColorMatrixFragment";

    private static final long RESET_DELAY = 10000;

    private boolean mCustomEnabled;
    private DropDownPreference mSelectPreference;
    private String mCurrentValue;
    private String mCustomValues;
    private SwitchPreference mEnableCustomPreference;
    private MatrixPreference mCustomPreference;
    private int mState;
    private Switch mSwitch;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getContext();
        TunerService.get(context).addTunable(this, DisplayController.COLOR_MATRIX_CUSTOM_ENABLED,
                DisplayController.COLOR_MATRIX_CUSTOM_VALUES, DisplayController.COLOR_STATE,
                Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view = LayoutInflater.from(getContext()).inflate(
                R.layout.color_matrix_settings, container, false);
        ((ViewGroup) view).addView(super.onCreateView(inflater, container, savedInstanceState));
        return view;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final Context context = getPreferenceManager().getContext();
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(context));

        mSelectPreference = new DropDownPreference(context);
        mSelectPreference.setTitle(R.string.color_transform);
        mSelectPreference.setSummary("%s");
        mSelectPreference.setOnPreferenceChangeListener(
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                if (Objects.equals(newValue, DisplayController.AUTO_STRING)) {
                    Settings.Secure.putInt(context.getContentResolver(),
                            DisplayController.COLOR_STATE,
                            DisplayController.COLOR_STATE_AUTO);
                    return true;
                }
                if (Objects.equals(newValue, DisplayController.NONE_STRING)) {
                    Settings.Secure.putString(context.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX, null);
                    return true;
                }
                Settings.Secure.putInt(context.getContentResolver(),
                        DisplayController.COLOR_STATE,
                        DisplayController.COLOR_STATE_ENABLED);
                final String value = (String) newValue;
                Settings.Secure.putString(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX,
                        value);
                return true;
            }
        });
        getPreferenceScreen().addPreference(mSelectPreference);

        mEnableCustomPreference = new SwitchPreference(context);
        mEnableCustomPreference.setTitle(R.string.color_enable_custom);
        mEnableCustomPreference.setOnPreferenceChangeListener(
                new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                boolean enabled = (Boolean) newValue;
                if (!enabled && Objects.equals(mCurrentValue, mCustomValues)) {
                    Settings.Secure.putString(context.getContentResolver(),
                            Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX, null);
                }
                Settings.Secure.putInt(context.getContentResolver(),
                        DisplayController.COLOR_MATRIX_CUSTOM_ENABLED, enabled ? 1 : 0);
                return true;
            }
        });
        getPreferenceScreen().addPreference(mEnableCustomPreference);

        mCustomPreference = new MatrixPreference(context);
        getPreferenceScreen().addPreference(mCustomPreference);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        View switchBar = view.findViewById(R.id.switch_bar);
        mSwitch = (Switch) switchBar.findViewById(android.R.id.switch_widget);
        mSwitch.setChecked(mState != DisplayController.COLOR_STATE_DISABLED);
        switchBar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int newState = mState != DisplayController.COLOR_STATE_DISABLED
                        ? DisplayController.COLOR_STATE_DISABLED
                        : DisplayController.COLOR_STATE_ENABLED;
                ContentResolver contentResolver = getContext().getContentResolver();
                if (newState == DisplayController.COLOR_STATE_DISABLED) {
                    String tiles = Settings.Secure.getString(contentResolver,
                            QSTileHost.TILES_SETTING);
                    if (tiles != null) {
                        if (tiles.contains(",colors")) {
                            tiles = tiles.replace(",colors", "");
                        } else if (tiles.contains("colors,")) {
                            tiles = tiles.replace("colors,", "");
                        }
                        Settings.Secure.putString(contentResolver, QSTileHost.TILES_SETTING,
                                tiles);
                    }
                }
                Settings.Secure.putInt(contentResolver,
                        DisplayController.COLOR_STATE, newState);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        TunerService.get(getContext()).removeTunable(this);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (DisplayController.COLOR_MATRIX_CUSTOM_ENABLED.equals(key)) {
            mCustomEnabled = newValue != null && Integer.parseInt(newValue) != 0;
            mEnableCustomPreference.setChecked(mCustomEnabled);
            mCustomPreference.setEnabled(mCustomEnabled
                    && mState != DisplayController.COLOR_STATE_DISABLED);
            updateSelectOptions();
        } else if (DisplayController.COLOR_MATRIX_CUSTOM_VALUES.equals(key)) {
            mCustomValues = newValue;
            if (mCustomValues == null) {
                mCustomValues = DisplayController.toString(DisplayController.IDENTITY_MATRIX);
            }
            mCustomPreference.setValues(mCustomValues);
            updateSelectOptions();
        } else if (DisplayController.COLOR_STATE.equals(key)) {
            mState = newValue != null ? Integer.parseInt(newValue) : 0;
            if (mSwitch != null) {
                mSwitch.setChecked(mState != DisplayController.COLOR_STATE_DISABLED);
            }
            mSelectPreference.setEnabled(mState != DisplayController.COLOR_STATE_DISABLED);
            mEnableCustomPreference.setEnabled(mState != DisplayController.COLOR_STATE_DISABLED);
            mCustomPreference.setEnabled(mCustomEnabled
                    && mState != DisplayController.COLOR_STATE_DISABLED);
        } else {
            mCurrentValue = newValue;
            updateSelectOptions();
        }
    }

    private void updateSelectOptions() {
        final int N = DisplayController.CUSTOM_INDEX + (mCustomEnabled ? 1 : 0);
        String[] values = new String[N];
        CharSequence[] names = new CharSequence[N];
        CharSequence[] totalNames = DisplayController.getColorTitles(getContext());
        String[] entries = DisplayController.getColorTransforms(getContext());
        entries[DisplayController.CUSTOM_INDEX] = mCustomValues != null ? mCustomValues : "";
        for (int i = 0; i < N; i++) {
            values[i] = entries[i];
            names[i] = totalNames[i];
        }
        mSelectPreference.setEntries(names);
        mSelectPreference.setEntryValues(values);
        int index = 0;
        if (mState == DisplayController.COLOR_STATE_AUTO) {
            index = DisplayController.AUTO_INDEX;
        } else if (mCustomValues != null && Objects.equals(mCurrentValue, mCustomValues)) {
            index = DisplayController.CUSTOM_INDEX;
        } else if (Objects.equals(mCurrentValue, entries[1])) {
            index = 1;
        }
        mSelectPreference.setValueIndex(index);
        mSelectPreference.setSummary("%s");
        return;
    }

    private void startRevertTimer() {
        getView().postDelayed(mResetColorMatrix, RESET_DELAY);
    }

    private void onApply() {
        Settings.Secure.putString(getContext().getContentResolver(),
                DisplayController.COLOR_MATRIX_CUSTOM_VALUES, mCurrentValue);
        getView().removeCallbacks(mResetColorMatrix);
    }

    private void onRevert() {
        getView().removeCallbacks(mResetColorMatrix);
        mResetColorMatrix.run();
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
            String[] strValues = customValues.split(",");
            mValues = new float[strValues.length];
            for (int i = 0; i < mValues.length; i++) {
                mValues[i] = Float.parseFloat(strValues[i]);
            }
            notifyChanged();
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            bindView(holder.findViewById(R.id.r_group), 0);
            bindView(holder.findViewById(R.id.g_group), 5);
            bindView(holder.findViewById(R.id.b_group), 10);
            holder.findViewById(R.id.apply).setOnClickListener(this);
        }

        private void bindView(View view, final int index) {
            SeekBar seekBar = (SeekBar) view.findViewById(com.android.internal.R.id.seekbar);
            seekBar.setMax(1000);
            seekBar.setProgress((int) (1000 * mValues[index]));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    mValues[index] = progress / 1000f;
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }

        @Override
        public void onClick(View v) {
            startRevertTimer();
            Settings.Secure.putString(getContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX,
                    DisplayController.toString(mValues));
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
