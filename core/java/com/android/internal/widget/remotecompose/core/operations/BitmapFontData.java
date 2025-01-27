/*
 * Copyright (C) 2025 The Android Open Source Project
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

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT_ARRAY;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;

import java.util.Arrays;
import java.util.List;

/** Operation to deal with bitmap font data. */
public class BitmapFontData extends Operation {
    private static final int OP_CODE = Operations.DATA_BITMAP_FONT;
    private static final String CLASS_NAME = "BitmapFontData";

    int mId;

    // Sorted in order of decreasing mChars length.
    @NonNull Glyph[] mFontGlyphs;

    /**
     * A bitmap font is comprised of a collection of Glyphs. Note each Glyph has its own bitmap
     * rather than using a texture atlas.
     */
    public static class Glyph {
        /** The character(s) this glyph represents. */
        public String mChars;

        /** The id of the bitmap for this glyph, or -1 for space. */
        public int mBitmapId;

        /** The margin in pixels to the left of the glyph bitmap. */
        public short mMarginLeft;

        /** The margin in pixels above of the glyph bitmap. */
        public short mMarginTop;

        /** The margin in pixels to the right of the glyph bitmap. */
        public short mMarginRight;

        /** The margin in pixels below the glyph bitmap. */
        public short mMarginBottom;

        public short mBitmapWidth;
        public short mBitmapHeight;

        public Glyph() {}

        public Glyph(
                String chars,
                int bitmapId,
                short marginLeft,
                short marginTop,
                short marginRight,
                short marginBottom,
                short width,
                short height) {
            mChars = chars;
            mBitmapId = bitmapId;
            mMarginLeft = marginLeft;
            mMarginTop = marginTop;
            mMarginRight = marginRight;
            mMarginBottom = marginBottom;
            mBitmapWidth = width;
            mBitmapHeight = height;
        }
    }

    /**
     * create a bitmap font structure.
     *
     * @param id the id of the bitmap font
     * @param fontGlyphs the glyphs that define the bitmap font
     */
    public BitmapFontData(int id, @NonNull Glyph[] fontGlyphs) {
        mId = id;
        mFontGlyphs = fontGlyphs;

        // Sort in order of decreasing mChars length.
        Arrays.sort(mFontGlyphs, (o1, o2) -> o2.mChars.length() - o1.mChars.length());
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mFontGlyphs);
    }

    @NonNull
    @Override
    public String toString() {
        return "BITMAP FONT DATA " + mId;
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
     * @param id the id the bitmap font will be stored under
     * @param glyphs glyph metadata
     */
    public static void apply(@NonNull WireBuffer buffer, int id, @NonNull Glyph[] glyphs) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(glyphs.length);
        for (Glyph element : glyphs) {
            buffer.writeUTF8(element.mChars);
            buffer.writeInt(element.mBitmapId);
            buffer.writeShort(element.mMarginLeft);
            buffer.writeShort(element.mMarginTop);
            buffer.writeShort(element.mMarginRight);
            buffer.writeShort(element.mMarginBottom);
            buffer.writeShort(element.mBitmapWidth);
            buffer.writeShort(element.mBitmapHeight);
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
        int numGlyphElements = buffer.readInt();
        Glyph[] glyphs = new Glyph[numGlyphElements];
        for (int i = 0; i < numGlyphElements; i++) {
            glyphs[i] = new Glyph();
            glyphs[i].mChars = buffer.readUTF8();
            glyphs[i].mBitmapId = buffer.readInt();
            glyphs[i].mMarginLeft = (short) buffer.readShort();
            glyphs[i].mMarginTop = (short) buffer.readShort();
            glyphs[i].mMarginRight = (short) buffer.readShort();
            glyphs[i].mMarginBottom = (short) buffer.readShort();
            glyphs[i].mBitmapWidth = (short) buffer.readShort();
            glyphs[i].mBitmapHeight = (short) buffer.readShort();
        }

        operations.add(new BitmapFontData(id, glyphs));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Bitmap font data")
                .field(DocumentedOperation.INT, "id", "id of bitmap font data")
                .field(INT_ARRAY, "glyphNodes", "list used to greedily convert strings into glyphs")
                .field(INT_ARRAY, "glyphElements", "");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.putObject(mId, this);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    /** Finds the largest glyph matching the string at the specified offset, or returns null. */
    @Nullable
    public Glyph lookupGlyph(String string, int offset) {
        // Since mFontGlyphs is sorted on decreasing size, it will match the longest items first.
        // It is expected that the mFontGlyphs array will be fairly small.
        for (Glyph glyph : mFontGlyphs) {
            if (string.startsWith(glyph.mChars, offset)) {
                return glyph;
            }
        }
        return null;
    }
}
