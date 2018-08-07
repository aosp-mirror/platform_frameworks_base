#include <stdint.h>

#include "google/protobuf/compiler/plugin.pb.h"
#include "google/protobuf/io/zero_copy_stream_impl.h"

namespace android {
namespace stream_proto {

using namespace google::protobuf;
using namespace google::protobuf::compiler;
using namespace std;

/**
 * Get encoded field id from a field.
 */
uint64_t get_field_id(const FieldDescriptorProto& field);

/**
 * Get the string name for a field.
 */
string get_proto_type(const FieldDescriptorProto& field);

/**
 * See if this is the file for this request, and not one of the imported ones.
 */
bool should_generate_for_file(const CodeGeneratorRequest& request, const string& file);

} // stream_proto
} // android
