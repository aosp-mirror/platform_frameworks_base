/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.test.soundtrigger;

import java.util.Random;
import java.util.UUID;

import android.app.Activity;
import android.hardware.soundtrigger.SoundTrigger;
import android.hardware.soundtrigger.SoundTrigger.GenericSoundModel;
import android.media.soundtrigger.SoundTriggerManager;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

public class TestSoundTriggerActivity extends Activity {
    private static final String TAG = "TestSoundTriggerActivity";
    private static final boolean DBG = true;

    private SoundTriggerUtil mSoundTriggerUtil;
    private Random mRandom;
    private UUID mModelUuid = UUID.randomUUID();
    private UUID mModelUuid2 = UUID.randomUUID();
    private UUID mVendorUuid = UUID.randomUUID();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (DBG) Log.d(TAG, "onCreate");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        mSoundTriggerUtil = new SoundTriggerUtil(this);
        mRandom = new Random();
    }

    /**
     * Called when the user clicks the enroll button.
     * Performs a fresh enrollment.
     */
    public void onEnrollButtonClicked(View v) {
        // Generate a fake model to push.
        byte[] data = new byte[1024];
        mRandom.nextBytes(data);
        GenericSoundModel model = new GenericSoundModel(mModelUuid, mVendorUuid, data);

        boolean status = mSoundTriggerUtil.addOrUpdateSoundModel(model);
        if (status) {
            Toast.makeText(
                    this, "Successfully created sound trigger model UUID=" + mModelUuid, Toast.LENGTH_SHORT)
                    .show();
        } else {
            Toast.makeText(this, "Failed to enroll!!!" + mModelUuid, Toast.LENGTH_SHORT).show();
        }

        // Test the SoundManager API.
        SoundTriggerManager.Model tmpModel = SoundTriggerManager.Model.create(mModelUuid2,
                mVendorUuid, data);
        mSoundTriggerUtil.addOrUpdateSoundModel(tmpModel);
    }

    /**
     * Called when the user clicks the un-enroll button.
     * Clears the enrollment information for the user.
     */
    public void onUnEnrollButtonClicked(View v) {
        GenericSoundModel soundModel = mSoundTriggerUtil.getSoundModel(mModelUuid);
        if (soundModel == null) {
            Toast.makeText(this, "Sound model not found!!!", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean status = mSoundTriggerUtil.deleteSoundModel(mModelUuid);
        if (status) {
            Toast.makeText(this, "Successfully deleted model UUID=" + soundModel.uuid,
                    Toast.LENGTH_SHORT)
                    .show();
        } else {
            Toast.makeText(this, "Failed to delete sound model!!!", Toast.LENGTH_SHORT).show();
        }
        mSoundTriggerUtil.deleteSoundModelUsingManager(mModelUuid2);
    }

    /**
     * Called when the user clicks the re-enroll button.
     * Uses the previously enrolled sound model and makes changes to it before pushing it back.
     */
    public void onReEnrollButtonClicked(View v) {
        GenericSoundModel soundModel = mSoundTriggerUtil.getSoundModel(mModelUuid);
        if (soundModel == null) {
            Toast.makeText(this, "Sound model not found!!!", Toast.LENGTH_SHORT).show();
            return;
        }
        // Generate a fake model to push.
        byte[] data = new byte[2048];
        mRandom.nextBytes(data);
        GenericSoundModel updated = new GenericSoundModel(soundModel.uuid,
                soundModel.vendorUuid, data);
        boolean status = mSoundTriggerUtil.addOrUpdateSoundModel(updated);
        if (status) {
            Toast.makeText(this, "Successfully re-enrolled, model UUID=" + updated.uuid,
                    Toast.LENGTH_SHORT)
                    .show();
        } else {
            Toast.makeText(this, "Failed to re-enroll!!!", Toast.LENGTH_SHORT).show();
        }
    }
}
