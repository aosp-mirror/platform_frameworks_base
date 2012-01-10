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
import android.renderscript.Allocation.MipmapControl;
import android.content.res.Resources;
import android.view.SurfaceHolder;
import android.util.Log;
import android.os.AsyncTask;

/**
 * @hide
 */
public class SceneManager extends SceneGraphBase {

    ScriptC_render mRenderLoop;
    ScriptC_camera mCameraScript;
    ScriptC_light mLightScript;
    ScriptC_params mParamsScript;
    ScriptC_transform mTransformScript;

    RenderScriptGL mRS;
    Resources mRes;
    Mesh mQuad;
    int mWidth;
    int mHeight;

    public static boolean isSDCardPath(String path) {
        int sdCardIndex = path.indexOf("sdcard/");
        // We are looking for /sdcard/ or sdcard/
        if (sdCardIndex == 0 || sdCardIndex == 1) {
            return true;
        }
        sdCardIndex = path.indexOf("mnt/sdcard/");
        if (sdCardIndex == 0 || sdCardIndex == 1) {
            return true;
        }
        return false;
    }

    public static class SceneLoadedCallback implements Runnable {
        Scene mLoadedScene;
        String mName;
        public void run() {
        }
    }

    private void initPFS() {
        ProgramStore.Builder b = new ProgramStore.Builder(mRS);

        b.setDepthFunc(ProgramStore.DepthFunc.LESS);
        b.setDitherEnabled(false);
        b.setDepthMaskEnabled(true);

        mRenderLoop.set_gPFSBackground(b.create());
    }

    public SceneManager() {
    }

    public void loadModel(String name, SceneLoadedCallback cb) {
        ColladaScene scene = new ColladaScene(name, cb);
        scene.init(mRS, mRes);
    }

    public Mesh getScreenAlignedQuad() {
        if (mQuad != null) {
            return mQuad;
        }

        Mesh.TriangleMeshBuilder tmb = new Mesh.TriangleMeshBuilder(mRS,
                                           3, Mesh.TriangleMeshBuilder.TEXTURE_0);

        tmb.setTexture(0.0f, 1.0f);
        tmb.addVertex(-1.0f, 1.0f, 1.0f);

        tmb.setTexture(0.0f, 0.0f);
        tmb.addVertex(-1.0f, -1.0f, 1.0f);

        tmb.setTexture(1.0f, 0.0f);
        tmb.addVertex(1.0f, -1.0f, 1.0f);

        tmb.setTexture(1.0f, 1.0f);
        tmb.addVertex(1.0f, 1.0f, 1.0f);

        tmb.addTriangle(0, 1, 2);
        tmb.addTriangle(2, 3, 0);

        mQuad = tmb.create(true);
        return mQuad;
    }

    public Renderable getRenderableQuad(String name, RenderState state) {
        Renderable quad = new Renderable();
        quad.setTransform(new MatrixTransform());
        quad.setMesh(getScreenAlignedQuad());
        quad.setName(name);
        quad.setRenderState(state);
        quad.setCullType(1);
        return quad;
    }

    public void initRS(RenderScriptGL rs, Resources res, int w, int h) {
        mRS = rs;
        mRes = res;
        mTransformScript = new ScriptC_transform(rs, res, R.raw.transform);
        mTransformScript.set_gTransformScript(mTransformScript);

        mCameraScript = new ScriptC_camera(rs, res, R.raw.camera);
        mLightScript = new ScriptC_light(rs, res, R.raw.light);
        mParamsScript = new ScriptC_params(rs, res, R.raw.params);

        mRenderLoop = new ScriptC_render(rs, res, R.raw.render);
        mRenderLoop.set_gTransformScript(mTransformScript);
        mRenderLoop.set_gCameraScript(mCameraScript);
        mRenderLoop.set_gLightScript(mLightScript);
        mRenderLoop.set_gParamsScript(mParamsScript);

        Allocation checker = Allocation.createFromBitmapResource(mRS, mRes, R.drawable.checker,
                                                         MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE);
        mRenderLoop.set_gTGrid(checker);
        initPFS();
    }

    public ScriptC_render getRenderLoop() {
        return mRenderLoop;
    }
}




