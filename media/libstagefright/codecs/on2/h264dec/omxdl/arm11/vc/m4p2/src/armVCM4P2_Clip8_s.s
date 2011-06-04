; /**
; * 
; * File Name:  armVCM4P2_Clip8_s.s
; * OpenMAX DL: v1.0.2
; * Revision:   9641
; * Date:       Thursday, February 7, 2008
; * 
; * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
; * 
; * 
; *
; * Description: 
; * Contains module for Clipping 16 bit value to [0,255] Range
; */ 

      INCLUDE omxtypes_s.h
      INCLUDE armCOMM_s.h
      

      M_VARIANTS ARM1136JS

        
     
      IF ARM1136JS
 
;//Input Arguments

pSrc                 RN 0
pDst                 RN 1
step                 RN 2

;// Local variables

x0                   RN 3
x1                   RN 4
x2                   RN 5
x3                   RN 6

Count                RN 14
     
        
        M_START armVCM4P2_Clip8,r6
       

        MOV          Count,#8
loop

        LDMIA        pSrc!,{x0, x1}
        SUBS         Count,Count, #1          ;// count down
        LDMIA        pSrc!,{x2, x3}
        USAT16       x0, #8, x0                 ;// clip two samples to [0,255]
        USAT16       x1, #8, x1                 ;// clip two samples to [0,255]
        STRB         x0, [pDst]
        MOV          x0, x0, LSR #16
        STRB         x0, [pDst,#1]
        STRB         x1, [pDst,#2]
        MOV          x1, x1, LSR #16
        STRB         x1, [pDst,#3]
                
        USAT16       x2, #8, x2                 ;// clip two samples to [0,255]
        USAT16       x3, #8, x3                 ;// clip two samples to [0,255]
        STRB         x2, [pDst,#4]
        MOV          x2, x2, LSR #16
        STRB         x2, [pDst,#5]
        STRB         x3, [pDst,#6]
        MOV          x3, x3, LSR #16
        STRB         x3, [pDst,#7]
        ADD          pDst,pDst,step             ;// Increment pDst by step value
         
        BGT          loop                       ;// Continue loop until Count reaches 64 

        M_END
        ENDIF
        
        END
