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
/*******************************************************************************
	File:		transform.h

	Content:	MDCT Transform functions

*******************************************************************************/

#ifndef  __TRANSFORM_H__
#define __TRANSFORM_H__

#include "typedef.h"

void Transform_Real(Word16 *mdctDelayBuffer,
                    Word16 *timeSignal,
                    Word16 chIncrement,     /*! channel increment */
                    Word32 *realOut,
                    Word16 *mdctScale,
                    Word16 windowSequence
                    );

#endif