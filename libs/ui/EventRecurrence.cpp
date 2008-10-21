/*
 *  Copyright 2006 The Android Open Source Project
 */

#include <pim/EventRecurrence.h>
#include <utils/String8.h>
#include <stdio.h>
#include <limits.h>

namespace android {

#define FAIL_HERE() do { \
            printf("Parsing failed at line %d\n", __LINE__); \
            return UNKNOWN_ERROR; \
        } while(0)

EventRecurrence::EventRecurrence()
    :freq((freq_t)0),
     until(),
     count(0),
     interval(0),
     bysecond(0),
     bysecondCount(0),
     byminute(0),
     byminuteCount(0),
     byhour(0),
     byhourCount(0),
     byday(0),
     bydayNum(0),
     bydayCount(0),
     bymonthday(0),
     bymonthdayCount(0),
     byyearday(0),
     byyeardayCount(0),
     byweekno(0),
     byweeknoCount(0),
     bymonth(0),
     bymonthCount(0),
     bysetpos(0),
     bysetposCount(0),
     wkst(0)
{
}

EventRecurrence::~EventRecurrence()
{
    delete[] bysecond;
    delete[] byminute;
    delete[] byhour;
    delete[] byday;
    delete[] bydayNum;
    delete[] byyearday;
    delete[] bymonthday;
    delete[] byweekno;
    delete[] bymonth;
    delete[] bysetpos;
}

enum LHS {
    NONE_LHS = 0,
    FREQ,
    UNTIL,
    COUNT,
    INTERVAL,
    BYSECOND,
    BYMINUTE,
    BYHOUR,
    BYDAY,
    BYMONTHDAY,
    BYYEARDAY,
    BYWEEKNO,
    BYMONTH,
    BYSETPOS,
    WKST
};

struct LHSProc
{
    const char16_t* text;
    size_t textSize;
    uint32_t value;
};

const char16_t FREQ_text[] = { 'F', 'R', 'E', 'Q' };
const char16_t UNTIL_text[] = { 'U', 'N', 'T', 'I', 'L' };
const char16_t COUNT_text[] = { 'C', 'O', 'U', 'N', 'T' };
const char16_t INTERVAL_text[] = { 'I', 'N', 'T', 'E', 'R', 'V', 'A', 'L'};
const char16_t BYSECOND_text[] = { 'B', 'Y', 'S', 'E', 'C', 'O', 'N', 'D' };
const char16_t BYMINUTE_text[] = { 'B', 'Y', 'M', 'I', 'N', 'U', 'T', 'E' };
const char16_t BYHOUR_text[] = { 'B', 'Y', 'H', 'O', 'U', 'R' };
const char16_t BYDAY_text[] = { 'B', 'Y', 'D', 'A', 'Y' };
const char16_t BYMONTHDAY_text[] = { 'B','Y','M','O','N','T','H','D','A','Y' };
const char16_t BYYEARDAY_text[] = { 'B','Y','Y','E','A','R','D','A','Y' };
const char16_t BYWEEKNO_text[] = { 'B', 'Y', 'W', 'E', 'E', 'K', 'N', 'O' };
const char16_t BYMONTH_text[] = { 'B', 'Y', 'M', 'O', 'N', 'T', 'H' };
const char16_t BYSETPOS_text[] = { 'B', 'Y', 'S', 'E', 'T', 'P', 'O', 'S' };
const char16_t WKST_text[] = { 'W', 'K', 'S', 'T' };

#define SIZ(x) (sizeof(x)/sizeof(x[0]))

const LHSProc LHSPROC[] = {
    { FREQ_text, SIZ(FREQ_text), FREQ },
    { UNTIL_text, SIZ(UNTIL_text), UNTIL },
    { COUNT_text, SIZ(COUNT_text), COUNT },
    { INTERVAL_text, SIZ(INTERVAL_text), INTERVAL },
    { BYSECOND_text, SIZ(BYSECOND_text), BYSECOND },
    { BYMINUTE_text, SIZ(BYMINUTE_text), BYMINUTE },
    { BYHOUR_text, SIZ(BYHOUR_text), BYHOUR },
    { BYDAY_text, SIZ(BYDAY_text), BYDAY },
    { BYMONTHDAY_text, SIZ(BYMONTHDAY_text), BYMONTHDAY },
    { BYYEARDAY_text, SIZ(BYYEARDAY_text), BYYEARDAY },
    { BYWEEKNO_text, SIZ(BYWEEKNO_text), BYWEEKNO },
    { BYMONTH_text, SIZ(BYMONTH_text), BYMONTH },
    { BYSETPOS_text, SIZ(BYSETPOS_text), BYSETPOS },
    { WKST_text, SIZ(WKST_text), WKST },
    { NULL, 0, NONE_LHS },
};

const char16_t SECONDLY_text[] = { 'S','E','C','O','N','D','L','Y' };
const char16_t MINUTELY_text[] = { 'M','I','N','U','T','E','L','Y' };
const char16_t HOURLY_text[] = { 'H','O','U','R','L','Y' };
const char16_t DAILY_text[] = { 'D','A','I','L','Y' };
const char16_t WEEKLY_text[] = { 'W','E','E','K','L','Y' };
const char16_t MONTHLY_text[] = { 'M','O','N','T','H','L','Y' };
const char16_t YEARLY_text[] = { 'Y','E','A','R','L','Y' };

typedef LHSProc FreqProc;

const FreqProc FREQPROC[] = {
    { SECONDLY_text, SIZ(SECONDLY_text), EventRecurrence::SECONDLY },
    { MINUTELY_text, SIZ(MINUTELY_text), EventRecurrence::MINUTELY },
    { HOURLY_text, SIZ(HOURLY_text), EventRecurrence::HOURLY },
    { DAILY_text, SIZ(DAILY_text), EventRecurrence::DAILY },
    { WEEKLY_text, SIZ(WEEKLY_text), EventRecurrence::WEEKLY },
    { MONTHLY_text, SIZ(MONTHLY_text), EventRecurrence::MONTHLY },
    { YEARLY_text, SIZ(YEARLY_text), EventRecurrence::YEARLY },
    { NULL, 0, NONE_LHS },
};

const char16_t SU_text[] = { 'S','U' };
const char16_t MO_text[] = { 'M','O' };
const char16_t TU_text[] = { 'T','U' };
const char16_t WE_text[] = { 'W','E' };
const char16_t TH_text[] = { 'T','H' };
const char16_t FR_text[] = { 'F','R' };
const char16_t SA_text[] = { 'S','A' };

const FreqProc WEEKDAYPROC[] = {
    { SU_text, SIZ(SU_text), EventRecurrence::SU },
    { MO_text, SIZ(MO_text), EventRecurrence::MO },
    { TU_text, SIZ(TU_text), EventRecurrence::TU },
    { WE_text, SIZ(WE_text), EventRecurrence::WE },
    { TH_text, SIZ(TH_text), EventRecurrence::TH },
    { FR_text, SIZ(FR_text), EventRecurrence::FR },
    { SA_text, SIZ(SA_text), EventRecurrence::SA },
    { NULL, 0, NONE_LHS },
};

// returns the index into LHSPROC for the match or -1 if not found
inline static int
match_proc(const LHSProc* p, const char16_t* str, size_t len)
{
    int i = 0;
    while (p->text != NULL) {
        if (p->textSize == len) {
            if (0 == memcmp(p->text, str, len*sizeof(char16_t))) {
                return i;
            }
        }
        p++;
        i++;
    }
    return -1;
}

// rangeMin and rangeMax are inclusive
static status_t
parse_int(const char16_t* str, size_t len, int* out,
            int rangeMin, int rangeMax, bool zeroOK)
{
    char16_t c;
    size_t i=0;

    if (len == 0) {
        FAIL_HERE();
    }
    bool negative = false;
    c = str[0];
    if (c == '-' ) {
        negative = true;
        i++;
    }
    else if (c == '+') {
        i++;
    }
    int n = 0;
    for (; i<len; i++) {
        c = str[i];
        if (c < '0' || c > '9') {
            FAIL_HERE();
        }
        int prev = n;
        n *= 10;
        // the spec doesn't address how big these numbers can be,
        // so we're not going to worry about not being able to represent
        // INT_MIN, and if we're going to wrap, we'll just clamp to
        // INT_MAX instead
        if (n < prev) {
            n = INT_MAX;
        } else {
            n += c - '0';
        }
    }
    if (negative) {
        n = -n;
    }
    if (n < rangeMin || n > rangeMax) {
        FAIL_HERE();
    }
    if (!zeroOK && n == 0) {
        FAIL_HERE();
    }
    *out = n;
    return NO_ERROR;
}

static status_t
parse_int_list(const char16_t* str, size_t len, int* countOut, int** listOut,
          int rangeMin, int rangeMax, bool zeroOK,
          status_t (*func)(const char16_t*,size_t,int*,int,int,bool)=parse_int)
{
    status_t err;

    if (len == 0) {
        *countOut = 0;
        *listOut = NULL;
        return NO_ERROR;
    }

    // make one pass through looking for commas so we know how big to make our
    // out array.
    int count = 1;
    for (size_t i=0; i<len; i++) {
        if (str[i] == ',') {
            count++;
        }
    }

    int* list = new int[count];
    const char16_t* p = str;
    int commaIndex = 0;
    size_t i;

    for (i=0; i<len; i++) {
        if (str[i] == ',') {
            err = func(p, (str+i-p), list+commaIndex, rangeMin,
                    rangeMax, zeroOK);
            if (err != NO_ERROR) {
                goto bail;
            }
            commaIndex++;
            p = str+i+1;
        }
    }

    err = func(p, (str+i-p), list+commaIndex, rangeMin, rangeMax, zeroOK);
    if (err != NO_ERROR) {
        goto bail;
    }
    commaIndex++;

    *countOut = count;
    *listOut = list;

    return NO_ERROR;

bail:
    delete[] list;
    FAIL_HERE();
}

// the numbers here are small, so we pack them both into one value, and then
// split it out later.  it lets us reuse all the comma separated list code.
static status_t
parse_byday(const char16_t* s, size_t len, int* out,
            int rangeMin, int rangeMax, bool zeroOK)
{
    status_t err;
    int n = 0;
    const char16_t* p = s;
    size_t plen = len;

    if (len > 0) {
        char16_t c = s[0];
        if (c == '-' || c == '+' || (c >= '0' && c <= '9')) {
            if (len > 1) {
                size_t nlen = 0;
                c = s[nlen];
                while (nlen < len
                        && (c == '-' || c == '+' || (c >= '0' && c <= '9'))) {
                    c = s[nlen];
                    nlen++;
                }
                if (nlen > 0) {
                    nlen--;
                    err = parse_int(s, nlen, &n, rangeMin, rangeMax, zeroOK);
                    if (err != NO_ERROR) {
                        FAIL_HERE();
                    }
                    p += nlen;
                    plen -= nlen;
                }
            }
        }

        int index = match_proc(WEEKDAYPROC, p, plen);
        if (index >= 0) {
            *out = (0xffff0000 & WEEKDAYPROC[index].value)
                    | (0x0000ffff & n);
            return NO_ERROR;
        }
    }
    return UNKNOWN_ERROR;
}

static void
postprocess_byday(int count, int* byday, int** bydayNum)
{
    int* bdn = new int[count];
    *bydayNum = bdn;
    for (int i=0; i<count; i++) {
        uint32_t v = byday[i];
        int16_t num = v & 0x0000ffff;
        byday[i] = v & 0xffff0000;  
        // will sign extend:
        bdn[i] = num;
    }
}

#define PARSE_INT_LIST_CHECKED(name, rangeMin, rangeMax, zeroOK) \
    if (name##Count != 0 || NO_ERROR != parse_int_list(s, slen, \
                         &name##Count, &name, rangeMin, rangeMax, zeroOK)) { \
        FAIL_HERE(); \
    }
status_t
EventRecurrence::parse(const String16& str)
{
    char16_t const* work = str.string();
    size_t len = str.size();

    int lhsIndex = NONE_LHS;
    int index;
    
    size_t start = 0;
    for (size_t i=0; i<len; i++) {
        char16_t c = work[i];
        if (c != ';' && i == len-1) {
            c = ';';
            i++;
        }
        if (c == ';' || c == '=') {
            if (i != start) {
                const char16_t* s = work+start;
                const size_t slen = i-start;

                String8 thestring(String16(s, slen));

                switch (c)
                {
                    case '=':
                        if (lhsIndex == NONE_LHS) {
                            lhsIndex = match_proc(LHSPROC, s, slen);
                            if (lhsIndex >= 0) {
                                break;
                            }
                        }
                        FAIL_HERE();
                    case ';':
                    {
                        switch (LHSPROC[lhsIndex].value)
                        {
                            case FREQ:
                                if (this->freq != 0) {
                                    FAIL_HERE();
                                }
                                index = match_proc(FREQPROC, s, slen);
                                if (index >= 0) {
                                    this->freq = (freq_t)FREQPROC[index].value;
                                }
                                break;
                            case UNTIL:
                                // XXX should check that this is a valid time
                                until.setTo(String16(s, slen));
                                break;
                            case COUNT:
                                if (count != 0
                                     || NO_ERROR != parse_int(s, slen,
                                             &count, INT_MIN, INT_MAX, true)) {
                                    FAIL_HERE();
                                }
                                break;
                            case INTERVAL:
                                if (interval != 0
                                     || NO_ERROR != parse_int(s, slen,
                                         &interval, INT_MIN, INT_MAX, false)) {
                                    FAIL_HERE();
                                }
                                break;
                            case BYSECOND:
                                PARSE_INT_LIST_CHECKED(bysecond, 0, 59, true)
                                break;
                            case BYMINUTE:
                                PARSE_INT_LIST_CHECKED(byminute, 0, 59, true)
                                break;
                            case BYHOUR:
                                PARSE_INT_LIST_CHECKED(byhour, 0, 23, true)
                                break;
                            case BYDAY:
                                if (bydayCount != 0 || NO_ERROR != 
                                        parse_int_list(s, slen, &bydayCount,
                                              &byday, -53, 53, false,
                                              parse_byday)) {
                                    FAIL_HERE();
                                }
                                postprocess_byday(bydayCount, byday, &bydayNum);
                                break;
                            case BYMONTHDAY:
                                PARSE_INT_LIST_CHECKED(bymonthday, -31, 31,
                                                        false)
                                break;
                            case BYYEARDAY:
                                PARSE_INT_LIST_CHECKED(byyearday, -366, 366,
                                                        false)
                                break;
                            case BYWEEKNO:
                                PARSE_INT_LIST_CHECKED(byweekno, -53, 53,
                                                        false)
                                break;
                            case BYMONTH:
                                PARSE_INT_LIST_CHECKED(bymonth, 1, 12, false)
                                break;
                            case BYSETPOS:
                                PARSE_INT_LIST_CHECKED(bysetpos,
                                                        INT_MIN, INT_MAX, true)
                                break;
                            case WKST:
                                if (this->wkst != 0) {
                                    FAIL_HERE();
                                }
                                index = match_proc(WEEKDAYPROC, s, slen);
                                if (index >= 0) {
                                    this->wkst = (int)WEEKDAYPROC[index].value;
                                }
                                break;
                            default:
                                FAIL_HERE();
                        }
                        lhsIndex = NONE_LHS;
                        break;
                    }
                }

                start = i+1;
            }
        }
    }

    // enforce that there was a FREQ
    if (freq == 0) {
        FAIL_HERE();
    }

    // default wkst to MO if it wasn't specified
    if (wkst == 0) {
        wkst = MO;
    }

    return NO_ERROR;
}


}; // namespace android


