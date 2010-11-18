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

    public enum CubemapLayout {
        VERTICAL_FACE_LIST (0),
        HORIZONTAL_FACE_LIST (1),
        VERTICAL_CROSS (2),
        HORIZONTAL_CROSS (3);

        int mID;
        CubemapLayout(int id) {
            mID = id;
        }
    }

    Allocation(int id, RenderScript rs, Type t) {
        super(id, rs);
        mType = t;
    }

    Allocation(int id, RenderScript rs) {
        super(id, rs);
    }

    @Override
    void updateFromNative() {
        super.updateFromNative();
        int typeID = mRS.nAllocationGetType(getID());
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
        mRS.nAllocationUploadToTexture(getID(), false, baseMipLevel);
    }

    public void uploadToTexture(boolean genMips, int baseMipLevel) {
        mRS.validate();
        mRS.nAllocationUploadToTexture(getID(), genMips, baseMipLevel);
    }

    public void uploadToBufferObject() {
        mRS.validate();
        mRS.nAllocationUploadToBufferObject(getID());
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

    public void updateFromBitmap(Bitmap b) {

        mRS.validate();
        if(mType.getX() != b.getWidth() ||
           mType.getY() != b.getHeight()) {
            throw new RSIllegalArgumentException("Cannot update allocation from bitmap, sizes mismatch");
        }

        mRS.nAllocationUpdateFromBitmap(getID(), b);
    }

    public void subData(int xoff, FieldPacker fp) {
        int eSize = mType.mElement.getSizeBytes();
        final byte[] data = fp.getData();

        int count = data.length / eSize;
        if ((eSize * count) != data.length) {
            throw new RSIllegalArgumentException("Field packer length " + data.length +
                                               " not divisible by element size " + eSize + ".");
        }
        data1DChecks(xoff, count, data.length, data.length);
        mRS.nAllocationSubData1D(getID(), xoff, count, data, data.length);
    }


    public void subElementData(int xoff, int component_number, FieldPacker fp) {
        if (component_number >= mType.mElement.mElements.length) {
            throw new RSIllegalArgumentException("Component_number " + component_number + " out of range.");
        }
        if(xoff < 0) {
            throw new RSIllegalArgumentException("Offset must be >= 0.");
        }

        final byte[] data = fp.getData();
        int eSize = mType.mElement.mElements[component_number].getSizeBytes();

        if (data.length != eSize) {
            throw new RSIllegalArgumentException("Field packer sizelength " + data.length +
                                               " does not match component size " + eSize + ".");
        }

        mRS.nAllocationSubElementData1D(getID(), xoff, component_number, data, data.length);
    }

    private void data1DChecks(int off, int count, int len, int dataSize) {
        mRS.validate();
        if(off < 0) {
            throw new RSIllegalArgumentException("Offset must be >= 0.");
        }
        if(count < 1) {
            throw new RSIllegalArgumentException("Count must be >= 1.");
        }
        if((off + count) > mType.getElementCount()) {
            throw new RSIllegalArgumentException("Overflow, Available count " + mType.getElementCount() +
                                               ", got " + count + " at offset " + off + ".");
        }
        if((len) < dataSize) {
            throw new RSIllegalArgumentException("Array too small for allocation type.");
        }
    }

    public void subData1D(int off, int count, int[] d) {
        int dataSize = mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length * 4, dataSize);
        mRS.nAllocationSubData1D(getID(), off, count, d, dataSize);
    }
    public void subData1D(int off, int count, short[] d) {
        int dataSize = mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length * 2, dataSize);
        mRS.nAllocationSubData1D(getID(), off, count, d, dataSize);
    }
    public void subData1D(int off, int count, byte[] d) {
        int dataSize = mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length, dataSize);
        mRS.nAllocationSubData1D(getID(), off, count, d, dataSize);
    }
    public void subData1D(int off, int count, float[] d) {
        int dataSize = mType.mElement.getSizeBytes() * count;
        data1DChecks(off, count, d.length * 4, dataSize);
        mRS.nAllocationSubData1D(getID(), off, count, d, dataSize);
    }


    public void subData2D(int xoff, int yoff, int w, int h, int[] d) {
        mRS.validate();
        mRS.nAllocationSubData2D(getID(), xoff, yoff, w, h, d, d.length * 4);
    }

    public void subData2D(int xoff, int yoff, int w, int h, float[] d) {
        mRS.validate();
        mRS.nAllocationSubData2D(getID(), xoff, yoff, w, h, d, d.length * 4);
    }

    public void readData(int[] d) {
        mRS.validate();
        mRS.nAllocationRead(getID(), d);
    }

    public void readData(float[] d) {
        mRS.validate();
        mRS.nAllocationRead(getID(), d);
    }

    public synchronized void resize(int dimX) {
        if ((mType.getY() > 0)|| (mType.getZ() > 0) || mType.getFaces() || mType.getLOD()) {
            throw new RSInvalidStateException("Resize only support for 1D allocations at this time.");
        }
        mRS.nAllocationResize1D(getID(), dimX);
        mRS.finish();  // Necessary because resize is fifoed and update is async.

        int typeID = mRS.nAllocationGetType(getID());
        mType = new Type(typeID, mRS);
        mType.updateFromNative();
    }

    /*
    public void resize(int dimX, int dimY) {
        if ((mType.getZ() > 0) || mType.getFaces() || mType.getLOD()) {
            throw new RSIllegalStateException("Resize only support for 2D allocations at this time.");
        }
        if (mType.getY() == 0) {
            throw new RSIllegalStateException("Resize only support for 2D allocations at this time.");
        }
        mRS.nAllocationResize2D(getID(), dimX, dimY);
    }
    */

    public class Adapter1D extends BaseObj {
        Adapter1D(int id, RenderScript rs) {
            super(id, rs);
        }

        public void setConstraint(Dimension dim, int value) {
            mRS.validate();
            mRS.nAdapter1DSetConstraint(getID(), dim.mID, value);
        }

        public void data(int[] d) {
            mRS.validate();
            mRS.nAdapter1DData(getID(), d);
        }

        public void data(float[] d) {
            mRS.validate();
            mRS.nAdapter1DData(getID(), d);
        }

        public void subData(int off, int count, int[] d) {
            mRS.validate();
            mRS.nAdapter1DSubData(getID(), off, count, d);
        }

        public void subData(int off, int count, float[] d) {
            mRS.validate();
            mRS.nAdapter1DSubData(getID(), off, count, d);
        }
    }

    public Adapter1D createAdapter1D() {
        mRS.validate();
        int id = mRS.nAdapter1DCreate();
        if(id == 0) {
            throw new RSRuntimeException("Adapter creation failed.");
        }
        mRS.nAdapter1DBindAllocation(id, getID());
        return new Adapter1D(id, mRS);
    }


    public class Adapter2D extends BaseObj {
        Adapter2D(int id, RenderScript rs) {
            super(id, rs);
        }

        public void setConstraint(Dimension dim, int value) {
            mRS.validate();
            mRS.nAdapter2DSetConstraint(getID(), dim.mID, value);
        }

        public void data(int[] d) {
            mRS.validate();
            mRS.nAdapter2DData(getID(), d);
        }

        public void data(float[] d) {
            mRS.validate();
            mRS.nAdapter2DData(getID(), d);
        }

        public void subData(int xoff, int yoff, int w, int h, int[] d) {
            mRS.validate();
            mRS.nAdapter2DSubData(getID(), xoff, yoff, w, h, d);
        }

        public void subData(int xoff, int yoff, int w, int h, float[] d) {
            mRS.validate();
            mRS.nAdapter2DSubData(getID(), xoff, yoff, w, h, d);
        }
    }

    public Adapter2D createAdapter2D() {
        mRS.validate();
        int id = mRS.nAdapter2DCreate();
        if(id == 0) {
            throw new RSRuntimeException("allocation failed.");
        }
        mRS.nAdapter2DBindAllocation(id, getID());
        if(id == 0) {
            throw new RSRuntimeException("Adapter creation failed.");
        }
        return new Adapter2D(id, mRS);
    }


    // creation

    private static BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();
    static {
        mBitmapOptions.inScaled = false;
    }

    static public Allocation createTyped(RenderScript rs, Type type) {

        rs.validate();
        if(type.getID() == 0) {
            throw new RSInvalidStateException("Bad Type");
        }
        int id = rs.nAllocationCreateTyped(type.getID());
        if(id == 0) {
            throw new RSRuntimeException("Allocation creation failed.");
        }
        return new Allocation(id, rs, type);
    }

    static public Allocation createSized(RenderScript rs, Element e, int count)
        throws IllegalArgumentException {

        rs.validate();
        Type.Builder b = new Type.Builder(rs, e);
        b.add(Dimension.X, count);
        Type t = b.create();

        int id = rs.nAllocationCreateTyped(t.getID());
        if(id == 0) {
            throw new RSRuntimeException("Allocation creation failed.");
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
        throw new RSInvalidStateException("Bad bitmap type: " + bc);
    }

    static private Type typeFromBitmap(RenderScript rs, Bitmap b, boolean mip) {
        Element e = elementFromBitmap(rs, b);
        Type.Builder tb = new Type.Builder(rs, e);
        tb.add(Dimension.X, b.getWidth());
        tb.add(Dimension.Y, b.getHeight());
        if (mip) {
            tb.add(Dimension.LOD, 1);
        }
        return tb.create();
    }

    static public Allocation createFromBitmap(RenderScript rs, Bitmap b,
                                              Element dstFmt, boolean genMips) {
        rs.validate();
        Type t = typeFromBitmap(rs, b, genMips);

        int id = rs.nAllocationCreateFromBitmap(dstFmt.getID(), genMips, b);
        if(id == 0) {
            throw new RSRuntimeException("Load failed.");
        }
        return new Allocation(id, rs, t);
    }

    static public Allocation createCubemapFromBitmap(RenderScript rs, Bitmap b,
                                                     Element dstFmt,
                                                     boolean genMips,
                                                     CubemapLayout layout) {
        rs.validate();
        int height = b.getHeight();
        int width = b.getWidth();

        if (layout != CubemapLayout.VERTICAL_FACE_LIST) {
            throw new RSIllegalArgumentException("Only vertical face list supported");
        }
        if (height % 6 != 0) {
            throw new RSIllegalArgumentException("Cubemap height must be multiple of 6");
        }
        if (height / 6 != width) {
            throw new RSIllegalArgumentException("Only square cobe map faces supported");
        }
        boolean isPow2 = (width & (width - 1)) == 0;
        if (!isPow2) {
            throw new RSIllegalArgumentException("Only power of 2 cube faces supported");
        }

        Element e = elementFromBitmap(rs, b);
        Type.Builder tb = new Type.Builder(rs, e);
        tb.add(Dimension.X, width);
        tb.add(Dimension.Y, width);
        tb.add(Dimension.FACE, 1);
        if (genMips) {
            tb.add(Dimension.LOD, 1);
        }
        Type t = tb.create();

        int id = rs.nAllocationCubeCreateFromBitmap(dstFmt.getID(), genMips, b);
        if(id == 0) {
            throw new RSRuntimeException("Load failed for bitmap " + b + " element " + e);
        }
        return new Allocation(id, rs, t);
    }

    static public Allocation createBitmapRef(RenderScript rs, Bitmap b) {

        rs.validate();
        Type t = typeFromBitmap(rs, b, false);

        int id = rs.nAllocationCreateBitmapRef(t.getID(), b);
        if(id == 0) {
            throw new RSRuntimeException("Load failed.");
        }

        Allocation a = new Allocation(id, rs, t);
        a.mBitmap = b;
        return a;
    }

    static public Allocation createFromBitmapResource(RenderScript rs, Resources res, int id, Element dstFmt, boolean genMips) {

        rs.validate();
        InputStream is = null;
        try {
            final TypedValue value = new TypedValue();
            is = res.openRawResource(id, value);

            int asset = ((AssetManager.AssetInputStream) is).getAssetInt();
            int aId = rs.nAllocationCreateFromAssetStream(dstFmt.getID(), genMips, asset);

            if (aId == 0) {
                throw new RSRuntimeException("Load failed.");
            }
            Allocation alloc = new Allocation(aId, rs, null);
            alloc.updateFromNative();
            return alloc;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }

    static public Allocation createFromString(RenderScript rs, String str) {
        byte[] allocArray = null;
        try {
            allocArray = str.getBytes("UTF-8");
            Allocation alloc = Allocation.createSized(rs, Element.U8(rs), allocArray.length);
            alloc.data(allocArray);
            return alloc;
        }
        catch (Exception e) {
            throw new RSRuntimeException("Could not convert string to utf-8.");
        }
    }
}


