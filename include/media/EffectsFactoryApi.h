/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_EFFECTSFACTORYAPI_H_
#define ANDROID_EFFECTSFACTORYAPI_H_

#include <errno.h>
#include <stdint.h>
#include <sys/types.h>
#include <media/EffectApi.h>

#if __cplusplus
extern "C" {
#endif

/////////////////////////////////////////////////
//      Effect factory interface
/////////////////////////////////////////////////

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectQueryNumberEffects
//
//    Description:    Returns the number of different effects in all loaded libraries.
//          Each effect must have a different effect uuid (see
//          effect_descriptor_t). This function together with EffectQueryEffect()
//          is used to enumerate all effects present in all loaded libraries.
//          Each time EffectQueryNumberEffects() is called, the factory must
//          reset the index of the effect descriptor returned by next call to
//          EffectQueryEffect() to restart enumeration from the beginning.
//
//    Input/Output:
//          pNumEffects:    address where the number of effects should be returned.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     factory failed to initialize
//                          -EINVAL     invalid pNumEffects
//        *pNumEffects:     updated with number of effects in factory
//
////////////////////////////////////////////////////////////////////////////////
int EffectQueryNumberEffects(uint32_t *pNumEffects);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectQueryEffect
//
//    Description:    Returns a descriptor of the next available effect.
//          See effect_descriptor_t for a details on effect descriptor.
//          This function together with EffectQueryNumberEffects() is used to enumerate all
//          effects present in all loaded libraries. The enumeration sequence is:
//              EffectQueryNumberEffects(&num_effects);
//              for (i = 0; i < num_effects; i++)
//                  EffectQueryEffect(i,...);
//
//    Input/Output:
//          pDescriptor:    address where to return the effect descriptor.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENOENT     no more effect available
//                          -ENODEV     factory failed to initialize
//                          -EINVAL     invalid pDescriptor
//                          -ENOSYS     effect list has changed since last execution of EffectQueryNumberEffects()
//        *pDescriptor:     updated with the effect descriptor.
//
////////////////////////////////////////////////////////////////////////////////
int EffectQueryEffect(uint32_t index, effect_descriptor_t *pDescriptor);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectCreate
//
//    Description:    Creates an effect engine of the specified type and returns an
//          effect control interface on this engine. The function will allocate the
//          resources for an instance of the requested effect engine and return
//          a handler on the effect control interface.
//
//    Input:
//          pEffectUuid:    pointer to the effect uuid.
//          sessionId:  audio session to which this effect instance will be attached. All effects created
//              with the same session ID are connected in series and process the same signal stream.
//              Knowing that two effects are part of the same effect chain can help the library implement
//              some kind of optimizations.
//          ioId:   identifies the output or input stream this effect is directed to at audio HAL. For future
//              use especially with tunneled HW accelerated effects
//
//    Input/Output:
//          pInterface:    address where to return the effect interface.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     factory failed to initialize
//                          -EINVAL     invalid pEffectUuid or pInterface
//                          -ENOENT     no effect with this uuid found
//        *pInterface:     updated with the effect interface.
//
////////////////////////////////////////////////////////////////////////////////
int EffectCreate(effect_uuid_t *pEffectUuid, int32_t sessionId, int32_t ioId, effect_interface_t *pInterface);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectRelease
//
//    Description:    Releases the effect engine whose handler is given as argument.
//          All resources allocated to this particular instance of the effect are
//          released.
//
//    Input:
//          interface:    handler on the effect interface to be released.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     factory failed to initialize
//                          -EINVAL     invalid interface handler
//
////////////////////////////////////////////////////////////////////////////////
int EffectRelease(effect_interface_t interface);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectLoadLibrary
//
//    Description:    Loads the effect library which path is given as first argument.
//          This must be the full path of a dynamic library (.so) implementing one or
//          more effect engines and exposing the effect library interface described in
//          EffectApi.h. The function returns a handle on the library for used by
//          further call to EffectUnloadLibrary() to unload the library.
//
//    Input:
//          libPath:    full path of the dynamic library file in the file system.
//
//          handle:     address where to return the library handle
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     effect factory not initialized or
//                                      library could not be loaded or
//                                      library does not implement required functions
//                          -EINVAL     invalid libPath string or handle
//
////////////////////////////////////////////////////////////////////////////////
int EffectLoadLibrary(const char *libPath, int *handle);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectUnloadLibrary
//
//    Description:  Unloads the effect library which handle is given as argument.
//
//    Input:
//          handle: library handle
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     effect factory not initialized
//                          -ENOENT     invalid handle
//
////////////////////////////////////////////////////////////////////////////////
int EffectUnloadLibrary(int handle);



////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectGetDescriptor
//
//    Description:    Returns the descriptor of the effect which uuid is pointed
//          to by first argument.
//
//    Input:
//          pEffectUuid:    pointer to the effect uuid.
//
//    Input/Output:
//          pDescriptor:    address where to return the effect descriptor.
//
//    Output:
//        returned value:    0          successful operation.
//                          -ENODEV     factory failed to initialize
//                          -EINVAL     invalid pEffectUuid or pDescriptor
//                          -ENOENT     no effect with this uuid found
//        *pDescriptor:     updated with the effect descriptor.
//
////////////////////////////////////////////////////////////////////////////////
int EffectGetDescriptor(effect_uuid_t *pEffectUuid, effect_descriptor_t *pDescriptor);

////////////////////////////////////////////////////////////////////////////////
//
//    Function:       EffectIsNullUuid
//
//    Description:    Helper function to compare effect uuid to EFFECT_UUID_NULL
//
//    Input:
//          pEffectUuid: pointer to effect uuid to compare to EFFECT_UUID_NULL.
//
//    Output:
//        returned value:    0 if uuid is different from EFFECT_UUID_NULL.
//                           1 if uuid is equal to EFFECT_UUID_NULL.
//
////////////////////////////////////////////////////////////////////////////////
int EffectIsNullUuid(effect_uuid_t *pEffectUuid);

#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTSFACTORYAPI_H_*/
