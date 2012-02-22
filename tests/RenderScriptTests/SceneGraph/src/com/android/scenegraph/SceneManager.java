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

import com.android.scenegraph.Camera;
import com.android.scenegraph.FragmentShader;
import com.android.scenegraph.MatrixTransform;
import com.android.scenegraph.Scene;
import com.android.scenegraph.VertexShader;
import com.android.testapp.R;

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

/**
 * @hide
 */
public class SceneManager extends SceneGraphBase {

    HashMap<String, Allocation> mAllocationMap;

    ScriptC_render mRenderLoop;
    ScriptC mCameraScript;
    ScriptC mLightScript;
    ScriptC mObjectParamsScript;
    ScriptC mFragmentParamsScript;
    ScriptC mVertexParamsScript;
    ScriptC mCullScript;
    ScriptC_transform mTransformScript;
    ScriptC_export mExportScript;

    RenderScriptGL mRS;
    Resources mRes;
    Mesh mQuad;
    int mWidth;
    int mHeight;

    Scene mActiveScene;
    private static SceneManager sSceneManager;

    private Allocation mDefault2D;
    private Allocation mDefaultCube;

    private FragmentShader mColor;
    private FragmentShader mTexture;
    private VertexShader mDefaultVertex;

    private RenderState mDefaultState;
    private Transform mDefaultTransform;

    private static Allocation getDefault(boolean isCube) {
        final int dimension = 4;
        final int bytesPerPixel = 4;
        int arraySize = dimension * dimension * bytesPerPixel;

        RenderScriptGL rs = sSceneManager.mRS;
        Type.Builder b = new Type.Builder(rs, Element.RGBA_8888(rs));
        b.setX(dimension).setY(dimension);
        if (isCube) {
            b.setFaces(true);
            arraySize *= 6;
        }
        Type bitmapType = b.create();

        Allocation.MipmapControl mip = Allocation.MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE;
        int usage =  Allocation.USAGE_GRAPHICS_TEXTURE;
        Allocation defaultImage = Allocation.createTyped(rs, bitmapType, mip, usage);

        byte imageData[] = new byte[arraySize];
        defaultImage.copyFrom(imageData);
        return defaultImage;
    }

    static Allocation getDefaultTex2D() {
        if (sSceneManager == null) {
            return null;
        }
        if (sSceneManager.mDefault2D == null) {
            sSceneManager.mDefault2D = getDefault(false);
        }
        return sSceneManager.mDefault2D;
    }

    static Allocation getDefaultTexCube() {
        if (sSceneManager == null) {
            return null;
        }
        if (sSceneManager.mDefaultCube == null) {
            sSceneManager.mDefaultCube = getDefault(true);
        }
        return sSceneManager.mDefaultCube;
    }

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

    static Allocation createFromBitmap(Bitmap b, RenderScriptGL rs, boolean isCube) {
        if (b == null) {
            return null;
        }
        MipmapControl mip = MipmapControl.MIPMAP_ON_SYNC_TO_TEXTURE;
        int usage = Allocation.USAGE_GRAPHICS_TEXTURE;
        if (isCube) {
            return Allocation.createCubemapFromBitmap(rs, b, mip, usage);
        }
        return Allocation.createFromBitmap(rs, b, mip, usage);
    }

    public static Allocation loadCubemap(String name, RenderScriptGL rs, Resources res) {
        return createFromBitmap(loadBitmap(name, res), rs, true);
    }

    public static Allocation loadCubemap(int id, RenderScriptGL rs, Resources res) {
        return createFromBitmap(BitmapFactory.decodeResource(res, id), rs, true);
    }

    public static Allocation loadTexture2D(String name, RenderScriptGL rs, Resources res) {
        return createFromBitmap(loadBitmap(name, res), rs, false);
    }

    public static Allocation loadTexture2D(int id, RenderScriptGL rs, Resources res) {
        return createFromBitmap(BitmapFactory.decodeResource(res, id), rs, false);
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

    static Allocation getCachedAlloc(String str) {
        if (sSceneManager == null) {
            throw new RuntimeException("Scene manager not initialized");
        }
        return sSceneManager.mAllocationMap.get(str);
    }

    static void cacheAlloc(String str, Allocation alloc) {
        if (sSceneManager == null) {
            throw new RuntimeException("Scene manager not initialized");
        }
        sSceneManager.mAllocationMap.put(str, alloc);
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

        if (mActiveScene == null) {
            return;
        }

        // Do some sanity checking
        if (mActiveScene.getCameras().size() == 0) {
            Matrix4f camPos = new Matrix4f();
            camPos.translate(0, 0, 10);
            MatrixTransform cameraTransform = new MatrixTransform();
            cameraTransform.setName("_DefaultCameraTransform");
            cameraTransform.setMatrix(camPos);
            mActiveScene.appendTransform(cameraTransform);
            Camera cam = new Camera();
            cam.setName("_DefaultCamera");
            cam.setTransform(cameraTransform);
            mActiveScene.appendCamera(cam);
        }

        mActiveScene.appendShader(getDefaultVS());
        mActiveScene.appendTransform(getDefaultTransform());
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

    // Provides the folowing inputs to fragment shader
    // Assigned by default if nothing is present
    // vec3 varWorldPos;
    // vec3 varWorldNormal;
    // vec2 varTex0;
    public static VertexShader getDefaultVS() {
        if (sSceneManager == null) {
            return null;
        }

        if (sSceneManager.mDefaultVertex == null) {
            RenderScriptGL rs = getRS();
            Element.Builder b = new Element.Builder(rs);
            b.add(Element.MATRIX_4X4(rs), "model");
            Type.Builder objConstBuilder = new Type.Builder(rs, b.create());

            b = new Element.Builder(rs);
            b.add(Element.MATRIX_4X4(rs), "viewProj");
            Type.Builder shaderConstBuilder = new Type.Builder(rs, b.create());

            b = new Element.Builder(rs);
            b.add(Element.F32_4(rs), "position");
            b.add(Element.F32_2(rs), "texture0");
            b.add(Element.F32_3(rs), "normal");
            Element defaultIn = b.create();

            final String code = "\n" +
                "varying vec3 varWorldPos;\n" +
                "varying vec3 varWorldNormal;\n" +
                "varying vec2 varTex0;\n" +
                "void main() {" +
                "   vec4 objPos = ATTRIB_position;\n" +
                "   vec4 worldPos = UNI_model * objPos;\n" +
                "   gl_Position = UNI_viewProj * worldPos;\n" +
                "   mat3 model3 = mat3(UNI_model[0].xyz, UNI_model[1].xyz, UNI_model[2].xyz);\n" +
                "   vec3 worldNorm = model3 * ATTRIB_normal;\n" +
                "   varWorldPos = worldPos.xyz;\n" +
                "   varWorldNormal = worldNorm;\n" +
                "   varTex0 = ATTRIB_texture0;\n" +
                "}\n";

            VertexShader.Builder sb = new VertexShader.Builder(rs);
            sb.addInput(defaultIn);
            sb.setObjectConst(objConstBuilder.setX(1).create());
            sb.setShaderConst(shaderConstBuilder.setX(1).create());
            sb.setShader(code);
            sSceneManager.mDefaultVertex = sb.create();
        }

        return sSceneManager.mDefaultVertex;
    }

    public static FragmentShader getColorFS() {
        if (sSceneManager == null) {
            return null;
        }
        if (sSceneManager.mColor == null) {
            RenderScriptGL rs = getRS();
            Element.Builder b = new Element.Builder(rs);
            b.add(Element.F32_4(rs), "color");
            Type.Builder objConstBuilder = new Type.Builder(rs, b.create());

            final String code = "\n" +
                "varying vec2 varTex0;\n" +
                "void main() {\n" +
                "   lowp vec4 col = UNI_color;\n" +
                "   gl_FragColor = col;\n" +
                "}\n";
            FragmentShader.Builder fb = new FragmentShader.Builder(rs);
            fb.setShader(code);
            fb.setObjectConst(objConstBuilder.create());
            sSceneManager.mColor = fb.create();
        }

        return sSceneManager.mColor;
    }

    public static FragmentShader getTextureFS() {
        if (sSceneManager == null) {
            return null;
        }
        if (sSceneManager.mTexture == null) {
            RenderScriptGL rs = getRS();

            final String code = "\n" +
                "varying vec2 varTex0;\n" +
                "void main() {\n" +
                "   lowp vec4 col = texture2D(UNI_color, varTex0).rgba;\n" +
                "   gl_FragColor = col;\n" +
                "}\n";

            FragmentShader.Builder fb = new FragmentShader.Builder(rs);
            fb.setShader(code);
            fb.addTexture(Program.TextureType.TEXTURE_2D, "color");
            sSceneManager.mTexture = fb.create();
            sSceneManager.mTexture.mProgram.bindSampler(Sampler.CLAMP_LINEAR_MIP_LINEAR(rs), 0);
        }

        return sSceneManager.mTexture;
    }

    static RenderState getDefaultState() {
        if (sSceneManager == null) {
            return null;
        }
        if (sSceneManager.mDefaultState == null) {
            sSceneManager.mDefaultState = new RenderState(getDefaultVS(), getColorFS(), null, null);
            sSceneManager.mDefaultState.setName("__DefaultState");
        }
        return sSceneManager.mDefaultState;
    }

    static Transform getDefaultTransform() {
        if (sSceneManager == null) {
            return null;
        }
        if (sSceneManager.mDefaultTransform == null) {
            sSceneManager.mDefaultTransform = new MatrixTransform();
            sSceneManager.mDefaultTransform.setName("__DefaultTransform");
        }
        return sSceneManager.mDefaultTransform;
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

        tmb.setTexture(0.0f, 1.0f).addVertex(-1.0f, 1.0f, 1.0f);
        tmb.setTexture(0.0f, 0.0f).addVertex(-1.0f, -1.0f, 1.0f);
        tmb.setTexture(1.0f, 0.0f).addVertex(1.0f, -1.0f, 1.0f);
        tmb.setTexture(1.0f, 1.0f).addVertex(1.0f, 1.0f, 1.0f);

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
        mAllocationMap = new HashMap<String, Allocation>();

        mQuad = null;
        mDefault2D = null;
        mDefaultCube = null;
        mDefaultVertex = null;
        mColor = null;
        mTexture = null;
        mDefaultState = null;
        mDefaultTransform = null;

        mExportScript = new ScriptC_export(rs, res, R.raw.export);

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

        mRenderLoop.set_gPFSBackground(ProgramStore.BLEND_NONE_DEPTH_TEST(mRS));
    }

    public ScriptC getRenderLoop() {
        return mRenderLoop;
    }
}




