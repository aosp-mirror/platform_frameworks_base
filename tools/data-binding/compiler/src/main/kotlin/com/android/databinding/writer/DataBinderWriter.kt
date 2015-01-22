/*
 * Copyright (C) 2015 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding.writer

import com.android.databinding.LayoutBinder

class DataBinderWriter(val pkg: String, val projectPackage: String, val className: String, val layoutBinders : List<LayoutBinder> ) {
    fun write() =
            kcode("") {
                tab("package $pkg;")
                tab("import $projectPackage.R;")
                tab("public class $className implements com.android.databinding.library.DataBinderMapper {") {
                    tab("@Override")
                    tab("public com.android.databinding.library.ViewDataBinder getDataBinder(android.view.View view, int layoutId) {") {
                        tab("switch(layoutId) {") {
                            layoutBinders.forEach {
                                tab("case R.layout.${it.getLayoutname()}:") {
                                    tab("return new ${it.getPackage()}.${it.getClassName()}(view);")
                                }
                            }
                        }
                        tab("}")
                        tab("return null;")
                    }
                    tab("}")

                    tab("public int getId(String key) {") {
                        tab("return android.binding.BR.getId(key);")
                    } tab("}")
                }
                tab("}")
            }.generate()
}