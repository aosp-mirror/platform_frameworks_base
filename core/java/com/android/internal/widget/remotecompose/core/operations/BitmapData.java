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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT_ARRAY;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.SHORT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.SerializableToString;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Operation to deal with bitmap data On getting an Image during a draw call the bitmap is
 * compressed and saved in playback the image is decompressed
 */
public class BitmapData extends Operation implements SerializableToString {
    private static final int OP_CODE = Operations.DATA_BITMAP;
    private static final String CLASS_NAME = "BitmapData";
    int mImageId;
    int mImageWidth;
    int mImageHeight;
    short mType;
    short mEncoding;
    @NonNull final byte[] mBitmap;

    /** The max size of width or height */
    public static final int MAX_IMAGE_DIMENSION = 8000;

    /** The data is encoded in the file (default) */
    public static final short ENCODING_INLINE = 0;

    /** The data is encoded in the url */
    public static final short ENCODING_URL = 1;

    /** The data is encoded as a reference to file */
    public static final short ENCODING_FILE = 2;

    /** The data is encoded as PNG_8888 (default) */
    public static final short TYPE_PNG_8888 = 0;

    /** The data is encoded as PNG */
    public static final short TYPE_PNG = 1;

    /** The data is encoded as RAW 8 bit */
    public static final short TYPE_RAW8 = 2;

    /** The data is encoded as RAW 8888 bit */
    public static final short TYPE_RAW8888 = 3;

    /**
     * create a bitmap structure
     *
     * @param imageId the id to store the image
     * @param width the width of the image
     * @param height the height of the image
     * @param bitmap the data
     */
    public BitmapData(int imageId, int width, int height, @NonNull byte[] bitmap) {
        this.mImageId = imageId;
        this.mImageWidth = width;
        this.mImageHeight = height;
        this.mBitmap = bitmap;
    }

    /**
     * The width of the image
     *
     * @return the width
     */
    public int getWidth() {
        return mImageWidth;
    }

    /**
     * The height of the image
     *
     * @return the height
     */
    public int getHeight() {
        return mImageHeight;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mImageId, mImageWidth, mImageHeight, mBitmap);
    }

    @NonNull
    @Override
    public String toString() {
        return "BITMAP DATA " + mImageId;
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return CLASS_NAME;
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    /**
     * Add the image to the document
     *
     * @param buffer document to write to
     * @param imageId the id the image will be stored under
     * @param width the width of the image
     * @param height the height of the image
     * @param bitmap the data used to store/encode the image
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int imageId,
            int width,
            int height,
            @NonNull byte[] bitmap) {
        buffer.start(OP_CODE);
        buffer.writeInt(imageId);
        buffer.writeInt(width);
        buffer.writeInt(height);
        buffer.writeBuffer(bitmap);
    }

    /**
     * Add the image to the document (using the ehanced encoding)
     *
     * @param buffer document to write to
     * @param imageId the id the image will be stored under
     * @param type the type of image
     * @param width the width of the image
     * @param encoding the encoding
     * @param height the height of the image
     * @param bitmap the data used to store/encode the image
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int imageId,
            short type,
            short width,
            short encoding,
            short height,
            @NonNull byte[] bitmap) {
        buffer.start(OP_CODE);
        buffer.writeInt(imageId);
        int w = (((int) type) << 16) | width;
        int h = (((int) encoding) << 16) | height;
        buffer.writeInt(w);
        buffer.writeInt(h);
        buffer.writeBuffer(bitmap);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
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

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Bitmap data")
                .field(DocumentedOperation.INT, "id", "id of bitmap data")
                .field(SHORT, "type", "width of the image")
                .field(SHORT, "width", "width of the image")
                .field(SHORT, "encoding", "height of the image")
                .field(INT, "width", "width of the image")
                .field(SHORT, "height", "height of the image")
                .field(INT_ARRAY, "values", "length", "Array of ints");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.loadBitmap(mImageId, mEncoding, mType, mImageWidth, mImageHeight, mBitmap);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(
                indent,
                CLASS_NAME + " id " + mImageId + " (" + mImageWidth + "x" + mImageHeight + ")");
    }
}
