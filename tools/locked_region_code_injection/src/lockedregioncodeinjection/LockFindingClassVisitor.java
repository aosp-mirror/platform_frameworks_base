/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package lockedregioncodeinjection;

import static com.google.common.base.Preconditions.checkElementIndex;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.TryCatchBlockSorter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * This visitor operates on two kinds of targets.  For a legacy target, it does the following:
 *
 * 1. Finds all the MONITOR_ENTER / MONITOR_EXIT in the byte code and inserts the corresponding pre
 * and post methods calls should it matches one of the given target type in the Configuration.
 *
 * 2. Find all methods that are synchronized and insert pre method calls in the beginning and post
 * method calls just before all return instructions.
 *
 * For a scoped target, it does the following:
 *
 * 1. Finds all the MONITOR_ENTER instructions in the byte code.  If the target of the opcode is
 *    named in a --scope switch, then the pre method is invoked ON THE TARGET immediately after
 *    MONITOR_ENTER opcode completes.
 *
 * 2. Finds all the MONITOR_EXIT instructions in the byte code.  If the target of the opcode is
 *    named in a --scope switch, then the post method is invoked ON THE TARGET immediately before
 *    MONITOR_EXIT opcode completes.
 */
class LockFindingClassVisitor extends ClassVisitor {
    private String className = null;
    private final List<LockTarget> targets;

    public LockFindingClassVisitor(List<LockTarget> targets, ClassVisitor chain) {
        super(Utils.ASM_VERSION, chain);
        this.targets = targets;
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, String signature,
            String[] exceptions) {
        assert this.className != null;
        MethodNode mn = new TryCatchBlockSorter(null, access, name, desc, signature, exceptions);
        MethodVisitor chain = super.visitMethod(access, name, desc, signature, exceptions);
        return new LockFindingMethodVisitor(this.className, mn, chain);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName,
            String[] interfaces) {
        this.className = name;
        super.visit(version, access, name, signature, superName, interfaces);
    }

    class LockFindingMethodVisitor extends MethodVisitor {
        private String owner;
        private MethodVisitor chain;
        private final String className;
        private final String methodName;

        public LockFindingMethodVisitor(String owner, MethodNode mn, MethodVisitor chain) {
            super(Utils.ASM_VERSION, mn);
            assert owner != null;
            this.owner = owner;
            this.chain = chain;
            className = owner;
            methodName = mn.name;
        }

        @SuppressWarnings("unchecked")
        @Override
        public void visitEnd() {
            MethodNode mn = (MethodNode) mv;

            Analyzer a = new Analyzer(new LockTargetStateAnalysis(targets));

            LockTarget ownerMonitor = null;
            if ((mn.access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                for (LockTarget t : targets) {
                    if (t.getTargetDesc().equals("L" + owner + ";")) {
                        ownerMonitor = t;
                        if (ownerMonitor.getScoped()) {
                            final String emsg = String.format(
                                "scoped targets do not support synchronized methods in %s.%s()",
                                className, methodName);
                            throw new RuntimeException(emsg);
                        }
                    }
                }
            }

            try {
                a.analyze(owner, mn);
            } catch (AnalyzerException e) {
                throw new RuntimeException("Locked region code injection: " + e.getMessage(), e);
            }
            InsnList instructions = mn.instructions;

            Frame[] frames = a.getFrames();
            List<Frame> frameMap = new LinkedList<>();
            frameMap.addAll(Arrays.asList(frames));

            List<List<TryCatchBlockNode>> handlersMap = new LinkedList<>();

            for (int i = 0; i < instructions.size(); i++) {
                handlersMap.add(a.getHandlers(i));
            }

            if (ownerMonitor != null) {
                AbstractInsnNode s = instructions.getFirst();
                MethodInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC,
                        ownerMonitor.getPreOwner(), ownerMonitor.getPreMethod(), "()V", false);
                insertMethodCallBeforeSync(mn, frameMap, handlersMap, s, 0, call);
            }

            boolean anyDup = false;

            for (int i = 0; i < instructions.size(); i++) {
                AbstractInsnNode s = instructions.get(i);

                if (s.getOpcode() == Opcodes.MONITORENTER) {
                    Frame f = frameMap.get(i);
                    BasicValue operand = (BasicValue) f.getStack(f.getStackSize() - 1);
                    if (operand instanceof LockTargetState) {
                        LockTargetState state = (LockTargetState) operand;
                        for (int j = 0; j < state.getTargets().size(); j++) {
                            LockTarget target = state.getTargets().get(j);
                            MethodInsnNode call = methodCall(target, true);
                            if (target.getScoped()) {
                                TypeInsnNode cast = typeCast(target);
                                i += insertInvokeAcquire(mn, frameMap, handlersMap, s, i,
                                        call, cast);
                                anyDup = true;
                            } else {
                                i += insertMethodCallBefore(mn, frameMap, handlersMap, s, i, call);
                            }
                        }
                    }
                }

                if (s.getOpcode() == Opcodes.MONITOREXIT) {
                    Frame f = frameMap.get(i);
                    BasicValue operand = (BasicValue) f.getStack(f.getStackSize() - 1);
                    if (operand instanceof LockTargetState) {
                        LockTargetState state = (LockTargetState) operand;
                        for (int j = 0; j < state.getTargets().size(); j++) {
                            // The instruction after a monitor_exit should be a label for
                            // the end of the implicit catch block that surrounds the
                            // synchronized block to call monitor_exit when an exception
                            // occurs.
                            checkState(instructions.get(i + 1).getType() == AbstractInsnNode.LABEL,
                                "Expected to find label after monitor exit");

                            int labelIndex = i + 1;
                            checkElementIndex(labelIndex, instructions.size());

                            LabelNode label = (LabelNode)instructions.get(labelIndex);

                            checkNotNull(handlersMap.get(i));
                            checkElementIndex(0, handlersMap.get(i).size());
                            checkState(handlersMap.get(i).get(0).end == label,
                                "Expected label to be the end of monitor exit's try block");

                            LockTarget target = state.getTargets().get(j);
                            MethodInsnNode call = methodCall(target, false);
                            if (target.getScoped()) {
                                TypeInsnNode cast = typeCast(target);
                                i += insertInvokeRelease(mn, frameMap, handlersMap, s, i,
                                        call, cast);
                                anyDup = true;
                            } else {
                                insertMethodCallAfter(mn, frameMap, handlersMap, label,
                                        labelIndex, call);
                            }
                        }
                    }
                }

                if (ownerMonitor != null && (s.getOpcode() == Opcodes.RETURN
                        || s.getOpcode() == Opcodes.ARETURN || s.getOpcode() == Opcodes.DRETURN
                        || s.getOpcode() == Opcodes.FRETURN || s.getOpcode() == Opcodes.IRETURN)) {
                    MethodInsnNode call =
                            new MethodInsnNode(Opcodes.INVOKESTATIC, ownerMonitor.getPostOwner(),
                                    ownerMonitor.getPostMethod(), "()V", false);
                    insertMethodCallBeforeSync(mn, frameMap, handlersMap, s, i, call);
                    i++; // Skip ahead. Otherwise, we will revisit this instruction again.
                }
            }

            if (anyDup) {
                mn.maxStack++;
            }

            super.visitEnd();
            mn.accept(chain);
        }

        // Insert a call to a monitor pre handler.  The node and the index identify the
        // monitorenter call itself.  Insert DUP immediately prior to the MONITORENTER.
        // Insert the typecast and call (in that order) after the MONITORENTER.
        public int insertInvokeAcquire(MethodNode mn, List<Frame> frameMap,
                List<List<TryCatchBlockNode>> handlersMap, AbstractInsnNode node, int index,
                MethodInsnNode call, TypeInsnNode cast) {
            InsnList instructions = mn.instructions;

            // Insert a DUP right before MONITORENTER, to capture the object being locked.
            // Note that the object will be typed as java.lang.Object.
            instructions.insertBefore(node, new InsnNode(Opcodes.DUP));
            frameMap.add(index, frameMap.get(index));
            handlersMap.add(index, handlersMap.get(index));

            // Insert the call right after the MONITORENTER.  These entries are pushed after
            // MONITORENTER so they are inserted in reverse order.  MONITORENTER should be
            // the target of a try/catch block, which means it must be immediately
            // followed by a label (which is part of the try/catch block definition).
            // Move forward past the label so the invocation in inside the proper block.
            // Throw an error if the next instruction is not a label.
            node = node.getNext();
            if (!(node instanceof LabelNode)) {
                throw new RuntimeException(String.format("invalid bytecode sequence in %s.%s()",
                                className, methodName));
            }
            node = node.getNext();
            index = instructions.indexOf(node);

            instructions.insertBefore(node, cast);
            frameMap.add(index, frameMap.get(index));
            handlersMap.add(index, handlersMap.get(index));

            instructions.insertBefore(node, call);
            frameMap.add(index, frameMap.get(index));
            handlersMap.add(index, handlersMap.get(index));

            return 3;
        }

        // Insert instructions completely before the current opcode.  This is slightly
        // different from insertMethodCallBefore(), which inserts the call before MONITOREXIT
        // but inserts the start and end labels after MONITOREXIT.
        public int insertInvokeRelease(MethodNode mn, List<Frame> frameMap,
                List<List<TryCatchBlockNode>> handlersMap, AbstractInsnNode node, int index,
                MethodInsnNode call, TypeInsnNode cast) {
            InsnList instructions = mn.instructions;

            instructions.insertBefore(node, new InsnNode(Opcodes.DUP));
            frameMap.add(index, frameMap.get(index));
            handlersMap.add(index, handlersMap.get(index));

            instructions.insertBefore(node, cast);
            frameMap.add(index, frameMap.get(index));
            handlersMap.add(index, handlersMap.get(index));

            instructions.insertBefore(node, call);
            frameMap.add(index, frameMap.get(index));
            handlersMap.add(index, handlersMap.get(index));

            return 3;
        }
    }

    public static MethodInsnNode methodCall(LockTarget target, boolean pre) {
        String spec = "()V";
        if (!target.getScoped()) {
            if (pre) {
                return new MethodInsnNode(
                    Opcodes.INVOKESTATIC, target.getPreOwner(), target.getPreMethod(), spec);
            } else {
                return new MethodInsnNode(
                    Opcodes.INVOKESTATIC, target.getPostOwner(), target.getPostMethod(), spec);
            }
        } else {
            if (pre) {
                return new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, target.getPreOwner(), target.getPreMethod(), spec);
            } else {
                return new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, target.getPostOwner(), target.getPostMethod(), spec);
            }
        }
    }

    public static TypeInsnNode typeCast(LockTarget target) {
        if (!target.getScoped()) {
            return null;
        } else {
            // preOwner and postOwner return the same string for scoped targets.
            return new TypeInsnNode(Opcodes.CHECKCAST, target.getPreOwner());
        }
    }

    /**
     * Insert a method call before the beginning or end of a synchronized method.
     */
    public static void insertMethodCallBeforeSync(MethodNode mn, List<Frame> frameMap,
            List<List<TryCatchBlockNode>> handlersMap, AbstractInsnNode node, int index,
            MethodInsnNode call) {
        List<TryCatchBlockNode> handlers = handlersMap.get(index);
        InsnList instructions = mn.instructions;
        LabelNode end = new LabelNode();
        instructions.insert(node, end);
        frameMap.add(index, null);
        handlersMap.add(index, null);
        instructions.insertBefore(node, call);
        frameMap.add(index, null);
        handlersMap.add(index, null);

        LabelNode start = new LabelNode();
        instructions.insert(node, start);
        frameMap.add(index, null);
        handlersMap.add(index, null);
        updateCatchHandler(mn, handlers, start, end, handlersMap);
    }

    public static void insertMethodCallAfter(MethodNode mn, List<Frame> frameMap,
            List<List<TryCatchBlockNode>> handlersMap, AbstractInsnNode node, int index,
            MethodInsnNode call) {
        List<TryCatchBlockNode> handlers = handlersMap.get(index + 1);
        InsnList instructions = mn.instructions;

        LabelNode end = new LabelNode();
        instructions.insert(node, end);
        frameMap.add(index + 1, null);
        handlersMap.add(index + 1, null);

        instructions.insert(node, call);
        frameMap.add(index + 1, null);
        handlersMap.add(index + 1, null);

        LabelNode start = new LabelNode();
        instructions.insert(node, start);
        frameMap.add(index + 1, null);
        handlersMap.add(index + 1, null);

        updateCatchHandler(mn, handlers, start, end, handlersMap);
    }

    // Insert instructions completely before the current opcode.  This is slightly different from
    // insertMethodCallBeforeSync(), which inserts the call before MONITOREXIT but inserts the
    // start and end labels after MONITOREXIT.
    public int insertMethodCallBefore(MethodNode mn, List<Frame> frameMap,
            List<List<TryCatchBlockNode>> handlersMap, AbstractInsnNode node, int index,
            MethodInsnNode call) {
        InsnList instructions = mn.instructions;

        instructions.insertBefore(node, call);
        frameMap.add(index, frameMap.get(index));
        handlersMap.add(index, handlersMap.get(index));

        return 1;
    }


    @SuppressWarnings("unchecked")
    public static void updateCatchHandler(MethodNode mn, List<TryCatchBlockNode> handlers,
            LabelNode start, LabelNode end, List<List<TryCatchBlockNode>> handlersMap) {
        if (handlers == null || handlers.size() == 0) {
            return;
        }

        InsnList instructions = mn.instructions;
        List<TryCatchBlockNode> newNodes = new ArrayList<>(handlers.size());
        for (TryCatchBlockNode handler : handlers) {
            if (!(instructions.indexOf(handler.start) <= instructions.indexOf(start)
                    && instructions.indexOf(end) <= instructions.indexOf(handler.end))) {
                TryCatchBlockNode newNode =
                        new TryCatchBlockNode(start, end, handler.handler, handler.type);
                newNodes.add(newNode);
                for (int i = instructions.indexOf(start); i <= instructions.indexOf(end); i++) {
                    if (handlersMap.get(i) == null) {
                        handlersMap.set(i, new ArrayList<>());
                    }
                    handlersMap.get(i).add(newNode);
                }
            } else {
                for (int i = instructions.indexOf(start); i <= instructions.indexOf(end); i++) {
                    if (handlersMap.get(i) == null) {
                        handlersMap.set(i, new ArrayList<>());
                    }
                    handlersMap.get(i).add(handler);
                }
            }
        }
        mn.tryCatchBlocks.addAll(0, newNodes);
    }
}
