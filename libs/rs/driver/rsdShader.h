/*
 * Copyright (C) 2011-2012 The Android Open Source Project
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

#ifndef ANDROID_RSD_SHADER_H
#define ANDROID_RSD_SHADER_H

#include <utils/String8.h>

// ---------------------------------------------------------------------------
namespace android {
namespace renderscript {

class Element;
class Context;
class Program;

}
}

class RsdShaderCache;

#define RS_SHADER_ATTR "ATTRIB_"
#define RS_SHADER_UNI "UNI_"

class RsdShader {
public:

    RsdShader(const android::renderscript::Program *p, uint32_t type,
              const char * shaderText, uint32_t shaderLength,
              const char** textureNames, size_t textureNamesCount,
              const size_t *textureNamesLength);
    virtual ~RsdShader();

    uint32_t getStateBasedShaderID(const android::renderscript::Context *);

    // Add ability to get all ID's to clean up the cached program objects
    uint32_t getStateBasedIDCount() const { return mStateBasedShaders.size(); }
    uint32_t getStateBasedID(uint32_t index) const {
        return mStateBasedShaders.itemAt(index)->mShaderID;
    }

    uint32_t getAttribCount() const {return mAttribCount;}
    uint32_t getUniformCount() const {return mUniformCount;}
    const android::String8 & getAttribName(uint32_t i) const {return mAttribNames[i];}
    const android::String8 & getUniformName(uint32_t i) const {return mUniformNames[i];}
    uint32_t getUniformArraySize(uint32_t i) const {return mUniformArraySizes[i];}

    android::String8 getGLSLInputString() const;

    bool isValid() const {return mIsValid;}
    void forceDirty() const {mDirty = true;}

    bool loadShader(const android::renderscript::Context *);
    void setup(const android::renderscript::Context *, RsdShaderCache *sc);

protected:

    class StateBasedKey {
    public:
        StateBasedKey(uint32_t texCount) : mShaderID(0) {
            mTextureTargets = new uint32_t[texCount];
        }
        ~StateBasedKey() {
            delete[] mTextureTargets;
        }
        uint32_t mShaderID;
        uint32_t *mTextureTargets;
    };

    bool createShader();
    StateBasedKey *getExistingState();

    const android::renderscript::Program *mRSProgram;
    bool mIsValid;

    // Applies to vertex and fragment shaders only
    void appendUserConstants();
    void setupUserConstants(const android::renderscript::Context *rsc,
                            RsdShaderCache *sc, bool isFragment);
    void initAddUserElement(const android::renderscript::Element *e,
                            android::String8 *names, uint32_t *arrayLengths,
                            uint32_t *count, const char *prefix);
    void setupTextures(const android::renderscript::Context *rsc, RsdShaderCache *sc);
    void setupSampler(const android::renderscript::Context *rsc,
                      const android::renderscript::Sampler *s,
                      const android::renderscript::Allocation *tex);

    void appendAttributes();
    void appendTextures();

    void initAttribAndUniformArray();

    mutable bool mDirty;
    android::String8 mShader;
    android::String8 mUserShader;
    uint32_t mType;

    uint32_t mTextureCount;
    StateBasedKey *mCurrentState;
    uint32_t mAttribCount;
    uint32_t mUniformCount;
    android::String8 *mAttribNames;
    android::String8 *mUniformNames;
    uint32_t *mUniformArraySizes;

    android::Vector<android::String8> mTextureNames;

    android::Vector<StateBasedKey*> mStateBasedShaders;

    int32_t mTextureUniformIndexStart;

    void logUniform(const android::renderscript::Element *field,
                    const float *fd, uint32_t arraySize);
    void setUniform(const android::renderscript::Context *rsc,
                    const android::renderscript::Element *field,
                    const float *fd, int32_t slot, uint32_t arraySize );
    void initMemberVars();
    void init(const char** textureNames, size_t textureNamesCount,
              const size_t *textureNamesLength);
};

#endif //ANDROID_RSD_SHADER_H




