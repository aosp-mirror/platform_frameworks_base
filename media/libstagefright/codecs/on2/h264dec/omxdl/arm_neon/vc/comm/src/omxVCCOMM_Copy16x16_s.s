 ;/**
 ; * Function: omxVCCOMM_Copy16x16
 ; *
 ; * Description:
 ; * Copies the reference 16x16 block to the current block.
 ; * Parameters:
 ; * [in] pSrc         - pointer to the reference block in the source frame; must be aligned on an 16-byte boundary.
 ; * [in] step         - distance between the starts of consecutive lines in the reference frame, in bytes;
 ; *                     must be a multiple of 16 and must be larger than or equal to 16.
 ; * [out] pDst        - pointer to the destination block; must be aligned on an 8-byte boundary.
 ; * Return Value:
 ; * OMX_Sts_NoErr     - no error
 ; * OMX_Sts_BadArgErr - bad arguments; returned under any of the following conditions:
 ; *                   - one or more of the following pointers is NULL:  pSrc, pDst
 ; *                   - one or more of the following pointers is not aligned on an 16-byte boundary:  pSrc, pDst
 ; *                   - step <16 or step is not a multiple of 16.  
 ; */

   INCLUDE omxtypes_s.h
   
     
     M_VARIANTS CortexA8
     
     IF CortexA8
     
     
 ;//Input Arguments
pSrc    RN 0        
pDst    RN 1        
step    RN 2

;//Local Variables
Return  RN 0
;// Neon Registers

X0      DN D0.S8 
X1      DN D1.S8 
X2      DN D2.S8
X3      DN D3.S8
X4      DN D4.S8
X5      DN D5.S8
X6      DN D6.S8
X7      DN D7.S8 
 
     M_START omxVCCOMM_Copy16x16
         
        
        VLD1  {X0,X1},[pSrc@128],step       ;// Load 16 bytes from 16 byte aligned pSrc and pSrc=pSrc + step after loading
        VLD1  {X2,X3},[pSrc@128],step
        VLD1  {X4,X5},[pSrc@128],step
        VLD1  {X6,X7},[pSrc@128],step
        
        VST1  {X0,X1,X2,X3},[pDst@128]!     ;// Store 32 bytes to 16 byte aligned pDst   
        VST1  {X4,X5,X6,X7},[pDst@128]!        
               
         
        VLD1  {X0,X1},[pSrc@128],step
        VLD1  {X2,X3},[pSrc@128],step
        VLD1  {X4,X5},[pSrc@128],step
        VLD1  {X6,X7},[pSrc@128],step
        
        VST1  {X0,X1,X2,X3},[pDst@128]!
        VST1  {X4,X5,X6,X7},[pDst@128]!
         
      
        VLD1  {X0,X1},[pSrc@128],step
        VLD1  {X2,X3},[pSrc@128],step
        VLD1  {X4,X5},[pSrc@128],step
        VLD1  {X6,X7},[pSrc@128],step
        
        VST1  {X0,X1,X2,X3},[pDst@128]!              
        VST1  {X4,X5,X6,X7},[pDst@128]!        
        
        
        VLD1  {X0,X1},[pSrc@128],step
        VLD1  {X2,X3},[pSrc@128],step
        VLD1  {X4,X5},[pSrc@128],step
        VLD1  {X6,X7},[pSrc@128],step
        
        VST1  {X0,X1,X2,X3},[pDst@128]!
        VST1  {X4,X5,X6,X7},[pDst@128]!

        
        MOV   Return,#OMX_Sts_NoErr

     
        
        M_END
        ENDIF



        
        END
       