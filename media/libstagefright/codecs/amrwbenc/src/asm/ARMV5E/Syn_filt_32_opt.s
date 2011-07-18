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
@void Syn_filt_32(
@     Word16 a[],                           /* (i) Q12 : a[m+1] prediction coefficients */
@     Word16 m,                             /* (i)     : order of LP filter             */
@     Word16 exc[],                         /* (i) Qnew: excitation (exc[i] >> Qnew)    */
@     Word16 Qnew,                          /* (i)     : exc scaling = 0(min) to 8(max) */
@     Word16 sig_hi[],                      /* (o) /16 : synthesis high                 */
@     Word16 sig_lo[],                      /* (o) /16 : synthesis low                  */
@     Word16 lg                             /* (i)     : size of filtering              */
@)
@***************************************************************
@
@ a[]      --- r0
@ m        --- r1
@ exc[]    --- r2
@ Qnew     --- r3
@ sig_hi[] --- r4
@ sig_lo[] --- r5
@ lg       --- r6

          .section  .text
          .global  Syn_filt_32_asm

Syn_filt_32_asm:

          STMFD   	r13!, {r4 - r12, r14}
          LDR           r4,  [r13, #40]                  @ get sig_hi[] address
          LDR           r5,  [r13, #44]                  @ get sig_lo[] address

          LDRSH         r6,  [r0]                        @ load Aq[0]
          ADD           r7,  r3, #4                      @ 4 + Q_new
          MOV           r3, r6, ASR r7                   @ a0 = Aq[0] >> (4 + Q_new)

          LDR           r14, =0xffff
          LDRSH         r6, [r0, #2]                     @ load Aq[1]
          LDRSH         r7, [r0, #4]                     @ load Aq[2]
          LDRSH         r8, [r0, #6]                     @ load Aq[3]
          LDRSH         r9, [r0, #8]                     @ load Aq[4]
          AND           r6, r6, r14
          AND           r8, r8, r14
          ORR           r10, r6, r7, LSL #16             @ Aq[2] -- Aq[1]
          ORR           r11, r8, r9, LSL #16             @ Aq[4] -- Aq[3]
          STR           r10, [r13, #-4]
          STR           r11, [r13, #-8]

          LDRSH         r6, [r0, #10]                    @ load Aq[5]
          LDRSH         r7, [r0, #12]                    @ load Aq[6]
          LDRSH         r8, [r0, #14]                    @ load Aq[7]
          LDRSH         r9, [r0, #16]                    @ load Aq[8]
          AND           r6, r6, r14
          AND           r8, r8, r14
          ORR           r10, r6, r7, LSL #16             @ Aq[6] -- Aq[5]
          ORR           r11, r8, r9, LSL #16             @ Aq[8] -- Aq[7]
          STR           r10, [r13, #-12]
          STR           r11, [r13, #-16]

          LDRSH         r6, [r0, #18]                    @ load Aq[9]
          LDRSH         r7, [r0, #20]                    @ load Aq[10]
          LDRSH         r8, [r0, #22]                    @ load Aq[11]
          LDRSH         r9, [r0, #24]                    @ load Aq[12]
          AND           r6, r6, r14
          AND           r8, r8, r14
          ORR           r10, r6, r7, LSL #16             @ Aq[10] -- Aq[9]
          ORR           r11, r8, r9, LSL #16             @ Aq[12] -- Aq[11]
          STR           r10, [r13, #-20]
          STR           r11, [r13, #-24]

          LDRSH         r6, [r0, #26]                    @ load Aq[13]
          LDRSH         r7, [r0, #28]                    @ load Aq[14]
          LDRSH         r8, [r0, #30]                    @ load Aq[15]
          LDRSH         r9, [r0, #32]                    @ load Aq[16]
          AND           r6, r6, r14
          AND           r8, r8, r14
          ORR           r10, r6, r7, LSL #16             @ Aq[14] -- Aq[13]
          ORR           r11, r8, r9, LSL #16             @ Aq[16] -- Aq[15]
          STR           r10, [r13, #-28]
          STR           r11, [r13, #-32]

          MOV           r8, #0                           @ i = 0

LOOP:
          LDRSH         r6, [r5, #-2]                    @ load sig_lo[i-1]
          LDRSH         r7, [r5, #-4]                    @ load sig_lo[i-2]

          LDR           r11, [r13, #-4]                  @ Aq[2] -- Aq[1]
          LDRSH         r9, [r5, #-6]                    @ load sig_lo[i-3]
          LDRSH         r10, [r5, #-8]                   @ load sig_lo[i-4]

          SMULBB        r12, r6, r11                     @ sig_lo[i-1] * Aq[1]

          LDRSH         r6, [r5, #-10]                   @ load sig_lo[i-5]
          SMLABT        r12, r7, r11, r12                @ sig_lo[i-2] * Aq[2]
          LDR           r11, [r13, #-8]                  @ Aq[4] -- Aq[3]
          LDRSH         r7, [r5, #-12]                   @ load sig_lo[i-6]
          SMLABB        r12, r9, r11, r12                @ sig_lo[i-3] * Aq[3]
          LDRSH         r9, [r5, #-14]                   @ load sig_lo[i-7]
          SMLABT        r12, r10, r11, r12               @ sig_lo[i-4] * Aq[4]
          LDR           r11, [r13, #-12]                 @ Aq[6] -- Aq[5]
          LDRSH         r10, [r5, #-16]                  @ load sig_lo[i-8]
          SMLABB        r12, r6, r11, r12                @ sig_lo[i-5] * Aq[5]
          LDRSH         r6,  [r5, #-18]                  @ load sig_lo[i-9]
          SMLABT        r12, r7, r11, r12                @ sig_lo[i-6] * Aq[6]
          LDR           r11, [r13, #-16]                 @ Aq[8] -- Aq[7]
          LDRSH         r7,  [r5, #-20]                  @ load sig_lo[i-10]
          SMLABB        r12, r9, r11, r12                @ sig_lo[i-7] * Aq[7]
          LDRSH         r9, [r5, #-22]                   @ load sig_lo[i-11]
          SMLABT        r12, r10, r11, r12               @ sig_lo[i-8] * Aq[8]
          LDR           r11, [r13, #-20]                 @ Aq[10] -- Aq[9]
          LDRSH         r10,[r5, #-24]                   @ load sig_lo[i-12]
          SMLABB        r12, r6, r11, r12                @ sig_lo[i-9] * Aq[9]
          LDRSH         r6, [r5, #-26]                   @ load sig_lo[i-13]
          SMLABT        r12, r7, r11, r12                @ sig_lo[i-10] * Aq[10]
          LDR           r11, [r13, #-24]                 @ Aq[12] -- Aq[11]
          LDRSH         r7, [r5, #-28]                   @ load sig_lo[i-14]
          SMLABB        r12, r9, r11, r12                @ sig_lo[i-11] * Aq[11]
          LDRSH         r9, [r5, #-30]                   @ load sig_lo[i-15]
          SMLABT        r12, r10, r11, r12               @ sig_lo[i-12] * Aq[12]

          LDR           r11, [r13, #-28]                 @ Aq[14] -- Aq[13]
          LDRSH         r10, [r5, #-32]                  @ load sig_lo[i-16]
          SMLABB        r12, r6, r11, r12                @ sig_lo[i-13] * Aq[13]
          SMLABT        r12, r7, r11, r12                @ sig_lo[i-14] * Aq[14]

          LDR           r11, [r13, #-32]                 @ Aq[16] -- Aq[15]
          LDRSH         r6, [r2],#2                      @ load exc[i]
          SMLABB        r12, r9, r11, r12                @ sig_lo[i-15] * Aq[15]
          SMLABT        r12, r10, r11, r12               @ sig_lo[i-16] * Aq[16]
          MUL           r7, r6, r3                       @ exc[i] * a0
          RSB           r14, r12, #0                     @ L_tmp
          MOV           r14, r14, ASR #11                @ L_tmp >>= 11
          ADD           r14, r14, r7, LSL #1             @ L_tmp += (exc[i] * a0) << 1


          LDRSH         r6, [r4, #-2]                    @ load sig_hi[i-1]
          LDRSH         r7, [r4, #-4]                    @ load sig_hi[i-2]

          LDR           r11, [r13, #-4]                  @ Aq[2] -- Aq[1]
          LDRSH         r9, [r4, #-6]                    @ load sig_hi[i-3]
          LDRSH         r10, [r4, #-8]                   @ load sig_hi[i-4]
          SMULBB        r12, r6, r11                     @ sig_hi[i-1] * Aq[1]
          LDRSH         r6, [r4, #-10]                   @ load sig_hi[i-5]
	  SMLABT        r12, r7, r11, r12                @ sig_hi[i-2] * Aq[2]

          LDR           r11, [r13, #-8]                  @ Aq[4] -- Aq[3]
          LDRSH         r7, [r4, #-12]                   @ load sig_hi[i-6]

          SMLABB        r12, r9, r11, r12                @ sig_hi[i-3] * Aq[3]
	  LDRSH         r9, [r4, #-14]                   @ load sig_hi[i-7]

	  SMLABT        r12, r10, r11, r12               @ sig_hi[i-4] * Aq[4]

          LDR           r11, [r13, #-12]                 @ Aq[6] -- Aq[5]
          LDRSH         r10, [r4, #-16]                  @ load sig_hi[i-8]

	  SMLABB        r12, r6, r11, r12                @ sig_hi[i-5] * Aq[5]

	  LDRSH         r6,  [r4, #-18]                  @ load sig_hi[i-9]
	  SMLABT        r12, r7, r11, r12                @ sig_hi[i-6] * Aq[6]

          LDR           r11, [r13, #-16]                 @ Aq[8] -- Aq[7]
          LDRSH         r7,  [r4, #-20]                  @ load sig_hi[i-10]

	  SMLABB        r12, r9, r11, r12                @ sig_hi[i-7] * Aq[7]

	  LDRSH         r9, [r4, #-22]                   @ load sig_hi[i-11]

	  SMLABT        r12, r10, r11, r12               @ sig_hi[i-8] * Aq[8]

          LDR           r11, [r13, #-20]                 @ Aq[10] -- Aq[9]
          LDRSH         r10,[r4, #-24]                   @ load sig_hi[i-12]

	  SMLABB        r12, r6, r11, r12                @ sig_hi[i-9] * Aq[9]
          LDRSH         r6, [r4, #-26]                   @ load sig_hi[i-13]
          SMLABT        r12, r7, r11, r12                @ sig_hi[i-10] * Aq[10]

          LDR           r11, [r13, #-24]                 @ Aq[12] -- Aq[11]
          LDRSH         r7, [r4, #-28]                   @ load sig_hi[i-14]
          SMLABB        r12, r9, r11, r12                @ sig_hi[i-11] * Aq[11]
          LDRSH         r9, [r4, #-30]                   @ load sig_hi[i-15]
          SMLABT        r12, r10, r11, r12               @ sig_hi[i-12] * Aq[12]

          LDR           r11, [r13, #-28]                 @ Aq[14] -- Aq[13]
          LDRSH         r10, [r4, #-32]                  @ load sig_hi[i-16]
          SMLABB        r12, r6, r11, r12                @ sig_hi[i-13] * Aq[13]
          SMLABT        r12, r7, r11, r12                @ sig_hi[i-14] * Aq[14]

          LDR           r11, [r13, #-32]                 @ Aq[16] -- Aq[15]
          SMLABB        r12, r9, r11, r12                @ sig_hi[i-15] * Aq[15]
          SMLABT        r12, r10, r11, r12               @ sig_hi[i-16] * Aq[16]
          ADD           r6, r12, r12                     @ r12 << 1
          SUB           r14, r14, r6
          MOV           r14, r14, LSL #3                 @ L_tmp <<=3

          MOV           r7, r14, ASR #16                 @ L_tmp >> 16

          MOV           r14, r14, ASR #4                 @ L_tmp >>=4
          STRH          r7, [r4], #2                         @ sig_hi[i] = L_tmp >> 16
          SUB           r9, r14, r7, LSL #12             @ sig_lo[i] = L_tmp - (sig_hi[i] << 12)

          ADD           r8, r8, #1
          STRH          r9, [r5], #2
          CMP           r8, #64
          BLT           LOOP

Syn_filt_32_end:

          LDMFD   	    r13!, {r4 - r12, r15}
          @ENDFUNC
          .END


