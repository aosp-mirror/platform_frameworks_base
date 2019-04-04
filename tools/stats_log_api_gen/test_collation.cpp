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

#include "frameworks/base/tools/stats_log_api_gen/test.pb.h"
#include "Collation.h"

#include <stdio.h>

namespace android {
namespace stats_log_api_gen {

using std::map;
using std::set;
using std::vector;

/**
 * Return whether the set contains a vector of the elements provided.
 */
static bool
set_contains_vector(const map<vector<java_type_t>, set<string>>& s, int count, ...)
{
    va_list args;
    vector<java_type_t> v;

    va_start(args, count);
    for (int i=0; i<count; i++) {
        v.push_back((java_type_t)va_arg(args, int));
    }
    va_end(args);

    return s.find(v) != s.end();
}

/**
 * Expect that the provided set contains the elements provided.
 */
#define EXPECT_SET_CONTAINS_SIGNATURE(s, ...) \
    do { \
        int count = sizeof((int[]){__VA_ARGS__})/sizeof(int); \
        EXPECT_TRUE(set_contains_vector(s, count, __VA_ARGS__)); \
    } while(0)

/** Expects that the provided atom has no enum values for any field. */
#define EXPECT_NO_ENUM_FIELD(atom) \
    do { \
        for (vector<AtomField>::const_iterator field = atom->fields.begin(); \
             field != atom->fields.end(); field++) { \
            EXPECT_TRUE(field->enumValues.empty()); \
        } \
    } while(0)

/** Expects that exactly one specific field has expected enum values. */
#define EXPECT_HAS_ENUM_FIELD(atom, field_name, values)        \
    do { \
        for (vector<AtomField>::const_iterator field = atom->fields.begin(); \
             field != atom->fields.end(); field++) { \
            if (field->name == field_name) { \
                EXPECT_EQ(field->enumValues, values); \
            } else { \
                EXPECT_TRUE(field->enumValues.empty()); \
            } \
        } \
    } while(0)


/**
 * Test a correct collation, with all the types.
 */
TEST(CollationTest, CollateStats) {
    Atoms atoms;
    int errorCount = collate_atoms(Event::descriptor(), &atoms);

    EXPECT_EQ(0, errorCount);
    EXPECT_EQ(3ul, atoms.signatures_to_modules.size());

    // IntAtom, AnotherIntAtom
    EXPECT_SET_CONTAINS_SIGNATURE(atoms.signatures_to_modules, JAVA_TYPE_INT);

    // OutOfOrderAtom
    EXPECT_SET_CONTAINS_SIGNATURE(atoms.signatures_to_modules, JAVA_TYPE_INT, JAVA_TYPE_INT);

    // AllTypesAtom
    EXPECT_SET_CONTAINS_SIGNATURE(
        atoms.signatures_to_modules,
        JAVA_TYPE_ATTRIBUTION_CHAIN, // AttributionChain
        JAVA_TYPE_DOUBLE,            // double
        JAVA_TYPE_FLOAT,             // float
        JAVA_TYPE_LONG,              // int64
        JAVA_TYPE_LONG,              // uint64
        JAVA_TYPE_INT,               // int32
        JAVA_TYPE_LONG,              // fixed64
        JAVA_TYPE_INT,               // fixed32
        JAVA_TYPE_BOOLEAN,           // bool
        JAVA_TYPE_STRING,            // string
        JAVA_TYPE_INT,               // uint32
        JAVA_TYPE_INT,               // AnEnum
        JAVA_TYPE_INT,               // sfixed32
        JAVA_TYPE_LONG,              // sfixed64
        JAVA_TYPE_INT,               // sint32
        JAVA_TYPE_LONG               // sint64
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
    int errorCount = collate_atoms(IntAtom::descriptor(), &atoms);

    EXPECT_EQ(1, errorCount);
}

/**
 * Test that atoms that have non-primitive types are rejected.
 */
TEST(CollationTest, FailOnBadTypes) {
    Atoms atoms;
    int errorCount = collate_atoms(BadTypesEvent::descriptor(), &atoms);

    EXPECT_EQ(2, errorCount);
}

/**
 * Test that atoms that skip field numbers (in the first position) are rejected.
 */
TEST(CollationTest, FailOnSkippedFieldsSingle) {
    Atoms atoms;
    int errorCount = collate_atoms(BadSkippedFieldSingle::descriptor(), &atoms);

    EXPECT_EQ(1, errorCount);
}

/**
 * Test that atoms that skip field numbers (not in the first position, and multiple
 * times) are rejected.
 */
TEST(CollationTest, FailOnSkippedFieldsMultiple) {
    Atoms atoms;
    int errorCount = collate_atoms(BadSkippedFieldMultiple::descriptor(), &atoms);

    EXPECT_EQ(2, errorCount);
}

/**
 * Test that atoms that have an attribution chain not in the first position are
 * rejected.
 */
TEST(CollationTest, FailBadAttributionNodePosition) {
  Atoms atoms;
  int errorCount =
      collate_atoms(BadAttributionNodePosition::descriptor(), &atoms);

  EXPECT_EQ(1, errorCount);
}

TEST(CollationTest, FailOnBadStateAtomOptions) {
    Atoms atoms;
    int errorCount = collate_atoms(BadStateAtoms::descriptor(), &atoms);

    EXPECT_EQ(3, errorCount);
}

TEST(CollationTest, PassOnGoodStateAtomOptions) {
    Atoms atoms;
    int errorCount = collate_atoms(GoodStateAtoms::descriptor(), &atoms);
    EXPECT_EQ(0, errorCount);
}

TEST(CollationTest, PassOnGoodBinaryFieldAtom) {
    Atoms atoms;
    int errorCount =
            collate_atoms(GoodEventWithBinaryFieldAtom::descriptor(), &atoms);
    EXPECT_EQ(0, errorCount);
}

TEST(CollationTest, FailOnBadBinaryFieldAtom) {
    Atoms atoms;
    int errorCount =
            collate_atoms(BadEventWithBinaryFieldAtom::descriptor(), &atoms);
    EXPECT_TRUE(errorCount > 0);
}

TEST(CollationTest, PassOnWhitelistedAtom) {
    Atoms atoms;
    int errorCount = collate_atoms(ListedAtoms::descriptor(), &atoms);
    EXPECT_EQ(errorCount, 0);
    EXPECT_EQ(atoms.decls.size(), 2ul);
}

TEST(CollationTest, RecogniseWhitelistedAtom) {
    Atoms atoms;
    collate_atoms(ListedAtoms::descriptor(), &atoms);
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
    int errorCount = collate_atoms(ModuleAtoms::descriptor(), &atoms);
    EXPECT_EQ(errorCount, 0);
    EXPECT_EQ(atoms.decls.size(), 3ul);
}

TEST(CollationTest, RecognizeModuleAtom) {
    Atoms atoms;
    int errorCount = collate_atoms(ModuleAtoms::descriptor(), &atoms);
    EXPECT_EQ(errorCount, 0);
    EXPECT_EQ(atoms.decls.size(), 3ul);
    for (const auto& atomDecl: atoms.decls) {
        if (atomDecl.code == 1) {
            EXPECT_TRUE(atomDecl.hasModule);
            EXPECT_EQ(atomDecl.moduleName, "module1");
        } else if (atomDecl.code == 2) {
            EXPECT_TRUE(atomDecl.hasModule);
            EXPECT_EQ(atomDecl.moduleName, "module2");
        } else {
            EXPECT_FALSE(atomDecl.hasModule);
        }
    }

    EXPECT_EQ(atoms.signatures_to_modules.size(), 2u);
    EXPECT_SET_CONTAINS_SIGNATURE(atoms.signatures_to_modules, JAVA_TYPE_INT);
    EXPECT_SET_CONTAINS_SIGNATURE(atoms.signatures_to_modules, JAVA_TYPE_STRING);
    for (auto signature_to_modules_it : atoms.signatures_to_modules) {
        vector<java_type_t> signature = signature_to_modules_it.first;
        if (signature[0] == JAVA_TYPE_STRING) {
            EXPECT_EQ(signature_to_modules_it.second.size(), 0u);
        } else if (signature[0] == JAVA_TYPE_INT) {
            set<string> modules = signature_to_modules_it.second;
            EXPECT_EQ(modules.size(), 2u);
            // Assert that the set contains "module1" and "module2".
            EXPECT_NE(modules.find("module1"), modules.end());
            EXPECT_NE(modules.find("module2"), modules.end());
        }
    }
}

}  // namespace stats_log_api_gen
}  // namespace android