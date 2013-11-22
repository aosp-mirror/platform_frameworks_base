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

import androidx.media.filterfw.BackingStore.Backing;

public class FrameValue extends Frame {

    public Object getValue() {
        Object result = mBackingStore.lockData(MODE_READ, BackingStore.ACCESS_OBJECT);
        mBackingStore.unlock();
        return result;
    }

    public void setValue(Object value) {
        Backing backing = mBackingStore.lockBacking(MODE_WRITE, BackingStore.ACCESS_OBJECT);
        backing.setData(value);
        mBackingStore.unlock();
    }

    static FrameValue create(BackingStore backingStore) {
        assertObjectBased(backingStore.getFrameType());
        return new FrameValue(backingStore);
    }

    FrameValue(BackingStore backingStore) {
        super(backingStore);
    }

    static void assertObjectBased(FrameType type) {
        if (type.getElementId() != FrameType.ELEMENT_OBJECT) {
            throw new RuntimeException("Cannot access non-object based Frame as FrameValue!");
        }
    }
}

