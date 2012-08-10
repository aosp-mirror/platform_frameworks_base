/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.renderscript;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map.Entry;
import java.util.HashMap;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * @hide
 **/
public class ScriptIntrinsicYuvToRGB extends ScriptIntrinsic {
    ScriptIntrinsicYuvToRGB(int id, RenderScript rs) {
        super(id, rs);
    }



    public static class Builder {
        RenderScript mRS;

        public Builder(RenderScript rs) {
            mRS = rs;
        }

        public void setInputFormat(int inputFormat) {

        }

        public void setOutputFormat(Element e) {

        }

        public ScriptIntrinsicYuvToRGB create() {
            return null;

        }

    }

}
