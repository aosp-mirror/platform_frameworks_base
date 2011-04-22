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
@void Scale_sig(
@	       Word16 x[],                           /* (i/o) : signal to scale               */
@	       Word16 lg,                            /* (i)   : size of x[]                   */
@	       Word16 exp                            /* (i)   : exponent: x = round(x << exp) */
@	       )
@
@r0 --- x[]
@r1 --- lg
@r2 --- exp

          .section  .text
	  .global   Scale_sig_opt

Scale_sig_opt:

         STMFD         r13!, {r4 - r12, r14}
	 SUB           r3, r1, #1                  @i = lg - 1
         CMP           r2, #0                      @Compare exp and 0
	 RSB           r7, r2, #0                  @exp = -exp
	 ADD           r10, r2, #16                @16 + exp
         ADD           r4, r0, r3, LSL #1          @x[i] address
	 MOV           r8, #0x7fffffff
	 MOV           r9, #0x8000
	 BLE           LOOP2
	 
LOOP1:

         LDRSH          r5, [r4]                    @load x[i]
         MOV           r12, r5, LSL r10
	 TEQ           r5, r12, ASR r10
	 EORNE         r12, r8, r5, ASR #31
	 SUBS          r3, r3, #1
	 QADD          r11, r12, r9
	 MOV           r12, r11, ASR #16
	 STRH          r12, [r4], #-2
	 BGE           LOOP1
         BL            The_end

LOOP2:

         LDRSH          r5, [r4]                   @load x[i]
	 MOV           r6, r5, LSL #16            @L_tmp = x[i] << 16
	 MOV           r5, r6, ASR r7             @L_tmp >>= exp
	 QADD          r11, r5, r9
	 MOV           r12, r11, ASR #16
	 SUBS          r3, r3, #1
	 STRH          r12, [r4], #-2
	 BGE           LOOP2

The_end:
         LDMFD         r13!, {r4 - r12, r15}
     
         @ENDFUNC
         .END	 
        
	
	  


