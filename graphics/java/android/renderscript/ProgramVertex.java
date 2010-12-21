/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.renderscript;


import android.graphics.Matrix;
import android.util.Config;
import android.util.Log;


/**
 * @hide
 *
 **/
public class ProgramVertex extends Program {

    ProgramVertex(int id, RenderScript rs) {
        super(id, rs);
    }

    public static class Builder extends BaseProgramBuilder {
        public Builder(RenderScript rs) {
            super(rs);
        }

        public Builder addInput(Element e) throws IllegalStateException {
            // Should check for consistant and non-conflicting names...
            if(mInputCount >= MAX_INPUT) {
                throw new RSIllegalArgumentException("Max input count exceeded.");
            }
            if (e.isComplex()) {
                throw new RSIllegalArgumentException("Complex elements not allowed.");
            }
            mInputs[mInputCount++] = e;
            return this;
        }

        public ProgramVertex create() {
            mRS.validate();
            int[] tmp = new int[(mInputCount + mOutputCount + mConstantCount + mTextureCount) * 2];
            int idx = 0;

            for (int i=0; i < mInputCount; i++) {
                tmp[idx++] = ProgramParam.INPUT.mID;
                tmp[idx++] = mInputs[i].getID();
            }
            for (int i=0; i < mOutputCount; i++) {
                tmp[idx++] = ProgramParam.OUTPUT.mID;
                tmp[idx++] = mOutputs[i].getID();
            }
            for (int i=0; i < mConstantCount; i++) {
                tmp[idx++] = ProgramParam.CONSTANT.mID;
                tmp[idx++] = mConstants[i].getID();
            }
            for (int i=0; i < mTextureCount; i++) {
                tmp[idx++] = ProgramParam.TEXTURE_TYPE.mID;
                tmp[idx++] = mTextureTypes[i].mID;
            }

            int id = mRS.nProgramVertexCreate(mShader, tmp);
            ProgramVertex pv = new ProgramVertex(id, mRS);
            initProgram(pv);
            return pv;
        }
    }

}
