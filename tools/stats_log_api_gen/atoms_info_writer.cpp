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
#include "utils.h"

#include <map>
#include <set>
#include <vector>

namespace android {
namespace stats_log_api_gen {

static void write_atoms_info_header_body(FILE* out, const Atoms& atoms) {
    fprintf(out, "struct StateAtomFieldOptions {\n");
    fprintf(out, "  std::vector<int> primaryFields;\n");
    fprintf(out, "  int exclusiveField;\n");
    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out, "struct AtomsInfo {\n");
    fprintf(out,
            "  const static std::set<int> "
            "kTruncatingTimestampAtomBlackList;\n");
    fprintf(out, "  const static std::map<int, int> kAtomsWithUidField;\n");
    fprintf(out,
            "  const static std::set<int> kAtomsWithAttributionChain;\n");
    fprintf(out,
            "  const static std::map<int, StateAtomFieldOptions> "
            "kStateAtomsFieldOptions;\n");
    fprintf(out,
            "  const static std::map<int, std::vector<int>> "
            "kBytesFieldAtoms;\n");
    fprintf(out,
            "  const static std::set<int> kWhitelistedAtoms;\n");
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
    for (set<string>::const_iterator blacklistedAtom = kTruncatingAtomNames.begin();
         blacklistedAtom != kTruncatingAtomNames.end(); blacklistedAtom++) {
            fprintf(out, " %s,\n", make_constant_name(*blacklistedAtom).c_str());
    }
    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out,
            "const std::set<int> AtomsInfo::kAtomsWithAttributionChain = {\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        for (vector<AtomField>::const_iterator field = atom->fields.begin();
             field != atom->fields.end(); field++) {
            if (field->javaType == JAVA_TYPE_ATTRIBUTION_CHAIN) {
                string constant = make_constant_name(atom->name);
                fprintf(out, " %s,\n", constant.c_str());
                break;
            }
        }
    }

    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out,
            "const std::set<int> AtomsInfo::kWhitelistedAtoms = {\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        if (atom->whitelisted) {
            string constant = make_constant_name(atom->name);
            fprintf(out, " %s,\n", constant.c_str());
        }
    }

    fprintf(out, "};\n");
    fprintf(out, "\n");

    fprintf(out, "static std::map<int, int> getAtomUidField() {\n");
    fprintf(out, "  std::map<int, int> uidField;\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        if (atom->uidField == 0) {
            continue;
        }
        fprintf(out,
                "\n    // Adding uid field for atom "
                "(%d)%s\n",
                atom->code, atom->name.c_str());
        fprintf(out, "    uidField[static_cast<int>(%s)] = %d;\n",
                make_constant_name(atom->name).c_str(), atom->uidField);
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
    fprintf(out, "    StateAtomFieldOptions opt;\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        if (atom->primaryFields.size() == 0 && atom->exclusiveField == 0) {
            continue;
        }
        fprintf(out,
                "\n    // Adding primary and exclusive fields for atom "
                "(%d)%s\n",
                atom->code, atom->name.c_str());
        fprintf(out, "    opt.primaryFields.clear();\n");
        for (const auto& field : atom->primaryFields) {
            fprintf(out, "    opt.primaryFields.push_back(%d);\n", field);
        }

        fprintf(out, "    opt.exclusiveField = %d;\n", atom->exclusiveField);
        fprintf(out, "    options[static_cast<int>(%s)] = opt;\n",
                make_constant_name(atom->name).c_str());
    }

    fprintf(out, "    return options;\n");
    fprintf(out, "}\n");

    fprintf(out,
            "const std::map<int, StateAtomFieldOptions> "
            "AtomsInfo::kStateAtomsFieldOptions = "
            "getStateAtomFieldOptions();\n");

    fprintf(out,
            "static std::map<int, std::vector<int>> "
            "getBinaryFieldAtoms() {\n");
    fprintf(out, "    std::map<int, std::vector<int>> options;\n");
    for (set<AtomDecl>::const_iterator atom = atoms.decls.begin();
         atom != atoms.decls.end(); atom++) {
        if (atom->binaryFields.size() == 0) {
            continue;
        }
        fprintf(out,
                "\n    // Adding binary fields for atom "
                "(%d)%s\n",
                atom->code, atom->name.c_str());

        for (const auto& field : atom->binaryFields) {
            fprintf(out, "    options[static_cast<int>(%s)].push_back(%d);\n",
                    make_constant_name(atom->name).c_str(), field);
        }
    }

    fprintf(out, "    return options;\n");
    fprintf(out, "}\n");

    fprintf(out,
            "const std::map<int, std::vector<int>> "
            "AtomsInfo::kBytesFieldAtoms = "
            "getBinaryFieldAtoms();\n");

}

int write_atoms_info_header(FILE* out, const Atoms &atoms, const string& namespaceStr) {
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

int write_atoms_info_cpp(FILE *out, const Atoms &atoms, const string& namespaceStr,
        const string& importHeader, const string& statslogHeader) {
    // Print prelude
    fprintf(out, "// This file is autogenerated\n");
    fprintf(out, "\n");
    fprintf(out, "#include <%s>\n", importHeader.c_str());
    fprintf(out, "#include <%s>\n", statslogHeader.c_str());
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
