/**
 * 
 * File Name:  armVCM4P2_CheckVLCEscapeMode.c
 * OpenMAX DL: v1.0.2
 * Revision:   9641
 * Date:       Thursday, February 7, 2008
 * 
 * (c) Copyright 2007-2008 ARM Limited. All Rights Reserved.
 * 
 * 
 * 
 * Description:
 * Contains module for VLC escape mode check 
 *
 */ 
 
#include "omxtypes.h"
#include "armOMX.h"

#include "armVC.h"
#include "armCOMM.h"

/**
 * Function: armVCM4P2_CheckVLCEscapeMode
 *
 * Description:
 * Performs escape mode decision based on the run, run+, level, level+ and 
 * last combinations.
 *
 * Remarks:
 *
 * Parameters:
 * [in] run             Run value (count of zeros) to be encoded  
 * [in] level           Level value (non-zero value) to be encoded
 * [in] runPlus         Calculated as runPlus = run - (RMAX + 1)  
 * [in] levelPlus       Calculated as 
 *                      levelPlus = sign(level)*[abs(level) - LMAX]
 * [in] maxStoreRun     Max store possible (considering last and inter/intra)
 * [in] maxRunForMultipleEntries 
 *                      The run value after which level 
 *                      will be equal to 1: 
 *                      (considering last and inter/intra status)
 * [in] pRunIndexTable  Run Index table defined in 
 *                      armVCM4P2_Huff_Tables_VLC.c
 *                      (considering last and inter/intra status)
 *
 *                      
 * Return Value:
 * Returns an Escape mode which can take values from 0 to 3
 * 0 --> no escape mode, 1 --> escape type 1,
 * 1 --> escape type 2, 3 --> escape type 3, check section 7.4.1.3
 * in the MPEG ISO standard.
 *
 */

OMX_U8 armVCM4P2_CheckVLCEscapeMode(
     OMX_U32 run,
     OMX_U32 runPlus,
     OMX_S16 level,
     OMX_S16 levelPlus,
     OMX_U8  maxStoreRun,
     OMX_U8  maxRunForMultipleEntries,
     OMX_INT shortVideoHeader,
     const OMX_U8  *pRunIndexTable
)
{
    OMX_U8 escape = 0, fMode = 0, entries;
    
    level = armAbs (level);
    levelPlus = armAbs (levelPlus);
    
    /* Check for a valid entry with run, level and Last combination 
       Mode 0 check */
    if (run <= maxStoreRun)
    {
        entries = pRunIndexTable[run + 1]
                  - pRunIndexTable[run];
        if (run > maxRunForMultipleEntries)
        {
            entries = 1;
        }
        if (level > entries)
        {
            escape = 1;
        }
    }
    else
    {
        escape = 1;
    }
    if(escape && shortVideoHeader)
    {
        escape = 0;
        fMode = 4;
    }
    /* Check for a valid entry with run, levelPlus and Last combination 
       Mode 1 check */    
    if (escape)
    {
        escape = 0;
        fMode = 1;
        if (run <= maxStoreRun)
        {
            entries = pRunIndexTable[run + 1]
                      - pRunIndexTable[run];
            if (run > maxRunForMultipleEntries)
            {
                entries = 1;
            }
            if (levelPlus > entries)
            {
                escape = 1;
            }
        }
        else
        {
            escape = 1;
        }
    }
    
    /* Check for a valid entry with runPlus, level and Last combination 
       Mode 2 check */    
    if (escape)
    {
        escape = 0;
        fMode = 2;
        if (runPlus <= maxStoreRun)
        {
            entries = pRunIndexTable[runPlus + 1]
                      - pRunIndexTable[runPlus];
            if (runPlus > maxRunForMultipleEntries)
            {
                entries = 1;
            }
            if (level > entries)
            {
                escape = 1;
            }
        }
        else
        {
            escape = 1;
        }
    }
    
    /* select mode 3 --> FLC */
    if (escape)
    {
        fMode = 3;
    }
    
    return fMode;
}

/*End of File*/

