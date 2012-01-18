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

import java.lang.Math;
import java.util.ArrayList;

import android.graphics.Camera;
import android.renderscript.RenderScriptGL;
import android.renderscript.Float4;
import android.renderscript.Matrix4f;
import android.renderscript.ProgramFragment;
import android.renderscript.ProgramStore;
import android.renderscript.ProgramVertex;
import android.renderscript.Element;
import android.util.Log;

/**
 * @hide
 */
public class TextureParam extends ShaderParam {

    Texture2D mTexture;

    public TextureParam(String name) {
        super(name);
    }

    public TextureParam(String name, Texture2D t) {
        super(name);
        setTexture(t);
    }

    public void setTexture(Texture2D t) {
        mTexture = t;
    }

    public Texture2D getTexture() {
        return mTexture;
    }

    void initLocalData(RenderScriptGL rs) {
    }
}





