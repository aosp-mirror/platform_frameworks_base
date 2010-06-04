
package com.android.modelviewer;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;



public class ScriptC_ModelViewer
    extends android.renderscript.ScriptC
{
    public ScriptC_ModelViewer(RenderScript rs, Resources resources, boolean isRoot) {
        super(rs, resources, R.raw.modelviewer_bc, isRoot);
    }

    public void set_gPVBackground(ProgramVertex v) {
        setVar(0, v.getID());
    }

    public void set_gPFBackground(ProgramFragment v) {
        setVar(1, v.getID());
    }

    public void set_gTGrid(Allocation v) {
        setVar(2, v.getID());
    }

    public void set_gTestMesh(SimpleMesh v) {
        setVar(3, v.getID());
    }

    public void set_gPFSBackground(ProgramStore v) {
        setVar(4, v.getID());
    }

    public void set_gRotate(float v) {
        setVar(5, v);
    }

}

