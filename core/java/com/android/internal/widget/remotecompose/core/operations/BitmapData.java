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

import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT;
import static com.android.internal.widget.remotecompose.core.documentation.Operation.INT_ARRAY;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.SerializableToString;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Operation to deal with bitmap data
 * On getting an Image during a draw call the bitmap is compressed and saved
 * in playback the image is decompressed
 */
public class BitmapData implements Operation, SerializableToString {
    private static final int OP_CODE = Operations.DATA_BITMAP;
    private static final String CLASS_NAME = "BitmapData";
    int mImageId;
    int mImageWidth;
    int mImageHeight;
    byte[] mBitmap;
    public static final int MAX_IMAGE_DIMENSION = 8000;

    public BitmapData(int imageId, int width, int height, byte[] bitmap) {
        this.mImageId = imageId;
        this.mImageWidth = width;
        this.mImageHeight = height;
        this.mBitmap = bitmap;
    }

    public int getWidth() {
        return mImageWidth;
    }

    public int getHeight() {
        return mImageHeight;
    }

    @Override
    public void write(WireBuffer buffer) {
        apply(buffer, mImageId, mImageWidth, mImageHeight, mBitmap);
    }

    @Override
    public String toString() {
        return "BITMAP DATA " + mImageId;
    }


    public static String name() {
        return CLASS_NAME;
    }

    public static int id() {
        return OP_CODE;
    }

    public static void apply(WireBuffer buffer, int imageId, int width, int height, byte[] bitmap) {
        buffer.start(OP_CODE);
        buffer.writeInt(imageId);
        buffer.writeInt(width);
        buffer.writeInt(height);
        buffer.writeBuffer(bitmap);
    }


    public static void read(WireBuffer buffer, List<Operation> operations) {
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


    public static void documentation(DocumentationBuilder doc) {
        doc.operation("Data Operations",
                        OP_CODE,
                        CLASS_NAME)
                .description("Bitmap data")
                .field(INT, "id", "id of bitmap data")
                .field(INT, "width",
                        "width of the image")
                .field(INT, "height",
                        "height of the image")
                .field(INT_ARRAY, "values", "length",
                        "Array of ints");
    }

    @Override
    public void apply(RemoteContext context) {
        context.loadBitmap(mImageId, mImageWidth, mImageHeight, mBitmap);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, CLASS_NAME
                + " id " + mImageId + " (" + mImageWidth + "x" + mImageHeight + ")");
    }

}
