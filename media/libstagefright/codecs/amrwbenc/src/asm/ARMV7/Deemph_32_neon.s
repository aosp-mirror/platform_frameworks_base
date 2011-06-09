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
@
@void Deemph_32(
@     Word16 x_hi[],                        /* (i)     : input signal (bit31..16) */
@     Word16 x_lo[],                        /* (i)     : input signal (bit15..4)  */
@     Word16 y[],                           /* (o)     : output signal (x16)      */
@     Word16 mu,                            /* (i) Q15 : deemphasis factor        */
@     Word16 L,                             /* (i)     : vector size              */
@     Word16 * mem                          /* (i/o)   : memory (y[-1])           */
@     )

@x_hi     RN      R0
@x_lo     RN      R1
@y[]      RN      R2
@*mem     RN      R3

           .section  .text
           .global   Deemph_32_asm
	   
Deemph_32_asm:

           STMFD   	r13!, {r4 - r12, r14} 
	   MOV          r4, #2                   @i=0
	   LDRSH        r6, [r0], #2             @load x_hi[0]
	   LDRSH        r7, [r1], #2             @load x_lo[0]
	   LDR          r5, =22282               @r5---mu
	   MOV          r11, #0x8000

           @y[0]
	   MOV          r10, r6, LSL #16         @L_tmp = x_hi[0]<<16
	   MOV          r8,  r5, ASR #1          @fac = mu >> 1
	   LDR          r5,  [r3]
	   ADD          r12, r10, r7, LSL #4     @L_tmp += x_lo[0] << 4
	   MOV          r10, r12, LSL #3         @L_tmp <<= 3
	   MUL          r9, r5, r8
	   LDRSH        r6, [r0], #2             @load x_hi[1] 
	   QDADD        r10, r10, r9
	   LDRSH        r7, [r1], #2             @load x_lo[1]  
	   MOV          r12, r10, LSL #1         @L_tmp = L_mac(L_tmp, *mem, fac)
	   QADD         r10, r12, r11
	   MOV          r14, r10, ASR #16        @y[0] = round(L_tmp)


	   MOV          r10, r6, LSL #16
	   ADD          r12, r10, r7, LSL #4
           STRH         r14, [r2], #2            @update y[0]
	   MOV          r10, r12, LSL #3
	   MUL          r9, r14, r8
	   QDADD        r10, r10, r9
	   MOV          r12, r10, LSL #1
	   QADD         r10, r12, r11
	   MOV          r14, r10, ASR #16        @y[1] = round(L_tmp)

LOOP:
           LDRSH        r6, [r0], #2             @load x_hi[]
	   LDRSH        r7, [r1], #2
	   STRH         r14, [r2], #2
	   MOV          r10, r6, LSL #16
	   ADD          r12, r10, r7, LSL #4
	   MUL          r9, r14, r8
	   MOV          r10, r12, LSL #3
	   QDADD        r10, r10, r9
           LDRSH        r6, [r0], #2             @load x_hi[]
	   MOV          r12, r10, LSL #1
	   QADD         r10, r12, r11
	   LDRSH        r7, [r1], #2
	   MOV          r14, r10, ASR #16

	   MOV          r10, r6, LSL #16
	   ADD          r12, r10, r7, LSL #4
	   STRH         r14, [r2], #2
	   MUL          r9, r14, r8
	   MOV          r10, r12, LSL #3
	   QDADD        r10, r10, r9
           ADD          r4, r4, #2
	   MOV          r12, r10, LSL #1
	   QADD         r10, r12, r11
           CMP          r4, #64
	   MOV          r14, r10, ASR #16

           BLT          LOOP
           STR          r14, [r3]
           STRH         r14, [r2]	   

           LDMFD   	r13!, {r4 - r12, r15} 

	   .END

