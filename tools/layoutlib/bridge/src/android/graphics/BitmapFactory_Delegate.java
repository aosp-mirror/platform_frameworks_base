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

package android.graphics;

import com.android.layoutlib.bridge.Bridge;
import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.ninepatch.NinePatchChunk;
import com.android.resources.Density;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import android.content.res.BridgeResources.NinePatchInputStream;
import android.graphics.BitmapFactory.Options;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;

/**
 * Delegate implementing the native methods of android.graphics.BitmapFactory
 *
 * Through the layoutlib_create tool, the original native methods of BitmapFactory have been
 * replaced by calls to methods of the same name in this delegate class.
 *
 * Because it's a stateless class to start with, there's no need to keep a {@link DelegateManager}
 * around to map int to instance of the delegate.
 *
 */
/*package*/ class BitmapFactory_Delegate {

    // ------ Java delegates ------

    @LayoutlibDelegate
    /*package*/ static Bitmap finishDecode(Bitmap bm, Rect outPadding, Options opts) {
        if (bm == null || opts == null) {
            return bm;
        }

        final int density = opts.inDensity;
        if (density == 0) {
            return bm;
        }

        bm.setDensity(density);
        final int targetDensity = opts.inTargetDensity;
        if (targetDensity == 0 || density == targetDensity || density == opts.inScreenDensity) {
            return bm;
        }

        byte[] np = bm.getNinePatchChunk();
        final boolean isNinePatch = np != null && NinePatch.isNinePatchChunk(np);
        // DELEGATE CHANGE: never scale 9-patch
        if (opts.inScaled && isNinePatch == false) {
            float scale = targetDensity / (float)density;
            // TODO: This is very inefficient and should be done in native by Skia
            final Bitmap oldBitmap = bm;
            bm = Bitmap.createScaledBitmap(oldBitmap, (int) (bm.getWidth() * scale + 0.5f),
                    (int) (bm.getHeight() * scale + 0.5f), true);
            oldBitmap.recycle();

            if (isNinePatch) {
                np = nativeScaleNinePatch(np, scale, outPadding);
                bm.setNinePatchChunk(np);
            }
            bm.setDensity(targetDensity);
        }

        return bm;
    }


    // ------ Native Delegates ------

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeDecodeStream(InputStream is, byte[] storage,
            Rect padding, Options opts) {
        return nativeDecodeStream(is, storage, padding, opts, false, 1.f);
    }

    @LayoutlibDelegate
    /*package*/ static  Bitmap nativeDecodeStream(InputStream is, byte[] storage,
            Rect padding, Options opts, boolean applyScale, float scale) {
        Bitmap bm = null;

        //TODO support rescaling

        Density density = Density.MEDIUM;
        if (opts != null) {
            density = Density.getEnum(opts.inDensity);
        }

        try {
            if (is instanceof NinePatchInputStream) {
                NinePatchInputStream npis = (NinePatchInputStream) is;
                npis.disableFakeMarkSupport();

                // load the bitmap as a nine patch
                com.android.ninepatch.NinePatch ninePatch = com.android.ninepatch.NinePatch.load(
                        npis, true /*is9Patch*/, false /*convert*/);

                // get the bitmap and chunk objects.
                bm = Bitmap_Delegate.createBitmap(ninePatch.getImage(), true /*isMutable*/,
                        density);
                NinePatchChunk chunk = ninePatch.getChunk();

                // put the chunk in the bitmap
                bm.setNinePatchChunk(NinePatch_Delegate.serialize(chunk));

                // read the padding
                int[] paddingarray = chunk.getPadding();
                padding.left = paddingarray[0];
                padding.top = paddingarray[1];
                padding.right = paddingarray[2];
                padding.bottom = paddingarray[3];
            } else {
                // load the bitmap directly.
                bm = Bitmap_Delegate.createBitmap(is, true, density);
            }
        } catch (IOException e) {
            Bridge.getLog().error(null,"Failed to load image" , e, null);
        }

        return bm;
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeDecodeFileDescriptor(FileDescriptor fd,
            Rect padding, Options opts) {
        opts.inBitmap = null;
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeDecodeAsset(int asset, Rect padding, Options opts) {
        opts.inBitmap = null;
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeDecodeAsset(int asset, Rect padding, Options opts,
            boolean applyScale, float scale) {
        opts.inBitmap = null;
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeDecodeByteArray(byte[] data, int offset,
            int length, Options opts) {
        opts.inBitmap = null;
        return null;
    }

    @LayoutlibDelegate
    /*package*/ static byte[] nativeScaleNinePatch(byte[] chunk, float scale, Rect pad) {
        // don't scale for now. This should not be called anyway since we re-implement
        // BitmapFactory.finishDecode();
        return chunk;
    }

    @LayoutlibDelegate
    /*package*/ static boolean nativeIsSeekable(FileDescriptor fd) {
        return true;
    }
}
