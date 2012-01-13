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
@void Residu (
@    Word16 a[], /* (i)     : prediction coefficients                      */
@    Word16 x[], /* (i)     : speech signal                                */
@    Word16 y[], /* (o)     : residual signal                              */
@    Word16 lg   /* (i)     : size of filtering                            */
@)
@a[]        RN     r0
@x[]        RN     r1
@y[]        RN     r2
@lg         RN     r3

	.section   .text
        .global    Residu_opt

Residu_opt:

        STMFD          r13!, {r4 - r12, r14}
        SUB            r7, r3, #4                       @i = lg - 4

        VLD1.S16       {D0, D1, D2, D3}, [r0]!              @get all a[]
	VLD1.S16       {D4}, [r0]!
        VMOV.S32       Q8,  #0x8000

LOOP1:
        ADD            r9, r1, r7, LSL #1               @copy the address
        ADD            r10, r2, r7, LSL #1
        MOV            r8, r9
        VLD1.S16       D5, [r8]!                       @get x[i], x[i+1], x[i+2], x[i+3]
        VQDMULL.S16    Q10, D5, D0[0]                  @finish the first L_mult

        SUB            r8, r9, #2                       @get the x[i-1] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D0[1]

        SUB            r8, r9, #4                       @load the x[i-2] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D0[2]

        SUB            r8, r9, #6                       @load the x[i-3] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D0[3]

        SUB            r8, r9, #8                       @load the x[i-4] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D1[0]

        SUB            r8, r9, #10                      @load the x[i-5] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D1[1]

        SUB            r8, r9, #12                      @load the x[i-6] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D1[2]

        SUB            r8, r9, #14                      @load the x[i-7] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D1[3]

        SUB            r8, r9, #16                      @load the x[i-8] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D2[0]

        SUB            r8, r9, #18                      @load the x[i-9] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D2[1]

        SUB            r8, r9, #20                      @load the x[i-10] address
        VLD1.S16       D5, [r8]!
        VQDMLAL.S16    Q10, D5, D2[2]

	SUB            r8, r9, #22                      @load the x[i-11] address
	VLD1.S16       D5, [r8]!
	VQDMLAL.S16    Q10, D5, D2[3]

	SUB            r8, r9, #24                      @load the x[i-12] address
	VLD1.S16       D5, [r8]!
	VQDMLAL.S16    Q10, D5, D3[0]

	SUB            r8, r9, #26                      @load the x[i-13] address
	VLD1.S16       D5, [r8]!
	VQDMLAL.S16    Q10, D5, D3[1]

	SUB            r8, r9, #28                      @load the x[i-14] address
	VLD1.S16       D5, [r8]!
	VQDMLAL.S16    Q10, D5, D3[2]

	SUB            r8, r9, #30                      @load the x[i-15] address
	VLD1.S16       D5, [r8]!
	VQDMLAL.S16    Q10, D5, D3[3]

	SUB            r8, r9, #32                      @load the x[i-16] address
	VLD1.S16       D5, [r8]!
	VQDMLAL.S16    Q10, D5, D4[0]

        SUB            r7, r7, #4                       @i-=4
        VQSHL.S32      Q10, Q10, #4
        VQADD.S32      Q10, Q10, Q8
        VSHRN.S32      D5, Q10, #16
        VST1.S16       D5, [r10]!
        CMP            r7,  #0

        BGE            LOOP1

Residu_asm_end:

        LDMFD      r13!, {r4 - r12, r15}

        @ENDFUNC
        .END


