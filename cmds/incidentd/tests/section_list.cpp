// This file is a dummy section_list.cpp used for test only.
#include "section_list.h"

namespace android {
namespace os {
namespace incidentd {

const Section* SECTION_LIST[] = {NULL};

Privacy sub_field_1{1, 1, NULL, DEST_LOCAL, NULL};
Privacy sub_field_2{2, 9, NULL, DEST_AUTOMATIC, NULL};

Privacy* list[] = {&sub_field_1, &sub_field_2, NULL};

Privacy field_0{0, 11, list, DEST_EXPLICIT, NULL};
Privacy field_1{1, 9, NULL, DEST_AUTOMATIC, NULL};

Privacy* final_list[] = {&field_0, &field_1};

const Privacy** PRIVACY_POLICY_LIST = const_cast<const Privacy**>(final_list);

const int PRIVACY_POLICY_COUNT = 2;

}  // namespace incidentd
}  // namespace os
}  // namespace android