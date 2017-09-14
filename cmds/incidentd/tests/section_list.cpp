// This file is a dummy section_list.cpp used for test only.
#include "section_list.h"

const Section* SECTION_LIST[] = {
    NULL
};

const uint8_t LOCAL = 0;
const uint8_t EXPLICIT = 1;
const uint8_t AUTOMATIC = 2;

Privacy sub_field_1 { 1, 1, NULL, LOCAL, NULL };
Privacy sub_field_2 { 2, 9, NULL, AUTOMATIC, NULL };

Privacy* list[] = {
    &sub_field_1,
    &sub_field_2,
    NULL };

Privacy field_0 { 0, 11, list, EXPLICIT, NULL };
Privacy field_1 { 1, 9, NULL, AUTOMATIC, NULL };

const Privacy* PRIVACY_POLICY_LIST[] = {
    &field_0,
    &field_1
};

const int PRIVACY_POLICY_COUNT = 2;