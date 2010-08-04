/*
 ** Copyright 2009, The Android Open Source Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

#include <ctype.h>
#include <stdlib.h>
#include <errno.h>

#include <cutils/log.h>

#include "hooks.h"

// ----------------------------------------------------------------------------
namespace android {
// ----------------------------------------------------------------------------

#undef API_ENTRY
#undef CALL_GL_API
#undef GL_EXTENSION
#undef GL_EXTENSION_NAME

#if defined(__arm__)

    #ifdef HAVE_ARM_TLS_REGISTER
        #define GET_TLS(reg) \
            "mrc p15, 0, " #reg ", c13, c0, 3 \n"
    #else
        #define GET_TLS(reg) \
            "mov   " #reg ", #0xFFFF0FFF      \n"  \
            "ldr   " #reg ", [" #reg ", #-15] \n"
    #endif

    #define API_ENTRY(_api) __attribute__((naked)) _api

    #define CALL_GL_EXTENSION_API(_api)                         \
         asm volatile(                                          \
            GET_TLS(r12)                                        \
            "ldr   r12, [r12, %[tls]] \n"                       \
            "cmp   r12, #0            \n"                       \
            "ldrne r12, [r12, %[api]] \n"                       \
            "cmpne r12, #0            \n"                       \
            "bxne  r12                \n"                       \
            "bx    lr                 \n"                       \
            :                                                   \
            : [tls] "J"(TLS_SLOT_OPENGL_API*4),                 \
              [api] "J"(__builtin_offsetof(gl_hooks_t,          \
                                      ext.extensions[_api]))    \
            :                                                   \
            );

    #define GL_EXTENSION_NAME(_n)  __glExtFwd##_n

    #define GL_EXTENSION(_n)                         \
        void API_ENTRY(GL_EXTENSION_NAME(_n))() {    \
            CALL_GL_EXTENSION_API(_n);               \
        }


#else

    #define GL_EXTENSION_NAME(_n) NULL

    #define GL_EXTENSION(_n)

    #warning "eglGetProcAddress() partially supported on this architecture"

#endif

GL_EXTENSION(0)
GL_EXTENSION(1)
GL_EXTENSION(2)
GL_EXTENSION(3)
GL_EXTENSION(4)
GL_EXTENSION(5)
GL_EXTENSION(6)
GL_EXTENSION(7)
GL_EXTENSION(8)
GL_EXTENSION(9)
GL_EXTENSION(10)
GL_EXTENSION(11)
GL_EXTENSION(12)
GL_EXTENSION(13)
GL_EXTENSION(14)
GL_EXTENSION(15)

GL_EXTENSION(16)
GL_EXTENSION(17)
GL_EXTENSION(18)
GL_EXTENSION(19)
GL_EXTENSION(20)
GL_EXTENSION(21)
GL_EXTENSION(22)
GL_EXTENSION(23)
GL_EXTENSION(24)
GL_EXTENSION(25)
GL_EXTENSION(26)
GL_EXTENSION(27)
GL_EXTENSION(28)
GL_EXTENSION(29)
GL_EXTENSION(30)
GL_EXTENSION(31)

GL_EXTENSION(32)
GL_EXTENSION(33)
GL_EXTENSION(34)
GL_EXTENSION(35)
GL_EXTENSION(36)
GL_EXTENSION(37)
GL_EXTENSION(38)
GL_EXTENSION(39)
GL_EXTENSION(40)
GL_EXTENSION(41)
GL_EXTENSION(42)
GL_EXTENSION(43)
GL_EXTENSION(44)
GL_EXTENSION(45)
GL_EXTENSION(46)
GL_EXTENSION(47)

GL_EXTENSION(48)
GL_EXTENSION(49)
GL_EXTENSION(50)
GL_EXTENSION(51)
GL_EXTENSION(52)
GL_EXTENSION(53)
GL_EXTENSION(54)
GL_EXTENSION(55)
GL_EXTENSION(56)
GL_EXTENSION(57)
GL_EXTENSION(58)
GL_EXTENSION(59)
GL_EXTENSION(60)
GL_EXTENSION(61)
GL_EXTENSION(62)
GL_EXTENSION(63)

extern const __eglMustCastToProperFunctionPointerType gExtensionForwarders[MAX_NUMBER_OF_GL_EXTENSIONS] = {
     GL_EXTENSION_NAME(0),  GL_EXTENSION_NAME(1),  GL_EXTENSION_NAME(2),  GL_EXTENSION_NAME(3),
     GL_EXTENSION_NAME(4),  GL_EXTENSION_NAME(5),  GL_EXTENSION_NAME(6),  GL_EXTENSION_NAME(7),
     GL_EXTENSION_NAME(8),  GL_EXTENSION_NAME(9),  GL_EXTENSION_NAME(10), GL_EXTENSION_NAME(11),
     GL_EXTENSION_NAME(12), GL_EXTENSION_NAME(13), GL_EXTENSION_NAME(14), GL_EXTENSION_NAME(15),
     GL_EXTENSION_NAME(16), GL_EXTENSION_NAME(17), GL_EXTENSION_NAME(18), GL_EXTENSION_NAME(19),
     GL_EXTENSION_NAME(20), GL_EXTENSION_NAME(21), GL_EXTENSION_NAME(22), GL_EXTENSION_NAME(23),
     GL_EXTENSION_NAME(24), GL_EXTENSION_NAME(25), GL_EXTENSION_NAME(26), GL_EXTENSION_NAME(27),
     GL_EXTENSION_NAME(28), GL_EXTENSION_NAME(29), GL_EXTENSION_NAME(30), GL_EXTENSION_NAME(31),
     GL_EXTENSION_NAME(32), GL_EXTENSION_NAME(33), GL_EXTENSION_NAME(34), GL_EXTENSION_NAME(35),
     GL_EXTENSION_NAME(36), GL_EXTENSION_NAME(37), GL_EXTENSION_NAME(38), GL_EXTENSION_NAME(39),
     GL_EXTENSION_NAME(40), GL_EXTENSION_NAME(41), GL_EXTENSION_NAME(42), GL_EXTENSION_NAME(43),
     GL_EXTENSION_NAME(44), GL_EXTENSION_NAME(45), GL_EXTENSION_NAME(46), GL_EXTENSION_NAME(47),
     GL_EXTENSION_NAME(48), GL_EXTENSION_NAME(49), GL_EXTENSION_NAME(50), GL_EXTENSION_NAME(51),
     GL_EXTENSION_NAME(52), GL_EXTENSION_NAME(53), GL_EXTENSION_NAME(54), GL_EXTENSION_NAME(55),
     GL_EXTENSION_NAME(56), GL_EXTENSION_NAME(57), GL_EXTENSION_NAME(58), GL_EXTENSION_NAME(59),
     GL_EXTENSION_NAME(60), GL_EXTENSION_NAME(61), GL_EXTENSION_NAME(62), GL_EXTENSION_NAME(63)
 };

#undef GL_EXTENSION_NAME
#undef GL_EXTENSION
#undef API_ENTRY
#undef CALL_GL_API

// ----------------------------------------------------------------------------
}; // namespace android
// ----------------------------------------------------------------------------

