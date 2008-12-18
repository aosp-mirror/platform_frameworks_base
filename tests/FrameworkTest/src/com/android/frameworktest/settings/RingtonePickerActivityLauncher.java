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

package com.android.frameworktest.settings;

import com.android.internal.app.RingtonePickerActivity;

import android.app.Activity;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;

/**
 * Activity that will launch the RingtonePickerActivity as a subactivity, and
 * waits for its result.
 */
public class RingtonePickerActivityLauncher extends Activity {

    private static final String TAG = "RingtonePickerActivityLauncher";
    
    public boolean resultReceived = false;
    
    public int resultCode;
    public Intent result;

    public Uri pickedUri;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        setContentView(android.R.layout.simple_list_item_1);
    }

    /**
     * Launches the {@link RingtonePickerActivity} and blocks until it returns.
     * 
     * @param showDefault {@link RingtonePickerActivity#EXTRA_SHOW_DEFAULT}
     * @param existingUri {@link RingtonePickerActivity#EXTRA_EXISTING_URI}
     * @param filterColumns {@link RingtonePickerActivity#EXTRA_RINGTONE_COLUMNS}
     */
    public void launchRingtonePickerActivity(boolean showDefault, Uri existingUri,
            int types) {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, showDefault);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, types);
        startActivityForResult(intent, 0);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
       
        resultReceived = true;

        this.resultCode = resultCode;
        this.result = data;
        
        if (data != null) {
            this.pickedUri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        }
    }
    
}
