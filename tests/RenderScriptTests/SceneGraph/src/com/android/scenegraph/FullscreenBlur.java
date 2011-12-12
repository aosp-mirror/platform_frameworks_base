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

import java.util.ArrayList;

class GaussianBlur {

    static Allocation sRenderTargetBlur0Color;
    static Allocation sRenderTargetBlur0Depth;
    static Allocation sRenderTargetBlur1Color;
    static Allocation sRenderTargetBlur1Depth;
    static Allocation sRenderTargetBlur2Color;
    static Allocation sRenderTargetBlur2Depth;

    static ProgramFragment mPF_BlurH;
    static ProgramFragment mPF_BlurV;
    static ProgramFragment mPF_SelectColor;
    static ProgramFragment mPF_Texture;
    static ScriptField_FBlurOffsets_s mFsBlurHConst;
    static ScriptField_FBlurOffsets_s mFsBlurVConst;
    static ProgramVertex mPV_Paint;
    static ProgramVertex mPV_Blur;

    // This is only used when full screen blur is enabled
    // Basically, it's the offscreen render targets
    static void createRenderTargets(RenderScriptGL rs, int w, int h) {
        Type.Builder b = new Type.Builder(rs, Element.RGBA_8888(rs));
        b.setX(w/8).setY(h/8);
        Type renderType = b.create();
        sRenderTargetBlur0Color = Allocation.createTyped(rs, renderType,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE |
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        sRenderTargetBlur1Color = Allocation.createTyped(rs, renderType,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE |
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        sRenderTargetBlur2Color = Allocation.createTyped(rs, renderType,
                                                         Allocation.USAGE_GRAPHICS_TEXTURE |
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);

        b = new Type.Builder(rs,
                             Element.createPixel(rs, Element.DataType.UNSIGNED_16,
                                                 Element.DataKind.PIXEL_DEPTH));
        b.setX(w/8).setY(h/8);
        sRenderTargetBlur0Depth = Allocation.createTyped(rs,
                                                         b.create(),
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);

        sRenderTargetBlur1Depth = Allocation.createTyped(rs,
                                                         b.create(),
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);
        sRenderTargetBlur2Depth = Allocation.createTyped(rs,
                                                         b.create(),
                                                         Allocation.USAGE_GRAPHICS_RENDER_TARGET);
    }

    private static Drawable getDrawableQuad(String name, RenderState state, SceneManager sceneManager) {
        Drawable quad = new Drawable();
        quad.setTransform(new MatrixTransform());
        quad.setMesh(sceneManager.getScreenAlignedQuad());
        quad.setName(name);
        quad.setRenderState(state);
        quad.setCullType(1);
        return quad;
    }

    static void addBlurPasses(Scene scene, RenderScriptGL rs, SceneManager sceneManager) {
        ArrayList<DrawableBase> allDraw = scene.getDrawables();
        int numDraw = allDraw.size();

        RenderState drawTex = new RenderState(mPV_Blur, mPF_Texture,
                                            BLEND_ADD_DEPTH_NONE(rs),
                                            ProgramRaster.CULL_NONE(rs));

        RenderState selectCol = new RenderState(mPV_Blur, mPF_SelectColor,
                                            ProgramStore.BLEND_NONE_DEPTH_NONE(rs),
                                            ProgramRaster.CULL_NONE(rs));

        RenderState hBlur = new RenderState(mPV_Blur, mPF_BlurH,
                                            ProgramStore.BLEND_NONE_DEPTH_NONE(rs),
                                            ProgramRaster.CULL_NONE(rs));

        RenderState vBlur = new RenderState(mPV_Blur, mPF_BlurV,
                                            ProgramStore.BLEND_NONE_DEPTH_NONE(rs),
                                            ProgramRaster.CULL_NONE(rs));

        RenderPass blurSourcePass = new RenderPass();
        blurSourcePass.setColorTarget(sRenderTargetBlur0Color);
        blurSourcePass.setDepthTarget(sRenderTargetBlur0Depth);
        blurSourcePass.setClearColor(new Float4(1.0f, 1.0f, 1.0f, 1.0f));
        blurSourcePass.setShouldClearColor(true);
        blurSourcePass.setClearDepth(1.0f);
        blurSourcePass.setShouldClearDepth(true);
        blurSourcePass.setCamera(scene.getCameras().get(1));
        for (int i = 0; i < numDraw; i ++) {
            blurSourcePass.appendDrawable((Drawable)allDraw.get(i));
        }
        scene.appendRenderPass(blurSourcePass);

        RenderPass selectColorPass = new RenderPass();
        selectColorPass.setColorTarget(sRenderTargetBlur2Color);
        selectColorPass.setDepthTarget(sRenderTargetBlur2Depth);
        selectColorPass.setShouldClearColor(false);
        selectColorPass.setShouldClearDepth(false);
        selectColorPass.setCamera(scene.getCameras().get(1));
        // Make blur shape
        Drawable quad = getDrawableQuad("ScreenAlignedQuadS", selectCol, sceneManager);
        quad.updateTextures(rs, sRenderTargetBlur0Color, 0);
        selectColorPass.appendDrawable(quad);
        scene.appendRenderPass(selectColorPass);

        RenderPass horizontalBlurPass = new RenderPass();
        horizontalBlurPass.setColorTarget(sRenderTargetBlur1Color);
        horizontalBlurPass.setDepthTarget(sRenderTargetBlur1Depth);
        horizontalBlurPass.setShouldClearColor(false);
        horizontalBlurPass.setShouldClearDepth(false);
        horizontalBlurPass.setCamera(scene.getCameras().get(1));
        // Make blur shape
        quad = getDrawableQuad("ScreenAlignedQuadH", hBlur, sceneManager);
        quad.updateTextures(rs, sRenderTargetBlur2Color, 0);
        horizontalBlurPass.appendDrawable(quad);
        scene.appendRenderPass(horizontalBlurPass);

        RenderPass verticalBlurPass = new RenderPass();
        verticalBlurPass.setColorTarget(sRenderTargetBlur2Color);
        verticalBlurPass.setDepthTarget(sRenderTargetBlur2Depth);
        verticalBlurPass.setShouldClearColor(false);
        verticalBlurPass.setShouldClearDepth(false);
        verticalBlurPass.setCamera(scene.getCameras().get(1));
        // Make blur shape
        quad = getDrawableQuad("ScreenAlignedQuadV", vBlur, sceneManager);
        quad.updateTextures(rs, sRenderTargetBlur1Color, 0);
        verticalBlurPass.appendDrawable(quad);
        scene.appendRenderPass(verticalBlurPass);

    }

    static void addCompositePass(Scene scene, RenderScriptGL rs, SceneManager sceneManager) {
        RenderState drawTex = new RenderState(mPV_Blur, mPF_Texture,
                                            BLEND_ADD_DEPTH_NONE(rs),
                                            ProgramRaster.CULL_NONE(rs));

        RenderPass compositePass = new RenderPass();
        compositePass.setClearColor(new Float4(1.0f, 1.0f, 1.0f, 0.0f));
        compositePass.setShouldClearColor(false);
        compositePass.setClearDepth(1.0f);
        compositePass.setShouldClearDepth(false);
        compositePass.setCamera(scene.getCameras().get(1));
        Drawable quad = getDrawableQuad("ScreenAlignedQuad", drawTex, sceneManager);
        quad.updateTextures(rs, sRenderTargetBlur2Color, 0);
        compositePass.appendDrawable(quad);

        scene.appendRenderPass(compositePass);
    }

    private static ProgramStore BLEND_ADD_DEPTH_NONE(RenderScript rs) {
        ProgramStore.Builder builder = new ProgramStore.Builder(rs);
        builder.setDepthFunc(ProgramStore.DepthFunc.ALWAYS);
        builder.setBlendFunc(ProgramStore.BlendSrcFunc.ONE, ProgramStore.BlendDstFunc.ONE);
        builder.setDitherEnabled(false);
        builder.setDepthMaskEnabled(false);
        return builder.create();
    }

    static void initShaders(Resources res, RenderScript rs,
                            ScriptField_VShaderParams_s vsConst,
                            ScriptField_FShaderParams_s fsConst) {
        ProgramVertex.Builder vb = new ProgramVertex.Builder(rs);
        vb.addConstant(vsConst.getAllocation().getType());
        vb.addInput(ScriptField_VertexShaderInputs_s.createElement(rs));
        vb.setShader(res, R.raw.blur_vertex);
        mPV_Blur = vb.create();
        mPV_Blur.bindConstants(vsConst.getAllocation(), 0);

        ProgramFragment.Builder fb = new ProgramFragment.Builder(rs);
        fb.addConstant(fsConst.getAllocation().getType());
        fb.setShader(res, R.raw.texture);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_Texture = fb.create();
        mPF_Texture.bindConstants(fsConst.getAllocation(), 0);
        mPF_Texture.bindSampler(Sampler.WRAP_LINEAR_MIP_LINEAR(rs), 0);

        mFsBlurHConst = new ScriptField_FBlurOffsets_s(rs, 1);
        float xAdvance = 1.0f / (float)sRenderTargetBlur0Color.getType().getX();
        ScriptField_FBlurOffsets_s.Item item = new ScriptField_FBlurOffsets_s.Item();
        item.blurOffset0 = - xAdvance * 2.5f;
        item.blurOffset1 = - xAdvance * 0.5f;
        item.blurOffset2 =   xAdvance * 1.5f;
        item.blurOffset3 =   xAdvance * 3.5f;
        mFsBlurHConst.set(item, 0, true);

        fb = new ProgramFragment.Builder(rs);
        fb.addConstant(mFsBlurHConst.getAllocation().getType());
        fb.setShader(res, R.raw.blur_h);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_BlurH = fb.create();
        mPF_BlurH.bindConstants(mFsBlurHConst.getAllocation(), 0);
        mPF_BlurH.bindTexture(sRenderTargetBlur0Color, 0);
        mPF_BlurH.bindSampler(Sampler.CLAMP_LINEAR(rs), 0);

        mFsBlurVConst = new ScriptField_FBlurOffsets_s(rs, 1);
        float yAdvance = 1.0f / (float)sRenderTargetBlur0Color.getType().getY();
        item.blurOffset0 = - yAdvance * 2.5f;
        item.blurOffset1 = - yAdvance * 0.5f;
        item.blurOffset2 =   yAdvance * 1.5f;
        item.blurOffset3 =   yAdvance * 3.5f;
        mFsBlurVConst.set(item, 0, true);

        fb = new ProgramFragment.Builder(rs);
        fb.addConstant(mFsBlurVConst.getAllocation().getType());
        fb.setShader(res, R.raw.blur_v);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_BlurV = fb.create();
        mPF_BlurV.bindConstants(mFsBlurVConst.getAllocation(), 0);
        mPF_BlurV.bindTexture(sRenderTargetBlur1Color, 0);
        mPF_BlurV.bindSampler(Sampler.CLAMP_LINEAR(rs), 0);

        fb = new ProgramFragment.Builder(rs);
        //fb.addConstant(mFsBlurVConst.getAllocation().getType());
        fb.setShader(res, R.raw.select_color);
        fb.addTexture(TextureType.TEXTURE_2D);
        mPF_SelectColor = fb.create();
        //mPF_SelectColor.bindConstants(mFsBlurVConst.getAllocation(), 0);
        //mPF_SelectColor.bindTexture(sRenderTargetBlur1Color, 0);
        mPF_SelectColor.bindSampler(Sampler.CLAMP_LINEAR(rs), 0);
    }

}





