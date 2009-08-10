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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.renderscript.Type;
import android.util.Config;
import android.util.Log;

/**
 * @hide
 *
 **/
public class Allocation extends BaseObj {
    Allocation(int id, RenderScript rs) {
        super(rs);
        mID = id;
    }

    public void uploadToTexture(int baseMipLevel) {
        mRS.nAllocationUploadToTexture(mID, baseMipLevel);
    }

    public void destroy() {
        if(mDestroyed) {
            throw new IllegalStateException("Object already destroyed.");
        }
        mDestroyed = true;
        mRS.nAllocationDestroy(mID);
    }

    public void data(int[] d) {
        mRS.nAllocationData(mID, d);
    }

    public void data(float[] d) {
        mRS.nAllocationData(mID, d);
    }

    public void subData1D(int off, int count, int[] d) {
        mRS.nAllocationSubData1D(mID, off, count, d);
    }

    public void subData1D(int off, int count, float[] d) {
        mRS.nAllocationSubData1D(mID, off, count, d);
    }

    public void subData2D(int xoff, int yoff, int w, int h, int[] d) {
        mRS.nAllocationSubData2D(mID, xoff, yoff, w, h, d);
    }

    public void subData2D(int xoff, int yoff, int w, int h, float[] d) {
        mRS.nAllocationSubData2D(mID, xoff, yoff, w, h, d);
    }

    public void readData(int[] d) {
        mRS.nAllocationRead(mID, d);
    }

    public void readData(float[] d) {
        mRS.nAllocationRead(mID, d);
    }


    public class Adapter1D extends BaseObj {
        Adapter1D(int id, RenderScript rs) {
            super(rs);
            mID = id;
        }

        public void destroy() {
            mRS.nAdapter1DDestroy(mID);
            mID = 0;
        }

        public void setConstraint(Dimension dim, int value) {
            mRS.nAdapter1DSetConstraint(mID, dim.mID, value);
        }

        public void data(int[] d) {
            mRS.nAdapter1DData(mID, d);
        }

        public void data(float[] d) {
            mRS.nAdapter1DData(mID, d);
        }

        public void subData(int off, int count, int[] d) {
            mRS.nAdapter1DSubData(mID, off, count, d);
        }

        public void subData(int off, int count, float[] d) {
            mRS.nAdapter1DSubData(mID, off, count, d);
        }
    }

    public Adapter1D createAdapter1D() {
        int id = mRS.nAdapter1DCreate();
        if (id != 0) {
            mRS.nAdapter1DBindAllocation(id, mID);
        }
        return new Adapter1D(id, mRS);
    }


    public class Adapter2D extends BaseObj {
        Adapter2D(int id, RenderScript rs) {
            super(rs);
            mID = id;
        }

        public void destroy() {
            mRS.nAdapter2DDestroy(mID);
            mID = 0;
        }

        public void setConstraint(Dimension dim, int value) {
            mRS.nAdapter2DSetConstraint(mID, dim.mID, value);
        }

        public void data(int[] d) {
            mRS.nAdapter2DData(mID, d);
        }

        public void data(float[] d) {
            mRS.nAdapter2DData(mID, d);
        }

        public void subData(int xoff, int yoff, int w, int h, int[] d) {
            mRS.nAdapter2DSubData(mID, xoff, yoff, w, h, d);
        }

        public void subData(int xoff, int yoff, int w, int h, float[] d) {
            mRS.nAdapter2DSubData(mID, xoff, yoff, w, h, d);
        }
    }

    public Adapter2D createAdapter2D() {
        int id = mRS.nAdapter2DCreate();
        if (id != 0) {
            mRS.nAdapter2DBindAllocation(id, mID);
        }
        return new Adapter2D(id, mRS);
    }


    // creation

    private static BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();
    static {
        mBitmapOptions.inScaled = false;
    }

    static public Allocation createTyped(RenderScript rs, Type type)
        throws IllegalArgumentException {

        if(type.mID == 0) {
            throw new IllegalStateException("Bad Type");
        }
        int id = rs.nAllocationCreateTyped(type.mID);
        return new Allocation(id, rs);
    }

    static public Allocation createSized(RenderScript rs, Element e, int count)
        throws IllegalArgumentException {

        int id;
        if(e.mIsPredefined) {
            id = rs.nAllocationCreatePredefSized(e.mPredefinedID, count);
        } else {
            id = rs.nAllocationCreateSized(e.mID, count);
            if(id == 0) {
                throw new IllegalStateException("Bad element.");
            }
        }
        return new Allocation(id, rs);
    }

    static public Allocation createFromBitmap(RenderScript rs, Bitmap b, Element dstFmt, boolean genMips)
        throws IllegalArgumentException {
        if(!dstFmt.mIsPredefined) {
            throw new IllegalStateException("Attempting to allocate a bitmap with a non-static element.");
        }

        int id = rs.nAllocationCreateFromBitmap(dstFmt.mPredefinedID, genMips, b);
        return new Allocation(id, rs);
    }

    static public Allocation createFromBitmapBoxed(RenderScript rs, Bitmap b, Element dstFmt, boolean genMips)
        throws IllegalArgumentException {
        if(!dstFmt.mIsPredefined) {
            throw new IllegalStateException("Attempting to allocate a bitmap with a non-static element.");
        }

        int id = rs.nAllocationCreateFromBitmapBoxed(dstFmt.mPredefinedID, genMips, b);
        return new Allocation(id, rs);
    }

    static public Allocation createFromBitmapResource(RenderScript rs, Resources res, int id, Element dstFmt, boolean genMips)
        throws IllegalArgumentException {

        Bitmap b = BitmapFactory.decodeResource(res, id, mBitmapOptions);
        return createFromBitmap(rs, b, dstFmt, genMips);
    }

    static public Allocation createFromBitmapResourceBoxed(RenderScript rs, Resources res, int id, Element dstFmt, boolean genMips)
        throws IllegalArgumentException {

        Bitmap b = BitmapFactory.decodeResource(res, id, mBitmapOptions);
        return createFromBitmapBoxed(rs, b, dstFmt, genMips);
    }


}


