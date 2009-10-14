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

package com.android.gldual;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;


public class GLDualActivity extends Activity {

    GLSurfaceView mGLView;
    GLDualGL2View mGL2View;

    @Override protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        View root = getLayoutInflater().inflate(R.layout.gldual_activity, null);
        mGLView = (GLSurfaceView) root.findViewById(R.id.gl1);
        mGLView.setEGLConfigChooser(5,6,5,0,0,0);
        mGLView.setRenderer(new TriangleRenderer());
        mGL2View = (GLDualGL2View) root.findViewById(R.id.gl2);
        setContentView(root);
    }

    @Override protected void onPause() {
        super.onPause();
        mGLView.onPause();
        mGL2View.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        mGLView.onResume();
        mGL2View.onResume();
    }
}
