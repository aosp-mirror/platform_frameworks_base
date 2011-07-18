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
@void Pred_lt4(
@		  Word16 exc[],                         /* in/out: excitation buffer */
@		  Word16 T0,                            /* input : integer pitch lag */
@		  Word16 frac,                          /* input : fraction of lag   */
@		  Word16 L_subfr                        /* input : subframe size     */
@	      )

@******************************
@       ARM Register
@******************************
@ r0  ---  exc[]
@ r1  ---  T0
@ r2  ---  frac
@ r3  ---  L_subfr

         .section  .text
	 .global   pred_lt4_asm
	 .extern   inter4_2

pred_lt4_asm:

         STMFD     r13!, {r4 - r12, r14}
         RSB       r4, r1, #0                         @-T0
         RSB       r2, r2, #0                         @frac = -frac
         ADD       r5, r0, r4, LSL #1                 @x = exc - T0
         CMP       r2, #0
         ADDLT     r2, r2, #4                         @frac += UP_SAMP
         SUBLT     r5, r5, #2                         @x--
         SUB       r5, r5, #30                        @x -= 15
         RSB       r4, r2, #3                         @k = 3 - frac
         LDR       r6, Table
	 MOV       r8, r4, LSL #6
         @MOV       r7, #0                             @j = 0
         ADD       r8, r6, r8                         @ptr2 = &(inter4_2[k][0])

	 MOV       r1, r5
	 MOV       r5, #0x8000
	 MOV       r14, #21
@ used register
         @r0 --- exc[]  r1 --- x  r7 --- j  r8 --- ptr2  r5 --- 0x8000
THREE_LOOP:

         @MOV       r1, r5                             @ptr1 = x
	 MOV       r2, r8                             @ptr = ptr2
         LDR       r3, [r2], #4                       @h[0], h[1]
	 LDRSH     r4, [r1], #2                       @x[0]
	 LDRSH     r6, [r1], #2                       @x[1]
	 LDRSH     r9, [r1], #2                       @x[2]

	 SMULBB    r10, r4, r3                        @x[0] * h[0]
	 SMULBB    r11, r6, r3                        @x[1] * h[0]
	 SMULBB    r12, r9, r3                        @x[2] * h[0]

         LDRSH     r4, [r1], #2                       @x[3]
	 SMLABT    r10, r6, r3, r10                   @x[1] * h[1]
         SMLABT    r11, r9, r3, r11                   @x[2] * h[1]
	 SMLABT    r12, r4, r3, r12                   @x[3] * h[1]

	 LDR       r3, [r2], #4                       @h[2], h[3]
	 LDRSH     r6, [r1], #2                       @x[4]
	 SMLABB    r10, r9, r3, r10                   @x[2] * h[2]
         SMLABB    r11, r4, r3, r11                   @x[3] * h[2]
         SMLABB    r12, r6, r3, r12                   @x[4] * h[2]

         LDRSH     r9, [r1], #2                       @x[5]
         SMLABT    r10, r4, r3, r10                   @x[3] * h[3]
         SMLABT    r11, r6, r3, r11                   @x[4] * h[3]
         SMLABT    r12, r9, r3, r12                   @x[5] * h[3]

         LDR       r3, [r2], #4                       @h[4], h[5]
         LDRSH     r4, [r1], #2                       @x[6]
         SMLABB    r10, r6, r3, r10                   @x[4] * h[4]
         SMLABB    r11, r9, r3, r11                   @x[5] * h[4]
         SMLABB    r12, r4, r3, r12                   @x[6] * h[4]

	 LDRSH     r6, [r1], #2                       @x[7]
	 SMLABT    r10, r9, r3, r10                   @x[5] * h[5]
	 SMLABT    r11, r4, r3, r11                   @x[6] * h[5]
	 SMLABT    r12, r6, r3, r12                   @x[7] * h[5]

         LDR       r3, [r2], #4                       @h[6], h[7]
	 LDRSH     r9, [r1], #2                       @x[8]
	 SMLABB    r10, r4, r3, r10                   @x[6] * h[6]
	 SMLABB    r11, r6, r3, r11                   @x[7] * h[6]
	 SMLABB    r12, r9, r3, r12                   @x[8] * h[6]

	 LDRSH     r4, [r1], #2                       @x[9]
	 SMLABT    r10, r6, r3, r10                   @x[7] * h[7]
	 SMLABT    r11, r9, r3, r11                   @x[8] * h[7]
	 SMLABT    r12, r4, r3, r12                   @x[9] * h[7]

	 LDR       r3, [r2], #4                       @h[8], h[9]
	 LDRSH     r6, [r1], #2                       @x[10]
	 SMLABB    r10, r9, r3, r10                   @x[8] * h[8]
	 SMLABB    r11, r4, r3, r11                   @x[9] * h[8]
	 SMLABB    r12, r6, r3, r12                   @x[10] * h[8]

	 LDRSH     r9, [r1], #2                       @x[11]
	 SMLABT    r10, r4, r3, r10                   @x[9] * h[9]
	 SMLABT    r11, r6, r3, r11                   @x[10] * h[9]
	 SMLABT    r12, r9, r3, r12                   @x[11] * h[9]

         LDR       r3, [r2], #4                       @h[10], h[11]
	 LDRSH     r4, [r1], #2                       @x[12]
         SMLABB    r10, r6, r3, r10                   @x[10] * h[10]
	 SMLABB    r11, r9, r3, r11                   @x[11] * h[10]
	 SMLABB    r12, r4, r3, r12                   @x[12] * h[10]

	 LDRSH     r6, [r1], #2                       @x[13]
	 SMLABT    r10, r9, r3, r10                   @x[11] * h[11]
	 SMLABT    r11, r4, r3, r11                   @x[12] * h[11]
	 SMLABT    r12, r6, r3, r12                   @x[13] * h[11]

	 LDR       r3, [r2], #4                       @h[12], h[13]
	 LDRSH     r9, [r1], #2                       @x[14]
	 SMLABB    r10, r4, r3, r10                   @x[12] * h[12]
	 SMLABB    r11, r6, r3, r11                   @x[13] * h[12]
	 SMLABB    r12, r9, r3, r12                   @x[14] * h[12]

	 LDRSH     r4, [r1], #2                       @x[15]
	 SMLABT    r10, r6, r3, r10                   @x[13] * h[13]
	 SMLABT    r11, r9, r3, r11                   @x[14] * h[13]
	 SMLABT    r12, r4, r3, r12                   @x[15] * h[13]

	 LDR       r3, [r2], #4                       @h[14], h[15]
	 LDRSH     r6, [r1], #2                       @x[16]
	 SMLABB    r10, r9, r3, r10                   @x[14] * h[14]
	 SMLABB    r11, r4, r3, r11                   @x[15] * h[14]
	 SMLABB    r12, r6, r3, r12                   @x[16] * h[14]

	 LDRSH     r9, [r1], #2                       @x[17]
         SMLABT    r10, r4, r3, r10                   @x[15] * h[15]
	 SMLABT    r11, r6, r3, r11                   @x[16] * h[15]
	 SMLABT    r12, r9, r3, r12                   @x[17] * h[15]

	 LDR       r3, [r2], #4                       @h[16], h[17]
	 LDRSH     r4, [r1], #2                       @x[18]
	 SMLABB    r10, r6, r3, r10                   @x[16] * h[16]
	 SMLABB    r11, r9, r3, r11                   @x[17] * h[16]
	 SMLABB    r12, r4, r3, r12                   @x[18] * h[16]

         LDRSH     r6, [r1], #2                       @x[19]
	 SMLABT    r10, r9, r3, r10                   @x[17] * h[17]
	 SMLABT    r11, r4, r3, r11                   @x[18] * h[17]
	 SMLABT    r12, r6, r3, r12                   @x[19] * h[17]

	 LDR       r3, [r2], #4                       @h[18], h[19]
         LDRSH     r9, [r1], #2                       @x[20]
	 SMLABB    r10, r4, r3, r10                   @x[18] * h[18]
	 SMLABB    r11, r6, r3, r11                   @x[19] * h[18]
	 SMLABB    r12, r9, r3, r12                   @x[20] * h[18]

	 LDRSH     r4, [r1], #2                       @x[21]
	 SMLABT    r10, r6, r3, r10                   @x[19] * h[19]
	 SMLABT    r11, r9, r3, r11                   @x[20] * h[19]
	 SMLABT    r12, r4, r3, r12                   @x[21] * h[19]

	 LDR       r3, [r2], #4                       @h[20], h[21]
	 LDRSH     r6, [r1], #2                       @x[22]
	 SMLABB    r10, r9, r3, r10                   @x[20] * h[20]
	 SMLABB    r11, r4, r3, r11                   @x[21] * h[20]
	 SMLABB    r12, r6, r3, r12                   @x[22] * h[20]

	 LDRSH     r9, [r1], #2                       @x[23]
	 SMLABT    r10, r4, r3, r10                   @x[21] * h[21]
	 SMLABT    r11, r6, r3, r11                   @x[22] * h[21]
	 SMLABT    r12, r9, r3, r12                   @x[23] * h[21]

	 LDR       r3, [r2], #4                       @h[22], h[23]
	 LDRSH     r4, [r1], #2                       @x[24]
	 SMLABB    r10, r6, r3, r10                   @x[22] * h[22]
	 SMLABB    r11, r9, r3, r11                   @x[23] * h[22]
	 SMLABB    r12, r4, r3, r12                   @x[24] * h[22]

         LDRSH     r6, [r1], #2                       @x[25]
	 SMLABT    r10, r9, r3, r10                   @x[23] * h[23]
	 SMLABT    r11, r4, r3, r11                   @x[24] * h[23]
	 SMLABT    r12, r6, r3, r12                   @x[25] * h[23]

	 LDR       r3, [r2], #4                       @h[24], h[25]
         LDRSH     r9, [r1], #2                       @x[26]
	 SMLABB    r10, r4, r3, r10                   @x[24] * h[24]
	 SMLABB    r11, r6, r3, r11                   @x[25] * h[24]
	 SMLABB    r12, r9, r3, r12                   @x[26] * h[24]

	 LDRSH     r4, [r1], #2                       @x[27]
	 SMLABT    r10, r6, r3, r10                   @x[25] * h[25]
	 SMLABT    r11, r9, r3, r11                   @x[26] * h[25]
	 SMLABT    r12, r4, r3, r12                   @x[27] * h[25]

	 LDR       r3, [r2], #4                       @h[26], h[27]
	 LDRSH     r6, [r1], #2                       @x[28]
	 SMLABB    r10, r9, r3, r10                   @x[26] * h[26]
	 SMLABB    r11, r4, r3, r11                   @x[27] * h[26]
	 SMLABB    r12, r6, r3, r12                   @x[28] * h[26]

	 LDRSH     r9, [r1], #2                       @x[29]
	 SMLABT    r10, r4, r3, r10                   @x[27] * h[27]
	 SMLABT    r11, r6, r3, r11                   @x[28] * h[27]
	 SMLABT    r12, r9, r3, r12                   @x[29] * h[27]

	 LDR       r3, [r2], #4                       @h[28], h[29]
	 LDRSH     r4, [r1], #2                       @x[30]
	 SMLABB    r10, r6, r3, r10                   @x[28] * h[28]
	 SMLABB    r11, r9, r3, r11                   @x[29] * h[28]
	 SMLABB    r12, r4, r3, r12                   @x[30] * h[28]

         LDRSH     r6, [r1], #2                       @x[31]
	 SMLABT    r10, r9, r3, r10                   @x[29] * h[29]
	 SMLABT    r11, r4, r3, r11                   @x[30] * h[29]
	 SMLABT    r12, r6, r3, r12                   @x[31] * h[29]

	 LDR       r3, [r2], #4                       @h[30], h[31]
         LDRSH     r9, [r1], #2                       @x[32]
	 SMLABB    r10, r4, r3, r10                   @x[30] * h[30]
	 SMLABB    r11, r6, r3, r11                   @x[31] * h[30]
	 SMLABB    r12, r9, r3, r12                   @x[32] * h[30]

	 LDRSH     r4, [r1], #-60                     @x[33]
	 SMLABT    r10, r6, r3, r10                   @x[31] * h[31]
	 SMLABT    r11, r9, r3, r11                   @x[32] * h[31]
	 SMLABT    r12, r4, r3, r12                   @x[33] * h[31]

	 @SSAT      r10, #32, r10, LSL #2
	 @SSAT      r11, #32, r11, LSL #2
	 @SSAT      r12, #32, r12, LSL #2

	 MOV       r10, r10, LSL #1
	 MOV       r11, r11, LSL #1
	 MOV       r12, r12, LSL #1

	 QADD      r10, r10, r10
	 QADD      r11, r11, r11
	 QADD      r12, r12, r12

	 QADD      r10, r10, r5
	 QADD      r11, r11, r5
	 QADD      r12, r12, r5

	 SUBS      r14, r14, #1

	 MOV       r10, r10, ASR #16
	 MOV       r11, r11, ASR #16
	 MOV       r12, r12, ASR #16

	 STRH      r10, [r0], #2
	 STRH      r11, [r0], #2
	 STRH      r12, [r0], #2
	 BNE       THREE_LOOP

	 MOV       r2, r8                             @ptr = ptr2

Last2LOOP:

         LDR       r3, [r2], #4                       @h[0], h[1]
	 LDRSH     r4, [r1], #2                       @x[0]
	 LDRSH     r6, [r1], #2                       @x[1]
	 LDRSH     r9, [r1], #2                       @x[2]

	 SMULBB    r10, r4, r3                        @x[0] * h[0]
	 SMULBB    r11, r6, r3                        @x[1] * h[0]

	 SMLABT    r10, r6, r3, r10                   @x[1] * h[1]
	 SMLABT    r11, r9, r3, r11                   @x[2] * h[1]

	 LDR       r3, [r2], #4                       @h[2], h[3]
	 LDRSH     r4, [r1], #2                       @x[3]
         LDRSH     r6, [r1], #2                       @x[4]

	 SMLABB    r10, r9, r3, r10                   @x[2] * h[2]
         SMLABB    r11, r4, r3, r11                   @x[3] * h[2]

	 SMLABT    r10, r4, r3, r10                   @x[3] * h[3]
	 SMLABT    r11, r6, r3, r11                   @x[4] * h[3]

	 LDR       r3, [r2], #4                       @h[4], h[5]
	 LDRSH     r9, [r1], #2                       @x[5]
	 LDRSH     r4, [r1], #2                       @x[6]

	 SMLABB    r10, r6, r3, r10                   @x[4] * h[4]
	 SMLABB    r11, r9, r3, r11                   @x[5] * h[4]

	 SMLABT    r10, r9, r3, r10                   @x[5] * h[5]
	 SMLABT    r11, r4, r3, r11                   @x[6] * h[5]

	 LDR       r3, [r2], #4                       @h[6], h[7]
	 LDRSH     r6, [r1], #2                       @x[7]
	 LDRSH     r9, [r1], #2                       @x[8]

	 SMLABB    r10, r4, r3, r10                   @x[6] * h[6]
	 SMLABB    r11, r6, r3, r11                   @x[7] * h[6]

	 SMLABT    r10, r6, r3, r10                   @x[7] * h[7]
	 SMLABT    r11, r9, r3, r11                   @x[8] * h[7]

	 LDR       r3, [r2], #4                       @h[8], h[9]
	 LDRSH     r4, [r1], #2                       @x[9]
	 LDRSH     r6, [r1], #2                       @x[10]

	 SMLABB    r10, r9, r3, r10                   @x[8] * h[8]
	 SMLABB    r11, r4, r3, r11                   @x[9] * h[8]

	 SMLABT    r10, r4, r3, r10                   @x[9] * h[9]
	 SMLABT    r11, r6, r3, r11                   @x[10] * h[9]

	 LDR       r3, [r2], #4                       @h[10], h[11]
	 LDRSH     r9, [r1], #2                       @x[11]
	 LDRSH     r4, [r1], #2                       @x[12]

	 SMLABB    r10, r6, r3, r10                   @x[10] * h[10]
	 SMLABB    r11, r9, r3, r11                   @x[11] * h[10]

	 SMLABT    r10, r9, r3, r10                   @x[11] * h[11]
	 SMLABT    r11, r4, r3, r11                   @x[12] * h[11]

	 LDR       r3, [r2], #4                       @h[12], h[13]
	 LDRSH     r6, [r1], #2                       @x[13]
	 LDRSH     r9, [r1], #2                       @x[14]

	 SMLABB    r10, r4, r3, r10                   @x[12] * h[12]
	 SMLABB    r11, r6, r3, r11                   @x[13] * h[12]

	 SMLABT    r10, r6, r3, r10                   @x[13] * h[13]
	 SMLABT    r11, r9, r3, r11                   @x[14] * h[13]

	 LDR       r3, [r2], #4                       @h[14], h[15]
	 LDRSH     r4, [r1], #2                       @x[15]
	 LDRSH     r6, [r1], #2                       @x[16]

	 SMLABB    r10, r9, r3, r10                   @x[14] * h[14]
	 SMLABB    r11, r4, r3, r11                   @x[15] * h[14]

	 SMLABT    r10, r4, r3, r10                   @x[15] * h[15]
	 SMLABT    r11, r6, r3, r11                   @x[16] * h[15]

	 LDR       r3, [r2], #4                       @h[16], h[17]
	 LDRSH     r9, [r1], #2                       @x[17]
	 LDRSH     r4, [r1], #2                       @x[18]

	 SMLABB    r10, r6, r3, r10                   @x[16] * h[16]
	 SMLABB    r11, r9, r3, r11                   @x[17] * h[16]

	 SMLABT    r10, r9, r3, r10                   @x[17] * h[17]
	 SMLABT    r11, r4, r3, r11                   @x[18] * h[17]

	 LDR       r3, [r2], #4                       @h[18], h[19]
	 LDRSH     r6, [r1], #2                       @x[19]
	 LDRSH     r9, [r1], #2                       @x[20]

	 SMLABB    r10, r4, r3, r10                   @x[18] * h[18]
	 SMLABB    r11, r6, r3, r11                   @x[19] * h[18]

	 SMLABT    r10, r6, r3, r10                   @x[19] * h[19]
	 SMLABT    r11, r9, r3, r11                   @x[20] * h[19]

	 LDR       r3, [r2], #4                       @h[20], h[21]
	 LDRSH     r4, [r1], #2                       @x[21]
	 LDRSH     r6, [r1], #2                       @x[22]

	 SMLABB    r10, r9, r3, r10                   @x[20] * h[20]
	 SMLABB    r11, r4, r3, r11                   @x[21] * h[20]

	 SMLABT    r10, r4, r3, r10                   @x[21] * h[21]
	 SMLABT    r11, r6, r3, r11                   @x[22] * h[21]

	 LDR       r3, [r2], #4                       @h[22], h[23]
	 LDRSH     r9, [r1], #2                       @x[23]
	 LDRSH     r4, [r1], #2                       @x[24]

	 SMLABB    r10, r6, r3, r10                   @x[22] * h[22]
	 SMLABB    r11, r9, r3, r11                   @x[23] * h[22]

	 SMLABT    r10, r9, r3, r10                   @x[23] * h[23]
	 SMLABT    r11, r4, r3, r11                   @x[24] * h[23]

	 LDR       r3, [r2], #4                       @h[24], h[25]
	 LDRSH     r6, [r1], #2                       @x[25]
	 LDRSH     r9, [r1], #2                       @x[26]

	 SMLABB    r10, r4, r3, r10                   @x[24] * h[24]
	 SMLABB    r11, r6, r3, r11                   @x[25] * h[24]

	 SMLABT    r10, r6, r3, r10                   @x[25] * h[25]
	 SMLABT    r11, r9, r3, r11                   @x[26] * h[25]

	 LDR       r3, [r2], #4                       @h[26], h[27]
	 LDRSH     r4, [r1], #2                       @x[27]
	 LDRSH     r6, [r1], #2                       @x[28]

	 SMLABB    r10, r9, r3, r10                   @x[26] * h[26]
	 SMLABB    r11, r4, r3, r11                   @x[27] * h[26]

	 SMLABT    r10, r4, r3, r10                   @x[27] * h[27]
	 SMLABT    r11, r6, r3, r11                   @x[28] * h[27]

	 LDR       r3, [r2], #4                       @h[28], h[29]
	 LDRSH     r9, [r1], #2                       @x[29]
	 LDRSH     r4, [r1], #2                       @x[30]

	 SMLABB    r10, r6, r3, r10                   @x[28] * h[28]
	 SMLABB    r11, r9, r3, r11                   @x[29] * h[28]

	 SMLABT    r10, r9, r3, r10                   @x[29] * h[29]
	 SMLABT    r11, r4, r3, r11                   @x[30] * h[29]

	 LDR       r3, [r2], #4                       @h[30], h[31]
	 LDRSH     r6, [r1], #2                       @x[31]
	 LDRSH     r9, [r1], #2                       @x[32]

	 SMLABB    r10, r4, r3, r10                   @x[30] * h[30]
	 SMLABB    r11, r6, r3, r11                   @x[31] * h[30]

	 SMLABT    r10, r6, r3, r10                   @x[31] * h[31]
	 SMLABT    r11, r9, r3, r11                   @x[32] * h[31]

	 @SSAT      r10, #32, r10, LSL #2
	 @SSAT      r11, #32, r11, LSL #2
	 MOV       r10, r10, LSL #1
	 MOV       r11, r11, LSL #1

	 QADD      r10, r10, r10
	 QADD      r11, r11, r11

	 QADD      r10, r10, r5
	 QADD      r11, r11, r5

	 MOV       r10, r10, ASR #16
	 MOV       r11, r11, ASR #16

	 STRH      r10, [r0], #2
	 STRH      r11, [r0], #2


pred_lt4_end:
         LDMFD     r13!, {r4 - r12, r15}

Table:
         .word       inter4_2
	 @ENDFUNC
	 .END




