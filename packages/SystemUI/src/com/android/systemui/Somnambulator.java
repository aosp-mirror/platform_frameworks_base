/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui;

import android.app.Activity;
import android.content.Intent;
import android.service.dreams.Sandman;

/**
 * A simple activity that launches a dream.
 * <p>
 * Note: This Activity is special.  If this class is moved to another package or
 * renamed, be sure to update the component name in {@link Sandman}.
 * </p>
 */
public class Somnambulator extends Activity {
    public Somnambulator() {
    }

    @Override
    public void onStart() {
        super.onStart();

        final Intent launchIntent = getIntent();
        final String action = launchIntent.getAction();
        if (Intent.ACTION_CREATE_SHORTCUT.equals(action)) {
            Intent shortcutIntent = new Intent(this, Somnambulator.class);
            shortcutIntent.setFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    | Intent.FLAG_ACTIVITY_NEW_TASK);
            Intent resultIntent = new Intent();
            resultIntent.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, R.mipmap.ic_launcher_dreams));
            resultIntent.putExtra(Intent.EXTRA_SHORTCUT_INTENT, shortcutIntent);
            resultIntent.putExtra(Intent.EXTRA_SHORTCUT_NAME, getString(R.string.start_dreams));
            setResult(RESULT_OK, resultIntent);
        } else {
            boolean docked = launchIntent.hasCategory(Intent.CATEGORY_DESK_DOCK);
            if (docked) {
                Sandman.startDreamWhenDockedIfAppropriate(this);
            } else {
                Sandman.startDreamByUserRequest(this);
            }
        }
        finish();
    }
}
