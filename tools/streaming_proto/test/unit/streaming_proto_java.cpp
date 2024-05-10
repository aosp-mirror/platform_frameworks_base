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

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "java/java_proto_stream_code_generator.h"

using ::testing::HasSubstr;
using ::testing::Not;

static void add_my_test_proto_file(CodeGeneratorRequest* request) {
    request->add_file_to_generate("MyTestProtoFile");

    FileDescriptorProto* file_desc = request->add_proto_file();
    file_desc->set_name("MyTestProtoFile");
    file_desc->set_package("test.package");

    auto* file_options = file_desc->mutable_options();
    file_options->set_java_multiple_files(false);

    auto* message = file_desc->add_message_type();
    message->set_name("MyTestMessage");

    auto* field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("my_test_field");

    field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("my_other_test_field");

    field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("my_other_test_message");
}

static void add_my_other_test_proto_file(CodeGeneratorRequest* request) {
    request->add_file_to_generate("MyOtherTestProtoFile");

    FileDescriptorProto* file_desc = request->add_proto_file();
    file_desc->set_name("MyOtherTestProtoFile");
    file_desc->set_package("test.package");

    auto* file_options = file_desc->mutable_options();
    file_options->set_java_multiple_files(false);

    auto* message = file_desc->add_message_type();
    message->set_name("MyOtherTestMessage");

    auto* field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("a_test_field");

    field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("another_test_field");
}

static CodeGeneratorRequest create_simple_two_file_request() {
    CodeGeneratorRequest request;

    add_my_test_proto_file(&request);
    add_my_other_test_proto_file(&request);

    return request;
}

static CodeGeneratorRequest create_simple_multi_file_request() {
    CodeGeneratorRequest request;

    request.add_file_to_generate("MyMultiMessageTestProtoFile");

    FileDescriptorProto* file_desc = request.add_proto_file();
    file_desc->set_name("MyMultiMessageTestProtoFile");
    file_desc->set_package("test.package");

    auto* file_options = file_desc->mutable_options();
    file_options->set_java_multiple_files(true);

    auto* message = file_desc->add_message_type();
    message->set_name("MyTestMessage");

    auto* field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("my_test_field");

    field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("my_other_test_field");

    field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("my_other_test_message");

    message = file_desc->add_message_type();
    message->set_name("MyOtherTestMessage");

    field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("a_test_field");

    field = message->add_field();
    field->set_label(FieldDescriptorProto::LABEL_OPTIONAL);
    field->set_name("another_test_field");

    return request;
}

TEST(StreamingProtoJavaTest, NoFilter) {
    CodeGeneratorRequest request = create_simple_two_file_request();
    CodeGeneratorResponse response = generate_java_protostream_code(request);

    auto generated_file_count = response.file_size();
    EXPECT_EQ(generated_file_count, 2);

    EXPECT_EQ(response.file(0).name(), "test/package/MyTestProtoFile.java");
    EXPECT_THAT(response.file(0).content(), HasSubstr("class MyTestProtoFile"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("class MyTestMessage"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("long MY_TEST_FIELD"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("long MY_OTHER_TEST_FIELD"));

    EXPECT_EQ(response.file(1).name(), "test/package/MyOtherTestProtoFile.java");
    EXPECT_THAT(response.file(1).content(), HasSubstr("class MyOtherTestProtoFile"));
    EXPECT_THAT(response.file(1).content(), HasSubstr("class MyOtherTestMessage"));
    EXPECT_THAT(response.file(1).content(), HasSubstr("long A_TEST_FIELD"));
    EXPECT_THAT(response.file(1).content(), HasSubstr("long ANOTHER_TEST_FIELD"));
}

TEST(StreamingProtoJavaTest, WithFilter) {
    CodeGeneratorRequest request = create_simple_two_file_request();
    request.set_parameter("include_filter:test.package.MyTestMessage");
    CodeGeneratorResponse response = generate_java_protostream_code(request);

    auto generated_file_count = response.file_size();
    EXPECT_EQ(generated_file_count, 1);

    EXPECT_EQ(response.file(0).name(), "test/package/MyTestProtoFile.java");
    EXPECT_THAT(response.file(0).content(), HasSubstr("class MyTestProtoFile"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("class MyTestMessage"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("long MY_TEST_FIELD"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("long MY_OTHER_TEST_FIELD"));
}

TEST(StreamingProtoJavaTest, WithoutFilter_MultipleJavaFiles) {
    CodeGeneratorRequest request = create_simple_multi_file_request();
    CodeGeneratorResponse response = generate_java_protostream_code(request);

    auto generated_file_count = response.file_size();
    EXPECT_EQ(generated_file_count, 2);

    EXPECT_EQ(response.file(0).name(), "test/package/MyTestMessage.java");
    EXPECT_THAT(response.file(0).content(), Not(HasSubstr("class MyTestProtoFile")));
    EXPECT_THAT(response.file(0).content(), HasSubstr("class MyTestMessage"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("long MY_TEST_FIELD"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("long MY_OTHER_TEST_FIELD"));

    EXPECT_EQ(response.file(1).name(), "test/package/MyOtherTestMessage.java");
    EXPECT_THAT(response.file(1).content(), Not(HasSubstr("class MyOtherTestProtoFile")));
    EXPECT_THAT(response.file(1).content(), HasSubstr("class MyOtherTestMessage"));
    EXPECT_THAT(response.file(1).content(), HasSubstr("long A_TEST_FIELD"));
    EXPECT_THAT(response.file(1).content(), HasSubstr("long ANOTHER_TEST_FIELD"));
}

TEST(StreamingProtoJavaTest, WithFilter_MultipleJavaFiles) {
    CodeGeneratorRequest request = create_simple_multi_file_request();
    request.set_parameter("include_filter:test.package.MyTestMessage");
    CodeGeneratorResponse response = generate_java_protostream_code(request);

    auto generated_file_count = response.file_size();
    EXPECT_EQ(generated_file_count, 1);

    EXPECT_EQ(response.file(0).name(), "test/package/MyTestMessage.java");
    EXPECT_THAT(response.file(0).content(), Not(HasSubstr("class MyTestProtoFile")));
    EXPECT_THAT(response.file(0).content(), HasSubstr("class MyTestMessage"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("long MY_TEST_FIELD"));
    EXPECT_THAT(response.file(0).content(), HasSubstr("long MY_OTHER_TEST_FIELD"));
}
