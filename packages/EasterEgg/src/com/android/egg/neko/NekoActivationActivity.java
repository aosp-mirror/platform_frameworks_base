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

package com.android.egg.neko;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;

public class NekoActivationActivity extends Activity {
    private void toastUp(String s) {
        Toast toast = Toast.makeText(this, s, Toast.LENGTH_SHORT);
        toast.getView().setBackgroundDrawable(null);
        toast.show();
    }

    @Override
    public void onStart() {
        super.onStart();

        final PackageManager pm = getPackageManager();
        final ComponentName cn = new ComponentName(this, NekoTile.class);
        if (pm.getComponentEnabledSetting(cn) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            if (NekoLand.DEBUG) {
                Log.v("Neko", "Disabling tile.");
            }
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            MetricsLogger.histogram(this, "egg_neko_enable", 0);
            toastUp("\uD83D\uDEAB");
        } else {
            if (NekoLand.DEBUG) {
                Log.v("Neko", "Enabling tile.");
            }
            pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
            MetricsLogger.histogram(this, "egg_neko_enable", 1);
            toastUp("\uD83D\uDC31");
        }
        finish();
    }
}
