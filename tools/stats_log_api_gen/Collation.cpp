/*
 * Copyright (C) 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "Collation.h"

#include <stdio.h>
#include <map>

namespace android {
namespace stats_log_api_gen {

using google::protobuf::EnumDescriptor;
using google::protobuf::FieldDescriptor;
using google::protobuf::FileDescriptor;
using google::protobuf::SourceLocation;
using std::map;


//
// AtomDecl class
//

AtomDecl::AtomDecl()
    :code(0),
     name()
{
}

AtomDecl::AtomDecl(const AtomDecl& that)
    :code(that.code),
     name(that.name),
     message(that.message),
     fields(that.fields)
{
}

AtomDecl::AtomDecl(int c, const string& n, const string& m)
    :code(c),
     name(n),
     message(m)
{
}

AtomDecl::~AtomDecl()
{
}


/**
 * Print an error message for a FieldDescriptor, including the file name and line number.
 */
static void
print_error(const FieldDescriptor* field, const char* format, ...)
{
    const Descriptor* message = field->containing_type();
    const FileDescriptor* file = message->file();

    SourceLocation loc;
    if (field->GetSourceLocation(&loc)) {
        // TODO: this will work if we can figure out how to pass --include_source_info to protoc
        fprintf(stderr, "%s:%d: ", file->name().c_str(), loc.start_line);
    } else {
        fprintf(stderr, "%s: ", file->name().c_str());
    }
    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end (args);
}

/**
 * Convert a protobuf type into a java type.
 */
static java_type_t
java_type(const FieldDescriptor* field)
{
    int protoType = field->type();
    switch (protoType) {
        case FieldDescriptor::TYPE_DOUBLE:
            return JAVA_TYPE_DOUBLE;
        case FieldDescriptor::TYPE_FLOAT:
            return JAVA_TYPE_FLOAT;
        case FieldDescriptor::TYPE_INT64:
            return JAVA_TYPE_LONG;
        case FieldDescriptor::TYPE_UINT64:
            return JAVA_TYPE_LONG;
        case FieldDescriptor::TYPE_INT32:
            return JAVA_TYPE_INT;
        case FieldDescriptor::TYPE_FIXED64:
            return JAVA_TYPE_LONG;
        case FieldDescriptor::TYPE_FIXED32:
            return JAVA_TYPE_INT;
        case FieldDescriptor::TYPE_BOOL:
            return JAVA_TYPE_BOOLEAN;
        case FieldDescriptor::TYPE_STRING:
            return JAVA_TYPE_STRING;
        case FieldDescriptor::TYPE_GROUP:
            return JAVA_TYPE_UNKNOWN;
        case FieldDescriptor::TYPE_MESSAGE:
            // TODO: not the final package name
            if (field->message_type()->full_name() == "android.os.statsd.WorkSource") {
                return JAVA_TYPE_WORK_SOURCE;
            } else {
                return JAVA_TYPE_OBJECT;
            }
        case FieldDescriptor::TYPE_BYTES:
            return JAVA_TYPE_BYTE_ARRAY;
        case FieldDescriptor::TYPE_UINT32:
            return JAVA_TYPE_INT;
        case FieldDescriptor::TYPE_ENUM:
            return JAVA_TYPE_ENUM;
        case FieldDescriptor::TYPE_SFIXED32:
            return JAVA_TYPE_INT;
        case FieldDescriptor::TYPE_SFIXED64:
            return JAVA_TYPE_LONG;
        case FieldDescriptor::TYPE_SINT32:
            return JAVA_TYPE_INT;
        case FieldDescriptor::TYPE_SINT64:
            return JAVA_TYPE_LONG;
        default:
            return JAVA_TYPE_UNKNOWN;
    }
}

/**
 * Gather the info about the atoms.
 */
int
collate_atoms(const Descriptor* descriptor, Atoms* atoms)
{
    int errorCount = 0;
    const bool dbg = false;

    for (int i=0; i<descriptor->field_count(); i++) {
        const FieldDescriptor* atomField = descriptor->field(i);

        if (dbg) {
            printf("   %s (%d)\n", atomField->name().c_str(), atomField->number());
        }

        // StatsEvent only has one oneof, which contains only messages. Don't allow other types.
        if (atomField->type() != FieldDescriptor::TYPE_MESSAGE) {
            print_error(atomField,
                    "Bad type for atom. StatsEvent can only have message type fields: %s\n",
                    atomField->name().c_str());
            errorCount++;
            continue;
        }

        const Descriptor* atom = atomField->message_type();

        // Build a sorted list of the fields. Descriptor has them in source file order.
        map<int,const FieldDescriptor*> fields;
        for (int j=0; j<atom->field_count(); j++) {
            const FieldDescriptor* field = atom->field(j);
            fields[field->number()] = field;
        }

        // Check that the parameters start at 1 and go up sequentially.
        int expectedNumber = 1;
        for (map<int,const FieldDescriptor*>::const_iterator it = fields.begin();
                it != fields.end(); it++) {
            const int number = it->first;
            const FieldDescriptor* field = it->second;
            if (number != expectedNumber) {
                print_error(field, "Fields must be numbered consecutively starting at 1:"
                        " '%s' is %d but should be %d\n",
                        field->name().c_str(), number, expectedNumber);
                errorCount++;
                expectedNumber = number;
                continue;
            }
            expectedNumber++;
        }

        // Check that only allowed types are present. Remove any invalid ones.
        for (map<int,const FieldDescriptor*>::const_iterator it = fields.begin();
                it != fields.end(); it++) {
            const FieldDescriptor* field = it->second;

            java_type_t javaType = java_type(field);

            if (javaType == JAVA_TYPE_UNKNOWN) {
                print_error(field, "Unkown type for field: %s\n", field->name().c_str());
                errorCount++;
                continue;
            } else if (javaType == JAVA_TYPE_OBJECT) {
                // Allow WorkSources, but only at position 1.
                print_error(field, "Message type not allowed for field: %s\n",
                        field->name().c_str());
                errorCount++;
                continue;
            } else if (javaType == JAVA_TYPE_BYTE_ARRAY) {
                print_error(field, "Raw bytes type not allowed for field: %s\n",
                        field->name().c_str());
                errorCount++;
                continue;
            }
        }

        // Check that if there's a WorkSource, it's at position 1.
        for (map<int,const FieldDescriptor*>::const_iterator it = fields.begin();
                it != fields.end(); it++) {
            int number = it->first;
            if (number != 1) {
                const FieldDescriptor* field = it->second;
                java_type_t javaType = java_type(field);
                if (javaType == JAVA_TYPE_WORK_SOURCE) {
                    print_error(field, "WorkSource fields must have field id 1, in message: '%s'\n",
                           atom->name().c_str());
                    errorCount++;
                }
            }
        }

        AtomDecl atomDecl(atomField->number(), atomField->name(), atom->name());

        // Build the type signature and the atom data.
        vector<java_type_t> signature;
        for (map<int,const FieldDescriptor*>::const_iterator it = fields.begin();
                it != fields.end(); it++) {
            const FieldDescriptor* field = it->second;
            java_type_t javaType = java_type(field);

            AtomField atField(field->name(), javaType);
            if (javaType == JAVA_TYPE_ENUM) {
                // All enums are treated as ints when it comes to function signatures.
                signature.push_back(JAVA_TYPE_INT);
                const EnumDescriptor* enumDescriptor = field->enum_type();
                for (int i = 0; i < enumDescriptor->value_count(); i++) {
                    atField.enumValues[enumDescriptor->value(i)->number()] =
                        enumDescriptor->value(i)->name().c_str();
                }
            } else {
                signature.push_back(javaType);
            }
            atomDecl.fields.push_back(atField);
        }

        atoms->signatures.insert(signature);
        atoms->decls.insert(atomDecl);
    }

    if (dbg) {
        printf("signatures = [\n");
        for (set<vector<java_type_t>>::const_iterator it = atoms->signatures.begin();
                it != atoms->signatures.end(); it++) {
            printf("   ");
            for (vector<java_type_t>::const_iterator jt = it->begin(); jt != it->end(); jt++) {
                printf(" %d", (int)*jt);
            }
            printf("\n");
        }
        printf("]\n");
    }

    return errorCount;
}

}  // namespace stats_log_api_gen
}  // namespace android
