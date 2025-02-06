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
import static com.android.internal.widget.remotecompose.core.operations.utilities.AnimatedFloatExpression.VAR1;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.VariableSupport;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.AnimatedFloatExpression;
import com.android.internal.widget.remotecompose.core.operations.utilities.NanMap;

import java.util.Arrays;
import java.util.List;

/**
 * This creates a particle system. which consist of id, particleCount, array of id's and equations
 * for constructing the particles
 */
public class ParticlesCreate extends Operation implements VariableSupport {
    private static final int OP_CODE = Operations.PARTICLE_DEFINE;
    private static final String CLASS_NAME = "ParticlesCreate";
    private final int mId;
    private final float[][] mEquations;
    private final float[][] mOutEquations;
    private final float[][] mParticles;
    private final int[] mIndexeVars; // the elements in mEquations that INDEXES
    private final int mParticleCount;
    private final int[] mVarId;
    private static final int MAX_FLOAT_ARRAY = 2000;
    private static final int MAX_EQU_LENGTH = 32;
    @NonNull AnimatedFloatExpression mExp = new AnimatedFloatExpression();

    public ParticlesCreate(int id, int[] varId, float[][] values, int particleCount) {
        mId = id;
        mVarId = varId;
        mEquations = values;
        mParticleCount = particleCount;
        mOutEquations = new float[values.length][];
        for (int i = 0; i < values.length; i++) {
            mOutEquations[i] = new float[values[i].length];
            System.arraycopy(values[i], 0, mOutEquations[i], 0, values[i].length);
        }
        mParticles = new float[particleCount][varId.length];

        int[] index = new int[20];
        int indexes = 0;
        int var1Int = Float.floatToRawIntBits(VAR1);
        for (int j = 0; j < mEquations.length; j++) {
            for (int k = 0; k < mEquations[j].length; k++) {
                if (Float.isNaN(mEquations[j][k])
                        && Float.floatToRawIntBits(mEquations[j][k]) == var1Int) {
                    index[indexes++] = j * mEquations.length + k;
                }
            }
        }
        mIndexeVars = Arrays.copyOf(index, indexes);
    }

    @Override
    public void updateVariables(@NonNull RemoteContext context) {
        for (int i = 0; i < mEquations.length; i++) {

            for (int j = 0; j < mEquations[i].length; j++) {
                float v = mEquations[i][j];
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
        context.putObject(mId, this); // T
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
        apply(buffer, mId, mVarId, mEquations, mParticleCount);
    }

    @NonNull
    @Override
    public String toString() {
        String str = "ParticlesCreate[" + Utils.idString(mId) + "] ";
        for (int j = 0; j < mVarId.length; j++) {
            str += "[" + mVarId[j] + "] ";
            float[] equation = mEquations[j];
            String[] labels = new String[equation.length];
            for (int i = 0; i < equation.length; i++) {
                if (Float.isNaN(equation[i])) {
                    labels[i] = "[" + Utils.idStringFromNan(equation[i]) + "]";
                }
            }
            str += AnimatedFloatExpression.toString(equation, labels) + "\n";
        }

        return str;
    }

    /**
     * Write the operation on the buffer
     *
     * @param buffer
     * @param id
     * @param varId
     * @param equations
     * @param particleCount
     */
    public static void apply(
            @NonNull WireBuffer buffer,
            int id,
            @NonNull int[] varId,
            @NonNull float[][] equations,
            int particleCount) {
        buffer.start(OP_CODE);
        buffer.writeInt(id);
        buffer.writeInt(particleCount);
        buffer.writeInt(varId.length);
        for (int i = 0; i < varId.length; i++) {
            buffer.writeInt(varId[i]);
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
        int particleCount = buffer.readInt();
        int varLen = buffer.readInt();
        if (varLen > MAX_FLOAT_ARRAY) {
            throw new RuntimeException(varLen + " map entries more than max = " + MAX_FLOAT_ARRAY);
        }
        int[] varId = new int[varLen];
        float[][] equations = new float[varLen][];
        for (int i = 0; i < varId.length; i++) {
            varId[i] = buffer.readInt();
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
        ParticlesCreate data = new ParticlesCreate(id, varId, equations, particleCount);
        operations.add(data);
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Creates a particle system")
                .field(DocumentedOperation.INT, "id", "The reference of the particle system")
                .field(INT, "particleCount", "number of particles to create")
                .field(INT, "varLen", "number of variables asociate with the particles")
                .field(FLOAT_ARRAY, "id", "varLen", "id followed by equations")
                .field(INT, "equLen", "length of the equation")
                .field(FLOAT_ARRAY, "equation", "varLen * equLen", "float array equations");
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    void initializeParticle(int pNo) {
        for (int j = 0; j < mParticles[pNo].length; j++) {
            for (int k = 0; k < mIndexeVars.length; k++) {
                int pos = mIndexeVars[k];
                int jIndex = pos / mOutEquations.length;
                int kIndex = pos % mOutEquations.length;
                mOutEquations[jIndex][kIndex] = pNo;
            }
            mParticles[pNo][j] = mExp.eval(mOutEquations[j], mOutEquations[j].length);
        }
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        for (int i = 0; i < mParticles.length; i++) {
            initializeParticle(i);
        }
    }

    public float[][] getParticles() {
        return mParticles;
    }

    public int[] getVariableIds() {
        return mVarId;
    }

    public float[][] getEquations() {
        return mOutEquations;
    }
}
