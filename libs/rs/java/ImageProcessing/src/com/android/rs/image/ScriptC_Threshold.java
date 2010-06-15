
package com.android.rs.image;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;

public class ScriptC_Threshold
    extends android.renderscript.ScriptC
{
    private final static int mFieldIndex_height = 0;
    private final static int mFieldIndex_width = 1;
    private final static int mFieldIndex_radius = 2;
    private final static int mFieldIndex_InPixel = 3;
    private final static int mFieldIndex_OutPixel = 4;
    private final static int mFieldIndex_ScratchPixel = 5;

    private final static int mFieldIndex_inBlack = 6;
    private final static int mFieldIndex_outBlack = 7;
    private final static int mFieldIndex_inWhite = 8;
    private final static int mFieldIndex_outWhite = 9;
    private final static int mFieldIndex_gamma = 10;

    private final static int mFieldIndex_saturation = 11;
    private final static int mFieldIndex_hue = 12;

    private Allocation mField_InPixel;
    private Allocation mField_OutPixel;
    private Allocation mField_ScratchPixel;

    public ScriptC_Threshold(RenderScript rs, Resources resources, boolean isRoot) {
        super(rs, resources, R.raw.threshold_bc, isRoot);
    }

    public void bind_InPixel(Allocation f) {
        if (f != null) {
            //if (f.getType().getElement() != Element.ATTRIB_COLOR_U8_4(mRS)) {
                //throw new IllegalArgumentException("Element type mismatch.");
            //}
        }
        bindAllocation(f, mFieldIndex_InPixel);
        mField_InPixel = f;
    }
    public Allocation get_InPixel() {
        return mField_InPixel;
    }

    public void bind_OutPixel(Allocation f) {
        if (f != null) {
            //if (f.getType().getElement() != Element.ATTRIB_COLOR_U8_4(mRS)) {
                //throw new IllegalArgumentException("Element type mismatch.");
            //}
        }
        bindAllocation(f, mFieldIndex_OutPixel);
        mField_OutPixel = f;
    }
    public void bind_ScratchPixel(Allocation f) {
        if (f != null) {
            //if (f.getType().getElement() != Element.ATTRIB_COLOR_U8_4(mRS)) {
                //throw new IllegalArgumentException("Element type mismatch.");
            //}
        }
        bindAllocation(f, mFieldIndex_ScratchPixel);
        mField_ScratchPixel = f;
    }
    public Allocation get_OutPixel() {
        return mField_OutPixel;
    }

    public void set_height(int v) {
        setVar(mFieldIndex_height, v);
    }

    public void set_width(int v) {
        setVar(mFieldIndex_width, v);
    }

    public void set_radius(int v) {
        setVar(mFieldIndex_radius, v);
    }

    public void set_inBlack(float v) {
        setVar(mFieldIndex_inBlack, v);
    }
    public void set_outBlack(float v) {
        setVar(mFieldIndex_outBlack, v);
    }
    public void set_inWhite(float v) {
        setVar(mFieldIndex_inWhite, v);
    }
    public void set_outWhite(float v) {
        setVar(mFieldIndex_outWhite, v);
    }
    public void set_gamma(float v) {
        setVar(mFieldIndex_gamma, v);
    }

    public void set_saturation(float v) {
        setVar(mFieldIndex_saturation, v);
    }
    public void set_hue(float v) {
        setVar(mFieldIndex_hue, v);
    }

    private final static int mInvokableIndex_Filter = 4;
    public void invokable_Filter() {
        invoke(mInvokableIndex_Filter);
    }

    private final static int mInvokableIndex_FilterBenchmark = 5;
    public void invokable_FilterBenchmark() {
        invoke(mInvokableIndex_FilterBenchmark);
    }
}

