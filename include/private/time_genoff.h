/*
 * Copyright (c) 2010-2013, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 *  * Neither the name of The Linux Foundation nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

#ifndef __TIME_GENOFF_H__
#define __TIME_GENOFF_H__

/*
 * Time genoff base -- To be used by the time setter
 * Reserved bases to be supported later.
 */
typedef enum time_bases {
	ATS_RTC = 0,
	ATS_TOD,
	ATS_USER,
	ATS_SECURE,
	ATS_RESERVED_1,
	ATS_RESERVED_2,
	ATS_RESERVED_3,
	ATS_GPS,
	ATS_1X,
	ATS_RESERVED_4,
	ATS_WCDMA,
	ATS_SNTP,
	ATS_UTC,
	ATS_MFLO,
	ATS_INVALID
} time_bases_type;

/* Time unit -- Unit in which time is set/get */
typedef enum time_unit {
	TIME_STAMP,		/* Not supported */
	TIME_MSEC,
	TIME_SECS,
	TIME_JULIAN,
	TIME_20MS_FRAME,	/* Not supported */
	TIME_INVALID
} time_unit_type;

/* Operation to be done */
typedef enum time_genoff_opr {
	T_SET,
	T_GET,
	T_MAX
} time_genoff_opr_type;

/* Structure to be passed as argument to time_genoff_operation() */
/*
 * In set/get: ts_val should be assigned memory and then passed.
 * if time_unit = TIME_MSEC, TIME_SECS then ts_val = (uint64_t *)
 * if time_unit = TIME_JULIAN then ts_val = (struct tm *)
 */
typedef struct time_genoff_info {
	time_bases_type base;		/* Genoff in consideration */
	void *ts_val;			/* Time to be set/get */
	time_unit_type unit;		/* Time unit */
	time_genoff_opr_type operation; /* Time operation to be done */
}time_genoff_info_type;

/* API to be called for time get/set operation */
int time_genoff_operation(time_genoff_info_type *pargs);

#endif /* __TIME_GENOFF_H__ */
