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
;   Filename: pvmp3_polyphase_filter_window.s
;
;------------------------------------------------------------------------------
; REVISION HISTORY
;
;
; Who:                                   Date: MM/DD/YYYY
; Description: 
;
;------------------------------------------------------------------------------

	CODE32

	AREA	|.drectve|, DRECTVE

	EXPORT	|pvmp3_polyphase_filter_window|
	IMPORT	|pqmfSynthWin|

	AREA	|.pdata|, PDATA

	AREA	|.text|, CODE, ARM

|pvmp3_polyphase_filter_window| PROC
        stmfd    sp!,{r0-r2,r4-r11,lr}

        sub      sp,sp,#4
        ldr      r2,[sp,#0xc]
        ldr      r1,PolyPh_filter_coeff
		
        sub      r2,r2,#1
        mov      r10,#1
        str      r2,[sp]

; Accumulators r9, r11::> Initialization

Loop_j
        mov      r9,  #0x20
        mov      r11, #0x20
        mov      r4,  #0x10
Loop_i
        add      r2,r4,r10
        add      r3,r0,r2,lsl #2
        sub      r2,r4,r10
        ldr      r5,[r3]
        ldr      lr,[r1]
        add      r12,r0,r2,lsl #2
        ldr      r6,[r12,#0x780]
        smlal    r2,r9,lr,r5
        smlal    r2,r11,lr,r6
        ldr      r2,[r1,#4]
        ldr      r7,[r12,#0x80]
        smlal    r5,r11,r2,r5
        smull    r6,r5,r2,r6
        sub      r9,r9,r5
        ldr      r5,[r1,#8]
        ldr      r8,[r3,#0x700]
        add      r4,r4,#0x200
        smlal    r6,r9,r5,r7
        smull    r6,r2,r5,r8
        ldr      r5,[r1,#0xc]
        sub      r11,r11,r2
        smlal    r8,r9,r5,r8
        smlal    r7,r11,r5,r7
        ldr      r5,[r3,#0x100]
        ldr      r2,[r1,#0x10]
        ldr      r6,[r12,#0x680]
        smlal    lr,r9,r2,r5
        smlal    lr,r11,r2,r6
        ldr      r2,[r1,#0x14]
        ldr      r7,[r12,#0x180]
        smlal    r5,r11,r2,r5
        smull    r6,r5,r2,r6
        ldr      r6,[r1,#0x18]
        ldr      r8,[r3,#0x600]
        sub      r9,r9,r5
        smlal    r5,r9,r6,r7
        smull    r2,r5,r6,r8
        ldr      r6,[r1,#0x1c]
        sub      r11,r11,r5
        smlal    r8,r9,r6,r8
        ldr      r2,[r1,#0x20]
        ldr      r5,[r3,#0x200]
        smlal    r7,r11,r6,r7
        ldr      r6,[r12,#0x580]
        smlal    lr,r9,r2,r5
        smlal    lr,r11,r2,r6
        ldr      r2,[r1,#0x24]
        ldr      r7,[r12,#0x280]
        smlal    r5,r11,r2,r5
        smull    r6,r5,r2,r6
        ldr      r6,[r1,#0x28]
        ldr      r8,[r3,#0x500]
        sub      r9,r9,r5
        smlal    r5,r9,r6,r7
        smull    r2,r5,r6,r8
        ldr      r6,[r1,#0x2c]
        sub      r11,r11,r5

        smlal    r8,r9,r6,r8
        smlal    r7,r11,r6,r7
        ldr      r5,[r3,#0x300]
        ldr      r8,[r1,#0x30]
        ldr      r6,[r12,#0x480]
        smlal    r7,r9,r8,r5
        smlal    r7,r11,r8,r6
        ldr      r8,[r1,#0x34]
        ldr      r12,[r12,#0x380]
        smlal    r5,r11,r8,r5
        smull    r6,r5,r8,r6
        ldr      r6,[r1,#0x38]


        ldr      r3,[r3,#0x400]
        sub      r9,r9,r5
        smlal    r7,r9,r6,r12
        smull    r8,r7,r6,r3
        cmp      r4,#0x210
        sub      r11,r11,r7

        ldr      r2,[r1,#0x3c]
        add      r1,r1,#0x40
        smlal    r3,r9,r2,r3
        smlal    r12,r11,r2,r12

        blt      Loop_i

        mov      r3,r9, asr #6
        mov      r4,r3, asr #15
        teq      r4,r3, asr #31
        ldr      r12,LOW_16BITS
        ldr      r2,[sp]
        eorne    r3,r12,r3,asr #31
        ldr      r4,[sp,#8]
        mov      r2,r10,lsl r2
        add      r4,r4,r2,lsl #1
        strh     r3,[r4]

        mov      r3,r11,asr #6
        mov      r4,r3,asr #15
        teq      r4,r3,asr #31
        eorne    r3,r12,r3,asr #31
        ldr      r12,[sp,#0xc]
        ldr      r11,[sp,#8]
        rsb      r2,r2,r12,lsl #5
        add      r2,r11,r2,lsl #1
        strh     r3,[r2]

        add      r10,r10,#1
        cmp      r10,#0x10
        blt      Loop_j

; Accumulators r4, r5 Initialization

        mov      r4,#0x20
        mov      r5,#0x20
        mov      r3,#0x10
PolyPh_filter_loop2
        add      r2,r0,r3,lsl #2
        ldr      r12,[r2]
        ldr      r8,[r1]
        ldr      r6,[r2,#0x80]
        smlal    r12,r4,r8,r12
        ldr      r12,[r1,#4]
        ldr      r7,[r2,#0x40]
        smlal    r6,r4,r12,r6

        ldr      r12,[r1,#8]
        ldr      r6,[r2,#0x180]
        smlal    r7,r5,r12,r7
        ldr      r12,[r2,#0x100]
        ldr      r7,[r1,#0xc]
        ldr      r2,[r2,#0x140]
        smlal    r12,r4,r7,r12
        ldr      r12,[r1,#0x10]
        add      r3,r3,#0x80
        smlal    r6,r4,r12,r6
        ldr      r6,[r1,#0x14]
        cmp      r3,#0x210
        smlal    r2,r5,r6,r2
        add      r1,r1,#0x18

        blt      PolyPh_filter_loop2
        mov      r0,r4,asr #6
        mov      r2,r0,asr #15
        teq      r2,r0,asr #31
        ldrne    r12,LOW_16BITS
        ldr      r1,[sp,#8]
        eorne    r0,r12,r0,asr #31
        strh     r0,[r1,#0]
        mov      r0,r5,asr #6
        mov      r2,r0,asr #15
        teq      r2,r0,asr #31
        ldrne    r12,LOW_16BITS
        ldr      r2,[sp]
        mov      r1,#0x10
        eorne    r0,r12,r0,asr #31
        ldr      r12,[sp,#8]
        mov      r1,r1,lsl r2
        add      r1,r12,r1,lsl #1
        strh     r0,[r1]
        add      sp,sp,#0x10
        ldmfd    sp!,{r4-r11,pc}


PolyPh_filter_coeff
        DCD      pqmfSynthWin
LOW_16BITS
        DCD      0x00007fff
	
		ENDP  ; |pvmp3_polyphase_filter_window|
		END

