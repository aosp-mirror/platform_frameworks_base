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

static inline void emptyline() {
    printf("\n");
}

static void generateHead(const char* header) {
    printf("// Auto generated file. Do not modify\n");
    emptyline();
    printf("#include \"%s.h\"\n", header);
    emptyline();
}

// ================================================================================
static bool generateIncidentSectionsCpp(Descriptor const* descriptor)
{
    generateHead("incident_sections");

    map<string,FieldDescriptor const*> sections;
    int N;
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

static const std::string replaceAll(const string& field_name, const char oldC, const string& newS) {
    if (field_name.find_first_of(oldC) == field_name.npos) return field_name.c_str();
    size_t pos = 0, idx = 0;
    char* res = new char[field_name.size() * newS.size() + 1]; // assign a larger buffer
    while (pos != field_name.size()) {
        char cur = field_name[pos++];
        if (cur != oldC) {
            res[idx++] = cur;
            continue;
        }

        for (size_t i=0; i<newS.size(); i++) {
            res[idx++] = newS[i];
        }
    }
    res[idx] = '\0';
    std::string result(res);
    delete [] res;
    return result;
}

static inline bool isDefaultDest(const FieldDescriptor* field) {
    return field->options().GetExtension(privacy).dest() == PrivacyFlags::default_instance().dest();
}

// Returns true if the descriptor doesn't have any non default privacy flags set, including its submessages
static bool generatePrivacyFlags(const Descriptor* descriptor, const char* alias, map<string, bool> &msgNames) {
    bool hasDefaultFlags[descriptor->field_count()];
    // iterate though its field and generate sub flags first
    for (int i=0; i<descriptor->field_count(); i++) {
        hasDefaultFlags[i] = true; // set default to true
        const FieldDescriptor* field = descriptor->field(i);
        const std::string field_name_str = replaceAll(field->full_name(), '.', "__");
        const char* field_name = field_name_str.c_str();
        // check if the same name is already defined
        if (msgNames.find(field_name) != msgNames.end()) {
            hasDefaultFlags[i] = msgNames[field_name];
            continue;
        };

        PrivacyFlags p = field->options().GetExtension(privacy);

        switch (field->type()) {
            case FieldDescriptor::TYPE_MESSAGE:
                if (generatePrivacyFlags(field->message_type(), field_name, msgNames) &&
                    isDefaultDest(field)) break;

                printf("Privacy %s { %d, %d, %s_LIST, %d, NULL };\n", field_name, field->number(), field->type(), field_name, p.dest());
                hasDefaultFlags[i] = false;
                break;
            case FieldDescriptor::TYPE_STRING:
                if (isDefaultDest(field) && p.patterns_size() == 0) break;

                printf("const char* %s_patterns[] = {\n", field_name);
                for (int i=0; i<p.patterns_size(); i++) {
                    // the generated string need to escape backslash as well, need to dup it here
                    printf("    \"%s\",\n", replaceAll(p.patterns(i), '\\', "\\\\").c_str());
                }
                printf("    NULL };\n");
                printf("Privacy %s { %d, %d, NULL, %d, %s_patterns };\n", field_name, field->number(), field->type(), p.dest(), field_name);
                hasDefaultFlags[i] = false;
                break;
            default:
                if (isDefaultDest(field)) break;
                printf("Privacy %s { %d, %d, NULL, %d, NULL };\n", field_name, field->number(), field->type(), p.dest());
                hasDefaultFlags[i] = false;
        }
        // add the field name to message map, true means it has default flags
        msgNames[field_name] = hasDefaultFlags[i];
    }

    bool allDefaults = true;
    for (int i=0; i<descriptor->field_count(); i++) {
        allDefaults &= hasDefaultFlags[i];
    }
    if (allDefaults) return true;

    emptyline();

    bool needConst = strcmp(alias, "PRIVACY_POLICY") == 0;
    int policyCount = 0;

    printf("%s Privacy* %s_LIST[] = {\n", needConst ? "const" : "", alias);
    for (int i=0; i<descriptor->field_count(); i++) {
        const FieldDescriptor* field = descriptor->field(i);
        if (hasDefaultFlags[i]) continue;
        printf("    &%s,\n", replaceAll(field->full_name(), '.', "__").c_str());
        policyCount++;
    }
    if (needConst) {
        printf("};\n\n");
        printf("const int PRIVACY_POLICY_COUNT = %d;\n", policyCount);
    } else {
        printf("    NULL };\n");
    }
    emptyline();
    return false;
}

static bool generateSectionListCpp(Descriptor const* descriptor) {
    generateHead("section_list");

    // generates SECTION_LIST
    printf("const Section* SECTION_LIST[] = {\n");
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
    printf("    NULL };\n");
    emptyline();

    // generates PRIVACY_POLICY
    map<string, bool> messageNames;
    if (generatePrivacyFlags(descriptor, "PRIVACY_POLICY", messageNames)) {
        // if no privacy options set at all, define an empty list
        printf("const Privacy* PRIVACY_POLICY_LIST[] = {};\n");
        printf("const int PRIVACY_POLICY_COUNT = 0;\n");
    }

    return true;
}

// ================================================================================
int main(int argc, char const *argv[])
{
    if (argc != 2) return 1;
    const char* module = argv[1];

    Descriptor const* descriptor = IncidentProto::descriptor();

    if (strcmp(module, "incident") == 0) {
        return !generateIncidentSectionsCpp(descriptor);
    }
    if (strcmp(module, "incidentd") == 0 ) {
        return !generateSectionListCpp(descriptor);
    }

    // return failure if not called by the whitelisted modules
    return 1;
}
