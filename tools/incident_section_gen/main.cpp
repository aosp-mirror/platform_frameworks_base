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
#include <string>

using namespace android;
using namespace android::os;
using namespace google::protobuf;
using namespace google::protobuf::io;
using namespace google::protobuf::internal;
using namespace std;

static void generateHead(const char* header) {
    printf("// Auto generated file. Do not modify\n");
    printf("\n");
    printf("#include \"%s.h\"\n", header);
    printf("\n");
}

// ================================================================================
static bool generateIncidentSectionsCpp()
{
    generateHead("incident_sections");

    map<string,FieldDescriptor const*> sections;
    int N;
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

    return true;
}

// ================================================================================
static void splitAndPrint(const string& args) {
    size_t base = 0;
    size_t found;
    while (true) {
        found = args.find_first_of(" ", base);
        if (found != base) {
            string arg = args.substr(base, found - base);
            printf(" \"%s\",", arg.c_str());
        }
        if (found == args.npos) break;
        base = found + 1;
    }
}

static bool generateSectionListCpp() {
    generateHead("section_list");

    printf("const Section* SECTION_LIST[] = {\n");
    Descriptor const* descriptor = IncidentProto::descriptor();
    for (int i=0; i<descriptor->field_count(); i++) {
        const FieldDescriptor* field = descriptor->field(i);

        if (field->type() != FieldDescriptor::TYPE_MESSAGE) {
            continue;
        }
        const SectionFlags s = field->options().GetExtension(section);
        switch (s.type()) {
            case SECTION_NONE:
                continue;
            case SECTION_FILE:
                printf("    new FileSection(%d, \"%s\"),\n", field->number(), s.args().c_str());
                break;
            case SECTION_COMMAND:
                printf("    new CommandSection(%d,", field->number());
                splitAndPrint(s.args());
                printf(" NULL),\n");
                break;
            case SECTION_DUMPSYS:
                printf("    new DumpsysSection(%d,", field->number());
                splitAndPrint(s.args());
                printf(" NULL),\n");
                break;
        }
    }
    printf("    NULL\n");
    printf("};\n");
    return true;
}

// ================================================================================
int main(int argc, char const *argv[])
{
    if (argc != 2) return 1;
    const char* module = argv[1];

    if (strcmp(module, "incident") == 0) {
        return !generateIncidentSectionsCpp();
    }
    if (strcmp(module, "incidentd") == 0 ) {
        return !generateSectionListCpp();
    }

    // return failure if not called by the whitelisted modules
    return 1;
}
