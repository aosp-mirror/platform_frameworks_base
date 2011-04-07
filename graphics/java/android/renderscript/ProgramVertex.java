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

 /**
 * <p>The Renderscript vertex program, also known as a vertex shader, describes a stage in
 * the graphics pipeline responsible for manipulating geometric data in a user-defined way.
 * The object is constructed by providing the Renderscript system with the following data:</p>
 * <ul>
 *   <li>Element describing its varying inputs or attributes</li>
 *   <li>GLSL shader string that defines the body of the program</li>
 *   <li>a Type that describes the layout of an Allocation containing constant or uniform inputs</li>
 * </ul>
 *
 * <p>Once the program is created, you bind it to the graphics context, RenderScriptGL, and it will be used for
 * all subsequent draw calls until you bind a new program. If the program has constant inputs,
 * the user needs to bind an allocation containing those inputs. The allocation's type must match
 * the one provided during creation. The Renderscript library then does all the necessary plumbing
 * to send those constants to the graphics hardware. Varying inputs to the shader, such as position, normal,
 * and texture coordinates are matched by name between the input Element and the Mesh object being drawn.
 * The signatures don't have to be exact or in any strict order. As long as the input name in the shader
 * matches a channel name and size available on the mesh, the runtime takes care of connecting the
 * two. Unlike OpenGL, there is no need to link the vertex and fragment programs.</p>
 *
 **/
package android.renderscript;


import android.graphics.Matrix;
import android.util.Log;


/**
 * ProgramVertex, also know as a vertex shader, describes a
 * stage in the graphics pipeline responsible for manipulating
 * geometric data in a user-defined way.
 *
 **/
public class ProgramVertex extends Program {

    ProgramVertex(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
    * Builder class for creating ProgramVertex objects.
    * The builder starts empty and the user must minimally provide
    * the GLSL shader code, and the varying inputs. Constant, or
    * uniform parameters to the shader may optionally be provided as
    * well.
    *
    **/
    public static class Builder extends BaseProgramBuilder {
        /**
         * Create a builder object.
         *
         * @param rs Context to which the program will belong.
         */
        public Builder(RenderScript rs) {
            super(rs);
        }

        /**
         * Add varying inputs to the program
         *
         * @param e element describing the layout of the varying input
         *          structure
         * @return  self
         */
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

        /**
         * Creates ProgramVertex from the current state of the builder
         *
         * @return  ProgramVertex
         */
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
