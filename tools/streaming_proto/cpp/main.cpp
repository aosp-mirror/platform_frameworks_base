#include "Errors.h"
#include "string_utils.h"

#include <frameworks/base/tools/streaming_proto/stream.pb.h>

#include "google/protobuf/compiler/plugin.pb.h"
#include "google/protobuf/io/zero_copy_stream_impl.h"
#include "google/protobuf/text_format.h"

#include <iomanip>
#include <iostream>
#include <sstream>

using namespace android::stream_proto;
using namespace google::protobuf;
using namespace google::protobuf::compiler;
using namespace google::protobuf::io;
using namespace std;

/**
 * Position of the field type in a (long long) fieldId.
 */
const uint64_t FIELD_TYPE_SHIFT = 32;

//
// FieldId flags for whether the field is single, repeated or packed.
// TODO: packed is not supported yet.
//
const uint64_t FIELD_COUNT_SHIFT = 40;
const uint64_t FIELD_COUNT_MASK = 0x0fULL << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_UNKNOWN = 0;
const uint64_t FIELD_COUNT_SINGLE = 1ULL << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_REPEATED = 2ULL << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_PACKED = 4ULL << FIELD_COUNT_SHIFT;

// Indent
const string INDENT = "    ";

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

static string
make_filename(const FileDescriptorProto& file_descriptor)
{
    return file_descriptor.name() + ".h";
}

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

static void
write_enum(stringstream& text, const EnumDescriptorProto& enu, const string& indent)
{
    const int N = enu.value_size();
    text << indent << "// enum " << enu.name() << endl;
    for (int i=0; i<N; i++) {
        const EnumValueDescriptorProto& value = enu.value(i);
        text << indent << "const uint32_t "
                << make_constant_name(value.name())
                << " = " << value.number() << ";" << endl;
    }
    text << endl;
}

static uint64_t
get_field_id(const FieldDescriptorProto& field)
{
    // Number
    uint64_t result = (uint64_t)field.number();

    // Type
    result |= (uint64_t)field.type() << FIELD_TYPE_SHIFT;

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

    text << indent << "const uint64_t " << make_constant_name(field.name()) << " = 0x";

    ios::fmtflags fmt(text.flags());
    text << setfill('0') << setw(16) << hex << get_field_id(field);
    text.flags(fmt);

    text << "LL;" << endl;

    text << endl;
}

static inline bool
should_generate_fields_mapping(const DescriptorProto& message)
{
    return message.options().GetExtension(stream).enable_fields_mapping();
}

static void
write_message(stringstream& text, const DescriptorProto& message, const string& indent)
{
    int N;
    const string indented = indent + INDENT;

    text << indent << "// message " << message.name() << endl;
    text << indent << "namespace " << message.name() << " {" << endl;

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

    if (should_generate_fields_mapping(message)) {
        N = message.field_size();
        text << indented << "const int _FIELD_COUNT = " << N << ";" << endl;
        text << indented << "const char* _FIELD_NAMES[" << N << "] = {" << endl;
        for (int i=0; i<N; i++) {
            text << indented << INDENT << "\"" << message.field(i).name() << "\"," << endl;
        }
        text << indented << "};" << endl;
        text << indented << "const uint64_t _FIELD_IDS[" << N << "] = {" << endl;
        for (int i=0; i<N; i++) {
            text << indented << INDENT << make_constant_name(message.field(i).name()) << "," << endl;
        }
        text << indented << "};" << endl << endl;
    }

    text << indent << "} //" << message.name() << endl;
    text << endl;
}

static void
write_header_file(CodeGeneratorResponse* response, const FileDescriptorProto& file_descriptor)
{
    stringstream text;

    text << "// Generated by protoc-gen-cppstream. DO NOT MODIFY." << endl;
    text << "// source: " << file_descriptor.name() << endl << endl;

    string header = "ANDROID_" + replace_string(file_descriptor.name(), '/', '_');
    header = replace_string(header, '.', '_') + "_stream_h";
    header = make_constant_name(header);

    text << "#ifndef " << header << endl;
    text << "#define " << header << endl;
    text << endl;

    vector<string> namespaces = split(file_descriptor.package(), '.');
    for (vector<string>::iterator it = namespaces.begin(); it != namespaces.end(); it++) {
        text << "namespace " << *it << " {" << endl;
    }
    text << endl;

    size_t N;
    N = file_descriptor.enum_type_size();
    for (size_t i=0; i<N; i++) {
        write_enum(text, file_descriptor.enum_type(i), "");
    }

    N = file_descriptor.message_type_size();
    for (size_t i=0; i<N; i++) {
        write_message(text, file_descriptor.message_type(i), "");
    }

    for (vector<string>::iterator it = namespaces.begin(); it != namespaces.end(); it++) {
        text << "} // " << *it << endl;
    }

    text << endl;
    text << "#endif // " << header << endl;

    CodeGeneratorResponse::File* file_response = response->add_file();
    file_response->set_name(make_filename(file_descriptor));
    file_response->set_content(text.str());
}

int main(int argc, char const *argv[])
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
            write_header_file(&response, file_descriptor);
        }
    }

    // If we had errors, don't write the response. Print the errors and exit.
    if (ERRORS.HasErrors()) {
        ERRORS.Print();
        return 1;
    }

    // If we didn't have errors, write the response and exit happily.
    response.SerializeToOstream(&cout);

    /* code */
    return 0;
}
