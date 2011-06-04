; **********
; * 
; * File Name:  omxVCM4P2_PredictReconCoefIntra_s.s
; * OpenMAX DL: v1.0.2
; * Revision:   12290
; * Date:       Wednesday, April 9, 2008
; * 
; * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
; * 
; * 
; * 
; * Description:
; * Contains module for DC/AC coefficient prediction
; *
; * 
; * Function: omxVCM4P2_PredictReconCoefIntra
; *
; * Description:
; * Performs adaptive DC/AC coefficient prediction for an intra block. Prior
; * to the function call, prediction direction (predDir) should be selected
; * as specified in subclause 7.4.3.1 of ISO/IEC 14496-2.
; *
; * Remarks:
; *
; * Parameters:
; * [in]  pSrcDst      pointer to the coefficient buffer which contains the 
; *                    quantized coefficient residuals (PQF) of the current 
; *                    block; must be aligned on a 4-byte boundary. The 
; *                    output coefficients are saturated to the range 
; *                    [-2048, 2047].
; * [in]  pPredBufRow  pointer to the coefficient row buffer; must be aligned
; *                    on a 4-byte boundary.
; * [in]  pPredBufCol  pointer to the coefficient column buffer; must be 
; *                    aligned on a 4-byte boundary.
; * [in]  curQP        quantization parameter of the current block. curQP may 
; *                    equal to predQP especially when the current block and 
; *                    the predictor block are in the same macroblock.
; * [in]  predQP       quantization parameter of the predictor block
; * [in]  predDir      indicates the prediction direction which takes one
; *                    of the following values:
; *                    OMX_VIDEO_HORIZONTAL    predict horizontally
; *                    OMX_VIDEO_VERTICAL        predict vertically
; * [in]  ACPredFlag   a flag indicating if AC prediction should be
; *                    performed. It is equal to ac_pred_flag in the bit
; *                    stream syntax of MPEG-4
; * [in]  videoComp    video component type (luminance, chrominance or
; *                    alpha) of the current block
; * [out] pSrcDst      pointer to the coefficient buffer which contains
; *                    the quantized coefficients (QF) of the current
; *                    block
; * [out] pPredBufRow  pointer to the updated coefficient row buffer
; * [out] pPredBufCol  pointer to the updated coefficient column buffer
; * Return Value:
; * OMX_Sts_NoErr - no error
; * OMX_Sts_BadArgErr - Bad arguments 
; * - At least one of the pointers is NULL: pSrcDst, pPredBufRow, or pPredBufCol.
; * - At least one the following cases: curQP <= 0, predQP <= 0, curQP >31, 
; *   predQP > 31, preDir exceeds [1,2].
; * - At least one of the pointers pSrcDst, pPredBufRow, or pPredBufCol is not 
; *   4-byte aligned.
; *
; *********
     
        INCLUDE omxtypes_s.h
        INCLUDE armCOMM_s.h
        
       M_VARIANTS CortexA8
       
             

       IMPORT        armVCM4P2_Reciprocal_QP_S32
       IMPORT        armVCM4P2_Reciprocal_QP_S16
       IMPORT        armVCM4P2_DCScaler
       
        IF CortexA8
;// Input Arguments

pSrcDst          RN 0
pPredBufRow      RN 1
pPredBufCol      RN 2
curQP            RN 3
QP               RN 3
predQP           RN 4
predDir          RN 5
ACPredFlag       RN 6
videoComp        RN 7

;// Local Variables

shortVideoHeader RN 4
dcScaler         RN 4
index            RN 6
predCoeffTable   RN 7
temp1            RN 6
temp2            RN 9
temp             RN 14
Const            RN 8
temppPredColBuf  RN 8
tempPred         RN 9

absCoeffDC       RN 8
negdcScaler      RN 10
Rem              RN 11
temp3            RN 12

dcRowbufCoeff    RN 10
dcColBuffCoeff   RN 11
Return           RN 0

;//NEON Registers

qPredRowBuf       QN Q0.S16
dPredRowBuf0      DN D0.S16
dPredRowBuf1      DN D1.S16




qCoeffTab         QN Q1.S32

qPredQP           QN Q2.S16
dPredQP0          DN D4.S16
dPredQP1          DN D5.S16


qtemp1            QN Q3.S32
qtemp             QN Q3.S16

dtemp0            DN D6.S16
dtemp1            DN D7.S16

dtemp2            DN D8.S16
dtemp3            DN D9.S16

dtemp4            DN D2.S16
dtemp5            DN D3.S16
dtemp6            DN D4.S16
dtemp7            DN D5.S16
 
qtempPred1        QN Q5.S32
qtempPred         QN Q5.S16

dtempPred0        DN D10.S16
dtempPred1        DN D11.S16
 
  

      M_START   omxVCM4P2_PredictReconCoefIntra,r11,d11

      ;// Assigning pointers to Input arguments on Stack
    
      M_ARG           predQPonStack,4  
      M_ARG           predDironStack,4
      M_ARG           ACPredFlagonStack,4
      M_ARG           videoComponStack,4
      
      ;// DC Prediction

      M_LDR           videoComp,videoComponStack                     ;// Load videoComp From Stack               
            
      M_LDR           predDir,predDironStack                         ;// Load Prediction direction
      ;// DC Scaler calculation   
      LDR             index, =armVCM4P2_DCScaler
      ADD             index,index,videoComp,LSL #5
      LDRB            dcScaler,[index,QP]

       
      LDR             predCoeffTable, =armVCM4P2_Reciprocal_QP_S16   ;// Loading the table with entries 32767/(1 to 63) 
      CMP             predDir,#2                                     ;// Check if the Prediction direction is vertical

      ;// Caulucate tempPred
            
      LDREQSH         absCoeffDC,[pPredBufRow]                       ;// If vetical load the coeff from Row Prediction Buffer
      LDRNESH         absCoeffDC,[pPredBufCol]                       ;// If horizontal load the coeff from column Prediction Buffer
      
      RSB             negdcScaler,dcScaler,#0                        ;// negdcScaler=-dcScaler   
      MOV             temp1,absCoeffDC                               ;// Load the Prediction coeff to temp for comparision                               
      CMP             temp1,#0                                       
      RSBLT           absCoeffDC,temp1,#0                            ;// calculate absolute val of prediction coeff
      
      ADD             temp,dcScaler,dcScaler
      LDRH            temp,[predCoeffTable,temp]                     ;// Load value from coeff table for performing division using multiplication
      SMULBB          tempPred,temp,absCoeffDC                       ;// tempped=pPredBufRow(Col)[0]*32767/dcScaler
      ADD             temp3,dcScaler,#1
      LSR             tempPred,tempPred,#15                          ;// tempped=pPredBufRow(Col)[0]/dcScaler                  
      LSR             temp3,temp3,#1                                 ;// temp3=round(dcScaler/2)           
      MLA             Rem,negdcScaler,tempPred,absCoeffDC            ;// Remainder Rem=abs(pPredBufRow(Col)[0])-tempPred*dcScaler
      
      LDRH            dcRowbufCoeff,[pPredBufCol]            
      
      CMP             Rem,temp3                                      ;// compare Rem with (dcScaler/2)
      ADDGE           tempPred,#1                                    ;// tempPred=tempPred+1 if Rem>=(dcScaler/2)
      CMP             temp1,#0
      RSBLT           tempPred,tempPred,#0                           ;// tempPred=-tempPred if 
       
      STRH            dcRowbufCoeff,[pPredBufRow,#-16]      
       

      LDRH            temp,[pSrcDst]                                 ;// temp=pSrcDst[0]
      ADD             temp,temp,tempPred                             ;// temp=pSrcDst[0]+tempPred
      SSAT16          temp,#12,temp                                  ;// clip temp to [-2048,2047]
      SMULBB          dcColBuffCoeff,temp,dcScaler                   ;// temp1=clipped(pSrcDst[0])*dcScaler           
      M_LDR           ACPredFlag,ACPredFlagonStack
      STRH            dcColBuffCoeff,[pPredBufCol]      
      

       ;// AC Prediction
      
      M_LDR           predQP,predQPonStack
      
      CMP             ACPredFlag,#1                                  ;// Check if the AC prediction flag is set or not
      BNE             Exit                                           ;// If not set Exit
      CMP             predDir,#2                                     ;// Check the Prediction direction                       
      LDR             predCoeffTable, =armVCM4P2_Reciprocal_QP_S32   ;// Loading the table with entries 0x1ffff/(1 to 63) 
      MOV             Const,#4
      MUL             curQP,curQP,Const                              ;// curQP=4*curQP
      VDUP            dPredQP0,predQP
      LDR             temp2,[predCoeffTable,curQP]                   ;// temp=0x1ffff/curQP
      VDUP            qCoeffTab,temp2
      BNE             Horizontal                                     ;// If the Prediction direction is horizontal branch to Horizontal
      
     
      
      ;// Vertical
      ;//Calculating tempPred

      VLD1            {dPredRowBuf0,dPredRowBuf1},[pPredBufRow]      ;// Loading pPredBufRow[i]:i=0 t0 7
      
      VMULL           qtemp1,dPredRowBuf0,dPredQP0                   ;//qtemp1[i]=pPredBufRow[i]*dPredQP[i]: i=0 t0 3
      VMUL            qtempPred1,qtemp1,qCoeffTab                    ;//qtempPred1[i]=pPredBufRow[i]*dPredQP[i]*0x1ffff/curQP : i=0 t0 3
      
      VMULL           qtemp1,dPredRowBuf1,dPredQP0                   ;//qtemp1[i]=pPredBufRow[i]*dPredQP[i] : i=4 t0 7      

      VRSHR           qtempPred1,qtempPred1,#17                      ;//qtempPred1[i]=round(pPredBufRow[i]*dPredQP[i]/curQP) : i=0 t0 3
      VSHRN           dPredQP1,qtempPred1,#0                         ;// narrow qtempPred1[i] to 16 bits
      
      
      VMUL            qtempPred1,qtemp1,qCoeffTab                    ;//qtempPred1[i]=pPredBufRow[i]*dPredQP[i]*0x1ffff/curQP : i=4 t0 7
      VRSHR           qtempPred1,qtempPred1,#17                      ;//qtempPred1[i]=round(pPredBufRow[i]*dPredQP[i]/curQP)  : i=4 t0 7
      VLD1            {dtemp0,dtemp1},[pSrcDst]                      ;//Loading pSrcDst[i] : i=0 to 7
      VSHRN           dtempPred1,qtempPred1,#0                       ;// narrow qtempPred1[i] to 16 bits
      VMOV            dtempPred0,dPredQP1
      
      ;//updating source and row prediction buffer contents      
      VADD            qtemp,qtemp,qtempPred                          ;//pSrcDst[i]=pSrcDst[i]+qtempPred[i]: i=0 to 7 
      VQSHL           qtemp,qtemp,#4                                 ;//Clip to [-2048,2047]
      LDRH            dcRowbufCoeff,[pPredBufRow]                    ;//Loading Dc Value of Row Prediction buffer
      VSHR            qtemp,qtemp,#4
      
      VST1            {dtemp0,dtemp1},[pSrcDst]                      ;//storing back the updated values 
      VST1            {dtemp0,dtemp1},[pPredBufRow]                  ;//storing back the updated row prediction values                      
      STRH            dcRowbufCoeff,[pPredBufRow]                    ;// storing the updated DC Row Prediction coeff
      
      B               Exit

Horizontal

      ;// Calculating Temppred

            

      VLD1            {dPredRowBuf0,dPredRowBuf1},[pPredBufCol]      ;// Loading pPredBufCol[i]:i=0 t0 7
      VMULL           qtemp1,dPredRowBuf0,dPredQP0                   ;//qtemp1[i]=pPredBufCol[i]*dPredQP[i]: i=0 t0 3
      VMUL            qtempPred1,qtemp1,qCoeffTab                    ;//qtempPred1[i]=pPredBufCol[i]*dPredQP[i]*0x1ffff/curQP : i=0 t0 3
      
      VMULL           qtemp1,dPredRowBuf1,dPredQP0                   ;//qtemp1[i]=pPredBufCol[i]*dPredQP[i] : i=4 t0 7      

      VRSHR           qtempPred1,qtempPred1,#17                      ;//qtempPred1[i]=round(pPredBufCol[i]*dPredQP[i]/curQP) : i=0 t0 3
      VSHRN           dPredQP1,qtempPred1,#0                         ;// narrow qtempPred1[i] to 16 bits
      
      
      VMUL            qtempPred1,qtemp1,qCoeffTab                    ;//qtempPred1[i]=pPredBufCol[i]*dPredQP[i]*0x1ffff/curQP : i=4 t0 7
      
      MOV             temppPredColBuf,pPredBufCol
      VRSHR           qtempPred1,qtempPred1,#17                      ;//qtempPred1[i]=round(pPredBufCol[i]*dPredQP[i]/curQP)  : i=4 t0 7
      VLD4            {dtemp0,dtemp1,dtemp2,dtemp3},[pSrcDst]        ;// Loading coefficients Interleaving by 4
      VSHRN           dtempPred1,qtempPred1,#0                       ;// narrow qtempPred1[i] to 16 bits
      VMOV            dtempPred0,dPredQP1
      
      ;// Updating source and column prediction buffer contents     
      ADD             temp2,pSrcDst,#32                                  
      VLD4            {dtemp4,dtemp5,dtemp6,dtemp7},[temp2]          ;// Loading next 16 coefficients Interleaving by 4
      VUZP            dtemp0,dtemp4                                  ;// Interleaving by 8
      VADD            dtemp0,dtemp0,dtempPred0                       ;// Adding tempPred to coeffs
      VQSHL           dtemp0,dtemp0,#4                               ;// Clip to [-2048,2047]
      VSHR            dtemp0,dtemp0,#4
      VST1            {dtemp0},[pPredBufCol]!                        ;// Updating Pridiction column buffer
      VZIP            dtemp0,dtemp4                                  ;// deinterleaving
      VST4            {dtemp0,dtemp1,dtemp2,dtemp3},[pSrcDst]        ;// Updating source coeffs         
      VST4            {dtemp4,dtemp5,dtemp6,dtemp7},[temp2]!
      
      MOV             temp1,temp2                                     
      VLD4            {dtemp0,dtemp1,dtemp2,dtemp3},[temp2]!         ;// Loading  coefficients Interleaving by 4
      
      VLD4            {dtemp4,dtemp5,dtemp6,dtemp7},[temp2]
      VUZP            dtemp0,dtemp4                                  ;// Interleaving by 8
      VADD            dtemp0,dtemp0,dtempPred1
      VQSHL           dtemp0,dtemp0,#4                               ;// Clip to [-2048,2047]
      VSHR            dtemp0,dtemp0,#4
      VST1            {dtemp0},[pPredBufCol]!
      VZIP            dtemp0,dtemp4
      VST4            {dtemp0,dtemp1,dtemp2,dtemp3},[temp1]
      STRH            dcColBuffCoeff,[temppPredColBuf] 
      VST4            {dtemp4,dtemp5,dtemp6,dtemp7},[temp2]
      
Exit

      STRH            temp,[pSrcDst]
          
 
      MOV             Return,#OMX_Sts_NoErr 
 
      M_END
      ENDIF


       END


   
