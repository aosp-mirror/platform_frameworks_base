// Copyright (C) 2011 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma version(1)

#pragma rs java_package_name(com.android.scenegraph)

// The sole purpose of this script is to have various structs exposed
// so that java reflected classes are generated
#include "scenegraph_objects.rsh"

// Export our native constants to java so that we don't have parallel definitions
const int ShaderParam_FLOAT4_DATA = SHADER_PARAM_FLOAT4_DATA;
const int ShaderParam_TRANSFORM_DATA = SHADER_PARAM_TRANSFORM_DATA;
const int ShaderParam_TRANSFORM_MODEL = SHADER_PARAM_TRANSFORM_MODEL;

const int ShaderParam_FLOAT4_CAMERA_POS = SHADER_PARAM_FLOAT4_CAMERA_POS;
const int ShaderParam_FLOAT4_CAMERA_DIR = SHADER_PARAM_FLOAT4_CAMERA_DIR;
const int ShaderParam_TRANSFORM_VIEW = SHADER_PARAM_TRANSFORM_VIEW;
const int ShaderParam_TRANSFORM_PROJ = SHADER_PARAM_TRANSFORM_PROJ;
const int ShaderParam_TRANSFORM_VIEW_PROJ = SHADER_PARAM_TRANSFORM_VIEW_PROJ;
const int ShaderParam_TRANSFORM_MODEL_VIEW = SHADER_PARAM_TRANSFORM_MODEL_VIEW;
const int ShaderParam_TRANSFORM_MODEL_VIEW_PROJ = SHADER_PARAM_TRANSFORM_MODEL_VIEW_PROJ;

const int ShaderParam_FLOAT4_LIGHT_COLOR = SHADER_PARAM_FLOAT4_LIGHT_COLOR;
const int ShaderParam_FLOAT4_LIGHT_POS = SHADER_PARAM_FLOAT4_LIGHT_POS;
const int ShaderParam_FLOAT4_LIGHT_DIR = SHADER_PARAM_FLOAT4_LIGHT_DIR;

const int ShaderParam_TEXTURE = SHADER_PARAM_TEXTURE;

const int Transform_TRANSLATE = TRANSFORM_TRANSLATE;
const int Transform_ROTATE = TRANSFORM_ROTATE;
const int Transform_SCALE = TRANSFORM_SCALE;

const int TextureType_TEXTURE_2D = TEXTURE_2D;
const int TextureType_TEXTURE_CUBE = TEXTURE_CUBE;
const int TextureType_TEXTURE_RENDER_TARGET = TEXTURE_RENDER_TARGET;

SgTransform *exportPtr;
SgTransformComponent *componentPtr;
SgRenderState *sExport;
SgRenderable *drExport;
SgRenderPass *pExport;
SgCamera *exportPtrCam;
SgLight *exportPtrLight;
SgShaderParam *spExport;
SgShaderParamData *spDataExport;
SgVertexShader *pvExport;
SgFragmentShader *pfExport;
SgTexture *texExport;
