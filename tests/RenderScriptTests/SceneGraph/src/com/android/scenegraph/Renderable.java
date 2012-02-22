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
import com.android.scenegraph.MatrixTransform;
import com.android.scenegraph.SceneManager;
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
    HashMap<String, ShaderParam> mSourceParams;

    RenderState mRenderState;
    Transform mTransform;

    String mMeshName;
    String mMeshIndexName;

    public String mMaterialName;

    ScriptField_Renderable_s mField;
    ScriptField_Renderable_s.Item mData;

    public Renderable() {
        mSourceParams = new HashMap<String, ShaderParam>();
        mData = new ScriptField_Renderable_s.Item();
    }

    public void setCullType(int cull) {
        mData.cullType = cull;
    }

    public void setRenderState(RenderState renderState) {
        mRenderState = renderState;
        if (mField != null) {
            RenderScriptGL rs = SceneManager.getRS();
            updateFieldItem(rs);
            mField.set(mData, 0, true);
        }
    }

    public void setMesh(Mesh mesh) {
        mData.mesh = mesh;
        if (mField != null) {
            mField.set_mesh(0, mData.mesh, true);
        }
    }

    public void setMesh(String mesh, String indexName) {
        mMeshName = mesh;
        mMeshIndexName = indexName;
    }

    public void setMaterialName(String name) {
        mMaterialName = name;
    }

    public Transform getTransform() {
        return mTransform;
    }

    public void setTransform(Transform t) {
        mTransform = t;
        if (mField != null) {
            RenderScriptGL rs = SceneManager.getRS();
            updateFieldItem(rs);
            mField.set(mData, 0, true);
        }
    }

    public void appendSourceParams(ShaderParam p) {
        mSourceParams.put(p.getParamName(), p);
        // Possibly lift this restriction later
        if (mField != null) {
            throw new RuntimeException("Can't add source params to objects that are rendering");
        }
    }

    public void resolveMeshData(Mesh mesh) {
        mData.mesh = mesh;
        if (mData.mesh == null) {
            Log.v("DRAWABLE: ", "*** NO MESH *** " + mMeshName);
            return;
        }
        int subIndexCount = mData.mesh.getPrimitiveCount();
        if (subIndexCount == 1 || mMeshIndexName == null) {
            mData.meshIndex = 0;
        } else {
            for (int i = 0; i < subIndexCount; i ++) {
                if (mData.mesh.getIndexSetAllocation(i).getName().equals(mMeshIndexName)) {
                    mData.meshIndex = i;
                    break;
                }
            }
        }
        if (mField != null) {
            mField.set(mData, 0, true);
        }
    }

    void updateTextures(RenderScriptGL rs) {
        Iterator<ShaderParam> allParamsIter = mSourceParams.values().iterator();
        int paramIndex = 0;
        while (allParamsIter.hasNext()) {
            ShaderParam sp = allParamsIter.next();
            if (sp instanceof TextureParam) {
                TextureParam p = (TextureParam)sp;
                TextureBase tex = p.getTexture();
                if (tex != null) {
                    mData.pf_textures[paramIndex++] = tex.getRsData(false).getAllocation();
                }
            }
        }
        ProgramFragment pf = mRenderState.mFragment.mProgram;
        mData.pf_num_textures = pf != null ? Math.min(pf.getTextureCount(), paramIndex) : 0;
        if (mField != null) {
            mField.set_pf_textures(0, mData.pf_textures, true);
            mField.set_pf_num_textures(0, mData.pf_num_textures, true);
        }
    }

    public void setVisible(boolean vis) {
        mData.cullType = vis ? 0 : 2;
        if (mField != null) {
            mField.set_cullType(0, mData.cullType, true);
        }
    }

    ScriptField_Renderable_s getRsField(RenderScriptGL rs, Resources res) {
        if (mField != null) {
            return mField;
        }
        updateFieldItem(rs);
        updateTextures(rs);

        mField = new ScriptField_Renderable_s(rs, 1);
        mField.set(mData, 0, true);

        return mField;
    }

    void updateVertexConstants(RenderScriptGL rs) {
        Allocation pvParams = null, vertexConstants = null;
        VertexShader pv = mRenderState.mVertex;
        if (pv != null && pv.getObjectConstants() != null) {
            vertexConstants = Allocation.createTyped(rs, pv.getObjectConstants());
            Element vertexConst = vertexConstants.getType().getElement();
            pvParams = ShaderParam.fillInParams(vertexConst, mSourceParams,
                                                mTransform).getAllocation();
        }
        mData.pv_const = vertexConstants;
        mData.pv_constParams = pvParams;
    }

    void updateFragmentConstants(RenderScriptGL rs) {
        Allocation pfParams = null, fragmentConstants = null;
        FragmentShader pf = mRenderState.mFragment;
        if (pf != null && pf.getObjectConstants() != null) {
            fragmentConstants = Allocation.createTyped(rs, pf.getObjectConstants());
            Element fragmentConst = fragmentConstants.getType().getElement();
            pfParams = ShaderParam.fillInParams(fragmentConst, mSourceParams,
                                                mTransform).getAllocation();
        }
        mData.pf_const = fragmentConstants;
        mData.pf_constParams = pfParams;
    }

    void updateFieldItem(RenderScriptGL rs) {
        if (mRenderState == null) {
            mRenderState = SceneManager.getDefaultState();
        }
        if (mTransform == null) {
            mTransform = SceneManager.getDefaultTransform();
        }
        updateVertexConstants(rs);
        updateFragmentConstants(rs);

        mData.transformMatrix = mTransform.getRSData().getAllocation();

        mData.name = getNameAlloc(rs);
        mData.render_state = mRenderState.getRSData().getAllocation();
        mData.bVolInitialized = 0;
    }
}





