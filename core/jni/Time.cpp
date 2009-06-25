#include "TimeUtils.h"
#include <stdio.h>
#include <cutils/tztime.h>

namespace android {

static void
dump(const Time& t)
{
    #ifdef HAVE_TM_GMTOFF
        long tm_gmtoff = t.t.tm_gmtoff;
    #else
        long tm_gmtoff = 0;
    #endif
    printf("%04d-%02d-%02d %02d:%02d:%02d (%d,%ld,%d,%d)\n",
            t.t.tm_year+1900, t.t.tm_mon+1, t.t.tm_mday,
            t.t.tm_hour, t.t.tm_min, t.t.tm_sec,
            t.t.tm_isdst, tm_gmtoff, t.t.tm_wday, t.t.tm_yday);
}

Time::Time()
{
    t.tm_sec = 0;
    t.tm_min = 0;
    t.tm_hour = 0;
    t.tm_mday = 0;
    t.tm_mon = 0;
    t.tm_year = 0;
    t.tm_wday = 0;
    t.tm_yday = 0;
    t.tm_isdst = -1; // we don't know, so let the C library determine
    #ifdef HAVE_TM_GMTOFF
        t.tm_gmtoff = 0;
    #endif
}


#define COMPARE_FIELD(field) do { \
        int diff = a.t.field - b.t.field; \
        if (diff != 0) return diff; \
    } while(0)

int
Time::compare(Time& a, Time& b)
{
    if (0 == strcmp(a.timezone, b.timezone)) {
        // if the timezones are the same, we can easily compare the two
        // times.  Otherwise, convert to milliseconds and compare that.
        // This requires that object be normalized.
        COMPARE_FIELD(tm_year);
        COMPARE_FIELD(tm_mon);
        COMPARE_FIELD(tm_mday);
        COMPARE_FIELD(tm_hour);
        COMPARE_FIELD(tm_min);
        COMPARE_FIELD(tm_sec);
        return 0;
    } else {
        int64_t am = a.toMillis(false /* use isDst */);
        int64_t bm = b.toMillis(false /* use isDst */);
        int64_t diff = am-bm;
        return (diff < 0) ? -1 : ((diff > 0) ? 1 : 0);
    }
}

static const int DAYS_PER_MONTH[] = {
                        31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31
                    };

static inline int days_this_month(int year, int month)
{
    int n = DAYS_PER_MONTH[month];
    if (n != 28) {
        return n;
    } else {
        int y = year;
        return ((y%4)==0&&((y%100)!=0||(y%400)==0)) ? 29 : 28;
    }
}

void 
Time::switchTimezone(const char* timezone)
{
    time_t seconds = mktime_tz(&(this->t), this->timezone);
    localtime_tz(&seconds, &(this->t), timezone);
}

String8 
Time::format(const char *format, const struct strftime_locale *locale) const
{
    char buf[257];
    int n = strftime_tz(buf, 257, format, &(this->t), locale);
    if (n > 0) {
        return String8(buf);
    } else {
        return String8();
    }
}

static inline short
tochar(int n)
{
    return (n >= 0 && n <= 9) ? ('0'+n) : ' ';
}

static inline short
next_char(int *m, int k)
{
    int n = *m / k;
    *m = *m % k;
    return tochar(n);
}

void
Time::format2445(short* buf, bool hasTime) const
{
    int n;

    n = t.tm_year+1900;
    buf[0] = next_char(&n, 1000);
    buf[1] = next_char(&n, 100);
    buf[2] = next_char(&n, 10);
    buf[3] = tochar(n);

    n = t.tm_mon+1;
    buf[4] = next_char(&n, 10);
    buf[5] = tochar(n);

    n = t.tm_mday;
    buf[6] = next_char(&n, 10);
    buf[7] = tochar(n);

    if (hasTime) {
      buf[8] = 'T';

      n = t.tm_hour;
      buf[9] = next_char(&n, 10);
      buf[10] = tochar(n);
      
      n = t.tm_min;
      buf[11] = next_char(&n, 10);
      buf[12] = tochar(n);
      
      n = t.tm_sec;
      buf[13] = next_char(&n, 10);
      buf[14] = tochar(n);
      bool inUtc = strcmp("UTC", timezone) == 0;
      if (inUtc) {
          buf[15] = 'Z';
      }
    }
}

String8 
Time::toString() const
{
    String8 str;
    char* s = str.lockBuffer(150);
    #ifdef HAVE_TM_GMTOFF
        long tm_gmtoff = t.tm_gmtoff;
    #else
        long tm_gmtoff = 0;
    #endif
    sprintf(s, "%04d%02d%02dT%02d%02d%02d%s(%d,%d,%ld,%d,%d)", 
            t.tm_year+1900, t.tm_mon+1, t.tm_mday, t.tm_hour, t.tm_min,
            t.tm_sec, timezone, t.tm_wday, t.tm_yday, tm_gmtoff, t.tm_isdst,
            (int)(((Time*)this)->toMillis(false /* use isDst */)/1000));
    str.unlockBuffer();
    return str;
}

void 
Time::setToNow()
{
    time_t seconds;
    time(&seconds);
    localtime_tz(&seconds, &(this->t), this->timezone);
}

int64_t 
Time::toMillis(bool ignoreDst)
{
    if (ignoreDst) {
        this->t.tm_isdst = -1;
    }
    int64_t r = mktime_tz(&(this->t), this->timezone);
    if (r == -1)
        return -1;
    return r * 1000;
}

void 
Time::set(int64_t millis)
{
    time_t seconds = millis / 1000;
    localtime_tz(&seconds, &(this->t), this->timezone);
}

}; // namespace android

