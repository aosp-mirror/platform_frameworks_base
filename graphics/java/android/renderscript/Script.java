/*
 * Copyright (C) 2008-2012 The Android Open Source Project
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

import android.util.SparseArray;

/**
 * The parent class for all executable scripts. This should not be used by
 * applications.
 **/
public class Script extends BaseObj {

    /**
     * KernelID is an identifier for a Script + root function pair. It is used
     * as an identifier for ScriptGroup creation.
     *
     * This class should not be directly created. Instead use the method in the
     * reflected or intrinsic code "getKernelID_funcname()".
     *
     */
    public static final class KernelID extends BaseObj {
        Script mScript;
        int mSlot;
        int mSig;
        KernelID(int id, RenderScript rs, Script s, int slot, int sig) {
            super(id, rs);
            mScript = s;
            mSlot = slot;
            mSig = sig;
        }
    }

    private final SparseArray<KernelID> mKIDs = new SparseArray<KernelID>();
    /**
     * Only to be used by generated reflected classes.
     */
    protected KernelID createKernelID(int slot, int sig, Element ein, Element eout) {
        KernelID k = mKIDs.get(slot);
        if (k != null) {
            return k;
        }

        int id = mRS.nScriptKernelIDCreate(getID(mRS), slot, sig);
        if (id == 0) {
            throw new RSDriverException("Failed to create KernelID");
        }

        k = new KernelID(id, mRS, this, slot, sig);
        mKIDs.put(slot, k);
        return k;
    }

    /**
     * FieldID is an identifier for a Script + exported field pair. It is used
     * as an identifier for ScriptGroup creation.
     *
     * This class should not be directly created. Instead use the method in the
     * reflected or intrinsic code "getFieldID_funcname()".
     *
     */
    public static final class FieldID extends BaseObj {
        Script mScript;
        int mSlot;
        FieldID(int id, RenderScript rs, Script s, int slot) {
            super(id, rs);
            mScript = s;
            mSlot = slot;
        }
    }

    private final SparseArray<FieldID> mFIDs = new SparseArray();
    /**
     * Only to be used by generated reflected classes.
     */
    protected FieldID createFieldID(int slot, Element e) {
        FieldID f = mFIDs.get(slot);
        if (f != null) {
            return f;
        }

        int id = mRS.nScriptFieldIDCreate(getID(mRS), slot);
        if (id == 0) {
            throw new RSDriverException("Failed to create FieldID");
        }

        f = new FieldID(id, mRS, this, slot);
        mFIDs.put(slot, f);
        return f;
    }


    /**
     * Only intended for use by generated reflected code.
     *
     */
    protected void invoke(int slot) {
        mRS.nScriptInvoke(getID(mRS), slot);
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    protected void invoke(int slot, FieldPacker v) {
        if (v != null) {
            mRS.nScriptInvokeV(getID(mRS), slot, v.getData());
        } else {
            mRS.nScriptInvoke(getID(mRS), slot);
        }
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    protected void forEach(int slot, Allocation ain, Allocation aout, FieldPacker v) {
        if (ain == null && aout == null) {
            throw new RSIllegalArgumentException(
                "At least one of ain or aout is required to be non-null.");
        }
        int in_id = 0;
        if (ain != null) {
            in_id = ain.getID(mRS);
        }
        int out_id = 0;
        if (aout != null) {
            out_id = aout.getID(mRS);
        }
        byte[] params = null;
        if (v != null) {
            params = v.getData();
        }
        mRS.nScriptForEach(getID(mRS), slot, in_id, out_id, params);
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    protected void forEach(int slot, Allocation ain, Allocation aout, FieldPacker v, LaunchOptions sc) {
        if (ain == null && aout == null) {
            throw new RSIllegalArgumentException(
                "At least one of ain or aout is required to be non-null.");
        }

        if (sc == null) {
            forEach(slot, ain, aout, v);
            return;
        }
        int in_id = 0;
        if (ain != null) {
            in_id = ain.getID(mRS);
        }
        int out_id = 0;
        if (aout != null) {
            out_id = aout.getID(mRS);
        }
        byte[] params = null;
        if (v != null) {
            params = v.getData();
        }
        mRS.nScriptForEachClipped(getID(mRS), slot, in_id, out_id, params, sc.xstart, sc.xend, sc.ystart, sc.yend, sc.zstart, sc.zend);
    }

    Script(int id, RenderScript rs) {
        super(id, rs);
    }


    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void bindAllocation(Allocation va, int slot) {
        mRS.validate();
        if (va != null) {
            mRS.nScriptBindAllocation(getID(mRS), va.getID(mRS), slot);
        } else {
            mRS.nScriptBindAllocation(getID(mRS), 0, slot);
        }
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void setVar(int index, float v) {
        mRS.nScriptSetVarF(getID(mRS), index, v);
    }
    public float getVarF(int index) {
        return mRS.nScriptGetVarF(getID(mRS), index);
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void setVar(int index, double v) {
        mRS.nScriptSetVarD(getID(mRS), index, v);
    }
    public double getVarD(int index) {
        return mRS.nScriptGetVarD(getID(mRS), index);
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void setVar(int index, int v) {
        mRS.nScriptSetVarI(getID(mRS), index, v);
    }
    public int getVarI(int index) {
        return mRS.nScriptGetVarI(getID(mRS), index);
    }


    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void setVar(int index, long v) {
        mRS.nScriptSetVarJ(getID(mRS), index, v);
    }
    public long getVarJ(int index) {
        return mRS.nScriptGetVarJ(getID(mRS), index);
    }


    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void setVar(int index, boolean v) {
        mRS.nScriptSetVarI(getID(mRS), index, v ? 1 : 0);
    }
    public boolean getVarB(int index) {
        return mRS.nScriptGetVarI(getID(mRS), index) > 0 ? true : false;
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void setVar(int index, BaseObj o) {
        mRS.nScriptSetVarObj(getID(mRS), index, (o == null) ? 0 : o.getID(mRS));
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void setVar(int index, FieldPacker v) {
        mRS.nScriptSetVarV(getID(mRS), index, v.getData());
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void setVar(int index, FieldPacker v, Element e, int[] dims) {
        mRS.nScriptSetVarVE(getID(mRS), index, v.getData(), e.getID(mRS), dims);
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    public void getVarV(int index, FieldPacker v) {
        mRS.nScriptGetVarV(getID(mRS), index, v.getData());
    }

    public void setTimeZone(String timeZone) {
        mRS.validate();
        try {
            mRS.nScriptSetTimeZone(getID(mRS), timeZone.getBytes("UTF-8"));
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Only intended for use by generated reflected code.
     *
     */
    public static class Builder {
        RenderScript mRS;

        Builder(RenderScript rs) {
            mRS = rs;
        }
    }


    /**
     * Only intended for use by generated reflected code.
     *
     */
    public static class FieldBase {
        protected Element mElement;
        protected Allocation mAllocation;

        protected void init(RenderScript rs, int dimx) {
            mAllocation = Allocation.createSized(rs, mElement, dimx, Allocation.USAGE_SCRIPT);
        }

        protected void init(RenderScript rs, int dimx, int usages) {
            mAllocation = Allocation.createSized(rs, mElement, dimx, Allocation.USAGE_SCRIPT | usages);
        }

        protected FieldBase() {
        }

        public Element getElement() {
            return mElement;
        }

        public Type getType() {
            return mAllocation.getType();
        }

        public Allocation getAllocation() {
            return mAllocation;
        }

        //@Override
        public void updateAllocation() {
        }
    }


    /**
     * Class used to specify clipping for a kernel launch.
     *
     */
    public static final class LaunchOptions {
        private int xstart = 0;
        private int ystart = 0;
        private int xend = 0;
        private int yend = 0;
        private int zstart = 0;
        private int zend = 0;
        private int strategy;

        /**
         * Set the X range.  If the end value is set to 0 the X dimension is not
         * clipped.
         *
         * @param xstartArg Must be >= 0
         * @param xendArg Must be >= xstartArg
         *
         * @return LaunchOptions
         */
        public LaunchOptions setX(int xstartArg, int xendArg) {
            if (xstartArg < 0 || xendArg <= xstartArg) {
                throw new RSIllegalArgumentException("Invalid dimensions");
            }
            xstart = xstartArg;
            xend = xendArg;
            return this;
        }

        /**
         * Set the Y range.  If the end value is set to 0 the Y dimension is not
         * clipped.
         *
         * @param ystartArg Must be >= 0
         * @param yendArg Must be >= ystartArg
         *
         * @return LaunchOptions
         */
        public LaunchOptions setY(int ystartArg, int yendArg) {
            if (ystartArg < 0 || yendArg <= ystartArg) {
                throw new RSIllegalArgumentException("Invalid dimensions");
            }
            ystart = ystartArg;
            yend = yendArg;
            return this;
        }

        /**
         * Set the Z range.  If the end value is set to 0 the Z dimension is not
         * clipped.
         *
         * @param zstartArg Must be >= 0
         * @param zendArg Must be >= zstartArg
         *
         * @return LaunchOptions
         */
        public LaunchOptions setZ(int zstartArg, int zendArg) {
            if (zstartArg < 0 || zendArg <= zstartArg) {
                throw new RSIllegalArgumentException("Invalid dimensions");
            }
            zstart = zstartArg;
            zend = zendArg;
            return this;
        }


        /**
         * Returns the current X start
         *
         * @return int current value
         */
        public int getXStart() {
            return xstart;
        }
        /**
         * Returns the current X end
         *
         * @return int current value
         */
        public int getXEnd() {
            return xend;
        }
        /**
         * Returns the current Y start
         *
         * @return int current value
         */
        public int getYStart() {
            return ystart;
        }
        /**
         * Returns the current Y end
         *
         * @return int current value
         */
        public int getYEnd() {
            return yend;
        }
        /**
         * Returns the current Z start
         *
         * @return int current value
         */
        public int getZStart() {
            return zstart;
        }
        /**
         * Returns the current Z end
         *
         * @return int current value
         */
        public int getZEnd() {
            return zend;
        }

    }
}

