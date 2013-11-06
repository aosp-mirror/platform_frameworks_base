/*
 * Copyright (C) 2013 The Android Open Source Project
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

import java.util.HashMap;

import org.objectweb.asm.ClassVisitor;

public class RefactorClassAdapter extends AbstractClassAdapter {

    private final HashMap<String, String> mRefactorClasses;

    RefactorClassAdapter(ClassVisitor cv, HashMap<String, String> refactorClasses) {
        super(cv);
        mRefactorClasses = refactorClasses;
    }

    @Override
    protected String renameInternalType(String oldClassName) {
        if (oldClassName != null) {
            String newName = mRefactorClasses.get(oldClassName);
            if (newName != null) {
                return newName;
            }
            int pos = oldClassName.indexOf('$');
            if (pos > 0) {
                newName = mRefactorClasses.get(oldClassName.substring(0, pos));
                if (newName != null) {
                    return newName + oldClassName.substring(pos);
                }
            }
        }
        return oldClassName;
    }
}
