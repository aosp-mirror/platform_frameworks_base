/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef VIDEO_EDiTOR_JAVA_H
#define VIDEO_EDiTOR_JAVA_H

#include <jni.h>
#include <JNIHelp.h>

/**
 ************************************************************************
 * @file        VideoEditorJava.h
 * @brief       Interface for JNI methods that have specific access to
 *              class, objects and method Ids defined in Java layer
 ************************************************************************
*/

extern "C" {
#include <M4OSA_Types.h>
#include <M4OSA_Error.h>
}

#define VIDEOEDIT_JAVA_CONSTANT_INIT(m_name, m_c)                           \
            { m_name,                                                       \
              0,                                                            \
              m_c,                                                          \
              #m_c }

#define VIDEOEDIT_JAVA_DEFINE_CONSTANTS(m_class)                            \
static                                                                      \
VideoEditJava_Constant g##m_class##Constants [] =

#define VIDEOEDIT_JAVA_DEFINE_CONSTANT_CLASS(                               \
                m_class,                                                    \
                m_name,                                                     \
                m_unknownName,                                              \
                m_unknownString)                                            \
                                                                            \
static VideoEditJava_ConstantsClass g##m_class##ConstantsClass =            \
{       m_name,                                                             \
        &g##m_class##Constants[0],                                          \
        (sizeof(g##m_class##Constants) / sizeof(VideoEditJava_Constant)),   \
        false                                                               \
};                                                                          \
                                                                            \
                                                                            \
void videoEditJava_init##m_class##Constants(                                \
                bool*                               pResult,                \
                JNIEnv*                             pEnv)                   \
{                                                                           \
    videoEditJava_initConstantClass(                                        \
                pResult,                                                    \
                pEnv,                                                       \
                &g##m_class##ConstantsClass);                               \
}                                                                           \
                                                                            \
const char* videoEditJava_get##m_class##Name(                               \
                int                                 value)                  \
{                                                                           \
    return(videoEditJava_getConstantClassName(                              \
                &g##m_class##ConstantsClass,                                \
                value,                                                      \
                m_unknownName));                                            \
}                                                                           \
                                                                            \
const char* videoEditJava_get##m_class##String(                             \
                int                                 value)                  \
{                                                                           \
    return(videoEditJava_getConstantClassString(                            \
                &g##m_class##ConstantsClass,                                \
                value,                                                      \
                m_unknownString));                                          \
}                                                                           \
                                                                            \
int                                                                         \
videoEditJava_get##m_class##JavaToC(                                        \
                bool*                               pResult,                \
                int                                 value)                  \
{                                                                           \
    return(videoEditJava_getConstantClassJavaToC(                           \
                pResult,                                                    \
                &g##m_class##ConstantsClass,                                \
                value));                                                    \
}                                                                           \
                                                                            \
int                                                                         \
videoEditJava_get##m_class##JavaToC(                                        \
                bool*                               pResult,                \
                int                                 value,                  \
                int                                 unknown)                \
{                                                                           \
    return(videoEditJava_getConstantClassJavaToC(                           \
                pResult,                                                    \
                &g##m_class##ConstantsClass,                                \
                value,                                                      \
                unknown));                                                  \
}                                                                           \
                                                                            \
int                                                                         \
videoEditJava_get##m_class##CToJava(                                        \
                        int                                 value)          \
{                                                                           \
    return(videoEditJava_getConstantClassCToJava(                           \
                &g##m_class##ConstantsClass,                                \
                value));                                                    \
}                                                                           \
                                                                            \
int                                                                         \
videoEditJava_get##m_class##CToJava(                                        \
                int                                 value,                  \
                int                                 unknown)                \
{                                                                           \
    return(videoEditJava_getConstantClassCToJava(                           \
                &g##m_class##ConstantsClass,                                \
                value,                                                      \
                unknown));                                                  \
}


#define VIDEOEDIT_JAVA_DECLARE_CONSTANT_CLASS(m_class)                       \
void                                                                        \
videoEditJava_init##m_class##Constants(                                     \
                bool*                               pResult,                \
                JNIEnv*                             pEnv);                  \
                                                                            \
const char*                                                                 \
videoEditJava_get##m_class##Name(                                           \
                int                                 value);                 \
                                                                            \
const char*                                                                 \
videoEditJava_get##m_class##String(                                         \
                int                                 value);                 \
                                                                            \
int                                                                         \
videoEditJava_get##m_class##JavaToC(                                        \
                bool*                               pResult,                \
                int                                 value,                  \
                int                                 unknown);               \
                                                                            \
int                                                                         \
videoEditJava_get##m_class##JavaToC(                                        \
                bool*                               pResult,                \
                int                                 value);                 \
                                                                            \
int                                                                         \
videoEditJava_get##m_class##CToJava(                                        \
                int                                 value);                 \
                                                                            \
int                                                                         \
videoEditJava_get##m_class##CToJava(                                        \
                int                                 value,                  \
                int                                 unknown);

#define VIDEOEDIT_JAVA_FIELD_INIT(m_name, m_type)                           \
    { m_name,                                                               \
      m_type,                                                               \
      NULL }

#define VIDEOEDIT_JAVA_DEFINE_FIELDS(m_class)                               \
static                                                                      \
VideoEditJava_Field g##m_class##Fields [] =

#define VIDEOEDIT_JAVA_DEFINE_FIELD_CLASS(m_class, m_name)                  \
static VideoEditJava_FieldsClass g##m_class##FieldsClass =                  \
    { m_name,                                                               \
      &g##m_class##Fields[0],                                               \
      (sizeof(g##m_class##Fields) / sizeof(VideoEditJava_Field)),           \
      false };                                                              \
                                                                            \
void                                                                        \
videoEditJava_init##m_class##Fields(                                        \
                bool*                               pResult,                \
                JNIEnv*                             pEnv)                   \
{                                                                           \
    videoEditJava_initFieldClass(                                           \
                pResult,                                                    \
                pEnv,                                                       \
                &g##m_class##FieldsClass);                                  \
}                                                                           \
                                                                            \
void                                                                        \
videoEditJava_get##m_class##Class(                                          \
                bool*                               pResult,                \
                JNIEnv*                             pEnv,                   \
                jclass*                             pClazz)                 \
{                                                                           \
    videoEditJava_fieldClassClass(                                          \
                pResult,                                                    \
                pEnv,                                                       \
                &g##m_class##FieldsClass,                                   \
                pClazz);                                                    \
}                                                                           \
                                                                            \
void                                                                        \
videoEditJava_get##m_class##FieldIds(                                       \
                bool*                               pResult,                \
                JNIEnv*                             pEnv,                   \
                VideoEditJava_##m_class##FieldIds*          pIds)           \
{                                                                           \
    videoEditJava_fieldClassFieldIds(                                       \
                pResult,                                                    \
                pEnv,                                                       \
                &g##m_class##FieldsClass,                                   \
                (sizeof(VideoEditJava_##m_class##FieldIds) /                \
                 sizeof(jfieldID)),                                         \
                (VideoEditJava_FieldIds*)pIds);                             \
}

#define VIDEOEDIT_JAVA_DECLARE_FIELD_CLASS(m_class)                         \
void                                                                        \
videoEditJava_init##m_class##Fields(                                        \
                bool*                               pResult,                \
                JNIEnv*                             pEnv);                  \
                                                                            \
void                                                                        \
videoEditJava_get##m_class##Class(                                          \
                bool*                               pResult,                \
                JNIEnv*                             pEnv,                   \
                jclass*                             pClazz);                \
                                                                            \
void                                                                        \
videoEditJava_get##m_class##FieldIds(                                       \
                bool*                               pResult,                \
                JNIEnv*                             pEnv,                   \
                VideoEditJava_##m_class##FieldIds*          pIds);


#define VIDEOEDIT_JAVA_METHOD_INIT(m_name, m_type)                          \
    { m_name,                                                               \
      m_type,                                                               \
      NULL }

#define VIDEOEDIT_JAVA_DEFINE_METHODS(m_class)                              \
static                                                                      \
VideoEditJava_Method g##m_class##Methods [] =

#define VIDEOEDIT_JAVA_DEFINE_METHOD_CLASS(m_class, m_name)                 \
static VideoEditJava_MethodsClass g##m_class##MethodsClass =                \
    { m_name,                                                               \
      &g##m_class##Methods[0],                                              \
      (sizeof(g##m_class##Methods) / sizeof(VideoEditJava_Method)),         \
      false };                                                              \
                                                                            \
void                                                                        \
videoEditJava_init##m_class##Methods(                                       \
                bool*                               pResult,                \
                JNIEnv*                             pEnv)                   \
{                                                                           \
    videoEditJava_initMethodClass(                                          \
                pResult,                                                    \
                pEnv,                                                       \
                &g##m_class##MethodsClass);                                 \
}                                                                           \
                                                                            \
void                                                                        \
videoEditJava_get##m_class##MethodIds(                                      \
                bool*                               pResult,                \
                JNIEnv*                             pEnv,                   \
                VideoEditJava_##m_class##MethodIds*         pIds)           \
{                                                                           \
    videoEditJava_methodClassMethodIds(                                     \
                pResult,                                                    \
                pEnv,                                                       \
                &g##m_class##MethodsClass,                                  \
                (sizeof(VideoEditJava_##m_class##MethodIds) /               \
                 sizeof(jmethodID)),                                        \
                (VideoEditJava_MethodIds*)pIds);                            \
}

#define VIDEOEDIT_JAVA_DECLARE_METHOD_CLASS(m_class)                        \
void                                                                        \
videoEditJava_init##m_class##Methods(                                       \
                bool*                               pResult,                \
                JNIEnv*                             pEnv);                  \
                                                                            \
void                                                                        \
videoEditJava_get##m_class##MethodIds(                                      \
                bool*                               pResult,                \
                JNIEnv*                             pEnv,                   \
                VideoEditJava_##m_class##MethodIds*         pIds);


typedef struct
{
    const char*     pName;
    int             java;
    int             c;
    const char*     pDescription;
} VideoEditJava_Constant;

typedef struct
{
    const char*             pName;
    VideoEditJava_Constant* pConstants;
    int                     count;
    bool                    initialized;
} VideoEditJava_ConstantsClass;

typedef const char* (*VideoEditJava_UnknownConstant)(int constant);

typedef struct
{
    const char*             pName;
    const char*             pType;
    jfieldID                fieldId;
} VideoEditJava_Field;

typedef struct
{
    const char*             pName;
    VideoEditJava_Field*    pFields;
    int                     count;
    bool                    initialized;
} VideoEditJava_FieldsClass;

typedef struct
{
    jfieldID fieldIds[];
} VideoEditJava_FieldIds;

typedef struct
{
    const char*             pName;
    const char*             pType;
    jmethodID               methodId;
} VideoEditJava_Method;

typedef struct
{
    const char*             pName;
    VideoEditJava_Method*   pMethods;
    int                     count;
    bool                    initialized;
} VideoEditJava_MethodsClass;

typedef struct
{
    jmethodID methodIds[];
} VideoEditJava_MethodIds;

#define videoEditJava_checkAndThrowIllegalArgumentException(\
    a, b, c, d) videoEditJava_checkAndThrowIllegalArgumentExceptionFunc(\
    a, b, c, d, __FILE__, __LINE__)

#define videoEditJava_checkAndThrowRuntimeException(\
    a, b, c, d) videoEditJava_checkAndThrowRuntimeExceptionFunc(\
    a, b, c, d, __FILE__, __LINE__)

#define videoEditJava_checkAndThrowIllegalStateException(\
    a, b, c, d) videoEditJava_checkAndThrowIllegalStateExceptionFunc(\
    a, b, c, d, __FILE__, __LINE__)

void
videoEditJava_checkAndThrowIllegalArgumentExceptionFunc(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                bool                                condition,
                const char*                         pMessage,
                const char*                         pFile,
                int                                 lineNo
                );

void
videoEditJava_checkAndThrowRuntimeExceptionFunc(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                bool                                condition,
                M4OSA_ERR                           result,
                const char*                         pFile,
                int                                 lineNo
                );

void
videoEditJava_checkAndThrowIllegalStateExceptionFunc(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                bool                                condition,
                const char*                         pMessage,
                const char*                         pFile,
                int                                 lineNo
                );

void
videoEditJava_getClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                const char*                         pName,
                jclass*                             pClazz);

void
videoEditJava_getMethodId(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jclass                              clazz,
                const char*                         pName,
                const char*                         pType,
                jmethodID*                          pMethodId);

void videoEditJava_getFieldId(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jclass                              clazz,
                const char*                         pName,
                const char*                         pType,
                jfieldID*                           pFieldId);

void videoEditJava_getObject(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                jfieldID                            objectFieldId,
                jobject*                            pObject);

void videoEditJava_getArray(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                jfieldID                            arrayFieldId,
                jobjectArray*                       pArray,
                jsize*                              pArraySize);

void* videoEditJava_getString(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                jfieldID                            stringFieldId,
                M4OSA_UInt32*                       pLength);

void videoEditJava_getStaticIntField(
                bool*                               pResult,
                JNIEnv*                             env,
                jclass                              clazz,
                const char*                         pName,
                int*                                pValue);

void
videoEditJava_initConstantClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                VideoEditJava_ConstantsClass*               pClass);

const char*
videoEditJava_getConstantClassName(
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value,
                VideoEditJava_UnknownConstant               unknown);

const char*
videoEditJava_getConstantClassString(
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value,
                VideoEditJava_UnknownConstant               unknown);

int
videoEditJava_getConstantClassJavaToC(
                bool*                               pResult,
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value);

int
videoEditJava_getConstantClassJavaToC(
                bool*                               pResult,
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value,
                int                                 unknown);

int
videoEditJava_getConstantClassCToJava(
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value);

int
videoEditJava_getConstantClassCToJava(
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value,
                int                                 unknown);

void
videoEditJava_initFieldClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                VideoEditJava_FieldsClass*                  pClass);

void
videoEditJava_fieldClassClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                const VideoEditJava_FieldsClass*            pClass,
                jclass*                             pClazz);

void
videoEditJava_fieldClassFieldIds(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                const VideoEditJava_FieldsClass*            pClass,
                int                                 count,
                VideoEditJava_FieldIds*                     pIds);

void
videoEditJava_initMethodClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                VideoEditJava_MethodsClass*                 pClass);

void
videoEditJava_methodClassMethodIds(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                const VideoEditJava_MethodsClass*           pClass,
                int                                 count,
                VideoEditJava_MethodIds*                    pIds);

#endif // VIDEO_EDiTOR_JAVA_H

