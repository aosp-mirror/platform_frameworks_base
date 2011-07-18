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

@**********************************************************************/
@void Filt_6k_7k(
@     Word16 signal[],                      /* input:  signal                  */
@     Word16 lg,                            /* input:  length of input         */
@     Word16 mem[]                          /* in/out: memory (size=30)        */
@)
@******************************************************************
@ r0    ---  signal[]
@ r1    ---  lg
@ r2    ---  mem[]

          .section  .text
          .global  Filt_6k_7k_asm
          .extern  voAWB_Copy
          .extern  fir_6k_7k

Filt_6k_7k_asm:

          STMFD   		r13!, {r4 - r12, r14}
          SUB    		r13, r13, #240              @ x[L_SUBFR16k + (L_FIR - 1)]
          MOV     		r8, r0                      @ copy signal[] address
          MOV     		r4, r1                      @ copy lg address
          MOV     		r5, r2                      @ copy mem[] address

          MOV     		r1, r13
          MOV     		r0, r2
          MOV     		r2, #30                     @ L_FIR - 1
          BL      		voAWB_Copy                   @ memcpy(x, mem, (L_FIR - 1)<<1)

          LDR     		r10, Lable1                 @ get fir_7k address

          MOV           	r14, #0
          MOV                   r3, r8                      @ change myMemCopy to Copy, due to Copy will change r3 content
          ADD     	    	r6, r13, #60                @ get x[L_FIR - 1] address
          MOV           	r7, r3                      @ get signal[i]
LOOP1:
          LDRSH         	r8,  [r7], #2
          LDRSH         	r9,  [r7], #2
          MOV           	r8, r8, ASR #2
          MOV           	r9, r9, ASR #2
          LDRSH         	r11, [r7], #2
          LDRSH         	r12, [r7], #2
          MOV           	r11, r11, ASR #2
          MOV           	r12, r12, ASR #2
          STRH          	r8, [r6], #2
          STRH          	r9, [r6], #2
          STRH          	r11, [r6], #2
          STRH          	r12, [r6], #2
          LDRSH         	r8,  [r7], #2
          LDRSH         	r9,  [r7], #2
          MOV           	r8, r8, ASR #2
          MOV           	r9, r9, ASR #2
          LDRSH         	r11, [r7], #2
          LDRSH         	r12, [r7], #2
          MOV           	r11, r11, ASR #2
          MOV           	r12, r12, ASR #2
          STRH          	r8, [r6], #2
          STRH          	r9, [r6], #2
          STRH          	r11, [r6], #2
          STRH          	r12, [r6], #2
          ADD           	r14, r14, #8
          CMP           	r14, #80
          BLT           	LOOP1


          STR     		r5, [sp, #-4]               @ PUSH  r5 to stack

          @ not use registers: r4, r10, r12, r14, r5
          MOV     		r4, r13
          MOV     		r5, #0                      @ i = 0
LOOP2:
          LDR           	r0, [r10]

          LDRSH  	        r1, [r4]                   @ load x[i]
          LDRSH   	        r2, [r4, #60]              @ load x[i + 30]
          LDRSH                 r6, [r4, #2]               @ load x[i + 1]
          LDRSH                 r7, [r4, #58]              @ load x[i + 29]
          ADD                   r1, r1, r2                 @ x[i] + x[i + 30]
          ADD                   r6, r6, r7                 @ x[i + 1] + x[i + 29]
          LDRSH                 r8, [r4, #4]               @ load x[i + 2]
          LDRSH                 r9, [r4, #56]              @ load x[i + 28]

          SMULBB                r14, r1, r0                @ (x[i] + x[i + 30]) * fir_7k[0]
          ADD                   r8, r8, r9                 @ x[i + 2] + x[i + 28]
          SMLABT                r14, r6, r0, r14           @ (x[i + 1] + x[i + 29]) * fir_7k[1]

          LDR                   r0, [r10, #4]
          LDRSH                 r1, [r4, #6]               @ load x[i+3]
          LDRSH                 r2, [r4, #54]              @ load x[i+27]
          LDRSH                 r6, [r4, #8]               @ load x[i+4]
          LDRSH                 r7, [r4, #52]              @ load x[i+26]
          ADD                   r1, r1, r2                 @ x[i+3] + x[i+27]
          ADD                   r6, r6, r7                 @ x[i+4] + x[i+26]
          SMLABB                r14, r8, r0, r14           @ (x[i + 2] + x[i + 28]) * fir_7k[2]
          LDRSH                 r8, [r4, #10]              @ load x[i+5]
          LDRSH                 r9, [r4, #50]              @ load x[i+25]
          SMLABT                r14, r1, r0, r14           @ (x[i+3] + x[i+27]) * fir_7k[3]
          ADD                   r8, r8, r9                 @ x[i+5] + x[i+25]

          LDR                   r0, [r10, #8]
          LDRSH                 r1, [r4, #12]              @ x[i+6]
          LDRSH                 r2, [r4, #48]              @ x[i+24]
          SMLABB                r14, r6, r0, r14           @ (x[i+4] + x[i+26]) * fir_7k[4]
          LDRSH                 r6, [r4, #14]              @ x[i+7]
          LDRSH                 r7, [r4, #46]              @ x[i+23]
          SMLABT                r14, r8, r0, r14           @ (x[i+5] + x[i+25]) * fir_7k[5]
          LDR                   r0, [r10, #12]
          ADD                   r1, r1, r2                 @ (x[i+6] + x[i+24])
          ADD                   r6, r6, r7                 @ (x[i+7] + x[i+23])
          SMLABB                r14, r1, r0, r14           @ (x[i+6] + x[i+24]) * fir_7k[6]
          LDRSH                 r8, [r4, #16]              @ x[i+8]
          LDRSH                 r9, [r4, #44]              @ x[i+22]
          SMLABT                r14, r6, r0, r14           @ (x[i+7] + x[i+23]) * fir_7k[7]
          LDR                   r0, [r10, #16]
          LDRSH                 r1, [r4, #18]              @ x[i+9]
          LDRSH                 r2, [r4, #42]              @ x[i+21]
          LDRSH                 r6, [r4, #20]              @ x[i+10]
          LDRSH                 r7, [r4, #40]              @ x[i+20]
          ADD                   r8, r8, r9                 @ (x[i+8] + x[i+22])
          ADD                   r1, r1, r2                 @ (x[i+9] + x[i+21])
          ADD                   r6, r6, r7                 @ (x[i+10] + x[i+20])
          SMLABB                r14, r8, r0, r14           @ (x[i+8] + x[i+22]) * fir_7k[8]
          LDRSH                 r8, [r4, #22]              @ x[i+11]
          LDRSH                 r9, [r4, #38]              @ x[i+19]
          SMLABT                r14, r1, r0, r14           @ (x[i+9] + x[i+21]) * fir_7k[9]
          LDR                   r0, [r10, #20]
          LDRSH                 r1, [r4, #24]              @ x[i+12]
          LDRSH                 r2, [r4, #36]              @ x[i+18]
          SMLABB                r14, r6, r0, r14           @ (x[i+10] + x[i+20]) * fir_7k[10]
          LDRSH                 r6, [r4, #26]              @ x[i+13]
          ADD                   r8, r8, r9                 @ (x[i+11] + x[i+19])
          LDRSH                 r7, [r4, #34]              @ x[i+17]
          SMLABT                r14, r8, r0, r14           @ (x[i+11] + x[i+19]) * fir_7k[11]
          LDR                   r0, [r10, #24]
          ADD                   r1, r1, r2                 @ x[i+12] + x[i+18]
          LDRSH                 r8, [r4, #28]              @ x[i+14]
          SMLABB                r14, r1, r0, r14           @ (x[i+12] + x[i+18]) * fir_7k[12]
          ADD                   r6, r6, r7                 @ (x[i+13] + x[i+17])
          LDRSH                 r9, [r4, #32]              @ x[i+16]
          SMLABT                r14, r6, r0, r14           @ (x[i+13] + x[i+17]) * fir_7k[13]
          LDR                   r0, [r10, #28]
          ADD                   r8, r8, r9                 @ (x[i+14] + x[i+16])
          LDRSH                 r1, [r4, #30]              @ x[i+15]
          SMLABB                r14, r8, r0, r14           @ (x[i+14] + x[i+16]) * fir_7k[14]
          SMLABT                r14, r1, r0, r14           @ x[i+15] * fir_7k[15]

          ADD     		r5, r5, #1
          ADD     		r14, r14, #0x4000
          ADD     		r4, r4, #2
          MOV     		r1, r14, ASR #15
          CMP     		r5, #80
          STRH    		r1, [r3], #2               @signal[i] = (L_tmp + 0x4000) >> 15
          BLT     		LOOP2

          LDR     		r1, [sp, #-4]               @mem address
          ADD     		r0, r13, #160               @x + lg
          MOV     		r2, #30
          BL      		voAWB_Copy

Filt_6k_7k_end:
          ADD     		r13, r13, #240
          LDMFD   		r13!, {r4 - r12, r15}

Lable1:
          .word   		fir_6k_7k
          @ENDFUNC
          .END


