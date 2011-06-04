/**
 * 
 * File Name:  armCOMM.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 *
 * Defines Common APIs used across OpenMAX API's
 */

#include "omxtypes.h"
#include "armCOMM.h"

/***********************************************************************/
                /* Miscellaneous Arithmetic operations */

/**
 * Function: armRoundFloatToS16
 *
 * Description:
 * Converts a double precision value into a short int after rounding
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_S16 format
 *
 */

OMX_S16 armRoundFloatToS16 (OMX_F64 Value)
{
    if (Value > 0)
    {
        return (OMX_S16)(Value + .5);
    }
    else
    {
        return (OMX_S16)(Value - .5);
    }
}

/**
 * Function: armRoundFloatToS32
 *
 * Description:
 * Converts a double precision value into a int after rounding
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_S32 format
 *
 */

OMX_S32 armRoundFloatToS32 (OMX_F64 Value)
{
    if (Value > 0)
    {
        return (OMX_S32)(Value + .5);
    }
    else
    {
        return (OMX_S32)(Value - .5);
    }
}
/**
 * Function: armSatRoundFloatToS16
 *
 * Description:
 * Converts a double precision value into a short int after rounding and saturation
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_S16 format
 *
 */

OMX_S16 armSatRoundFloatToS16 (OMX_F64 Value)
{
    if (Value > 0)
    {
        Value += 0.5;
        
        if(Value > (OMX_S16)OMX_MAX_S16 )
        {
            return (OMX_S16)OMX_MAX_S16;
        }
        else
        {
            return (OMX_S16)Value;
        }
    }
    else
    {
        Value -= 0.5;

        if(Value < (OMX_S16)OMX_MIN_S16 )
        {
            return (OMX_S16)OMX_MIN_S16;
        }
        else
        {
            return (OMX_S16)Value;
        }
    }
}

/**
 * Function: armSatRoundFloatToS32
 *
 * Description:
 * Converts a double precision value into a int after rounding and saturation
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_S32 format
 *
 */

OMX_S32 armSatRoundFloatToS32 (OMX_F64 Value)
{
    if (Value > 0)
    {
        Value += 0.5;
        
        if(Value > (OMX_S32)OMX_MAX_S32 )
        {
            return (OMX_S32)OMX_MAX_S32;
        }
        else
        {
            return (OMX_S32)Value;
        }
    }
    else
    {
        Value -= 0.5;

        if(Value < (OMX_S32)OMX_MIN_S32 )
        {
            return (OMX_S32)OMX_MIN_S32;
        }
        else
        {
            return (OMX_S32)Value;
        }
    }
}

/**
 * Function: armSatRoundFloatToU16
 *
 * Description:
 * Converts a double precision value into a unsigned short int after rounding and saturation
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_U16 format
 *
 */

OMX_U16 armSatRoundFloatToU16 (OMX_F64 Value)
{
    Value += 0.5;
    
    if(Value > (OMX_U16)OMX_MAX_U16 )
    {
        return (OMX_U16)OMX_MAX_U16;
    }
    else
    {
        return (OMX_U16)Value;
    }
}

/**
 * Function: armSatRoundFloatToU32
 *
 * Description:
 * Converts a double precision value into a unsigned int after rounding and saturation
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_U32 format
 *
 */

OMX_U32 armSatRoundFloatToU32 (OMX_F64 Value)
{
    Value += 0.5;
    
    if(Value > (OMX_U32)OMX_MAX_U32 )
    {
        return (OMX_U32)OMX_MAX_U32;
    }
    else
    {
        return (OMX_U32)Value;
    }
}

/**
 * Function: armRoundFloatToS64
 *
 * Description:
 * Converts a double precision value into a 64 bit int after rounding
 *
 * Parameters:
 * [in]  Value                 Float value to be converted
 *
 * Return Value:
 * [out] converted value in OMX_S64 format
 *
 */

OMX_S64 armRoundFloatToS64 (OMX_F64 Value)
{
    if (Value > 0)
    {
        return (OMX_S64)(Value + .5);
    }
    else
    {
        return (OMX_S64)(Value - .5);
    }
}

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

OMX_INT armSignCheck (
    OMX_S16 var
)

{
    OMX_INT Sign;
    
    if (var < 0)
    {
        Sign = -1;
    }
    else if ( var > 0)
    {
        Sign = 1;
    }
    else
    {
        Sign = 0;
    }
    
    return Sign;
}

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
)
 
{
    if (src > max)
    {
        src = max;
    }
    else if (src < min)
    {
        src = min;
    }
    
    return src;
}

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
)
 
{
    if (src > max)
    {
        src = max;
    }
    else if (src < min)
    {
        src = min;
    }
    
    return src;
}

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
 
OMX_U32 armShiftSat_F32(OMX_F32 v, OMX_INT shift, OMX_INT satBits) 
{
    OMX_U32 allOnes = (OMX_U32)(-1);
    OMX_U32 maxV = allOnes >> (32-satBits);
    OMX_F32 vShifted, vRounded, shiftDiv = (OMX_F32)(1 << shift);
    OMX_U32 vInt;
    OMX_U32 vIntSat;
    
    if(v <= 0)
        return 0;
    
    vShifted = v / shiftDiv;
    vRounded = (OMX_F32)(vShifted + 0.5);
    vInt = (OMX_U32)vRounded;
    vIntSat = vInt;
    if(vIntSat > maxV) 
        vIntSat = maxV;
    return vIntSat;
}

/**
 * Functions: armSwapElem
 *
 * Description:
 * These function swaps two elements at the specified pointer locations.
 * The size of each element could be anything as specified by <elemSize>
 *
 * Return Value:
 * OMXResult -- Error status from the function
 */
OMXResult armSwapElem(
        OMX_U8 *pBuf1,
        OMX_U8 *pBuf2,
        OMX_INT elemSize
       )
{
    OMX_INT i;
    OMX_U8 temp;
    armRetArgErrIf(!pBuf1 || !pBuf2, OMX_Sts_BadArgErr);
    
    for(i = 0; i < elemSize; i++)
    {
        temp = *(pBuf1 + i);
        *(pBuf1 + i) = *(pBuf2 + i);
        *(pBuf2 + i) = temp;
    }
    return OMX_Sts_NoErr;
}

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
)
{
    OMX_S32 a, b, c;
    
    a = armMin (fEntry, sEntry);
    b = armMax (fEntry, sEntry);
    c = armMin (b, tEntry);
    return (armMax (a, c));
}

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
 * OMX_U8 --     Returns the minimum number of bits required to represent the positive value. 
                 This is the smallest k>=0 such that that value is less than (1<<k).
 */ 
 
OMX_U8 armLogSize (
    OMX_U16 value 
)
{
    OMX_U8 i;    
    for ( i = 0; value > 0; value = value >> 1) 
    {
        i++;
    }
    return i;
}

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
 
OMX_S32 armSatAdd_S32(OMX_S32 Value1,OMX_S32 Value2)
{
    OMX_S32 Result;
    
    Result = Value1 + Value2;

    if( (Value1^Value2) >= 0)
    {
        /*Same sign*/
        if( (Result^Value1) >= 0)
        {
            /*Result has not saturated*/
            return Result;
        }
        else
        {
            if(Value1 >= 0)
            {
                /*Result has saturated in positive side*/
                return OMX_MAX_S32;
            }
            else
            {
                /*Result has saturated in negative side*/
                return OMX_MIN_S32;
            }
        
        }
   
    }
    else
    {
        return Result;
    }
    
}

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
 
OMX_S64 armSatAdd_S64(OMX_S64 Value1,OMX_S64 Value2)
{
    OMX_S64 Result;
    
    Result = Value1 + Value2;

    if( (Value1^Value2) >= 0)
    {
        /*Same sign*/
        if( (Result^Value1) >= 0)
        {
            /*Result has not saturated*/
            return Result;
        }
        else
        {
            if(Value1 >= 0)
            {
                /*Result has saturated in positive side*/
                Result = OMX_MAX_S64;
                return Result;
            }
            else
            {
                /*Result has saturated in negative side*/
                return OMX_MIN_S64;
            }
        
        }
   
    }
    else
    {
        return Result;
    }
    
}

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

OMX_S32 armSatSub_S32(OMX_S32 Value1,OMX_S32 Value2)
{
    OMX_S32 Result;
    
    Result = Value1 - Value2;

    if( (Value1^Value2) < 0)
    {
        /*Opposite sign*/
        if( (Result^Value1) >= 0)
        {
            /*Result has not saturated*/
            return Result;
        }
        else
        {
            if(Value1 >= 0)
            {
                /*Result has saturated in positive side*/
                return OMX_MAX_S32;
            }
            else
            {
                /*Result has saturated in negative side*/
                return OMX_MIN_S32;
            }
        
        }
   
    }
    else
    {
        return Result;
    }
    
}

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

OMX_S32 armSatMac_S32(OMX_S32 Mac,OMX_S16 Value1,OMX_S16 Value2)
{
    OMX_S32 Result;
    
    Result = (OMX_S32)(Value1*Value2);
    Result = armSatAdd_S32( Mac , Result );

    return Result;    
}

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
 
OMX_S32 armSatMac_S16S32_S32(OMX_S32 mac, OMX_S32 delayElem, OMX_S16 filTap )
{
    
    OMX_S32 result;

    result = armSatMulS16S32_S32(filTap,delayElem); 

    if ( result > OMX_MAX_S16 )
    {
        result = OMX_MAX_S32;
    }
    else if( result < OMX_MIN_S16 )
    {
        result = OMX_MIN_S32;
    }
    else
    {
        result = delayElem * filTap;
    }

    mac = armSatAdd_S32(mac,result);
    
    return mac;
}


/**
 * Function :armSatRoundRightShift_S32_S16
 *
 * Description :
 *   Returns the result of rounded right shift operation of input by the scalefactor
 *
 *   output = Saturate_in_16Bits( ( Right/LeftShift( (Round(input) , shift ) )
 *
 * Parametrs:
 * [in] input       The input to be operated on
 * [in] shift The shift number
 *
 * Return:
 * [out]            Result of operation
 *    
 **/


OMX_S16 armSatRoundRightShift_S32_S16(OMX_S32 input, OMX_INT shift)
{
    input = armSatRoundLeftShift_S32(input,-shift);

    if ( input > OMX_MAX_S16 )
    {
        return (OMX_S16)OMX_MAX_S16;
    }
    else if (input < OMX_MIN_S16)
    {
        return (OMX_S16)OMX_MIN_S16;
    }
    else
    {
       return (OMX_S16)input;
    }

}

/**
 * Function :armSatRoundLeftShift_S32()
 *
 * Description :
 *     Returns the result of saturating left-shift operation on input
 *     Or rounded Right shift if the input Shift is negative.
 *     
 * Parametrs:
 * [in] Value        Operand
 * [in] Shift        Operand for shift operation
 *
 * Return:
 * [out]             Result of operation
 *    
 **/

OMX_S32 armSatRoundLeftShift_S32(OMX_S32 Value, OMX_INT Shift)
{
    OMX_INT i;
    
    if (Shift < 0)
    {
        Shift = -Shift;
        Value = armSatAdd_S32(Value, (1 << (Shift - 1)));
        Value = Value >> Shift;
    }
    else
    {
        for (i = 0; i < Shift; i++)
        {
            Value = armSatAdd_S32(Value, Value);
        }
    }
    return Value;
}

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
 
OMX_S64 armSatRoundLeftShift_S64(OMX_S64 Value, OMX_INT Shift)
{
    OMX_INT i;
    
    if (Shift < 0)
    {
        Shift = -Shift;
        Value = armSatAdd_S64(Value, ((OMX_S64)1 << (Shift - 1)));
        Value = Value >> Shift;
    }
    else
    {
        for (i = 0; i < Shift; i++)
        {
            Value = armSatAdd_S64(Value, Value);
        }
    }
    return Value;
}

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


OMX_S32 armSatMulS16S32_S32(OMX_S16 input1,OMX_S32 input2)
{
    OMX_S16 hi2,lo1;
    OMX_U16 lo2;
    
    OMX_S32 temp1,temp2;
    OMX_S32 result;
    
    lo1  = input1;

    hi2  = ( input2 >>  16 );
    lo2  = ( (OMX_U32)( input2 << 16 ) >> 16 );
    
    temp1 = hi2 * lo1;
    temp2 = ( lo2* lo1 ) >> 16;

    result =  armSatAdd_S32(temp1,temp2);

    return result;
}

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

OMX_S32 armSatMulS32S32_S32(OMX_S32 input1,OMX_S32 input2)
{
    OMX_S16 hi1,hi2;
    OMX_U16 lo1,lo2;
    
    OMX_S32 temp1,temp2,temp3;
    OMX_S32 result;

    hi1  = ( input1 >>  16 );
    lo1  = ( (OMX_U32)( input1 << 16 ) >> 16 );

    hi2  = ( input2 >>  16 );
    lo2  = ( (OMX_U32)( input2 << 16 ) >> 16 );
    
    temp1 =   hi1 * hi2;
    temp2 = ( hi1* lo2 ) >> 16;
    temp3 = ( hi2* lo1 ) >> 16;

    result = armSatAdd_S32(temp1,temp2);
    result = armSatAdd_S32(result,temp3);

    return result;
}

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

OMX_S32 armIntDivAwayFromZero (OMX_S32 Num, OMX_S32 Deno)
{
    OMX_F64 result;
    
    result = ((OMX_F64)Num)/((OMX_F64)Deno);
    
    if (result >= 0)
    {
        result += 0.5;
    }
    else
    {
        result -= 0.5;
    }

    return (OMX_S32)(result);
}


/*End of File*/

