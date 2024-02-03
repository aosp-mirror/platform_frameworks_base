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
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Operation to draw a given cached bitmap
 */
public class DrawBitmapInt extends PaintOperation {
    int mImageId;
    int mSrcLeft;
    int mSrcTop;
    int mSrcRight;
    int mSrcBottom;
    int mDstLeft;
    int mDstTop;
    int mDstRight;
    int mDstBottom;
    int mContentDescId = 0;
    public static final Companion COMPANION = new Companion();

    public DrawBitmapInt(int imageId,
                         int srcLeft,
                         int srcTop,
                         int srcRight,
                         int srcBottom,
                         int dstLeft,
                         int dstTop,
                         int dstRight,
                         int dstBottom,
                         int cdId) {
        this.mImageId = imageId;
        this.mSrcLeft = srcLeft;
        this.mSrcTop = srcTop;
        this.mSrcRight = srcRight;
        this.mSrcBottom = srcBottom;
        this.mDstLeft = dstLeft;
        this.mDstTop = dstTop;
        this.mDstRight = dstRight;
        this.mDstBottom = dstBottom;
        this.mContentDescId = cdId;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mImageId, mSrcLeft, mSrcTop, mSrcRight, mSrcBottom,
                mDstLeft, mDstTop, mDstRight, mDstBottom, mContentDescId);
    }

    @Override
    public String toString() {
        return "DRAW_BITMAP_INT " + mImageId + " on " + mSrcLeft + " " + mSrcTop
                + " " + mSrcRight + " " + mSrcBottom + " "
                + "- " + mDstLeft + " " + mDstTop + " " + mDstRight + " " + mDstBottom + ";";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {}

        @Override
        public String name() {
            return "DrawBitmapInt";
        }

        @Override
        public int id() {
            return Operations.DRAW_BITMAP;
        }

        public void apply(WireBuffer buffer, int imageId,
                   int srcLeft, int srcTop, int srcRight, int srcBottom,
                   int dstLeft, int dstTop, int dstRight, int dstBottom,
                   int cdId) {
            buffer.start(Operations.DRAW_BITMAP_INT);
            buffer.writeInt(imageId);
            buffer.writeInt(srcLeft);
            buffer.writeInt(srcTop);
            buffer.writeInt(srcRight);
            buffer.writeInt(srcBottom);
            buffer.writeInt(dstLeft);
            buffer.writeInt(dstTop);
            buffer.writeInt(dstRight);
            buffer.writeInt(dstBottom);
            buffer.writeInt(cdId);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int imageId = buffer.readInt();
            int sLeft = buffer.readInt();
            int srcTop = buffer.readInt();
            int srcRight = buffer.readInt();
            int srcBottom = buffer.readInt();
            int dstLeft = buffer.readInt();
            int dstTop = buffer.readInt();
            int dstRight = buffer.readInt();
            int dstBottom = buffer.readInt();
            int cdId = buffer.readInt();
            DrawBitmapInt op = new DrawBitmapInt(imageId, sLeft, srcTop, srcRight, srcBottom,
                    dstLeft, dstTop, dstRight, dstBottom, cdId);

            operations.add(op);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.drawBitmap(mImageId, mSrcLeft, mSrcTop, mSrcRight, mSrcBottom,
                mDstLeft, mDstTop, mDstRight, mDstBottom, mContentDescId);
    }
}
