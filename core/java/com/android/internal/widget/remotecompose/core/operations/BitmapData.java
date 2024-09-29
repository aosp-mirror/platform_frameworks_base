/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Operation to deal with bitmap data
 * On getting an Image during a draw call the bitmap is compressed and saved
 * in playback the image is decompressed
 */
public class BitmapData implements Operation {
    int mImageId;
    int mImageWidth;
    int mImageHeight;
    byte[] mBitmap;
    public static final int MAX_IMAGE_DIMENSION = 8000;

    public static final Companion COMPANION = new Companion();

    public BitmapData(int imageId, int width, int height, byte[] bitmap) {
        this.mImageId = imageId;
        this.mImageWidth = width;
        this.mImageHeight = height;
        this.mBitmap = bitmap;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mImageId, mImageWidth, mImageHeight, mBitmap);
    }

    @Override
    public String toString() {
        return "BITMAP DATA " + mImageId;
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "BitmapData";
        }

        @Override
        public int id() {
            return Operations.DATA_BITMAP;
        }

        public void apply(WireBuffer buffer, int imageId, int width, int height, byte[] bitmap) {
            buffer.start(Operations.DATA_BITMAP);
            buffer.writeInt(imageId);
            buffer.writeInt(width);
            buffer.writeInt(height);
            buffer.writeBuffer(bitmap);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int imageId = buffer.readInt();
            int width = buffer.readInt();
            int height = buffer.readInt();
            if (width < 1
                    || height < 1
                    || height > MAX_IMAGE_DIMENSION
                    || width > MAX_IMAGE_DIMENSION) {
                throw new RuntimeException("Dimension of image is invalid " + width + "x" + height);
            }
            byte[] bitmap = buffer.readBuffer();
            operations.add(new BitmapData(imageId, width, height, bitmap));
        }
    }

    @Override
    public void apply(RemoteContext context) {
        context.loadBitmap(mImageId, mImageWidth, mImageHeight, mBitmap);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

}
