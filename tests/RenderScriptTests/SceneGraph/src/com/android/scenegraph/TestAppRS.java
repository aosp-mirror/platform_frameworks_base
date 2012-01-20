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
import com.android.scenegraph.VertexShader;
import com.android.scenegraph.VertexShader.Builder;

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

    ScriptField_FShaderParams_s mFsConst;
    ScriptField_FShaderLightParams_s mFsConst2;
    ScriptField_VShaderParams_s mVsConst;

    // Shaders
    private FragmentShader mPaintF;
    private FragmentShader mLightsF;
    private FragmentShader mAluminumF;
    private FragmentShader mPlasticF;
    private FragmentShader mDiffuseF;
    private FragmentShader mTextureF;
    private VertexShader mPaintV;

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

        mTouchHandler = new TouchHandler();

        mSceneManager = SceneManager.getInstance();
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
                mPaintF.mProgram.bindTexture(mEnvCube, 1);
            }

            if (tempDiff != null) {
                mDiffCube = tempDiff;
                mAluminumF.mProgram.bindTexture(mDiffCube, 1);
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

    FragmentShader createFromResource(int id, boolean addCubemap) {
        FragmentShader.Builder fb = new FragmentShader.Builder(mRS);
        fb.setShaderConst(mFsConst.getAllocation().getType());
        fb.setShader(mRes, id);
        fb.addTexture(TextureType.TEXTURE_2D, "diffuse");
        if (addCubemap) {
            fb.addTexture(TextureType.TEXTURE_CUBE, "reflection");
        }
        FragmentShader pf = fb.create();
        pf.mProgram.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(mRS), 0);
        if (addCubemap) {
            pf.mProgram.bindSampler(Sampler.CLAMP_LINEAR_MIP_LINEAR(mRS), 1);
        }
        return pf;
    }

    private void initPaintShaders() {
        ScriptField_VObjectParams_s objConst = new ScriptField_VObjectParams_s(mRS, 1);
        ScriptField_VSParams_s shaderConst = new ScriptField_VSParams_s(mRS, 1);

        VertexShader.Builder vb = new VertexShader.Builder(mRS);
        vb.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        vb.setShader(mRes, R.raw.shader2v);
        vb.setObjectConst(objConst.getAllocation().getType());
        vb.setShaderConst(shaderConst.getAllocation().getType());
        mPaintV = vb.create();

        mFsConst = new ScriptField_FShaderParams_s(mRS, 1);
        mFsConst2 = new ScriptField_FShaderLightParams_s(mRS, 1);

        mPaintF = createFromResource(R.raw.paintf, true);
        mAluminumF = createFromResource(R.raw.metal, true);

        mPlasticF = createFromResource(R.raw.plastic, false);
        mDiffuseF = createFromResource(R.raw.diffuse, false);
        mTextureF = createFromResource(R.raw.texture, false);

        FragmentShader.Builder fb = new FragmentShader.Builder(mRS);
        fb.setObjectConst(mFsConst2.getAllocation().getType());
        fb.setShader(mRes, R.raw.plastic_lights);
        mLightsF = fb.create();
    }

    void initRenderPasses() {
        ArrayList<RenderableBase> allDraw = mActiveScene.getRenderables();
        int numDraw = allDraw.size();

        if (mUseBlur) {
            FullscreenBlur.addBlurPasses(mActiveScene, mRS);
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
            FullscreenBlur.addCompositePass(mActiveScene, mRS);
        }
    }

    private void addShadersToScene() {
        mActiveScene.appendShader(mPaintF);
        mActiveScene.appendShader(mLightsF);
        mActiveScene.appendShader(mAluminumF);
        mActiveScene.appendShader(mPlasticF);
        mActiveScene.appendShader(mDiffuseF);
        mActiveScene.appendShader(mTextureF);
        mActiveScene.appendShader(mPaintV);
    }

    public void prepareToRender(Scene s) {
        mSceneManager.setActiveScene(s);
        mActiveScene = s;
        addShadersToScene();
        RenderState plastic = new RenderState(mPaintV, mPlasticF, null, null);
        RenderState diffuse = new RenderState(mPaintV, mDiffuseF, null, null);
        RenderState paint = new RenderState(mPaintV, mPaintF, null, null);
        RenderState aluminum = new RenderState(mPaintV, mAluminumF, null, null);
        RenderState lights = new RenderState(mPaintV, mLightsF, null, null);
        RenderState glassTransp = new RenderState(mPaintV, mPaintF,
                                                  ProgramStore.BLEND_ALPHA_DEPTH_TEST(mRS), null);

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

        mActiveScene.assignRenderStateToMaterial(lights, "^#LightBlinn");

        Renderable plane = (Renderable)mActiveScene.getRenderableByName("pPlaneShape1");
        if (plane != null) {
            RenderState texState = new RenderState(mPaintV, mTextureF, null, null);
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
        mPaintF.mProgram.bindTexture(mDefaultCube, 1);
        mAluminumF.mProgram.bindTexture(mDefaultCube, 1);

        // Reflection maps from SD card
        new ImageLoaderTask().execute();

        ScriptC_render renderLoop = mSceneManager.getRenderLoop();

        mLoadingScreen.setRenderLoop(renderLoop);
    }
}
