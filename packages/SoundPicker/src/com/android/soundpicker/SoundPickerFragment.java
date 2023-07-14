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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.common.util.concurrent.FutureCallback;

import org.jetbrains.annotations.NotNull;

/**
 * A fragment that displays a picker used to select sound or silent. It also includes the
 * ability to add custom sounds.
 */
public class SoundPickerFragment extends BasePickerFragment {

    private static final String TAG = "SoundPickerFragment";

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
                        mRingtonePickerViewModel.addSoundRingtoneAsync(data.getData(),
                                mPickerConfig.ringtoneType,
                                mAddCustomRingtoneCallback,
                                // Causes the callback to be executed on the main thread.
                                ContextCompat.getMainExecutor(
                                        requireActivity().getApplicationContext()));
                    }
                }
            });

    @Override
    public void onViewCreated(@NotNull View view, Bundle savedInstanceState) {
        mRingtonePickerViewModel = new ViewModelProvider(requireActivity()).get(
                RingtonePickerViewModel.class);
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    protected RingtoneListHandler getRingtoneListHandler() {
        return mRingtonePickerViewModel.getSoundListHandler();
    }

    @Override
    protected void addRingtoneAsync() {
        // The "Add new ringtone" item was clicked. Start a file picker intent to
        // select only audio files (MIME type "audio/*")
        final Intent chooseFile = getMediaFilePickerIntent();
        mActivityResultLauncher.launch(chooseFile);
    }

    @Override
    protected void addNewRingtoneItem() {
        // If external storage is available, add a button to install sounds from storage.
        if (resolvesMediaFilePicker()
                && Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            mRingtoneListViewAdapter.addTitleForAddRingtoneItem(
                    RingtonePickerViewModel.getAddNewItemTextByType(mPickerConfig.ringtoneType));
        }
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
}
