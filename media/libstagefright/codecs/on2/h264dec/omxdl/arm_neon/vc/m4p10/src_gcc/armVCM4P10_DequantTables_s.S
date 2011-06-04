/*
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 *
 */

    .eabi_attribute 24, 1
    .eabi_attribute 25, 1

    .arm
    .fpu neon
    .section .rodata
    .align 4


    .global armVCM4P10_QPDivTable
    .global armVCM4P10_VMatrixQPModTable
    .global armVCM4P10_PosToVCol4x4
    .global armVCM4P10_PosToVCol2x2
    .global armVCM4P10_VMatrix
    .global armVCM4P10_QPModuloTable
    .global armVCM4P10_VMatrixU16

armVCM4P10_PosToVCol4x4:
    .byte  0, 2, 0, 2
    .byte  2, 1, 2, 1
    .byte  0, 2, 0, 2
    .byte  2, 1, 2, 1

armVCM4P10_PosToVCol2x2:
    .byte  0, 2
    .byte  2, 1

armVCM4P10_VMatrix:
    .byte  10, 16, 13
    .byte  11, 18, 14
    .byte  13, 20, 16
    .byte  14, 23, 18
    .byte  16, 25, 20
    .byte  18, 29, 23

;//-------------------------------------------------------
;// This table evaluates the expression [(INT)(QP/6)],
;// for values of QP from 0 to 51 (inclusive).
;//-------------------------------------------------------

armVCM4P10_QPDivTable:
    .byte  0,  0,  0,  0,  0,  0
    .byte  1,  1,  1,  1,  1,  1
    .byte  2,  2,  2,  2,  2,  2
    .byte  3,  3,  3,  3,  3,  3
    .byte  4,  4,  4,  4,  4,  4
    .byte  5,  5,  5,  5,  5,  5
    .byte  6,  6,  6,  6,  6,  6
    .byte  7,  7,  7,  7,  7,  7
    .byte  8,  8,  8,  8,  8,  8

;//----------------------------------------------------
;// This table contains armVCM4P10_VMatrix[QP%6][0] entires,
;// for values of QP from 0 to 51 (inclusive).
;//----------------------------------------------------

armVCM4P10_VMatrixQPModTable:
    .byte 10, 11, 13, 14, 16, 18
    .byte 10, 11, 13, 14, 16, 18
    .byte 10, 11, 13, 14, 16, 18
    .byte 10, 11, 13, 14, 16, 18
    .byte 10, 11, 13, 14, 16, 18
    .byte 10, 11, 13, 14, 16, 18
    .byte 10, 11, 13, 14, 16, 18
    .byte 10, 11, 13, 14, 16, 18
    .byte 10, 11, 13, 14, 16, 18

;//-------------------------------------------------------
;// This table evaluates the modulus expression [QP%6]*6,
;// for values of QP from 0 to 51 (inclusive).
;//-------------------------------------------------------

armVCM4P10_QPModuloTable:
    .byte 0, 6, 12, 18, 24, 30
    .byte 0, 6, 12, 18, 24, 30
    .byte 0, 6, 12, 18, 24, 30
    .byte 0, 6, 12, 18, 24, 30
    .byte 0, 6, 12, 18, 24, 30
    .byte 0, 6, 12, 18, 24, 30
    .byte 0, 6, 12, 18, 24, 30
    .byte 0, 6, 12, 18, 24, 30
    .byte 0, 6, 12, 18, 24, 30

;//-------------------------------------------------------
;// This table contains the invidual byte values stored as
;// halfwords. This avoids unpacking inside the function
;//-------------------------------------------------------

armVCM4P10_VMatrixU16:
    .hword 10, 16, 13
    .hword 11, 18, 14
    .hword 13, 20, 16
    .hword 14, 23, 18
    .hword 16, 25, 20
    .hword 18, 29, 23

    .end

