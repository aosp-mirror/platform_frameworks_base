/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.graphics;

public class RadialGradient extends Shader {

   /** Create a shader that draws a radial gradient given the center and radius.
       @param x        The x-coordinate of the center of the radius
       @param y        The y-coordinate of the center of the radius
       @param radius   Must be positive. The radius of the circle for this gradient
       @param colors   The colors to be distributed between the center and edge of the circle
       @param positions May be NULL. The relative position of
                       each corresponding color in the colors array. If this is NULL,
                       the the colors are distributed evenly between the center and edge of the circle.
       @param  tile    The Shader tiling mode
   */
   public RadialGradient(float x, float y, float radius,
                         int colors[], float positions[], TileMode tile) {
       if (radius <= 0) {
           throw new IllegalArgumentException("radius must be > 0");
       }
       if (colors.length < 2) {
           throw new IllegalArgumentException("needs >= 2 number of colors");
       }
       if (positions != null && colors.length != positions.length) {
           throw new IllegalArgumentException("color and position arrays must be of equal length");
       }

       // FIXME Implement shader
   }

   /** Create a shader that draws a radial gradient given the center and radius.
       @param x        The x-coordinate of the center of the radius
       @param y        The y-coordinate of the center of the radius
       @param radius   Must be positive. The radius of the circle for this gradient
       @param color0   The color at the center of the circle.
       @param color1   The color at the edge of the circle.
       @param tile     The Shader tiling mode
   */
   public RadialGradient(float x, float y, float radius,
                         int color0, int color1, TileMode tile) {
       if (radius <= 0) {
           throw new IllegalArgumentException("radius must be > 0");
       }
       // FIXME Implement shader
   }
}

