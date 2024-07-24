/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "java_proto_stream_code_generator.h"

#include <stdio.h>

#include <algorithm>
#include <iomanip>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <unordered_set>

#include "Errors.h"

using namespace android::stream_proto;
using namespace google::protobuf::io;
using namespace std;

static bool outer_class_name_clashes_with_any_message(const string& outer_class_name,
                                                      const vector<DescriptorProto>& messages) {
    return any_of(messages.cbegin(), messages.cend(), [&](const DescriptorProto& message) {
        return message.name() == outer_class_name;
    });
}

/**
 * If the descriptor gives us a class name, use that. Otherwise make one up from
 * the filename of the .proto file.
 */
static string make_outer_class_name(const FileDescriptorProto& file_descriptor,
                                    const vector<DescriptorProto>& messages) {
    string name = file_descriptor.options().java_outer_classname();
    if (!name.empty()) {
        return name;
    }

    // Outer class and messages with the same name would result in invalid java (outer class and
    // inner class cannot have same names).
    // If the outer class name clashes with any message, let's append an "OuterClass" suffix.
    // This behavior is consistent with the standard protoc.
    name = to_camel_case(file_base_name(file_descriptor.name()));
    while (outer_class_name_clashes_with_any_message(name, messages)) {
        name += "OuterClass";
    }

    if (name.empty()) {
        ERRORS.Add(UNKNOWN_FILE, UNKNOWN_LINE, "Unable to make an outer class name for file: %s",
                   file_descriptor.name().c_str());
        name = "Unknown";
    }

    return name;
}

/**
 * Figure out the package name that we are generating.
 */
static string make_java_package(const FileDescriptorProto& file_descriptor) {
    if (file_descriptor.options().has_java_package()) {
        return file_descriptor.options().java_package();
    } else {
        return file_descriptor.package();
    }
}

/**
 * Figure out the name of the file we are generating.
 */
static string make_file_name(const FileDescriptorProto& file_descriptor, const string& class_name) {
    string const package = make_java_package(file_descriptor);
    string result;
    if (package.size() > 0) {
        result = replace_string(package, '.', '/');
        result += '/';
    }

    result += class_name;
    result += ".java";

    return result;
}

static string indent_more(const string& indent) {
    return indent + INDENT;
}

/**
 * Write the constants for an enum.
 */
static void write_enum(stringstream& text, const EnumDescriptorProto& enu, const string& indent) {
    const int N = enu.value_size();
    text << indent << "// enum " << enu.name() << endl;
    for (int i = 0; i < N; i++) {
        const EnumValueDescriptorProto& value = enu.value(i);
        text << indent << "public static final int " << make_constant_name(value.name()) << " = "
             << value.number() << ";" << endl;
    }
    text << endl;
}

/**
 * Write a field.
 */
static void write_field(stringstream& text, const FieldDescriptorProto& field,
                        const string& indent) {
    string optional_comment =
            field.label() == FieldDescriptorProto::LABEL_OPTIONAL ? "optional " : "";
    string repeated_comment =
            field.label() == FieldDescriptorProto::LABEL_REPEATED ? "repeated " : "";
    string proto_type = get_proto_type(field);
    string packed_comment = field.options().packed() ? " [packed=true]" : "";
    text << indent << "// " << optional_comment << repeated_comment << proto_type << ' '
         << field.name() << " = " << field.number() << packed_comment << ';' << endl;

    text << indent << "public static final long " << make_constant_name(field.name()) << " = 0x";

    ios::fmtflags fmt(text.flags());
    text << setfill('0') << setw(16) << hex << get_field_id(field);
    text.flags(fmt);

    text << "L;" << endl;

    text << endl;
}

/**
 * Write a Message constants class.
 */
static void write_message(stringstream& text, const DescriptorProto& message,
                          const string& indent) {
    int N;
    const string indented = indent_more(indent);

    text << indent << "// message " << message.name() << endl;
    text << indent << "public final class " << message.name() << " {" << endl;
    text << endl;

    // Enums
    N = message.enum_type_size();
    for (int i = 0; i < N; i++) {
        write_enum(text, message.enum_type(i), indented);
    }

    // Nested classes
    N = message.nested_type_size();
    for (int i = 0; i < N; i++) {
        write_message(text, message.nested_type(i), indented);
    }

    // Fields
    N = message.field_size();
    for (int i = 0; i < N; i++) {
        write_field(text, message.field(i), indented);
    }

    // Extensions
    N = message.extension_size();
    for (int i = 0; i < N; i++) {
        write_field(text, message.extension(i), indented);
    }

    text << indent << "}" << endl;
    text << endl;
}

/**
 * Write the contents of a file.
 *
 * If there are enums and generate_outer is false, invalid java code will be generated.
 */
static void write_file(CodeGeneratorResponse* response, const FileDescriptorProto& file_descriptor,
                       const string& filename, bool generate_outer,
                       const vector<EnumDescriptorProto>& enums,
                       const vector<DescriptorProto>& messages) {
    stringstream text;

    string const package_name = make_java_package(file_descriptor);
    string const outer_class_name = make_outer_class_name(file_descriptor, messages);

    text << "// Generated by protoc-gen-javastream. DO NOT MODIFY." << endl;
    text << "// source: " << file_descriptor.name() << endl << endl;

    if (package_name.size() > 0) {
        if (package_name.size() > 0) {
            text << "package " << package_name << ";" << endl;
            text << endl;
        }
    }

    // This bit of policy is android api rules specific: Raw proto classes
    // must never be in the API
    text << "/** @hide */" << endl;
    //    text << "@android.annotation.TestApi" << endl;

    if (generate_outer) {
        text << "public final class " << outer_class_name << " {" << endl;
        text << endl;
    }

    size_t N;
    const string indented = generate_outer ? indent_more("") : string();

    N = enums.size();
    for (size_t i = 0; i < N; i++) {
        write_enum(text, enums[i], indented);
    }

    N = messages.size();
    for (size_t i = 0; i < N; i++) {
        write_message(text, messages[i], indented);
    }

    if (generate_outer) {
        text << "}" << endl;
    }

    CodeGeneratorResponse::File* file_response = response->add_file();
    file_response->set_name(filename);
    file_response->set_content(text.str());
}

/**
 * Write one file per class.  Put all of the enums into the "outer" class.
 */
static void write_multiple_files(CodeGeneratorResponse* response,
                                 const FileDescriptorProto& file_descriptor,
                                 const unordered_set<string>& messages_allowlist) {
    // If there is anything to put in the outer class file, create one
    if (file_descriptor.enum_type_size() > 0) {
        vector<EnumDescriptorProto> enums;
        int N = file_descriptor.enum_type_size();
        for (int i = 0; i < N; i++) {
            auto enum_full_name =
                    file_descriptor.package() + "." + file_descriptor.enum_type(i).name();
            if (!messages_allowlist.empty() && !messages_allowlist.count(enum_full_name)) {
                continue;
            }
            enums.push_back(file_descriptor.enum_type(i));
        }

        vector<DescriptorProto> messages;

        if (messages_allowlist.empty() || !enums.empty()) {
            write_file(response, file_descriptor,
                       make_file_name(file_descriptor,
                                      make_outer_class_name(file_descriptor, messages)),
                       true, enums, messages);
        }
    }

    // For each of the message types, make a file
    int N = file_descriptor.message_type_size();
    for (int i = 0; i < N; i++) {
        vector<EnumDescriptorProto> enums;

        vector<DescriptorProto> messages;

        auto message_full_name =
                file_descriptor.package() + "." + file_descriptor.message_type(i).name();
        if (!messages_allowlist.empty() && !messages_allowlist.count(message_full_name)) {
            continue;
        }
        messages.push_back(file_descriptor.message_type(i));

        if (messages_allowlist.empty() || !messages.empty()) {
            write_file(response, file_descriptor,
                       make_file_name(file_descriptor, file_descriptor.message_type(i).name()),
                       false, enums, messages);
        }
    }
}

static void write_single_file(CodeGeneratorResponse* response,
                              const FileDescriptorProto& file_descriptor,
                              const unordered_set<string>& messages_allowlist) {
    int N;

    vector<EnumDescriptorProto> enums;
    N = file_descriptor.enum_type_size();
    for (int i = 0; i < N; i++) {
        auto enum_full_name = file_descriptor.package() + "." + file_descriptor.enum_type(i).name();
        if (!messages_allowlist.empty() && !messages_allowlist.count(enum_full_name)) {
            continue;
        }

        enums.push_back(file_descriptor.enum_type(i));
    }

    vector<DescriptorProto> messages;
    N = file_descriptor.message_type_size();
    for (int i = 0; i < N; i++) {
        auto message_full_name =
                file_descriptor.package() + "." + file_descriptor.message_type(i).name();

        if (!messages_allowlist.empty() && !messages_allowlist.count(message_full_name)) {
            continue;
        }

        messages.push_back(file_descriptor.message_type(i));
    }

    if (messages_allowlist.empty() || !enums.empty() || !messages.empty()) {
        write_file(response, file_descriptor,
                   make_file_name(file_descriptor,
                                  make_outer_class_name(file_descriptor, messages)),
                   true, enums, messages);
    }
}

static void parse_args_string(stringstream args_string_stream,
                              unordered_set<string>& messages_allowlist_out) {
    string line;
    while (getline(args_string_stream, line, ';')) {
        stringstream line_ss(line);
        string arg_name;
        getline(line_ss, arg_name, ':');
        if (arg_name == "include_filter") {
            string full_message_name;
            while (getline(line_ss, full_message_name, ',')) {
                messages_allowlist_out.insert(full_message_name);
            }
        } else {
            ERRORS.Add(UNKNOWN_FILE, UNKNOWN_LINE, "Unexpected argument '%s'.", arg_name.c_str());
        }
    }
}

CodeGeneratorResponse generate_java_protostream_code(CodeGeneratorRequest request) {
    CodeGeneratorResponse response;

    unordered_set<string> messages_allowlist;
    auto request_params = request.parameter();
    if (!request_params.empty()) {
        parse_args_string(stringstream(request_params), messages_allowlist);
    }

    // Build the files we need.
    const int N = request.proto_file_size();
    for (int i = 0; i < N; i++) {
        const FileDescriptorProto& file_descriptor = request.proto_file(i);
        if (should_generate_for_file(request, file_descriptor.name())) {
            if (file_descriptor.options().java_multiple_files()) {
                write_multiple_files(&response, file_descriptor, messages_allowlist);
            } else {
                write_single_file(&response, file_descriptor, messages_allowlist);
            }
        }
    }

    return response;
}
