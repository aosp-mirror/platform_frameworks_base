/*
 * Copyright (C) 2019, The Android Open Source Project
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

#include "atoms_info_writer.h"

#include <map>
#include <set>
#include <vector>

#include "utils.h"

namespace android {
namespace stats_log_api_gen {

static void write_atoms_info_header_body(FILE* out, const Atoms& atoms) {
    fprintf(out, "static int UNSET_VALUE = INT_MAX;\n");
    fprintf(out, "static int FIRST_UID_IN_CHAIN = 0;\n");

    fprintf(out, "struct StateAtomFieldOptions {\n");
    fprintf(out, "  std::vector<int> primaryFields;\n");
    fprintf(out, "  int exclusiveField;\n");
    fprintf(out, "  int defaultState = UNSET_VALUE;\n");
    fprintf(out, "  int resetState = UNSET_VALUE;\n");
    fprintf(out, "  bool nested;\n");
    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out, "struct AtomsInfo {\n");
    fprintf(out,
            "  const static std::set<int> "
            "kTruncatingTimestampAtomBlackList;\n");
    fprintf(out, "  const static std::map<int, int> kAtomsWithUidField;\n");
    fprintf(out, "  const static std::set<int> kAtomsWithAttributionChain;\n");
    fprintf(out,
            "  const static std::map<int, StateAtomFieldOptions> "
            "kStateAtomsFieldOptions;\n");
    fprintf(out, "  const static std::set<int> kWhitelistedAtoms;\n");
    fprintf(out, "};\n");
    fprintf(out, "const static int kMaxPushedAtomId = %d;\n\n", atoms.maxPushedAtomId);
}

static void write_atoms_info_cpp_body(FILE* out, const Atoms& atoms) {
    std::set<string> kTruncatingAtomNames = {"mobile_radio_power_state_changed",
                                             "audio_state_changed",
                                             "call_state_changed",
                                             "phone_signal_strength_changed",
                                             "mobile_bytes_transfer_by_fg_bg",
                                             "mobile_bytes_transfer"};
    fprintf(out,
            "const std::set<int> "
            "AtomsInfo::kTruncatingTimestampAtomBlackList = {\n");
    for (AtomDeclSet::const_iterator atomIt = atoms.decls.begin(); atomIt != atoms.decls.end();
         atomIt++) {
        if (kTruncatingAtomNames.find((*atomIt)->name) != kTruncatingAtomNames.end()) {
            const string constant = make_constant_name((*atomIt)->name);
            fprintf(out, "    %d, // %s\n", (*atomIt)->code, constant.c_str());
        }
    }

    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out, "const std::set<int> AtomsInfo::kAtomsWithAttributionChain = {\n");
    for (AtomDeclSet::const_iterator atomIt = atoms.decls.begin(); atomIt != atoms.decls.end();
         atomIt++) {
        for (vector<AtomField>::const_iterator field = (*atomIt)->fields.begin();
             field != (*atomIt)->fields.end(); field++) {
            if (field->javaType == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                const string constant = make_constant_name((*atomIt)->name);
                fprintf(out, "    %d, // %s\n", (*atomIt)->code, constant.c_str());
                break;
            }
        }
    }

    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out, "const std::set<int> AtomsInfo::kWhitelistedAtoms = {\n");
    for (AtomDeclSet::const_iterator atomIt = atoms.decls.begin(); atomIt != atoms.decls.end();
         atomIt++) {
        if ((*atomIt)->whitelisted) {
            const string constant = make_constant_name((*atomIt)->name);
            fprintf(out, "    %d, // %s\n", (*atomIt)->code, constant.c_str());
        }
    }

    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out, "static std::map<int, int> getAtomUidField() {\n");
    fprintf(out, "    std::map<int, int> uidField;\n");
    for (AtomDeclSet::const_iterator atomIt = atoms.decls.begin(); atomIt != atoms.decls.end();
         atomIt++) {
        if ((*atomIt)->uidField == 0) {
            continue;
        }
        fprintf(out,
                "\n    // Adding uid field for atom "
                "(%d)%s\n",
                (*atomIt)->code, (*atomIt)->name.c_str());
        fprintf(out, "    uidField[%d /* %s */] = %d;\n", (*atomIt)->code,
                make_constant_name((*atomIt)->name).c_str(), (*atomIt)->uidField);
    }

    fprintf(out, "    return uidField;\n");
    fprintf(out, "};\n");

    fprintf(out,
            "const std::map<int, int> AtomsInfo::kAtomsWithUidField = "
            "getAtomUidField();\n");

    fprintf(out,
            "static std::map<int, StateAtomFieldOptions> "
            "getStateAtomFieldOptions() {\n");
    fprintf(out, "    std::map<int, StateAtomFieldOptions> options;\n");
    fprintf(out, "    StateAtomFieldOptions* opt;\n");
    for (AtomDeclSet::const_iterator atomIt = atoms.decls.begin(); atomIt != atoms.decls.end();
         atomIt++) {
        if ((*atomIt)->primaryFields.size() == 0 && (*atomIt)->exclusiveField == 0) {
            continue;
        }
        fprintf(out,
                "\n    // Adding primary and exclusive fields for atom "
                "(%d)%s\n",
                (*atomIt)->code, (*atomIt)->name.c_str());
        fprintf(out, "    opt = &(options[%d /* %s */]);\n", (*atomIt)->code,
                make_constant_name((*atomIt)->name).c_str());
        fprintf(out, "    opt->primaryFields.reserve(%lu);\n", (*atomIt)->primaryFields.size());
        for (const auto& field : (*atomIt)->primaryFields) {
            fprintf(out, "    opt->primaryFields.push_back(%d);\n", field);
        }

        fprintf(out, "    opt->exclusiveField = %d;\n", (*atomIt)->exclusiveField);
        if ((*atomIt)->defaultState != INT_MAX) {
            fprintf(out, "    opt->defaultState = %d;\n", (*atomIt)->defaultState);
        } else {
            fprintf(out, "    opt->defaultState = UNSET_VALUE;\n");
        }

        if ((*atomIt)->resetState != INT_MAX) {
            fprintf(out, "    opt->resetState = %d;\n", (*atomIt)->resetState);
        } else {
            fprintf(out, "    opt->resetState = UNSET_VALUE;\n");
        }
        fprintf(out, "    opt->nested = %d;\n", (*atomIt)->nested);
    }

    fprintf(out, "    return options;\n");
    fprintf(out, "}\n");

    fprintf(out,
            "const std::map<int, StateAtomFieldOptions> "
            "AtomsInfo::kStateAtomsFieldOptions = "
            "getStateAtomFieldOptions();\n");
}

int write_atoms_info_header(FILE* out, const Atoms& atoms, const string& namespaceStr) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");
    fprintf(out, "#pragma once\n");
    fprintf(out, "\n");
    fprintf(out, "#include <vector>\n");
    fprintf(out, "#include <map>\n");
    fprintf(out, "#include <set>\n");
    fprintf(out, "\n");

    write_namespace(out, namespaceStr);

    write_atoms_info_header_body(out, atoms);

    fprintf(out, "\n");
    write_closing_namespace(out, namespaceStr);

    return 0;
}

int write_atoms_info_cpp(FILE* out, const Atoms& atoms, const string& namespaceStr,
                         const string& importHeader) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");
    fprintf(out, "#include <%s>\n", importHeader.c_str());
    fprintf(out, "\n");

    write_namespace(out, namespaceStr);

    write_atoms_info_cpp_body(out, atoms);

    // Print footer
    fprintf(out, "\n");
    write_closing_namespace(out, namespaceStr);

    return 0;
}

}  // namespace stats_log_api_gen
}  // namespace android
