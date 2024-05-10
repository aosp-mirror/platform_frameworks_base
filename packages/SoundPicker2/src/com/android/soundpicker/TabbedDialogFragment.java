/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.app.Activity.RESULT_CANCELED;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.media.RingtoneManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import dagger.hilt.android.AndroidEntryPoint;

import org.jetbrains.annotations.NotNull;

/**
 * A dialog fragment with a sound and/or vibration tab based on the picker type.
 * <ul>
 * <li> Ringtone Pickers will display both sound and vibration tabs.
 * <li> Sound Pickers will only display the sound tab.
 * <li> Vibration Pickers will only display the vibration tab.
 * </ul>
 */
@AndroidEntryPoint(DialogFragment.class)
public class TabbedDialogFragment extends Hilt_TabbedDialogFragment {

    static final String TAG = "TabbedDialogFragment";

    private RingtonePickerViewModel mRingtonePickerViewModel;

    private final ViewPager2.OnPageChangeCallback mOnPageChangeCallback =
            new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageScrollStateChanged(int state) {
                    super.onPageScrollStateChanged(state);
                    if (state == ViewPager2.SCROLL_STATE_IDLE) {
                        mRingtonePickerViewModel.onStop(/* isChangingConfigurations= */ false);
                    }
                }
            };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRingtonePickerViewModel = new ViewModelProvider(requireActivity()).get(
                RingtonePickerViewModel.class);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(requireActivity(),
                android.R.style.ThemeOverlay_Material_Dialog)
                .setTitle(mRingtonePickerViewModel.getPickerConfig().title);
        // Do not show OK/Cancel buttons in the buttonless (watch-only) version.
        if (mRingtonePickerViewModel.getPickerConfig().showOkCancelButtons) {
            dialogBuilder
                    .setPositiveButton(getString(com.android.internal.R.string.ok),
                            (dialog, whichButton) -> {
                                setSuccessResultWithSelectedRingtone();
                                requireActivity().finish();
                            })
                    .setNegativeButton(getString(com.android.internal.R.string.cancel),
                            (dialog, whichButton) -> {
                                requireActivity().setResult(RESULT_CANCELED);
                                requireActivity().finish();
                            });
        }

        View view = buildTabbedView(requireActivity().getLayoutInflater());
        dialogBuilder.setView(view);

        return dialogBuilder.create();
    }

    @Override
    public void onCancel(@NonNull @NotNull DialogInterface dialog) {
        super.onCancel(dialog);
        if (!requireActivity().isChangingConfigurations()) {
            requireActivity().finish();
        }
    }

    @Override
    public void onDismiss(@NonNull @NotNull DialogInterface dialog) {
        super.onDismiss(dialog);
        if (!requireActivity().isChangingConfigurations()) {
            requireActivity().finish();
        }
    }

    private void setSuccessResultWithSelectedRingtone() {
        requireActivity().setResult(Activity.RESULT_OK,
                new Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI,
                        mRingtonePickerViewModel.getSelectedRingtoneUri()));
    }

    /**
     * Inflates the tabbed layout view and adds the required fragments. If there's only one
     * fragment to display, then the tab area is hidden.
     * @param inflater The LayoutInflater that is used to inflate the tabbed view.
     * @return The tabbed view.
     */
    private View buildTabbedView(@NonNull LayoutInflater inflater) {
        View view = inflater.inflate(R.layout.fragment_tabbed_dialog, null, false);
        TabLayout tabLayout = view.requireViewById(R.id.tabLayout);
        ViewPager2 viewPager = view.requireViewById(R.id.masterViewPager);

        ViewPagerAdapter adapter = new ViewPagerAdapter(requireActivity());
        addFragments(adapter);

        if (adapter.getItemCount() == 1) {
            // Hide the tab area since there's only one fragment to display.
            tabLayout.setVisibility(View.GONE);
        }

        viewPager.setAdapter(adapter);
        viewPager.registerOnPageChangeCallback(mOnPageChangeCallback);
        new TabLayoutMediator(tabLayout, viewPager,
                (tab, position) -> tab.setText(adapter.getTitle(position))).attach();

        return view;
    }

    /**
     * Adds the appropriate fragments to the adapter based on the PickerType.
     *
     * @param adapter The adapter to add the fragments to.
     */
    private void addFragments(ViewPagerAdapter adapter) {
        switch (mRingtonePickerViewModel.getPickerConfig().mPickerType) {
            case RINGTONE_PICKER:
                adapter.addFragment(getString(R.string.sound_page_title),
                        new SoundPickerFragment());
                adapter.addFragment(getString(R.string.vibration_page_title),
                        new VibrationPickerFragment());
                break;
            case SOUND_PICKER:
                adapter.addFragment(getString(R.string.sound_page_title),
                        new SoundPickerFragment());
                break;
            case VIBRATION_PICKER:
                adapter.addFragment(getString(R.string.vibration_page_title),
                        new VibrationPickerFragment());
                break;
            default:
                adapter.addFragment(getString(R.string.sound_page_title),
                        new SoundPickerFragment());
                break;
        }
    }
}
