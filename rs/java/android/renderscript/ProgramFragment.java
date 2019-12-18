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

import android.compat.annotation.UnsupportedAppUsage;


/**
 * @hide
 * @deprecated in API 16
 * <p>The RenderScript fragment program, also known as fragment shader is responsible
 * for manipulating pixel data in a user defined way. It's constructed from a GLSL
 * shader string containing the program body, textures inputs, and a Type object
 * that describes the constants used by the program. Similar to the vertex programs,
 * when an allocation with constant input values is bound to the shader, its values
 * are sent to the graphics program automatically.</p>
 * <p> The values inside the allocation are not explicitly tracked. If they change between two draw
 * calls using the same program object, the runtime needs to be notified of that
 * change by calling rsgAllocationSyncAll so it could send the new values to hardware.
 * Communication between the vertex and fragment programs is handled internally in the
 * GLSL code. For example, if the fragment program is expecting a varying input called
 * varTex0, the GLSL code inside the program vertex must provide it.
 * </p>
 *
 **/
public class ProgramFragment extends Program {
    ProgramFragment(long id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * @deprecated in API 16
     */
    public static class Builder extends BaseProgramBuilder {
        /**
         * @deprecated in API 16
         * Create a builder object.
         *
         * @param rs Context to which the program will belong.
         */
        @UnsupportedAppUsage
        public Builder(RenderScript rs) {
            super(rs);
        }

        /**
         * @deprecated in API 16
         * Creates ProgramFragment from the current state of the builder
         *
         * @return  ProgramFragment
         */
        @UnsupportedAppUsage
        public ProgramFragment create() {
            mRS.validate();
            long[] tmp = new long[(mInputCount + mOutputCount + mConstantCount + mTextureCount) * 2];
            String[] texNames = new String[mTextureCount];
            int idx = 0;

            for (int i=0; i < mInputCount; i++) {
                tmp[idx++] = ProgramParam.INPUT.mID;
                tmp[idx++] = mInputs[i].getID(mRS);
            }
            for (int i=0; i < mOutputCount; i++) {
                tmp[idx++] = ProgramParam.OUTPUT.mID;
                tmp[idx++] = mOutputs[i].getID(mRS);
            }
            for (int i=0; i < mConstantCount; i++) {
                tmp[idx++] = ProgramParam.CONSTANT.mID;
                tmp[idx++] = mConstants[i].getID(mRS);
            }
            for (int i=0; i < mTextureCount; i++) {
                tmp[idx++] = ProgramParam.TEXTURE_TYPE.mID;
                tmp[idx++] = mTextureTypes[i].mID;
                texNames[i] = mTextureNames[i];
            }

            long id = mRS.nProgramFragmentCreate(mShader, texNames, tmp);
            ProgramFragment pf = new ProgramFragment(id, mRS);
            initProgram(pf);
            return pf;
        }
    }
}



