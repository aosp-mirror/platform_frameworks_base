
package com.android.fountain;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;

public class ScriptC_Fountain
    extends android.renderscript.ScriptC
{
    public ScriptC_Fountain(RenderScript rs, Resources resources, boolean isRoot) {
        super(rs, resources, R.raw.fountain_bc, isRoot);
    }

    public void set_partColor(Float4 v) {
        FieldPacker fp = new FieldPacker(16);
        fp.addF32(v);
        setVar(0, fp);
    }
    public void set_partMesh(SimpleMesh v) {
        setVar(1, v.getID());
    }

    private ScriptField_Point mField_point;
    public void bind_point(ScriptField_Point f) {
        mField_point = f;
        if (f == null) {
            bindAllocation(null, 2);
        } else {
            bindAllocation(f.getAllocation(), 2);
        }
    }
    public ScriptField_Point get_point() {
        return mField_point;
    }


    public void invokable_addParticles(int count, int x, int y) {
        FieldPacker fp = new FieldPacker(12);
        fp.addI32(count);
        fp.addI32(x);
        fp.addI32(y);
        invokeV(0, fp);
    }
}

