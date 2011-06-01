@
@ Copyright (C) 2009 The Android Open Source Project
@
@ Licensed under the Apache License, Version 2.0 (the "License");
@ you may not use this file except in compliance with the License.
@ You may obtain a copy of the License at
@
@      http://www.apache.org/licenses/LICENSE-2.0
@
@ Unless required by applicable law or agreed to in writing, software
@ distributed under the License is distributed on an "AS IS" BASIS,
@ WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@ See the License for the specific language governing permissions and
@ limitations under the License.
@




    .macro REQUIRE8
    .eabi_attribute 24, 1
    .endm

    .macro PRESERVE8
    .eabi_attribute 25, 1
    .endm


    .macro function name, export=0
.if \export
    .global \name
.endif
    .type   \name, %function
    .func   \name
\name:
    .endm

    .macro endfunction
    .endfunc
    .endm

