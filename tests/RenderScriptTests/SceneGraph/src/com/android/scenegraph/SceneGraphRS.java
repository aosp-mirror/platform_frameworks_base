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

public class SceneGraphRS {

    private static String modelName = "orientation_test";
    private static String TAG = "SceneGraphRS";
    private final int STATE_LAST_FOCUS = 1;
    private final boolean mLoadFromSD = true;
    private static String mSDCardPath = "sdcard/scenegraph/";

    int mWidth;
    int mHeight;
    int mRotation;

    boolean mUseBlur;

    SceneLoadedCallback mLoadedCallback = new SceneLoadedCallback() {
        public void run() {
            prepareToRender(mLoadedScene);
        }
    };

    SceneManager mSceneManager;

    TouchHandler mTouchHandler;

    public SceneGraphRS() {
        mUseBlur = false;
    }

    void toggleBlur() {
        mUseBlur = !mUseBlur;

        mActiveScene.clearRenderPasses();
        initRenderPasses();
        mActiveScene.initRenderPassRS(mRS, mSceneManager);
        Drawable plane = (Drawable)mActiveScene.getDrawableByName("pPlaneShape1");
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
        mSceneManager.initRS(mRS, mRes, mWidth, mHeight);

        renderLoading();

        new LoadingScreenLoaderTask().execute();

        initRS();

        mSceneManager.loadModel(modelName, mLoadedCallback);
    }

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
    private ProgramFragment mPF_BlurH;
    private ProgramFragment mPF_BlurV;
    private ProgramFragment mPF_SelectColor;
    private ProgramFragment mPF_Texture;
    ScriptField_FShaderParams_s mFsConst;
    ScriptField_FBlurOffsets_s mFsBlurHConst;
    ScriptField_FBlurOffsets_s mFsBlurVConst;
    private ProgramVertex mPV_Paint;
    ScriptField_VShaderParams_s mVsConst;
    private ProgramVertex mPV_Blur;

    private Allocation mDefaultCube;
    private Allocation mAllocPV;
    private Allocation mEnvCube;
    private Allocation mDiffCube;

    private Allocation mRenderTargetBlur0Color;
    private Allocation mRenderTargetBlur0Depth;
    private Allocation mRenderTargetBlur1Color;
    private Allocation mRenderTargetBlur1Depth;
    private Allocation mRenderTargetBlur2Color;
    private Allocation mRenderTargetBlur2Depth;

    Scene mActiveScene;

    private ScriptC_scenegraph mScript;

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

    private void initPaintShaders() {
        ProgramVertex.Builder vb = new ProgramVertex.Builder(mRS);
        mVsConst = new ScriptField_VShaderParams_s(mRS, 1);
        vb.addConstant(mVsConst.getAllocation().getType());
        vb.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        vb.setShader(mRes, R.raw.shader2v);
        mPV_Paint = vb.create();
        mPV_Paint.bindConstants(mVsConst.getAllocation(), 0);

        vb = new ProgramVertex.Builder(mRS);
        vb.addConstant(mVsConst.getAllocation().getType());
        vb.addInput(ScriptField_VertexShaderInputs_s.createElement(mRS));
        vb.setShader(mRes, R.raw.blur_vertex);
        mPV_Blur = vb.create();
        mPV_Blur.bindConstants(mVsConst.getAllocation(), 0);

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

        mFsBlurHConst = new ScriptField_FBlurOffsets_s(mRS, 1);
        float xAdvance = 1.0f / (float)mRenderTargetBlur0Color.getType().getX();
        ScriptField_FBlurOffsets_s.Item item = new ScriptField_FBlurOffsets_s.Item();
        item.blurOffset0 = - xAdvance * 2.5f;
        item.blurOffset1 = - xAdvance * 0.5f;
        item.blurOffset2 =   xAdvance * 1.5f;
        item.blurOffset3 =   xAdvance * 3.5f;
        mFsBlurHConst.set(item, 0, true);

        fb = new ProgramFragment.Builder(mRS);
        fb.addConstant(mFsBlurHConst.getAllocation().getType());
        fb.setShader(mRes, R.raw.blur_h);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_BlurH = fb.create();
        mPF_BlurH.bindConstants(mFsBlurHConst.getAllocation(), 0);
        mPF_BlurH.bindTexture(mRenderTargetBlur0Color, 0);
        mPF_BlurH.bindSampler(Sampler.CLAMP_LINEAR(mRS), 0);

        mFsBlurVConst = new ScriptField_FBlurOffsets_s(mRS, 1);
        float yAdvance = 1.0f / (float)mRenderTargetBlur0Color.getType().getY();
        item.blurOffset0 = - yAdvance * 2.5f;
        item.blurOffset1 = - yAdvance * 0.5f;
        item.blurOffset2 =   yAdvance * 1.5f;
        item.blurOffset3 =   yAdvance * 3.5f;
        mFsBlurVConst.set(item, 0, true);

        fb = new ProgramFragment.Builder(mRS);
        fb.addConstant(mFsBlurVConst.getAllocation().getType());
        fb.setShader(mRes, R.raw.blur_v);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_BlurV = fb.create();
        mPF_BlurV.bindConstants(mFsBlurVConst.getAllocation(), 0);
        mPF_BlurV.bindTexture(mRenderTargetBlur1Color, 0);
        mPF_BlurV.bindSampler(Sampler.CLAMP_LINEAR(mRS), 0);

        fb = new ProgramFragment.Builder(mRS);
        //fb.addConstant(mFsBlurVConst.getAllocation().getType());
        fb.setShader(mRes, R.raw.select_color);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_SelectColor = fb.create();
        //mPF_SelectColor.bindConstants(mFsBlurVConst.getAllocation(), 0);
        //mPF_SelectColor.bindTexture(mRenderTargetBlur1Color, 0);
        mPF_SelectColor.bindSampler(Sampler.CLAMP_LINEAR(mRS), 0);
    }

    private void initPFS() {
        ProgramStore.Builder b = new ProgramStore.Builder(mRS);

        b.setDepthFunc(ProgramStore.DepthFunc.LESS);
        b.setDitherEnabled(false);
        b.setDepthMaskEnabled(true);
        mPSBackground = b.create();

        mScript.set_gPFSBackground(mPSBackground);
    }

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

    void renderLoading() {
        mScript = new ScriptC_scenegraph(mRS, mRes, R.raw.scenegraph);
        mRS.bindRootScript(mScript);
    }

    void initSceneRS() {

    }

    void createRenderTargets() {
        Type.Builder b = new Type.Builder(mRS, Element.RGBA_8888(mRS));
        b.setX(mWidth/8).setY(mHeight/8);
        Type renderType = b.create();
        mRenderTargetBlur0Color = Allocation.createTyped(mRS, renderType,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE |
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        mRenderTargetBlur1Color = Allocation.createTyped(mRS, renderType,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE |
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        mRenderTargetBlur2Color = Allocation.createTyped(mRS, renderType,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE |
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);

        b = new Type.Builder(mRS,
                             Element.createPixel(mRS, Element.DataType.UNSIGNED_16,
                                                 Element.DataKind.PIXEL_DEPTH));
        b.setX(mWidth/8).setY(mHeight/8);
        mRenderTargetBlur0Depth = Allocation.createTyped(mRS,
                                                         b.create(),
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);

        mRenderTargetBlur1Depth = Allocation.createTyped(mRS,
                                                         b.create(),
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        mRenderTargetBlur2Depth = Allocation.createTyped(mRS,
                                                         b.create(),
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);
    }

    ProgramStore BLEND_ADD_DEPTH_NONE(RenderScript rs) {
        ProgramStore.Builder builder = new ProgramStore.Builder(rs);
        builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        builder.setBlendFunc(ProgramStore.BlendSrcFunc.ONE, ProgramStore.BlendDstFunc.ONE);
        builder.setDitherEnabled(false);
        builder.setDepthMaskEnabled(false);
        return builder.create();
    }

    Drawable getDrawableQuad(String name, RenderState state) {
        Drawable quad = new Drawable();
        quad.setTransform(new MatrixTransform());
        quad.setMesh(mSceneManager.getScreenAlignedQuad());
        quad.setName(name);
        quad.setRenderState(state);
        quad.setCullType(1);
        return quad;
    }

    void addBlurPasses() {
        ArrayList<DrawableBase> allDraw = mActiveScene.getDrawables();
        int numDraw = allDraw.size();

        RenderState drawTex = new RenderState(mPV_Blur, mPF_Texture,
                                            BLEND_ADD_DEPTH_NONE(mRS),
                                            ProgramRaster.CULL_NONE(mRS));

        RenderState selectCol = new RenderState(mPV_Blur, mPF_SelectColor,
                                            ProgramStore.BLEND_NONE_DEPTH_NONE(mRS),
                                            ProgramRaster.CULL_NONE(mRS));

        RenderState hBlur = new RenderState(mPV_Blur, mPF_BlurH,
                                            ProgramStore.BLEND_NONE_DEPTH_NONE(mRS),
                                            ProgramRaster.CULL_NONE(mRS));

        RenderState vBlur = new RenderState(mPV_Blur, mPF_BlurV,
                                            ProgramStore.BLEND_NONE_DEPTH_NONE(mRS),
                                            ProgramRaster.CULL_NONE(mRS));

        RenderPass blurSourcePass = new RenderPass();
        blurSourcePass.setColorTarget(mRenderTargetBlur0Color);
        blurSourcePass.setDepthTarget(mRenderTargetBlur0Depth);
        blurSourcePass.setClearColor(new Float4(1.0f, 1.0f, 1.0f, 1.0f));
        blurSourcePass.setShouldClearColor(true);
        blurSourcePass.setClearDepth(1.0f);
        blurSourcePass.setShouldClearDepth(true);
        blurSourcePass.setCamera(mActiveScene.getCameras().get(1));
        for (int i = 0; i < numDraw; i ++) {
            blurSourcePass.appendDrawable((Drawable)allDraw.get(i));
        }
        mActiveScene.appendRenderPass(blurSourcePass);

        RenderPass selectColorPass = new RenderPass();
        selectColorPass.setColorTarget(mRenderTargetBlur2Color);
        selectColorPass.setDepthTarget(mRenderTargetBlur2Depth);
        selectColorPass.setShouldClearColor(false);
        selectColorPass.setShouldClearDepth(false);
        selectColorPass.setCamera(mActiveScene.getCameras().get(1));
        // Make blur shape
        Drawable quad = getDrawableQuad("ScreenAlignedQuadS", selectCol);
        quad.updateTextures(mRS, mRenderTargetBlur0Color, 0);
        selectColorPass.appendDrawable(quad);
        mActiveScene.appendRenderPass(selectColorPass);

        RenderPass horizontalBlurPass = new RenderPass();
        horizontalBlurPass.setColorTarget(mRenderTargetBlur1Color);
        horizontalBlurPass.setDepthTarget(mRenderTargetBlur1Depth);
        horizontalBlurPass.setShouldClearColor(false);
        horizontalBlurPass.setShouldClearDepth(false);
        horizontalBlurPass.setCamera(mActiveScene.getCameras().get(1));
        // Make blur shape
        quad = getDrawableQuad("ScreenAlignedQuadH", hBlur);
        quad.updateTextures(mRS, mRenderTargetBlur2Color, 0);
        horizontalBlurPass.appendDrawable(quad);
        mActiveScene.appendRenderPass(horizontalBlurPass);

        RenderPass verticalBlurPass = new RenderPass();
        verticalBlurPass.setColorTarget(mRenderTargetBlur2Color);
        verticalBlurPass.setDepthTarget(mRenderTargetBlur2Depth);
        verticalBlurPass.setShouldClearColor(false);
        verticalBlurPass.setShouldClearDepth(false);
        verticalBlurPass.setCamera(mActiveScene.getCameras().get(1));
        // Make blur shape
        quad = getDrawableQuad("ScreenAlignedQuadV", vBlur);
        quad.updateTextures(mRS, mRenderTargetBlur1Color, 0);
        verticalBlurPass.appendDrawable(quad);
        mActiveScene.appendRenderPass(verticalBlurPass);

    }

    void initRenderPasses() {
        ArrayList<DrawableBase> allDraw = mActiveScene.getDrawables();
        int numDraw = allDraw.size();

        if (mUseBlur) {
            addBlurPasses();
        }

        RenderPass mainPass = new RenderPass();
        mainPass.setClearColor(new Float4(1.0f, 1.0f, 1.0f, 1.0f));
        mainPass.setShouldClearColor(true);
        mainPass.setClearDepth(1.0f);
        mainPass.setShouldClearDepth(true);
        mainPass.setCamera(mActiveScene.getCameras().get(1));
        for (int i = 0; i < numDraw; i ++) {
            mainPass.appendDrawable((Drawable)allDraw.get(i));
        }
        mActiveScene.appendRenderPass(mainPass);

        if (mUseBlur) {
            RenderState drawTex = new RenderState(mPV_Blur, mPF_Texture,
                                            BLEND_ADD_DEPTH_NONE(mRS),
                                            ProgramRaster.CULL_NONE(mRS));

            RenderPass compositePass = new RenderPass();
            compositePass.setClearColor(new Float4(1.0f, 1.0f, 1.0f, 0.0f));
            compositePass.setShouldClearColor(false);
            compositePass.setClearDepth(1.0f);
            compositePass.setShouldClearDepth(false);
            compositePass.setCamera(mActiveScene.getCameras().get(1));
            Drawable quad = getDrawableQuad("ScreenAlignedQuad", drawTex);
            quad.updateTextures(mRS, mRenderTargetBlur2Color, 0);
            compositePass.appendDrawable(quad);

            mActiveScene.appendRenderPass(compositePass);
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

        Drawable plane = (Drawable)mActiveScene.getDrawableByName("pPlaneShape1");
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

        createRenderTargets();
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
