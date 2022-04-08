// This file is a dummy section_list.cpp used for test only.
#include "section_list.h"

#include "frameworks/base/cmds/incidentd/tests/test_proto.pb.h"


namespace android {
namespace os {
namespace incidentd {

class TestSection: public Section {
public:
    TestSection(int id);
    ~TestSection();
    virtual status_t Execute(ReportWriter* writer) const;
};

TestSection::TestSection(int id)
        :Section(id, 5000 /* ms timeout */) {
}

TestSection::~TestSection() {
}

status_t TestSection::Execute(ReportWriter* writer) const {
    uint8_t buf[1024];
    status_t err;

    TestSectionProto proto;
    proto.set_field_1(this->id);
    proto.set_field_2(this->id * 10);

    // Not infinitely scalable, but we know that our TestSectionProto will always
    // fit in this many bytes.
    if (!proto.SerializeToArray(buf, sizeof(buf))) {
        return -1;
    }
    FdBuffer buffer;
    err = buffer.write(buf, proto.ByteSize());
    if (err != NO_ERROR) {
        return err;
    }

    return writer->writeSection(buffer);
}

TestSection section1(1);
TestSection section2(2);

const Section* SECTION_LIST[] = {
    &section1,
    &section2,
    NULL
};

Privacy sub_field_1{1, 1, NULL, PRIVACY_POLICY_LOCAL, NULL};
Privacy sub_field_2{2, 9, NULL, PRIVACY_POLICY_AUTOMATIC, NULL};

Privacy* list[] = {&sub_field_1, &sub_field_2, NULL};

Privacy field_0{0, 11, list, PRIVACY_POLICY_EXPLICIT, NULL};
Privacy field_1{1, 9, NULL, PRIVACY_POLICY_AUTOMATIC, NULL};

Privacy* final_list[] = {&field_0, &field_1};

const Privacy** PRIVACY_POLICY_LIST = const_cast<const Privacy**>(final_list);

const int PRIVACY_POLICY_COUNT = 2;

}  // namespace incidentd
}  // namespace os
}  // namespace android
