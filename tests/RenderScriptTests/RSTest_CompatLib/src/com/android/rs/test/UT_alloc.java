/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.rs.test_compat;

import android.content.Context;
import android.content.res.Resources;
import android.support.v8.renderscript.*;

public class UT_alloc extends UnitTest {
    private Resources mRes;

    protected UT_alloc(RSTestCore rstc, Resources res, Context ctx) {
        super(rstc, "Alloc", ctx);
        mRes = res;
    }

    private void initializeGlobals(RenderScript RS, ScriptC_alloc s) {
        Type.Builder typeBuilder = new Type.Builder(RS, Element.I32(RS));
        int X = 5;
        int Y = 7;
        int Z = 0;
        s.set_dimX(X);
        s.set_dimY(Y);
        s.set_dimZ(Z);
        typeBuilder.setX(X).setY(Y);
        Allocation A = Allocation.createTyped(RS, typeBuilder.create());
        s.bind_a(A);
        s.set_aRaw(A);

        typeBuilder = new Type.Builder(RS, Element.I32(RS));
        typeBuilder.setX(X).setY(Y).setFaces(true);
        Allocation AFaces = Allocation.createTyped(RS, typeBuilder.create());
        s.set_aFaces(AFaces);
        typeBuilder.setFaces(false).setMipmaps(true);
        Allocation ALOD = Allocation.createTyped(RS, typeBuilder.create());
        s.set_aLOD(ALOD);
        typeBuilder.setFaces(true).setMipmaps(true);
        Allocation AFacesLOD = Allocation.createTyped(RS, typeBuilder.create());
        s.set_aFacesLOD(AFacesLOD);

        return;
    }

    public void run() {
        RenderScript pRS = RenderScript.create(mCtx);
        ScriptC_alloc s = new ScriptC_alloc(pRS);
        pRS.setMessageHandler(mRsMessage);
        initializeGlobals(pRS, s);
        s.forEach_root(s.get_aRaw());
        s.invoke_alloc_test();
        pRS.finish();
        waitForMessage();
        pRS.destroy();
    }
}
