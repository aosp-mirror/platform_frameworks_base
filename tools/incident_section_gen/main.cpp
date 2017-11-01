/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


#include <frameworks/base/core/proto/android/os/incident.pb.h>


#include <map>

using namespace android::os;
using namespace google::protobuf;
using namespace google::protobuf::io;
using namespace google::protobuf::internal;
using namespace std;

int
main(int, const char**)
{
    map<string,FieldDescriptor const*> sections;
    int N;

    printf("// Auto generated file. Do not modify\n");
    printf("\n");
    printf("#include \"incident_sections.h\"\n");
    printf("\n");

    Descriptor const* descriptor = IncidentProto::descriptor();
    N = descriptor->field_count();
    for (int i=0; i<N; i++) {
        const FieldDescriptor* field = descriptor->field(i);
        if (field->type() == FieldDescriptor::TYPE_MESSAGE) {
            sections[field->name()] = field;
        }
    }

    printf("IncidentSection const INCIDENT_SECTIONS[] = {\n");
    N = sections.size();
    int i = 0;
    for (map<string,FieldDescriptor const*>::const_iterator it = sections.begin();
            it != sections.end(); it++, i++) {
        const FieldDescriptor* field = it->second;
        printf("    { %d, \"%s\" }", field->number(), field->name().c_str());
        if (i != N-1) {
            printf(",\n");
        } else {
            printf("\n");
        }
    }
    printf("};\n");

    printf("const int INCIDENT_SECTION_COUNT = %d;\n", N);

    return 0;
}
