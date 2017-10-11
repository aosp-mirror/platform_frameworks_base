/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tools.layoutlib.create;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

class CreateInfoAdapter implements ICreateInfo {
    private static final String[] EMPTY_STRING_ARRAY = new String[0];

    @Override
    public Class<?>[] getInjectedClasses() {
        return new Class<?>[0];
    }

    @Override
    public String[] getDelegateMethods() {
        return EMPTY_STRING_ARRAY;
    }

    @Override
    public String[] getDelegateClassNatives() {
        return EMPTY_STRING_ARRAY;
    }

    @Override
    public String[] getRenamedClasses() {
        return EMPTY_STRING_ARRAY;
    }

    @Override
    public String[] getRefactoredClasses() {
        return EMPTY_STRING_ARRAY;
    }

    @Override
    public String[] getDeleteReturns() {
        return EMPTY_STRING_ARRAY;
    }

    @Override
    public String[] getJavaPkgClasses() {
        return EMPTY_STRING_ARRAY;
    }

    @Override
    public Set<String> getExcludedClasses() {
        return Collections.emptySet();
    }

    @Override
    public String[] getPromotedFields() {
        return EMPTY_STRING_ARRAY;
    }

    @Override
    public String[] getPromotedClasses() {
        return EMPTY_STRING_ARRAY;
    }

    @Override
    public Map<String, InjectMethodRunnable> getInjectedMethodsMap() {
        return Collections.emptyMap();
    }
}
