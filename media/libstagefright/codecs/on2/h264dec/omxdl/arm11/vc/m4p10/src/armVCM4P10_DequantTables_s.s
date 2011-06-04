;//
;// 
;// File Name:  armVCM4P10_DequantTables_s.s
;// OpenMAX DL: v1.0.2
;// Revision:   9641
;// Date:       Thursday, February 7, 2008
;// 
;// (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
;// 
;// 
;//

        

         INCLUDE omxtypes_s.h
         INCLUDE armCOMM_s.h
     
         EXPORT armVCM4P10_QPDivTable
         EXPORT armVCM4P10_VMatrixQPModTable
         EXPORT armVCM4P10_PosToVCol4x4
         EXPORT armVCM4P10_PosToVCol2x2
         EXPORT armVCM4P10_VMatrix 
         EXPORT armVCM4P10_QPModuloTable
         EXPORT armVCM4P10_VMatrixU16
         
;// Define the processor variants supported by this file
         
         M_VARIANTS ARM1136JS
           
         
;// Guarding implementation by the processor name

    
    IF ARM1136JS :LOR: CortexA8
           
 
         M_TABLE armVCM4P10_PosToVCol4x4
         DCB  0, 2, 0, 2
         DCB  2, 1, 2, 1
         DCB  0, 2, 0, 2
         DCB  2, 1, 2, 1


         M_TABLE armVCM4P10_PosToVCol2x2
         DCB  0, 2
         DCB  2, 1


         M_TABLE armVCM4P10_VMatrix
         DCB  10, 16, 13
         DCB  11, 18, 14
         DCB  13, 20, 16
         DCB  14, 23, 18
         DCB  16, 25, 20
         DCB  18, 29, 23

;//-------------------------------------------------------
;// This table evaluates the expression [(INT)(QP/6)],
;// for values of QP from 0 to 51 (inclusive). 
;//-------------------------------------------------------

         M_TABLE armVCM4P10_QPDivTable
         DCB  0,  0,  0,  0,  0,  0
         DCB  1,  1,  1,  1,  1,  1
         DCB  2,  2,  2,  2,  2,  2
         DCB  3,  3,  3,  3,  3,  3
         DCB  4,  4,  4,  4,  4,  4
         DCB  5,  5,  5,  5,  5,  5
         DCB  6,  6,  6,  6,  6,  6
         DCB  7,  7,  7,  7,  7,  7
         DCB  8,  8,  8,  8,  8,  8
    
;//----------------------------------------------------
;// This table contains armVCM4P10_VMatrix[QP%6][0] entires,
;// for values of QP from 0 to 51 (inclusive). 
;//----------------------------------------------------

         M_TABLE armVCM4P10_VMatrixQPModTable
         DCB 10, 11, 13, 14, 16, 18
         DCB 10, 11, 13, 14, 16, 18
         DCB 10, 11, 13, 14, 16, 18
         DCB 10, 11, 13, 14, 16, 18
         DCB 10, 11, 13, 14, 16, 18
         DCB 10, 11, 13, 14, 16, 18
         DCB 10, 11, 13, 14, 16, 18
         DCB 10, 11, 13, 14, 16, 18
         DCB 10, 11, 13, 14, 16, 18
    
;//-------------------------------------------------------
;// This table evaluates the modulus expression [QP%6]*6,
;// for values of QP from 0 to 51 (inclusive). 
;//-------------------------------------------------------

         M_TABLE armVCM4P10_QPModuloTable
         DCB 0, 6, 12, 18, 24, 30
         DCB 0, 6, 12, 18, 24, 30
         DCB 0, 6, 12, 18, 24, 30
         DCB 0, 6, 12, 18, 24, 30
         DCB 0, 6, 12, 18, 24, 30
         DCB 0, 6, 12, 18, 24, 30
         DCB 0, 6, 12, 18, 24, 30
         DCB 0, 6, 12, 18, 24, 30
         DCB 0, 6, 12, 18, 24, 30
        
;//-------------------------------------------------------
;// This table contains the invidual byte values stored as
;// halfwords. This avoids unpacking inside the function
;//-------------------------------------------------------
        
         M_TABLE armVCM4P10_VMatrixU16
         DCW 10, 16, 13 
         DCW 11, 18, 14
         DCW 13, 20, 16
         DCW 14, 23, 18
         DCW 16, 25, 20
         DCW 18, 29, 23 
         
    ENDIF                                                           ;//ARM1136JS            


                           
    
         END