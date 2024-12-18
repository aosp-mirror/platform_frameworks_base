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

#ifndef __ADYNAMICINSTRUMENTATIONMANAGER_H__
#define __ADYNAMICINSTRUMENTATIONMANAGER_H__

#include <sys/cdefs.h>
#include <sys/types.h>

__BEGIN_DECLS

struct ADynamicInstrumentationManager_MethodDescriptor;
typedef struct ADynamicInstrumentationManager_MethodDescriptor
        ADynamicInstrumentationManager_MethodDescriptor;

struct ADynamicInstrumentationManager_TargetProcess;
typedef struct ADynamicInstrumentationManager_TargetProcess
        ADynamicInstrumentationManager_TargetProcess;

struct ADynamicInstrumentationManager_ExecutableMethodFileOffsets;
typedef struct ADynamicInstrumentationManager_ExecutableMethodFileOffsets
        ADynamicInstrumentationManager_ExecutableMethodFileOffsets;

/**
 * Initializes an ADynamicInstrumentationManager_TargetProcess. Caller must clean up when they are
 * done with ADynamicInstrumentationManager_TargetProcess_destroy.
 *
 * @param uid of targeted process.
 * @param pid of targeted process.
 * @param processName UTF-8 encoded string representing the same process as specified by `pid`.
 *                    Supplied to disambiguate from corner cases that may arise from pid reuse.
 *                    Referenced parameter must outlive the returned
 *                    ADynamicInstrumentationManager_TargetProcess.
 */
ADynamicInstrumentationManager_TargetProcess* _Nullable
        ADynamicInstrumentationManager_TargetProcess_create(
                uid_t uid, pid_t pid, const char* _Nonnull processName) __INTRODUCED_IN(36);
/**
 * Clean up an ADynamicInstrumentationManager_TargetProcess.
 *
 * @param instance returned from ADynamicInstrumentationManager_TargetProcess_create.
 */
void ADynamicInstrumentationManager_TargetProcess_destroy(
        const ADynamicInstrumentationManager_TargetProcess* _Nullable instance) __INTRODUCED_IN(36);

/**
 * Initializes an ADynamicInstrumentationManager_MethodDescriptor. Caller must clean up when they
 * are done with ADynamicInstrumentationManager_MethodDescriptor_destroy.
 *
 * @param fullyQualifiedClassName UTF-8 encoded fqcn of class containing the method. Referenced
 *                                parameter must outlive the returned
 *                                ADynamicInstrumentationManager_MethodDescriptor.
 * @param methodName UTF-8 encoded method name. Referenced parameter must outlive the returned
 *                   ADynamicInstrumentationManager_MethodDescriptor.
 * @param fullyQualifiedParameters UTF-8 encoded fqcn of parameters of the method's signature,
 *                                 or e.g. "int" for primitives. Referenced parameter should
 *                                 outlive the returned
 *                                 ADynamicInstrumentationManager_MethodDescriptor.
 * @param numParameters length of `fullyQualifiedParameters` array.
 */
ADynamicInstrumentationManager_MethodDescriptor* _Nullable
        ADynamicInstrumentationManager_MethodDescriptor_create(
                const char* _Nonnull fullyQualifiedClassName, const char* _Nonnull methodName,
                const char* _Nonnull* _Nonnull fullyQualifiedParameters, size_t numParameters)
                __INTRODUCED_IN(36);
/**
 * Clean up an ADynamicInstrumentationManager_MethodDescriptor.
 *
 * @param instance returned from ADynamicInstrumentationManager_MethodDescriptor_create.
 */
void ADynamicInstrumentationManager_MethodDescriptor_destroy(
        const ADynamicInstrumentationManager_MethodDescriptor* _Nullable instance)
        __INTRODUCED_IN(36);

/**
 * Get the containerPath calculated by
 * ADynamicInstrumentationManager_getExecutableMethodFileOffsets.
 * @param instance created with ADynamicInstrumentationManager_getExecutableMethodFileOffsets.
 * @return The OS path of the containing file as a UTF-8 string, which has the same lifetime
 *         as the ADynamicInstrumentationManager_ExecutableMethodFileOffsets instance passed
 *         as a param.
 */
const char* _Nullable ADynamicInstrumentationManager_ExecutableMethodFileOffsets_getContainerPath(
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets* _Nonnull instance)
        __INTRODUCED_IN(36);
/**
 * Get the containerOffset calculated by
 * ADynamicInstrumentationManager_getExecutableMethodFileOffsets.
 * @param instance created with ADynamicInstrumentationManager_getExecutableMethodFileOffsets.
 * @return The absolute address of the containing file within remote the process' virtual memory
 *         space.
 */
uint64_t ADynamicInstrumentationManager_ExecutableMethodFileOffsets_getContainerOffset(
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets* _Nonnull instance)
        __INTRODUCED_IN(36);
/**
 * Get the methodOffset calculated by ADynamicInstrumentationManager_getExecutableMethodFileOffsets.
 * @param instance created with ADynamicInstrumentationManager_getExecutableMethodFileOffsets.
 * @return The offset of the method within the container whose address is returned by
 *         ADynamicInstrumentationManager_ExecutableMethodFileOffsets_getContainerOffset.
 */
uint64_t ADynamicInstrumentationManager_ExecutableMethodFileOffsets_getMethodOffset(
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets* _Nonnull instance)
        __INTRODUCED_IN(36);
/**
 * Clean up an ADynamicInstrumentationManager_ExecutableMethodFileOffsets.
 *
 * @param instance returned from ADynamicInstrumentationManager_getExecutableMethodFileOffsets.
 */
void ADynamicInstrumentationManager_ExecutableMethodFileOffsets_destroy(
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets* _Nullable instance)
        __INTRODUCED_IN(36);
/**
 * Provides ART metadata about the described java method within the target process.
 *
 * @param targetProcess describes for which process the data is requested.
 * @param methodDescriptor describes the targeted method.
 * @param out will be populated with the data if successful. A nullptr combined
 *        with an OK status means that the program method is defined, but the offset
 *        info was unavailable because it is not AOT compiled. Caller owns `out` and
 *        should clean it up with
 *        ADynamicInstrumentationManager_ExecutableMethodFileOffsets_destroy.
 * @return status indicating success or failure. The values correspond to the `binder_exception_t`
 *         enum values from <android/binder_status.h>.
 */
int32_t ADynamicInstrumentationManager_getExecutableMethodFileOffsets(
        const ADynamicInstrumentationManager_TargetProcess* _Nonnull targetProcess,
        const ADynamicInstrumentationManager_MethodDescriptor* _Nonnull methodDescriptor,
        const ADynamicInstrumentationManager_ExecutableMethodFileOffsets* _Nullable* _Nonnull out)
        __INTRODUCED_IN(36);

__END_DECLS

#endif // __ADYNAMICINSTRUMENTATIONMANAGER_H__
