/*
 * Copyright (C) 2016 The Android Open Source Project
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

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Injects fields in a class.
 * <p>
 * TODO: Generify
 */
public class FieldInjectorAdapter extends ClassVisitor {
    public FieldInjectorAdapter(ClassVisitor cv) {
        super(Opcodes.ASM4, cv);
    }

    @Override
    public void visitEnd() {
        super.visitField(Opcodes.ACC_PUBLIC, "mLayoutlibCallback",
                "Lcom/android/ide/common/rendering/api/LayoutlibCallback;", null, null);
        super.visitField(Opcodes.ACC_PUBLIC, "mContext",
                "Lcom/android/layoutlib/bridge/android/BridgeContext;", null, null);
        super.visitEnd();
    }
}
