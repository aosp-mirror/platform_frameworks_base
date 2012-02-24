/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.testapp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;

import com.android.scenegraph.*;
import com.android.scenegraph.SceneManager.SceneLoadedCallback;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.renderscript.*;
import android.renderscript.Program.TextureType;
import android.util.Log;

// This is where the scenegraph and the rendered objects are initialized and used
public class SimpleAppRS {
    SceneManager mSceneManager;

    RenderScriptGL mRS;
    Resources mRes;

    Scene mScene;
    Mesh mSimpleMesh;
    Mesh mSphereMesh;
    Mesh mCubeMesh;

    public void init(RenderScriptGL rs, Resources res, int width, int height) {
        mRS = rs;
        mRes = res;
        mSceneManager = SceneManager.getInstance();
        mSceneManager.initRS(mRS, mRes, width, height);

        mScene = new Scene();

        setupGeometry();
        setupColoredQuad();
        setupTexturedQuad();
        setupShadedGeometry();
        setupCamera();
        setupRenderPass();

        mSceneManager.setActiveScene(mScene);

        mScene.initRS();
        mRS.bindRootScript(mSceneManager.getRenderLoop());
    }

    private void setupGeometry() {
        Mesh.TriangleMeshBuilder tmb = new Mesh.TriangleMeshBuilder(mRS, 3,
                                                         Mesh.TriangleMeshBuilder.TEXTURE_0);

        // Create four vertices with texture coordinates
        tmb.setTexture(0.0f, 1.0f).addVertex(-1.0f, 1.0f, 0.0f);
        tmb.setTexture(0.0f, 0.0f).addVertex(-1.0f, -1.0f, 0.0f);
        tmb.setTexture(1.0f, 0.0f).addVertex(1.0f, -1.0f, 0.0f);
        tmb.setTexture(1.0f, 1.0f).addVertex(1.0f, 1.0f, 0.0f);

        tmb.addTriangle(0, 1, 2);
        tmb.addTriangle(2, 3, 0);
        mSimpleMesh = tmb.create(true);

        // Load a file that constains two pieces of geometry, a sphere and a cube
        FileA3D model = FileA3D.createFromResource(mRS, mRes, R.raw.unit_obj);
        for (int i = 0; i < model.getIndexEntryCount(); i ++) {
            FileA3D.IndexEntry entry = model.getIndexEntry(i);
            if (entry != null && entry.getName().equals("CubeMesh")) {
                mCubeMesh = entry.getMesh();
            } else if (entry != null && entry.getName().equals("SphereMesh")) {
                mSphereMesh = entry.getMesh();
            }
        }
    }

    private void setupColoredQuad() {
        // Built-in shader that provides position, texcoord and normal
        VertexShader genericV = SceneManager.getDefaultVS();
        // Built-in shader that displays a color
        FragmentShader colorF = SceneManager.getColorFS();
        RenderState colorRS = new RenderState(genericV, colorF, null, null);

        // Draw a simple colored quad
        Renderable quad = mScene.appendNewRenderable();
        quad.setMesh(mSimpleMesh);
        // Our shader has a constant input called "color"
        // This tells the scenegraph to assign the following float3 to that input
        quad.appendSourceParams(new Float4Param("color", 0.2f, 0.3f, 0.4f));
        quad.setRenderState(colorRS);
    }

    private void setupTexturedQuad() {
        // Built-in shader that provides position, texcoord and normal
        VertexShader genericV = SceneManager.getDefaultVS();
        // Built-in shader that displays a texture
        FragmentShader textureF = SceneManager.getTextureFS();
        // We want to use transparency based on the alpha channel of the texture
        ProgramStore alphaBlend = ProgramStore.BLEND_ALPHA_DEPTH_TEST(mRS);
        RenderState texRS = new RenderState(genericV, textureF, alphaBlend, null);

        // Draw a textured quad
        Renderable quad = mScene.appendNewRenderable();
        quad.setMesh(mSimpleMesh);
        // Make a transform to position the quad
        CompoundTransform t = mScene.appendNewCompoundTransform();
        t.addTranslate("position", new Float3(2, 2, 0));
        quad.setTransform(t);
        // Our fragment shader has a constant texture input called "color"
        // This will assign an icon from drawables to that input
        quad.appendSourceParams(new TextureParam("color", new Texture2D(R.drawable.icon)));
        quad.setRenderState(texRS);
    }

    private FragmentShader createLambertShader() {
        // Describe what constant inputs our shader wants
        Element.Builder b = new Element.Builder(mRS);
        b.add(Element.F32_4(mRS), "cameraPos");

        // Create a shader from a text file in resources
        FragmentShader.Builder fb = new FragmentShader.Builder(mRS);
        // Tell the shader what constants we want
        fb.setShaderConst(new Type.Builder(mRS, b.create()).setX(1).create());
        // Shader code location
        fb.setShader(mRes, R.raw.diffuse);
        // We want a texture called diffuse on our shader
        fb.addTexture(TextureType.TEXTURE_2D, "diffuse");
        FragmentShader shader = fb.create();
        mScene.appendShader(shader);
        return shader;
    }

    private void setupShadedGeometry() {
        // Built-in shader that provides position, texcoord and normal
        VertexShader genericV = SceneManager.getDefaultVS();
        // Custom shader
        FragmentShader diffuseF = createLambertShader();
        RenderState diffuseRS = new RenderState(genericV, diffuseF, null, null);

        // Draw a sphere
        Renderable sphere = mScene.appendNewRenderable();
        // Use the sphere geometry loaded earlier
        sphere.setMesh(mSphereMesh);
        // Make a transform to position the sphere
        CompoundTransform t = mScene.appendNewCompoundTransform();
        t.addTranslate("position", new Float3(-1, 2, 3));
        t.addScale("scale", new Float3(1.4f, 1.4f, 1.4f));
        sphere.setTransform(t);
        // Tell the renderable which texture to use when we draw
        // This will mean a texture param in the shader called "diffuse"
        // will be assigned a texture called red.jpg
        sphere.appendSourceParams(new TextureParam("diffuse", new Texture2D("", "red.jpg")));
        sphere.setRenderState(diffuseRS);

        // Draw a cube
        Renderable cube = mScene.appendNewRenderable();
        cube.setMesh(mCubeMesh);
        t = mScene.appendNewCompoundTransform();
        t.addTranslate("position", new Float3(-2, -2.1f, 0));
        t.addRotate("rotateX", new Float3(1, 0, 0), 30);
        t.addRotate("rotateY", new Float3(0, 1, 0), 30);
        t.addScale("scale", new Float3(2, 2, 2));
        cube.setTransform(t);
        cube.appendSourceParams(new TextureParam("diffuse", new Texture2D("", "orange.jpg")));
        cube.setRenderState(diffuseRS);
    }

    private void setupCamera() {
        Camera camera = mScene.appendNewCamera();
        camera.setFar(200);
        camera.setNear(0.1f);
        camera.setFOV(60);
        CompoundTransform cameraTransform = mScene.appendNewCompoundTransform();
        cameraTransform.addTranslate("camera", new Float3(0, 0, 10));
        camera.setTransform(cameraTransform);
    }

    private void setupRenderPass() {
        RenderPass mainPass = mScene.appendNewRenderPass();
        mainPass.setClearColor(new Float4(1.0f, 1.0f, 1.0f, 1.0f));
        mainPass.setShouldClearColor(true);
        mainPass.setClearDepth(1.0f);
        mainPass.setShouldClearDepth(true);
        mainPass.setCamera(mScene.getCameras().get(0));
        ArrayList<RenderableBase> allRender = mScene.getRenderables();
        for (RenderableBase renderable : allRender) {
            mainPass.appendRenderable((Renderable)renderable);
        }
    }
}
