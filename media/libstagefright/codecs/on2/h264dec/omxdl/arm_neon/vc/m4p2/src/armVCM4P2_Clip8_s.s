; /**
; * 
; * File Name:  armVCM4P2_Clip8_s.s
; * OpenMAX DL: v1.0.2
; * Revision:   12290
; * Date:       Wednesday, April 9, 2008
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
      

      M_VARIANTS CortexA8

      IF CortexA8
;//Input Arguments

pSrc                 RN 0
pDst                 RN 1
step                 RN 2

;// Neon Registers

qx0                  QN  Q0.S16                  
dx00                 DN  D0.S16
dx01                 DN  D1.S16
qx1                  QN  Q1.S16
dx10                 DN  D2.S16
dx11                 DN  D3.S16

qx2                  QN  Q2.S16                  
dx20                 DN  D4.S16
dx21                 DN  D5.S16
qx3                  QN  Q3.S16
dx30                 DN  D6.S16
dx31                 DN  D7.S16


dclip0               DN  D0.U8
dclip1               DN  D2.U8 
dclip2               DN  D4.U8
dclip3               DN  D6.U8
 
       M_START armVCM4P2_Clip8

       VLD1          {dx00,dx01,dx10,dx11},[pSrc]!          ;// Load 16 entries from pSrc
       VLD1          {dx20,dx21,dx30,dx31},[pSrc]!          ;// Load next 16 entries from pSrc  
       VQSHRUN       dclip0,qx0,#0                          ;// dclip0[i]=clip qx0[i] to [0,255]
       VQSHRUN       dclip1,qx1,#0                          ;// dclip1[i]=clip qx1[i] to [0,255]
       VST1          {dclip0},[pDst],step                   ;// store 8 bytes and pDst=pDst+step
       VST1          {dclip1},[pDst],step                   ;// store 8 bytes and pDst=pDst+step
       VQSHRUN       dclip2,qx2,#0
       VQSHRUN       dclip3,qx3,#0
       VST1          {dclip2},[pDst],step
       VST1          {dclip3},[pDst],step
       
       VLD1          {dx00,dx01,dx10,dx11},[pSrc]!          ;// Load 16 entries from pSrc
       VLD1          {dx20,dx21,dx30,dx31},[pSrc]!          ;// Load next 16 entries from pSrc  
       VQSHRUN       dclip0,qx0,#0                          ;// dclip0[i]=clip qx0[i] to [0,255]
       VQSHRUN       dclip1,qx1,#0                          ;// dclip1[i]=clip qx1[i] to [0,255]
       VST1          {dclip0},[pDst],step                   ;// store 8 bytes and pDst=pDst+step
       VST1          {dclip1},[pDst],step                   ;// store 8 bytes and pDst=pDst+step
       VQSHRUN       dclip2,qx2,#0
       VQSHRUN       dclip3,qx3,#0
       VST1          {dclip2},[pDst],step
       VST1          {dclip3},[pDst],step


       
        M_END
        ENDIF
        
     
        
        END
