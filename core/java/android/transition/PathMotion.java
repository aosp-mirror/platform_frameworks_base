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
package android.transition;

import android.content.Context;
import android.graphics.Path;
import android.util.AttributeSet;

/**
 * This base class can be extended to provide motion along a Path to Transitions.
 *
 * <p>
 * Transitions such as {@link android.transition.ChangeBounds} move Views, typically
 * in a straight path between the start and end positions. Applications that desire to
 * have these motions move in a curve can change how Views interpolate in two dimensions
 * by extending PathMotion and implementing {@link #getPath(float, float, float, float)}.
 * </p>
 * <p>This may be used in XML as an element inside a transition.</p>
 * <pre>
 * {@code
 * <changeBounds>
 *     <pathMotion class="my.app.transition.MyPathMotion"/>
 * </changeBounds>
 * }
 * </pre>
 */
public abstract class PathMotion {

    public PathMotion() {}

    public PathMotion(Context context, AttributeSet attrs) {}

    /**
     * Provide a Path to interpolate between two points <code>(startX, startY)</code> and
     * <code>(endX, endY)</code>. This allows controlled curved motion along two dimensions.
     *
     * @param startX The x coordinate of the starting point.
     * @param startY The y coordinate of the starting point.
     * @param endX   The x coordinate of the ending point.
     * @param endY   The y coordinate of the ending point.
     * @return A Path along which the points should be interpolated. The returned Path
     * must start at point <code>(startX, startY)</code>, typically using
     * {@link android.graphics.Path#moveTo(float, float)} and end at <code>(endX, endY)</code>.
     */
    public abstract Path getPath(float startX, float startY, float endX, float endY);
}
