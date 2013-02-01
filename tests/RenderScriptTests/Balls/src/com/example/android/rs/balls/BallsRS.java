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

package com.example.android.rs.balls;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;


public class BallsRS {
    public static final int PART_COUNT = 4000;

    public BallsRS() {
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private ScriptC_balls mScript;
    private ScriptC_ball_physics mPhysicsScript;
    private ProgramFragment mPFPoints;
    private ScriptField_Point mPoints;
    private ScriptField_VpConsts mVpConsts;
    private ScriptField_BallGrid mGrid;
    private ScriptField_Ball mBalls;
    private Allocation mGridCache;

    void updateProjectionMatrices() {
        mVpConsts = new ScriptField_VpConsts(mRS, 1,
                                             Allocation.USAGE_SCRIPT |
                                             Allocation.USAGE_GRAPHICS_CONSTANTS);
        ScriptField_VpConsts.Item i = new ScriptField_VpConsts.Item();
        Matrix4f mvp = new Matrix4f();
        mvp.loadOrtho(0, mRS.getWidth(), mRS.getHeight(), 0, -1, 1);
        i.MVP = mvp;
        mVpConsts.set(i, 0, true);
    }

    private void createProgramVertex() {
        updateProjectionMatrices();

        ProgramVertex.Builder sb = new ProgramVertex.Builder(mRS);
        String t =  "varying vec4 varColor;\n" +
                    "void main() {\n" +
                    "  vec4 pos = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                    "  pos.xy = ATTRIB_position;\n" +
                    "  gl_Position = UNI_MVP * pos;\n" +
                    "  varColor = ATTRIB_color;\n" +
                    "  gl_PointSize = 12.0;\n" +
                    "}\n";
        sb.setShader(t);
        sb.addConstant(mVpConsts.getType());
        sb.addInput(mPoints.getElement());
        ProgramVertex pvs = sb.create();
        pvs.bindConstants(mVpConsts.getAllocation(), 0);
        mRS.bindProgramVertex(pvs);
    }

    private Allocation loadTexture(int id) {
        final Allocation allocation =
            Allocation.createFromBitmapResource(mRS, mRes,
                id, Allocation.MipmapControl.MIPMAP_NONE,
                Allocation.USAGE_GRAPHICS_TEXTURE);
        return allocation;
    }

    ProgramStore BLEND_ADD_DEPTH_NONE(RenderScript rs) {
        ProgramStore.Builder builder = new ProgramStore.Builder(rs);
        builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        builder.setBlendFunc(ProgramStore.BlendSrcFunc.ONE, ProgramStore.BlendDstFunc.ONE);
        builder.setDitherEnabled(false);
        builder.setDepthMaskEnabled(false);
        return builder.create();
    }

    private void createPF(int width, int height) {
        ProgramFragmentFixedFunction.Builder pfb = new ProgramFragmentFixedFunction.Builder(mRS);
        pfb.setPointSpriteTexCoordinateReplacement(true);
        pfb.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.MODULATE,
                           ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        pfb.setVaryingColor(true);
        mPFPoints = pfb.create();
    }

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;

        createPF(width, height);

        mPFPoints.bindTexture(loadTexture(R.drawable.flares), 0);

        mPoints = new ScriptField_Point(mRS, PART_COUNT, Allocation.USAGE_SCRIPT);

        Mesh.AllocationBuilder smb = new Mesh.AllocationBuilder(mRS);
        smb.addVertexAllocation(mPoints.getAllocation());
        smb.addIndexSetType(Mesh.Primitive.POINT);
        Mesh smP = smb.create();

        mGrid = ScriptField_BallGrid.create2D(mRS, (width + 99) / 100, (height + 99) / 100);
        mGridCache = Allocation.createSized(mRS, Element.F32_2(mRS), PART_COUNT);
        mBalls = new ScriptField_Ball(mRS, PART_COUNT, Allocation.USAGE_SCRIPT);

        mPhysicsScript = new ScriptC_ball_physics(mRS);
        mPhysicsScript.set_gGridCache(mGridCache);
        mPhysicsScript.set_gBalls(mBalls.getAllocation());

        mScript = new ScriptC_balls(mRS);
        mScript.set_partMesh(smP);
        mScript.set_physics_script(mPhysicsScript);
        mScript.bind_point(mPoints);
        mScript.bind_balls(mBalls);
        mScript.set_gGrid(mGrid.getAllocation());
        mScript.bind_gGridCache(mGridCache);

        mScript.set_gPFPoints(mPFPoints);
        createProgramVertex();

        mRS.bindProgramStore(BLEND_ADD_DEPTH_NONE(mRS));

        mPhysicsScript.set_gMinPos(new Float2(5, 5));
        mPhysicsScript.set_gMaxPos(new Float2(width - 5, height - 5));
        mPhysicsScript.set_gGrid(mGrid.getAllocation());

        mScript.invoke_initParts(width, height);

        mRS.bindRootScript(mScript);
    }

    public void newTouchPosition(float x, float y, float pressure, int id) {
        mPhysicsScript.invoke_touch(x, y, pressure * mRS.getWidth() / 1280, id);
    }

    public void setAccel(float x, float y) {
        mPhysicsScript.set_gGravityVector(new Float2(x, y));
    }

}
