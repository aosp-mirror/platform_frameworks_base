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

import java.util.Arrays;
import java.util.HashMap;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

public class RefactorClassAdapter extends AbstractClassAdapter {

    private final HashMap<String, String> mRefactorClasses;

    RefactorClassAdapter(ClassVisitor cv, HashMap<String, String> refactorClasses) {
        super(cv);
        mRefactorClasses = refactorClasses;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature,
            String[] exceptions) {
        MethodVisitor mw = super.visitMethod(access, name, desc, signature, exceptions);

        return new RefactorStackMapAdapter(mw);
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

    /**
     * A method visitor that renames all references from an old class name to a new class name in
     * the stackmap of the method.
     */
    private class RefactorStackMapAdapter extends MethodVisitor {

        private RefactorStackMapAdapter(MethodVisitor mv) {
            super(Main.ASM_VERSION, mv);
        }


        private Object[] renameFrame(Object[] elements) {
            if (elements == null) {
                return null;
            }

            // The input array cannot be modified. We only copy the source array on write
            boolean copied = false;
            for (int i = 0; i < elements.length; i++) {
                if (!(elements[i] instanceof String)) {
                    continue;
                }

                if (!copied) {
                    elements = Arrays.copyOf(elements, elements.length);
                    copied = true;
                }

                String type = (String)elements[i];
                if (type.indexOf(';') > 0) {
                    elements[i] = renameTypeDesc(type);
                } else {
                    elements[i] = renameInternalType(type);
                }
            }

            return elements;
        }

        @Override
        public void visitFrame(int type, int nLocal, Object[] local, int nStack, Object[] stack) {
            super.visitFrame(type, nLocal, renameFrame(local), nStack, renameFrame(stack));
        }
    }
}
