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

import android.app.Activity;
import android.content.ContentProvider;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.util.concurrent.FutureCallback;

import dagger.hilt.android.AndroidEntryPoint;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * A fragment that will display a picker used to select sound or silent. It also includes the
 * ability to add custom sounds.
 */
@AndroidEntryPoint(Fragment.class)
public class SoundPickerFragment extends Hilt_SoundPickerFragment {

    private static final String TAG = "SoundPickerFragment";
    private static final String COLUMN_LABEL = MediaStore.Audio.Media.TITLE;
    private static final int POS_UNKNOWN = -1;

    private RingtonePickerViewModel.PickerConfig mPickerConfig;
    private boolean mIsManagedProfile;
    private RingtonePickerViewModel mRingtonePickerViewModel;
    private RingtoneAdapter mRingtoneAdapter;
    private RecyclerView mSoundRecyclerView;

    private final RingtoneAdapter.WorkRingtoneProvider mWorkRingtoneProvider =
            new RingtoneAdapter.WorkRingtoneProvider() {
                private Drawable mWorkIconDrawable;
                @Override
                public boolean isWorkRingtone(int position) {
                    if (mIsManagedProfile) {
                        /*
                         * Display the w ork icon if the ringtone belongs to a work profile. We
                         * can tell that a ringtone belongs to a work profile if the picker user
                         * is a managed profile, the ringtone Uri is in external storage, and
                         * either the uri has no user id or has the id of the picker user
                         */
                        Uri currentUri = mRingtonePickerViewModel.getRingtoneUri(position);
                        int uriUserId = ContentProvider.getUserIdFromUri(currentUri,
                                mPickerConfig.userId);
                        Uri uriWithoutUserId = ContentProvider.getUriWithoutUserId(currentUri);

                        return uriUserId == mPickerConfig.userId
                                && uriWithoutUserId.toString().startsWith(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI.toString());
                    }

                    return false;
                }

                @Override
                public Drawable getWorkIconDrawable() {
                    if (mWorkIconDrawable == null) {
                        mWorkIconDrawable = requireActivity().getPackageManager()
                                .getUserBadgeForDensityNoBackground(
                                        UserHandle.of(mPickerConfig.userId), /* density= */ -1);
                    }

                    return mWorkIconDrawable;
                }
            };

    private final FutureCallback<Uri> mAddCustomRingtoneCallback = new FutureCallback<>() {
        @Override
        public void onSuccess(Uri ringtoneUri) {
            requeryForAdapter();
        }

        @Override
        public void onFailure(Throwable throwable) {
            Log.e(TAG, "Failed to add custom ringtone.", throwable);
            // Ringtone was not added, display error Toast
            Toast.makeText(requireActivity().getApplicationContext(),
                    R.string.unable_to_add_ringtone, Toast.LENGTH_SHORT).show();
        }
    };

    ActivityResultLauncher<Intent> mActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        // There are no request codes
                        Intent data = result.getData();
                        mRingtonePickerViewModel.addRingtoneAsync(data.getData(),
                                mPickerConfig.ringtoneType,
                                mAddCustomRingtoneCallback,
                                // Causes the callback to be executed on the main thread.
                                ContextCompat.getMainExecutor(
                                        requireActivity().getApplicationContext()));
                    }
                }
            });

    private final RingtoneAdapter.RingtoneSelectionListener mRingtoneSelectionListener =
            new RingtoneAdapter.RingtoneSelectionListener() {
                @Override
                public void onRingtoneSelected(int position) {
                    SoundPickerFragment.this.setSelectedItem(position);

                    // In the buttonless (watch-only) version, preemptively set our result since
                    // we won't have another chance to do so before the activity closes.
                    if (!mPickerConfig.showOkCancelButtons) {
                        setSuccessResultWithRingtone(
                                mRingtonePickerViewModel.getCurrentlySelectedRingtoneUri());
                    }

                    // Play clip
                    playRingtone(position);
                }

                @Override
                public void onAddRingtoneSelected() {
                    // The "Add new ringtone" item was clicked. Start a file picker intent to
                    // select only audio files (MIME type "audio/*")
                    final Intent chooseFile = getMediaFilePickerIntent();
                    mActivityResultLauncher.launch(chooseFile);
                }
            };

    public SoundPickerFragment() {
        super(R.layout.fragment_sound_picker);
    }

    @Override
    public void onViewCreated(@NotNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mRingtonePickerViewModel = new ViewModelProvider(requireActivity()).get(
                RingtonePickerViewModel.class);
        mSoundRecyclerView = view.findViewById(R.id.recycler_view);
        Objects.requireNonNull(mSoundRecyclerView);

        mPickerConfig = mRingtonePickerViewModel.getPickerConfig();
        mIsManagedProfile = UserManager.get(requireActivity()).isManagedProfile(
                mPickerConfig.userId);

        mRingtoneAdapter = createRingtoneAdapter();
        mSoundRecyclerView.setHasFixedSize(true);
        mSoundRecyclerView.setAdapter(mRingtoneAdapter);
        mSoundRecyclerView.setLayoutManager(new LinearLayoutManager(requireActivity()));
        setSelectedItem(mRingtonePickerViewModel.getSelectedItemPosition());
        prepareRecyclerView(mSoundRecyclerView);
    }

    private void prepareRecyclerView(@NonNull RecyclerView recyclerView) {
        // Reset the static item count, as this method can be called multiple times
        mRingtonePickerViewModel.resetFixedItemCount();

        if (mPickerConfig.hasDefaultItem) {
            int defaultItemPos = addDefaultRingtoneItem();

            if (getSelectedItem() == POS_UNKNOWN
                    && RingtoneManager.isDefault(mPickerConfig.existingUri)) {
                setSelectedItem(defaultItemPos);
            }
        }

        if (mPickerConfig.hasSilentItem) {
            int silentItemPos = addSilentItem();

            // The 'Silent' item should use a null Uri
            if (getSelectedItem() == POS_UNKNOWN && mPickerConfig.existingUri == null) {
                setSelectedItem(silentItemPos);
            }
        }

        if (getSelectedItem() == POS_UNKNOWN) {
            setSelectedItem(
                    mRingtonePickerViewModel.getRingtonePosition(mPickerConfig.existingUri));
        }

        // In the buttonless (watch-only) version, preemptively set our result since we won't
        // have another chance to do so before the activity closes.
        if (!mPickerConfig.showOkCancelButtons) {
            setSuccessResultWithRingtone(
                    mRingtonePickerViewModel.getCurrentlySelectedRingtoneUri());
        }
        // If external storage is available, add a button to install sounds from storage.
        if (resolvesMediaFilePicker()
                && Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            addNewSoundItem();
        }

        // Enable context menu in ringtone items
        registerForContextMenu(recyclerView);
    }

    /**
     * Re-query RingtoneManager for the most recent set of installed ringtones. May move the
     * selected item position to match the new position of the chosen sound.
     * <p>
     * This should only need to happen after adding or removing a ringtone.
     */
    private void requeryForAdapter() {
        // Refresh and set a new cursor, closing the old one.
        mRingtonePickerViewModel.init(mPickerConfig);
        mRingtoneAdapter = createRingtoneAdapter();
        mSoundRecyclerView.setAdapter(mRingtoneAdapter);
        prepareRecyclerView(mSoundRecyclerView);

        // Update selected item location.
        int selectedPosition = POS_UNKNOWN;
        for (int i = 0; i < mRingtoneAdapter.getItemCount(); i++) {
            if (mRingtoneAdapter.getItemId(i) == mRingtonePickerViewModel.getSelectedItemId()) {
                selectedPosition = i;
                break;
            }
        }
        if (mPickerConfig.hasSilentItem && selectedPosition == POS_UNKNOWN) {
            selectedPosition = mRingtonePickerViewModel.getSilentItemPosition();
        }
        setSelectedItem(selectedPosition);
    }

    private void playRingtone(int position) {
        mRingtonePickerViewModel.setSampleItemPosition(position);
        mRingtonePickerViewModel.playRingtone(mRingtonePickerViewModel.getSampleItemPosition(),
                mPickerConfig.uriForDefaultItem, mPickerConfig.audioAttributesFlags);
    }

    private boolean resolvesMediaFilePicker() {
        return getMediaFilePickerIntent().resolveActivity(requireActivity().getPackageManager())
                != null;
    }

    private Intent getMediaFilePickerIntent() {
        final Intent chooseFile = new Intent(Intent.ACTION_GET_CONTENT);
        chooseFile.setType("audio/*");
        chooseFile.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"audio/*", "application/ogg"});
        return chooseFile;
    }

    private void setSuccessResultWithRingtone(Uri ringtoneUri) {
        requireActivity().setResult(Activity.RESULT_OK,
                new Intent().putExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, ringtoneUri));
    }

    private int getSelectedItem() {
        return mRingtonePickerViewModel.getSelectedItemPosition();
    }

    private void setSelectedItem(int pos) {
        Objects.requireNonNull(mRingtoneAdapter);
        mRingtonePickerViewModel.setSelectedItemPosition(pos);
        mRingtoneAdapter.setSelectedItem(pos);
        mRingtonePickerViewModel.setSelectedItemId(mRingtoneAdapter.getItemId(pos));
        mSoundRecyclerView.scrollToPosition(pos);
    }

    /**
     * Adds a fixed item to the fixed items list . A fixed item is one that is not from
     * the RingtoneManager.
     *
     * @param textResId The resource ID of the text for the item.
     * @return The position of the inserted item.
     */
    private int addFixedItem(int textResId) {
        mRingtoneAdapter.addTitleForFixedItem(textResId);
        return mRingtonePickerViewModel.incrementAndGetFixedItemCount();
    }

    private int addDefaultRingtoneItem() {
        int defaultRingtoneItemPos = addFixedItem(
                RingtonePickerViewModel.getDefaultRingtoneItemTextByType(
                        mPickerConfig.ringtoneType));
        mRingtonePickerViewModel.setDefaultItemPosition(defaultRingtoneItemPos);
        return defaultRingtoneItemPos;
    }

    private int addSilentItem() {
        int silentItemPos = addFixedItem(com.android.internal.R.string.ringtone_silent);
        mRingtonePickerViewModel.setSilentItemPosition(silentItemPos);
        return silentItemPos;
    }

    private void addNewSoundItem() {
        mRingtoneAdapter.addTitleForAddRingtoneItem(
                RingtonePickerViewModel.getAddNewItemTextByType(mPickerConfig.ringtoneType));
    }

    private RingtoneAdapter createRingtoneAdapter() {
        LocalizedCursor cursor = new LocalizedCursor(
                mRingtonePickerViewModel.getRingtoneCursor(), getResources(), COLUMN_LABEL);
        return new RingtoneAdapter(cursor, mRingtoneSelectionListener, mWorkRingtoneProvider);
    }
}
