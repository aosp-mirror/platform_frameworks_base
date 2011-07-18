@/*
@ ** Copyright 2003-2010, VisualOn, Inc.
@ **
@ ** Licensed under the Apache License, Version 2.0 (the "License");
@ ** you may not use this file except in compliance with the License.
@ ** You may obtain a copy of the License at
@ **
@ **     http://www.apache.org/licenses/LICENSE-2.0
@ **
@ ** Unless required by applicable law or agreed to in writing, software
@ ** distributed under the License is distributed on an "AS IS" BASIS,
@ ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@ ** See the License for the specific language governing permissions and
@ ** limitations under the License.
@ */


@*void Convolve (
@*    Word16 x[],        /* (i)     : input vector                           */
@*    Word16 h[],        /* (i)     : impulse response                       */
@*    Word16 y[],        /* (o)     : output vector                          */
@*    Word16 L           /* (i)     : vector size                            */
@*)
@  r0 --- x[]
@  r1 --- h[]
@  r2 --- y[]
@  r3 --- L

	.section  .text
        .global   Convolve_asm

Convolve_asm:

        STMFD          r13!, {r4 - r12, r14}
        MOV            r3,  #0                           @ n
	MOV            r11, #0x8000

LOOP:
        ADD            r4, r1, r3, LSL #1                @ tmpH address
        ADD            r5, r3, #1                        @ i = n + 1
        MOV            r6, r0                            @ tmpX = x
        LDRSH          r9,  [r6], #2                     @ *tmpX++
        LDRSH          r10, [r4], #-2                    @ *tmpH--
        SUB            r5, r5, #1
        MUL            r8,  r9, r10

LOOP1:
        CMP            r5, #0
        BLE            L1
	LDRSH          r9,  [r6], #2                     @ *tmpX++
	LDRSH          r10, [r4], #-2                    @ *tmpH--
	LDRSH          r12, [r6], #2                     @ *tmpX++
	LDRSH          r14, [r4], #-2                    @ *tmpH--
	MLA            r8, r9, r10, r8
	MLA            r8, r12, r14, r8
	LDRSH          r9,  [r6], #2                     @ *tmpX++
	LDRSH          r10, [r4], #-2                    @ *tmpH--
	LDRSH          r12, [r6], #2                     @ *tmpX++
	LDRSH          r14, [r4], #-2                    @ *tmpH--
	MLA            r8, r9, r10, r8
        SUBS           r5, r5, #4
	MLA            r8, r12, r14, r8

        B              LOOP1

L1:

        ADD            r5, r11, r8, LSL #1
        MOV            r5, r5, LSR #16                   @extract_h(s)
        ADD            r3, r3, #1
        STRH           r5, [r2], #2                      @y[n]


        ADD            r4, r1, r3, LSL #1                @tmpH address
        ADD            r5, r3, #1
        MOV            r6, r0
        LDRSH          r9,  [r6], #2                     @ *tmpX++
        LDRSH          r10, [r4], #-2
        LDRSH          r12, [r6], #2
        LDRSH          r14, [r4], #-2

        MUL            r8, r9, r10
        SUB            r5, r5, #2
        MLA            r8, r12, r14, r8

LOOP2:
        CMP            r5, #0
        BLE            L2
	LDRSH          r9,  [r6], #2                     @ *tmpX++
	LDRSH          r10, [r4], #-2                    @ *tmpH--
	LDRSH          r12, [r6], #2                     @ *tmpX++
	LDRSH          r14, [r4], #-2                    @ *tmpH--
	MLA            r8, r9, r10, r8
	MLA            r8, r12, r14, r8
	LDRSH          r9,  [r6], #2                     @ *tmpX++
	LDRSH          r10, [r4], #-2                    @ *tmpH--
	LDRSH          r12, [r6], #2                     @ *tmpX++
	LDRSH          r14, [r4], #-2                    @ *tmpH--
	MLA            r8, r9, r10, r8
        SUBS           r5, r5, #4
	MLA            r8, r12, r14, r8
        B              LOOP2

L2:
        ADD            r8, r11, r8, LSL #1
        MOV            r8, r8, LSR #16                   @extract_h(s)
        ADD            r3, r3, #1
        STRH           r8, [r2], #2                      @y[n]

        ADD            r4, r1, r3, LSL #1
        ADD            r5, r3, #1
        MOV            r6, r0
        LDRSH          r9,  [r6], #2
        LDRSH          r10, [r4], #-2
        LDRSH          r12, [r6], #2
        LDRSH          r14, [r4], #-2
        MUL            r8, r9, r10
        LDRSH          r9,  [r6], #2
        LDRSH          r10, [r4], #-2
        MLA            r8, r12, r14, r8
        SUB            r5, r5, #3
        MLA            r8, r9, r10, r8

LOOP3:
        CMP            r5, #0
        BLE            L3
	LDRSH          r9,  [r6], #2                     @ *tmpX++
	LDRSH          r10, [r4], #-2                    @ *tmpH--
	LDRSH          r12, [r6], #2                     @ *tmpX++
	LDRSH          r14, [r4], #-2                    @ *tmpH--
	MLA            r8, r9, r10, r8
	MLA            r8, r12, r14, r8
	LDRSH          r9,  [r6], #2                     @ *tmpX++
	LDRSH          r10, [r4], #-2                    @ *tmpH--
	LDRSH          r12, [r6], #2                     @ *tmpX++
	LDRSH          r14, [r4], #-2                    @ *tmpH--
	MLA            r8, r9, r10, r8
        SUBS           r5, r5, #4
	MLA            r8, r12, r14, r8
        B              LOOP3

L3:
        ADD            r8, r11, r8, LSL #1
        MOV            r8, r8, LSR #16                   @extract_h(s)
        ADD            r3, r3, #1
        STRH           r8, [r2], #2                      @y[n]

        ADD            r5, r3, #1                        @ i = n + 1
        ADD            r4, r1, r3, LSL #1                @ tmpH address
        MOV            r6, r0
        MOV            r8, #0

LOOP4:
        CMP            r5, #0
        BLE            L4
	LDRSH          r9,  [r6], #2                     @ *tmpX++
	LDRSH          r10, [r4], #-2                    @ *tmpH--
	LDRSH          r12, [r6], #2                     @ *tmpX++
	LDRSH          r14, [r4], #-2                    @ *tmpH--
	MLA            r8, r9, r10, r8
	MLA            r8, r12, r14, r8
	LDRSH          r9,  [r6], #2                     @ *tmpX++
	LDRSH          r10, [r4], #-2                    @ *tmpH--
	LDRSH          r12, [r6], #2                     @ *tmpX++
	LDRSH          r14, [r4], #-2                    @ *tmpH--
	MLA            r8, r9, r10, r8
        SUBS           r5, r5, #4
	MLA            r8, r12, r14, r8
        B              LOOP4
L4:
        ADD            r5, r11, r8, LSL #1
        MOV            r5, r5, LSR #16                   @extract_h(s)
        ADD            r3, r3, #1
        STRH           r5, [r2], #2                      @y[n]

        CMP            r3, #64
        BLT            LOOP

Convolve_asm_end:

        LDMFD      r13!, {r4 - r12, r15}

        @ENDFUNC
        .END


