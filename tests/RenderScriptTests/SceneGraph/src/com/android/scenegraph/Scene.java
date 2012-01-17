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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.renderscript.RenderScriptGL;
import android.renderscript.Mesh;
import android.renderscript.*;
import android.content.res.Resources;
import android.util.Log;
import android.os.AsyncTask;

/**
 * @hide
 */
public class Scene extends SceneGraphBase {
    private static String TIMER_TAG = "TIMER";

    private class ImageLoaderTask extends AsyncTask<String, Void, Boolean> {
        protected Boolean doInBackground(String... names) {
            long start = System.currentTimeMillis();
            for (int i = 0; i < mRenderables.size(); i ++) {
                Renderable dI = (Renderable)mRenderables.get(i);
                dI.updateTextures(mRS, mRes);
            }
            long end = System.currentTimeMillis();
            Log.v(TIMER_TAG, "Texture init time: " + (end - start));
            return new Boolean(true);
        }

        protected void onPostExecute(Boolean result) {
        }
    }

    CompoundTransform mRootTransforms;
    HashMap<String, Transform> mTransformMap;
    ArrayList<RenderPass> mRenderPasses;
    ArrayList<LightBase> mLights;
    ArrayList<Camera> mCameras;
    ArrayList<RenderableBase> mRenderables;
    HashMap<String, RenderableBase> mRenderableMap;
    ArrayList<Texture2D> mTextures;

    HashMap<String, ArrayList<Renderable> > mRenderableMeshMap;

    // RS Specific stuff
    ScriptField_SgTransform mTransformRSData;

    RenderScriptGL mRS;
    Resources mRes;

    ScriptField_RenderPass_s mRenderPassAlloc;

    public Scene() {
        mRenderPasses = new ArrayList<RenderPass>();
        mLights = new ArrayList<LightBase>();
        mCameras = new ArrayList<Camera>();
        mRenderables = new ArrayList<RenderableBase>();
        mRenderableMap = new HashMap<String, RenderableBase>();
        mRenderableMeshMap = new HashMap<String, ArrayList<Renderable> >();
        mTextures = new ArrayList<Texture2D>();
        mRootTransforms = new CompoundTransform();
        mRootTransforms.setName("_scene_root_");
        mTransformMap = new HashMap<String, Transform>();
    }

    public void appendTransform(Transform t) {
        mRootTransforms.appendChild(t);
    }

    // temporary
    public void addToTransformMap(Transform t) {
        mTransformMap.put(t.getName(), t);
    }

    public Transform getTransformByName(String name) {
        return mTransformMap.get(name);
    }

    public void appendRenderPass(RenderPass p) {
        mRenderPasses.add(p);
    }

    public void clearRenderPasses() {
        mRenderPasses.clear();
    }

    public void appendLight(LightBase l) {
        mLights.add(l);
    }

    public void appendCamera(Camera c) {
        mCameras.add(c);
    }

    public ArrayList<Camera> getCameras() {
        return mCameras;
    }

    public ArrayList<LightBase> getLights() {
        return mLights;
    }

    public void appendRenderable(RenderableBase d) {
        mRenderables.add(d);
        mRenderableMap.put(d.getName(), d);
    }

    public ArrayList<RenderableBase> getRenderables() {
        return mRenderables;
    }

    public RenderableBase getRenderableByName(String name) {
        return mRenderableMap.get(name);
    }

    public void appendTextures(Texture2D tex) {
        mTextures.add(tex);
    }

    public void assignRenderStateToMaterial(RenderState renderState, String regex) {
        Pattern pattern = Pattern.compile(regex);
        int numRenderables = mRenderables.size();
        for (int i = 0; i < numRenderables; i ++) {
            Renderable shape = (Renderable)mRenderables.get(i);
            Matcher m = pattern.matcher(shape.mMaterialName);
            if (m.find()) {
                shape.setRenderState(renderState);
            }
        }
    }

    public void assignRenderState(RenderState renderState) {
        int numRenderables = mRenderables.size();
        for (int i = 0; i < numRenderables; i ++) {
            Renderable shape = (Renderable)mRenderables.get(i);
            shape.setRenderState(renderState);
        }
    }

    public void meshLoaded(Mesh m) {
        ArrayList<Renderable> entries = mRenderableMeshMap.get(m.getName());
        int numEntries = entries.size();
        for (int i = 0; i < numEntries; i++) {
            Renderable d = entries.get(i);
            d.resolveMeshData(m);
            //mRenderablesField.set(d.getRsField(mRS, mRes), d.sceneIndex, true);
        }
    }

    void addToMeshMap(Renderable d) {
        ArrayList<Renderable> entries = mRenderableMeshMap.get(d.mMeshName);
        if (entries == null) {
            entries = new ArrayList<Renderable>();
            mRenderableMeshMap.put(d.mMeshName, entries);
        }
        entries.add(d);
    }

    public void destroyRS(SceneManager sceneManager) {
        mTransformRSData = null;
        sceneManager.mRenderLoop.bind_gRootNode(mTransformRSData);
        sceneManager.mRenderLoop.set_gRenderableObjects(null);
        mRenderPassAlloc = null;
        sceneManager.mRenderLoop.set_gRenderPasses(null);
        sceneManager.mRenderLoop.bind_gFrontToBack(null);
        sceneManager.mRenderLoop.bind_gBackToFront(null);
        sceneManager.mRenderLoop.set_gCameras(null);

        mTransformMap = null;
        mRenderPasses = null;
        mLights = null;
        mCameras = null;
        mRenderables = null;
        mRenderableMap = null;
        mTextures = null;
        mRenderableMeshMap = null;
        mRootTransforms = null;
    }

    public void initRenderPassRS(RenderScriptGL rs, SceneManager sceneManager) {
        if (mRenderPasses.size() != 0) {
            mRenderPassAlloc = new ScriptField_RenderPass_s(mRS, mRenderPasses.size());
            for (int i = 0; i < mRenderPasses.size(); i ++) {
                mRenderPassAlloc.set(mRenderPasses.get(i).getRsField(mRS, mRes), i, false);
            }
            mRenderPassAlloc.copyAll();
            sceneManager.mRenderLoop.set_gRenderPasses(mRenderPassAlloc.getAllocation());
        }
    }

    public void initRS(RenderScriptGL rs, Resources res, SceneManager sceneManager) {
        mRS = rs;
        mRes = res;
        long start = System.currentTimeMillis();
        mTransformRSData = mRootTransforms.getRSData(rs);
        long end = System.currentTimeMillis();
        Log.v(TIMER_TAG, "Transform init time: " + (end - start));

        start = System.currentTimeMillis();

        sceneManager.mRenderLoop.bind_gRootNode(mTransformRSData);
        end = System.currentTimeMillis();
        Log.v(TIMER_TAG, "Script init time: " + (end - start));

        start = System.currentTimeMillis();
        Allocation drawableData = Allocation.createSized(rs,
                                                         Element.ALLOCATION(rs),
                                                         mRenderables.size());
        Allocation[] drawableAllocs = new Allocation[mRenderables.size()];
        for (int i = 0; i < mRenderables.size(); i ++) {
            Renderable dI = (Renderable)mRenderables.get(i);
            dI.sceneIndex = i;
            addToMeshMap(dI);
            drawableAllocs[i] = dI.getRsField(rs, res).getAllocation();
        }
        drawableData.copyFrom(drawableAllocs);
        sceneManager.mRenderLoop.set_gRenderableObjects(drawableData);

        initRenderPassRS(rs, sceneManager);

        new ImageLoaderTask().execute();

        end = System.currentTimeMillis();
        Log.v(TIMER_TAG, "Renderable init time: " + (end - start));

        Allocation opaqueBuffer = Allocation.createSized(rs, Element.U32(rs), mRenderables.size());
        Allocation transparentBuffer = Allocation.createSized(rs,
                                                              Element.U32(rs), mRenderables.size());

        sceneManager.mRenderLoop.bind_gFrontToBack(opaqueBuffer);
        sceneManager.mRenderLoop.bind_gBackToFront(transparentBuffer);

        Allocation cameraData = Allocation.createSized(rs, Element.ALLOCATION(rs), mCameras.size());
        Allocation[] cameraAllocs = new Allocation[mCameras.size()];
        for (int i = 0; i < mCameras.size(); i ++) {
            cameraAllocs[i] = mCameras.get(i).getRSData(rs).getAllocation();
        }
        cameraData.copyFrom(cameraAllocs);
        sceneManager.mRenderLoop.set_gCameras(cameraData);

        if (mLights.size() != 0) {
            Allocation lightData = Allocation.createSized(rs,
                                                          Element.ALLOCATION(rs),
                                                          mCameras.size());
            Allocation[] lightAllocs = new Allocation[mLights.size()];
            for (int i = 0; i < mLights.size(); i ++) {
                lightAllocs[i] = mLights.get(i).getRSData(rs).getAllocation();
            }
            lightData.copyFrom(lightAllocs);
            sceneManager.mRenderLoop.set_gLights(lightData);
        }
    }
}




