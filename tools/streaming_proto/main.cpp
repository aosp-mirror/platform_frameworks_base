#include "Errors.h"

#include "string_utils.h"

#include "google/protobuf/compiler/plugin.pb.h"
#include "google/protobuf/io/zero_copy_stream_impl.h"
#include "google/protobuf/text_format.h"

#include <stdio.h>
#include <iomanip>
#include <iostream>
#include <sstream>
#include <map>

using namespace android::javastream_proto;
using namespace google::protobuf;
using namespace google::protobuf::compiler;
using namespace google::protobuf::io;
using namespace std;

const int FIELD_TYPE_SHIFT = 32;
const uint64_t FIELD_TYPE_DOUBLE = 1L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_FLOAT = 2L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_INT32 = 3L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_INT64 = 4L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_UINT32 = 5L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_UINT64 = 6L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_SINT32 = 7L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_SINT64 = 8L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_FIXED32 = 9L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_FIXED64 = 10L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_SFIXED32 = 11L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_SFIXED64 = 12L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_BOOL = 13L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_STRING = 14L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_BYTES = 15L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_ENUM = 16L << FIELD_TYPE_SHIFT;
const uint64_t FIELD_TYPE_OBJECT = 17L << FIELD_TYPE_SHIFT;

const int FIELD_COUNT_SHIFT = 40;
const uint64_t FIELD_COUNT_SINGLE = 1L << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_REPEATED = 2L << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_PACKED = 5L << FIELD_COUNT_SHIFT;


/**
 * See if this is the file for this request, and not one of the imported ones.
 */
static bool
should_generate_for_file(const CodeGeneratorRequest& request, const string& file)
{
    const int N = request.file_to_generate_size();
    for (int i=0; i<N; i++) {
        if (request.file_to_generate(i) == file) {
            return true;
        }
    }
    return false;
}

/**
 * If the descriptor gives us a class name, use that. Otherwise make one up from
 * the filename of the .proto file.
 */
static string
make_outer_class_name(const FileDescriptorProto& file_descriptor)
{
    string name = file_descriptor.options().java_outer_classname();
    if (name.size() == 0) {
        name = to_camel_case(file_base_name(file_descriptor.name()));
        if (name.size() == 0) {
            ERRORS.Add(UNKNOWN_FILE, UNKNOWN_LINE,
                    "Unable to make an outer class name for file: %s",
                    file_descriptor.name().c_str());
            name = "Unknown";
        }
    }
    return name;
}

/**
 * Figure out the package name that we are generating.
 */
static string
make_java_package(const FileDescriptorProto& file_descriptor) {
    if (file_descriptor.options().has_java_package()) {
        return file_descriptor.options().java_package();
    } else {
        return file_descriptor.package();
    }
}

/**
 * Figure out the name of the file we are generating.
 */
static string
make_file_name(const FileDescriptorProto& file_descriptor, const string& class_name)
{
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

static string
indent_more(const string& indent)
{
    return indent + "    ";
}

/**
 * Write the constants for an enum.
 */
static void
write_enum(stringstream& text, const EnumDescriptorProto& enu, const string& indent)
{
    const int N = enu.value_size();
    text << indent << "// enum " << enu.name() << endl;
    for (int i=0; i<N; i++) {
        const EnumValueDescriptorProto& value = enu.value(i);
        text << indent << "public static final int "
                << make_constant_name(value.name())
                << " = " << value.number() << ";" << endl;
    }
    text << endl;
}

/**
 * Get the string name for a field.
 */
static string
get_proto_type(const FieldDescriptorProto& field)
{
    switch (field.type()) {
        case FieldDescriptorProto::TYPE_DOUBLE:
            return "double";
        case FieldDescriptorProto::TYPE_FLOAT:
            return "float";
        case FieldDescriptorProto::TYPE_INT64:
            return "int64";
        case FieldDescriptorProto::TYPE_UINT64:
            return "uint64";
        case FieldDescriptorProto::TYPE_INT32:
            return "int32";
        case FieldDescriptorProto::TYPE_FIXED64:
            return "fixed64";
        case FieldDescriptorProto::TYPE_FIXED32:
            return "fixed32";
        case FieldDescriptorProto::TYPE_BOOL:
            return "bool";
        case FieldDescriptorProto::TYPE_STRING:
            return "string";
        case FieldDescriptorProto::TYPE_GROUP:
            return "group<unsupported!>";
        case FieldDescriptorProto::TYPE_MESSAGE:
            return field.type_name();
        case FieldDescriptorProto::TYPE_BYTES:
            return "bytes";
        case FieldDescriptorProto::TYPE_UINT32:
            return "uint32";
        case FieldDescriptorProto::TYPE_ENUM:
            return field.type_name();
        case FieldDescriptorProto::TYPE_SFIXED32:
            return "sfixed32";
        case FieldDescriptorProto::TYPE_SFIXED64:
            return "sfixed64";
        case FieldDescriptorProto::TYPE_SINT32:
            return "sint32";
        case FieldDescriptorProto::TYPE_SINT64:
            return "sint64";
        default:
            // won't happen
            return "void";
    }
}

static uint64_t
get_field_id(const FieldDescriptorProto& field)
{
    // Number
    uint64_t result = (uint32_t)field.number();

    // Type
    switch (field.type()) {
        case FieldDescriptorProto::TYPE_DOUBLE:
            result |= FIELD_TYPE_DOUBLE;
            break;
        case FieldDescriptorProto::TYPE_FLOAT:
            result |= FIELD_TYPE_FLOAT;
            break;
        case FieldDescriptorProto::TYPE_INT64:
            result |= FIELD_TYPE_INT64;
            break;
        case FieldDescriptorProto::TYPE_UINT64:
            result |= FIELD_TYPE_UINT64;
            break;
        case FieldDescriptorProto::TYPE_INT32:
            result |= FIELD_TYPE_INT32;
            break;
        case FieldDescriptorProto::TYPE_FIXED64:
            result |= FIELD_TYPE_FIXED64;
            break;
        case FieldDescriptorProto::TYPE_FIXED32:
            result |= FIELD_TYPE_FIXED32;
            break;
        case FieldDescriptorProto::TYPE_BOOL:
            result |= FIELD_TYPE_BOOL;
            break;
        case FieldDescriptorProto::TYPE_STRING:
            result |= FIELD_TYPE_STRING;
            break;
        case FieldDescriptorProto::TYPE_MESSAGE:
            result |= FIELD_TYPE_OBJECT;
            break;
        case FieldDescriptorProto::TYPE_BYTES:
            result |= FIELD_TYPE_BYTES;
            break;
        case FieldDescriptorProto::TYPE_UINT32:
            result |= FIELD_TYPE_UINT32;
            break;
        case FieldDescriptorProto::TYPE_ENUM:
            result |= FIELD_TYPE_ENUM;
            break;
        case FieldDescriptorProto::TYPE_SFIXED32:
            result |= FIELD_TYPE_SFIXED32;
            break;
        case FieldDescriptorProto::TYPE_SFIXED64:
            result |= FIELD_TYPE_SFIXED64;
            break;
        case FieldDescriptorProto::TYPE_SINT32:
            result |= FIELD_TYPE_SINT32;
            break;
        case FieldDescriptorProto::TYPE_SINT64:
            result |= FIELD_TYPE_SINT64;
            break;
        default:
            ;
    }

    // Count
    if (field.options().packed()) {
        result |= FIELD_COUNT_PACKED;
    } else if (field.label() == FieldDescriptorProto::LABEL_REPEATED) {
        result |= FIELD_COUNT_REPEATED;
    } else {
        result |= FIELD_COUNT_SINGLE;
    }

    return result;
}

/**
 * Write a field.
 */
static void
write_field(stringstream& text, const FieldDescriptorProto& field, const string& indent)
{
    string optional_comment = field.label() == FieldDescriptorProto::LABEL_OPTIONAL
            ? "optional " : "";
    string repeated_comment = field.label() == FieldDescriptorProto::LABEL_REPEATED
            ? "repeated " : "";
    string proto_type = get_proto_type(field);
    string packed_comment = field.options().packed()
            ? " [packed=true]" : "";
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
static void
write_message(stringstream& text, const DescriptorProto& message, const string& indent)
{
    int N;
    const string indented = indent_more(indent);

    text << indent << "// message " << message.name() << endl;
    text << indent << "public final class " << message.name() << " {" << endl;
    text << endl;

    // Enums
    N = message.enum_type_size();
    for (int i=0; i<N; i++) {
        write_enum(text, message.enum_type(i), indented);
    }

    // Nested classes
    N = message.nested_type_size();
    for (int i=0; i<N; i++) {
        write_message(text, message.nested_type(i), indented);
    }

    // Fields
    N = message.field_size();
    for (int i=0; i<N; i++) {
        write_field(text, message.field(i), indented);
    }

    text << indent << "}" << endl;
    text << endl;
}

/**
 * Write the contents of a file.
 *
 * If there are enums and generate_outer is false, invalid java code will be generated.
 */
static void
write_file(CodeGeneratorResponse* response, const FileDescriptorProto& file_descriptor,
        const string& filename, bool generate_outer,
        const vector<EnumDescriptorProto>& enums, const vector<DescriptorProto>& messages)
{
    stringstream text;

    string const package_name = make_java_package(file_descriptor);
    string const outer_class_name = make_outer_class_name(file_descriptor);

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
    for (size_t i=0; i<N; i++) {
        write_enum(text, enums[i], indented);
    }

    N = messages.size();
    for (size_t i=0; i<N; i++) {
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
static void
write_multiple_files(CodeGeneratorResponse* response, const FileDescriptorProto& file_descriptor)
{
    // If there is anything to put in the outer class file, create one
    if (file_descriptor.enum_type_size() > 0) {
        vector<EnumDescriptorProto> enums;
        int N = file_descriptor.enum_type_size();
        for (int i=0; i<N; i++) {
            enums.push_back(file_descriptor.enum_type(i));
        }

        vector<DescriptorProto> messages;

        write_file(response, file_descriptor,
                make_file_name(file_descriptor, make_outer_class_name(file_descriptor)),
                true, enums, messages);
    }

    // For each of the message types, make a file
    int N = file_descriptor.message_type_size();
    for (int i=0; i<N; i++) {
        vector<EnumDescriptorProto> enums;

        vector<DescriptorProto> messages;
        messages.push_back(file_descriptor.message_type(i));

        write_file(response, file_descriptor,
                make_file_name(file_descriptor, file_descriptor.message_type(i).name()),
                false, enums, messages);
    }
}

static void
write_single_file(CodeGeneratorResponse* response, const FileDescriptorProto& file_descriptor)
{
    int N;

    vector<EnumDescriptorProto> enums;
    N = file_descriptor.enum_type_size();
    for (int i=0; i<N; i++) {
        enums.push_back(file_descriptor.enum_type(i));
    }

    vector<DescriptorProto> messages;
    N = file_descriptor.message_type_size();
    for (int i=0; i<N; i++) {
        messages.push_back(file_descriptor.message_type(i));
    }

    write_file(response, file_descriptor,
            make_file_name(file_descriptor, make_outer_class_name(file_descriptor)),
            true, enums, messages);
}

/**
 * Main.
 */
int
main(int argc, char const*const* argv)
{
    (void)argc;
    (void)argv;

    GOOGLE_PROTOBUF_VERIFY_VERSION;

    CodeGeneratorRequest request;
    CodeGeneratorResponse response;

    // Read the request
    request.ParseFromIstream(&cin);

    // Build the files we need.
    const int N = request.proto_file_size();
    for (int i=0; i<N; i++) {
        const FileDescriptorProto& file_descriptor = request.proto_file(i);
        if (should_generate_for_file(request, file_descriptor.name())) {
            if (file_descriptor.options().java_multiple_files()) {
                write_multiple_files(&response, file_descriptor);
            } else {
                write_single_file(&response, file_descriptor);
            }
        }
    }

    // If we had errors, don't write the response. Print the errors and exit.
    if (ERRORS.HasErrors()) {
        ERRORS.Print();
        return 1;
    }

    // If we didn't have errors, write the response and exit happily.
    response.SerializeToOstream(&cout);
    return 0;
}
