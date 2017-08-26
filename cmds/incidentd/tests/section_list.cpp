// This file is a dummy section_list.cpp used for test only.
#include "section_list.h"

const Section* SECTION_LIST[] = {
    NULL
};

const uint8_t LOCAL = 0;
const uint8_t EXPLICIT = 1;
const uint8_t AUTOMATIC = 2;

const Privacy* list[] = {
    new Privacy(1, 1, LOCAL),
    new Privacy(2, AUTOMATIC, (const char**)NULL),
    NULL };

const Privacy* PRIVACY_POLICY_LIST[] = {
    new Privacy(0, list),
    new Privacy(1, 9, AUTOMATIC),
    NULL
};