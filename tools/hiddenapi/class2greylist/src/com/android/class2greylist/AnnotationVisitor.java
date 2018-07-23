/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.class2greylist;

import org.apache.bcel.Const;
import org.apache.bcel.classfile.AnnotationEntry;
import org.apache.bcel.classfile.DescendingVisitor;
import org.apache.bcel.classfile.ElementValuePair;
import org.apache.bcel.classfile.EmptyVisitor;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.FieldOrMethod;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import java.util.Locale;

/**
 * Visits a JavaClass instance and pulls out all members annotated with a
 * specific annotation. The signatures of such members are passed to {@link
 * Status#greylistEntry(String)}. Any errors result in a call to {@link
 * Status#error(String)}.
 *
 * If the annotation has a property "expectedSignature" the generated signature
 * will be verified against the one specified there. If it differs, an error
 * will be generated.
 */
public class AnnotationVisitor extends EmptyVisitor {

    private static final String EXPECTED_SIGNATURE = "expectedSignature";

    private final JavaClass mClass;
    private final String mAnnotationType;
    private final Status mStatus;
    private final DescendingVisitor mDescendingVisitor;

    public AnnotationVisitor(JavaClass clazz, String annotation, Status d) {
        mClass = clazz;
        mAnnotationType = annotation;
        mStatus = d;
        mDescendingVisitor = new DescendingVisitor(clazz, this);
    }

    public void visit() {
        mStatus.debug("Visit class %s", mClass.getClassName());
        mDescendingVisitor.visit();
    }

    private static String getClassDescriptor(JavaClass clazz) {
        // JavaClass.getName() returns the Java-style name (with . not /), so we must fetch
        // the original class name from the constant pool.
        return clazz.getConstantPool().getConstantString(
                clazz.getClassNameIndex(), Const.CONSTANT_Class);
    }

    @Override
    public void visitMethod(Method method) {
        visitMember(method, "L%s;->%s%s");
    }

    @Override
    public void visitField(Field field) {
        visitMember(field, "L%s;->%s:%s");
    }

    private void visitMember(FieldOrMethod member, String signatureFormatString) {
        JavaClass definingClass = (JavaClass) mDescendingVisitor.predecessor();
        mStatus.debug("Visit member %s : %s", member.getName(), member.getSignature());
        for (AnnotationEntry a : member.getAnnotationEntries()) {
            if (mAnnotationType.equals(a.getAnnotationType())) {
                mStatus.debug("Method has annotation %s", mAnnotationType);
                String signature = String.format(Locale.US, signatureFormatString,
                        getClassDescriptor(definingClass), member.getName(), member.getSignature());
                for (ElementValuePair property : a.getElementValuePairs()) {
                    switch (property.getNameString()) {
                        case EXPECTED_SIGNATURE:
                            String expected = property.getValue().stringifyValue();
                            if (!signature.equals(expected)) {
                                error(definingClass, member,
                                        "Expected signature does not match generated:\n"
                                                + "Expected:  %s\n"
                                                + "Generated: %s", expected, signature);
                            }
                            break;
                    }
                }
                mStatus.greylistEntry(signature);
            }
        }
    }

    private void error(JavaClass clazz, FieldOrMethod member, String message, Object... args) {
        StringBuilder error = new StringBuilder();
        error.append(clazz.getSourceFileName())
                .append(": ")
                .append(clazz.getClassName())
                .append(".")
                .append(member.getName())
                .append(": ")
                .append(String.format(Locale.US, message, args));

        mStatus.error(error.toString());
    }

}
