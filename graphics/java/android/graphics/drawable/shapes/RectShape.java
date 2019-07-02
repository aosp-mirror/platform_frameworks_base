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

import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;

import java.util.Objects;

/**
 * Defines a rectangle shape.
 * <p>
 * The rectangle can be drawn to a Canvas with its own draw() method,
 * but more graphical control is available if you instead pass
 * the RectShape to a {@link android.graphics.drawable.ShapeDrawable}.
 */
public class RectShape extends Shape {
    private RectF mRect = new RectF();

    public RectShape() {}
    
    @Override
    public void draw(Canvas canvas, Paint paint) {
        canvas.drawRect(mRect, paint);
    }

    @Override
    public void getOutline(Outline outline) {
        final RectF rect = rect();
        outline.setRect((int) Math.ceil(rect.left), (int) Math.ceil(rect.top),
                (int) Math.floor(rect.right), (int) Math.floor(rect.bottom));
    }

    @Override
    protected void onResize(float width, float height) {
        mRect.set(0, 0, width, height);
    }

    /**
     * Returns the RectF that defines this rectangle's bounds.
     */
    protected final RectF rect() {
        return mRect;
    }

    @Override
    public RectShape clone() throws CloneNotSupportedException {
        final RectShape shape = (RectShape) super.clone();
        shape.mRect = new RectF(mRect);
        return shape;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }
        RectShape rectShape = (RectShape) o;
        return Objects.equals(mRect, rectShape.mRect);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), mRect);
    }
}
