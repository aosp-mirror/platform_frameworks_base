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

package com.example.android.rs.fountainfbo;

import android.content.res.Resources;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Mesh;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramFragmentFixedFunction;
import android.renderscript.RenderScriptGL;
import android.renderscript.Type;

public class FountainFboRS {
    public static final int PART_COUNT = 50000;

    public FountainFboRS() {
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private ScriptC_fountainfbo mScript;
    private Allocation mColorBuffer;
    private ProgramFragment mProgramFragment;
    private ProgramFragment mTextureProgramFragment;
    public void init(RenderScriptGL rs, Resources res) {
      mRS = rs;
      mRes = res;

      ScriptField_Point points = new ScriptField_Point(mRS, PART_COUNT);

      Mesh.AllocationBuilder smb = new Mesh.AllocationBuilder(mRS);
      smb.addVertexAllocation(points.getAllocation());
      smb.addIndexSetType(Mesh.Primitive.POINT);
      Mesh sm = smb.create();

      mScript = new ScriptC_fountainfbo(mRS, mRes, R.raw.fountainfbo);
      mScript.set_partMesh(sm);
      mScript.bind_point(points);

      ProgramFragmentFixedFunction.Builder pfb = new ProgramFragmentFixedFunction.Builder(rs);
      pfb.setVaryingColor(true);
      mProgramFragment = pfb.create();
      mScript.set_gProgramFragment(mProgramFragment);

      /* Second fragment shader to use a texture (framebuffer object) to draw with */
      pfb.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
          ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);

      /* Set the fragment shader in the Renderscript runtime */
      mTextureProgramFragment = pfb.create();
      mScript.set_gTextureProgramFragment(mTextureProgramFragment);

      /* Create the allocation for the color buffer */
      Type.Builder colorBuilder = new Type.Builder(mRS, Element.RGBA_8888(mRS));
      colorBuilder.setX(256).setY(256);
      mColorBuffer = Allocation.createTyped(mRS, colorBuilder.create(),
      Allocation.USAGE_GRAPHICS_TEXTURE |
      Allocation.USAGE_GRAPHICS_RENDER_TARGET);

      /* Set the allocation in the Renderscript runtime */
      mScript.set_gColorBuffer(mColorBuffer);

      mRS.bindRootScript(mScript);
  }

    boolean holdingColor[] = new boolean[10];
    public void newTouchPosition(float x, float y, float pressure, int id) {
        if (id >= holdingColor.length) {
            return;
        }
        int rate = (int)(pressure * pressure * 500.f);
        if (rate > 500) {
            rate = 500;
        }
        if (rate > 0) {
            mScript.invoke_addParticles(rate, x, y, id, !holdingColor[id]);
            holdingColor[id] = true;
        } else {
            holdingColor[id] = false;
        }

    }
}

