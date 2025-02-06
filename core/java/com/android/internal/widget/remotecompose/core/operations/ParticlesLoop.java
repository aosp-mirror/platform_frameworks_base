/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.FLOAT_ARRAY;
import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.Container;
import com.android.internal.widget.remotecompose.core.operations.utilities.AnimatedFloatExpression;
import com.android.internal.widget.remotecompose.core.operations.utilities.NanMap;

import java.util.ArrayList;
import java.util.List;

/**
 * This provides the mechanism to evolve the particles It consist of a restart equation and a list
 * of equations particle restarts if restart equation > 0
 */
public class ParticlesLoop extends PaintOperation implements VariableSupport, Container {
    private static final int OP_CODE = Operations.PARTICLE_LOOP;
    private static final String CLASS_NAME = "ParticlesLoop";
    private final int mId;
    private final float[] mRestart;
    private final float[] mOutRestart;
    private final float[][] mEquations;
    private final float[][] mOutEquations;
    private int[] mVarId;
    private float[][] mParticles;
    private static final int MAX_FLOAT_ARRAY = 2000;
    private static final int MAX_EQU_LENGTH = 32;
    ParticlesCreate mParticlesSource;

    @NonNull
    @Override
    public ArrayList<Operation> getList() {
        return mList;
    }

    @NonNull private ArrayList<Operation> mList = new ArrayList<>();

    @NonNull AnimatedFloatExpression mExp = new AnimatedFloatExpression();

    /**
     * Create a new ParticlesLoop operation
     *
     * @param id of the create
     * @param restart the restart equation kills and restart when positive
     * @param values the loop equations
     */
    public ParticlesLoop(int id, float[] restart, float[][] values) {
        mId = id;
        mRestart = restart;
        if (restart != null) {
            mOutRestart = new float[restart.length];
            System.arraycopy(restart, 0, mOutRestart, 0, restart.length);
        } else {
            mOutRestart = null;
        }

        mEquations = values;
        mOutEquations = new float[values.length][];
        for (int i = 0; i < values.length; i++) {
            mOutEquations[i] = new float[values[i].length];
            System.arraycopy(values[i], 0, mOutEquations[i], 0, values[i].length);
        }
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        if (mOutRestart != null) {
            for (int i = 0; i < mRestart.length; i++) {
                float v = mRestart[i];
                mOutRestart[i] =
                        (Float.isNaN(v)
                                        && !AnimatedFloatExpression.isMathOperator(v)
                                        && !NanMap.isDataVariable(v))
                                ? context.getFloat(Utils.idFromNan(v))
                                : v;
            }
        }
        for (int i = 0; i < mEquations.length; i++) {
            float[] mEquation = mEquations[i];
            for (int j = 0; j < mEquation.length; j++) {
                float v = mEquation[j];
                mOutEquations[i][j] =
                        (Float.isNaN(v)
                                        && !AnimatedFloatExpression.isMathOperator(v)
                                        && !NanMap.isDataVariable(v))
                                ? context.getFloat(Utils.idFromNan(v))
                                : v;
            }
        }
    }

    @Override
    public void registerListening(@NonNull RemoteContext context) {
        mParticlesSource = (ParticlesCreate) context.getObject(mId);
        mParticles = mParticlesSource.getParticles();
        mVarId = mParticlesSource.getVariableIds();
        if (mRestart != null) {
            for (int i = 0; i < mRestart.length; i++) {
                float v = mRestart[i];
                if (Float.isNaN(v)
                        && !AnimatedFloatExpression.isMathOperator(v)
                        && !NanMap.isDataVariable(v)) {
                    context.listensTo(Utils.idFromNan(v), this);
                }
            }
        }
        for (int i = 0; i < mEquations.length; i++) {
            float[] mEquation = mEquations[i];
            for (float v : mEquation) {
                if (Float.isNaN(v)
                        && !AnimatedFloatExpression.isMathOperator(v)
                        && !NanMap.isDataVariable(v)) {
                    context.listensTo(Utils.idFromNan(v), this);
                }
            }
        }
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mId, mRestart, mEquations);
    }

    @NonNull
    @Override
    public String toString() {
        String str = "ParticlesLoop[" + Utils.idString(mId) + "] ";
        return str;
    }

    /**
     * Write the operation on the buffer
     *
     * @param buffer the buffer to write to
     * @param id the id of the particle system
     * @param restart the restart equation
     * @param equations the equations to evolve the particles
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int id,
            @Nullable float[] restart,
            @NonNull float[][] equations) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        if (restart != null) {
            buffer.writeInt(restart.length);
            for (int i = 0; i < restart.length; i++) {
                buffer.writeFloat(restart[i]);
            }
        } else {
            buffer.writeInt(0);
        }
        buffer.writeInt(equations.length);
        for (int i = 0; i < equations.length; i++) {
            buffer.writeInt(equations[i].length);
            for (int j = 0; j < equations[i].length; j++) {
                buffer.writeFloat(equations[i][j]);
            }
        }
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int id = buffer.readInt();
        int restartLen = buffer.readInt();
        float[] restart = null;
        if (restartLen > 0) {
            restart = new float[restartLen];
            for (int i = 0; i < restartLen; i++) {
                restart[i] = buffer.readFloat();
            }
        }

        int varLen = buffer.readInt();
        if (varLen > MAX_FLOAT_ARRAY) {
            throw new RuntimeException(varLen + " map entries more than max = " + MAX_FLOAT_ARRAY);
        }

        float[][] equations = new float[varLen][];
        for (int i = 0; i < varLen; i++) {

            int equLen = buffer.readInt();
            if (equLen > MAX_EQU_LENGTH) {
                throw new RuntimeException(
                        equLen + " map entries more than max = " + MAX_FLOAT_ARRAY);
            }
            equations[i] = new float[equLen];
            for (int j = 0; j < equations[i].length; j++) {
                equations[i][j] = buffer.readFloat();
            }
        }
        ParticlesLoop data = new ParticlesLoop(id, restart, equations);
        operations.add(data);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("This evolves the particles & recycles them")
                .field(DocumentedOperation.INT, "id", "id of particle system")
                .field(
                        INT,
                        "recycleLen",
                        "the number of floats in restart equeation if 0 no restart")
                .field(FLOAT_ARRAY, "values", "recycleLen", "array of floats")
                .field(INT, "varLen", "the number of equations to follow")
                .field(INT, "equLen", "the number of equations to follow")
                .field(FLOAT_ARRAY, "values", "equLen", "floats for the equation");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @Override
    public void paint(@NonNull PaintContext context) {
        RemoteContext remoteContext = context.getContext();
        for (int i = 0; i < mParticles.length; i++) {
            // Save the values to context TODO hand code the update
            for (int j = 0; j < mParticles[i].length; j++) {
                remoteContext.loadFloat(mVarId[j], mParticles[i][j]);
                updateVariables(remoteContext);
            }
            // Evaluate the update function
            for (int j = 0; j < mParticles[i].length; j++) {
                mParticles[i][j] = mExp.eval(mOutEquations[j], mOutEquations[j].length);
                remoteContext.loadFloat(mVarId[j], mParticles[i][j]);
            }
            // test for reset
            if (mOutRestart != null) {
                for (int k = 0; k < mRestart.length; k++) {
                    float v = mRestart[k];
                    mOutRestart[k] =
                            (Float.isNaN(v)
                                            && !AnimatedFloatExpression.isMathOperator(v)
                                            && !NanMap.isDataVariable(v))
                                    ? remoteContext.getFloat(Utils.idFromNan(v))
                                    : v;
                }
                if (mExp.eval(mOutRestart, mOutRestart.length) > 0) {
                    mParticlesSource.initializeParticle(i);
                }
            }

            for (Operation op : mList) {
                if (op instanceof VariableSupport) {
                    ((VariableSupport) op).updateVariables(context.getContext());
                }

                remoteContext.incrementOpCount();
                op.apply(context.getContext());
            }
        }
    }
}
