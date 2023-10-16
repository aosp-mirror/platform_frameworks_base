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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class FrameBuffer1D extends Frame {

    private int mLength = 0;

    /**
     * Access frame's data using a {@link ByteBuffer}.
     * This is a convenience method and is equivalent to calling {@code lockData} with an
     * {@code accessFormat} of {@code ACCESS_BYTES}.
     * When writing to the {@link ByteBuffer}, the byte order should be always set to
     * {@link ByteOrder#nativeOrder()}.
     *
     * @return The byte buffer instance holding the Frame's data.
     */
    public ByteBuffer lockBytes(int mode) {
        assertAccessible(mode);
        return (ByteBuffer)mBackingStore.lockData(mode, BackingStore.ACCESS_BYTES);
    }

    public int getLength() {
        return mLength;
    }

    @Override
    public int[] getDimensions() {
        return super.getDimensions();
    }

    /**
     * TODO: Documentation. Note that frame contents are invalidated.
     */
    @Override
    public void resize(int[] newDimensions) {
        super.resize(newDimensions);
    }

    static FrameBuffer1D create(BackingStore backingStore) {
        assertCanCreate(backingStore);
        return new FrameBuffer1D(backingStore);
    }

    FrameBuffer1D(BackingStore backingStore) {
        super(backingStore);
        updateLength(backingStore.getDimensions());
    }

    static void assertCanCreate(BackingStore backingStore) {
        FrameType type = backingStore.getFrameType();
        if (type.getElementSize() == 0) {
            throw new RuntimeException("Cannot access Frame of type " + type + " as a FrameBuffer "
                + "instance!");
        }
        int[] dims = backingStore.getDimensions();
        if (dims == null || dims.length == 0) {
            throw new RuntimeException("Cannot access Frame with no dimensions as a FrameBuffer "
                + "instance!");
        }
    }

    void updateLength(int[] dimensions) {
        mLength = 1;
        for (int dim : dimensions) {
            mLength *= dim;
        }
    }
}

