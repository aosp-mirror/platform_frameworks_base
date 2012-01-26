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

package com.android.scenegraph;

import java.lang.Math;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;

import com.android.scenegraph.Float4Param;
import com.android.scenegraph.ShaderParam;
import com.android.scenegraph.TransformParam;

import android.content.res.Resources;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.Element.DataType;
import android.renderscript.Matrix4f;
import android.renderscript.Mesh;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.RenderScriptGL;
import android.util.Log;

/**
 * @hide
 */
public class Renderable extends RenderableBase {
    Allocation mVertexConstants;
    Allocation mVertexParams;
    Allocation mFragmentConstants;
    Allocation mFragmentParams;
    ArrayList<Allocation> mFragmentTextures;

    HashMap<String, ShaderParam> mSourceParams;

    ArrayList<ShaderParam> mVertexParamList;
    ArrayList<ShaderParam> mFragmentParamList;

    Mesh mMesh;
    int mMeshIndex;

    int mCullType;

    RenderState mRenderState;

    Transform mTransform;

    String mMeshName;
    String mMeshIndexName;

    public String mMaterialName;

    // quick hack to prototype
    int sceneIndex;

    ScriptField_Renderable_s mRsField;
    ScriptField_Renderable_s.Item mRsFieldItem;

    public Renderable() {
        mSourceParams = new HashMap<String, ShaderParam>();
        mVertexParamList = new ArrayList<ShaderParam>();
        mFragmentParamList = new ArrayList<ShaderParam>();
    }

    public void setCullType(int cull) {
        mCullType = cull;
    }

    public void setRenderState(RenderState renderState) {
        mRenderState = renderState;
    }

    public void setMesh(Mesh mesh) {
        mMesh = mesh;
    }

    public void setMesh(String mesh, String indexName) {
        mMeshName = mesh;
        mMeshIndexName = indexName;
    }

    public void setMaterialName(String name) {
        mMaterialName = name;
    }

    public void setTransform(Transform t) {
        mTransform = t;
    }

    public void appendSourceParams(ShaderParam p) {
        mSourceParams.put(p.getParamName(), p);
    }

    public void resolveMeshData(Mesh mMesh) {
        mMesh = mMesh;
        if (mMesh == null) {
            Log.v("DRAWABLE: ", "*** NO MESH *** " + mMeshName);
            return;
        }
        int subIndexCount = mMesh.getPrimitiveCount();
        if (subIndexCount == 1 || mMeshIndexName == null) {
            mMeshIndex = 0;
        } else {
            for (int i = 0; i < subIndexCount; i ++) {
                if (mMesh.getIndexSetAllocation(i).getName().equals(mMeshIndexName)) {
                    mMeshIndex = i;
                    break;
                }
            }
        }

        mRsFieldItem.mesh = mMesh;
        mRsFieldItem.meshIndex = mMeshIndex;

        mRsField.set(mRsFieldItem, 0, true);
    }

    void updateTextures(RenderScriptGL rs, Resources res) {
        Iterator<ShaderParam> allParamsIter = mSourceParams.values().iterator();
        int paramIndex = 0;
        while (allParamsIter.hasNext()) {
            ShaderParam sp = allParamsIter.next();
            if (sp instanceof TextureParam) {
                TextureParam p = (TextureParam)sp;
                mRsFieldItem.pf_textures[paramIndex++] = p.getTexture().getRsData(rs, res);
            }
        }
        ProgramFragment pf = mRenderState.mFragment.mProgram;
        mRsFieldItem.pf_num_textures = pf != null ? Math.min(pf.getTextureCount(), paramIndex) : 0;
        mRsField.set(mRsFieldItem, 0, true);
    }

    void updateTextures(RenderScriptGL rs, Allocation a, int slot) {
        getRsFieldItem(rs, null);
        mRsFieldItem.pf_textures[slot] = a;
    }

    public void setVisible(RenderScriptGL rs, boolean vis) {
        getRsField(rs, null);
        mRsFieldItem.cullType = vis ? 0 : 2;
        mRsField.set(mRsFieldItem, 0, true);
    }

    void linkConstants() {
        // Assign all the fragment params
        if (mFragmentConstants != null) {
            Element fragmentConst = mFragmentConstants.getType().getElement();
            ShaderParam.fillInParams(fragmentConst, mSourceParams, mTransform, mFragmentParamList);
        }

        // Assign all the vertex params
        if (mVertexConstants != null) {
            Element vertexConst = mVertexConstants.getType().getElement();
            ShaderParam.fillInParams(vertexConst, mSourceParams, mTransform, mVertexParamList);
        }
    }

    ScriptField_Renderable_s getRsField(RenderScriptGL rs, Resources res) {
        if (mRsField != null) {
            return mRsField;
        }
        getRsFieldItem(rs, res);

        mRsField = new ScriptField_Renderable_s(rs, 1);
        mRsField.set(mRsFieldItem, 0, true);

        return mRsField;
    }

    void getRsFieldItem(RenderScriptGL rs, Resources res) {
        if (mRsFieldItem != null) {
            return;
        }

        VertexShader pv = mRenderState.mVertex;
        if (pv != null && pv.getObjectConstants() != null) {
            mVertexConstants = Allocation.createTyped(rs, pv.getObjectConstants());
        }
        FragmentShader pf = mRenderState.mFragment;
        if (pf != null && pf.getObjectConstants() != null) {
            mFragmentConstants = Allocation.createTyped(rs, pf.getObjectConstants());
        }

        // Very important step that links available inputs and the constants vertex and
        // fragment shader request
        linkConstants();

        ScriptField_ShaderParam_s pvParams = null, pfParams = null;
        int paramCount = mVertexParamList.size();
        if (paramCount != 0) {
            pvParams = new ScriptField_ShaderParam_s(rs, paramCount);
            for (int i = 0; i < paramCount; i++) {
                pvParams.set(mVertexParamList.get(i).getRSData(rs), i, false);
            }
            pvParams.copyAll();
        }

        paramCount = mFragmentParamList.size();
        if (paramCount != 0) {
            pfParams = new ScriptField_ShaderParam_s(rs, paramCount);
            for (int i = 0; i < paramCount; i++) {
                pfParams.set(mFragmentParamList.get(i).getRSData(rs), i, false);
            }
            pfParams.copyAll();
        }

        mRsFieldItem = new ScriptField_Renderable_s.Item();
        mRsFieldItem.mesh = mMesh;
        mRsFieldItem.meshIndex = mMeshIndex;
        mRsFieldItem.pv_const = mVertexConstants;
        mRsFieldItem.pv_constParams = pvParams != null ? pvParams.getAllocation() : null;
        mRsFieldItem.pf_const = mFragmentConstants;
        mRsFieldItem.pf_constParams = pfParams != null ? pfParams.getAllocation() : null;
        if (mTransform != null) {
            mRsFieldItem.transformMatrix = mTransform.getRSData(rs).getAllocation();
        }
        mRsFieldItem.name = SceneManager.getStringAsAllocation(rs, getName());
        mRsFieldItem.render_state = mRenderState.getRSData(rs, res).getAllocation();
        mRsFieldItem.bVolInitialized = 0;
        mRsFieldItem.cullType = mCullType;
    }
}





