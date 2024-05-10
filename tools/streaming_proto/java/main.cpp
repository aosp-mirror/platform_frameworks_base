#include <stdio.h>

#include <iomanip>
#include <iostream>
#include <map>
#include <sstream>
#include <string>

#include "Errors.h"
#include "java_proto_stream_code_generator.h"
#include "stream_proto_utils.h"

using namespace android::stream_proto;
using namespace google::protobuf::io;
using namespace std;

/**
 *
 * Main.
 */
int
main(int argc, char const*const* argv)
{
    (void)argc;
    (void)argv;

    GOOGLE_PROTOBUF_VERIFY_VERSION;

    // Read the request
    CodeGeneratorRequest request;
    request.ParseFromIstream(&cin);

    CodeGeneratorResponse response = generate_java_protostream_code(request);

    // If we had errors, don't write the response. Print the errors and exit.
    if (ERRORS.HasErrors()) {
        ERRORS.Print();
        return 1;
    }

    // If we didn't have errors, write the response and exit happily.
    response.SerializeToOstream(&cout);
    return 0;
}
