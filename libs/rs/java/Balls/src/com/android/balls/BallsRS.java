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

package com.android.balls;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;

public class BallsRS {
    public static final int PART_COUNT = 800;

    public BallsRS() {
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private ScriptC_balls mScript;
    private ScriptC_ball_physics mPhysicsScript;
    private ProgramFragment mPFLines;
    private ProgramFragment mPFPoints;
    private ProgramVertex mPV;
    private ScriptField_Point mPoints;
    private ScriptField_Point mArcs;
    private ScriptField_VpConsts mVpConsts;

    void updateProjectionMatrices() {
        mVpConsts = new ScriptField_VpConsts(mRS, 1);
        ScriptField_VpConsts.Item i = new ScriptField_VpConsts.Item();
        Matrix4f mvp = new Matrix4f();
        mvp.loadOrtho(0, mRS.getWidth(), mRS.getHeight(), 0, -1, 1);
        i.MVP = mvp;
        mVpConsts.set(i, 0, true);
    }

    private void createProgramVertex() {
        updateProjectionMatrices();

        ProgramVertex.ShaderBuilder sb = new ProgramVertex.ShaderBuilder(mRS);
        String t =  "varying vec4 varColor;\n" +
                    "void main() {\n" +
                    "  vec4 pos = vec4(0.0, 0.0, 0.0, 1.0);\n" +
                    "  pos.xy = ATTRIB_position;\n" +
                    "  gl_Position = UNI_MVP * pos;\n" +
                    "  varColor = ATTRIB_color;\n" +
                    "  gl_PointSize = ATTRIB_size;\n" +
                    "}\n";
        sb.setShader(t);
        sb.addConstant(mVpConsts.getType());
        sb.addInput(mPoints.getElement());
        ProgramVertex pvs = sb.create();
        pvs.bindConstants(mVpConsts.getAllocation(), 0);
        mRS.contextBindProgramVertex(pvs);
    }

    private Allocation loadTexture(int id) {
        final Allocation allocation = Allocation.createFromBitmapResource(mRS, mRes,
                id, Element.RGB_565(mRS), false);
        allocation.uploadToTexture(0);
        return allocation;
    }

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;

        ProgramFragment.Builder pfb = new ProgramFragment.Builder(rs);
        pfb.setPointSpriteTexCoordinateReplacement(true);
        pfb.setTexture(ProgramFragment.Builder.EnvMode.MODULATE,
                           ProgramFragment.Builder.Format.RGBA, 0);
        pfb.setVaryingColor(true);
        mPFPoints = pfb.create();

        pfb = new ProgramFragment.Builder(rs);
        pfb.setVaryingColor(true);
        mPFLines = pfb.create();

        mPFPoints.bindTexture(loadTexture(R.drawable.flares), 0);

        mPoints = new ScriptField_Point(mRS, PART_COUNT);
        mArcs = new ScriptField_Point(mRS, PART_COUNT * 2);

        Mesh.AllocationBuilder smb = new Mesh.AllocationBuilder(mRS);
        smb.addVertexAllocation(mPoints.getAllocation());
        smb.addIndexType(Primitive.POINT);
        Mesh smP = smb.create();

        smb = new Mesh.AllocationBuilder(mRS);
        smb.addVertexAllocation(mArcs.getAllocation());
        smb.addIndexType(Primitive.LINE);
        Mesh smA = smb.create();

        mPhysicsScript = new ScriptC_ball_physics(mRS, mRes, R.raw.ball_physics);

        mScript = new ScriptC_balls(mRS, mRes, R.raw.balls);
        mScript.set_partMesh(smP);
        mScript.set_arcMesh(smA);
        mScript.set_physics_script(mPhysicsScript);
        mScript.bind_point(mPoints);
        mScript.bind_arc(mArcs);
        mScript.bind_balls1(new ScriptField_Ball(mRS, PART_COUNT));
        mScript.bind_balls2(new ScriptField_Ball(mRS, PART_COUNT));

        mScript.set_gPFLines(mPFLines);
        mScript.set_gPFPoints(mPFPoints);
        createProgramVertex();

        mRS.contextBindProgramStore(ProgramStore.BLEND_ADD_DEPTH_NO_DEPTH(mRS));

        mPhysicsScript.set_gMinPos(new Float2(5, 5));
        mPhysicsScript.set_gMaxPos(new Float2(width - 5, height - 5));

        mScript.invoke_initParts(width, height);

        mRS.contextBindRootScript(mScript);
    }

    public void newTouchPosition(float x, float y, float pressure, int id) {
        mPhysicsScript.set_touchX(x);
        mPhysicsScript.set_touchY(y);
        mPhysicsScript.set_touchPressure(pressure);
    }

    public void setAccel(float x, float y) {
        mPhysicsScript.set_gGravityVector(new Float2(x, y));
    }

}
