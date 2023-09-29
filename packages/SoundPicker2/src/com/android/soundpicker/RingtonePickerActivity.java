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

package com.android.soundpicker;

import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * The {@link RingtonePickerActivity} allows the user to choose one from all of the
 * available ringtones. The chosen ringtone's URI will be persisted as a string.
 *
 * @see RingtoneManager#ACTION_RINGTONE_PICKER
 */
@AndroidEntryPoint(AppCompatActivity.class)
public final class RingtonePickerActivity extends Hilt_RingtonePickerActivity {

    private static final String TAG = "RingtonePickerActivity";
    // TODO: Use the extra keys from RingtoneManager once they're added.
    private static final String EXTRA_RINGTONE_PICKER_CATEGORY = "EXTRA_RINGTONE_PICKER_CATEGORY";
    private static final String EXTRA_VIBRATION_SHOW_DEFAULT = "EXTRA_VIBRATION_SHOW_DEFAULT";
    private static final String EXTRA_VIBRATION_DEFAULT_URI = "EXTRA_VIBRATION_DEFAULT_URI";
    private static final String EXTRA_VIBRATION_SHOW_SILENT = "EXTRA_VIBRATION_SHOW_SILENT";
    private static final String EXTRA_VIBRATION_EXISTING_URI = "EXTRA_VIBRATION_EXISTING_URI";
    private static final boolean RINGTONE_PICKER_CATEGORY_FEATURE_ENABLED = false;

    private RingtonePickerViewModel mRingtonePickerViewModel;
    private int mAttributesFlags;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_ringtone_picker);

        mRingtonePickerViewModel = new ViewModelProvider(this).get(RingtonePickerViewModel.class);

        Intent intent = getIntent();
        /**
         * Id of the user to which the ringtone picker should list the ringtones
         */
        int pickerUserId = UserHandle.myUserId();

        // Get the types of ringtones to show
        int ringtoneType = intent.getIntExtra(RingtoneManager.EXTRA_RINGTONE_TYPE,
                RingtonePickerViewModel.RINGTONE_TYPE_UNKNOWN);

        // AudioAttributes flags
        mAttributesFlags |= intent.getIntExtra(
                RingtoneManager.EXTRA_RINGTONE_AUDIO_ATTRIBUTES_FLAGS,
                0 /*defaultValue == no flags*/);

        boolean showOkCancelButtons = getResources().getBoolean(R.bool.config_showOkCancelButtons);

        String title = intent.getStringExtra(RingtoneManager.EXTRA_RINGTONE_TITLE);
        if (title == null) {
            title = getString(RingtonePickerViewModel.getTitleByType(ringtoneType));
        }
        String ringtonePickerCategory = intent.getStringExtra(EXTRA_RINGTONE_PICKER_CATEGORY);
        RingtonePickerViewModel.PickerType pickerType = mapCategoryToPickerType(
                ringtonePickerCategory);

        RingtoneListHandler.Config soundListConfig = getSoundListConfig(pickerType, intent,
                ringtoneType);
        RingtoneListHandler.Config vibrationListConfig = getVibrationListConfig(pickerType, intent);

        RingtonePickerViewModel.Config pickerConfig =
                new RingtonePickerViewModel.Config(title, pickerUserId, ringtoneType,
                        showOkCancelButtons, mAttributesFlags, pickerType);

        mRingtonePickerViewModel.init(pickerConfig, soundListConfig, vibrationListConfig);

        if (savedInstanceState == null) {
            TabbedDialogFragment dialogFragment = new TabbedDialogFragment();

            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            Fragment prev = getSupportFragmentManager().findFragmentByTag(TabbedDialogFragment.TAG);
            if (prev != null) {
                ft.remove(prev);
            }
            ft.addToBackStack(null);
            dialogFragment.show(ft, TabbedDialogFragment.TAG);
        }

        // The volume keys will control the stream that we are choosing a ringtone for
        setVolumeControlStream(mRingtonePickerViewModel.getRingtoneStreamType());
    }

    private RingtoneListHandler.Config getSoundListConfig(
            RingtonePickerViewModel.PickerType pickerType, Intent intent, int ringtoneType) {
        if (pickerType != RingtonePickerViewModel.PickerType.SOUND_PICKER
                && pickerType != RingtonePickerViewModel.PickerType.RINGTONE_PICKER) {
            // This ringtone picker does not require a sound picker.
            return null;
        }

        // Get whether to show the 'Default' sound item, and the URI to play when it's clicked
        boolean hasDefaultSoundItem =
                intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);

        // The Uri to play when the 'Default' sound item is clicked.
        Uri uriForDefaultSoundItem =
                intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI);
        if (uriForDefaultSoundItem == null) {
            uriForDefaultSoundItem = RingtonePickerViewModel.getDefaultItemUriByType(ringtoneType);
        }

        // Get whether this list has the 'Silent' sound item.
        boolean hasSilentSoundItem =
                intent.getBooleanExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);

        // AudioAttributes flags
        mAttributesFlags |= intent.getIntExtra(
                RingtoneManager.EXTRA_RINGTONE_AUDIO_ATTRIBUTES_FLAGS,
                0 /*defaultValue == no flags*/);

        // Get the sound URI whose list item should have a checkmark
        Uri existingSoundUri = intent
                .getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI);

        return new RingtoneListHandler.Config(hasDefaultSoundItem,
                uriForDefaultSoundItem, hasSilentSoundItem, existingSoundUri);
    }

    private RingtoneListHandler.Config getVibrationListConfig(
            RingtonePickerViewModel.PickerType pickerType, Intent intent) {
        if (pickerType != RingtonePickerViewModel.PickerType.VIBRATION_PICKER
                && pickerType != RingtonePickerViewModel.PickerType.RINGTONE_PICKER) {
            // This ringtone picker does not require a vibration picker.
            return null;
        }

        // Get whether to show the 'Default' vibration item, and the URI to play when it's clicked
        boolean hasDefaultVibrationItem =
                intent.getBooleanExtra(EXTRA_VIBRATION_SHOW_DEFAULT, false);

        // The Uri to play when the 'Default' vibration item is clicked.
        Uri uriForDefaultVibrationItem = intent.getParcelableExtra(EXTRA_VIBRATION_DEFAULT_URI);

        // Get whether this list has the 'Silent' vibration item.
        boolean hasSilentVibrationItem =
                intent.getBooleanExtra(EXTRA_VIBRATION_SHOW_SILENT, true);

        // Get the vibration URI whose list item should have a checkmark
        Uri existingVibrationUri = intent.getParcelableExtra(EXTRA_VIBRATION_EXISTING_URI);

        return new RingtoneListHandler.Config(
                hasDefaultVibrationItem, uriForDefaultVibrationItem, hasSilentVibrationItem,
                existingVibrationUri);
    }

    @Override
    public void onDestroy() {
        mRingtonePickerViewModel.cancelPendingAsyncTasks();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mRingtonePickerViewModel.onStop(isChangingConfigurations());
    }

    @Override
    protected void onPause() {
        super.onPause();
        mRingtonePickerViewModel.onPause(isChangingConfigurations());
    }

    /**
     * Maps the ringtone picker category to the appropriate PickerType.
     * If the category is null or the feature is still not released, then it defaults to sound
     * picker.
     *
     * @param category the ringtone picker category.
     * @return the corresponding picker type.
     */
    private static RingtonePickerViewModel.PickerType mapCategoryToPickerType(String category) {
        if (category == null || !RINGTONE_PICKER_CATEGORY_FEATURE_ENABLED) {
            return RingtonePickerViewModel.PickerType.SOUND_PICKER;
        }

        switch (category) {
            case "android.intent.category.RINGTONE_PICKER_RINGTONE":
                return RingtonePickerViewModel.PickerType.RINGTONE_PICKER;
            case "android.intent.category.RINGTONE_PICKER_SOUND":
                return RingtonePickerViewModel.PickerType.SOUND_PICKER;
            case "android.intent.category.RINGTONE_PICKER_VIBRATION":
                return RingtonePickerViewModel.PickerType.VIBRATION_PICKER;
            default:
                Log.w(TAG, "Unrecognized category: " + category + ". Defaulting to sound picker.");
                return RingtonePickerViewModel.PickerType.SOUND_PICKER;
        }
    }
}
