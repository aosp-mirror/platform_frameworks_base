/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef RENDER_SCRIPT_H
#define RENDER_SCRIPT_H

#include <stdint.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

//////////////////////////////////////////////////////
//

typedef void * RsAdapter1D;
typedef void * RsAdapter2D;
typedef void * RsAllocation;
typedef void * RsAnimation;
typedef void * RsContext;
typedef void * RsDevice;
typedef void * RsElement;
typedef void * RsFile;
typedef void * RsSampler;
typedef void * RsScript;
typedef void * RsSimpleMesh;
typedef void * RsType;
typedef void * RsLight;
typedef void * RsObjectBase;

typedef void * RsProgram;
typedef void * RsProgramVertex;
typedef void * RsProgramFragment;
typedef void * RsProgramStore;
typedef void * RsProgramRaster;

typedef void (* RsBitmapCallback_t)(void *);

enum RsDeviceParam {
    RS_DEVICE_PARAM_FORCE_SOFTWARE_GL,
    RS_DEVICE_PARAM_COUNT
};

RsDevice rsDeviceCreate();
void rsDeviceDestroy(RsDevice);
void rsDeviceSetConfig(RsDevice, RsDeviceParam, int32_t value);

RsContext rsContextCreate(RsDevice, uint32_t version);
RsContext rsContextCreateGL(RsDevice, uint32_t version, bool useDepth);
void rsContextDestroy(RsContext);
void rsObjDestroyOOB(RsContext, void *);

uint32_t rsContextGetMessage(RsContext, void *data, size_t *receiveLen, size_t bufferLen, bool wait);
void rsContextInitToClient(RsContext);
void rsContextDeinitToClient(RsContext);

#define RS_MAX_TEXTURE 2
#define RS_MAX_ATTRIBS 16

enum RsDataType {
    RS_TYPE_NONE,
    RS_TYPE_FLOAT_16,
    RS_TYPE_FLOAT_32,
    RS_TYPE_FLOAT_64,
    RS_TYPE_SIGNED_8,
    RS_TYPE_SIGNED_16,
    RS_TYPE_SIGNED_32,
    RS_TYPE_SIGNED_64,
    RS_TYPE_UNSIGNED_8,
    RS_TYPE_UNSIGNED_16,
    RS_TYPE_UNSIGNED_32,
    RS_TYPE_UNSIGNED_64,

    RS_TYPE_BOOLEAN,

    RS_TYPE_UNSIGNED_5_6_5,
    RS_TYPE_UNSIGNED_5_5_5_1,
    RS_TYPE_UNSIGNED_4_4_4_4,

    RS_TYPE_ELEMENT,
    RS_TYPE_TYPE,
    RS_TYPE_ALLOCATION,
    RS_TYPE_SAMPLER,
    RS_TYPE_SCRIPT,
    RS_TYPE_MESH,
    RS_TYPE_PROGRAM_FRAGMENT,
    RS_TYPE_PROGRAM_VERTEX,
    RS_TYPE_PROGRAM_RASTER,
    RS_TYPE_PROGRAM_STORE
};

enum RsDataKind {
    RS_KIND_USER,

    RS_KIND_PIXEL_L = 7,
    RS_KIND_PIXEL_A,
    RS_KIND_PIXEL_LA,
    RS_KIND_PIXEL_RGB,
    RS_KIND_PIXEL_RGBA,
};

enum RsSamplerParam {
    RS_SAMPLER_MIN_FILTER,
    RS_SAMPLER_MAG_FILTER,
    RS_SAMPLER_WRAP_S,
    RS_SAMPLER_WRAP_T,
    RS_SAMPLER_WRAP_R
};

enum RsSamplerValue {
    RS_SAMPLER_NEAREST,
    RS_SAMPLER_LINEAR,
    RS_SAMPLER_LINEAR_MIP_LINEAR,
    RS_SAMPLER_WRAP,
    RS_SAMPLER_CLAMP
};

enum RsDimension {
    RS_DIMENSION_X,
    RS_DIMENSION_Y,
    RS_DIMENSION_Z,
    RS_DIMENSION_LOD,
    RS_DIMENSION_FACE,

    RS_DIMENSION_ARRAY_0 = 100,
    RS_DIMENSION_ARRAY_1,
    RS_DIMENSION_ARRAY_2,
    RS_DIMENSION_ARRAY_3,
    RS_DIMENSION_MAX = RS_DIMENSION_ARRAY_3
};

enum RsDepthFunc {
    RS_DEPTH_FUNC_ALWAYS,
    RS_DEPTH_FUNC_LESS,
    RS_DEPTH_FUNC_LEQUAL,
    RS_DEPTH_FUNC_GREATER,
    RS_DEPTH_FUNC_GEQUAL,
    RS_DEPTH_FUNC_EQUAL,
    RS_DEPTH_FUNC_NOTEQUAL
};

enum RsBlendSrcFunc {
    RS_BLEND_SRC_ZERO,                  // 0
    RS_BLEND_SRC_ONE,                   // 1
    RS_BLEND_SRC_DST_COLOR,             // 2
    RS_BLEND_SRC_ONE_MINUS_DST_COLOR,   // 3
    RS_BLEND_SRC_SRC_ALPHA,             // 4
    RS_BLEND_SRC_ONE_MINUS_SRC_ALPHA,   // 5
    RS_BLEND_SRC_DST_ALPHA,             // 6
    RS_BLEND_SRC_ONE_MINUS_DST_ALPHA,   // 7
    RS_BLEND_SRC_SRC_ALPHA_SATURATE     // 8
};

enum RsBlendDstFunc {
    RS_BLEND_DST_ZERO,                  // 0
    RS_BLEND_DST_ONE,                   // 1
    RS_BLEND_DST_SRC_COLOR,             // 2
    RS_BLEND_DST_ONE_MINUS_SRC_COLOR,   // 3
    RS_BLEND_DST_SRC_ALPHA,             // 4
    RS_BLEND_DST_ONE_MINUS_SRC_ALPHA,   // 5
    RS_BLEND_DST_DST_ALPHA,             // 6
    RS_BLEND_DST_ONE_MINUS_DST_ALPHA    // 7
};

enum RsTexEnvMode {
    RS_TEX_ENV_MODE_NONE,
    RS_TEX_ENV_MODE_REPLACE,
    RS_TEX_ENV_MODE_MODULATE,
    RS_TEX_ENV_MODE_DECAL
};

enum RsProgramParam {
    RS_PROGRAM_PARAM_INPUT,
    RS_PROGRAM_PARAM_OUTPUT,
    RS_PROGRAM_PARAM_CONSTANT,
    RS_PROGRAM_PARAM_TEXTURE_COUNT,
};

enum RsPrimitive {
    RS_PRIMITIVE_POINT,
    RS_PRIMITIVE_LINE,
    RS_PRIMITIVE_LINE_STRIP,
    RS_PRIMITIVE_TRIANGLE,
    RS_PRIMITIVE_TRIANGLE_STRIP,
    RS_PRIMITIVE_TRIANGLE_FAN
};

enum RsError {
    RS_ERROR_NONE,
    RS_ERROR_BAD_SHADER,
    RS_ERROR_BAD_SCRIPT,
    RS_ERROR_BAD_VALUE,
    RS_ERROR_OUT_OF_MEMORY
};

enum RsAnimationInterpolation {
    RS_ANIMATION_INTERPOLATION_STEP,
    RS_ANIMATION_INTERPOLATION_LINEAR,
    RS_ANIMATION_INTERPOLATION_BEZIER,
    RS_ANIMATION_INTERPOLATION_CARDINAL,
    RS_ANIMATION_INTERPOLATION_HERMITE,
    RS_ANIMATION_INTERPOLATION_BSPLINE
};

enum RsAnimationEdge {
    RS_ANIMATION_EDGE_UNDEFINED,
    RS_ANIMATION_EDGE_CONSTANT,
    RS_ANIMATION_EDGE_GRADIENT,
    RS_ANIMATION_EDGE_CYCLE,
    RS_ANIMATION_EDGE_OSCILLATE,
    RS_ANIMATION_EDGE_CYLE_RELATIVE
};

enum RsA3DClassID {
    RS_A3D_CLASS_ID_UNKNOWN,
    RS_A3D_CLASS_ID_MESH,
    RS_A3D_CLASS_ID_SIMPLE_MESH,
    RS_A3D_CLASS_ID_TYPE,
    RS_A3D_CLASS_ID_ELEMENT,
    RS_A3D_CLASS_ID_ALLOCATION,
    RS_A3D_CLASS_ID_PROGRAM_VERTEX,
    RS_A3D_CLASS_ID_PROGRAM_RASTER,
    RS_A3D_CLASS_ID_PROGRAM_FRAGMENT,
    RS_A3D_CLASS_ID_PROGRAM_STORE,
    RS_A3D_CLASS_ID_SAMPLER,
    RS_A3D_CLASS_ID_ANIMATION,
    RS_A3D_CLASS_ID_LIGHT,
    RS_A3D_CLASS_ID_ADAPTER_1D,
    RS_A3D_CLASS_ID_ADAPTER_2D,
    RS_A3D_CLASS_ID_SCRIPT_C
};

typedef struct {
    RsA3DClassID classID;
    const char* objectName;
} RsFileIndexEntry;

#ifndef NO_RS_FUNCS
#include "rsgApiFuncDecl.h"
#endif

#ifdef __cplusplus
};
#endif

#endif // RENDER_SCRIPT_H



