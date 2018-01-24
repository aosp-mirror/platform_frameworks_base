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

package android.graphics;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.graphics.drawable.Drawable;

/**
 *  Helper interface for adding custom processing to an image.
 *
 *  <p>The image being processed may be a {@link Drawable}, {@link Bitmap} or frame
 *  of an animated image produced by {@link ImageDecoder}. This is called before
 *  the requested object is returned.</p>
 *
 *  <p>This custom processing also applies to image types that are otherwise
 *  immutable, such as {@link Bitmap.Config#HARDWARE}.</p>
 *
 *  <p>On an animated image, the callback will only be called once, but the drawing
 *  commands will be applied to each frame, as if the {@code Canvas} had been
 *  returned by {@link Picture#beginRecording}.<p>
 *
 *  <p>Supplied to ImageDecoder via {@link ImageDecoder#setPostProcessor}.</p>
 */
public interface PostProcessor {
    /**
     *  Do any processing after (for example) decoding.
     *
     *  <p>Drawing to the {@link Canvas} will behave as if the initial processing
     *  (e.g. decoding) already exists in the Canvas. An implementation can draw
     *  effects on top of this, or it can even draw behind it using
     *  {@link PorterDuff.Mode#DST_OVER}. A common effect is to add transparency
     *  to the corners to achieve rounded corners. That can be done with the
     *  following code:</p>
     *
     *  <code>
     *      Path path = new Path();
     *      path.setFillType(Path.FillType.INVERSE_EVEN_ODD);
     *      int width = canvas.getWidth();
     *      int height = canvas.getHeight();
     *      path.addRoundRect(0, 0, width, height, 20, 20, Path.Direction.CW);
     *      Paint paint = new Paint();
     *      paint.setAntiAlias(true);
     *      paint.setColor(Color.TRANSPARENT);
     *      paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
     *      canvas.drawPath(path, paint);
     *      return PixelFormat.TRANSLUCENT;
     *  </code>
     *
     *
     *  @param canvas The {@link Canvas} to draw to.
     *  @return Opacity of the result after drawing.
     *      {@link PixelFormat#UNKNOWN} means that the implementation did not
     *      change whether the image has alpha. Return this unless you added
     *      transparency (e.g. with the code above, in which case you should
     *      return {@code PixelFormat.TRANSLUCENT}) or you forced the image to
     *      be opaque (e.g. by drawing everywhere with an opaque color and
     *      {@code PorterDuff.Mode.DST_OVER}, in which case you should return
     *      {@code PixelFormat.OPAQUE}).
     *      {@link PixelFormat#TRANSLUCENT} means that the implementation added
     *      transparency. This is safe to return even if the image already had
     *      transparency. This is also safe to return if the result is opaque,
     *      though it may draw more slowly.
     *      {@link PixelFormat#OPAQUE} means that the implementation forced the
     *      image to be opaque. This is safe to return even if the image was
     *      already opaque.
     *      {@link PixelFormat#TRANSPARENT} (or any other integer) is not
     *      allowed, and will result in throwing an
     *      {@link java.lang.IllegalArgumentException}.
     */
    @PixelFormat.Opacity
    public int onPostProcess(@NonNull Canvas canvas);
}
