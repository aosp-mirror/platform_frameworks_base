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

#define LOG_TAG "VideoEditorJava"

#include <VideoEditorClasses.h>
#include <VideoEditorJava.h>
#include <VideoEditorLogging.h>
#include <VideoEditorOsal.h>

extern "C" {
#include <M4OSA_CharStar.h>
};


void
videoEditJava_checkAndThrowIllegalArgumentExceptionFunc(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                bool                                condition,
                const char*                         pMessage,
                const char*                         pFile,
                int                                 lineNo)
{
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Check if the condition is true.
        if (condition)
        {
            // Log the exception.
            VIDEOEDIT_LOG_EXCEPTION(ANDROID_LOG_ERROR, "VIDEO_EDITOR_JAVA",\
                    "videoEditJava_checkAndThrowIllegalArgumentException, %s (%s:%d)",
                    pMessage, pFile, lineNo);

            // Reset the result flag.
            (*pResult) = false;

            // Throw an exception.
            jniThrowException(pEnv, "java/lang/IllegalArgumentException", pMessage);
        }
    }
}

void
videoEditJava_checkAndThrowRuntimeExceptionFunc(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                bool                                condition,
                M4OSA_ERR                           result,
                const char*                         pFile,
                int                                 lineNo
                )
{
    const char* pMessage = NULL;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Check if the condition is true.
        if (condition)
        {
            // Get the error string.
            pMessage = videoEditJava_getErrorName(result);

            // Log the exception.
            VIDEOEDIT_LOG_EXCEPTION(ANDROID_LOG_ERROR, "VIDEO_EDITOR_JAVA",
                    "videoEditJava_checkAndThrowRuntimeException, %s (%s:%d)",
                    pMessage, pFile, lineNo);

            // Reset the result flag.
            (*pResult) = false;

            // Throw an exception.
            jniThrowException(pEnv, "java/lang/RuntimeException", pMessage);
        }
    }
}

void
videoEditJava_checkAndThrowIllegalStateExceptionFunc(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                bool                                condition,
                const char*                         pMessage,
                const char*                         pFile,
                int                                 lineNo
                )
{
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Check if the condition is true.
        if (condition)
        {
            // Log the exception.
            VIDEOEDIT_LOG_EXCEPTION(ANDROID_LOG_ERROR, "VIDEO_EDITOR_JAVA",
                    "videoEditJava_checkAndThrowIllegalStateException, %s (%s:%d)",
                    pMessage, pFile, lineNo);

            // Reset the result flag.
            (*pResult) = false;

            // Throw an exception.
            jniThrowException(pEnv, "java/lang/IllegalStateException", pMessage);
        }
    }
}

void
videoEditJava_getClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                const char*                         pName,
                jclass*                             pClazz)
{
    // Only look for the class if locating the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA",
                "videoEditJava_getClass(%s)", pName);

        // Look up the class.
        jclass clazz = pEnv->FindClass(pName);

        // Clear any resulting exceptions.
        pEnv->ExceptionClear();

        // Check if the class could be located.
        if (NULL != clazz)
        {
            // Return the class.
            (*pClazz) = clazz;
        }
        else
        {
            // Reset the result flag.
            (*pResult) = false;

            // Log the error.
            VIDEOEDIT_LOG_EXCEPTION(ANDROID_LOG_ERROR, "VIDEO_EDITOR_JAVA",
                    "videoEditJava_getClass, error: unable to locate class %s", pName);

            // Throw an exception.
            jniThrowException(pEnv, "java/lang/ClassNotFoundException",
                    "unable to locate class");
        }
    }
}

void
videoEditJava_getMethodId(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jclass                              clazz,
                const char*                         pName,
                const char*                         pType,
                jmethodID*                          pMethodId)
{
    // Only look for the class if locating the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA",
                "videoEditJava_getMethodId(%s,%s)", pName, pType);

        // Look up the method id.
        jmethodID methodId = pEnv->GetMethodID(clazz, pName, pType);

        // Clear any resulting exceptions.
        pEnv->ExceptionClear();

        // Check if the method could be located.
        if (NULL != methodId)
        {
            // Return the method id.
            (*pMethodId) = methodId;
        }
        else
        {
            // Reset the result flag.
            (*pResult) = false;

            // Log the error.
            VIDEOEDIT_LOG_EXCEPTION(ANDROID_LOG_ERROR, "VIDEO_EDITOR_JAVA",
                    "videoEditJava_getMethodId, error: unable to locate method %s with type %s",
                    pName, pType);

            // Throw an exception.
            jniThrowException(pEnv, "java/lang/NoSuchMethodException", "unable to locate method");
        }
    }
}

void
videoEditJava_getFieldId(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jclass                              clazz,
                const char*                         pName,
                const char*                         pType,
                jfieldID*                           pFieldId)
{
    // Only look for the class if locating the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA",
                "videoEditJava_getFieldId(%s,%s)", pName, pType);

        // Look up the field id.
        jfieldID fieldId = pEnv->GetFieldID(clazz, pName, pType);

        // Clear any resulting exceptions.
        pEnv->ExceptionClear();

        // Check if the field could be located.
        if (NULL != fieldId)
        {
            // Return the field id.
            (*pFieldId) = fieldId;
        }
        else
        {
            // Reset the result flag.
            (*pResult) = false;

            // Log the error.
            VIDEOEDIT_LOG_EXCEPTION(ANDROID_LOG_ERROR, "VIDEO_EDITOR_JAVA",
                    "videoEditJava_getFieldId, error: unable to locate field %s with type %s",
                    pName, pType);

            // Throw an exception.
            jniThrowException(pEnv, "java/lang/NoSuchFieldException", "unable to locate field");
        }
    }
}

void
videoEditJava_getObject(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                jfieldID                            objectFieldId,
                jobject*                            pObject)
{
    // Only retrieve the array object and size if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA",
            "videoEditJava_getObject()");

        // Retrieve the object.
        (*pObject) = pEnv->GetObjectField(object, objectFieldId);

        // Clear any resulting exceptions.
        pEnv->ExceptionClear();
    }
}

void
videoEditJava_getArray(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                jfieldID                            arrayFieldId,
                jobjectArray*                       pArray,
                jsize*                              pArraySize)
{
    // Only retrieve the array object and size if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA", "videoEditJava_getArray()");

        // Retrieve the array object.
        jobjectArray array     = (jobjectArray)pEnv->GetObjectField(object, arrayFieldId);
        jsize        arraySize = 0;

        // Clear any resulting exceptions.
        pEnv->ExceptionClear();

        // Check if the array could be retrieved.
        if (NULL != array)
        {
            // Retrieve the array size.
            arraySize = pEnv->GetArrayLength(array);
        }

        // Return the array and its size.
        (*pArray)     = array;
        (*pArraySize) = arraySize;
    }
}

void*
videoEditJava_getString(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jobject                             object,
                jfieldID                            stringFieldId,
                M4OSA_UInt32*                       pLength)
{
    void*        pString = M4OSA_NULL;
    jstring      string  = NULL;
    M4OSA_UInt32 length  = 0;
    M4OSA_Char*  pLocal  = M4OSA_NULL;
    M4OSA_ERR    result  = M4NO_ERROR;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA", "videoEditJava_getString()");

        // Check if an object containing a string was specified.
        if (NULL != stringFieldId)
        {
            // Retrieve the string object.
            string = (jstring)pEnv->GetObjectField(object, stringFieldId);

            // Clear any resulting exceptions.
            pEnv->ExceptionClear();
        }
        else
        {
            // The string itself was specified.
            string = (jstring)object;
        }

        // Check if the string could be retrieved.
        if (NULL != string)
        {
            // Get a local copy of the string.
            pLocal = (M4OSA_Char*)pEnv->GetStringUTFChars(string, M4OSA_NULL);
            if (M4OSA_NULL != pLocal)
            {
                // Determine the length of the path
                // (add one extra character for the zero terminator).
                length = strlen((const char *)pLocal) + 1;

                // Allocate memory for the string.
                pString = videoEditOsal_alloc(pResult, pEnv, length, "String");
                if (*pResult)
                {
                    // Copy the string.
                    result = M4OSA_chrNCopy((M4OSA_Char*)pString, pLocal, length);

                    // Check if the copy succeeded.
                    videoEditJava_checkAndThrowRuntimeException(pResult, pEnv,
                     (M4NO_ERROR != result), result);

                    // Check if the string could not be copied.
                    if (!(*pResult))
                    {
                        // Free the allocated memory.
                        videoEditOsal_free(pString);
                        pString = M4OSA_NULL;
                    }
                }

                // Release the local copy of the string.
                pEnv->ReleaseStringUTFChars(string, (const char *)pLocal);
            }
        }

        // Check if the string was empty or could be copied.
        if (*pResult)
        {
            // Check if the length was requested.
            if (M4OSA_NULL != pLength)
            {
                // Return the length.
                (*pLength) = length;
            }
        }

        // Delete local references to avoid memory leaks
        pEnv->DeleteLocalRef(string);
    }

    // Return the string.
    return(pString);
}

void
videoEditJava_getStaticIntField(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                jclass                              clazz,
                const char*                         pName,
                int*                                pValue)
{
    // Only look for the class if locating the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA",
                "videoEditJava_getStaticIntField(%s)", pName);

        // Look up the field id.
        jfieldID fieldId = pEnv->GetStaticFieldID(clazz, pName, "I");

        // Clear any resulting exceptions.
        pEnv->ExceptionClear();

        // Check if the field could be located.
        if (NULL != fieldId)
        {
            // Retrieve the field value.
            (*pValue) = pEnv->GetStaticIntField(clazz, fieldId);

            // Log the value.
            VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA",
                    "videoEditJava_getStaticIntField, %s = %d", pName, (*pValue));
        }
        else
        {
            // Reset the result flag.
            (*pResult) = false;

            // Log the error.
            VIDEOEDIT_LOG_EXCEPTION(ANDROID_LOG_ERROR, "VIDEO_EDITOR_JAVA",
                    "videoEditJava_getStaticIntField, error: unable to locate field %s", pName);

            // Throw an exception.
            jniThrowException(pEnv, "java/lang/NoSuchFieldException",
                    "unable to locate static field");
        }
    }
}

void
videoEditJava_initConstantClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                VideoEditJava_ConstantsClass*               pClass)
{
    bool   gotten = true;
    jclass clazz  = NULL;
    int    index  = 0;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA",
                "videoEditJava_initConstantClass(%s)", pClass->pName);

        // Only initialize the class once.
        if (!pClass->initialized)
        {
            // Look up the class.
            videoEditJava_getClass(pResult, pEnv, pClass->pName, &clazz);

            // Loop over the constants.
            for (index = 0; index < pClass->count; index++)
            {
                // Look up the constant.
                videoEditJava_getStaticIntField(pResult, pEnv, clazz,
                                        pClass->pConstants[index].pName,
                                        &pClass->pConstants[index].java);
            }

            // Check if all constants could be located.
            if (*pResult)
            {
                // Set the initialized flag.
                pClass->initialized = true;
            }
        }
    }
}

const char*
videoEditJava_getConstantClassName(
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value,
                VideoEditJava_UnknownConstant               unknown)
{
    const char* pName = M4OSA_NULL;
    int         index = 0;

    // Loop over the list with constants.
    for (index = 0;
         ((M4OSA_NULL == pName) && (index < pClass->count));
         index++)
    {
        // Check if the specified value matches the c value of the constant.
        if (value == pClass->pConstants[index].c)
        {
            // Set the name.
            pName = pClass->pConstants[index].pName;
        }
    }

    // Check if no constant was found.
    if (M4OSA_NULL == pName)
    {
        // Check if a function was specified to handle this case.
        if (M4OSA_NULL != unknown)
        {
            // Pass the constant to the specified unknown function.
            pName = unknown(value);
        }
        else
        {
            // Set the description to a default value.
            pName = "<unknown>";
        }
    }

    // Return the result.
    return(pName);
}

const char*
videoEditJava_getConstantClassString(
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value,
                VideoEditJava_UnknownConstant               unknown)
{
    const char* pString = M4OSA_NULL;
    int         index   = 0;

    // Loop over the list with constants.
    for (index = 0;
         ((M4OSA_NULL == pString) && (index < pClass->count));
         index++)
    {
        // Check if the specified value matches the c value of the constant.
        if (value == pClass->pConstants[index].c)
        {
            // Set the description.
            pString = pClass->pConstants[index].pDescription;
        }
    }

    // Check if no constant was found.
    if (M4OSA_NULL == pString)
    {
        // Check if a function was specified to handle this case.
        if (M4OSA_NULL != unknown)
        {
            // Pass the constant to the specified unknown function.
            pString = unknown(value);
        }
        else
        {
            // Set the description to a default value.
            pString = "<unknown>";
        }
    }

    // Return the result.
    return(pString);
}

int
videoEditJava_getConstantClassJavaToC(
                bool*                               pResult,
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value)
{
    bool gotten = false;
    int  index  = 0;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Loop over the list with constants.
        for (index = 0; ((!gotten) && (index < pClass->count)); index++)
        {
            // Check if the specified value matches the java value of the constant.
            if (value == pClass->pConstants[index].java)
            {
                // Set the value to the c value.
                value = pClass->pConstants[index].c;

                // Set the gotten flag.
                gotten = true;
            }
        }

        // Check if the value was not found.
        if (!gotten)
        {
            (*pResult) = false;
        }
    }

    // Return the translated value.
    return(value);
}

int
videoEditJava_getConstantClassJavaToC(
                bool*                               pResult,
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value,
                int                                 unknown)
{
    bool gotten = false;
    int  index  = 0;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Loop over the list with constants.
        for (index = 0; ((!gotten) && (index < pClass->count)); index++)
        {
            // Check if the specified value matches the java value of the constant.
            if (value == pClass->pConstants[index].java)
            {
                // Set the value to the c value.
                value = pClass->pConstants[index].c;

                // Set the gotten flag.
                gotten = true;
            }
        }

        // If the constant was not found, look for the specified unknown.
        if (!gotten)
        {
            // Set the value to the c value.
            value = unknown;
        }
    }

    // Return the translated value.
    return(value);
}

int
videoEditJava_getConstantClassCToJava(
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value)
{
    bool gotten = false;
    int  index  = 0;

    // Loop over the list with constants.
    for (index = 0; ((!gotten) && (index < pClass->count)); index++)
    {
        // Check if the specified value matches the c value of the constant.
        if (value == pClass->pConstants[index].c)
        {
            // Set the value to the java value.
            value = pClass->pConstants[index].java;

            // Set the gotten flag.
            gotten = true;
        }
    }

    // Return the translated value.
    return(value);
}

int
videoEditJava_getConstantClassCToJava(
                const VideoEditJava_ConstantsClass*         pClass,
                int                                 value,
                int                                 unknown)
{
    bool gotten = false;
    int  index  = 0;

    // Loop over the list with constants.
    for (index = 0; ((!gotten) && (index < pClass->count)); index++)
    {
        // Check if the specified value matches the c value of the constant.
        if (value == pClass->pConstants[index].c)
        {
            // Set the value to the java value.
            value = pClass->pConstants[index].java;

            // Set the gotten flag.
            gotten = true;
        }
    }

    // If the constant was not found, look for the specified unknown.
    if (!gotten)
    {
        // Loop over the list with constants.
        for (index = 0; ((!gotten) && (index < pClass->count)); index++)
        {
            // Check if the specified value matches the java value of the constant.
            if (unknown == pClass->pConstants[index].c)
            {
                // Set the value to the c value.
                value = pClass->pConstants[index].java;

                // Set the gotten flag.
                gotten = true;
            }
        }
    }

    // Return the translated value.
    return(value);
}

void
videoEditJava_initFieldClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                VideoEditJava_FieldsClass*                  pClass)
{
    bool   gotten = true;
    jclass clazz  = NULL;
    int    index  = 0;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA",
                "videoEditJava_initFieldClass(%s)", pClass->pName);

        // Only initialize the class once.
        if (!pClass->initialized)
        {
            // Look up the class.
            videoEditJava_getClass(pResult, pEnv, pClass->pName, &clazz);

            // Loop over the fields.
            for (index = 0; index < pClass->count; index++)
            {
                // Look up the field id.
                videoEditJava_getFieldId(
                        pResult,
                        pEnv,
                        clazz,
                        pClass->pFields[index].pName,
                        pClass->pFields[index].pType,
                        &pClass->pFields[index].fieldId);
            }

            // Check if all fields could be located.
            if (*pResult)
            {
                // Set the initialized flag.
                pClass->initialized = true;
            }
        }
    }
}

void
videoEditJava_fieldClassClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                const VideoEditJava_FieldsClass*            pClass,
                jclass*                             pClazz)
{
    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Check if the class is initialized.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv, (!pClass->initialized),
                "field class not initialized");

        // Get the class.
        videoEditJava_getClass(pResult, pEnv, pClass->pName, pClazz);
    }
}

void
videoEditJava_fieldClassFieldIds(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                const VideoEditJava_FieldsClass*            pClass,
                int                                 count,
                VideoEditJava_FieldIds*                     pIds)
{
    int index = 0;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Check if the class is initialized.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv, (!pClass->initialized),
                "field class not initialized");

        // Check if the number of fields matches.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,
                (pClass->count != count),
                "field class type mismatch");

        // Check if the class and object are valid.
        if (*pResult)
        {
            // Loop over the class fields.
            for (index = 0; index < count; index++)
            {
                // Copy the field ids.
                pIds->fieldIds[index] = pClass->pFields[index].fieldId;
            }
        }
    }
}

void
videoEditJava_initMethodClass(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                VideoEditJava_MethodsClass*                 pClass)
{
    bool   gotten = true;
    jclass clazz  = NULL;
    int    index  = 0;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Log the function call.
        VIDEOEDIT_LOG_FUNCTION(ANDROID_LOG_INFO, "VIDEO_EDITOR_JAVA",
                "videoEditJava_initMethodClass(%s)", pClass->pName);

        // Only initialize the class once.
        if (!pClass->initialized)
        {
            // Look up the class.
            videoEditJava_getClass(pResult, pEnv, pClass->pName, &clazz);

            // Loop over the methods.
            for (index = 0; index < pClass->count; index++)
            {
                // Look up the method id.
                videoEditJava_getMethodId(
                        pResult,
                        pEnv,
                        clazz,
                        pClass->pMethods[index].pName,
                        pClass->pMethods[index].pType,
                        &pClass->pMethods[index].methodId);
            }

            // Check if all methods could be located.
            if (*pResult)
            {
                // Set the initialized flag.
                pClass->initialized = true;
            }
        }
    }
}

void
videoEditJava_methodClassMethodIds(
                bool*                               pResult,
                JNIEnv*                             pEnv,
                const VideoEditJava_MethodsClass*   pClass,
                int                                 count,
                VideoEditJava_MethodIds*            pIds)
{
    int index = 0;

    // Check if the previous action succeeded.
    if (*pResult)
    {
        // Check if the class is initialized.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv, (!pClass->initialized),
                    "method class not initialized");

        // Check if the number of methods matches.
        videoEditJava_checkAndThrowIllegalArgumentException(pResult, pEnv,\
                    (pClass->count != count),
                    "method class type mismatch");

        // Check if the class and object are valid.
        if (*pResult)
        {
            // Loop over the class methods.
            for (index = 0; index < count; index++)
            {
                // Copy the method ids.
                pIds->methodIds[index] = pClass->pMethods[index].methodId;
            }
        }
    }
}

