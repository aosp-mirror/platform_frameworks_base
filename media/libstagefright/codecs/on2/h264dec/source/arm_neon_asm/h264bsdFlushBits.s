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

    EXPORT h264bsdFlushBits

; Input / output registers
pStrmData       RN  0
numBits         RN  1
readBits        RN  2
strmBuffSize    RN  3
pStrmBuffStart  RN  1
pStrmCurrPos    RN  2
bitPosInWord    RN  1

; -- NEON registers --



;/*------------------------------------------------------------------------------
;
;    Function: h264bsdFlushBits
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

h264bsdFlushBits
;//    PUSH     {r4-r6,lr}

    LDR readBits, [pStrmData, #0x10]
    LDR strmBuffSize, [pStrmData, #0xC]

    ADD readBits, readBits, numBits
    AND bitPosInWord, readBits, #7

    STR readBits, [pStrmData, #0x10]
    STR bitPosInWord, [pStrmData, #0x8]

    LDR pStrmBuffStart, [pStrmData, #0x0]

    CMP readBits, strmBuffSize, LSL #3

    BHI end_of_stream

    ADD pStrmCurrPos, pStrmBuffStart, readBits, LSR #3
    STR pStrmCurrPos, [pStrmData, #0x4]
    MOV r0, #0
    BX  lr
;//    POP      {r4-r6,pc}

end_of_stream
    MVN r0, #0
    BX  lr
;//    POP      {r4-r6,pc}

    END


