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


package android.filterfw.core;

import android.filterfw.core.Frame;
import android.filterfw.core.FrameFormat;
import android.filterfw.core.FrameManager;
import android.filterfw.core.NativeBuffer;
import android.filterfw.format.ObjectFormat;
import android.graphics.Bitmap;

import java.lang.reflect.Constructor;
import java.nio.ByteBuffer;

/**
 * @hide
 */
public class SimpleFrame extends Frame {

    private Object mObject;

    SimpleFrame(FrameFormat format, FrameManager frameManager) {
        super(format, frameManager);
        initWithFormat(format);
        setReusable(false);
    }

    static SimpleFrame wrapObject(Object object, FrameManager frameManager) {
        FrameFormat format = ObjectFormat.fromObject(object, FrameFormat.TARGET_SIMPLE);
        SimpleFrame result = new SimpleFrame(format, frameManager);
        result.setObjectValue(object);
        return result;
    }

    private void initWithFormat(FrameFormat format) {
        final int count = format.getLength();
        final int baseType = format.getBaseType();
        switch (baseType) {
            case FrameFormat.TYPE_BYTE:
                mObject = new byte[count];
                break;
            case FrameFormat.TYPE_INT16:
                mObject = new short[count];
                break;
            case FrameFormat.TYPE_INT32:
                mObject = new int[count];
                break;
            case FrameFormat.TYPE_FLOAT:
                mObject = new float[count];
                break;
            case FrameFormat.TYPE_DOUBLE:
                mObject = new double[count];
                break;
            default:
                mObject = null;
                break;
        }
    }

    @Override
    protected boolean hasNativeAllocation() {
        return false;
    }

    @Override
    protected void releaseNativeAllocation() {
    }

    @Override
    public Object getObjectValue() {
        return mObject;
    }

    @Override
    public void setInts(int[] ints) {
        assertFrameMutable();
        setGenericObjectValue(ints);
    }

    @Override
    public int[] getInts() {
        return (mObject instanceof int[]) ? (int[])mObject : null;
    }

    @Override
    public void setFloats(float[] floats) {
        assertFrameMutable();
        setGenericObjectValue(floats);
    }

    @Override
    public float[] getFloats() {
        return (mObject instanceof float[]) ? (float[])mObject : null;
    }

    @Override
    public void setData(ByteBuffer buffer, int offset, int length) {
        assertFrameMutable();
        setGenericObjectValue(ByteBuffer.wrap(buffer.array(), offset, length));
    }

    @Override
    public ByteBuffer getData() {
        return (mObject instanceof ByteBuffer) ? (ByteBuffer)mObject : null;
    }

    @Override
    public void setBitmap(Bitmap bitmap) {
        assertFrameMutable();
        setGenericObjectValue(bitmap);
    }

    @Override
    public Bitmap getBitmap() {
        return (mObject instanceof Bitmap) ? (Bitmap)mObject : null;
    }

    private void setFormatObjectClass(Class objectClass) {
        MutableFrameFormat format = getFormat().mutableCopy();
        format.setObjectClass(objectClass);
        setFormat(format);
    }

    @Override
    protected void setGenericObjectValue(Object object) {
        // Update the FrameFormat class
        // TODO: Take this out! FrameFormats should not be modified and convenience formats used
        // instead!
        FrameFormat format = getFormat();
        if (format.getObjectClass() == null) {
            setFormatObjectClass(object.getClass());
        } else if (!format.getObjectClass().isAssignableFrom(object.getClass())) {
            throw new RuntimeException(
                "Attempting to set object value of type '" + object.getClass() + "' on " +
                "SimpleFrame of type '" + format.getObjectClass() + "'!");
        }

        // Set the object value
        mObject = object;
    }

    @Override
    public String toString() {
        return "SimpleFrame (" + getFormat() + ")";
    }
}
