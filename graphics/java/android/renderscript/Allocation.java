/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.renderscript;

import java.io.IOException;
import java.io.InputStream;

import android.content.res.Resources;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;
import android.util.TypedValue;

/**
 * @hide
 *
 **/
public class Allocation extends BaseObj {
    Type mType;
    Bitmap mBitmap;

    Allocation(int id, RenderScript rs, Type t) {
        super(id, rs);
        mType = t;
    }

    Allocation(int id, RenderScript rs) {
        super(id, rs);
    }

    @Override
    void updateFromNative() {
        mRS.validate();
        mName = mRS.nGetName(mID);
        int typeID = mRS.nAllocationGetType(mID);
        if(typeID != 0) {
            mType = new Type(typeID, mRS);
            mType.updateFromNative();
        }
    }

    public Type getType() {
        return mType;
    }

    public void uploadToTexture(int baseMipLevel) {
        mRS.validate();
        mRS.nAllocationUploadToTexture(mID, false, baseMipLevel);
    }

    public void uploadToTexture(boolean genMips, int baseMipLevel) {
        mRS.validate();
        mRS.nAllocationUploadToTexture(mID, genMips, baseMipLevel);
    }

    public void uploadToBufferObject() {
        mRS.validate();
        mRS.nAllocationUploadToBufferObject(mID);
    }

    public void data(int[] d) {
        mRS.validate();
        subData1D(0, mType.getElementCount(), d);
    }
    public void data(short[] d) {
        mRS.validate();
        subData1D(0, mType.getElementCount(), d);
    }
    public void data(byte[] d) {
        mRS.validate();
        subData1D(0, mType.getElementCount(), d);
    }
    public void data(float[] d) {
        mRS.validate();
        subData1D(0, mType.getElementCount(), d);
    }

    public void subData(int off, FieldPacker fp) {
        int eSize = mType.mElement.getSizeBytes();
        final byte[] data = fp.getData();

        int count = data.length / eSize;
        if ((eSize * count) != data.length) {
            throw new IllegalArgumentException("Field packer length " + data.length +
                                               " not divisible by element size " + eSize + ".");
        }
        data1DChecks(off, count, data.length, data.length);
        mRS.nAllocationSubData1D(mID, off, count, data, data.length);
    }

    private void data1DChecks(int off, int count, int len, int dataSize) {
        mRS.validate();
        if(off < 0) {
            throw new IllegalArgumentException("Offset must be >= 0.");
        }
        if(count < 1) {
            throw new IllegalArgumentException("Count must be >= 1.");
        }
        if((off + count) > mType.getElementCount()) {
            throw new IllegalArgumentException("Overflow, Available count " + mType.getElementCount() +
                                               ", got " + count + " at offset " + off + ".");
        }
        if((len) < dataSize) {
            throw new IllegalArgumentException("Array too small for allocation type.");
        }
    }

    public void subData1D(int off, int count, int[] d) {
        int dataSize = mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length * 4, dataSize);
        mRS.nAllocationSubData1D(mID, off, count, d, dataSize);
    }
    public void subData1D(int off, int count, short[] d) {
        int dataSize = mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length * 2, dataSize);
        mRS.nAllocationSubData1D(mID, off, count, d, dataSize);
    }
    public void subData1D(int off, int count, byte[] d) {
        int dataSize = mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length, dataSize);
        mRS.nAllocationSubData1D(mID, off, count, d, dataSize);
    }
    public void subData1D(int off, int count, float[] d) {
        int dataSize = mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length * 4, dataSize);
        mRS.nAllocationSubData1D(mID, off, count, d, dataSize);
    }



    public void subData2D(int xoff, int yoff, int w, int h, int[] d) {
        mRS.validate();
        mRS.nAllocationSubData2D(mID, xoff, yoff, w, h, d, d.length * 4);
    }

    public void subData2D(int xoff, int yoff, int w, int h, float[] d) {
        mRS.validate();
        mRS.nAllocationSubData2D(mID, xoff, yoff, w, h, d, d.length * 4);
    }

    public void readData(int[] d) {
        mRS.validate();
        mRS.nAllocationRead(mID, d);
    }

    public void readData(float[] d) {
        mRS.validate();
        mRS.nAllocationRead(mID, d);
    }

    public void data(Object o) {
        mRS.validate();
        mRS.nAllocationSubDataFromObject(mID, mType, 0, o);
    }

    public void read(Object o) {
        mRS.validate();
        mRS.nAllocationSubReadFromObject(mID, mType, 0, o);
    }

    public void subData(int offset, Object o) {
        mRS.validate();
        mRS.nAllocationSubDataFromObject(mID, mType, offset, o);
    }

    public class Adapter1D extends BaseObj {
        Adapter1D(int id, RenderScript rs) {
            super(id, rs);
        }

        public void setConstraint(Dimension dim, int value) {
            mRS.validate();
            mRS.nAdapter1DSetConstraint(mID, dim.mID, value);
        }

        public void data(int[] d) {
            mRS.validate();
            mRS.nAdapter1DData(mID, d);
        }

        public void data(float[] d) {
            mRS.validate();
            mRS.nAdapter1DData(mID, d);
        }

        public void subData(int off, int count, int[] d) {
            mRS.validate();
            mRS.nAdapter1DSubData(mID, off, count, d);
        }

        public void subData(int off, int count, float[] d) {
            mRS.validate();
            mRS.nAdapter1DSubData(mID, off, count, d);
        }
    }

    public Adapter1D createAdapter1D() {
        mRS.validate();
        int id = mRS.nAdapter1DCreate();
        if(id == 0) {
            throw new IllegalStateException("allocation failed.");
        }
        mRS.nAdapter1DBindAllocation(id, mID);
        return new Adapter1D(id, mRS);
    }


    public class Adapter2D extends BaseObj {
        Adapter2D(int id, RenderScript rs) {
            super(id, rs);
        }

        public void setConstraint(Dimension dim, int value) {
            mRS.validate();
            mRS.nAdapter2DSetConstraint(mID, dim.mID, value);
        }

        public void data(int[] d) {
            mRS.validate();
            mRS.nAdapter2DData(mID, d);
        }

        public void data(float[] d) {
            mRS.validate();
            mRS.nAdapter2DData(mID, d);
        }

        public void subData(int xoff, int yoff, int w, int h, int[] d) {
            mRS.validate();
            mRS.nAdapter2DSubData(mID, xoff, yoff, w, h, d);
        }

        public void subData(int xoff, int yoff, int w, int h, float[] d) {
            mRS.validate();
            mRS.nAdapter2DSubData(mID, xoff, yoff, w, h, d);
        }
    }

    public Adapter2D createAdapter2D() {
        mRS.validate();
        int id = mRS.nAdapter2DCreate();
        if(id == 0) {
            throw new IllegalStateException("allocation failed.");
        }
        mRS.nAdapter2DBindAllocation(id, mID);
        return new Adapter2D(id, mRS);
    }


    // creation

    private static BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();
    static {
        mBitmapOptions.inScaled = false;
    }

    static public Allocation createTyped(RenderScript rs, Type type)
        throws IllegalArgumentException {

        rs.validate();
        if(type.mID == 0) {
            throw new IllegalStateException("Bad Type");
        }
        int id = rs.nAllocationCreateTyped(type.mID);
        return new Allocation(id, rs, type);
    }

    static public Allocation createSized(RenderScript rs, Element e, int count)
        throws IllegalArgumentException {

        rs.validate();
        Type.Builder b = new Type.Builder(rs, e);
        b.add(Dimension.X, count);
        Type t = b.create();

        int id = rs.nAllocationCreateTyped(t.mID);
        if(id == 0) {
            throw new IllegalStateException("Bad element.");
        }
        return new Allocation(id, rs, t);
    }

    static private Element elementFromBitmap(RenderScript rs, Bitmap b) {
        final Bitmap.Config bc = b.getConfig();
        if (bc == Bitmap.Config.ALPHA_8) {
            return Element.A_8(rs);
        }
        if (bc == Bitmap.Config.ARGB_4444) {
            return Element.RGBA_4444(rs);
        }
        if (bc == Bitmap.Config.ARGB_8888) {
            return Element.RGBA_8888(rs);
        }
        if (bc == Bitmap.Config.RGB_565) {
            return Element.RGB_565(rs);
        }
        throw new IllegalStateException("Bad bitmap type.");
    }

    static private Type typeFromBitmap(RenderScript rs, Bitmap b) {
        Element e = elementFromBitmap(rs, b);
        Type.Builder tb = new Type.Builder(rs, e);
        tb.add(Dimension.X, b.getWidth());
        tb.add(Dimension.Y, b.getHeight());
        return tb.create();
    }

    static public Allocation createFromBitmap(RenderScript rs, Bitmap b, Element dstFmt, boolean genMips)
        throws IllegalArgumentException {

        rs.validate();
        Type t = typeFromBitmap(rs, b);

        int id = rs.nAllocationCreateFromBitmap(dstFmt.mID, genMips, b);
        if(id == 0) {
            throw new IllegalStateException("Load failed.");
        }
        return new Allocation(id, rs, t);
    }

    static public Allocation createBitmapRef(RenderScript rs, Bitmap b)
        throws IllegalArgumentException {

        rs.validate();
        Type t = typeFromBitmap(rs, b);

        int id = rs.nAllocationCreateBitmapRef(t.getID(), b);
        if(id == 0) {
            throw new IllegalStateException("Load failed.");
        }

        Allocation a = new Allocation(id, rs, t);
        a.mBitmap = b;
        return a;
    }

    static Allocation createFromBitmapBoxed(RenderScript rs, Bitmap b, Element dstFmt, boolean genMips)
        throws IllegalArgumentException {

        rs.validate();
        int id = rs.nAllocationCreateFromBitmapBoxed(dstFmt.mID, genMips, b);
        if(id == 0) {
            throw new IllegalStateException("Load failed.");
        }
        return new Allocation(id, rs, null);
    }

    static public Allocation createFromBitmapResource(RenderScript rs, Resources res, int id, Element dstFmt, boolean genMips)
        throws IllegalArgumentException {

        rs.validate();
        InputStream is = null;
        try {
            final TypedValue value = new TypedValue();
            is = res.openRawResource(id, value);

            int asset = ((AssetManager.AssetInputStream) is).getAssetInt();
            int allocationId = rs.nAllocationCreateFromAssetStream(dstFmt.mID, genMips,
                    asset);

            if(allocationId == 0) {
                throw new IllegalStateException("Load failed.");
            }
            return new Allocation(allocationId, rs, null);
        } catch (Exception e) {
            // Ignore
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        return null;
    }

    static public Allocation createFromBitmapResourceBoxed(RenderScript rs, Resources res, int id, Element dstFmt, boolean genMips)
        throws IllegalArgumentException {

        Bitmap b = BitmapFactory.decodeResource(res, id, mBitmapOptions);
        return createFromBitmapBoxed(rs, b, dstFmt, genMips);
    }

    static public Allocation createFromString(RenderScript rs, String str)
        throws IllegalArgumentException {
        byte[] allocArray = null;
        try {
            allocArray = str.getBytes("UTF-8");
            Allocation alloc = Allocation.createSized(rs, Element.U8(rs), allocArray.length);
            alloc.data(allocArray);
            return alloc;
        }
        catch (Exception e) {
            Log.e("rs", "could not convert string to utf-8");
        }
        return null;
    }
}


