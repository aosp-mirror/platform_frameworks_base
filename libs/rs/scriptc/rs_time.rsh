#ifndef __RS_TIME_RSH__
#define __RS_TIME_RSH__

typedef int rs_time_t;

typedef struct {
    int tm_sec;
    int tm_min;
    int tm_hour;
    int tm_mday;
    int tm_mon;
    int tm_year;
    int tm_wday;
    int tm_yday;
    int tm_isdst;
} rs_tm;

extern rs_time_t __attribute__((overloadable))
    rsTime(rs_time_t *timer);

extern rs_tm * __attribute__((overloadable))
    rsLocaltime(rs_tm *local, const rs_time_t *timer);

// Return the current system clock in milliseconds
extern int64_t __attribute__((overloadable))
    rsUptimeMillis(void);

// Return the current system clock in nanoseconds
extern int64_t __attribute__((overloadable))
    rsUptimeNanos(void);

// Return the time in seconds since function was last called in this script.
extern float __attribute__((overloadable))
    rsGetDt(void);

#endif
