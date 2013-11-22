/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.source.presentation;

import com.android.accessorydisplay.common.Logger;
import com.android.accessorydisplay.source.R;

import android.app.Presentation;
import android.content.Context;
import android.content.res.Resources;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

/**
 * The presentation to show on the accessory display.
 * <p>
 * Note that this display may have different metrics from the display on which
 * the main activity is showing so we must be careful to use the presentation's
 * own {@link Context} whenever we load resources.
 * </p>
 */
public final class DemoPresentation extends Presentation {
    private final Logger mLogger;

    private GLSurfaceView mSurfaceView;
    private CubeRenderer mRenderer;
    private Button mExplodeButton;

    public DemoPresentation(Context context, Display display, Logger logger) {
        super(context, display);
        mLogger = logger;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Be sure to call the super class.
        super.onCreate(savedInstanceState);

        // Get the resources for the context of the presentation.
        // Notice that we are getting the resources from the context of the presentation.
        Resources r = getContext().getResources();

        // Inflate the layout.
        setContentView(R.layout.presentation_content);

        // Set up the surface view for visual interest.
        mRenderer = new CubeRenderer(false);
        mSurfaceView = (GLSurfaceView)findViewById(R.id.surface_view);
        mSurfaceView.setRenderer(mRenderer);

        // Add a button.
        mExplodeButton = (Button)findViewById(R.id.explode_button);
        mExplodeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mRenderer.explode();
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        mLogger.log("Received touch event: " + event);
        return super.onTouchEvent(event);
    }
}