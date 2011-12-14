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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.renderscript.*;
import android.renderscript.Allocation.MipmapControl;
import android.renderscript.Element.Builder;
import android.renderscript.Font.Style;
import android.renderscript.Program.TextureType;
import android.renderscript.ProgramStore.DepthFunc;
import android.util.Log;

import com.android.scenegraph.SceneManager.SceneLoadedCallback;

// This is where the scenegraph and the rendered objects are initialized and used
public class TestAppRS {

    private static String modelName = "orientation_test";
    private static String TAG = "TestAppRS";
    private final int STATE_LAST_FOCUS = 1;
    private final boolean mLoadFromSD = true;
    private static String mSDCardPath = "sdcard/scenegraph/";

    int mWidth;
    int mHeight;
    int mRotation;

    boolean mUseBlur;

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

        // Shows the loading screen with some text
        renderLoading();
        // Adds a little 3D bugdroid model to the laoding screen asynchronously.
        new LoadingScreenLoaderTask().execute();

        // Initi renderscript stuff specific to the app. This will need to be abstracted out later.
        initRS();

        // Load a scene to render
        mSceneManager.loadModel(modelName, mLoadedCallback);
    }

    // When a new model file is selected from the UI, this function gets called to init everything
    void loadModel(String path) {
        String shortName = path.substring(path.lastIndexOf('/') + 1);
        shortName = shortName.substring(0, shortName.lastIndexOf('.'));
        mScript.set_gInitialized(false);
        mActiveScene.destroyRS(mSceneManager);
        mSceneManager.loadModel(shortName, mLoadedCallback);
    }

    private Resources mRes;
    private RenderScriptGL mRS;
    private Sampler mSampler;
    private ProgramStore mPSBackground;
    private ProgramFragment mPFBackground;
    private ProgramVertex mPVBackground;
    private ProgramVertexFixedFunction.Constants mPVA;

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

    private ScriptC_scenegraph mScript;

    // The loading screen has some elements that shouldn't be loaded on the UI thread
    private class LoadingScreenLoaderTask extends AsyncTask<String, Void, Boolean> {
        Allocation robotTex;
        Mesh robotMesh;
        protected Boolean doInBackground(String... names) {
            long start = System.currentTimeMillis();
            robotTex = Allocation.createFromBitmapResource(mRS, mRes, R.drawable.robot,
                                                           MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                           Allocation.USAGE_GRAPHICS_TEXTURE);

            FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.robot);
            FileA3D.IndexEntry entry = model.getIndexEntry(0);
            if (entry != null && entry.getEntryType() == FileA3D.EntryType.MESH) {
                robotMesh = entry.getMesh();
            }

            initPFS();
            initPF();
            initPV();

            long end = System.currentTimeMillis();
            Log.v("TIMER", "Loading load time: " + (end - start));
            return new Boolean(true);
        }

        protected void onPostExecute(Boolean result) {
            mScript.set_gRobotTex(robotTex);
            mScript.set_gRobotMesh(robotMesh);
        }
    }

    // We use this to laod environment maps off the UI thread
    private class ImageLoaderTask extends AsyncTask<String, Void, Boolean> {
        Allocation tempEnv;
        Allocation tempDiff;

        InputStream openStream(String name) {
            InputStream is = null;
            try {
                if (!mLoadFromSD) {
                    is = mRes.getAssets().open(name);
                } else {
                    File f = new File(mSDCardPath + name);
                    is = new BufferedInputStream(new FileInputStream(f));
                }
            } catch (IOException e) {
                Log.e("PAINTSHADERS", " Message: " + e.getMessage());
            }
            return is;
        }

        protected Boolean doInBackground(String... names) {
            long start = System.currentTimeMillis();
            InputStream is = openStream("cube_env.png");
            if (is == null) {
                return new Boolean(false);
            }

            Bitmap b = BitmapFactory.decodeStream(is);
            tempEnv = Allocation.createCubemapFromBitmap(mRS,
                                                         b,
                                                         MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE);

            is = openStream("cube_spec.png");
            if (is == null) {
                return new Boolean(false);
            }

            b = BitmapFactory.decodeStream(is);
            tempDiff = Allocation.createCubemapFromBitmap(mRS,
                                                          b,
                                                          MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE,
                                                          Allocation.USAGE_GRAPHICS_TEXTURE);
            long end = System.currentTimeMillis();
            Log.v("TIMER", "Image load time: " + (end - start));
            return new Boolean(true);
        }

        protected void onPostExecute(Boolean result) {
            mEnvCube = tempEnv;
            mDiffCube = tempDiff;

            mPF_Paint.bindTexture(mEnvCube, 1);
            mPF_Aluminum.bindTexture(mDiffCube, 1);
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

    // All the custom shaders used to render the scene are initialized here
    // This includes stuff like plastic, car paint, etc.
    private void initPaintShaders() {
        ProgramVertex.Builder vb = new ProgramVertex.Builder(mRS);
        mVsConst = new ScriptField_VShaderParams_s(mRS, 1);
        vb.addConstant(mVsConst.getAllocation().getType());
        vb.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        vb.setShader(mRes, R.raw.shader2v);
        mPV_Paint = vb.create();
        mPV_Paint.bindConstants(mVsConst.getAllocation(), 0);

        ProgramFragment.Builder fb = new ProgramFragment.Builder(mRS);
        mFsConst = new ScriptField_FShaderParams_s(mRS, 1);
        fb.addConstant(mFsConst.getAllocation().getType());
        fb.setShader(mRes, R.raw.paintf);
        fb.addTexture(TextureType.TEXTURE_2D);
        fb.addTexture(TextureType.TEXTURE_CUBE);
        mPF_Paint = fb.create();

        mPF_Paint.bindConstants(mFsConst.getAllocation(), 0);
        mPF_Paint.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);
        mPF_Paint.bindSampler(Sampler.CLAMP_LINEAR_MIP_LINEAR(mRS), 1);

        fb = new ProgramFragment.Builder(mRS);
        fb.addConstant(mFsConst.getAllocation().getType());
        fb.setShader(mRes, R.raw.metal);
        fb.addTexture(TextureType.TEXTURE_2D);
        fb.addTexture(TextureType.TEXTURE_CUBE);
        mPF_Aluminum = fb.create();

        mPF_Aluminum.bindConstants(mFsConst.getAllocation(), 0);
        mPF_Aluminum.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);
        mPF_Aluminum.bindSampler(Sampler.CLAMP_LINEAR_MIP_LINEAR(mRS), 1);

        fb = new ProgramFragment.Builder(mRS);
        fb.addConstant(mFsConst.getAllocation().getType());
        fb.setShader(mRes, R.raw.plastic);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_Plastic = fb.create();
        mPF_Plastic.bindConstants(mFsConst.getAllocation(), 0);
        mPF_Plastic.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);

        fb = new ProgramFragment.Builder(mRS);
        fb.addConstant(mFsConst.getAllocation().getType());
        fb.setShader(mRes, R.raw.diffuse);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_Diffuse = fb.create();
        mPF_Diffuse.bindConstants(mFsConst.getAllocation(), 0);
        mPF_Diffuse.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);

        fb = new ProgramFragment.Builder(mRS);
        fb.addConstant(mFsConst.getAllocation().getType());
        fb.setShader(mRes, R.raw.texture);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_Texture = fb.create();
        mPF_Texture.bindConstants(mFsConst.getAllocation(), 0);
        mPF_Texture.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);

        FullscreenBlur.initShaders(mRes, mRS, mVsConst, mFsConst);
    }

    // This needs to be cleaned up a bit, it's one of the default render state objects
    private void initPFS() {
        ProgramStore.Builder b = new ProgramStore.Builder(mRS);

        b.setDepthFunc(ProgramStore.DepthFunc.LESS);
        b.setDitherEnabled(false);
        b.setDepthMaskEnabled(true);
        mPSBackground = b.create();

        mScript.set_gPFSBackground(mPSBackground);
    }

    // This needs to be cleaned up a bit, it's one of the default render state objects
    private void initPF() {
        Sampler.Builder bs = new Sampler.Builder(mRS);
        bs.setMinification(Sampler.Value.LINEAR);
        bs.setMagnification(Sampler.Value.LINEAR);
        bs.setWrapS(Sampler.Value.CLAMP);
        bs.setWrapT(Sampler.Value.CLAMP);
        mSampler = bs.create();

        ProgramFragmentFixedFunction.Builder b = new ProgramFragmentFixedFunction.Builder(mRS);
        b.setTexture(ProgramFragmentFixedFunction.Builder.EnvMode.REPLACE,
                     ProgramFragmentFixedFunction.Builder.Format.RGBA, 0);
        mPFBackground = b.create();
        mPFBackground.bindSampler(Sampler.CLAMP_LINEAR(mRS), 0);

        mScript.set_gPFBackground(mPFBackground);
    }

    private void initPV() {
        ProgramVertexFixedFunction.Builder pvb = new ProgramVertexFixedFunction.Builder(mRS);
        mPVBackground = pvb.create();

        mPVA = new ProgramVertexFixedFunction.Constants(mRS);
        ((ProgramVertexFixedFunction)mPVBackground).bindConstants(mPVA);

        mScript.set_gPVBackground(mPVBackground);
    }

    // Creates a simple script to show a loding screen until everything is initialized
    // Could also be used to do some custom renderscript work before handing things over
    // to the scenegraph
    void renderLoading() {
        mScript = new ScriptC_scenegraph(mRS, mRes, R.raw.scenegraph);
        mRS.bindRootScript(mScript);
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

        mScript.set_gInitialized(true);
    }

    private void initRS() {

        FullscreenBlur.createRenderTargets(mRS, mWidth, mHeight);
        initPaintShaders();
        new ImageLoaderTask().execute();

        Bitmap b = BitmapFactory.decodeResource(mRes, R.drawable.defaultcube);
        mDefaultCube = Allocation.createCubemapFromBitmap(mRS, b);
        mPF_Paint.bindTexture(mDefaultCube, 1);
        mPF_Aluminum.bindTexture(mDefaultCube, 1);

        ScriptC_render renderLoop = mSceneManager.getRenderLoop();
        renderLoop.bind_vConst(mVsConst);
        renderLoop.bind_fConst(mFsConst);

        mScript.set_gRenderLoop(renderLoop);
        Allocation dummyAlloc = Allocation.createSized(mRS, Element.I32(mRS), 1);
        mScript.set_gDummyAlloc(dummyAlloc);
    }
}
