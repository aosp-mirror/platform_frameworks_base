/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */

/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#include    "mp4dec_api.h"
#include    "mp4def.h"
#include    "scaling.h"
/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/

/* this scaling can be used for dividing values up to 3292             07/10/01 */
const int32 scale[63] = {0, 262145, 131073, 87382, 65537, 52430, 43692, 37450, 32769, 29128,
                         26215, 23832, 21846, 20166, 18726, 17477, 16385, 15421, 14565, 13798,
                         13108, 12484, 11917, 11399, 10924, 10487, 10083, 9710, 9363, 9040,
                         8739, 8457, 8193, 7945, 7711, 7491, 7283, 7086, 6900, 6723, 6555, 6395,
                         6243, 6097, 5959, 5826, 5700, 5579, 5462, 5351, 5244, 5141, 5042, 4947, 4856,
                         4767, 4682, 4600, 4521, 4444, 4370, 4298, 4229
                        };
/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; Define all local variables
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; Function body here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; Return nothing or data or data pointer
----------------------------------------------------------------------------*/


