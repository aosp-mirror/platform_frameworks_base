/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.databinding.renderer

class DataBinderRenderer(val pkg: String, val projectPackage: String, val className: String, val renderers : List<ViewExprBinderRenderer> ) {
    fun render(br : BrRenderer) =
"""
package $pkg;
import $projectPackage.R;
import ${br.pkg}.${br.className};
public class $className implements com.android.databinding.library.DataBinderMapper {
    @Override
    public com.android.databinding.library.ViewDataBinder getDataBinder(android.view.View view, int layoutId) {
        switch(layoutId) {${renderers.map {"""
            case R.layout.${it.layoutName}:
                return new ${it.pkg}.${it.className}(view);"""
}.joinToString("\n            ")}
        }
        return null;
    }

    @Override
    public int getId(String key) {
        switch(key) {
            ${br.keyToInt.map({ "case \"${it.key}\": return  ${br.className}.${it.key};"}).joinToString("\n            ")}
        }
        return -1;
    }
}
"""
}
