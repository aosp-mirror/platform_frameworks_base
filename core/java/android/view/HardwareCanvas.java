/*
 * Copyright (C) 2010 The Android Open Source Project
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

import android.graphics.Bitmap;
import android.graphics.Canvas;

/**
 * Hardware accelerated canvas. 
 */
abstract class HardwareCanvas extends Canvas {
    @Override
    public boolean isHardwareAccelerated() {
        return true;
    }

    @Override
    public void setBitmap(Bitmap bitmap) {
        throw new UnsupportedOperationException();
    }
    
    /**
     * This method <strong>must</strong> be called before releasing a
     * reference to a hardware canvas. This method is responsible for
     * freeing native resources associated with the hardware. Not
     * invoking this method properly can result in memory leaks.
     */    
    public abstract void destroy();

    /**
     * Invoked before any drawing operation is performed in this canvas.
     */
    abstract void onPreDraw();

    /**
     * Invoked after all drawing operation have been performed.
     */
    abstract void onPostDraw();
    
    /**
     * Draws the specified display list onto this canvas.
     * 
     * @param displayList The display list to replay.
     */
    public abstract void drawDisplayList(DisplayList displayList);
}
