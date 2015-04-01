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

package android.databinding;

import android.view.View;

/**
 * This class will be stripped from the jar and then replaced by the annotation processor
 * as part of the code generation step. This class's existence is just to ensure that
 * compile works and no reflection is needed to access the generated class.
 */
public class DataBinderMapper {
    public ViewDataBinding getDataBinder(View view, int layoutId) {
        return null;
    }
    public int getId(String key) {
        return 0;
    }
}
