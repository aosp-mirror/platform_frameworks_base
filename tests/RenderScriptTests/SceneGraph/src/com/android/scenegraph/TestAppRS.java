/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import com.android.scenegraph.SceneManager;
import com.android.scenegraph.SceneManager.SceneLoadedCallback;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.renderscript.*;
import android.renderscript.Program.TextureType;
import android.util.Log;

// This is where the scenegraph and the rendered objects are initialized and used
public class TestAppRS {

    private static String modelName = "orientation_test.dae";
    private static String TAG = "TestAppRS";
    private static String mFilePath = "";

    int mWidth;
    int mHeight;
    int mRotation;

    boolean mUseBlur;

    TestAppLoadingScreen mLoadingScreen;

    // Used to asynchronously load scene elements like meshes and transform hierarchies
    SceneLoadedCallback mLoadedCallback = new SceneLoadedCallback() {
        public void run() {
            prepareToRender(mLoadedScene);
        }
    };

    // Top level class that initializes all the elements needed to use the scene graph
    SceneManager mSceneManager;

    // Used to move the camera around in the 3D world
    TouchHandler mTouchHandler;

    private Resources mRes;
    private RenderScriptGL mRS;

    private ProgramFragment mPF_Paint;
    private ProgramFragment mPF_Aluminum;
    private ProgramFragment mPF_Plastic;
    private ProgramFragment mPF_Diffuse;
    private ProgramFragment mPF_Texture;
    ScriptField_FShaderParams_s mFsConst;
    private ProgramVertex mPV_Paint;
    ScriptField_VShaderParams_s mVsConst;

    private Allocation mDefaultCube;
    private Allocation mAllocPV;
    private Allocation mEnvCube;
    private Allocation mDiffCube;

    Scene mActiveScene;

    public TestAppRS() {
        mUseBlur = false;
    }

    // This is a part of the test app, it's used to tests multiple render passes and is toggled
    // on and off in the menu, off by default
    void toggleBlur() {
        mUseBlur = !mUseBlur;

        mActiveScene.clearRenderPasses();
        initRenderPasses();
        mActiveScene.initRenderPassRS(mRS, mSceneManager);

        // This is just a hardcoded object in the scene that gets turned on and off for the demo
        // to make things look a bit better. This could be deleted in the cleanup
        Renderable plane = (Renderable)mActiveScene.getRenderableByName("pPlaneShape1");
        if (plane != null) {
            plane.setVisible(mRS, !mUseBlur);
        }
    }

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        mWidth = width;
        mHeight = height;
        mRotation = 0;

        mTouchHandler = new TouchHandler();

        mSceneManager = new SceneManager();
        // Initializes all the RS specific scenegraph elements
        mSceneManager.initRS(mRS, mRes, mWidth, mHeight);

        mLoadingScreen = new TestAppLoadingScreen(mRS, mRes);

        // Initi renderscript stuff specific to the app. This will need to be abstracted out later.
        initRS();

        // Load a scene to render
        mSceneManager.loadModel(mFilePath + modelName, mLoadedCallback);
    }

    // When a new model file is selected from the UI, this function gets called to init everything
    void loadModel(String path) {
        //String shortName = path.substring(path.lastIndexOf('/') + 1);
        //shortName = shortName.substring(0, shortName.lastIndexOf('.'));
        mLoadingScreen.showLoadingScreen(true);
        mActiveScene.destroyRS(mSceneManager);
        mSceneManager.loadModel(path, mLoadedCallback);
    }

    // We use this to laod environment maps off the UI thread
    private class ImageLoaderTask extends AsyncTask<String, Void, Boolean> {
        Allocation tempEnv;
        Allocation tempDiff;

        protected Boolean doInBackground(String... names) {
            long start = System.currentTimeMillis();

            tempEnv = SceneManager.loadCubemap("sdcard/scenegraph/cube_env.png", mRS, mRes);
            tempDiff = SceneManager.loadCubemap("sdcard/scenegraph/cube_spec.png", mRS, mRes);

            long end = System.currentTimeMillis();
            Log.v("TIMER", "Image load time: " + (end - start));
            return new Boolean(true);
        }

        protected void onPostExecute(Boolean result) {
            if (tempEnv != null) {
                mEnvCube = tempEnv;
                mPF_Paint.bindTexture(mEnvCube, 1);
            }

            if (tempDiff != null) {
                mDiffCube = tempDiff;
                mPF_Aluminum.bindTexture(mDiffCube, 1);
            }
        }
    }

    public void onActionDown(float x, float y) {
        mTouchHandler.onActionDown(x, y);

        //mSceneManager.getRenderLoop().invoke_pick((int)x, (int)y);
    }

    public void onActionScale(float scale) {
        mTouchHandler.onActionScale(scale);
    }

    public void onActionMove(float x, float y) {
        mTouchHandler.onActionMove(x, y);
    }

    ProgramFragment createFromResource(int id, boolean addCubemap) {
        ProgramFragment.Builder fb = new ProgramFragment.Builder(mRS);
        fb.addConstant(mFsConst.getAllocation().getType());
        fb.setShader(mRes, id);
        fb.addTexture(TextureType.TEXTURE_2D);
        if (addCubemap) {
            fb.addTexture(TextureType.TEXTURE_CUBE);
        }
        ProgramFragment pf = fb.create();
        pf.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);
        if (addCubemap) {
            pf.bindSampler(Sampler.CLAMP_LINEAR_MIP_LINEAR(mRS), 1);
        }
        return pf;
    }

    // All the custom shaders used to render the scene are initialized here
    // This includes stuff like plastic, car paint, etc.
    private void initPaintShaders() {
        ProgramVertex.Builder vb = new ProgramVertex.Builder(mRS);
        mVsConst = new ScriptField_VShaderParams_s(mRS, 1);
        vb.addConstant(mVsConst.getAllocation().getType());
        vb.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        vb.setShader(mRes, R.raw.shader2v);
        mPV_Paint = vb.create();

        mFsConst = new ScriptField_FShaderParams_s(mRS, 1);

        mPF_Paint = createFromResource(R.raw.paintf, true);
        mPF_Aluminum = createFromResource(R.raw.metal, true);

        mPF_Plastic = createFromResource(R.raw.plastic, false);
        mPF_Diffuse = createFromResource(R.raw.diffuse, false);
        mPF_Texture = createFromResource(R.raw.texture, false);

        FullscreenBlur.initShaders(mRes, mRS, mVsConst, mFsConst);
    }

    void initRenderPasses() {
        ArrayList<RenderableBase> allDraw = mActiveScene.getRenderables();
        int numDraw = allDraw.size();

        if (mUseBlur) {
            FullscreenBlur.addBlurPasses(mActiveScene, mRS, mSceneManager);
        }

        RenderPass mainPass = new RenderPass();
        mainPass.setClearColor(new Float4(1.0f, 1.0f, 1.0f, 1.0f));
        mainPass.setShouldClearColor(true);
        mainPass.setClearDepth(1.0f);
        mainPass.setShouldClearDepth(true);
        mainPass.setCamera(mActiveScene.getCameras().get(1));
        for (int i = 0; i < numDraw; i ++) {
            mainPass.appendRenderable((Renderable)allDraw.get(i));
        }
        mActiveScene.appendRenderPass(mainPass);

        if (mUseBlur) {
            FullscreenBlur.addCompositePass(mActiveScene, mRS, mSceneManager);
        }
    }

    public void prepareToRender(Scene s) {
        mActiveScene = s;
        RenderState plastic = new RenderState(mPV_Paint, mPF_Plastic, null, null);
        RenderState diffuse = new RenderState(mPV_Paint, mPF_Diffuse, null, null);
        RenderState paint = new RenderState(mPV_Paint, mPF_Paint, null, null);
        RenderState aluminum = new RenderState(mPV_Paint, mPF_Aluminum, null, null);
        RenderState glassTransp = new RenderState(mPV_Paint,
                                                  mPF_Paint,
                                                  ProgramStore.BLEND_ALPHA_DEPTH_TEST(mRS),
                                                  null);

        initRenderPasses();

        mActiveScene.assignRenderState(plastic);

        mActiveScene.assignRenderStateToMaterial(diffuse, "lambert2$");

        mActiveScene.assignRenderStateToMaterial(paint, "^#Paint");
        mActiveScene.assignRenderStateToMaterial(paint, "^#Carbon");
        mActiveScene.assignRenderStateToMaterial(paint, "^#Glass");
        mActiveScene.assignRenderStateToMaterial(paint, "^#MainGlass");

        mActiveScene.assignRenderStateToMaterial(aluminum, "^#Metal");
        mActiveScene.assignRenderStateToMaterial(aluminum, "^#Brake");

        mActiveScene.assignRenderStateToMaterial(glassTransp, "^#GlassLight");

        Renderable plane = (Renderable)mActiveScene.getRenderableByName("pPlaneShape1");
        if (plane != null) {
            RenderState texState = new RenderState(mPV_Paint, mPF_Texture, null, null);
            plane.setRenderState(texState);
            plane.setVisible(mRS, !mUseBlur);
        }

        mTouchHandler.init(mActiveScene);

        long start = System.currentTimeMillis();
        mActiveScene.initRS(mRS, mRes, mSceneManager);
        long end = System.currentTimeMillis();
        Log.v("TIMER", "Scene init time: " + (end - start));

        mLoadingScreen.showLoadingScreen(false);
    }

    private void initRS() {

        FullscreenBlur.createRenderTargets(mRS, mWidth, mHeight);
        initPaintShaders();

        Bitmap b = BitmapFactory.decodeResource(mRes, R.drawable.defaultcube);
        mDefaultCube = Allocation.createCubemapFromBitmap(mRS, b);
        mPF_Paint.bindTexture(mDefaultCube, 1);
        mPF_Aluminum.bindTexture(mDefaultCube, 1);

        // Reflection maps from SD card
        new ImageLoaderTask().execute();

        ScriptC_render renderLoop = mSceneManager.getRenderLoop();

        mLoadingScreen.setRenderLoop(renderLoop);
    }
}
