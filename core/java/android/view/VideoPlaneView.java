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

package android.view;

import android.content.Context;
import android.util.AttributeSet;

/**
 * Provides a dedicated surface embedded inside of a view hierarchy much like a
 * {@link SurfaceView}, but the surface is actually backed by a hardware video
 * plane.
 *
 * TODO: Eventually this should be separate from SurfaceView.
 *
 * @hide
 */
public class VideoPlaneView extends SurfaceView {
    public VideoPlaneView(Context context) {
        super(context);
    }

    public VideoPlaneView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VideoPlaneView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public VideoPlaneView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void updateWindow(boolean force, boolean redrawNeeded) {
        mLayout.privateFlags |= WindowManager.LayoutParams.PRIVATE_FLAG_VIDEO_PLANE;
        super.updateWindow(force, redrawNeeded);
    }
}
