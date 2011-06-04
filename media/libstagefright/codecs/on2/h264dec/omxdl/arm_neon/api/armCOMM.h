/**
 * 
 * File Name:  armCOMM.h
 * OpenMAX DL: v1.0.2
 * Revision:   12290
 * Date:       Wednesday, April 9, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *   
 * File: armCOMM.h
 * Brief: Declares Common APIs/Data Types used across OpenMAX API's
 *
 */
 
  
#ifndef _armCommon_H_
#define _armCommon_H_

#include "omxtypes.h"

typedef struct
{
  OMX_F32 Re; /** Real part */
  OMX_F32 Im; /** Imaginary part */	
        
} OMX_FC32; /** single precision floating point complex number */

typedef struct
{
  OMX_F64 Re; /** Real part */
  OMX_F64 Im; /** Imaginary part */	
        
} OMX_FC64; /** double precision floating point complex number */


/* Used by both IP and IC domains for 8x8 JPEG blocks. */
typedef OMX_S16 ARM_BLOCK8x8[64];


#include "armOMX.h"

#define  armPI (OMX_F64)(3.1415926535897932384626433832795)

/***********************************************************************/

/* Compiler extensions */
#ifdef ARM_DEBUG
/* debug version */
#include <stdlib.h>
#include <assert.h>
#include <stdio.h>
#define armError(str) {printf((str)); printf("\n"); exit(-1);}
#define armWarn(str) {printf((str)); printf("\n");}
#define armIgnore(a) ((void)a)
#define armAssert(a) assert(a)
#else 
/* release version */
#define armError(str) ((void) (str))
#define armWarn(str)  ((void) (str))
#define armIgnore(a)  ((void) (a))
#define armAssert(a)  ((void) (a))
#endif /* ARM_DEBUG */

/* Arithmetic operations */

#define armMin(a,b)             ( (a) > (b) ?  (b):(a) )
#define armMax(a,b)             ( (a) > (b) ?  (a):(b) )
#define armAbs(a)               ( (a) <  0  ? -(a):(a) )

/* Alignment operation */

#define armAlignToBytes(Ptr,N)      (Ptr + ( ((N-(int)Ptr)&(N-1)) / sizeof(*Ptr) ))
#define armAlignTo2Bytes(Ptr)       armAlignToBytes(Ptr,2)
#define armAlignTo4Bytes(Ptr)       armAlignToBytes(Ptr,4)
#define armAlignTo8Bytes(Ptr)       armAlignToBytes(Ptr,8)
#define armAlignTo16Bytes(Ptr)      armAlignToBytes(Ptr,16)

/* Error and Alignment check */

#define armRetArgErrIf(condition, code)  if(condition) { return (code); }
#define armRetDataErrIf(condition, code) if(condition) { return (code); }

#ifndef ALIGNMENT_DOESNT_MATTER
#define armIsByteAligned(Ptr,N)     ((((int)(Ptr)) % N)==0)
#define armNotByteAligned(Ptr,N)    ((((int)(Ptr)) % N)!=0)
#else
#define armIsByteAligned(Ptr,N)     (1)
#define armNotByteAligned(Ptr,N)    (0)
#endif

#define armIs2ByteAligned(Ptr)      armIsByteAligned(Ptr,2)
#define armIs4ByteAligned(Ptr)      armIsByteAligned(Ptr,4)
#define armIs8ByteAligned(Ptr)      armIsByteAligned(Ptr,8)
#define armIs16ByteAligned(Ptr)     armIsByteAligned(Ptr,16)

#define armNot2ByteAligned(Ptr)     armNotByteAligned(Ptr,2)
#define armNot4ByteAligned(Ptr)     armNotByteAligned(Ptr,4)
#define armNot8ByteAligned(Ptr)     armNotByteAligned(Ptr,8)
#define armNot16ByteAligned(Ptr)    armNotByteAligned(Ptr,16)
#define armNot32ByteAligned(Ptr)    armNotByteAligned(Ptr,32)

/**
 * Function: armRoundFloatToS16_ref/armRoundFloatToS32_ref/armRoundFloatToS64
 *
 * Description:
 * Converts a double precision value into a short int/int after rounding
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_S16/OMX_S32 format
 *
 */

OMX_S16 armRoundFloatToS16 (OMX_F64 Value);
OMX_S32 armRoundFloatToS32 (OMX_F64 Value);
OMX_S64 armRoundFloatToS64 (OMX_F64 Value);

/**
 * Function: armSatRoundFloatToS16_ref/armSatRoundFloatToS32
 *
 * Description:
 * Converts a double precision value into a short int/int after rounding and saturation
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_S16/OMX_S32 format
 *
 */

OMX_S16 armSatRoundFloatToS16 (OMX_F64 Value);
OMX_S32 armSatRoundFloatToS32 (OMX_F64 Value);

/**
 * Function: armSatRoundFloatToU16_ref/armSatRoundFloatToU32
 *
 * Description:
 * Converts a double precision value into a unsigned short int/int after rounding and saturation
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_U16/OMX_U32 format
 *
 */

OMX_U16 armSatRoundFloatToU16 (OMX_F64 Value);
OMX_U32 armSatRoundFloatToU32 (OMX_F64 Value);

/**
 * Function: armSignCheck
 *
 * Description:
 * Checks the sign of a variable:
 * returns 1 if it is Positive
 * returns 0 if it is 0
 * returns -1 if it is Negative 
 *
 * Remarks:
 *
 * Parameters:
 * [in]	    var     Variable to be checked
 *
 * Return Value:
 * OMX_INT --   returns 1 if it is Positive
 *              returns 0 if it is 0
 *              returns -1 if it is Negative 
 */ 
 
OMX_INT armSignCheck (OMX_S16 var);

/**
 * Function: armClip
 *
 * Description: Clips the input between MAX and MIN value
 * 
 *
 * Remarks:
 *
 * Parameters:
 * [in] Min     lower bound
 * [in] Max     upper bound
 * [in] src     variable to the clipped
 *
 * Return Value:
 * OMX_S32 --   returns clipped value
 */ 
 
OMX_S32 armClip (
        OMX_INT min,
        OMX_INT max, 
        OMX_S32 src
        );

/**
 * Function: armClip_F32
 *
 * Description: Clips the input between MAX and MIN value
 * 
 *
 * Remarks:
 *
 * Parameters:
 * [in] Min     lower bound
 * [in] Max     upper bound
 * [in] src     variable to the clipped
 *
 * Return Value:
 * OMX_F32 --   returns clipped value
 */ 
 
OMX_F32 armClip_F32 (
        OMX_F32 min,
        OMX_F32 max, 
        OMX_F32 src
        );

/**
 * Function: armShiftSat_F32
 *
 * Description: Divides a float value by 2^shift and 
 * saturates it for unsigned value range for satBits.
 * Second parameter is like "shifting" the corresponding 
 * integer value. Takes care of rounding while clipping the final 
 * value.
 *
 * Parameters:
 * [in] v          Number to be operated upon
 * [in] shift      Divides the input "v" by "2^shift"
 * [in] satBits    Final range is [0, 2^satBits)
 *
 * Return Value:
 * OMX_S32 --   returns "shifted" saturated value
 */ 
 
OMX_U32 armShiftSat_F32(
        OMX_F32 v, 
        OMX_INT shift, 
        OMX_INT satBits
        );

/**
 * Functions: armSwapElem
 *
 * Description:
 * This function swaps two elements at the specified pointer locations.
 * The size of each element could be anything as specified by <elemSize>
 *
 * Return Value:
 * OMXResult -- Error status from the function
 */
OMXResult armSwapElem(OMX_U8 *pBuf1, OMX_U8 *pBuf2, OMX_INT elemSize);


/**
 * Function: armMedianOf3
 *
 * Description: Finds the median of three numbers
 * 
 * Remarks:
 *
 * Parameters:
 * [in] fEntry     First entry
 * [in] sEntry     second entry
 * [in] tEntry     Third entry
 *
 * Return Value:
 * OMX_S32 --   returns the median value
 */ 
 
OMX_S32 armMedianOf3 (
    OMX_S32 fEntry,
    OMX_S32 sEntry, 
    OMX_S32 tEntry 
    );

/**
 * Function: armLogSize
 *
 * Description: Finds the size of a positive value and returns the same
 * 
 * Remarks:
 *
 * Parameters:
 * [in] value    Positive value
 *
 * Return Value:
 * OMX_U8 --   returns the size of the positive value
 */ 
 
OMX_U8 armLogSize (
    OMX_U16 value 
    );    

/***********************************************************************/
                /* Saturating Arithmetic operations */

/**
 * Function :armSatAdd_S32()
 *
 * Description :
 *   Returns the result of saturated addition of the two inputs Value1, Value2
 *
 * Parametrs:
 * [in] Value1       First Operand
 * [in] Value2       Second Operand
 *
 * Return:
 * [out]             Result of operation
 * 
 *    
 **/

OMX_S32 armSatAdd_S32(
                OMX_S32 Value1,
                OMX_S32 Value2
                );

/**
 * Function :armSatAdd_S64()
 *
 * Description :
 *   Returns the result of saturated addition of the two inputs Value1, Value2
 *
 * Parametrs:
 * [in] Value1       First Operand
 * [in] Value2       Second Operand
 *
 * Return:
 * [out]             Result of operation
 * 
 *    
 **/

OMX_S64 armSatAdd_S64(
                OMX_S64 Value1,
                OMX_S64 Value2
                );

/** Function :armSatSub_S32()
 * 
 * Description :
 *     Returns the result of saturated substraction of the two inputs Value1, Value2
 *
 * Parametrs:
 * [in] Value1       First Operand
 * [in] Value2       Second Operand
 *
 * Return:
 * [out]             Result of operation
 * 
 **/

OMX_S32 armSatSub_S32(
                    OMX_S32 Value1,
                    OMX_S32 Value2
                    );

/**
 * Function :armSatMac_S32()
 *
 * Description :
 *     Returns the result of Multiplication of Value1 and Value2 and subesquent saturated
 *     accumulation with Mac
 *
 * Parametrs:
 * [in] Value1       First Operand
 * [in] Value2       Second Operand
 * [in] Mac          Accumulator
 *
 * Return:
 * [out]             Result of operation
 **/

OMX_S32 armSatMac_S32(
                    OMX_S32 Mac,
                    OMX_S16 Value1,
                    OMX_S16 Value2
                    );

/**
 * Function :armSatMac_S16S32_S32
 *
 * Description :
 *   Returns the result of saturated MAC operation of the three inputs delayElem, filTap , mac
 *
 *   mac = mac + Saturate_in_32Bits(delayElem * filTap)
 *
 * Parametrs:
 * [in] delayElem    First 32 bit Operand
 * [in] filTap       Second 16 bit Operand
 * [in] mac          Result of MAC operation
 *
 * Return:
 * [out]  mac        Result of operation
 *    
 **/
 
OMX_S32 armSatMac_S16S32_S32(
                        OMX_S32 mac, 
                        OMX_S32 delayElem, 
                        OMX_S16 filTap );

/**
 * Function :armSatRoundRightShift_S32_S16
 *
 * Description :
 *   Returns the result of rounded right shift operation of input by the scalefactor
 *
 *   output = Saturate_in_16Bits( ( RightShift( (Round(input) , scaleFactor ) )
 *
 * Parametrs:
 * [in] input       The input to be operated on
 * [in] scaleFactor The shift number
 *
 * Return:
 * [out]            Result of operation
 *    
 **/


OMX_S16 armSatRoundRightShift_S32_S16(
                        OMX_S32 input, 
                        OMX_INT scaleFactor);

/**
 * Function :armSatRoundLeftShift_S32()
 *
 * Description :
 *     Returns the result of saturating left-shift operation on input
 *     Or rounded Right shift if the input Shift is negative.
 *
 * Parametrs:
 * [in] Value        Operand
 * [in] shift        Operand for shift operation
 *
 * Return:
 * [out]             Result of operation
 *    
 **/
 
OMX_S32 armSatRoundLeftShift_S32(
                        OMX_S32 Value,
                        OMX_INT shift
                        );

/**
 * Function :armSatRoundLeftShift_S64()
 *
 * Description :
 *     Returns the result of saturating left-shift operation on input
 *     Or rounded Right shift if the input Shift is negative.
 *
 * Parametrs:
 * [in] Value        Operand
 * [in] shift        Operand for shift operation
 *
 * Return:
 * [out]             Result of operation
 *    
 **/
 
OMX_S64 armSatRoundLeftShift_S64(
                        OMX_S64 Value,
                        OMX_INT shift
                        );

/**
 * Function :armSatMulS16S32_S32()
 *
 * Description :
 *     Returns the result of a S16 data type multiplied with an S32 data type
 *     in a S32 container
 *
 * Parametrs:
 * [in] input1       Operand 1
 * [in] input2       Operand 2
 *
 * Return:
 * [out]             Result of operation
 *    
 **/


OMX_S32 armSatMulS16S32_S32(
                    OMX_S16 input1,
                    OMX_S32 input2);

/**
 * Function :armSatMulS32S32_S32()
 *
 * Description :
 *     Returns the result of a S32 data type multiplied with an S32 data type
 *     in a S32 container
 *
 * Parametrs:
 * [in] input1       Operand 1
 * [in] input2       Operand 2
 *
 * Return:
 * [out]             Result of operation
 *    
 **/

OMX_S32 armSatMulS32S32_S32(
                    OMX_S32 input1,
                    OMX_S32 input2);


/**
 * Function :armIntDivAwayFromZero()
 *
 * Description : Integer division with rounding to the nearest integer. 
 *               Half-integer values are rounded away from zero
 *               unless otherwise specified. For example 3//2 is rounded 
 *               to 2, and -3//2 is rounded to -2.
 *
 * Parametrs:
 * [in] Num        Operand 1
 * [in] Deno       Operand 2
 *
 * Return:
 * [out]             Result of operation input1//input2
 *    
 **/

OMX_S32 armIntDivAwayFromZero (OMX_S32 Num, OMX_S32 Deno);


/***********************************************************************/
/*
 * Debugging macros
 *
 */


/*
 * Definition of output stream - change to stderr if necessary
 */
#define DEBUG_STREAM stdout

/*
 * Debug printf macros, one for each argument count.
 * Add more if needed.
 */
#ifdef DEBUG_ON
#include <stdio.h>

#define DEBUG_PRINTF_0(a)                                               fprintf(DEBUG_STREAM, a)
#define DEBUG_PRINTF_1(a, b)                                            fprintf(DEBUG_STREAM, a, b)
#define DEBUG_PRINTF_2(a, b, c)                                         fprintf(DEBUG_STREAM, a, b, c)
#define DEBUG_PRINTF_3(a, b, c, d)                                      fprintf(DEBUG_STREAM, a, b, c, d)
#define DEBUG_PRINTF_4(a, b, c, d, e)                                   fprintf(DEBUG_STREAM, a, b, c, d, e)
#define DEBUG_PRINTF_5(a, b, c, d, e, f)                                fprintf(DEBUG_STREAM, a, b, c, d, e, f)
#define DEBUG_PRINTF_6(a, b, c, d, e, f, g)                             fprintf(DEBUG_STREAM, a, b, c, d, e, f, g)
#define DEBUG_PRINTF_7(a, b, c, d, e, f, g, h)                          fprintf(DEBUG_STREAM, a, b, c, d, e, f, g, h)
#define DEBUG_PRINTF_8(a, b, c, d, e, f, g, h, i)                       fprintf(DEBUG_STREAM, a, b, c, d, e, f, g, h, i)
#define DEBUG_PRINTF_9(a, b, c, d, e, f, g, h, i, j)                    fprintf(DEBUG_STREAM, a, b, c, d, e, f, g, h, i, j)
#define DEBUG_PRINTF_10(a, b, c, d, e, f, g, h, i, j, k)                fprintf(DEBUG_STREAM, a, b, c, d, e, f, g, h, i, j, k)
#define DEBUG_PRINTF_11(a, b, c, d, e, f, g, h, i, j, k, l)             fprintf(DEBUG_STREAM, a, b, c, d, e, f, g, h, i, j, k, l)
#define DEBUG_PRINTF_12(a, b, c, d, e, f, g, h, i, j, k, l, m)          fprintf(DEBUG_STREAM, a, b, c, d, e, f, g, h, i, j, k, l, m)
#define DEBUG_PRINTF_13(a, b, c, d, e, f, g, h, i, j, k, l, m, n)       fprintf(DEBUG_STREAM, a, b, c, d, e, f, g, h, i, j, k, l, m, n)
#define DEBUG_PRINTF_14(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)    fprintf(DEBUG_STREAM, a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)
#else /* DEBUG_ON */
#define DEBUG_PRINTF_0(a)                                  
#define DEBUG_PRINTF_1(a, b)                               
#define DEBUG_PRINTF_2(a, b, c)                            
#define DEBUG_PRINTF_3(a, b, c, d)                         
#define DEBUG_PRINTF_4(a, b, c, d, e)                      
#define DEBUG_PRINTF_5(a, b, c, d, e, f)                   
#define DEBUG_PRINTF_6(a, b, c, d, e, f, g)                
#define DEBUG_PRINTF_7(a, b, c, d, e, f, g, h)             
#define DEBUG_PRINTF_8(a, b, c, d, e, f, g, h, i)          
#define DEBUG_PRINTF_9(a, b, c, d, e, f, g, h, i, j)       
#define DEBUG_PRINTF_10(a, b, c, d, e, f, g, h, i, j, k)    
#define DEBUG_PRINTF_11(a, b, c, d, e, f, g, h, i, j, k, l)             
#define DEBUG_PRINTF_12(a, b, c, d, e, f, g, h, i, j, k, l, m)          
#define DEBUG_PRINTF_13(a, b, c, d, e, f, g, h, i, j, k, l, m, n)      
#define DEBUG_PRINTF_14(a, b, c, d, e, f, g, h, i, j, k, l, m, n, o)   
#endif /* DEBUG_ON */


/*
 * Domain and sub domain definitions
 *
 * In order to turn on debug for an entire domain or sub-domain
 * at compile time, one of the DEBUG_DOMAIN_* below may be defined,
 * which will activate debug in all of the defines it contains.
 */

#ifdef DEBUG_DOMAIN_AC
#define DEBUG_OMXACAAC_DECODECHANPAIRELT_MPEG4
#define DEBUG_OMXACAAC_DECODECHANPAIRELT
#define DEBUG_OMXACAAC_DECODEDATSTRELT
#define DEBUG_OMXACAAC_DECODEFILLELT
#define DEBUG_OMXACAAC_DECODEISSTEREO_S32
#define DEBUG_OMXACAAC_DECODEMSPNS_S32
#define DEBUG_OMXACAAC_DECODEMSSTEREO_S32_I
#define DEBUG_OMXACAAC_DECODEPRGCFGELT
#define DEBUG_OMXACAAC_DECODETNS_S32_I
#define DEBUG_OMXACAAC_DEINTERLEAVESPECTRUM_S32
#define DEBUG_OMXACAAC_ENCODETNS_S32_I
#define DEBUG_OMXACAAC_LONGTERMPREDICT_S32
#define DEBUG_OMXACAAC_LONGTERMRECONSTRUCT_S32
#define DEBUG_OMXACAAC_MDCTFWD_S32
#define DEBUG_OMXACAAC_MDCTINV_S32_S16
#define DEBUG_OMXACAAC_NOISELESSDECODE
#define DEBUG_OMXACAAC_QUANTINV_S32_I
#define DEBUG_OMXACAAC_UNPACKADIFHEADER
#define DEBUG_OMXACAAC_UNPACKADTSFRAMEHEADER
#define DEBUG_OMXACMP3_HUFFMANDECODESFBMBP_S32
#define DEBUG_OMXACMP3_HUFFMANDECODESFB_S32
#define DEBUG_OMXACMP3_HUFFMANDECODE_S32
#define DEBUG_OMXACMP3_MDCTINV_S32
#define DEBUG_OMXACMP3_REQUANTIZESFB_S32_I
#define DEBUG_OMXACMP3_REQUANTIZE_S32_I
#define DEBUG_OMXACMP3_SYNTHPQMF_S32_S16
#define DEBUG_OMXACMP3_UNPACKFRAMEHEADER
#define DEBUG_OMXACMP3_UNPACKSCALEFACTORS_S8
#define DEBUG_OMXACMP3_UNPACKSIDEINFO
#endif /* DEBUG_DOMAIN_AC */


#ifdef DEBUG_DOMAIN_VC
#define DEBUG_OMXVCM4P10_AVERAGE_16X
#define DEBUG_OMXVCM4P10_AVERAGE_4X
#define DEBUG_OMXVCM4P10_AVERAGE_8X
#define DEBUG_OMXVCM4P10_DEBLOCKCHROMA_U8_C1IR
#define DEBUG_OMXVCM4P10_DEBLOCKLUMA_U8_C1IR
#define DEBUG_OMXVCM4P10_DECODECHROMADCCOEFFSTOPAIRCAVLC_U8
#define DEBUG_OMXVCM4P10_DECODECOEFFSTOPAIRCAVLC_U8
#define DEBUG_OMXVCM4P10_DEQUANTTRANSFORMACFROMPAIR_U8_S16_C1_DLX
#define DEBUG_OMXVCM4P10_EXPANDFRAME
#define DEBUG_OMXVCM4P10_FILTERDEBLOCKINGCHROMA_HOREDGE_U8_C1IR
#define DEBUG_OMXVCM4P10_FILTERDEBLOCKINGCHROMA_VEREDGE_U8_C1IR
#define DEBUG_OMXVCM4P10_FILTERDEBLOCKINGLUMA_HOREDGE_U8_C1IR
#define DEBUG_OMXVCM4P10_FILTERDEBLOCKINGLUMA_VEREDGE_U8_C1IR
#define DEBUG_OMXVCM4P10_PREDICTINTRACHROMA8X8_U8_C1R
#define DEBUG_OMXVCM4P10_PREDICTINTRA_16X16_U8_C1R
#define DEBUG_OMXVCM4P10_PREDICTINTRA_4X4_U8_C1R
#define DEBUG_OMXVCM4P10_SADQUAR_16X
#define DEBUG_OMXVCM4P10_SADQUAR_4X
#define DEBUG_OMXVCM4P10_SADQUAR_8X
#define DEBUG_OMXVCM4P10_SAD_16X
#define DEBUG_OMXVCM4P10_SAD_4X
#define DEBUG_OMXVCM4P10_SAD_8X
#define DEBUG_OMXVCM4P10_SATD_4X4
#define DEBUG_OMXVCM4P10_TRANSFORMDEQUANTCHROMADCFROMPAIR_U8_S16_C1
#define DEBUG_OMXVCM4P10_TRANSFORMDEQUANTLUMADCFROMPAIR_U8_S16_C1
#define DEBUG_OMXVCM4P10_TRANSFORMQUANT_CHROMADC
#define DEBUG_OMXVCM4P10_TRANSFORMQUANT_LUMADC
#define DEBUG_OMXVCM4P2_BLOCKMATCH_HALF_16X16
#define DEBUG_OMXVCM4P2_BLOCKMATCH_HALF_8X8
#define DEBUG_OMXVCM4P2_BLOCKMATCH_INTEGER_16X16
#define DEBUG_OMXVCM4P2_BLOCKMATCH_INTEGER_8X8
#define DEBUG_OMXVCM4P2_COMPUTETEXTUREERRORBLOCK_SAD_U8_S16
#define DEBUG_OMXVCM4P2_COMPUTETEXTUREERRORBLOCK_U8_S16
#define DEBUG_OMXVCM4P2_DCT8X8BLKDLX
#define DEBUG_OMXVCM4P2_DECODEBLOCKCOEF_INTER_S16
#define DEBUG_OMXVCM4P2_DECODEPADMV_PVOP
#define DEBUG_OMXVCM4P2_DECODEVLCZIGZAG_INTER_S16
#define DEBUG_OMXVCM4P2_DECODEVLCZIGZAG_INTRAACVLC_S16
#define DEBUG_OMXVCM4P2_DECODEVLCZIGZAG_INTRADCVLC_S16
#define DEBUG_OMXVCM4P2_ENCODEMV_U8_S16
#define DEBUG_OMXVCM4P2_ENCODEVLCZIGZAG_INTER_S16
#define DEBUG_OMXVCM4P2_ENCODEVLCZIGZAG_INTRAACVLC_S16
#define DEBUG_OMXVCM4P2_ENCODEVLCZIGZAG_INTRADCVLC_S16
#define DEBUG_OMXVCM4P2_FINDMVPRED
#define DEBUG_OMXVCM4P2_IDCT8X8BLKDLX
#define DEBUG_OMXVCM4P2_LIMITMVTORECT
#define DEBUG_OMXVCM4P2_MOTIONESTIMATIONMB
#define DEBUG_OMXVCM4P2_PADMBGRAY_U8
#define DEBUG_OMXVCM4P2_PADMBHORIZONTAL_U8
#define DEBUG_OMXVCM4P2_PADMBVERTICAL_U8
#define DEBUG_OMXVCM4P2_PADMV
#define DEBUG_OMXVCM4P2_QUANTINTER_S16_I
#define DEBUG_OMXVCM4P2_QUANTINTRA_S16_I
#define DEBUG_OMXVCM4P2_QUANTINVINTER_S16_I
#define DEBUG_OMXVCM4P2_QUANTINVINTRA_S16_I
#define DEBUG_OMXVCM4P2_TRANSRECBLOCKCEOF_INTER
#define DEBUG_OMXVCM4P2_TRANSRECBLOCKCEOF_INTRA
#endif /* DEBUG_DOMAIN_VC */


#ifdef DEBUG_DOMAIN_IC
/* To be filled in */
#endif /* DEBUG_DOMAIN_IC */


#ifdef DEBUG_DOMAIN_SP
#define DEBUG_OMXACSP_DOTPROD_S16
#define DEBUG_OMXACSP_BLOCKEXP_S16
#define DEBUG_OMXACSP_BLOCKEXP_S32
#define DEBUG_OMXACSP_COPY_S16
#define DEBUG_OMXACSP_DOTPROD_S16
#define DEBUG_OMXACSP_DOTPROD_S16_SFS
#define DEBUG_OMXACSP_FFTFWD_CTOC_SC16_SFS
#define DEBUG_OMXACSP_FFTFWD_CTOC_SC32_SFS
#define DEBUG_OMXACSP_FFTFWD_RTOCCS_S16S32_SFS
#define DEBUG_OMXACSP_FFTFWD_RTOCCS_S32_SFS
#define DEBUG_OMXACSP_FFTGETBUFSIZE_C_SC16
#define DEBUG_OMXACSP_FFTGETBUFSIZE_C_SC32
#define DEBUG_OMXACSP_FFTGETBUFSIZE_R_S16_S32
#define DEBUG_OMXACSP_FFTGETBUFSIZE_R_S32
#define DEBUG_OMXACSP_FFTINIT_C_SC16
#define DEBUG_OMXACSP_FFTINIT_C_SC32
#define DEBUG_OMXACSP_FFTINIT_R_S16_S32
#define DEBUG_OMXACSP_FFTINIT_R_S32
#define DEBUG_OMXACSP_FFTINV_CCSTOR_S32S16_SFS
#define DEBUG_OMXACSP_FFTINV_CCSTOR_S32_SFS
#define DEBUG_OMXACSP_FFTINV_CTOC_SC16_SFS
#define DEBUG_OMXACSP_FFTINV_CTOC_SC32_SFS
#define DEBUG_OMXACSP_FILTERMEDIAN_S32_I
#define DEBUG_OMXACSP_FILTERMEDIAN_S32
#define DEBUG_OMXACSP_FIRONE_DIRECT_S16_ISFS
#define DEBUG_OMXACSP_FIRONE_DIRECT_S16_I
#define DEBUG_OMXACSP_FIRONE_DIRECT_S16
#define DEBUG_OMXACSP_FIRONE_DIRECT_S16_SFS
#define DEBUG_OMXACSP_FIR_DIRECT_S16_ISFS
#define DEBUG_OMXACSP_FIR_DIRECT_S16_I
#define DEBUG_OMXACSP_FIR_DIRECT_S16
#define DEBUG_OMXACSP_FIR_DIRECT_S16_SFS
#define DEBUG_OMXACSP_IIRONE_BIQUADDIRECT_S16_I
#define DEBUG_OMXACSP_IIRONE_BIQUADDIRECT_S16
#define DEBUG_OMXACSP_IIRONE_DIRECT_S16_I
#define DEBUG_OMXACSP_IIRONE_DIRECT_S16
#define DEBUG_OMXACSP_IIR_BIQUADDIRECT_S16_I
#define DEBUG_OMXACSP_IIR_BIQUADDIRECT_S16
#define DEBUG_OMXACSP_IIR_DIRECT_S16_I
#define DEBUG_OMXACSP_IIR_DIRECT_S16
#endif /* DEBUG_DOMAIN_SP */


#ifdef DEBUG_DOMAIN_IP
#define DEBUG_OMXIPBM_ADDC_U8_C1R_SFS
#define DEBUG_OMXIPBM_COPY_U8_C1R
#define DEBUG_OMXIPBM_COPY_U8_C3R
#define DEBUG_OMXIPBM_MIRROR_U8_C1R
#define DEBUG_OMXIPBM_MULC_U8_C1R_SFS
#define DEBUG_OMXIPCS_COLORTWISTQ14_U8_C3R
#define DEBUG_OMXIPCS_RGB565TOYCBCR420LS_MCU_U16_S16_C3P3R
#define DEBUG_OMXIPCS_RGB565TOYCBCR422LS_MCU_U16_S16_C3P3R
#define DEBUG_OMXIPCS_RGB565TOYCBCR444LS_MCU_U16_S16_C3P3R
#define DEBUG_OMXIPCS_RGBTOYCBCR420LS_MCU_U8_S16_C3P3R
#define DEBUG_OMXIPCS_RGBTOYCBCR422LS_MCU_U8_S16_C3P3R
#define DEBUG_OMXIPCS_RGBTOYCBCR444LS_MCU_U8_S16_C3P3R
#define DEBUG_OMXIPCS_YCBCR420RSZROT_U8_P3R
#define DEBUG_OMXIPCS_YCBCR420TORGB565LS_MCU_S16_U16_P3C3R
#define DEBUG_OMXIPCS_YCBCR420TORGB565_U8_U16_P3C3R
#define DEBUG_OMXIPCS_YCBCR420TORGBLS_MCU_S16_U8_P3C3R
#define DEBUG_OMXIPCS_YCBCR422RSZCSCROTRGB_U8_C2R
#define DEBUG_OMXIPCS_YCBCR422RSZROT_U8_P3R
#define DEBUG_OMXIPCS_YCBCR422TORGB565LS_MCU_S16_U16_P3C3R
#define DEBUG_OMXIPCS_YCBCR422TORGB565_U8_U16_C2C3R
#define DEBUG_OMXIPCS_YCBCR422TORGBLS_MCU_S16_U8_P3C3R
#define DEBUG_OMXIPCS_YCBCR422TORGB_U8_C2C3R
#define DEBUG_OMXIPCS_YCBCR422TOYCBCR420ROTATE_U8_C2P3R
#define DEBUG_OMXIPCS_YCBCR422TOYCBCR420ROTATE_U8_P3R
#define DEBUG_OMXIPCS_YCBCR444TORGB565LS_MCU_S16_U16_P3C3R
#define DEBUG_OMXIPCS_YCBCR444TORGBLS_MCU_S16_U8_P3C3R
#define DEBUG_OMXIPCS_YCBCRTORGB565_U8_U16_C3R
#define DEBUG_OMXIPCS_YCBCRTORGB565_U8_U16_P3C3R
#define DEBUG_OMXIPCS_YCBCRTORGB_U8_C3R
#define DEBUG_OMXIPPP_GETCENTRALMOMENT_S64
#define DEBUG_OMXIPPP_GETSPATIALMOMENT_S64
#define DEBUG_OMXIPPP_MOMENTGETSTATESIZE_S64
#define DEBUG_OMXIPPP_MOMENTINIT_S64
#define DEBUG_OMXIPPP_MOMENTS64S_U8_C1R
#define DEBUG_OMXIPPP_MOMENTS64S_U8_C3R
#endif /* DEBUG_DOMAIN_IP */


#endif /* _armCommon_H_ */

/*End of File*/




