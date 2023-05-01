/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.packageinstaller;

import static com.android.packageinstaller.PackageInstallerActivity.EXTRA_STAGED_SESSION_ID;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

/**
 * Trampoline activity. Calls PackageInstallerActivity and deletes staged install file onResult.
 */
public class DeleteStagedFileOnResult extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            Intent installIntent = new Intent(getIntent());
            installIntent.setClass(this, PackageInstallerActivity.class);

            installIntent.setFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivityForResult(installIntent, 0);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        setResult(resultCode, data);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (isFinishing()) {
            // While we expect PIA/InstallStaging to abandon/commit the session, still there
            // might be cases when the session becomes orphan.
            int sessionId = getIntent().getIntExtra(EXTRA_STAGED_SESSION_ID, 0);
            try {
                getPackageManager().getPackageInstaller().abandonSession(sessionId);
            } catch (SecurityException ignored) {
            }
        }
    }
}
