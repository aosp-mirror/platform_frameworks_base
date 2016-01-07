/*
 * Copyright (C) 2015 The Android Open Source Project
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

import com.android.tools.layoutlib.create.ICreateInfo.InjectMethodRunnable;

import org.objectweb.asm.ClassVisitor;

/**
 * Injects methods into some classes.
 */
public class InjectMethodsAdapter extends ClassVisitor {

    private final ICreateInfo.InjectMethodRunnable mRunnable;

    public InjectMethodsAdapter(ClassVisitor cv, InjectMethodRunnable runnable) {
        super(Main.ASM_VERSION, cv);
        mRunnable = runnable;
    }

    @Override
    public void visitEnd() {
        mRunnable.generateMethods(this);
        super.visitEnd();
    }
}
