/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.view.surfacecontroltests;

import android.app.Activity;
import android.os.Bundle;
import android.view.SurfaceView;

public class SurfaceControlPictureProfileTestActivity extends Activity {
    private SurfaceView[] mSurfaceViews;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.picture_profile_test_layout);
        mSurfaceViews = new SurfaceView[3];
        mSurfaceViews[0] = (SurfaceView) findViewById(R.id.surfaceview1);
        mSurfaceViews[1] = (SurfaceView) findViewById(R.id.surfaceview2);
        mSurfaceViews[2] = (SurfaceView) findViewById(R.id.surfaceview3);
    }

    public SurfaceView getSurfaceView() {
        return mSurfaceViews[0];
    }

    public SurfaceView[] getSurfaceViews() {
        return mSurfaceViews;
    }
}
