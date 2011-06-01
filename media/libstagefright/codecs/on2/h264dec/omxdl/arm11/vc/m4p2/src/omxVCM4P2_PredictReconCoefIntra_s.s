; **********
; * 
; * File Name:  omxVCM4P2_PredictReconCoefIntra_s.s
; * OpenMAX DL: v1.0.2
; * Revision:   9641
; * Date:       Thursday, February 7, 2008
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
        
       M_VARIANTS ARM1136JS
       
             

       IMPORT        armVCM4P2_Reciprocal_QP_S32
       IMPORT        armVCM4P2_Reciprocal_QP_S16
       IMPORT        armVCM4P2_DCScaler
       


        IF ARM1136JS


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

temp2            RN 5
negCurQP         RN 7
negdcScaler      RN 7
tempPred         RN 8

dcScaler         RN 4
CoeffTable       RN 9
absCoeffDC       RN 9
temp3            RN 6
absCoeffAC       RN 6

shortVideoHeader RN 9
predCoeffTable   RN 10
Count            RN 10
temp1            RN 12
index            RN 12
Rem              RN 14
temp             RN 11
Return           RN 0

       

       M_START   omxVCM4P2_PredictReconCoefIntra,r12
       
       ;// Assigning pointers to Input arguments on Stack
    
       M_ARG           predQPonStack,4  
       M_ARG           predDironStack,4
       M_ARG           ACPredFlagonStack,4
       M_ARG           videoComponStack,4
       
       ;// DC Prediction

       M_LDR           videoComp,videoComponStack                     ;// Load videoComp From Stack               
       
       M_LDR           predDir,predDironStack                         ;// Load Prediction direction
       
       ;// dcScaler Calculation

       LDR             index, =armVCM4P2_DCScaler
       ADD             index,index,videoComp,LSL #5
       LDRB            dcScaler,[index,QP]
           
    
calDCVal
      
       
       LDR             predCoeffTable, =armVCM4P2_Reciprocal_QP_S16   ;// Loading the table with entries 32767/(1 to 63) 
      
       CMP             predDir,#2                                     ;// Check if the Prediction direction is vertical

       ;// Caulucate temp pred by performing Division
            
       LDREQSH         absCoeffDC,[pPredBufRow]                       ;// If vetical load the coeff from Row Prediction Buffer
       LDRNESH         absCoeffDC,[pPredBufCol]                       ;// If horizontal load the coeff from column Prediction Buffer
       
       RSB             negdcScaler,dcScaler,#0                        ;// negdcScaler=-dcScaler  
       
       MOV             temp1,absCoeffDC                               ;// temp1=prediction coeff
       CMP             temp1,#0
       RSBLT           absCoeffDC,temp1,#0                            ;//absCoeffDC=abs(temp1)
       
       ADD             temp,dcScaler,dcScaler
       LDRH            temp,[predCoeffTable,temp]                     ;// Load value from coeff table for performing division using multiplication
       
       SMULBB          tempPred,temp,absCoeffDC                       ;// tempPred=pPredBufRow(Col)[0]*32767/dcScaler
       ADD             temp3,dcScaler,#1
       LSR             tempPred,tempPred,#15                          ;// tempPred=pPredBufRow(Col)[0]/dcScaler          
       LSR             temp3,temp3,#1                                 ;// temp3=round(dcScaler/2)
       
       MLA             Rem,negdcScaler,tempPred,absCoeffDC            ;// Rem = pPredBufRow(Col)[0]-tempPred*dcScaler      
       
       
       LDRH            temp,[pPredBufCol]
       CMP             Rem,temp3                                      
       ADDGE           tempPred,#1                                    ;// If Rem>=round(dcScaler/2);tempPred=tempPred+1
       CMP             temp1,#0
       RSBLT           tempPred,tempPred,#0                            ;/ if pPredBufRow(Col)[0]<0; tempPred=-tempPred
             
       
       STRH            temp,[pPredBufRow,#-16]      

       LDRH            temp,[pSrcDst]                                 ;// temp=pSrcDst[0]
       M_LDR           ACPredFlag,ACPredFlagonStack
       ADD             temp,temp,tempPred                             ;// temp=pSrcDst[0]+tempPred
       SSAT16          temp,#12,temp                                  ;// clip temp to [-2048,2047]
       
       SMULBB          temp1,temp,dcScaler                            ;// temp1=clipped(pSrcDst[0])*dcScaler           
       M_LDR           predQP,predQPonStack
       STRH            temp,[pSrcDst]                                 
       CMP             ACPredFlag,#1                                  ;// Check if the AC prediction flag is set or not
       STRH            temp1,[pPredBufCol]                            ;// store temp1 to pPredBufCol
 
       ;// AC Prediction

              
       BNE             Exit                                           ;// If not set Exit
       
       LDR             predCoeffTable, =armVCM4P2_Reciprocal_QP_S32   ;// Loading the table with entries 0x1ffff/(1 to 63)
       MOV             temp1,#4
       MUL             temp1,curQP,temp1
       CMP             predDir,#2                                     ;// Check the Prediction direction
       RSB             negCurQP,curQP,#0                                  
       LDR             CoeffTable,[predCoeffTable,temp1]              ;// CoeffTable=0x1ffff/curQP
       ADD             curQP,curQP,#1                                 ;// curQP=curQP+1
       LSR             curQP,curQP,#1                                 ;// curQP=round(curQP/2)                
       MOV             Count,#2                                       ;// Initializing the Loop Count
       BNE             Horizontal                                     ;// If the Prediction direction is horizontal branch to Horizontal

       

loop1       
       ;// Calculate tempPred
       
       LDRSH           absCoeffAC,[pPredBufRow,Count]                 ;// absCoeffAC=pPredBufRow[i], 1=<i<=7
       MOV             temp1,absCoeffAC
       CMP             temp1,#0                                       ;// compare pPredBufRow[i] with zero, 1=<i<=7
       RSBLT           absCoeffAC,temp1,#0                            ;// absCoeffAC= abs(pPredBufRow[i])
                                            
       SMULBB          absCoeffAC,absCoeffAC,predQP                   ;// temp1=pPredBufRow[i]*predQP
       MUL             tempPred,absCoeffAC,CoeffTable                 ;// tempPred=pPredBufRow[i]*predQP*0x1ffff/curQP
       LSR             tempPred,tempPred,#17          
             
       MLA             Rem,negCurQP,tempPred,absCoeffAC               ;// Rem=abs(pPredBufRow[i])-tempPred*curQP
       LDRH            temp,[pSrcDst,Count]                           ;// temp=pSrcDst[i],1<=i<8
       
       CMP             Rem,curQP
       ADDGE           tempPred,#1                                    ;// if Rem>=round(curQP/2); tempPred=tempPred+1
       CMP             temp1,#0
       RSBLT           tempPred,tempPred,#0                           ;// if pPredBufRow[i]<0 ; tempPred=-tempPred
              
       ;// Update source and Row Prediction buffers
       
       ADD             temp,temp,tempPred                             ;// temp=tempPred+pSrcDst[i]
       SSAT16          temp,#12,temp                                  ;// Clip temp to [-2048,2047]
       STRH            temp,[pSrcDst,Count]
       STRH            temp,[pPredBufRow,Count]                       ;// pPredBufRow[i]=temp
       ADD             Count,Count,#2                                 ;// i=i+1
       CMP             Count,#16                                      ;// compare if i=8
       BLT             loop1
       B               Exit                                           ;// Branch to exit

Horizontal

       MOV             Count,#16                                      ;// Initializing i=8

loop2  
     
       LSR             temp2,Count,#3                                 ;// temp2=i>>3
       
       ;// Calculate tempPred
       
       LDRH            absCoeffAC,[pPredBufCol,temp2]                 ;// absCoefAC=pPredBufCol[i>>3]                       
       MOV             temp1,absCoeffAC
       CMP             temp1,#0                                       ;// compare pPredBufRow[i] with zero, 1=<i<=7
       RSBLT           absCoeffAC,temp1,#0                            ;// absCoeffAC=abs(pPredBufCol[i>>3])
                                      
       SMULBB          absCoeffAC,absCoeffAC,predQP                   ;// temp1=pPredBufCol[i>>3]*predQP
       MUL             tempPred,absCoeffAC,CoeffTable                 ;// tempPred=pPredBufCol[i>>3]*predQP*0x1ffff/curQP
       LSR             tempPred,tempPred,#17                          ;// tempPred=pPredBufCol[i>>3]*predQP/curQP
       
       MLA             Rem,negCurQP,tempPred,absCoeffAC
       LDRH            temp,[pSrcDst,Count]                           ;// temp=pSrcDst[i]
       
       CMP             Rem,curQP                                      ;// Compare Rem with round(curQP/2)
       ADDGE           tempPred,#1                                    ;// tempPred=tempPred+1 if Rem>=round(curQP/2)
       CMP             temp1,#0
       RSBLT           tempPred,tempPred,#0                           ;// if pPredBufCol[i>>3 <0 tempPred=-tempPred
       
       ;// Update source and Row Prediction buffers
       
       ADD             temp,temp,tempPred                             ;// temp=pSrcDst[i]+tempPred
       SSAT16          temp,#12,temp                                  ;// Clip temp to [-2048,2047]
       STRH            temp,[pSrcDst,Count]                           ;// pSrcDst[0]= clipped value
       STRH            temp,[pPredBufCol,temp2]                       ;// pPredBufCol[i>>3]=temp
       ADD             Count,Count,#16                                ;// i=i+8
       CMP             Count,#128                                     ;// compare i with 64
       BLT             loop2

             
Exit
  
       MOV             Return,#OMX_Sts_NoErr 

       M_END
       ENDIF
       END


   
