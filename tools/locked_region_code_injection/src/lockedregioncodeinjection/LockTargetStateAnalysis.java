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

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

/**
 * A simple dataflow analysis to determine if the operands on the stack must be one of target lock
 * class type.
 */
public class LockTargetStateAnalysis extends BasicInterpreter {

    private final List<LockTarget> targetLocks;

    public LockTargetStateAnalysis(List<LockTarget> targetLocks) {
        this.targetLocks = targetLocks;
    }

    @Override
    public BasicValue naryOperation(AbstractInsnNode inst, @SuppressWarnings("rawtypes") List args)
            throws AnalyzerException {
        // We target the return type of any invocation.

        @SuppressWarnings("unchecked")
        BasicValue base = super.naryOperation(inst, args);
        if (!(inst instanceof MethodInsnNode)) {
            return base;
        }

        MethodInsnNode invoke = (MethodInsnNode) inst;
        Type returnType = Type.getReturnType(invoke.desc);
        if (returnType.equals(Type.VOID_TYPE)) {
            return base;
        }

        List<LockTarget> types = new ArrayList<>();

        for (LockTarget target : targetLocks) {
            if (returnType.getDescriptor().equals(target.getTargetDesc())) {
                types.add(target);
            }
        }

        return new LockTargetState(base.getType(), types);
    }

    @Override
    public BasicValue newValue(Type type) {
        BasicValue base = super.newValue(type);
        List<LockTarget> types = new ArrayList<>();

        if (type == null) {
            return base;
        }
        for (LockTarget target : targetLocks) {
            if (type.getDescriptor().equals(target.getTargetDesc())) {
                types.add(target);
            }
        }

        if (types.isEmpty()) {
            return base;
        }

        return new LockTargetState(base.getType(), types);
    }

    @Override
    public BasicValue merge(BasicValue v1, BasicValue v2) {
        BasicValue base = super.merge(v1, v2);

        if (!(v1 instanceof LockTargetState)) {
            return base;
        }
        if (!(v2 instanceof LockTargetState)) {
            return base;
        }

        LockTargetState state1 = (LockTargetState) v1;
        LockTargetState state2 = (LockTargetState) v2;

        List<LockTarget> newList = new ArrayList<>(state1.getTargets());
        for (LockTarget otherTarget : state2.getTargets()) {
            if (!newList.contains(otherTarget)) {
                newList.add(otherTarget);
            }
        }

        return new LockTargetState(base.getType(), newList);
    }
}
