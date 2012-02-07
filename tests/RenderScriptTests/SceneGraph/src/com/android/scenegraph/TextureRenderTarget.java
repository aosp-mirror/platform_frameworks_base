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

package com.android.scenegraph;

import java.lang.Math;

import com.android.scenegraph.SceneManager;

import android.content.res.Resources;
import android.renderscript.*;
import android.util.Log;

/**
 * @hide
 */
public class TextureRenderTarget extends TextureBase {
    public TextureRenderTarget() {
        super(ScriptC_export.const_TextureType_TEXTURE_RENDER_TARGET);
    }

    public TextureRenderTarget(Allocation tex) {
        super(ScriptC_export.const_TextureType_TEXTURE_RENDER_TARGET);
        setTexture(tex);
    }

    public void setTexture(Allocation tex) {
        mData.texture = tex;
        if (mField != null) {
            mField.set_texture(0, mData.texture, true);
        }
    }

    void load() {
    }

    ScriptField_Texture_s getRsData(boolean loadNow) {
        if (mField != null) {
            return mField;
        }

        RenderScriptGL rs = SceneManager.getRS();
        if (rs == null) {
            return null;
        }

        mField = new ScriptField_Texture_s(rs, 1);
        mField.set(mData, 0, true);
        return mField;
    }
}





