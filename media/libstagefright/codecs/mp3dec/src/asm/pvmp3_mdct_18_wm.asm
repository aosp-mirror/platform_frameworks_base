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
;   Filename: pvmp3_dct_18.s
;
;------------------------------------------------------------------------------
; REVISION HISTORY
;
;
; Who:                                   Date: MM/DD/YYYY
; Description: 
;
;------------------------------------------------------------------------------

        EXPORT |pvmp3_mdct_18|

        IMPORT pvmp3_dct_9


;------------------------------------------------------------------------------

 AREA |.text|, CODE, READONLY, ALIGN=2


;------------------------------------------------------------------------------

|pvmp3_mdct_18| PROC
        stmfd    sp!,{r4-r10,lr}
        mov      r7,r2
        ldr      r2,table
        mov      r6,r1
        add      r3,r2,#0x24
        add      r12,r3,#0x44
        add      r1,r0,#0x44
        mov      r5,r0

;    for ( i=9; i!=0; i--)
;    {

        mov      r4,#9
Loop_1

;       tmp  = *(pt_vec);
;		tmp1 = *(pt_vec_o);

        ldr      lr,[r0]		;; tmp  == lr
        ldr      r8,[r3],#4		;; tmp1 == r8

;        tmp  = fxp_mul32_Q32( tmp<<1,  *(pt_cos++  ));
;        tmp1 = fxp_mul32_Q27( tmp1, *(pt_cos_x--));

        mov      lr,lr,lsl #1
        smull    r10,lr,r8,lr
        ldr      r8,[r12],#-4
        ldr      r9,[r1]
        subs     r4,r4,#1
        smull    r9,r10,r8,r9
        mov      r8,r9,lsr #27
        add      r8,r8,r10,lsl #5

;        *(pt_vec++)   =   tmp + tmp1 ;
;        *(pt_vec_o--) = fxp_mul32_Q28( (tmp - tmp1), *(pt_cos_split++));

        add      r9,lr,r8
        sub      r8,lr,r8
        ldr      lr,[r2],#4
        str      r9,[r0],#4
        smull    r8,r9,lr,r8
        mov      lr,r8,lsr #28
        add      lr,lr,r9,lsl #4
        str      lr,[r1],#-4
        bne      Loop_1

;		}

        mov      r0,r5			;; r0 = vec
        bl       pvmp3_dct_9
        add      r0,r5,#0x24	;; r0 = &vec[9]
        bl       pvmp3_dct_9

        ldr      r0,[r5,#0x20]
        ldr      r2,[r5,#0x40]
        str      r0,[r5,#0x40]
        ldr      r0,[r5,#0x1c]
        ldr      r3,[r5,#0x38]
        str      r0,[r5,#0x38]
        ldr      r1,[r5,#0x18]
        ldr      r0,[r5,#0x30]
        str      r1,[r5,#0x30]
        ldr      r12,[r5,#0x14]
        ldr      r1,[r5,#0x28]
        str      r12,[r5,#0x28]
        ldr      r12,[r5,#0x10]
        str      r12,[r5,#0x20]
        ldr      r12,[r5,#0xc]
        str      r12,[r5,#0x18]
        ldr      r12,[r5,#8]
        str      r12,[r5,#0x10]
        ldr      r12,[r5,#4]
        str      r12,[r5,#8]
        ldr      r12,[r5,#0x24]
        sub      r12,r12,r1
        str      r12,[r5,#4]
        ldr      r12,[r5,#0x2c]
        sub      r1,r12,r1
        str      r1,[r5,#0xc]
        sub      r1,r12,r0
        str      r1,[r5,#0x14]
        ldr      r1,[r5,#0x34]
        sub      r0,r1,r0
        str      r0,[r5,#0x1c]
        sub      r0,r1,r3
        str      r0,[r5,#0x24]
        ldr      r1,[r5,#0x3c]
        sub      r3,r1,r3
        sub      r1,r1,r2
        str      r1,[r5,#0x34]
        str      r3,[r5,#0x2c]
        ldr      r1,[r5,#0x44]
        sub      r1,r1,r2
        str      r1,[r5,#0x3c]
        ldr      r12,[r5,#0]

Loop_2
        add      r1,r5,r4,lsl #2
        ldr      r2,[r1,#0x28]
        ldr      r3,[r6,r4,lsl #2]
        add      r0,r0,r2
        str      r0,[r1,#0x28]
        ldr      lr,[r7,r4,lsl #2]
        ldr      r1,[r1,#4]
        smlal    r0,r3,lr,r0
        mov      r0,r2
        add      r2,r12,r1
        rsb      r2,r2,#0
        str      r3,[r5,r4,lsl #2]
        str      r2,[r6,r4,lsl #2]
        add      r4,r4,#1
        cmp      r4,#6
        mov      r12,r1

        blt      Loop_2

        ldr      r1,[r5,#0x40]
        ldr      r2,[r6,#0x18]
        add      r3,r0,r1
        str      r3,[r5,#0x40]
        ldr      lr,[r7,r4,lsl #2]
        mov      r3,r3,lsl #1
        ldr      r0,[r5,#0x1c]
        smlal    r3,r2,lr,r3
        add      r3,r12,r0
        str      r2,[r5,#0x18]
        ldr      r2,[r6,#0x1c]
        rsb      r3,r3,#0
        str      r3,[r6,#0x18]
        ldr      r3,[r5,#0x20]
        add      r0,r3,r0
        rsb      r0,r0,#0
        str      r0,[r6,#0x1c]
        ldr      r3,[r5,#0x44]
        ldr      r0,[r6,#0x20]
        add      r3,r3,r1
        mov      r1,r2
        ldr      r10,[r7,#0x1c]
        mov      r2,r3,lsl #1
        smlal    r12,r1,r10,r2
        str      r1,[r5,#0x1c]
        ldr      r1,[r5,#0x20]
        ldr      r3,[r5,#0x24]
        add      r1,r1,r3
        rsb      r1,r1,#0
        str      r1,[r6,#0x20]
        ldr      r1,[r5,#0x44]
        ldr      r3,[r7,#0x20]
        mov      r1,r1,lsl #1
        smlal    r12,r0,r3,r1
        ldr      lr,[r7,#0x24]
        ldr      r3,[r6,#0x24]
        str      r0,[r5,#0x20]
        smlal    r1,r3,lr,r1
        ldr      r0,[r6,#0x40]
        ldr      r12,[r6,#0x44]
        str      r3,[r5,#0x24]
        ldr      r1,[r5,#0x28]
        ldr      r3,[r7,#0x44]
        mov      r1,r1,lsl #1
        smlal    r1,r12,r3,r1
        ldr      r1,[r5,#0x40]
        str      r12,[r5,#0x44]
        rsb      r8,r1,#0
        str      r8,[r5,#0x28]
        ldr      r1,[r5,#0x2c]
        ldr      r3,[r7,#0x40]
        mov      r1,r1,lsl #1
        smlal    r1,r0,r3,r1
        str      r0,[r5,#0x40]
        ldr      r0,[r5,#0x3c]
        ldr      r1,[r6,#0x38]
        ldr      r3,[r6,#0x3c]
        rsb      r9,r0,#0
        str      r9,[r5,#0x2c]
        ldr      r0,[r5,#0x30]
        ldr      r12,[r7,#0x3c]
        mov      r0,r0,lsl #1
        smlal    r0,r3,r12,r0
        str      r3,[r5,#0x3c]
        ldr      r0,[r5,#0x38]
        rsb      r0,r0,#0
        str      r0,[r5,#0x30]
        ldr      r3,[r5,#0x34]
        ldr      r12,[r7,#0x38]
        mov      r3,r3,lsl #1
        smlal    r3,r1,r12,r3
        mov      r0,r0,lsl #1
        str      r1,[r5,#0x38]
        ldr      r4,[r7,#0x34]
        ldr      r1,[r6,#0x34]
        ldr      r3,[r6,#0x30]
        smlal    r0,r1,r4,r0
        ldr      r12,[r6,#0x2c]
        ldr      lr,[r6,#0x28]
        str      r1,[r5,#0x34]
        ldr      r1,[r7,#0x30]
        mov      r0,r9,lsl #1
        smlal    r0,r3,r1,r0
        mov      r0,r8,lsl #1
        ldr      r1,[r7,#0x2c]
        str      r3,[r5,#0x30]
        smlal    r0,r12,r1,r0
        ldr      r0,[r7,#0x28]
        str      r12,[r5,#0x2c]
        smlal    r2,lr,r0,r2
        str      lr,[r5,#0x28]
        ldr      r1,[r6,#4]
        ldr      r12,[r7,#0x48]
        mov      r2,r1,lsl #1
        ldr      r1,[r6,#0x20]
        ldr      r0,[r6]
        mov      r1,r1,lsl #1
        smull    r4,lr,r12,r1
        ldr      r3,[r6,#0x1c]
        str      lr,[r6]
        ldr      r12,[r7,#0x4c]
        mov      r3,r3,lsl #1
        smull    r4,lr,r12,r3
        mov      r0,r0,lsl #1
        ldr      r12,[r7,#0x64]
        str      lr,[r6,#4]
        smull    r4,lr,r12,r2
        ldr      r12,[r7,#0x68]
        str      lr,[r6,#0x1c]
        smull    r4,lr,r12,r0
        ldr      r12,[r7,#0x6c]
        str      lr,[r6,#0x20]
        smull    lr,r0,r12,r0
        ldr      r12,[r7,#0x70]
        str      r0,[r6,#0x24]
        smull    r0,r2,r12,r2
        ldr      r0,[r7,#0x88]
        str      r2,[r6,#0x28]
        smull    r3,r2,r0,r3
        ldr      r0,[r7,#0x8c]
        str      r2,[r6,#0x40]
        smull    r2,r1,r0,r1
        str      r1,[r6,#0x44]
        ldr      r0,[r6,#0x18]
        ldr      lr,[r7,#0x50]
        mov      r1,r0,lsl #1
        ldr      r0,[r6,#0x14]
        smull    r5,r4,lr,r1
        ldr      r12,[r6,#0x10]
        mov      r3,r0,lsl #1
        ldr      r0,[r6,#0xc]
        mov      r12,r12,lsl #1
        mov      r2,r0,lsl #1
        ldr      r0,[r6,#8]
        str      r4,[r6,#8]
        ldr      lr,[r7,#0x54]
        mov      r0,r0,lsl #1
        smull    r5,r4,lr,r3
        ldr      lr,[r7,#0x58]
        str      r4,[r6,#0xc]
        smull    r5,r4,lr,r12
        ldr      lr,[r7,#0x5c]
        str      r4,[r6,#0x10]
        smull    r5,r4,lr,r2
        ldr      lr,[r7,#0x60]
        str      r4,[r6,#0x14]
        smull    r5,r4,lr,r0
        ldr      lr,[r7,#0x74]
        str      r4,[r6,#0x18]
        smull    r4,r0,lr,r0
        ldr      lr,[r7,#0x78]
        str      r0,[r6,#0x2c]
        smull    r0,r2,lr,r2
        ldr      r0,[r7,#0x7c]
        str      r2,[r6,#0x30]
        smull    r12,r2,r0,r12
        ldr      r0,[r7,#0x80]
        str      r2,[r6,#0x34]
        smull    r3,r2,r0,r3
        ldr      r0,[r7,#0x84]
        str      r2,[r6,#0x38]
        smull    r2,r1,r0,r1
        str      r1,[r6,#0x3c]
        ldmfd    sp!,{r4-r10,pc}
table
        DCD      cosTerms_dct18
        ENDP

;------------------------------------------------------------------------------

 AREA |.constdata|, DATA, READONLY, ALIGN=2

;------------------------------------------------------------------------------

cosTerms_dct18
        DCD      0x0807d2b0
        DCD      0x08483ee0
        DCD      0x08d3b7d0
        DCD      0x09c42570
        DCD      0x0b504f30
        DCD      0x0df29440
        DCD      0x12edfb20
        DCD      0x1ee8dd40
        DCD      0x5bca2a00
cosTerms_1_ov_cos_phi
        DCD      0x400f9c00
        DCD      0x408d6080
        DCD      0x418dcb80
        DCD      0x431b1a00
        DCD      0x4545ea00
        DCD      0x48270680
        DCD      0x4be25480
        DCD      0x50ab9480
        DCD      0x56ce4d80
        DCD      0x05ebb630
        DCD      0x06921a98
        DCD      0x0771d3a8
        DCD      0x08a9a830
        DCD      0x0a73d750
        DCD      0x0d4d5260
        DCD      0x127b1ca0
        DCD      0x1ea52b40
        DCD      0x5bb3cc80



        END
