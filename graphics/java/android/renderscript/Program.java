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


import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import android.content.res.Resources;
import android.util.Log;


/**
 *
 * Program is a base class for all the objects that modify
 * various stages of the graphics pipeline
 *
 **/
public class Program extends BaseObj {
    static final int MAX_INPUT = 8;
    static final int MAX_OUTPUT = 8;
    static final int MAX_CONSTANT = 8;
    static final int MAX_TEXTURE = 8;

    /**
     *
     * TextureType specifies what textures are attached to Program
     * objects
     *
     **/
    public enum TextureType {
        TEXTURE_2D (0),
        TEXTURE_CUBE (1);

        int mID;
        TextureType(int id) {
            mID = id;
        }
    }

    enum ProgramParam {
        INPUT (0),
        OUTPUT (1),
        CONSTANT (2),
        TEXTURE_TYPE (3);

        int mID;
        ProgramParam(int id) {
            mID = id;
        }
    };

    Element mInputs[];
    Element mOutputs[];
    Type mConstants[];
    TextureType mTextures[];
    int mTextureCount;
    String mShader;

    Program(int id, RenderScript rs) {
        super(id, rs);
    }

    /**
     * Binds a constant buffer to be used as uniform inputs to the
     * program
     *
     * @param a allocation containing uniform data
     * @param slot index within the program's list of constant
     *             buffer allocations
     */
    public void bindConstants(Allocation a, int slot) {
        if (slot < 0 || slot >= mConstants.length) {
            throw new IllegalArgumentException("Slot ID out of range.");
        }
        if (a != null &&
            a.getType().getID() != mConstants[slot].getID()) {
            throw new IllegalArgumentException("Allocation type does not match slot type.");
        }
        int id = a != null ? a.getID() : 0;
        mRS.nProgramBindConstants(getID(), slot, id);
    }

    /**
     * Binds a texture to be used in the program
     *
     * @param va allocation containing texture data
     * @param slot index within the program's list of textures
     *
     */
    public void bindTexture(Allocation va, int slot)
        throws IllegalArgumentException {
        mRS.validate();
        if ((slot < 0) || (slot >= mTextureCount)) {
            throw new IllegalArgumentException("Slot ID out of range.");
        }
        if (va != null && va.getType().hasFaces() &&
            mTextures[slot] != TextureType.TEXTURE_CUBE) {
            throw new IllegalArgumentException("Cannot bind cubemap to 2d texture slot");
        }

        int id = va != null ? va.getID() : 0;
        mRS.nProgramBindTexture(getID(), slot, id);
    }

    /**
     * Binds an object that describes how a texture at the
     * corresponding location is sampled
     *
     * @param vs sampler for a corresponding texture
     * @param slot index within the program's list of textures to
     *             use the sampler on
     *
     */
    public void bindSampler(Sampler vs, int slot)
        throws IllegalArgumentException {
        mRS.validate();
        if ((slot < 0) || (slot >= mTextureCount)) {
            throw new IllegalArgumentException("Slot ID out of range.");
        }

        int id = vs != null ? vs.getID() : 0;
        mRS.nProgramBindSampler(getID(), slot, id);
    }


    public static class BaseProgramBuilder {
        RenderScript mRS;
        Element mInputs[];
        Element mOutputs[];
        Type mConstants[];
        Type mTextures[];
        TextureType mTextureTypes[];
        int mInputCount;
        int mOutputCount;
        int mConstantCount;
        int mTextureCount;
        String mShader;


        protected BaseProgramBuilder(RenderScript rs) {
            mRS = rs;
            mInputs = new Element[MAX_INPUT];
            mOutputs = new Element[MAX_OUTPUT];
            mConstants = new Type[MAX_CONSTANT];
            mInputCount = 0;
            mOutputCount = 0;
            mConstantCount = 0;
            mTextureCount = 0;
            mTextureTypes = new TextureType[MAX_TEXTURE];
        }

        /**
         * Sets the GLSL shader code to be used in the program
         *
         * @param s GLSL shader string
         * @return  self
         */
        public BaseProgramBuilder setShader(String s) {
            mShader = s;
            return this;
        }

        /**
         * Sets the GLSL shader code to be used in the program
         *
         * @param resources application resources
         * @param resourceID id of the file containing GLSL shader code
         *
         * @return  self
         */
        public BaseProgramBuilder setShader(Resources resources, int resourceID) {
            byte[] str;
            int strLength;
            InputStream is = resources.openRawResource(resourceID);
            try {
                try {
                    str = new byte[1024];
                    strLength = 0;
                    while(true) {
                        int bytesLeft = str.length - strLength;
                        if (bytesLeft == 0) {
                            byte[] buf2 = new byte[str.length * 2];
                            System.arraycopy(str, 0, buf2, 0, str.length);
                            str = buf2;
                            bytesLeft = str.length - strLength;
                        }
                        int bytesRead = is.read(str, strLength, bytesLeft);
                        if (bytesRead <= 0) {
                            break;
                        }
                        strLength += bytesRead;
                    }
                } finally {
                    is.close();
                }
            } catch(IOException e) {
                throw new Resources.NotFoundException();
            }

            try {
                mShader = new String(str, 0, strLength, "UTF-8");
            } catch (UnsupportedEncodingException e) {
                Log.e("Renderscript shader creation", "Could not decode shader string");
            }

            return this;
        }

        /**
         * Queries the index of the last added constant buffer type
         *
         */
        public int getCurrentConstantIndex() {
            return mConstantCount - 1;
        }

        /**
         * Queries the index of the last added texture type
         *
         */
        public int getCurrentTextureIndex() {
            return mTextureCount - 1;
        }

        /**
         * Adds constant (uniform) inputs to the program
         *
         * @param t Type that describes the layout of the Allocation
         *          object to be used as constant inputs to the Program
         * @return  self
         */
        public BaseProgramBuilder addConstant(Type t) throws IllegalStateException {
            // Should check for consistant and non-conflicting names...
            if(mConstantCount >= MAX_CONSTANT) {
                throw new RSIllegalArgumentException("Max input count exceeded.");
            }
            if (t.getElement().isComplex()) {
                throw new RSIllegalArgumentException("Complex elements not allowed.");
            }
            mConstants[mConstantCount] = t;
            mConstantCount++;
            return this;
        }

        /**
         * Adds a texture input to the Program
         *
         * @param texType describes that the texture to append it (2D,
         *                Cubemap, etc.)
         * @return  self
         */
        public BaseProgramBuilder addTexture(TextureType texType) throws IllegalArgumentException {
            if(mTextureCount >= MAX_TEXTURE) {
                throw new IllegalArgumentException("Max texture count exceeded.");
            }
            mTextureTypes[mTextureCount ++] = texType;
            return this;
        }

        protected void initProgram(Program p) {
            p.mInputs = new Element[mInputCount];
            System.arraycopy(mInputs, 0, p.mInputs, 0, mInputCount);
            p.mOutputs = new Element[mOutputCount];
            System.arraycopy(mOutputs, 0, p.mOutputs, 0, mOutputCount);
            p.mConstants = new Type[mConstantCount];
            System.arraycopy(mConstants, 0, p.mConstants, 0, mConstantCount);
            p.mTextureCount = mTextureCount;
            p.mTextures = new TextureType[mTextureCount];
            System.arraycopy(mTextureTypes, 0, p.mTextures, 0, mTextureCount);
        }
    }

}


