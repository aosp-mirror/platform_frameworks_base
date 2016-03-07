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

import android.annotation.Nullable;
import com.android.layoutlib.bridge.util.NinePatchInputStream;
import android.graphics.BitmapFactory.Options;
import android.graphics.Bitmap_Delegate.BitmapCreateFlags;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Set;

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

    // ------ Native Delegates ------

    @LayoutlibDelegate
    /*package*/ static Bitmap nativeDecodeStream(InputStream is, byte[] storage,
            @Nullable Rect padding, @Nullable Options opts) {
        Bitmap bm = null;

        Density density = Density.MEDIUM;
        Set<BitmapCreateFlags> bitmapCreateFlags = EnumSet.of(BitmapCreateFlags.MUTABLE);
        if (opts != null) {
            density = Density.getEnum(opts.inDensity);
            if (opts.inPremultiplied) {
                bitmapCreateFlags.add(BitmapCreateFlags.PREMULTIPLIED);
            }
            opts.inScaled = false;
        }

        try {
            if (is instanceof NinePatchInputStream) {
                NinePatchInputStream npis = (NinePatchInputStream) is;
                npis.disableFakeMarkSupport();

                // load the bitmap as a nine patch
                com.android.ninepatch.NinePatch ninePatch = com.android.ninepatch.NinePatch.load(
                        npis, true /*is9Patch*/, false /*convert*/);

                // get the bitmap and chunk objects.
                bm = Bitmap_Delegate.createBitmap(ninePatch.getImage(), bitmapCreateFlags,
                        density);
                NinePatchChunk chunk = ninePatch.getChunk();

                // put the chunk in the bitmap
                bm.setNinePatchChunk(NinePatch_Delegate.serialize(chunk));

                if (padding != null) {
                    // read the padding
                    int[] paddingArray = chunk.getPadding();
                    padding.left = paddingArray[0];
                    padding.top = paddingArray[1];
                    padding.right = paddingArray[2];
                    padding.bottom = paddingArray[3];
                }
            } else {
                // load the bitmap directly.
                bm = Bitmap_Delegate.createBitmap(is, bitmapCreateFlags, density);
            }
        } catch (IOException e) {
            Bridge.getLog().error(null, "Failed to load image", e, null);
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
    /*package*/ static Bitmap nativeDecodeAsset(long asset, Rect padding, Options opts) {
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
    /*package*/ static boolean nativeIsSeekable(FileDescriptor fd) {
        return true;
    }

    /**
     * Set the newly decoded bitmap's density based on the Options.
     *
     * Copied from {@link BitmapFactory#setDensityFromOptions(Bitmap, Options)}.
     */
    @LayoutlibDelegate
    /*package*/ static void setDensityFromOptions(Bitmap outputBitmap, Options opts) {
        if (outputBitmap == null || opts == null) return;

        final int density = opts.inDensity;
        if (density != 0) {
            outputBitmap.setDensity(density);
            final int targetDensity = opts.inTargetDensity;
            if (targetDensity == 0 || density == targetDensity || density == opts.inScreenDensity) {
                return;
            }

            // --- Change from original implementation begins ---
            // LayoutLib doesn't scale the nine patch when decoding it. Hence, don't change the
            // density of the source bitmap in case of ninepatch.

            if (opts.inScaled) {
            // --- Change from original implementation ends. ---
                outputBitmap.setDensity(targetDensity);
            }
        } else if (opts.inBitmap != null) {
            // bitmap was reused, ensure density is reset
            outputBitmap.setDensity(Bitmap.getDefaultDensity());
        }
    }
}
