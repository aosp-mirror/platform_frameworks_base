/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "test/Test.h"

namespace aapt {

using ArchiveTest = TestDirectoryFixture;

constexpr size_t kTestDataLength = 100;

class TestData : public io::MallocData {
 public:
  TestData(std::unique_ptr<uint8_t[]>& data, size_t size)
      : MallocData(std::move(data), size) {}

  bool HadError() const override { return !error_.empty(); }

  std::string GetError() const override { return error_; }

  std::string error_;
};

std::unique_ptr<uint8_t[]> MakeTestArray() {
  auto array = std::make_unique<uint8_t[]>(kTestDataLength);
  for (int index = 0; index < kTestDataLength; ++index) {
    array[index] = static_cast<uint8_t>(rand());
  }
  return array;
}

std::unique_ptr<IArchiveWriter> MakeDirectoryWriter(const std::string& output_path) {
  file::mkdirs(output_path);

  StdErrDiagnostics diag;
  return CreateDirectoryArchiveWriter(&diag, output_path);
}

std::unique_ptr<IArchiveWriter> MakeZipFileWriter(const std::string& output_path) {
  file::mkdirs(file::GetStem(output_path).to_string());
  std::remove(output_path.c_str());

  StdErrDiagnostics diag;
  return CreateZipFileArchiveWriter(&diag, output_path);
}

void VerifyDirectory(const std::string& path, const std::string& file, const uint8_t array[]) {
  std::string file_path = file::BuildPath({path, file});
  auto buffer = std::make_unique<char[]>(kTestDataLength);
  std::ifstream stream(file_path);
  stream.read(buffer.get(), kTestDataLength);

  for (int index = 0; index < kTestDataLength; ++index) {
    ASSERT_EQ(array[index], static_cast<uint8_t>(buffer[index]));
  }
}

void VerifyZipFile(const std::string& output_path, const std::string& file, const uint8_t array[]) {
  std::unique_ptr<io::ZipFileCollection> zip = io::ZipFileCollection::Create(output_path, nullptr);
  std::unique_ptr<io::InputStream> stream = zip->FindFile(file)->OpenInputStream();

  std::vector<uint8_t> buffer;
  const void* data;
  size_t size;

  while (stream->Next(&data, &size)) {
    auto pointer = static_cast<const uint8_t*>(data);
    buffer.insert(buffer.end(), pointer, pointer + size);
  }

  for (int index = 0; index < kTestDataLength; ++index) {
    ASSERT_EQ(array[index], buffer[index]);
  }
}

TEST_F(ArchiveTest, DirectoryWriteEntrySuccess) {
  std::string output_path = GetTestPath("output");
  std::unique_ptr<IArchiveWriter> writer = MakeDirectoryWriter(output_path);
  std::unique_ptr<uint8_t[]> data1 = MakeTestArray();
  std::unique_ptr<uint8_t[]> data2 = MakeTestArray();

  ASSERT_TRUE(writer->StartEntry("test1", 0));
  ASSERT_TRUE(writer->Write(static_cast<const void*>(data1.get()), kTestDataLength));
  ASSERT_TRUE(writer->FinishEntry());
  ASSERT_FALSE(writer->HadError());

  ASSERT_TRUE(writer->StartEntry("test2", 0));
  ASSERT_TRUE(writer->Write(static_cast<const void*>(data2.get()), kTestDataLength));
  ASSERT_TRUE(writer->FinishEntry());
  ASSERT_FALSE(writer->HadError());

  writer.reset();

  VerifyDirectory(output_path, "test1", data1.get());
  VerifyDirectory(output_path, "test2", data2.get());
}

TEST_F(ArchiveTest, DirectoryWriteFileSuccess) {
  std::string output_path = GetTestPath("output");
  std::unique_ptr<IArchiveWriter> writer = MakeDirectoryWriter(output_path);

  std::unique_ptr<uint8_t[]> data1 = MakeTestArray();
  auto data1_copy = std::make_unique<uint8_t[]>(kTestDataLength);
  std::copy(data1.get(), data1.get() + kTestDataLength, data1_copy.get());

  std::unique_ptr<uint8_t[]> data2 = MakeTestArray();
  auto data2_copy = std::make_unique<uint8_t[]>(kTestDataLength);
  std::copy(data2.get(), data2.get() + kTestDataLength, data2_copy.get());

  auto input1 = std::make_unique<TestData>(data1_copy, kTestDataLength);
  auto input2 = std::make_unique<TestData>(data2_copy, kTestDataLength);

  ASSERT_TRUE(writer->WriteFile("test1", 0, input1.get()));
  ASSERT_FALSE(writer->HadError());
  ASSERT_TRUE(writer->WriteFile("test2", 0, input2.get()));
  ASSERT_FALSE(writer->HadError());

  writer.reset();

  VerifyDirectory(output_path, "test1", data1.get());
  VerifyDirectory(output_path, "test2", data2.get());
}

TEST_F(ArchiveTest, DirectoryWriteFileError) {
  std::string output_path = GetTestPath("output");
  std::unique_ptr<IArchiveWriter> writer = MakeDirectoryWriter(output_path);
  std::unique_ptr<uint8_t[]> data = MakeTestArray();
  auto input = std::make_unique<TestData>(data, kTestDataLength);
  input->error_ = "DirectoryWriteFileError";

  ASSERT_FALSE(writer->WriteFile("test", 0, input.get()));
  ASSERT_TRUE(writer->HadError());
  ASSERT_EQ("DirectoryWriteFileError", writer->GetError());
}

TEST_F(ArchiveTest, ZipFileWriteEntrySuccess) {
  std::string output_path = GetTestPath("output.apk");
  std::unique_ptr<IArchiveWriter> writer = MakeZipFileWriter(output_path);
  std::unique_ptr<uint8_t[]> data1 = MakeTestArray();
  std::unique_ptr<uint8_t[]> data2 = MakeTestArray();

  ASSERT_TRUE(writer->StartEntry("test1", 0));
  ASSERT_TRUE(writer->Write(static_cast<const void*>(data1.get()), kTestDataLength));
  ASSERT_TRUE(writer->FinishEntry());
  ASSERT_FALSE(writer->HadError());

  ASSERT_TRUE(writer->StartEntry("test2", 0));
  ASSERT_TRUE(writer->Write(static_cast<const void*>(data2.get()), kTestDataLength));
  ASSERT_TRUE(writer->FinishEntry());
  ASSERT_FALSE(writer->HadError());

  writer.reset();

  VerifyZipFile(output_path, "test1", data1.get());
  VerifyZipFile(output_path, "test2", data2.get());
}

TEST_F(ArchiveTest, ZipFileWriteFileSuccess) {
  std::string output_path = GetTestPath("output.apk");
  std::unique_ptr<IArchiveWriter> writer = MakeZipFileWriter(output_path);

  std::unique_ptr<uint8_t[]> data1 = MakeTestArray();
  auto data1_copy = std::make_unique<uint8_t[]>(kTestDataLength);
  std::copy(data1.get(), data1.get() + kTestDataLength, data1_copy.get());

  std::unique_ptr<uint8_t[]> data2 = MakeTestArray();
  auto data2_copy = std::make_unique<uint8_t[]>(kTestDataLength);
  std::copy(data2.get(), data2.get() + kTestDataLength, data2_copy.get());

  auto input1 = std::make_unique<TestData>(data1_copy, kTestDataLength);
  auto input2 = std::make_unique<TestData>(data2_copy, kTestDataLength);

  ASSERT_TRUE(writer->WriteFile("test1", 0, input1.get()));
  ASSERT_FALSE(writer->HadError());
  ASSERT_TRUE(writer->WriteFile("test2", 0, input2.get()));
  ASSERT_FALSE(writer->HadError());

  writer.reset();

  VerifyZipFile(output_path, "test1", data1.get());
  VerifyZipFile(output_path, "test2", data2.get());
}

TEST_F(ArchiveTest, ZipFileWriteFileError) {
  std::string output_path = GetTestPath("output.apk");
  std::unique_ptr<IArchiveWriter> writer = MakeZipFileWriter(output_path);
  std::unique_ptr<uint8_t[]> data = MakeTestArray();
  auto input = std::make_unique<TestData>(data, kTestDataLength);
  input->error_ = "ZipFileWriteFileError";

  ASSERT_FALSE(writer->WriteFile("test", 0, input.get()));
  ASSERT_TRUE(writer->HadError());
  ASSERT_EQ("ZipFileWriteFileError", writer->GetError());
}

}  // namespace aapt
