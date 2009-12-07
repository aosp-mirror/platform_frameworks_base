; ------------------------------------------------------------------
; Copyright (C) 1998-2009 PacketVideo
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
;      http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
; express or implied.
; See the License for the specific language governing permissions
; and limitations under the License.
; -------------------------------------------------------------------

;
;
;   Filename: pvmp3_dct_9.s
;
;------------------------------------------------------------------------------
; REVISION HISTORY
;
;
; Who:                                   Date: MM/DD/YYYY
; Description: 
;
;------------------------------------------------------------------------------

  AREA  |.drectve|, DRECTVE

    DCB "-defaultlib:coredll.lib "
    DCB "-defaultlib:corelibc.lib "

  IMPORT pvmp3_mdct_18 ; pvmp3_mdct_18.cpp

;------------------------------------------------------------------------------

  AREA  |.rdata|, DATA, READONLY
  % 4


;------------------------------------------------------------------------------

  AREA  |.text|, CODE, READONLY


;------------------------------------------------------------------------------

 EXPORT |pvmp3_dct_9|

|pvmp3_dct_9| PROC
        stmfd    sp!,{r4-r10,lr}
        ldr      r2, [r0, #0x20]
        ldr      r3, [r0]
        ldr      r12,[r0, #4]
        add      r1,r2,r3
        sub      lr,r2,r3
        ldr      r3,[r0, #0x1c]
        ldr      r4,[r0, #0x18]
        add      r2,r3,r12
        ldr      r5,[r0,#8]
        sub      r3,r3,r12
        add      r12,r4,r5
        sub      r4,r4,r5
        ldr      r5,[r0, #0x14]
        ldr      r7,[r0, #0xc]
        ldr      r9,[r0, #0x10]
        add      r6,r5,r7
        sub      r5,r5,r7
        add      r7,r1,r12
        add      r8,r9,r2
        add      r7,r7,r6
        add      r10,r7,r8
        rsb      r7,r8,r7,asr #1
        str      r7,[r0, #0x18]
        rsb      r2,r9,r2,asr #1
        str      r10,[r0]
        ldr      r11,|cos_2pi_9|
        rsb      r7,r2,#0

        mov      r9,r1,lsl #1
		mov      r1,r9			;;;;;;  !!!!!!
        mov      r8,r7

;    vec[4]  = fxp_mac32_Q32( vec[4], tmp0<<1, cos_2pi_9); 

        smlal    r1,r8,r11,r9
        ldr      r10,|cos_4pi_9|
        ldr      r11,|cos_pi_9|

;    vec[8]  = fxp_mac32_Q32( vec[8], tmp0<<1, cos_4pi_9);

        smlal    r1,r7,r10,r9



;    vec[2]  = fxp_mac32_Q32( vec[2], tmp0<<1, cos_pi_9);

        smlal    r9,r2,r11,r9
        mov      r1,r12,lsl #1
        rsb      r9,r10,#0
        ldr      r11,|cos_5pi_9|

        smlal    r12,r2,r9,r1



;    vec[2]  = fxp_mac32_Q32( vec[2], tmp2<<1, cos_5pi_9);

        ldr      r9,|cos_2pi_9|
        mov      r12,r1			;;;;;;  !!!!!!
        smlal    r12,r8,r11,r1


;    vec[8]  = fxp_mac32_Q32( vec[8], tmp2<<1, cos_2pi_9);

        smlal    r1,r7,r9,r1
        mov      r1,r6,lsl #1
        smlal    r12,r7,r11,r1
        and      r6,r10,r11,asr #14
        smlal    r12,r8,r6,r1
        ldr      r10,|cos_11pi_18|
        add      r12,r11,r6
        smlal    r1,r2,r12,r1
        ldr      r9,|cos_8pi_9|
        str      r2,[r0,#8]
        mov      r1,r5,lsl #1

;    vec[8]  = fxp_mac32_Q32( vec[8], tmp3<<1, cos_8pi_9);

        smull    r2,r6,r9,r1
        str      r7,[r0,#0x20]
        mov      r2,r4,lsl #1
        ldr      r7,|cos_13pi_18|
        smlal    r12,r6,r10,r2

        mov      r3,r3,lsl #1

;    vec[5]  = fxp_mac32_Q32( vec[5], tmp8<<1, cos_13pi_18);

        smlal    r12,r6,r7,r3
        add      r4,r5,r4
        mov      r12,lr,lsl #1
        sub      lr,r4,lr
        ldr      r7,|cos_17pi_18|
        str      r8,[r0, #0x10]
        ldr      r4,|cos_pi_6|

        mov      lr,lr,lsl #1

;    vec[1]  = fxp_mac32_Q32( vec[1], tmp8<<1, cos_17pi_18);

        smlal    r8,r6,r7,r12

;    vec[3]  = fxp_mul32_Q32((tmp5 + tmp6  - tmp8)<<1, cos_pi_6);

        smull    r5,lr,r4,lr
        str      r6,[r0, #4]
        str      lr,[r0, #0xc]


;    vec[5]  = fxp_mul32_Q32(tmp5<<1, cos_17pi_18);
        smull    r5,lr,r7,r1
        rsb      r6,r9,#0
;    vec[5]  = fxp_mac32_Q32( vec[5], tmp6<<1,  cos_7pi_18);
        smlal    r5,lr,r6,r2
;    vec[5]  = fxp_mac32_Q32( vec[5], tmp7<<1,    cos_pi_6);
        smlal    r5,lr,r4,r3
;    vec[5]  = fxp_mac32_Q32( vec[5], tmp8<<1, cos_13pi_18);
        smlal    r5,lr,r10,r12
        str      lr,[r0, #0x14]
        rsb      lr,r10,#0

;    vec[7]  = fxp_mul32_Q32(tmp5<<1, cos_5pi_18);
        smull    r5,r1,lr,r1
;    vec[7]  = fxp_mac32_Q32( vec[7], tmp6<<1, cos_17pi_18);
        smlal    r2,r1,r7,r2
;    vec[7]  = fxp_mac32_Q32( vec[7], tmp7<<1,    cos_pi_6);
        smlal    r3,r1,r4,r3
;    vec[7]  = fxp_mac32_Q32( vec[7], tmp8<<1, cos_11pi_18);
        smlal    r12,r1,r9,r12
        str      r1,[r0, #0x1c]
        ldmfd    sp!,{r4-r10,pc}
|cos_2pi_9|
        DCD      0x620dbe80
|cos_4pi_9|
        DCD      0x163a1a80
|cos_pi_9|
        DCD      0x7847d900
|cos_5pi_9|
        DCD      0x87b82700
|cos_8pi_9|
        DCD      0xd438af00
|cos_11pi_18|
        DCD      0xadb92280
|cos_13pi_18|
        DCD      0x91261480
|cos_17pi_18|
        DCD      0x81f1d200
|cos_pi_6|
        DCD      0x6ed9eb80
        ENDP





        END
