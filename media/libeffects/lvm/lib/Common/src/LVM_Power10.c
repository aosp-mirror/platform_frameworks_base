/*
 * Copyright (C) 2004-2010 NXP Software
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "LVM_Types.h"
#include "LVM_Macros.h"
#include "ScalarArithmetic.h"
#include "Filter.h"


/*-------------------------------------------------------------------------*/
/* FUNCTION:                                                               */
/*   LVM_Power10                                                           */
/*                                                                         */
/* DESCRIPTION:                                                            */
/*   This function calculates 10X using an 11th order polynomial. It uses  */
/*   the following table of 32-bit integer polynomial coefficients:        */
/*                                                                         */
/*   Coefficient    Value                                                  */
/*   A0             67102543                                               */
/*   A1             309032995                                              */
/*   A2             712096127                                              */
/*   A3             1092797331                                             */
/*   A4             1251625137                                             */
/*   A5             1154649460                                             */
/*   A6             915654800                                              */
/*   A7             597883683                                              */
/*   A8             284378230                                              */
/*   A9             150262097                                              */
/*   A10            124894471                                              */
/*   A11            50477244                                               */
/*   A12            -2                                                     */
/*                                                                         */
/*  Y = (A0 + A1*X + A2*X2 + A3*X3 + ….. + AN*xN) << AN+1                  */
/*                                                                         */
/*                                                                         */
/* PARAMETERS:                                                             */
/*                                                                         */
/*  X               is the input variable in Q2.30 format                  */
/*                                                                         */
/* RETURNS:                                                                */
/*   The result of the 10x expansion in Q8.24 format                       */
/*-------------------------------------------------------------------------*/

LVM_INT32 LVM_Power10(LVM_INT32     X)
{
    LVM_INT32 Y,Coefficients[13]={  16775636,
                                        77258249,
                                       178024032,
                                       273199333,
                                       312906284,
                                       288662365,
                                       228913700,
                                       149470921,
                                        71094558,
                                        37565524,
                                        31223618,
                                        12619311,
                                     0};
    Y=LVM_Polynomial((LVM_UINT16)11,
                        Coefficients,
                        X);
    return Y;
}

