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
#include <sstream>
#include <string>

#ifndef FALLTHROUGH_INTENDED
#define FALLTHROUGH_INTENDED [[fallthrough]]
#endif

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
 * Privacy WindowState__state { 1, 9, NULL, LOCAL, NULL }; // first two integers are values for field id and proto type.
 * Privacy WindowState__child_windows { 3, 11, NULL, UNSET, NULL }; // reserved for WindowState_LIST
 * Privacy* WindowState__MSG__UNSET[] = {
 *     &WindowState_state,
 *     // display id is default, nothing is generated.
 *     &WindowState_child_windows,
 *     NULL  // terminator of the array
 * };
 * Privacy WindowState__my_window { 1, 11, WindowState__MSG__UNSET, UNSET, NULL };
 *
 * createList() {
 *    ...
 *    WindowState_child_windows.children = WindowState__MSG_UNSET; // point to its own definition after the list is defined.
 *    ...
 * }
 *
 * const Privacy** PRIVACY_POLICY_LIST = createList();
 * const int PRIVACY_POLICY_COUNT = 1;
 *
 * Privacy Value Inheritance rules:
 * 1. Both field and message can be tagged with DESTINATION: LOCAL(L), EXPLICIT(E), AUTOMATIC(A).
 * 2. Primitives inherits containing message's tag unless defined explicitly.
 * 3. Containing message's tag doesn't apply to message fields, even when unset (in this case, uses its default message tag).
 * 4. Message field tag overrides its default message tag.
 * 5. UNSET tag defaults to EXPLICIT.
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
        sections[field->name()] = field;
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
        found = args.find_first_of(' ', base);
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

static inline void printPrivacy(const string& name, const FieldDescriptor* field, const string& children,
        const Destination dest, const string& patterns, const string& comments = "") {
    printf("Privacy %s = { %d, %d, %s, %d, %s };%s\n", name.c_str(), field->number(), field->type(),
        children.c_str(), dest, patterns.c_str(), comments.c_str());
}

// Get Custom Options ================================================================================
static inline SectionFlags getSectionFlags(const FieldDescriptor* field) {
    return field->options().GetExtension(section);
}

static inline PrivacyFlags getPrivacyFlags(const FieldDescriptor* field) {
    return field->options().GetExtension(privacy);
}

static inline PrivacyFlags getPrivacyFlags(const Descriptor* descriptor) {
    return descriptor->options().GetExtension(msg_privacy);
}

// Get Destinations ===================================================================================
static inline Destination getMessageDest(const Descriptor* descriptor, const Destination overridden) {
    return overridden != DEST_UNSET ? overridden : getPrivacyFlags(descriptor).dest();
}

// Returns field's own dest, when it is a message field, uses its message default tag if unset.
static inline Destination getFieldDest(const FieldDescriptor* field) {
    Destination fieldDest = getPrivacyFlags(field).dest();
    return field->type() != FieldDescriptor::TYPE_MESSAGE ? fieldDest :
            getMessageDest(field->message_type(), fieldDest);
}

// Converts Destination to a string.
static inline string getDestString(const Destination dest) {
    switch (dest) {
        case DEST_AUTOMATIC: return "AUTOMATIC";
        case DEST_LOCAL: return "LOCAL";
        case DEST_EXPLICIT: return "EXPLICIT";
        // UNSET is considered EXPLICIT by default.
        case DEST_UNSET: return "EXPLICIT";
        default: return "UNKNOWN";
    }
}

// Get Names ===========================================================================================
static inline string getFieldName(const FieldDescriptor* field) {
    // replace . with double underscores to avoid name conflicts since fields use snake naming convention
    return replaceAll(field->full_name(), '.', "__");
}


static inline string getMessageName(const Descriptor* descriptor, const Destination overridden) {
    // replace . with one underscore since messages use camel naming convention
    return replaceAll(descriptor->full_name(), '.', "_") + "__MSG__" +
            to_string(getMessageDest(descriptor, overridden));
}

// IsDefault ============================================================================================
// Returns true if a field is default. Default is defined as this field has same dest as its containing message.
// For message fields, it only looks at its field tag and own default message tag, doesn't recursively go deeper.
static inline bool isDefaultField(const FieldDescriptor* field, const Destination containerDest) {
    Destination fieldDest = getFieldDest(field);
    if (field->type() != FieldDescriptor::TYPE_MESSAGE) {
        return fieldDest == containerDest || (fieldDest == DEST_UNSET);
    } else {
        return fieldDest == containerDest ||
            (containerDest == DEST_UNSET && fieldDest == DEST_EXPLICIT) ||
            (containerDest == DEST_EXPLICIT && fieldDest == DEST_UNSET);
    }
}

static bool isDefaultMessageImpl(const Descriptor* descriptor, const Destination dest, set<string>* parents) {
    const int N = descriptor->field_count();
    const Destination messageDest = getMessageDest(descriptor, dest);
    parents->insert(descriptor->full_name());
    for (int i=0; i<N; ++i) {
        const FieldDescriptor* field = descriptor->field(i);
        const Destination fieldDest = getFieldDest(field);
        // If current field is not default, return false immediately
        if (!isDefaultField(field, messageDest)) return false;
        switch (field->type()) {
            case FieldDescriptor::TYPE_MESSAGE:
                // if self recursion, don't go deep.
                if (parents->find(field->message_type()->full_name()) != parents->end()) break;
                // if is a default message, just continue
                if (isDefaultMessageImpl(field->message_type(), fieldDest, parents)) break;
                // sub message is not default, so this message is always not default
                return false;
            case FieldDescriptor::TYPE_STRING:
                if (getPrivacyFlags(field).patterns_size() != 0) return false;
                break;
            default:
                break;
        }
    }
    parents->erase(descriptor->full_name());
    return true;
}

// Recursively look at if this message is default, meaning all its fields and sub-messages
// can be described by the same dest.
static bool isDefaultMessage(const Descriptor* descriptor, const Destination dest) {
    set<string> parents;
    return isDefaultMessageImpl(descriptor, dest, &parents);
}

// ===============================================================================================================
static bool numberInOrder(const FieldDescriptor* f1, const FieldDescriptor* f2) {
    return f1->number() < f2->number();
}

// field numbers are possibly out of order, sort them here.
static vector<const FieldDescriptor*> sortFields(const Descriptor* descriptor) {
    vector<const FieldDescriptor*> fields;
    fields.reserve(descriptor->field_count());
    for (int i=0; i<descriptor->field_count(); i++) {
        fields.push_back(descriptor->field(i));
    }
    std::sort(fields.begin(), fields.end(), numberInOrder);
    return fields;
}

// This function looks for privacy tags of a message type and recursively its sub-messages.
// It generates Privacy objects for each non-default fields including non-default sub-messages.
// And if the message has Privacy objects generated, it returns a list of them.
// Returns false if the descriptor doesn't have any non default privacy flags set, including its submessages
static bool generatePrivacyFlags(const Descriptor* descriptor, const Destination overridden,
        map<string, bool> &variableNames, set<string>* parents) {
    const string messageName = getMessageName(descriptor, overridden);
    const Destination messageDest = getMessageDest(descriptor, overridden);

    if (variableNames.find(messageName) != variableNames.end()) {
        bool hasDefault = variableNames[messageName];
        return !hasDefault; // if has default, then don't generate privacy flags.
    }
    // insert the message type name so sub-message will figure out if self-recursion occurs
    parents->insert(messageName);

    // sort fields based on number, iterate though them and generate sub flags first
    vector<const FieldDescriptor*> fieldsInOrder = sortFields(descriptor);
    bool hasDefaultFlags[fieldsInOrder.size()];
    for (size_t i=0; i<fieldsInOrder.size(); i++) {
        const FieldDescriptor* field = fieldsInOrder[i];
        const string fieldName = getFieldName(field);
        const Destination fieldDest = getFieldDest(field);

        if (variableNames.find(fieldName) != variableNames.end()) {
            hasDefaultFlags[i] = variableNames[fieldName];
            continue;
        }
        hasDefaultFlags[i] = isDefaultField(field, messageDest);

        string fieldMessageName;
        PrivacyFlags p = getPrivacyFlags(field);
        switch (field->type()) {
            case FieldDescriptor::TYPE_MESSAGE:
                fieldMessageName = getMessageName(field->message_type(), fieldDest);
                if (parents->find(fieldMessageName) != parents->end()) { // Self-Recursion proto definition
                    if (hasDefaultFlags[i]) {
                        hasDefaultFlags[i] = isDefaultMessage(field->message_type(), fieldDest);
                    }
                    if (!hasDefaultFlags[i]) {
                        printPrivacy(fieldName, field, "NULL", fieldDest, "NULL",
                            " // self recursion field of " + fieldMessageName);
                        // generate the assignment and used to construct createList function later on.
                        gSelfRecursionAssignments.push_back(fieldName + ".children = " + fieldMessageName);
                    }
                } else if (generatePrivacyFlags(field->message_type(), p.dest(), variableNames, parents)) {
                    if (variableNames.find(fieldName) == variableNames.end()) {
                        printPrivacy(fieldName, field, fieldMessageName, fieldDest, "NULL");
                    }
                    hasDefaultFlags[i] = false;
                } else if (!hasDefaultFlags[i]) {
                    printPrivacy(fieldName, field, "NULL", fieldDest, "NULL");
                }
                break;
            case FieldDescriptor::TYPE_STRING:
                if (p.patterns_size() != 0) { // if patterns are specified
                    if (hasDefaultFlags[i]) break;
                    printf("const char* %s_patterns[] = {\n", fieldName.c_str());
                    for (int j=0; j<p.patterns_size(); j++) {
                        // generated string needs to escape backslash too, duplicate it to allow escape again.
                        printf("    \"%s\",\n", replaceAll(p.patterns(j), '\\', "\\\\").c_str());
                    }
                    printf("    NULL };\n");
                    printPrivacy(fieldName, field, "NULL", fieldDest, fieldName + "_patterns");
                    break;
                }
                FALLTHROUGH_INTENDED;
                // else treat string field as primitive field and goes to default
            default:
                if (!hasDefaultFlags[i]) printPrivacy(fieldName, field, "NULL", fieldDest, "NULL");
        }
        // Don't generate a variable twice
        if (!hasDefaultFlags[i]) variableNames[fieldName] = false;
    }

    bool allDefaults = true;
    for (size_t i=0; i<fieldsInOrder.size(); i++) {
        allDefaults &= hasDefaultFlags[i];
    }

    parents->erase(messageName); // erase the message type name when exit the message.
    variableNames[messageName] = allDefaults; // store the privacy tags of the message here to avoid overhead.

    if (allDefaults) return false;

    emptyline();
    int policyCount = 0;
    printf("Privacy* %s[] = {\n", messageName.c_str());
    for (size_t i=0; i<fieldsInOrder.size(); i++) {
        const FieldDescriptor* field = fieldsInOrder[i];
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

    // generate namespaces
    printf("namespace android {\n");
    printf("namespace os {\n");
    printf("namespace incidentd {\n");

    // generates SECTION_LIST
    printf("// Generate SECTION_LIST.\n\n");

    printf("const Section* SECTION_LIST[] = {\n");
    for (int i=0; i<descriptor->field_count(); i++) {
        const FieldDescriptor* field = descriptor->field(i);

        if (field->type() != FieldDescriptor::TYPE_MESSAGE &&
            field->type() != FieldDescriptor::TYPE_STRING &&
            field->type() != FieldDescriptor::TYPE_BYTES) {
          continue;
        }

        const SectionFlags s = getSectionFlags(field);
        if (s.userdebug_and_eng_only()) {
            printf("#if ALLOW_RESTRICTED_SECTIONS\n");
        }

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
                printf("    new DumpsysSection(%d, ", field->number());
                splitAndPrint(s.args());
                printf(" NULL),\n");
                break;
            case SECTION_LOG:
                printf("    new LogSection(%d, %s),\n", field->number(), s.args().c_str());
                break;
            case SECTION_GZIP:
                printf("    new GZipSection(%d,", field->number());
                splitAndPrint(s.args());
                printf(" NULL),\n");
                break;
            case SECTION_TOMBSTONE:
                printf("    new TombstoneSection(%d, \"%s\"),\n", field->number(),
                        s.args().c_str());
                break;
        }
        if (s.userdebug_and_eng_only()) {
            printf("#endif\n");
        }
    }
    printf("    NULL };\n");

    emptyline();
    printf("// =============================================================================\n");
    emptyline();

    // generates PRIVACY_POLICY_LIST
    printf("// Generate PRIVACY_POLICY_LIST.\n\n");
    map<string, bool> variableNames;
    set<string> parents;
    vector<const FieldDescriptor*> fieldsInOrder = sortFields(descriptor);
    vector<bool> skip(fieldsInOrder.size());
    const Destination incidentDest = getPrivacyFlags(descriptor).dest();

    for (size_t i=0; i<fieldsInOrder.size(); i++) {
        const FieldDescriptor* field = fieldsInOrder[i];
        const string fieldName = getFieldName(field);
        const Destination fieldDest = getFieldDest(field);
        printf("\n// Incident Report Section: %s (%d)\n", field->name().c_str(), field->number());
        if (field->type() != FieldDescriptor::TYPE_MESSAGE) {
            printPrivacy(fieldName, field, "NULL", fieldDest, "NULL");
            continue;
        }

        skip[i] = true;
        const string fieldMessageName = getMessageName(field->message_type(), fieldDest);
        // generate privacy flags for each section.
        if (generatePrivacyFlags(field->message_type(), incidentDest, variableNames, &parents)) {
            printPrivacy(fieldName, field, fieldMessageName, fieldDest, "NULL");
        } else if (fieldDest == incidentDest) {
            printf("// default %s: fieldDest=%d incidentDest=%d\n", fieldName.c_str(),
                    getFieldDest(field), incidentDest);
            continue; // don't create a new privacy if the value is default.
        } else {
            printPrivacy(fieldName, field, "NULL", fieldDest, "NULL");
        }
        skip[i] = false;
    }

    // generate final PRIVACY_POLICY_LIST
    emptyline();
    int policyCount = 0;
    if (gSelfRecursionAssignments.empty()) {
        printf("Privacy* privacyArray[] = {\n");
        for (size_t i=0; i<fieldsInOrder.size(); i++) {
            if (skip[i]) continue;
            printf("    &%s,\n", getFieldName(fieldsInOrder[i]).c_str());
            policyCount++;
        }
        printf("};\n\n");
        printf("const Privacy** PRIVACY_POLICY_LIST = const_cast<const Privacy**>(privacyArray);\n\n");
        printf("const int PRIVACY_POLICY_COUNT = %d;\n", policyCount);
    } else {
        for (size_t i=0; i<fieldsInOrder.size(); i++) {
            if (!skip[i]) policyCount++;
        }

        printf("static const Privacy** createList() {\n");
        for (size_t i=0; i<gSelfRecursionAssignments.size(); ++i) {
            printf("    %s;\n", gSelfRecursionAssignments[i].c_str());
        }
        printf("    Privacy** privacyArray = (Privacy**)malloc(%d * sizeof(Privacy**));\n", policyCount);
        policyCount = 0; // reset
        for (size_t i=0; i<fieldsInOrder.size(); i++) {
            if (skip[i]) continue;
            printf("    privacyArray[%d] = &%s;\n", policyCount++, getFieldName(fieldsInOrder[i]).c_str());
        }
        printf("    return const_cast<const Privacy**>(privacyArray);\n");
        printf("}\n\n");
        printf("const Privacy** PRIVACY_POLICY_LIST = createList();\n\n");
        printf("const int PRIVACY_POLICY_COUNT = %d;\n", policyCount);
    }

    printf("}  // incidentd\n");
    printf("}  // os\n");
    printf("}  // android\n");
    return true;
}

// ================================================================================
static string replace_string(const string& str, const char replace, const char with)
{
    string result(str);
    const int N = result.size();
    for (int i=0; i<N; i++) {
        if (result[i] == replace) {
            result[i] = with;
        }
    }
    return result;
}

static void generateCsv(Descriptor const* descriptor, const string& indent, set<string>* parents, const Destination containerDest = DEST_UNSET) {
    DebugStringOptions options;
    options.include_comments = true;
    for (int i=0; i<descriptor->field_count(); i++) {
        const FieldDescriptor* field = descriptor->field(i);
        const Destination fieldDest = getFieldDest(field);
        stringstream text;
        if (field->type() == FieldDescriptor::TYPE_MESSAGE) {
            text << field->message_type()->name();
        } else {
            text << field->type_name();
        }
        text << " " << field->name();
        text << " (PRIVACY=";
        if (isDefaultField(field, containerDest)) {
            text << getDestString(containerDest);
        } else {
            text << getDestString(fieldDest);
        }
        text << ")";
        printf("%s%s,\n", indent.c_str(), replace_string(text.str(), '\n', ' ').c_str());
        if (field->type() == FieldDescriptor::TYPE_MESSAGE &&
            parents->find(field->message_type()->full_name()) == parents->end()) {
            parents->insert(field->message_type()->full_name());
            generateCsv(field->message_type(), indent + ",", parents, fieldDest);
            parents->erase(field->message_type()->full_name());
        }
    }
}

// ================================================================================
int main(int argc, char const *argv[])
{
    if (argc < 2) return 1;
    const char* module = argv[1];

    Descriptor const* descriptor = IncidentProto::descriptor();

    if (strcmp(module, "incident") == 0) {
        return !generateIncidentSectionsCpp(descriptor);
    }
    if (strcmp(module, "incidentd") == 0 ) {
        return !generateSectionListCpp(descriptor);
    }
    // Generates Csv Format of proto definition for each section.
    if (strcmp(module, "csv") == 0 && argc > 2) {
        int sectionId = atoi(argv[2]);
        for (int i=0; i<descriptor->field_count(); i++) {
            const FieldDescriptor* field = descriptor->field(i);
            if (strcmp(field->name().c_str(), argv[2]) == 0
                || field->number() == sectionId) {
                set<string> parents;
                printf("%s\n", field->name().c_str());
                generateCsv(field->message_type(), "", &parents, getFieldDest(field));
                break;
            }
        }
        // Returns failure if csv is enabled to prevent Android building with it.
        // It doesn't matter if this command runs manually.
        return 1;
    }
    // Returns failure if not called by the whitelisted modules
    return 1;
}
