;
; Copyright (C) 2009 The Android Open Source Project
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;      http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.
;

    REQUIRE8
    PRESERVE8

    AREA    |.text|, CODE

    EXPORT h264bsdClearMbLayer

; Input / output registers
pMbLayer    RN  0
size        RN  1
pTmp        RN  2
step        RN  3

; -- NEON registers --

qZero   QN  Q0.U8

;/*------------------------------------------------------------------------------
;
;    Function: h264bsdClearMbLayer
;
;        Functional description:
;
;        Inputs:
;
;        Outputs:
;
;        Returns:
;
;------------------------------------------------------------------------------*/

h264bsdClearMbLayer

    VMOV    qZero, #0
    ADD     pTmp, pMbLayer, #16
    MOV     step, #32
    SUBS    size, size, #64

loop
    VST1    qZero, [pMbLayer], step
    SUBS    size, size, #64
    VST1    qZero, [pTmp], step
    VST1    qZero, [pMbLayer], step
    VST1    qZero, [pTmp], step
    BCS     loop

    BX      lr
    END


