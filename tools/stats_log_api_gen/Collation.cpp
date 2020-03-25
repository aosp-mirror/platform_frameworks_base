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

#include "frameworks/base/cmds/statsd/src/atoms.pb.h"

namespace android {
namespace stats_log_api_gen {

using google::protobuf::EnumDescriptor;
using google::protobuf::FieldDescriptor;
using google::protobuf::FileDescriptor;
using google::protobuf::SourceLocation;
using std::make_shared;
using std::map;

const bool dbg = false;

//
// AtomDecl class
//

AtomDecl::AtomDecl() : code(0), name() {
}

AtomDecl::AtomDecl(const AtomDecl& that)
    : code(that.code),
      name(that.name),
      message(that.message),
      fields(that.fields),
      fieldNumberToAnnotations(that.fieldNumberToAnnotations),
      primaryFields(that.primaryFields),
      exclusiveField(that.exclusiveField),
      defaultState(that.defaultState),
      resetState(that.resetState),
      nested(that.nested),
      uidField(that.uidField),
      whitelisted(that.whitelisted),
      truncateTimestamp(that.truncateTimestamp) {
}

AtomDecl::AtomDecl(int c, const string& n, const string& m) : code(c), name(n), message(m) {
}

AtomDecl::~AtomDecl() {
}

/**
 * Print an error message for a FieldDescriptor, including the file name and
 * line number.
 */
static void print_error(const FieldDescriptor* field, const char* format, ...) {
    const Descriptor* message = field->containing_type();
    const FileDescriptor* file = message->file();

    SourceLocation loc;
    if (field->GetSourceLocation(&loc)) {
        // TODO: this will work if we can figure out how to pass
        // --include_source_info to protoc
        fprintf(stderr, "%s:%d: ", file->name().c_str(), loc.start_line);
    } else {
        fprintf(stderr, "%s: ", file->name().c_str());
    }
    va_list args;
    va_start(args, format);
    vfprintf(stderr, format, args);
    va_end(args);
}

/**
 * Convert a protobuf type into a java type.
 */
static java_type_t java_type(const FieldDescriptor* field) {
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
            if (field->message_type()->full_name() == "android.os.statsd.AttributionNode") {
                return JAVA_TYPE_ATTRIBUTION_CHAIN;
            } else if (field->message_type()->full_name() == "android.os.statsd.KeyValuePair") {
                return JAVA_TYPE_KEY_VALUE_PAIR;
            } else if (field->options().GetExtension(os::statsd::log_mode) ==
                       os::statsd::LogMode::MODE_BYTES) {
                return JAVA_TYPE_BYTE_ARRAY;
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
 * Gather the enums info.
 */
void collate_enums(const EnumDescriptor& enumDescriptor, AtomField* atomField) {
    for (int i = 0; i < enumDescriptor.value_count(); i++) {
        atomField->enumValues[enumDescriptor.value(i)->number()] =
                enumDescriptor.value(i)->name().c_str();
    }
}

static void addAnnotationToAtomDecl(AtomDecl* atomDecl, const int fieldNumber,
                                    const int annotationId, const AnnotationType annotationType,
                                    const AnnotationValue annotationValue) {
    if (dbg) {
        printf("   Adding annotation to %s: [%d] = {id: %d, type: %d}\n", atomDecl->name.c_str(),
               fieldNumber, annotationId, annotationType);
    }
    atomDecl->fieldNumberToAnnotations[fieldNumber].insert(
            make_shared<Annotation>(annotationId, atomDecl->code, annotationType, annotationValue));
}

static int collate_field_annotations(AtomDecl* atomDecl, const FieldDescriptor* field,
                                     const int fieldNumber, const java_type_t& javaType) {
    int errorCount = 0;

    if (field->options().HasExtension(os::statsd::state_field_option)) {
        const int option = field->options().GetExtension(os::statsd::state_field_option).option();
        if (option != STATE_OPTION_UNSET) {
            addAnnotationToAtomDecl(atomDecl, fieldNumber, ANNOTATION_ID_STATE_OPTION,
                                    ANNOTATION_TYPE_INT, AnnotationValue(option));
        }

        if (option == STATE_OPTION_PRIMARY) {
            if (javaType == JAVA_TYPE_UNKNOWN || javaType == JAVA_TYPE_ATTRIBUTION_CHAIN ||
                javaType == JAVA_TYPE_OBJECT || javaType == JAVA_TYPE_BYTE_ARRAY) {
                print_error(field, "Invalid primary state field: '%s'\n",
                            atomDecl->message.c_str());
                errorCount++;
            }
            atomDecl->primaryFields.push_back(fieldNumber);
        }

        if (option == STATE_OPTION_PRIMARY_FIELD_FIRST_UID) {
            if (javaType != JAVA_TYPE_ATTRIBUTION_CHAIN) {
                print_error(field,
                            "PRIMARY_FIELD_FIRST_UID annotation is only for AttributionChains: "
                            "'%s'\n",
                            atomDecl->message.c_str());
                errorCount++;
            } else {
                atomDecl->primaryFields.push_back(FIRST_UID_IN_CHAIN_ID);
            }
        }

        if (option == STATE_OPTION_EXCLUSIVE) {
            if (javaType == JAVA_TYPE_UNKNOWN || javaType == JAVA_TYPE_ATTRIBUTION_CHAIN ||
                javaType == JAVA_TYPE_OBJECT || javaType == JAVA_TYPE_BYTE_ARRAY) {
                print_error(field, "Invalid exclusive state field: '%s'\n",
                            atomDecl->message.c_str());
                errorCount++;
            }

            if (atomDecl->exclusiveField == 0) {
                atomDecl->exclusiveField = fieldNumber;
            } else {
                print_error(field,
                            "Cannot have more than one exclusive state field in an "
                            "atom: '%s'\n",
                            atomDecl->message.c_str());
                errorCount++;
            }

            if (field->options()
                        .GetExtension(os::statsd::state_field_option)
                        .has_default_state_value()) {
                const int defaultState = field->options()
                                                 .GetExtension(os::statsd::state_field_option)
                                                 .default_state_value();
                atomDecl->defaultState = defaultState;

                addAnnotationToAtomDecl(atomDecl, fieldNumber, ANNOTATION_ID_DEFAULT_STATE,
                                        ANNOTATION_TYPE_INT, AnnotationValue(defaultState));
            }

            if (field->options()
                        .GetExtension(os::statsd::state_field_option)
                        .has_reset_state_value()) {
                const int resetState = field->options()
                                               .GetExtension(os::statsd::state_field_option)
                                               .reset_state_value();

                atomDecl->resetState = resetState;
                addAnnotationToAtomDecl(atomDecl, fieldNumber, ANNOTATION_ID_RESET_STATE,
                                        ANNOTATION_TYPE_INT, AnnotationValue(resetState));
            }

            if (field->options().GetExtension(os::statsd::state_field_option).has_nested()) {
                const bool nested =
                        field->options().GetExtension(os::statsd::state_field_option).nested();
                atomDecl->nested = nested;

                addAnnotationToAtomDecl(atomDecl, fieldNumber, ANNOTATION_ID_STATE_NESTED,
                                        ANNOTATION_TYPE_BOOL, AnnotationValue(nested));
            }
        }
    }

    if (field->options().GetExtension(os::statsd::is_uid) == true) {
        if (javaType != JAVA_TYPE_INT) {
            print_error(field, "is_uid annotation can only be applied to int32 fields: '%s'\n",
                        atomDecl->message.c_str());
            errorCount++;
        }

        if (atomDecl->uidField == 0) {
            atomDecl->uidField = fieldNumber;

            addAnnotationToAtomDecl(atomDecl, fieldNumber, ANNOTATION_ID_IS_UID,
                                    ANNOTATION_TYPE_BOOL, AnnotationValue(true));
        } else {
            print_error(field,
                        "Cannot have more than one field in an atom with is_uid "
                        "annotation: '%s'\n",
                        atomDecl->message.c_str());
            errorCount++;
        }
    }

    return errorCount;
}

/**
 * Gather the info about an atom proto.
 */
int collate_atom(const Descriptor* atom, AtomDecl* atomDecl, vector<java_type_t>* signature) {
    int errorCount = 0;

    // Build a sorted list of the fields. Descriptor has them in source file
    // order.
    map<int, const FieldDescriptor*> fields;
    for (int j = 0; j < atom->field_count(); j++) {
        const FieldDescriptor* field = atom->field(j);
        fields[field->number()] = field;
    }

    // Check that the parameters start at 1 and go up sequentially.
    int expectedNumber = 1;
    for (map<int, const FieldDescriptor*>::const_iterator it = fields.begin(); it != fields.end();
         it++) {
        const int number = it->first;
        const FieldDescriptor* field = it->second;
        if (number != expectedNumber) {
            print_error(field,
                        "Fields must be numbered consecutively starting at 1:"
                        " '%s' is %d but should be %d\n",
                        field->name().c_str(), number, expectedNumber);
            errorCount++;
            expectedNumber = number;
            continue;
        }
        expectedNumber++;
    }

    // Check that only allowed types are present. Remove any invalid ones.
    for (map<int, const FieldDescriptor*>::const_iterator it = fields.begin(); it != fields.end();
         it++) {
        const FieldDescriptor* field = it->second;
        bool isBinaryField = field->options().GetExtension(os::statsd::log_mode) ==
                             os::statsd::LogMode::MODE_BYTES;

        java_type_t javaType = java_type(field);

        if (javaType == JAVA_TYPE_UNKNOWN) {
            print_error(field, "Unknown type for field: %s\n", field->name().c_str());
            errorCount++;
            continue;
        } else if (javaType == JAVA_TYPE_OBJECT && atomDecl->code < PULL_ATOM_START_ID) {
            // Allow attribution chain, but only at position 1.
            print_error(field, "Message type not allowed for field in pushed atoms: %s\n",
                        field->name().c_str());
            errorCount++;
            continue;
        } else if (javaType == JAVA_TYPE_BYTE_ARRAY && !isBinaryField) {
            print_error(field, "Raw bytes type not allowed for field: %s\n", field->name().c_str());
            errorCount++;
            continue;
        }

        if (isBinaryField && javaType != JAVA_TYPE_BYTE_ARRAY) {
            print_error(field, "Cannot mark field %s as bytes.\n", field->name().c_str());
            errorCount++;
            continue;
        }

        // Doubles are not supported yet.
        if (javaType == JAVA_TYPE_DOUBLE) {
            print_error(field,
                        "Doubles are not supported in atoms. Please change field %s "
                        "to float\n",
                        field->name().c_str());
            errorCount++;
            continue;
        }

        if (field->is_repeated() &&
            !(javaType == JAVA_TYPE_ATTRIBUTION_CHAIN || javaType == JAVA_TYPE_KEY_VALUE_PAIR)) {
            print_error(field,
                        "Repeated fields are not supported in atoms. Please make "
                        "field %s not "
                        "repeated.\n",
                        field->name().c_str());
            errorCount++;
            continue;
        }
    }

    // Check that if there's an attribution chain, it's at position 1.
    for (map<int, const FieldDescriptor*>::const_iterator it = fields.begin(); it != fields.end();
         it++) {
        int number = it->first;
        if (number != 1) {
            const FieldDescriptor* field = it->second;
            java_type_t javaType = java_type(field);
            if (javaType == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                print_error(field,
                            "AttributionChain fields must have field id 1, in message: '%s'\n",
                            atom->name().c_str());
                errorCount++;
            }
        }
    }

    // Build the type signature and the atom data.
    for (map<int, const FieldDescriptor*>::const_iterator it = fields.begin(); it != fields.end();
         it++) {
        const FieldDescriptor* field = it->second;
        java_type_t javaType = java_type(field);
        bool isBinaryField = field->options().GetExtension(os::statsd::log_mode) ==
                             os::statsd::LogMode::MODE_BYTES;

        AtomField atField(field->name(), javaType);

        if (javaType == JAVA_TYPE_ENUM) {
            // All enums are treated as ints when it comes to function signatures.
            collate_enums(*field->enum_type(), &atField);
        }

        // Generate signature for pushed atoms
        if (atomDecl->code < PULL_ATOM_START_ID) {
            if (javaType == JAVA_TYPE_ENUM) {
                // All enums are treated as ints when it comes to function signatures.
                signature->push_back(JAVA_TYPE_INT);
            } else if (javaType == JAVA_TYPE_OBJECT && isBinaryField) {
                signature->push_back(JAVA_TYPE_BYTE_ARRAY);
            } else {
                signature->push_back(javaType);
            }
        }

        atomDecl->fields.push_back(atField);

        errorCount += collate_field_annotations(atomDecl, field, it->first, javaType);
    }

    return errorCount;
}

// This function flattens the fields of the AttributionNode proto in an Atom
// proto and generates the corresponding atom decl and signature.
bool get_non_chained_node(const Descriptor* atom, AtomDecl* atomDecl,
                          vector<java_type_t>* signature) {
    // Build a sorted list of the fields. Descriptor has them in source file
    // order.
    map<int, const FieldDescriptor*> fields;
    for (int j = 0; j < atom->field_count(); j++) {
        const FieldDescriptor* field = atom->field(j);
        fields[field->number()] = field;
    }

    AtomDecl attributionDecl;
    vector<java_type_t> attributionSignature;
    collate_atom(android::os::statsd::AttributionNode::descriptor(), &attributionDecl,
                 &attributionSignature);

    // Build the type signature and the atom data.
    bool has_attribution_node = false;
    for (map<int, const FieldDescriptor*>::const_iterator it = fields.begin(); it != fields.end();
         it++) {
        const FieldDescriptor* field = it->second;
        java_type_t javaType = java_type(field);
        if (javaType == JAVA_TYPE_ATTRIBUTION_CHAIN) {
            atomDecl->fields.insert(atomDecl->fields.end(), attributionDecl.fields.begin(),
                                    attributionDecl.fields.end());
            signature->insert(signature->end(), attributionSignature.begin(),
                              attributionSignature.end());
            has_attribution_node = true;

        } else {
            AtomField atField(field->name(), javaType);
            if (javaType == JAVA_TYPE_ENUM) {
                // All enums are treated as ints when it comes to function signatures.
                signature->push_back(JAVA_TYPE_INT);
                collate_enums(*field->enum_type(), &atField);
            } else {
                signature->push_back(javaType);
            }
            atomDecl->fields.push_back(atField);
        }
    }
    return has_attribution_node;
}

static void populateFieldNumberToAnnotations(const AtomDecl& atomDecl,
                                             FieldNumberToAnnotations* fieldNumberToAnnotations) {
    for (FieldNumberToAnnotations::const_iterator it = atomDecl.fieldNumberToAnnotations.begin();
         it != atomDecl.fieldNumberToAnnotations.end(); it++) {
        const int fieldNumber = it->first;
        const set<shared_ptr<Annotation>>& insertAnnotationsSource = it->second;
        set<shared_ptr<Annotation>>& insertAnnotationsTarget =
                (*fieldNumberToAnnotations)[fieldNumber];
        insertAnnotationsTarget.insert(insertAnnotationsSource.begin(),
                                       insertAnnotationsSource.end());
    }
}

/**
 * Gather the info about the atoms.
 */
int collate_atoms(const Descriptor* descriptor, const string& moduleName, Atoms* atoms) {
    int errorCount = 0;

    int maxPushedAtomId = 2;
    for (int i = 0; i < descriptor->field_count(); i++) {
        const FieldDescriptor* atomField = descriptor->field(i);

        if (moduleName != DEFAULT_MODULE_NAME) {
            const int moduleCount = atomField->options().ExtensionSize(os::statsd::module);
            int j;
            for (j = 0; j < moduleCount; ++j) {
                const string atomModuleName =
                        atomField->options().GetExtension(os::statsd::module, j);
                if (atomModuleName == moduleName) {
                    break;
                }
            }

            // This atom is not in the module we're interested in; skip it.
            if (moduleCount == j) {
                if (dbg) {
                    printf("   Skipping %s (%d)\n", atomField->name().c_str(), atomField->number());
                }
                continue;
            }
        }

        if (dbg) {
            printf("   %s (%d)\n", atomField->name().c_str(), atomField->number());
        }

        // StatsEvent only has one oneof, which contains only messages. Don't allow
        // other types.
        if (atomField->type() != FieldDescriptor::TYPE_MESSAGE) {
            print_error(atomField,
                        "Bad type for atom. StatsEvent can only have message type "
                        "fields: %s\n",
                        atomField->name().c_str());
            errorCount++;
            continue;
        }

        const Descriptor* atom = atomField->message_type();
        AtomDecl atomDecl(atomField->number(), atomField->name(), atom->name());

        if (atomField->options().GetExtension(os::statsd::allow_from_any_uid) == true) {
            atomDecl.whitelisted = true;
            if (dbg) {
                printf("%s is whitelisted\n", atomField->name().c_str());
            }
        }

        if (atomDecl.code < PULL_ATOM_START_ID &&
            atomField->options().GetExtension(os::statsd::truncate_timestamp)) {
            addAnnotationToAtomDecl(&atomDecl, ATOM_ID_FIELD_NUMBER,
                                    ANNOTATION_ID_TRUNCATE_TIMESTAMP, ANNOTATION_TYPE_BOOL,
                                    AnnotationValue(true));
            if (dbg) {
                printf("%s can have timestamp truncated\n", atomField->name().c_str());
            }
        }

        vector<java_type_t> signature;
        errorCount += collate_atom(atom, &atomDecl, &signature);
        if (atomDecl.primaryFields.size() != 0 && atomDecl.exclusiveField == 0) {
            print_error(atomField, "Cannot have a primary field without an exclusive field: %s\n",
                        atomField->name().c_str());
            errorCount++;
            continue;
        }

        atoms->decls.insert(atomDecl);
        FieldNumberToAnnotations& fieldNumberToAnnotations = atoms->signatureInfoMap[signature];
        populateFieldNumberToAnnotations(atomDecl, &fieldNumberToAnnotations);

        AtomDecl nonChainedAtomDecl(atomField->number(), atomField->name(), atom->name());
        vector<java_type_t> nonChainedSignature;
        if (get_non_chained_node(atom, &nonChainedAtomDecl, &nonChainedSignature)) {
            atoms->non_chained_decls.insert(nonChainedAtomDecl);
            FieldNumberToAnnotations& fieldNumberToAnnotations =
                    atoms->nonChainedSignatureInfoMap[nonChainedSignature];
            populateFieldNumberToAnnotations(atomDecl, &fieldNumberToAnnotations);
        }

        if (atomDecl.code < PULL_ATOM_START_ID && atomDecl.code > maxPushedAtomId) {
            maxPushedAtomId = atomDecl.code;
        }
    }

    atoms->maxPushedAtomId = maxPushedAtomId;

    if (dbg) {
        printf("signatures = [\n");
        for (map<vector<java_type_t>, FieldNumberToAnnotations>::const_iterator it =
                     atoms->signatureInfoMap.begin();
             it != atoms->signatureInfoMap.end(); it++) {
            printf("   ");
            for (vector<java_type_t>::const_iterator jt = it->first.begin(); jt != it->first.end();
                 jt++) {
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
