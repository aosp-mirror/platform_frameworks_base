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

    .global armVCM4P10_MFMatrixQPModTable
    .global armVCM4P10_QPDivIntraTable
    .global armVCM4P10_QPDivPlusOneTable

;//------------------------------------------------------------------
;// This table contains (1 << QbitsPlusOne) / 3 Values (Intra case) ,
;// for values of iQP from 0 to 51 (inclusive).
;//------------------------------------------------------------------


armVCM4P10_QPDivIntraTable:
    .word 21845, 21845, 21845, 21845, 21845, 21845
    .word 43690, 43690, 43690, 43690, 43690, 43690
    .word 87381, 87381, 87381, 87381, 87381, 87381
    .word 174762, 174762, 174762, 174762, 174762, 174762
    .word 349525, 349525, 349525, 349525, 349525, 349525
    .word 699050, 699050, 699050, 699050, 699050, 699050
    .word 1398101, 1398101, 1398101, 1398101, 1398101, 1398101
    .word 2796202, 2796202, 2796202, 2796202, 2796202, 2796202


;//--------------------------------------------------------------
;// This table contains armVCM4P10_MFMatrix [iQP % 6][0] entires,
;// for values of iQP from 0 to 51 (inclusive).
;//--------------------------------------------------------------

armVCM4P10_MFMatrixQPModTable:
    .hword 13107, 11916, 10082, 9362, 8192, 7282
    .hword 13107, 11916, 10082, 9362, 8192, 7282
    .hword 13107, 11916, 10082, 9362, 8192, 7282
    .hword 13107, 11916, 10082, 9362, 8192, 7282
    .hword 13107, 11916, 10082, 9362, 8192, 7282
    .hword 13107, 11916, 10082, 9362, 8192, 7282
    .hword 13107, 11916, 10082, 9362, 8192, 7282
    .hword 13107, 11916, 10082, 9362, 8192, 7282
    .hword 13107, 11916, 10082, 9362, 8192, 7282

;//---------------------------------------------------------------
;// This table contains ARM_M4P10_Q_OFFSET + 1 + (iQP / 6) values,
;// for values of iQP from 0 to 51 (inclusive).
;//---------------------------------------------------------------

armVCM4P10_QPDivPlusOneTable:
    .byte 16, 16, 16, 16, 16, 16
    .byte 17, 17, 17, 17, 17, 17
    .byte 18, 18, 18, 18, 18, 18
    .byte 19, 19, 19, 19, 19, 19
    .byte 20, 20, 20, 20, 20, 20
    .byte 21, 21, 21, 21, 21, 21
    .byte 22, 22, 22, 22, 22, 22
    .byte 23, 23, 23, 23, 23, 23
    .byte 24, 24, 24, 24, 24, 24

    .end

