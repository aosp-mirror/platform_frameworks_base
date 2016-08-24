/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.tuner;


import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.support.v14.preference.PreferenceFragment;
import android.support.v7.preference.Preference;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.SeekBar;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.statusbar.policy.NightModeController;

public class ColorAndAppearanceFragment extends PreferenceFragment {

    private static final String KEY_CALIBRATE = "calibrate";

    private static final long RESET_DELAY = 10000;
    private static final CharSequence KEY_NIGHT_MODE = "night_mode";

    private NightModeController mNightModeController;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mNightModeController = new NightModeController(getContext());
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.color_and_appearance);
    }

    @Override
    public void onResume() {
        super.onResume();
        MetricsLogger.visibility(getContext(), MetricsEvent.TUNER_COLOR_AND_APPEARANCE, true);
        // TODO: Figure out better title model for Tuner, to avoid any more of this.
        getActivity().setTitle(R.string.color_and_appearance);

        Preference nightMode = findPreference(KEY_NIGHT_MODE);
        nightMode.setSummary(mNightModeController.isEnabled()
                ? R.string.night_mode_on : R.string.night_mode_off);
    }

    @Override
    public void onPause() {
        super.onPause();
        MetricsLogger.visibility(getContext(), MetricsEvent.TUNER_COLOR_AND_APPEARANCE, false);
    }

    @Override
    public void onDisplayPreferenceDialog(Preference preference) {
        if (preference instanceof CalibratePreference) {
            CalibrateDialog.show(this);
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }

    private void startRevertTimer() {
        getView().postDelayed(mResetColorMatrix, RESET_DELAY);
    }

    private void onApply() {
        MetricsLogger.action(getContext(), MetricsEvent.ACTION_TUNER_CALIBRATE_DISPLAY_CHANGED);
        mNightModeController.setCustomValues(Settings.Secure.getString(
                getContext().getContentResolver(), Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX));
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

    public static class CalibrateDialog extends DialogFragment implements
            DialogInterface.OnClickListener {
        private float[] mValues;
        private NightModeController mNightModeController;

        public static void show(ColorAndAppearanceFragment fragment) {
            CalibrateDialog dialog = new CalibrateDialog();
            dialog.setTargetFragment(fragment, 0);
            dialog.show(fragment.getFragmentManager(), "Calibrate");
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mNightModeController = new NightModeController(getContext());
            String customValues = mNightModeController.getCustomValues();
            if (customValues == null) {
                // Generate this as a string because its the easiest way to generate a copy of the
                // identity.
                customValues = NightModeController.toString(NightModeController.IDENTITY_MATRIX);
            }
            mValues = NightModeController.toValues(customValues);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            View v = LayoutInflater.from(getContext()).inflate(R.layout.calibrate_sliders, null);
            bindView(v.findViewById(R.id.r_group), 0);
            bindView(v.findViewById(R.id.g_group), 5);
            bindView(v.findViewById(R.id.b_group), 10);
            MetricsLogger.visible(getContext(), MetricsEvent.TUNER_CALIBRATE_DISPLAY);
            return new AlertDialog.Builder(getContext())
                    .setTitle(R.string.calibrate_display)
                    .setView(v)
                    .setPositiveButton(R.string.color_apply, this)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
        }

        @Override
        public void onDismiss(DialogInterface dialog) {
            super.onDismiss(dialog);
            MetricsLogger.hidden(getContext(), MetricsEvent.TUNER_CALIBRATE_DISPLAY);
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
        public void onClick(DialogInterface dialog, int which) {
            if (mValues[0] == 1 && mValues[5] == 1 && mValues[10] == 1) {
                // Allow removal of matrix by all values set to highest.
                mNightModeController.setCustomValues(null);
                return;
            }
            ((ColorAndAppearanceFragment) getTargetFragment()).startRevertTimer();
            Settings.Secure.putString(getContext().getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX,
                    NightModeController.toString(mValues));
            RevertWarning.show((ColorAndAppearanceFragment) getTargetFragment());
        }
    }

    public static class RevertWarning extends DialogFragment
            implements DialogInterface.OnClickListener {

        public static void show(ColorAndAppearanceFragment fragment) {
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
            ((ColorAndAppearanceFragment) getTargetFragment()).onRevert();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            ((ColorAndAppearanceFragment) getTargetFragment()).onApply();
        }
    }
}
