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

#include <gtest/gtest.h>
#include <stdio.h>

#include "Collation.h"
#include "frameworks/base/tools/stats_log_api_gen/test.pb.h"

namespace android {
namespace stats_log_api_gen {

using std::map;
using std::set;
using std::vector;

/**
 * Return whether the map contains a vector of the elements provided.
 */
static bool map_contains_vector(const map<vector<java_type_t>, FieldNumberToAnnotations>& s,
                                int count, ...) {
    va_list args;
    vector<java_type_t> v;

    va_start(args, count);
    for (int i = 0; i < count; i++) {
        v.push_back((java_type_t)va_arg(args, int));
    }
    va_end(args);

    return s.find(v) != s.end();
}

/**
 * Expect that the provided map contains the elements provided.
 */
#define EXPECT_MAP_CONTAINS_SIGNATURE(s, ...)                    \
    do {                                                         \
        int count = sizeof((int[]){__VA_ARGS__}) / sizeof(int);  \
        EXPECT_TRUE(map_contains_vector(s, count, __VA_ARGS__)); \
    } while (0)

/** Expects that the provided atom has no enum values for any field. */
#define EXPECT_NO_ENUM_FIELD(atom)                                           \
    do {                                                                     \
        for (vector<AtomField>::const_iterator field = atom->fields.begin(); \
             field != atom->fields.end(); field++) {                         \
            EXPECT_TRUE(field->enumValues.empty());                          \
        }                                                                    \
    } while (0)

/** Expects that exactly one specific field has expected enum values. */
#define EXPECT_HAS_ENUM_FIELD(atom, field_name, values)                      \
    do {                                                                     \
        for (vector<AtomField>::const_iterator field = atom->fields.begin(); \
             field != atom->fields.end(); field++) {                         \
            if (field->name == field_name) {                                 \
                EXPECT_EQ(field->enumValues, values);                        \
            } else {                                                         \
                EXPECT_TRUE(field->enumValues.empty());                      \
            }                                                                \
        }                                                                    \
    } while (0)

/**
 * Test a correct collation, with all the types.
 */
TEST(CollationTest, CollateStats) {
    Atoms atoms;
    int errorCount = collate_atoms(Event::descriptor(), DEFAULT_MODULE_NAME, &atoms);

    EXPECT_EQ(0, errorCount);
    EXPECT_EQ(3ul, atoms.signatureInfoMap.size());

    // IntAtom, AnotherIntAtom
    EXPECT_MAP_CONTAINS_SIGNATURE(atoms.signatureInfoMap, JAVA_TYPE_INT);

    // OutOfOrderAtom
    EXPECT_MAP_CONTAINS_SIGNATURE(atoms.signatureInfoMap, JAVA_TYPE_INT, JAVA_TYPE_INT);

    // AllTypesAtom
    EXPECT_MAP_CONTAINS_SIGNATURE(atoms.signatureInfoMap,
                                  JAVA_TYPE_ATTRIBUTION_CHAIN,  // AttributionChain
                                  JAVA_TYPE_FLOAT,              // float
                                  JAVA_TYPE_LONG,               // int64
                                  JAVA_TYPE_LONG,               // uint64
                                  JAVA_TYPE_INT,                // int32
                                  JAVA_TYPE_LONG,               // fixed64
                                  JAVA_TYPE_INT,                // fixed32
                                  JAVA_TYPE_BOOLEAN,            // bool
                                  JAVA_TYPE_STRING,             // string
                                  JAVA_TYPE_INT,                // uint32
                                  JAVA_TYPE_INT,                // AnEnum
                                  JAVA_TYPE_INT,                // sfixed32
                                  JAVA_TYPE_LONG,               // sfixed64
                                  JAVA_TYPE_INT,                // sint32
                                  JAVA_TYPE_LONG                // sint64
    );

    set<AtomDecl>::const_iterator atom = atoms.decls.begin();
    EXPECT_EQ(1, atom->code);
    EXPECT_EQ("int_atom", atom->name);
    EXPECT_EQ("IntAtom", atom->message);
    EXPECT_NO_ENUM_FIELD(atom);
    atom++;

    EXPECT_EQ(2, atom->code);
    EXPECT_EQ("out_of_order_atom", atom->name);
    EXPECT_EQ("OutOfOrderAtom", atom->message);
    EXPECT_NO_ENUM_FIELD(atom);
    atom++;

    EXPECT_EQ(3, atom->code);
    EXPECT_EQ("another_int_atom", atom->name);
    EXPECT_EQ("AnotherIntAtom", atom->message);
    EXPECT_NO_ENUM_FIELD(atom);
    atom++;

    EXPECT_EQ(4, atom->code);
    EXPECT_EQ("all_types_atom", atom->name);
    EXPECT_EQ("AllTypesAtom", atom->message);
    map<int, string> enumValues;
    enumValues[0] = "VALUE0";
    enumValues[1] = "VALUE1";
    EXPECT_HAS_ENUM_FIELD(atom, "enum_field", enumValues);
    atom++;

    EXPECT_TRUE(atom == atoms.decls.end());
}

/**
 * Test that event class that contains stuff other than the atoms is rejected.
 */
TEST(CollationTest, NonMessageTypeFails) {
    Atoms atoms;
    int errorCount = collate_atoms(IntAtom::descriptor(), DEFAULT_MODULE_NAME, &atoms);

    EXPECT_EQ(1, errorCount);
}

/**
 * Test that atoms that have non-primitive types or repeated fields are
 * rejected.
 */
TEST(CollationTest, FailOnBadTypes) {
    Atoms atoms;
    int errorCount = collate_atoms(BadTypesEvent::descriptor(), DEFAULT_MODULE_NAME, &atoms);

    EXPECT_EQ(4, errorCount);
}

/**
 * Test that atoms that skip field numbers (in the first position) are rejected.
 */
TEST(CollationTest, FailOnSkippedFieldsSingle) {
    Atoms atoms;
    int errorCount =
            collate_atoms(BadSkippedFieldSingle::descriptor(), DEFAULT_MODULE_NAME, &atoms);

    EXPECT_EQ(1, errorCount);
}

/**
 * Test that atoms that skip field numbers (not in the first position, and
 * multiple times) are rejected.
 */
TEST(CollationTest, FailOnSkippedFieldsMultiple) {
    Atoms atoms;
    int errorCount =
            collate_atoms(BadSkippedFieldMultiple::descriptor(), DEFAULT_MODULE_NAME, &atoms);

    EXPECT_EQ(2, errorCount);
}

/**
 * Test that atoms that have an attribution chain not in the first position are
 * rejected.
 */
TEST(CollationTest, FailBadAttributionNodePosition) {
    Atoms atoms;
    int errorCount =
            collate_atoms(BadAttributionNodePosition::descriptor(), DEFAULT_MODULE_NAME, &atoms);

    EXPECT_EQ(1, errorCount);
}

TEST(CollationTest, FailOnBadStateAtomOptions) {
    Atoms atoms;
    int errorCount = collate_atoms(BadStateAtoms::descriptor(), DEFAULT_MODULE_NAME, &atoms);

    EXPECT_EQ(3, errorCount);
}

TEST(CollationTest, PassOnGoodStateAtomOptions) {
    Atoms atoms;
    int errorCount = collate_atoms(GoodStateAtoms::descriptor(), DEFAULT_MODULE_NAME, &atoms);
    EXPECT_EQ(0, errorCount);
}

TEST(CollationTest, PassOnGoodBinaryFieldAtom) {
    Atoms atoms;
    int errorCount =
            collate_atoms(GoodEventWithBinaryFieldAtom::descriptor(), DEFAULT_MODULE_NAME, &atoms);
    EXPECT_EQ(0, errorCount);
}

TEST(CollationTest, FailOnBadBinaryFieldAtom) {
    Atoms atoms;
    int errorCount =
            collate_atoms(BadEventWithBinaryFieldAtom::descriptor(), DEFAULT_MODULE_NAME, &atoms);
    EXPECT_TRUE(errorCount > 0);
}

TEST(CollationTest, PassOnWhitelistedAtom) {
    Atoms atoms;
    int errorCount = collate_atoms(ListedAtoms::descriptor(), DEFAULT_MODULE_NAME, &atoms);
    EXPECT_EQ(errorCount, 0);
    EXPECT_EQ(atoms.decls.size(), 2ul);
}

TEST(CollationTest, RecogniseWhitelistedAtom) {
    Atoms atoms;
    collate_atoms(ListedAtoms::descriptor(), DEFAULT_MODULE_NAME, &atoms);
    for (const auto& atomDecl : atoms.decls) {
        if (atomDecl.code == 1) {
            EXPECT_TRUE(atomDecl.whitelisted);
        } else {
            EXPECT_FALSE(atomDecl.whitelisted);
        }
    }
}

TEST(CollationTest, PassOnLogFromModuleAtom) {
    Atoms atoms;
    int errorCount = collate_atoms(ModuleAtoms::descriptor(), DEFAULT_MODULE_NAME, &atoms);
    EXPECT_EQ(errorCount, 0);
    EXPECT_EQ(atoms.decls.size(), 4ul);
}

TEST(CollationTest, RecognizeModuleAtom) {
    Atoms atoms;
    int errorCount = collate_atoms(ModuleAtoms::descriptor(), DEFAULT_MODULE_NAME, &atoms);
    EXPECT_EQ(errorCount, 0);
    EXPECT_EQ(atoms.decls.size(), 4ul);
    EXPECT_EQ(atoms.signatureInfoMap.size(), 2u);
    EXPECT_MAP_CONTAINS_SIGNATURE(atoms.signatureInfoMap, JAVA_TYPE_INT);
    EXPECT_MAP_CONTAINS_SIGNATURE(atoms.signatureInfoMap, JAVA_TYPE_STRING);
    for (auto signatureInfoMapIt : atoms.signatureInfoMap) {
        vector<java_type_t> signature = signatureInfoMapIt.first;
        const FieldNumberToAnnotations& fieldNumberToAnnotations = signatureInfoMapIt.second;
        if (signature[0] == JAVA_TYPE_STRING) {
            EXPECT_EQ(0u, fieldNumberToAnnotations.size());
        } else if (signature[0] == JAVA_TYPE_INT) {
            EXPECT_EQ(1u, fieldNumberToAnnotations.size());
            EXPECT_NE(fieldNumberToAnnotations.end(), fieldNumberToAnnotations.find(1));
            const set<shared_ptr<Annotation>>& annotations = fieldNumberToAnnotations.at(1);
            EXPECT_EQ(2u, annotations.size());
            for (const shared_ptr<Annotation> annotation : annotations) {
                EXPECT_TRUE(annotation->annotationId == ANNOTATION_ID_IS_UID ||
                            annotation->annotationId == ANNOTATION_ID_STATE_OPTION);
                if (ANNOTATION_ID_IS_UID == annotation->annotationId) {
                    EXPECT_EQ(1, annotation->atomId);
                    EXPECT_EQ(ANNOTATION_TYPE_BOOL, annotation->type);
                    EXPECT_TRUE(annotation->value.boolValue);
                }

                if (ANNOTATION_ID_STATE_OPTION == annotation->annotationId) {
                    EXPECT_EQ(3, annotation->atomId);
                    EXPECT_EQ(ANNOTATION_TYPE_INT, annotation->type);
                    EXPECT_EQ(os::statsd::StateField::EXCLUSIVE_STATE, annotation->value.intValue);
                }
            }
        }
    }
}

TEST(CollationTest, RecognizeModule1Atom) {
    Atoms atoms;
    const string moduleName = "module1";
    int errorCount = collate_atoms(ModuleAtoms::descriptor(), moduleName, &atoms);
    EXPECT_EQ(errorCount, 0);
    EXPECT_EQ(atoms.decls.size(), 2ul);
    EXPECT_EQ(atoms.signatureInfoMap.size(), 1u);
    EXPECT_MAP_CONTAINS_SIGNATURE(atoms.signatureInfoMap, JAVA_TYPE_INT);
    for (auto signatureInfoMapIt : atoms.signatureInfoMap) {
        vector<java_type_t> signature = signatureInfoMapIt.first;
        const FieldNumberToAnnotations& fieldNumberToAnnotations = signatureInfoMapIt.second;
        EXPECT_EQ(JAVA_TYPE_INT, signature[0]);
        EXPECT_EQ(1u, fieldNumberToAnnotations.size());
        int fieldNumber = 1;
        EXPECT_NE(fieldNumberToAnnotations.end(), fieldNumberToAnnotations.find(fieldNumber));
        const set<shared_ptr<Annotation>>& annotations = fieldNumberToAnnotations.at(fieldNumber);
        EXPECT_EQ(2u, annotations.size());
        for (const shared_ptr<Annotation> annotation : annotations) {
            EXPECT_TRUE(annotation->annotationId == ANNOTATION_ID_IS_UID ||
                        annotation->annotationId == ANNOTATION_ID_STATE_OPTION);
            if (ANNOTATION_ID_IS_UID == annotation->annotationId) {
                EXPECT_EQ(1, annotation->atomId);
                EXPECT_EQ(ANNOTATION_TYPE_BOOL, annotation->type);
                EXPECT_TRUE(annotation->value.boolValue);
            }

            if (ANNOTATION_ID_STATE_OPTION == annotation->annotationId) {
                EXPECT_EQ(3, annotation->atomId);
                EXPECT_EQ(ANNOTATION_TYPE_INT, annotation->type);
                EXPECT_EQ(os::statsd::StateField::EXCLUSIVE_STATE, annotation->value.intValue);
            }
        }
    }
}

}  // namespace stats_log_api_gen
}  // namespace android
