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

package com.android.rs.test;

import android.content.Context;
import android.content.res.Resources;
import android.renderscript.*;
import android.renderscript.Mesh.*;

public class UT_mesh extends UnitTest {
    private Resources mRes;

    Mesh mesh;

    protected UT_mesh(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Mesh", ctx);
        mRes = res;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_mesh s) {
        Allocation vAlloc0 = Allocation.createSized(RS, Element.F32(RS), 10);
        Allocation vAlloc1 = Allocation.createSized(RS, Element.F32_2(RS), 10);

        Allocation iAlloc0 = Allocation.createSized(RS, Element.I16(RS), 10);
        Allocation iAlloc2 = Allocation.createSized(RS, Element.I16(RS), 10);

        Mesh.AllocationBuilder mBuilder = new Mesh.AllocationBuilder(RS);
        mBuilder.addVertexAllocation(vAlloc0);
        mBuilder.addVertexAllocation(vAlloc1);

        mBuilder.addIndexSetAllocation(iAlloc0, Primitive.POINT);
        mBuilder.addIndexSetType(Primitive.LINE);
        mBuilder.addIndexSetAllocation(iAlloc2, Primitive.TRIANGLE);

        s.set_mesh(mBuilder.create());
        s.set_vertexAlloc0(vAlloc0);
        s.set_vertexAlloc1(vAlloc1);
        s.set_indexAlloc0(iAlloc0);
        s.set_indexAlloc2(iAlloc2);
    }

    private void testScriptSide(RenderScript pRS) {
        ScriptC_mesh s = new ScriptC_mesh(pRS, mRes, R.raw.mesh);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.invoke_mesh_test();
        pRS.finish();
        waitForMessage();
    }

    private void testJavaSide(RenderScript RS) {
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        testScriptSide(pRS);
        testJavaSide(pRS);
        passTest();
        pRS.destroy();
    }
}
