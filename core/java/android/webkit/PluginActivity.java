/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.webkit;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

/**
 * This activity  is invoked when a plugin elects to go into full screen mode.
 * @hide
 */
public class PluginActivity extends Activity {

    /* package */ static final String INTENT_EXTRA_PACKAGE_NAME =
            "android.webkit.plugin.PACKAGE_NAME";
    /* package */ static final String INTENT_EXTRA_CLASS_NAME =
            "android.webkit.plugin.CLASS_NAME";
    /* package */ static final String INTENT_EXTRA_NPP_INSTANCE =
            "android.webkit.plugin.NPP_INSTANCE";

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        if (intent == null) {
            // No intent means no class to lookup.
            finish();
        }
        final String packageName =
                intent.getStringExtra(INTENT_EXTRA_PACKAGE_NAME);
        final String className = intent.getStringExtra(INTENT_EXTRA_CLASS_NAME);
        final int npp = intent.getIntExtra(INTENT_EXTRA_NPP_INSTANCE, -1);
        // Retrieve the PluginStub implemented in packageName.className
        PluginStub stub =
                PluginUtil.getPluginStub(this, packageName, className);

        if (stub != null) {
            View pluginView = stub.getFullScreenView(npp, this);
            if (pluginView != null) {
                setContentView(pluginView);
            } else {
                // No custom full-sreen view returned by the plugin, odd but
                // just in case, finish the activity.
                finish();
            }
        } else {
            finish();
        }
    }
}
