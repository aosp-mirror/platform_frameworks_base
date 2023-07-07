/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.traceinjection;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;
import org.objectweb.asm.commons.Method;

/**
 * Adapter that injects tracing code to methods annotated with the configured annotation.
 *
 * Assuming the configured annotation is {@code @Trace} and the configured methods are
 * {@code Tracing.begin()} and {@code Tracing.end()}, it effectively transforms:
 *
 * <pre>{@code
 * @Trace
 * void method() {
 *     doStuff();
 * }
 * }</pre>
 *
 * into:
 * <pre>{@code
 * @Trace
 * void method() {
 *     Tracing.begin();
 *     try {
 *         doStuff();
 *     } finally {
 *         Tracing.end();
 *     }
 * }
 * }</pre>
 */
public class TraceInjectionMethodAdapter extends AdviceAdapter {
    private final TraceInjectionConfiguration mParams;
    private final Label mStartFinally = newLabel();
    private final boolean mIsConstructor;

    private boolean mShouldTrace;
    private long mTraceId;
    private String mTraceLabel;

    public TraceInjectionMethodAdapter(MethodVisitor methodVisitor, int access,
            String name, String descriptor, TraceInjectionConfiguration params) {
        super(Opcodes.ASM9, methodVisitor, access, name, descriptor);
        mParams = params;
        mIsConstructor = "<init>".equals(name);
    }

    @Override
    public void visitCode() {
        super.visitCode();
        if (mShouldTrace) {
            visitLabel(mStartFinally);
        }
    }

    @Override
    protected void onMethodEnter() {
        if (!mShouldTrace) {
            return;
        }
        Type type = Type.getType(toJavaSpecifier(mParams.startMethodClass));
        Method trace = Method.getMethod("void " + mParams.startMethodName + " (long, String)");
        push(mTraceId);
        push(getTraceLabel());
        invokeStatic(type, trace);
    }

    private String getTraceLabel() {
        return !isEmpty(mTraceLabel) ? mTraceLabel : getName();
    }

    @Override
    protected void onMethodExit(int opCode) {
        // Any ATHROW exits will be caught as part of our exception-handling block, so putting it
        // here would cause us to call the end trace method multiple times.
        if (opCode != ATHROW) {
            onFinally();
        }
    }

    private void onFinally() {
        if (!mShouldTrace) {
            return;
        }
        Type type = Type.getType(toJavaSpecifier(mParams.endMethodClass));
        Method trace = Method.getMethod("void " + mParams.endMethodName + " (long)");
        push(mTraceId);
        invokeStatic(type, trace);
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        final int minStackSize;
        if (mShouldTrace) {
            Label endFinally = newLabel();
            visitLabel(endFinally);
            catchException(mStartFinally, endFinally, null);
            // The stack will always contain exactly one element: the exception we caught
            final Object[] stack = new Object[]{ "java/lang/Throwable"};
            // Because we use EXPAND_FRAMES, the frame type must always be F_NEW.
            visitFrame(F_NEW, /* numLocal= */ 0, /* local= */ null, stack.length, stack);
            onFinally();
            // Rethrow the exception that we caught in the finally block.
            throwException();

            // Make sure we have at least enough stack space to push the trace arguments
            // (long, String)
            minStackSize = Type.LONG_TYPE.getSize() + Type.getType(String.class).getSize();
        } else {
            // We didn't inject anything, so no need for additional stack space.
            minStackSize = 0;
        }

        super.visitMaxs(Math.max(minStackSize, maxStack), maxLocals);
    }

    @Override
    public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
        AnnotationVisitor av = super.visitAnnotation(descriptor, visible);
        if (descriptor.equals(toJavaSpecifier(mParams.annotation))) {
            if (mIsConstructor) {
                // TODO: Support constructor tracing. At the moment, constructors aren't supported
                //  because you can't put an exception handler around a super() call within the
                //  constructor itself.
                throw new IllegalStateException("Cannot trace constructors");
            }
            av = new TracingAnnotationVisitor(av);
        }
        return av;
    }

    /**
     * An AnnotationVisitor that pulls the trace ID and label information from the configured
     * annotation.
     */
    class TracingAnnotationVisitor extends AnnotationVisitor {

        TracingAnnotationVisitor(AnnotationVisitor annotationVisitor) {
            super(Opcodes.ASM9, annotationVisitor);
        }

        @Override
        public void visit(String name, Object value) {
            if ("tag".equals(name)) {
                mTraceId = (long) value;
                // If we have a trace annotation and ID, then we have everything we need to trace
                mShouldTrace = true;
            } else if ("label".equals(name)) {
                mTraceLabel = (String) value;
            }
            super.visit(name, value);
        }
    }

    private static String toJavaSpecifier(String klass) {
        return "L" + klass + ";";
    }

    private static boolean isEmpty(String str) {
        return str == null || "".equals(str);
    }
}
