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

import java.util.ArrayList;

import com.android.scenegraph.Float4Param;
import com.android.scenegraph.SceneManager;
import com.android.scenegraph.Texture2D;
import com.android.scenegraph.TextureParam;

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

class FullscreenBlur {

    static Allocation sRenderTargetBlur0Color;
    static Allocation sRenderTargetBlur0Depth;
    static Allocation sRenderTargetBlur1Color;
    static Allocation sRenderTargetBlur1Depth;
    static Allocation sRenderTargetBlur2Color;
    static Allocation sRenderTargetBlur2Depth;

    static FragmentShader mPF_BlurH;
    static FragmentShader mPF_BlurV;
    static FragmentShader mPF_SelectColor;
    static FragmentShader mPF_Texture;
    static VertexShader mPV_Paint;
    static VertexShader mPV_Blur;

    // This is only used when full screen blur is enabled
    // Basically, it's the offscreen render targets
    static void createRenderTargets(RenderScriptGL rs, int w, int h) {
        Type.Builder b = new Type.Builder(rs, Element.RGBA_8888(rs));
        Type renderType = b.setX(w/8).setY(h/8).create();
        int usage = Allocation.USAGE_GRAPHICS_TEXTURE | Allocation.USAGE_GRAPHICS_RENDER_TARGET;
        sRenderTargetBlur0Color = Allocation.createTyped(rs, renderType, usage);
        sRenderTargetBlur1Color = Allocation.createTyped(rs, renderType, usage);
        sRenderTargetBlur2Color = Allocation.createTyped(rs, renderType, usage);

        b = new Type.Builder(rs, Element.createPixel(rs, Element.DataType.UNSIGNED_16,
                                                     Element.DataKind.PIXEL_DEPTH));
        renderType = b.setX(w/8).setY(h/8).create();
        usage = Allocation.USAGE_GRAPHICS_RENDER_TARGET;
        sRenderTargetBlur0Depth = Allocation.createTyped(rs, renderType, usage);
        sRenderTargetBlur1Depth = Allocation.createTyped(rs, renderType, usage);
        sRenderTargetBlur2Depth = Allocation.createTyped(rs, renderType, usage);
    }

    static void addOffsets(Renderable quad, float advance) {
        quad.appendSourceParams(new Float4Param("blurOffset0", - advance * 2.5f));
        quad.appendSourceParams(new Float4Param("blurOffset1", - advance * 0.5f));
        quad.appendSourceParams(new Float4Param("blurOffset2", advance * 1.5f));
        quad.appendSourceParams(new Float4Param("blurOffset3", advance * 3.5f));
    }

    static RenderPass addPass(Scene scene, Allocation color, Allocation depth) {
        RenderPass pass = new RenderPass();
        pass.setColorTarget(color);
        pass.setDepthTarget(depth);
        pass.setShouldClearColor(false);
        pass.setShouldClearDepth(false);
        pass.setCamera(scene.getCameras().get(1));
        scene.appendRenderPass(pass);
        return pass;
    }

    static void addBlurPasses(Scene scene, RenderScriptGL rs) {
        SceneManager sceneManager = SceneManager.getInstance();
        ArrayList<RenderableBase> allDraw = scene.getRenderables();
        int numDraw = allDraw.size();

        ProgramRaster cullNone = ProgramRaster.CULL_NONE(rs);
        ProgramStore blendAdd = SceneManager.BLEND_ADD_DEPTH_NONE(rs);
        ProgramStore blendNone = ProgramStore.BLEND_NONE_DEPTH_NONE(rs);

        RenderState drawTex = new RenderState(mPV_Blur, mPF_Texture, blendAdd, cullNone);
        RenderState selectCol = new RenderState(mPV_Blur, mPF_SelectColor, blendNone, cullNone);
        RenderState hBlur = new RenderState(mPV_Blur, mPF_BlurH, blendNone, cullNone);
        RenderState vBlur = new RenderState(mPV_Blur, mPF_BlurV, blendNone, cullNone);

        // Renders the scene off screen
        RenderPass blurSourcePass = addPass(scene,
                                            sRenderTargetBlur0Color,
                                            sRenderTargetBlur0Depth);
        blurSourcePass.setClearColor(new Float4(1.0f, 1.0f, 1.0f, 1.0f));
        blurSourcePass.setShouldClearColor(true);
        blurSourcePass.setClearDepth(1.0f);
        blurSourcePass.setShouldClearDepth(true);
        for (int i = 0; i < numDraw; i ++) {
            blurSourcePass.appendRenderable((Renderable)allDraw.get(i));
        }

        // Pass for selecting bright colors
        RenderPass selectColorPass = addPass(scene,
                                             sRenderTargetBlur2Color,
                                             sRenderTargetBlur2Depth);
        Renderable quad = sceneManager.getRenderableQuad("ScreenAlignedQuadS", selectCol);
        quad.appendSourceParams(new TextureParam("tex0", new Texture2D(sRenderTargetBlur0Color)));
        selectColorPass.appendRenderable(quad);

        // Horizontal blur
        RenderPass horizontalBlurPass = addPass(scene,
                                                sRenderTargetBlur1Color,
                                                sRenderTargetBlur1Depth);
        quad = sceneManager.getRenderableQuad("ScreenAlignedQuadH", hBlur);
        quad.appendSourceParams(new TextureParam("tex0", new Texture2D(sRenderTargetBlur2Color)));
        addOffsets(quad, 1.0f / (float)sRenderTargetBlur0Color.getType().getX());
        horizontalBlurPass.appendRenderable(quad);

        // Vertical Blur
        RenderPass verticalBlurPass = addPass(scene,
                                              sRenderTargetBlur2Color,
                                              sRenderTargetBlur2Depth);
        quad = sceneManager.getRenderableQuad("ScreenAlignedQuadV", vBlur);
        quad.appendSourceParams(new TextureParam("tex0", new Texture2D(sRenderTargetBlur1Color)));
        addOffsets(quad, 1.0f / (float)sRenderTargetBlur0Color.getType().getY());
        verticalBlurPass.appendRenderable(quad);
    }

    // Additively renders the blurred colors on top of the scene
    static void addCompositePass(Scene scene, RenderScriptGL rs) {
        SceneManager sceneManager = SceneManager.getInstance();
        RenderState drawTex = new RenderState(mPV_Blur, mPF_Texture,
                                              SceneManager.BLEND_ADD_DEPTH_NONE(rs),
                                              ProgramRaster.CULL_NONE(rs));

        RenderPass compositePass = addPass(scene, null, null);
        Renderable quad = sceneManager.getRenderableQuad("ScreenAlignedQuad", drawTex);
        quad.appendSourceParams(new TextureParam("tex0", new Texture2D(sRenderTargetBlur2Color)));
        compositePass.appendRenderable(quad);
    }

    static private FragmentShader getShader(Resources res, RenderScriptGL rs,
                                            int resID, Type constants) {
        FragmentShader.Builder fb = new FragmentShader.Builder(rs);
        fb.setShader(res, resID);
        fb.addTexture(TextureType.TEXTURE_2D, "color");
        if (constants != null) {
            fb.setObjectConst(constants);
        }
        FragmentShader prog = fb.create();
        prog.mProgram.bindSampler(Sampler.CLAMP_LINEAR(rs), 0);
        return prog;
    }

    static void initShaders(Resources res, RenderScriptGL rs) {
        ScriptField_FBlurOffsets_s blurConst = new ScriptField_FBlurOffsets_s(rs, 1);
        VertexShader.Builder vb = new VertexShader.Builder(rs);
        vb.addInput(ScriptField_VertexShaderInputs_s.createElement(rs));
        vb.setShader(res, R.raw.blur_vertex);
        mPV_Blur = vb.create();

        mPF_Texture = getShader(res, rs, R.raw.texture, null);
        mPF_Texture.mProgram.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(rs), 0);
        mPF_BlurH = getShader(res, rs, R.raw.blur_h, blurConst.getAllocation().getType());
        mPF_BlurV = getShader(res, rs, R.raw.blur_v, blurConst.getAllocation().getType());
        mPF_SelectColor = getShader(res, rs, R.raw.select_color, null);
    }

}





