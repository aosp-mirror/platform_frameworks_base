#include "stream_proto_utils.h"

namespace android {
namespace stream_proto {

/**
 * Position of the field type in a (long long) fieldId.
 */
const uint64_t FIELD_TYPE_SHIFT = 32;

//
// FieldId flags for whether the field is single, repeated or packed.
// TODO: packed is not supported yet.
//
const uint64_t FIELD_COUNT_SHIFT = 40;
const uint64_t FIELD_COUNT_SINGLE = 1ULL << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_REPEATED = 2ULL << FIELD_COUNT_SHIFT;
const uint64_t FIELD_COUNT_PACKED = 5ULL << FIELD_COUNT_SHIFT;

uint64_t
get_field_id(const FieldDescriptorProto& field)
{
    // Number
    uint64_t result = (uint32_t)field.number();

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

string
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

bool
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

} // stream_proto
} // android
