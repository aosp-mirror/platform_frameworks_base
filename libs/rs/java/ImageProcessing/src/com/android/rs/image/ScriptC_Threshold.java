
package com.android.rs.image;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;

public class ScriptC_Threshold
    extends android.renderscript.ScriptC
{
    private final static int mFieldIndex_height = 0;
    private final static int mFieldIndex_width = 1;
    private final static int mFieldIndex_threshold = 2;
    private final static int mFieldIndex_InPixel = 3;
    private final static int mFieldIndex_OutPixel = 4;

    private Allocation mField_InPixel;
    private Allocation mField_OutPixel;

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
    public Allocation get_OutPixel() {
        return mField_OutPixel;
    }

    public void set_height(int v) {
        setVar(mFieldIndex_height, v);
    }

    public void set_width(int v) {
        setVar(mFieldIndex_width, v);
    }

    public void set_threshold(float v) {
        setVar(mFieldIndex_threshold, v);
    }

    private final static int mInvokableIndex_Filter = 0;
    public void invokable_Filter() {
        invokeData(mInvokableIndex_Filter);
    }
}

