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

import java.lang.reflect.Array;

public class FrameValues extends FrameValue {

    /**
     * Returns the number of values in the Frame.
     *
     * This returns 1, if the Frame value is null, or if the value is not an array.
     *
     * @return The number of values in the Frame.
     */
    public int getCount() {
        Object value = super.getValue();
        if (value == null || !value.getClass().isArray()) {
            return 1;
        } else {
            return Array.getLength(super.getValue());
        }
    }

    /**
     * Returns the values in the Frame as an array.
     *
     * Note, that this may be called on Frames that have a non-array object assigned to them. In
     * that case, this method will wrap the object in an array and return that. This way, filters
     * can treat any object based frame as arrays.
     *
     * @return The array of values in this frame.
     */
    public Object getValues() {
        Object value = super.getValue();
        if (value == null || value.getClass().isArray()) {
            return super.getValue();
        } else {
            // Allow reading a single as an array.
            Object[] array = (Object[])Array.newInstance(value.getClass(), 1);
            array[0] = value;
            return array;
        }
    }

    /**
     * Returns the value at the specified index.
     *
     * In case the value is null or not an array, the index must be 0, and the value itself is
     * returned.
     *
     * @param index The index to access.
     * @return The value at that index.
     */
    public Object getValueAtIndex(int index) {
        Object value = super.getValue();
        if (value == null || !value.getClass().isArray()) {
            if (index != 0) {
                throw new ArrayIndexOutOfBoundsException(index);
            } else {
                return value;
            }
        } else {
            return Array.get(value, index);
        }
    }

    /**
     * Returns the value as a FrameValue at the specified index.
     *
     * Use this if you want to access elements as FrameValues. You must release the result when
     * you are done using it.
     *
     * @param index The index to access.
     * @return The value as a FrameValue at that index (must release).
     */
    public FrameValue getFrameValueAtIndex(int index) {
        Object value = getValueAtIndex(index);
        FrameValue result = Frame.create(getType().asSingle(), new int[0]).asFrameValue();
        result.setValue(value);
        return result;
    }

    /**
     * Assign the array of values to the frame.
     *
     * You may assign null or a non-array object, which are interpreted as a 1-length array.
     *
     * @param values The values to assign to the frame.
     */
    public void setValues(Object values) {
        super.setValue(values);
    }

    /**
     * Assign a value at the specified index.
     *
     * In case the held value is not an array, the index must be 0, and the object will be replaced
     * by the new object.
     *
     * @param value The value to assign.
     * @param index The index to assign to.
     */
    public void setValueAtIndex(Object value, int index) {
        super.assertAccessible(MODE_WRITE);
        Object curValue = super.getValue();
        if (curValue == null || !curValue.getClass().isArray()) {
            if (index != 0) {
                throw new ArrayIndexOutOfBoundsException(index);
            } else {
                curValue = value;
            }
        } else {
            Array.set(curValue, index, value);
        }
    }

    /**
     * Assign a FrameValue's value at the specified index.
     *
     * This method unpacks the FrameValue and assigns the unpacked value to the specified index.
     * This does not affect the retain-count of the passed Frame.
     *
     * @param frame The frame value to assign.
     * @param index The index to assign to.
     */
    public void setFrameValueAtIndex(FrameValue frame, int index) {
        Object value = frame.getValue();
        setValueAtIndex(value, index);
    }

    static FrameValues create(BackingStore backingStore) {
        assertObjectBased(backingStore.getFrameType());
        return new FrameValues(backingStore);
    }

    FrameValues(BackingStore backingStore) {
        super(backingStore);
    }
}

