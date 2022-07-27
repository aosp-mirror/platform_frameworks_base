/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.perftests.utils;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * A simple activity used for testing, e.g. performance of activity switching, or as a base
 * container of testing view.
 */
public class SurfaceFlingerTestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        final SurfaceView surfaceview = new SurfaceView(this);
        layout.addView(surfaceview);
        setContentView(layout);

    }
}
