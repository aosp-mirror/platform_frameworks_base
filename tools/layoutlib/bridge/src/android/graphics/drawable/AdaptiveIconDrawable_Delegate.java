/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.graphics.drawable;

import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable.LayerState;

/**
 * Delegate used to provide new implementation of a select few methods of {@link
 * AdaptiveIconDrawable}
 * <p>
 * Through the layoutlib_create tool, the original  methods of AdaptiveIconDrawable have been
 * replaced by calls to methods of the same name in this delegate class.
 */
@SuppressWarnings("unused")
public class AdaptiveIconDrawable_Delegate {
    @LayoutlibDelegate
    /*package*/ static void draw(AdaptiveIconDrawable thisDrawable, Canvas canvas) {
        // This is a workaround for the broken BitmapShader in layoutlib. This new draw methods
        // avoids the use of the shader.

        for (int i = 0; i < LayerState.N_CHILDREN; i++) {
            if (thisDrawable.mLayerState.mChildren[i] == null) {
                continue;
            }
            final Drawable dr = thisDrawable.mLayerState.mChildren[i].mDrawable;
            if (dr != null) {
                dr.draw(canvas);
            }
        }

        if (thisDrawable.mMaskBitmap != null) {
            Rect bounds = thisDrawable.getBounds();
            Paint paint = new Paint();
            paint.setXfermode(new PorterDuffXfermode(Mode.DST_IN));
            canvas.drawBitmap(thisDrawable.mMaskBitmap, bounds.left, bounds.top, paint);
        }
    }
}
