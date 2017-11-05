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
#include <set>
#include <string>

using namespace android;
using namespace android::os;
using namespace google::protobuf;
using namespace google::protobuf::io;
using namespace google::protobuf::internal;
using namespace std;

/**
 * Implementation details:
 * This binary auto generates .cpp files for incident and incidentd.
 *
 * When argument "incident" is specified, it generates incident_section.cpp file.
 *
 * When argument "incidentd" is specified, it generates section_list.cpp file.
 *
 * In section_list.cpp file, it generates a SECTION_LIST array and a PRIVACY_POLICY_LIST array.
 * For SECTION_LIST, it generates Section.h classes only for proto fields with section option enabled.
 * For PRIVACY_POLICY_LIST, it generates Privacy.h classes only for proto fields with privacy option enabled.
 *
 * For Privacy struct, it is possible to have self recursion definitions since protobuf is defining "classes"
 * So the logic to handle it becomes very complicated when Privacy tag of a message contains a list of Privacies
 * of its sub-messages. The code also handles multiple depth of self recursion fields.
 *
 * For example here is a one level self recursion message WindowManager:
 * message WindowState {
 *     string state = 1 [(privacy).dest = LOCAL];
 *     int32  display_id = 2;
 *     repeated WindowState child_windows = 3;
 * }
 *
 * message WindowManager {
 *     WindowState my_window = 1;
 * }
 *
 * When generating Privacy options for WindowManager, this tool will generate cpp syntax source code:
 *
 * #include "section_list.h"
 * ...
 * Privacy WindowState_state { 1, 9, NULL, LOCAL, NULL }; // first two integers are values for field id and proto type.
 * Privacy WindowState_child_windows { 3, 11, NULL, DEFAULT, NULL }; // reserved for WindowState_LIST
 * Privacy* WindowState_MSG_[] = {
 *     &WindowState_state,
 *     // display id is default, nothing is generated.
 *     &WindowState_child_windows,
 *     NULL  // terminator of the array
 * };
 * Privacy WindowState_my_window { 1, 11, WindowState_my_window_LIST, DEFAULT, NULL };
 *
 * createList() {
 *    ...
 *    WindowState_child_windows.children = WindowState_my_window_LIST; // point to its own definition after the list is defined.
 *    ...
 * }
 *
 * const Privacy** PRIVACY_POLICY_LIST = createList();
 * const int PRIVACY_POLICY_COUNT = 1;
 */

// The assignments will be called when constructs PRIVACY_POLICY_LIST, has to be global variable
vector<string> gSelfRecursionAssignments;

static inline void emptyline() {
    printf("\n");
}

static void generateHead(const char* header) {
    printf("// Auto generated file. Do not modify\n");
    emptyline();
    printf("#include \"%s.h\"\n", header);
    emptyline();
}

// ======================== incident_sections =============================
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

// ========================= section_list ===================================
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

static string replaceAll(const string& fieldName, const char oldC, const string& newS) {
    if (fieldName.find_first_of(oldC) == fieldName.npos) return fieldName.c_str();
    size_t pos = 0, idx = 0;
    char* res = new char[fieldName.size() * newS.size() + 1]; // assign a larger buffer
    while (pos != fieldName.size()) {
        char cur = fieldName[pos++];
        if (cur != oldC) {
            res[idx++] = cur;
            continue;
        }

        for (size_t i=0; i<newS.size(); i++) {
            res[idx++] = newS[i];
        }
    }
    res[idx] = '\0';
    string result(res);
    delete [] res;
    return result;
}

static string getFieldName(const FieldDescriptor* field) {
    return replaceAll(field->full_name(), '.', "__");
}

static string getMessageTypeName(const Descriptor* descriptor) {
    return replaceAll(descriptor->full_name(), '.', "_") + "_MSG_";
}

static inline SectionFlags getSectionFlags(const FieldDescriptor* field) {
    return field->options().GetExtension(section);
}

static inline PrivacyFlags getPrivacyFlags(const FieldDescriptor* field) {
    return field->options().GetExtension(privacy);
}

static inline bool isDefaultField(const FieldDescriptor* field) {
    return getPrivacyFlags(field).dest() == PrivacyFlags::default_instance().dest();
}

static bool isDefaultMessageImpl(const Descriptor* descriptor, set<string>* parents) {
    int N = descriptor->field_count();
    parents->insert(descriptor->full_name());
    for (int i=0; i<N; ++i) {
        const FieldDescriptor* field = descriptor->field(i);
        // look at if the current field is default or not, return false immediately
        if (!isDefaultField(field)) return false;

        switch (field->type()) {
            case FieldDescriptor::TYPE_MESSAGE:
                // if self recursion, don't go deep.
                if (parents->find(field->message_type()->full_name()) != parents->end()) break;
                // if is a default message, just continue
                if (isDefaultMessageImpl(field->message_type(), parents)) break;
                // sub message is not default, so this message is always not default
                return false;
            case FieldDescriptor::TYPE_STRING:
                if (getPrivacyFlags(field).patterns_size() != 0) return false;
            default:
                continue;
        }
    }
    parents->erase(descriptor->full_name());
    return true;
}

static bool isDefaultMessage(const Descriptor* descriptor) {
    set<string> parents;
    return isDefaultMessageImpl(descriptor, &parents);
}

// This function is called for looking at privacy tags for a message type and recursively its sub-messages
// It prints out each fields's privacy tags and a List of Privacy of the message itself (don't print default values)
// Returns false if the descriptor doesn't have any non default privacy flags set, including its submessages
static bool generatePrivacyFlags(const Descriptor* descriptor, map<string, bool> &msgNames, set<string>* parents) {
    bool hasDefaultFlags[descriptor->field_count()];

    string messageTypeName = getMessageTypeName(descriptor);
    // if the message is already defined, skip it.
    if (msgNames.find(messageTypeName) != msgNames.end()) {
        bool hasDefault = msgNames[messageTypeName];
        return !hasDefault; // don't generate if it has default privacy.
    }
    // insert the message type name so sub-message will figure out if self-recursion occurs
    parents->insert(messageTypeName);

    // iterate though its field and generate sub flags first
    for (int i=0; i<descriptor->field_count(); i++) {
        hasDefaultFlags[i] = true; // set default to true

        const FieldDescriptor* field = descriptor->field(i);
        const string fieldName = getFieldName(field);
        // check if the same field name is already defined.
        if (msgNames.find(fieldName) != msgNames.end()) {
            hasDefaultFlags[i] = msgNames[fieldName];
            continue;
        };

        PrivacyFlags p = getPrivacyFlags(field);
        string fieldMessageName;
        switch (field->type()) {
            case FieldDescriptor::TYPE_MESSAGE:
                fieldMessageName = getMessageTypeName(field->message_type());
                if (parents->find(fieldMessageName) != parents->end()) { // Self-Recursion proto definition
                    if (isDefaultField(field)) {
                        hasDefaultFlags[i] = isDefaultMessage(field->message_type());
                    } else {
                        hasDefaultFlags[i] = false;
                    }
                    if (!hasDefaultFlags[i]) {
                        printf("Privacy %s = { %d, %d, NULL, %d, NULL }; // self recursion field of %s\n",
                                fieldName.c_str(), field->number(), field->type(), p.dest(), fieldMessageName.c_str());
                        // generate the assignment and used to construct createList function later on.
                        gSelfRecursionAssignments.push_back(fieldName + ".children = " + fieldMessageName);
                    }
                    break;
                } else if (generatePrivacyFlags(field->message_type(), msgNames, parents)) {
                    printf("Privacy %s = { %d, %d, %s, %d, NULL };\n", fieldName.c_str(), field->number(),
                            field->type(), fieldMessageName.c_str(), p.dest());
                } else if (isDefaultField(field)) {
                    // don't create a new privacy if the value is default.
                    break;
                } else {
                    printf("Privacy %s = { %d, %d, NULL, %d, NULL };\n", fieldName.c_str(), field->number(),
                            field->type(), p.dest());
                }
                hasDefaultFlags[i] = false;
                break;
            case FieldDescriptor::TYPE_STRING:
                if (isDefaultField(field) && p.patterns_size() == 0) break;

                printf("const char* %s_patterns[] = {\n", fieldName.c_str());
                for (int i=0; i<p.patterns_size(); i++) {
                    // the generated string need to escape backslash as well, need to dup it here
                    printf("    \"%s\",\n", replaceAll(p.patterns(i), '\\', "\\\\").c_str());
                }
                printf("    NULL };\n");
                printf("Privacy %s = { %d, %d, NULL, %d, %s_patterns };\n", fieldName.c_str(), field->number(),
                        field->type(), p.dest(), fieldName.c_str());
                hasDefaultFlags[i] = false;
                break;
            default:
                if (isDefaultField(field)) break;
                printf("Privacy %s = { %d, %d, NULL, %d, NULL };\n", fieldName.c_str(), field->number(),
                        field->type(), p.dest());
                hasDefaultFlags[i] = false;
        }
        // add the field name to message map, true means it has default flags
        msgNames[fieldName] = hasDefaultFlags[i];
    }

    bool allDefaults = true;
    for (int i=0; i<descriptor->field_count(); i++) {
        allDefaults &= hasDefaultFlags[i];
    }

    parents->erase(messageTypeName); // erase the message type name when exit the message.
    msgNames[messageTypeName] = allDefaults; // store the privacy tags of the message here to avoid overhead.

    if (allDefaults) return false;

    emptyline();
    int policyCount = 0;
    printf("Privacy* %s[] = {\n", messageTypeName.c_str());
    for (int i=0; i<descriptor->field_count(); i++) {
        const FieldDescriptor* field = descriptor->field(i);
        if (hasDefaultFlags[i]) continue;
        printf("    &%s,\n", getFieldName(field).c_str());
        policyCount++;
    }
    printf("    NULL };\n");
    emptyline();
    return true;
}

static bool generateSectionListCpp(Descriptor const* descriptor) {
    generateHead("section_list");

    // generates SECTION_LIST
    printf("// Generate SECTION_LIST.\n\n");

    printf("const Section* SECTION_LIST[] = {\n");
    for (int i=0; i<descriptor->field_count(); i++) {
        const FieldDescriptor* field = descriptor->field(i);

        if (field->type() != FieldDescriptor::TYPE_MESSAGE) {
            continue;
        }
        const SectionFlags s = getSectionFlags(field);
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
    printf("// =============================================================================\n");
    emptyline();

    // generates PRIVACY_POLICY_LIST
    printf("// Generate PRIVACY_POLICY_LIST.\n\n");
    map<string, bool> messageNames;
    set<string> parents;
    bool skip[descriptor->field_count()];

    for (int i=0; i<descriptor->field_count(); i++) {
        const FieldDescriptor* field = descriptor->field(i);
        const string fieldName = getFieldName(field);
        PrivacyFlags p = getPrivacyFlags(field);

        skip[i] = true;

        if (field->type() != FieldDescriptor::TYPE_MESSAGE) {
            continue;
        }
        // generate privacy flags for each field.
        if (generatePrivacyFlags(field->message_type(), messageNames, &parents)) {
            printf("Privacy %s { %d, %d, %s, %d, NULL };\n", fieldName.c_str(), field->number(),
                    field->type(), getMessageTypeName(field->message_type()).c_str(), p.dest());
        } else if (isDefaultField(field)) {
            continue; // don't create a new privacy if the value is default.
        } else {
            printf("Privacy %s { %d, %d, NULL, %d, NULL };\n", fieldName.c_str(), field->number(),
                    field->type(), p.dest());
        }
        skip[i] = false;
    }

    // generate final PRIVACY_POLICY_LIST
    emptyline();
    int policyCount = 0;
    if (gSelfRecursionAssignments.empty()) {
        printf("Privacy* privacyArray[] = {\n");
        for (int i=0; i<descriptor->field_count(); i++) {
            if (skip[i]) continue;
            printf("    &%s,\n", getFieldName(descriptor->field(i)).c_str());
            policyCount++;
        }
        printf("};\n\n");
        printf("const Privacy** PRIVACY_POLICY_LIST = const_cast<const Privacy**>(privacyArray);\n\n");
        printf("const int PRIVACY_POLICY_COUNT = %d;\n", policyCount);
    } else {
        for (int i=0; i<descriptor->field_count(); i++) {
            if (!skip[i]) policyCount++;
        }

        printf("static const Privacy** createList() {\n");
        for (size_t i=0; i<gSelfRecursionAssignments.size(); ++i) {
            printf("    %s;\n", gSelfRecursionAssignments[i].c_str());
        }
        printf("    Privacy** privacyArray = (Privacy**)malloc(%d * sizeof(Privacy**));\n", policyCount);
        policyCount = 0; // reset
        for (int i=0; i<descriptor->field_count(); i++) {
            if (skip[i]) continue;
            printf("    privacyArray[%d] = &%s;\n", policyCount++, getFieldName(descriptor->field(i)).c_str());
        }
        printf("    return const_cast<const Privacy**>(privacyArray);\n");
        printf("}\n\n");
        printf("const Privacy** PRIVACY_POLICY_LIST = createList();\n\n");
        printf("const int PRIVACY_POLICY_COUNT = %d;\n", policyCount);
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
