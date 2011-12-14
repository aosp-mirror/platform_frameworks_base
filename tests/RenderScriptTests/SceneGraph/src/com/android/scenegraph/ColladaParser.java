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
import com.android.scenegraph.CompoundTransform.TranslateComponent;
import com.android.scenegraph.CompoundTransform.RotateComponent;
import com.android.scenegraph.CompoundTransform.ScaleComponent;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.StringTokenizer;
import java.util.HashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import android.renderscript.*;
import android.util.Log;

public class ColladaParser {
    static final String TAG = "ColladaParser";
    Document mDom;

    HashMap<String, LightBase> mLights;
    HashMap<String, Camera> mCameras;
    HashMap<String, ArrayList<ShaderParam> > mEffectsParams;
    HashMap<String, Texture2D> mImages;
    HashMap<String, Texture2D> mSamplerImageMap;
    Scene mScene;

    String toString(Float3 v) {
        String valueStr = v.x + " " + v.y + " " + v.z;
        return valueStr;
    }

    String toString(Float4 v) {
        String valueStr = v.x + " " + v.y + " " + v.z + " " + v.w;
        return valueStr;
    }

    public ColladaParser(){
        mLights = new HashMap<String, LightBase>();
        mCameras = new HashMap<String, Camera>();
        mEffectsParams = new HashMap<String, ArrayList<ShaderParam> >();
        mImages = new HashMap<String, Texture2D>();
    }

    public void init(InputStream is) {
        mLights.clear();
        mCameras.clear();
        mEffectsParams.clear();

        long start = System.currentTimeMillis();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder db = dbf.newDocumentBuilder();
            mDom = db.parse(is);
        } catch(ParserConfigurationException e) {
            e.printStackTrace();
        } catch(SAXException e) {
            e.printStackTrace();
        } catch(IOException e) {
            e.printStackTrace();
        }
        long end = System.currentTimeMillis();
        Log.v("TIMER", "    Parse time: " + (end - start));
        exportSceneData();
    }

    Scene getScene() {
        return mScene;
    }

    private void exportSceneData(){
        mScene = new Scene();

        Element docEle = mDom.getDocumentElement();
        NodeList nl = docEle.getElementsByTagName("light");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element l = (Element)nl.item(i);
                convertLight(l);
            }
        }

        nl = docEle.getElementsByTagName("camera");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element c = (Element)nl.item(i);
                convertCamera(c);
            }
        }

        nl = docEle.getElementsByTagName("image");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element img = (Element)nl.item(i);
                convertImage(img);
            }
        }

        nl = docEle.getElementsByTagName("effect");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element e = (Element)nl.item(i);
                convertEffects(e);
            }
        }

        // Material is just a link to the effect
        nl = docEle.getElementsByTagName("material");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element m = (Element)nl.item(i);
                convertMaterials(m);
            }
        }

        nl = docEle.getElementsByTagName("visual_scene");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element s = (Element)nl.item(i);
                getScene(s);
            }
        }
    }

    private void getRenderable(Element shape, Transform t) {
        String geoURL = shape.getAttribute("url");
        //RenderableGroup group = new RenderableGroup();
        //group.setName(geoURL.substring(1));
        //mScene.appendRenderable(group);
        NodeList nl = shape.getElementsByTagName("instance_material");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element materialRef = (Element)nl.item(i);
                String meshIndexName = materialRef.getAttribute("symbol");
                String materialName = materialRef.getAttribute("target");

                Renderable d = new Renderable();
                d.setMesh(geoURL.substring(1), meshIndexName);
                d.setMaterialName(materialName);
                d.setName(geoURL.substring(1));

                //Log.v(TAG, "Created drawable geo " + geoURL + " index " + meshIndexName + " material " + materialName);

                // Append transform and material data here
                TransformParam modelP = new TransformParam("model");
                modelP.setTransform(t);
                d.appendSourceParams(modelP);
                d.setTransform(t);
                //Log.v(TAG, "Set source param " + t.getName());

                // Now find all the parameters that exist on the material
                ArrayList<ShaderParam> materialParams;
                materialParams = mEffectsParams.get(materialName.substring(1));
                for (int pI = 0; pI < materialParams.size(); pI ++) {
                    d.appendSourceParams(materialParams.get(pI));
                    //Log.v(TAG, "Set source param i: " + pI + " name " + materialParams.get(pI).getParamName());
                }
                mScene.appendRenderable(d);
                //group.appendChildren(d);
            }
        }
    }

    private void updateLight(Element shape, Transform t) {
        String lightURL = shape.getAttribute("url");
        // collada uses a uri structure to link things,
        // but we ignore it for now and do a simple search
        LightBase light = mLights.get(lightURL.substring(1));
        if (light != null) {
            light.setTransform(t);
            //Log.v(TAG, "Set Light " + light.getName() + " " + t.getName());
        }
    }

    private void updateCamera(Element shape, Transform t) {
        String camURL = shape.getAttribute("url");
        // collada uses a uri structure to link things,
        // but we ignore it for now and do a simple search
        Camera cam = mCameras.get(camURL.substring(1));
        if (cam != null) {
            cam.setTransform(t);
            //Log.v(TAG, "Set Camera " + cam.getName() + " " + t.getName());
        }
    }

    private void getNode(Element node, Transform parent, String indent) {
        String name = node.getAttribute("name");
        String id = node.getAttribute("id");
        CompoundTransform current = new CompoundTransform();
        current.setName(name);
        if (parent != null) {
            parent.appendChild(current);
        } else {
            mScene.appendTransform(current);
        }

        mScene.addToTransformMap(current);

        //Log.v(TAG, indent + "|");
        //Log.v(TAG, indent + "[" + name + "]");

        Node childNode = node.getFirstChild();
        while (childNode != null) {
            if (childNode.getNodeType() == Node.ELEMENT_NODE) {
                Element field = (Element)childNode;
                String fieldName = field.getTagName();
                String description = field.getAttribute("sid");
                if (fieldName.equals("translate")) {
                    Float3 value = getFloat3(field);
                    current.addComponent(new TranslateComponent(description, value));
                    //Log.v(TAG, indent + " translate " + description + toString(value));
                } else if (fieldName.equals("rotate")) {
                    Float4 value = getFloat4(field);
                    //Log.v(TAG, indent + " rotate " + description + toString(value));
                    Float3 axis = new Float3(value.x, value.y, value.z);
                    current.addComponent(new RotateComponent(description, axis, value.w));
                } else if (fieldName.equals("scale")) {
                    Float3 value = getFloat3(field);
                    //Log.v(TAG, indent + " scale " + description + toString(value));
                    current.addComponent(new ScaleComponent(description, value));
                } else if (fieldName.equals("instance_geometry")) {
                    getRenderable(field, current);
                } else if (fieldName.equals("instance_light")) {
                    updateLight(field, current);
                } else if (fieldName.equals("instance_camera")) {
                    updateCamera(field, current);
                } else if (fieldName.equals("node")) {
                    getNode(field, current, indent + "   ");
                }
            }
            childNode = childNode.getNextSibling();
        }
    }

    Texture2D getTexture(String samplerName) {
        Element sampler = mDom.getElementById(samplerName);
        if (sampler == null) {
            return null;
        }

        NodeList nl = sampler.getElementsByTagName("source");
        if (nl != null && nl.getLength() == 1) {
            Element ref = (Element)nl.item(0);
            String surfaceName = getString(ref);
            if (surfaceName == null) {
                return null;
            }

            Element surface = mDom.getElementById(surfaceName);
            if (surface == null) {
                return null;
            }
            nl = surface.getElementsByTagName("init_from");
            if (nl != null && nl.getLength() == 1) {
                ref = (Element)nl.item(0);
                String texName = getString(ref);
                //Log.v(TAG, "Extracted texture name " + texName);
                return mImages.get(texName);
            }
        }
        return null;
    }

    void extractParams(Element fx, ArrayList<ShaderParam> params) {
        Node paramNode = fx.getFirstChild();
        while (paramNode != null) {
            if (paramNode.getNodeType() == Node.ELEMENT_NODE) {
                String name = paramNode.getNodeName();
                // Now find what type it is
                Node typeNode = paramNode.getFirstChild();
                while (typeNode != null && typeNode.getNodeType() != Node.ELEMENT_NODE) {
                    typeNode = typeNode.getNextSibling();
                }
                String paramType = typeNode.getNodeName();
                Element typeElem = (Element)typeNode;
                ShaderParam sceneParam = null;
                if (paramType.equals("color")) {
                    Float4Param f4p = new Float4Param(name);
                    Float4 value = getFloat4(typeElem);
                    f4p.setValue(value);
                    sceneParam = f4p;
                    //Log.v(TAG, "Extracted " + sceneParam.getParamName() + " value " + toString(value));
                } else if (paramType.equals("float")) {
                    Float4Param f4p = new Float4Param(name);
                    float value = getFloat(typeElem);
                    f4p.setValue(new Float4(value, value, value, value));
                    sceneParam = f4p;
                    //Log.v(TAG, "Extracted " + sceneParam.getParamName() + " value " + value);
                }  else if (paramType.equals("texture")) {
                    String samplerName = typeElem.getAttribute("texture");
                    Texture2D tex = getTexture(samplerName);
                    TextureParam texP = new TextureParam(name);
                    texP.setTexture(tex);
                    sceneParam = texP;
                    //Log.v(TAG, "Extracted texture " + tex);
                }
                if (sceneParam != null) {
                    params.add(sceneParam);
                }
            }
            paramNode = paramNode.getNextSibling();
        }
    }

    private void convertMaterials(Element mat) {
        String id = mat.getAttribute("id");
        NodeList nl = mat.getElementsByTagName("instance_effect");
        if (nl != null && nl.getLength() == 1) {
            Element ref = (Element)nl.item(0);
            String url = ref.getAttribute("url");
            ArrayList<ShaderParam> params = mEffectsParams.get(url.substring(1));
            mEffectsParams.put(id, params);
        }
    }

    private void convertEffects(Element fx) {
        String id = fx.getAttribute("id");
        ArrayList<ShaderParam> params = new ArrayList<ShaderParam>();

        NodeList nl = fx.getElementsByTagName("newparam");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element field = (Element)nl.item(i);
                field.setIdAttribute("sid", true);
            }
        }

        nl = fx.getElementsByTagName("blinn");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element field = (Element)nl.item(i);
                //Log.v(TAG, "blinn");
                extractParams(field, params);
            }
        }
        nl = fx.getElementsByTagName("lambert");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element field = (Element)nl.item(i);
                //Log.v(TAG, "lambert");
                extractParams(field, params);
            }
        }
        nl = fx.getElementsByTagName("phong");
        if (nl != null) {
            for(int i = 0; i < nl.getLength(); i++) {
                Element field = (Element)nl.item(i);
                //Log.v(TAG, "phong");
                extractParams(field, params);
            }
        }
        mEffectsParams.put(id, params);
    }

    private void convertLight(Element light) {
        String name = light.getAttribute("name");
        String id = light.getAttribute("id");

        // Determine type
        String[] knownTypes = { "point", "spot", "directional" };
        final int POINT_LIGHT = 0;
        final int SPOT_LIGHT = 1;
        final int DIR_LIGHT = 2;
        int type = -1;
        for (int i = 0; i < knownTypes.length; i ++) {
            NodeList nl = light.getElementsByTagName(knownTypes[i]);
            if (nl != null && nl.getLength() != 0) {
                type = i;
                break;
            }
        }

        //Log.v(TAG, "Found Light Type " + type);

        LightBase sceneLight = null;
        switch (type) {
        case POINT_LIGHT:
            sceneLight = new PointLight();
            break;
        case SPOT_LIGHT: // TODO: finish light types
            break;
        case DIR_LIGHT: // TODO: finish light types
            break;
        }

        if (sceneLight == null) {
            return;
        }

        Float3 color = getFloat3(light, "color");
        sceneLight.setColor(color);
        sceneLight.setName(name);
        mScene.appendLight(sceneLight);
        mLights.put(id, sceneLight);

        //Log.v(TAG, "Light " + name + " color " + toString(color));
    }

    private void convertCamera(Element camera) {
        String name = camera.getAttribute("name");
        String id = camera.getAttribute("id");
        float fov = getFloat(camera, "yfov");
        float near = getFloat(camera, "znear");
        float far = getFloat(camera, "zfar");

        Camera sceneCamera = new Camera();
        sceneCamera.setFOV(fov);
        sceneCamera.setNear(near);
        sceneCamera.setFar(far);
        sceneCamera.setName(name);
        mScene.appendCamera(sceneCamera);
        mCameras.put(id, sceneCamera);
    }

    private void convertImage(Element img) {
        String name = img.getAttribute("name");
        String id = img.getAttribute("id");
        String file = getString(img, "init_from");

        Texture2D tex = new Texture2D();
        tex.setFileName(file);
        mScene.appendTextures(tex);
        mImages.put(id, tex);
    }

    private void getScene(Element scene) {
        String name = scene.getAttribute("name");
        String id = scene.getAttribute("id");

        Node childNode = scene.getFirstChild();
        while (childNode != null) {
            if (childNode.getNodeType() == Node.ELEMENT_NODE) {
                String indent = "";
                getNode((Element)childNode, null, indent);
            }
            childNode = childNode.getNextSibling();
        }
    }

    private String getString(Element elem, String name) {
        String text = null;
        NodeList nl = elem.getElementsByTagName(name);
        if (nl != null) {
            text = ((Element)nl.item(0)).getFirstChild().getNodeValue();
        }
        return text;
    }

    private String getString(Element elem) {
        String text = null;
        text = elem.getFirstChild().getNodeValue();
        return text;
    }

    private int getInt(Element elem, String name) {
        return Integer.parseInt(getString(elem, name));
    }

    private float getFloat(Element elem, String name) {
        return Float.parseFloat(getString(elem, name));
    }

    private float getFloat(Element elem) {
        return Float.parseFloat(getString(elem));
    }

    private Float3 parseFloat3(String valueString) {
        StringTokenizer st = new StringTokenizer(valueString);
        float x = Float.parseFloat(st.nextToken());
        float y = Float.parseFloat(st.nextToken());
        float z = Float.parseFloat(st.nextToken());
        return new Float3(x, y, z);
    }

    private Float4 parseFloat4(String valueString) {
        StringTokenizer st = new StringTokenizer(valueString);
        float x = Float.parseFloat(st.nextToken());
        float y = Float.parseFloat(st.nextToken());
        float z = Float.parseFloat(st.nextToken());
        float w = Float.parseFloat(st.nextToken());
        return new Float4(x, y, z, w);
    }

    private Float3 getFloat3(Element elem, String name) {
        String valueString = getString(elem, name);
        return parseFloat3(valueString);
    }

    private Float4 getFloat4(Element elem, String name) {
        String valueString = getString(elem, name);
        return parseFloat4(valueString);
    }

    private Float3 getFloat3(Element elem) {
        String valueString = getString(elem);
        return parseFloat3(valueString);
    }

    private Float4 getFloat4(Element elem) {
        String valueString = getString(elem);
        return parseFloat4(valueString);
    }
}
