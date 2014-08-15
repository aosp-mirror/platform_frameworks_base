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

package android.graphics.drawable.shapes;

import android.annotation.NonNull;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;

/**
 * Defines a generic graphical "shape."
 * Any Shape can be drawn to a Canvas with its own draw() method,
 * but more graphical control is available if you instead pass
 * it to a {@link android.graphics.drawable.ShapeDrawable}.
 */
public abstract class Shape implements Cloneable {
    private float mWidth;
    private float mHeight;
    
    /**
     * Returns the width of the Shape.
     */
    public final float getWidth() {
        return mWidth;
    }
    
    /**
     * Returns the height of the Shape.
     */
    public final float getHeight() {
        return mHeight;
    }

    /**
     * Draw this shape into the provided Canvas, with the provided Paint.
     * Before calling this, you must call {@link #resize(float,float)}.
     * 
     * @param canvas the Canvas within which this shape should be drawn
     * @param paint  the Paint object that defines this shape's characteristics
     */
    public abstract void draw(Canvas canvas, Paint paint);

    /**
     * Resizes the dimensions of this shape.
     * Must be called before {@link #draw(Canvas,Paint)}.
     * 
     * @param width the width of the shape (in pixels)
     * @param height the height of the shape (in pixels)
     */
    public final void resize(float width, float height) {
        if (width < 0) {
            width = 0;
        }
        if (height < 0) {
            height =0;
        }
        if (mWidth != width || mHeight != height) {
            mWidth = width;
            mHeight = height;
            onResize(width, height);
        }
    }
    
    /**
     * Checks whether the Shape is opaque.
     * Default impl returns true. Override if your subclass can be opaque.
     * 
     * @return true if any part of the drawable is <em>not</em> opaque. 
     */
    public boolean hasAlpha() {
        return true;
    }
    
    /**
     * Callback method called when {@link #resize(float,float)} is executed.
     * 
     * @param width the new width of the Shape
     * @param height the new height of the Shape
     */
    protected void onResize(float width, float height) {}

    /**
     * Compute the Outline of the shape and return it in the supplied Outline
     * parameter. The default implementation does nothing and {@code outline} is not changed.
     *
     * @param outline The Outline to be populated with the result. Should not be null.
     */
    public void getOutline(@NonNull Outline outline) {}

    @Override
    public Shape clone() throws CloneNotSupportedException {
        return (Shape) super.clone();
    }

}
