/*
 ** Copyright 2003-2010, VisualOn, Inc.
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

/***********************************************************************
*       File: random.c                                                 *
*                                                                      *
*       Description: Signed 16 bits random generator                   *
*                                                                      *
************************************************************************/

#include "typedef.h"
#include "basic_op.h"

Word16 Random(Word16 * seed)
{
	/* static Word16 seed = 21845; */
	*seed = (Word16)(L_add((L_mult(*seed, 31821) >> 1), 13849L));
	return (*seed);
}

