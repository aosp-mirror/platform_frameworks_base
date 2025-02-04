/*
 * Copyright (C) 2024 The Android Open Source Project
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
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.SHORT;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/** Operation to extract meta Attributes from image data objects */
public class ImageAttribute extends PaintOperation {
    private static final int OP_CODE = Operations.ATTRIBUTE_IMAGE;
    private static final String CLASS_NAME = "ImageAttribute";
    private final int[] mArgs;
    public int mId;
    int mImageId;
    short mType;

    public static final short IMAGE_WIDTH = 0;
    public static final short IMAGE_HEIGHT = 1;

    /**
     * Create a new ImageAttribute operation
     *
     * @param id the id to store the attribute
     * @param imageId the id of the image
     * @param type the type of value to return
     * @param args support for additional arguments (currently none)
     */
    public ImageAttribute(int id, int imageId, short type, int[] args) {
        this.mId = id;
        this.mImageId = imageId;
        this.mType = type;
        this.mArgs = args;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mImageId, mType, mArgs);
    }

    @Override
    public @NonNull String toString() {
        return "ImageAttribute[" + mId + "] = " + mImageId + " " + mType;
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    public static @NonNull String name() {
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
     * Writes out the operation to the buffer
     *
     * @param buffer write command to this buffer
     * @param id the id
     * @param imageId the id of the image
     * @param type the type of value
     * @param args the value of the float
     */
    public static void apply(
            @NonNull WireBuffer buffer, int id, int imageId, short type, int[] args) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(imageId);
        buffer.writeShort(type);
        if (args == null) {
            buffer.writeShort((short) 0);
        } else {
            buffer.writeShort((short) args.length);
            for (int i = 0; i < args.length; i++) {
                buffer.writeInt(args[i]);
            }
        }
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        int imageId = buffer.readInt();
        short type = (short) buffer.readShort();
        short len = (short) buffer.readShort();
        int[] args = new int[len];
        for (int i = 0; i < args.length; i++) {
            args[i] = buffer.readInt();
        }
        operations.add(new ImageAttribute(id, imageId, type, args));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Image Attributes", OP_CODE, CLASS_NAME)
                .description("Measure text")
                .field(INT, "id", "id of float result of the measure")
                .field(INT, "ImageId", "id of the image")
                .field(SHORT, "type", "type: measure 0=width,1=height")
                .field(SHORT, "len", "number of additional arguments (currently 0)")
                .field(INT, "a", "len", "number of arguments");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @NonNull float[] mBounds = new float[4];

    @Override
    public void paint(@NonNull PaintContext context) {
        BitmapData bitmapData = (BitmapData) context.getContext().getObject(mImageId);
        switch (mType) {
            case IMAGE_WIDTH:
                context.getContext().loadFloat(mId, bitmapData.getWidth());
                break;
            case IMAGE_HEIGHT:
                context.getContext().loadFloat(mId, bitmapData.getHeight());
                break;
        }
    }
}
