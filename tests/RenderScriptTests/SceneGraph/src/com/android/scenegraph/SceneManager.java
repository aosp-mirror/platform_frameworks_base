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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.lang.Math;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.android.scenegraph.Scene;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.renderscript.*;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Mesh;
import android.renderscript.RenderScriptGL;
import android.util.Log;
import android.view.SurfaceHolder;

import com.android.testapp.R;

/**
 * @hide
 */
public class SceneManager extends SceneGraphBase {

    ScriptC_render mRenderLoop;
    ScriptC mCameraScript;
    ScriptC mLightScript;
    ScriptC mObjectParamsScript;
    ScriptC mFragmentParamsScript;
    ScriptC mVertexParamsScript;
    ScriptC mCullScript;
    ScriptC_transform mTransformScript;

    RenderScriptGL mRS;
    Resources mRes;
    Mesh mQuad;
    int mWidth;
    int mHeight;

    Scene mActiveScene;
    private static SceneManager sSceneManager;

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

    static Bitmap loadBitmap(String name, Resources res) {
        InputStream is = null;
        boolean loadFromSD = isSDCardPath(name);
        try {
            if (!loadFromSD) {
                is = res.getAssets().open(name);
            } else {
                File f = new File(name);
                is = new BufferedInputStream(new FileInputStream(f));
            }
        } catch (IOException e) {
            Log.e("ImageLoaderTask", " Message: " + e.getMessage());
            return null;
        }

        Bitmap b = BitmapFactory.decodeStream(is);
        try {
            is.close();
        } catch (IOException e) {
            Log.e("ImageLoaderTask", " Message: " + e.getMessage());
        }
        return b;
    }

    public static Allocation loadCubemap(String name, RenderScriptGL rs, Resources res) {
        Bitmap b = loadBitmap(name, res);
        return Allocation.createCubemapFromBitmap(rs, b,
                                                  MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                  Allocation.USAGE_GRAPHICS_TEXTURE);
    }

    public static Allocation loadTexture2D(String name, RenderScriptGL rs, Resources res) {
        Bitmap b = loadBitmap(name, res);
        return Allocation.createFromBitmap(rs, b,
                                           Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                           Allocation.USAGE_GRAPHICS_TEXTURE);
    }

    public static ProgramStore BLEND_ADD_DEPTH_NONE(RenderScript rs) {
        ProgramStore.Builder builder = new ProgramStore.Builder(rs);
        builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        builder.setBlendFunc(ProgramStore.BlendSrcFunc.ONE, ProgramStore.BlendDstFunc.ONE);
        builder.setDitherEnabled(false);
        builder.setDepthMaskEnabled(false);
        return builder.create();
    }

    static Allocation getStringAsAllocation(RenderScript rs, String str) {
        if (str == null) {
            return null;
        }
        if (str.length() == 0) {
            return null;
        }
        byte[] allocArray = null;
        byte[] nullChar = new byte[1];
        nullChar[0] = 0;
        try {
            allocArray = str.getBytes("UTF-8");
            Allocation alloc = Allocation.createSized(rs, Element.U8(rs),
                                                      allocArray.length + 1,
                                                      Allocation.USAGE_SCRIPT);
            alloc.copy1DRangeFrom(0, allocArray.length, allocArray);
            alloc.copy1DRangeFrom(allocArray.length, 1, nullChar);
            return alloc;
        }
        catch (Exception e) {
            throw new RSRuntimeException("Could not convert string to utf-8.");
        }
    }

    public static class SceneLoadedCallback implements Runnable {
        public Scene mLoadedScene;
        public String mName;
        public void run() {
        }
    }

    public Scene getActiveScene() {
        return mActiveScene;
    }

    public void setActiveScene(Scene s) {
        mActiveScene = s;
    }

    static RenderScriptGL getRS() {
        if (sSceneManager == null) {
            return null;
        }
        return sSceneManager.mRS;
    }

    static Resources getRes() {
        if (sSceneManager == null) {
            return null;
        }
        return sSceneManager.mRes;
    }

    public static SceneManager getInstance() {
        if (sSceneManager == null) {
            sSceneManager = new SceneManager();
        }
        return sSceneManager;
    }

    protected SceneManager() {
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
        mObjectParamsScript = new ScriptC_object_params(rs, res, R.raw.object_params);
        mFragmentParamsScript = new ScriptC_object_params(rs, res, R.raw.fragment_params);
        mVertexParamsScript = new ScriptC_object_params(rs, res, R.raw.vertex_params);
        mCullScript = new ScriptC_cull(rs, res, R.raw.cull);

        mRenderLoop = new ScriptC_render(rs, res, R.raw.render);
        mRenderLoop.set_gTransformScript(mTransformScript);
        mRenderLoop.set_gCameraScript(mCameraScript);
        mRenderLoop.set_gLightScript(mLightScript);
        mRenderLoop.set_gObjectParamsScript(mObjectParamsScript);
        mRenderLoop.set_gFragmentParamsScript(mFragmentParamsScript);
        mRenderLoop.set_gVertexParamsScript(mVertexParamsScript);
        mRenderLoop.set_gCullScript(mCullScript);

        Allocation checker = Allocation.createFromBitmapResource(mRS, mRes, R.drawable.checker,
                                                         MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE);
        mRenderLoop.set_gTGrid(checker);
        mRenderLoop.set_gPFSBackground(ProgramStore.BLEND_NONE_DEPTH_TEST(mRS));
    }

    public ScriptC getRenderLoop() {
        return mRenderLoop;
    }
}




