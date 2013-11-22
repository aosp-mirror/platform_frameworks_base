/*
 * Copyright (C) 2011 The Android Open Source Project
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

package androidx.media.filterfw;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import androidx.media.filterfw.BackingStore.Backing;

public class FrameImage2D extends FrameBuffer2D {

    /**
     * Access frame's data using a TextureSource.
     * This is a convenience method and is equivalent to calling {@code lockData} with an
     * {@code accessFormat} of {@code ACCESS_TEXTURE}.
     *
     * @return The TextureSource instance holding the Frame's data.
     */
    public TextureSource lockTextureSource() {
        return (TextureSource)mBackingStore.lockData(MODE_READ, BackingStore.ACCESS_TEXTURE);
    }

    /**
     * Access frame's data using a RenderTarget.
     * This is a convenience method and is equivalent to calling {@code lockData} with an
     * {@code accessFormat} of {@code ACCESS_RENDERTARGET}.
     *
     * @return The RenderTarget instance holding the Frame's data.
     */
    public RenderTarget lockRenderTarget() {
        return (RenderTarget)mBackingStore.lockData(MODE_WRITE, BackingStore.ACCESS_RENDERTARGET);
    }

    /**
     * Assigns the pixel data of the specified bitmap.
     *
     * The RGBA pixel data will be extracted from the bitmap and assigned to the frame data. Note,
     * that the colors are premultiplied with the alpha channel. If you wish to have
     * non-premultiplied colors, you must pass the Frame through an
     * {@code UnpremultiplyAlphaFilter}.
     *
     * @param bitmap The bitmap pixels to assign.
     */
    public void setBitmap(Bitmap bitmap) {
        bitmap = convertToFrameType(bitmap, mBackingStore.getFrameType());
        validateBitmapSize(bitmap, mBackingStore.getDimensions());
        Backing backing = mBackingStore.lockBacking(MODE_WRITE, BackingStore.ACCESS_BITMAP);
        backing.setData(bitmap);
        mBackingStore.unlock();
    }

    /**
     * Returns the RGBA image contents as a Bitmap instance.
     *
     * @return a Bitmap instance holding the RGBA Frame image content.
     */
    public Bitmap toBitmap() {
        Bitmap result = (Bitmap)mBackingStore.lockData(MODE_READ, BackingStore.ACCESS_BITMAP);
        mBackingStore.unlock();
        return result;
    }

    /**
     * Copies the image data from one frame to another.
     *
     * The source and target rectangles must be given in normalized coordinates, where 0,0 is the
     * top-left of the image and 1,1 is the bottom-right.
     *
     * If the target rectangle is smaller than the target frame, the pixel values outside of the
     * target rectangle are undefined.
     *
     * This method must be called within a Filter during execution. It supports both GL-enabled
     * and GL-disabled run contexts.
     *
     * @param target The target frame to copy to.
     * @param sourceRect The source rectangle in normalized coordinates.
     * @param targetRect The target rectangle in normalized coordinates.
     */
    public void copyToFrame(FrameImage2D target, RectF sourceRect, RectF targetRect) {
        if (GraphRunner.current().isOpenGLSupported()) {
            gpuImageCopy(this, target, sourceRect, targetRect);
        } else {
            cpuImageCopy(this, target, sourceRect, targetRect);
        }
    }

    static FrameImage2D create(BackingStore backingStore) {
        assertCanCreate(backingStore);
        return new FrameImage2D(backingStore);
    }

    FrameImage2D(BackingStore backingStore) {
        super(backingStore);
    }

    static void assertCanCreate(BackingStore backingStore) {
        FrameBuffer2D.assertCanCreate(backingStore);
    }

    private static Bitmap convertToFrameType(Bitmap bitmap, FrameType type) {
        Bitmap.Config config = bitmap.getConfig();
        Bitmap result = bitmap;
        switch(type.getElementId()) {
            case FrameType.ELEMENT_RGBA8888:
                if (config != Bitmap.Config.ARGB_8888) {
                    result = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                    if (result == null) {
                        throw new RuntimeException("Could not convert bitmap to frame-type " +
                                "RGBA8888!");
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("Unsupported frame type '" + type + "' for " +
                        "bitmap assignment!");
        }
        return result;
    }

    private void validateBitmapSize(Bitmap bitmap, int[] dimensions) {
        if (bitmap.getWidth() != dimensions[0] || bitmap.getHeight() != dimensions[1]) {
            throw new IllegalArgumentException("Cannot assign bitmap of size " + bitmap.getWidth()
                    + "x" + bitmap.getHeight() + " to frame of size " + dimensions[0] + "x"
                    + dimensions[1] + "!");
        }
    }

    private static void gpuImageCopy(
            FrameImage2D srcImage, FrameImage2D dstImage, RectF srcRect, RectF dstRect) {
        ImageShader idShader = RenderTarget.currentTarget().getIdentityShader();
        // We briefly modify the shader
        // TODO: Implement a safer way to save and restore a shared shader.
        idShader.setSourceRect(srcRect);
        idShader.setTargetRect(dstRect);
        idShader.process(srcImage, dstImage);
        // And reset it as others may use it as well
        idShader.setSourceRect(0f, 0f, 1f, 1f);
        idShader.setTargetRect(0f, 0f, 1f, 1f);
    }

    private static void cpuImageCopy(
            FrameImage2D srcImage, FrameImage2D dstImage, RectF srcRect, RectF dstRect) {
        // Convert rectangles to integer rectangles in image dimensions
        Rect srcIRect = new Rect((int) srcRect.left * srcImage.getWidth(),
                (int) srcRect.top * srcImage.getHeight(),
                (int) srcRect.right * srcImage.getWidth(),
                (int) srcRect.bottom * srcImage.getHeight());
        Rect dstIRect = new Rect((int) dstRect.left * srcImage.getWidth(),
                (int) dstRect.top * srcImage.getHeight(),
                (int) dstRect.right * srcImage.getWidth(),
                (int) dstRect.bottom * srcImage.getHeight());

        // Create target canvas
        Bitmap.Config config = Bitmap.Config.ARGB_8888;
        Bitmap dstBitmap = Bitmap.createBitmap(dstImage.getWidth(), dstImage.getHeight(), config);
        Canvas canvas = new Canvas(dstBitmap);

        // Draw source bitmap into target canvas
        Paint paint = new Paint();
        paint.setFilterBitmap(true);
        Bitmap srcBitmap = srcImage.toBitmap();
        canvas.drawBitmap(srcBitmap, srcIRect, dstIRect, paint);

        // Assign bitmap to output frame
        dstImage.setBitmap(dstBitmap);
    }
}

