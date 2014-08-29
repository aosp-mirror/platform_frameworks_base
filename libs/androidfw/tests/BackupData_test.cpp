/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "ObbFile_test"
#include <androidfw/BackupHelpers.h>
#include <utils/Log.h>
#include <utils/String8.h>

#include <gtest/gtest.h>

#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <string.h>

namespace android {

#define TEST_FILENAME "/test.bd"

// keys of different lengths to test padding
#define KEY1 "key1"
#define KEY2 "key2a"
#define KEY3 "key3bc"
#define KEY4 "key4def"

// payloads of different lengths to test padding
#define DATA1 "abcdefg"
#define DATA2 "hijklmnopq"
#define DATA3 "rstuvwxyz"
// KEY4 is only ever deleted

class BackupDataTest : public testing::Test {
protected:
    char* m_external_storage;
    String8 mFilename;
    String8 mKey1;
    String8 mKey2;
    String8 mKey3;
    String8 mKey4;

    virtual void SetUp() {
        m_external_storage = getenv("EXTERNAL_STORAGE");
        mFilename.append(m_external_storage);
        mFilename.append(TEST_FILENAME);

        ::unlink(mFilename.string());
        int fd = ::open(mFilename.string(), O_CREAT | O_TRUNC, S_IRUSR | S_IWUSR);
        if (fd < 0) {
            FAIL() << "Couldn't create " << mFilename.string() << " for writing";
        }
        mKey1 = String8(KEY1);
        mKey2 = String8(KEY2);
        mKey3 = String8(KEY3);
        mKey4 = String8(KEY4);
   }

    virtual void TearDown() {
    }
};

TEST_F(BackupDataTest, WriteAndReadSingle) {
  int fd = ::open(mFilename.string(), O_WRONLY);
  BackupDataWriter* writer = new BackupDataWriter(fd);

  EXPECT_EQ(NO_ERROR, writer->WriteEntityHeader(mKey1, sizeof(DATA1)))
          << "WriteEntityHeader returned an error";
  EXPECT_EQ(NO_ERROR, writer->WriteEntityData(DATA1, sizeof(DATA1)))
          << "WriteEntityData returned an error";

  ::close(fd);
  fd = ::open(mFilename.string(), O_RDONLY);
  BackupDataReader* reader = new BackupDataReader(fd);
  EXPECT_EQ(NO_ERROR, reader->Status())
          << "Reader ctor failed";

  bool done;
  int type;
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader";

  String8 key;
  size_t dataSize;
  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error";
  EXPECT_EQ(mKey1, key)
          << "wrong key from ReadEntityHeader";
  EXPECT_EQ(sizeof(DATA1), dataSize)
          << "wrong size from ReadEntityHeader";

  char* dataBytes = new char[dataSize];
  EXPECT_EQ((int) dataSize, reader->ReadEntityData(dataBytes, dataSize))
          << "ReadEntityData returned an error";
  for (unsigned int i = 0; i < sizeof(DATA1); i++) {
    EXPECT_EQ(DATA1[i], dataBytes[i])
             << "data character " << i << " should be equal";
  }
  delete dataBytes;
  delete writer;
  delete reader;
}

TEST_F(BackupDataTest, WriteAndReadMultiple) {
  int fd = ::open(mFilename.string(), O_WRONLY);
  BackupDataWriter* writer = new BackupDataWriter(fd);
  writer->WriteEntityHeader(mKey1, sizeof(DATA1));
  writer->WriteEntityData(DATA1, sizeof(DATA1));
  writer->WriteEntityHeader(mKey2, sizeof(DATA2));
  writer->WriteEntityData(DATA2, sizeof(DATA2));

  ::close(fd);
  fd = ::open(mFilename.string(), O_RDONLY);
  BackupDataReader* reader = new BackupDataReader(fd);

  bool done;
  int type;
  String8 key;
  size_t dataSize;
  char* dataBytes;
  // read first entity
  reader->ReadNextHeader(&done, &type);
  reader->ReadEntityHeader(&key, &dataSize);
  dataBytes = new char[dataSize];
  reader->ReadEntityData(dataBytes, dataSize);
  delete dataBytes;

  // read and verify second entity
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on second entity";
  EXPECT_EQ(mKey2, key)
          << "wrong key from ReadEntityHeader on second entity";
  EXPECT_EQ(sizeof(DATA2), dataSize)
          << "wrong size from ReadEntityHeader on second entity";

  dataBytes = new char[dataSize];
  EXPECT_EQ((int)dataSize, reader->ReadEntityData(dataBytes, dataSize))
          << "ReadEntityData returned an error on second entity";
  for (unsigned int i = 0; i < sizeof(DATA2); i++) {
    EXPECT_EQ(DATA2[i], dataBytes[i])
             << "data character " << i << " should be equal";
  }
  delete dataBytes;
  delete writer;
  delete reader;
}

TEST_F(BackupDataTest, SkipEntity) {
  int fd = ::open(mFilename.string(), O_WRONLY);
  BackupDataWriter* writer = new BackupDataWriter(fd);
  writer->WriteEntityHeader(mKey1, sizeof(DATA1));
  writer->WriteEntityData(DATA1, sizeof(DATA1));
  writer->WriteEntityHeader(mKey2, sizeof(DATA2));
  writer->WriteEntityData(DATA2, sizeof(DATA2));
  writer->WriteEntityHeader(mKey3, sizeof(DATA3));
  writer->WriteEntityData(DATA3, sizeof(DATA3));

  ::close(fd);
  fd = ::open(mFilename.string(), O_RDONLY);
  BackupDataReader* reader = new BackupDataReader(fd);

  bool done;
  int type;
  String8 key;
  size_t dataSize;
  char* dataBytes;
  // read first entity
  reader->ReadNextHeader(&done, &type);
  reader->ReadEntityHeader(&key, &dataSize);
  dataBytes = new char[dataSize];
  reader->ReadEntityData(dataBytes, dataSize);
  delete dataBytes;

  // skip second entity
  reader->ReadNextHeader(&done, &type);
  reader->ReadEntityHeader(&key, &dataSize);
  reader->SkipEntityData();

  // read and verify third entity
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader after skip";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on third entity";
  EXPECT_EQ(mKey3, key)
          << "wrong key from ReadEntityHeader on third entity";
  EXPECT_EQ(sizeof(DATA3), dataSize)
          << "wrong size from ReadEntityHeader on third entity";

  dataBytes = new char[dataSize];
  EXPECT_EQ((int) dataSize, reader->ReadEntityData(dataBytes, dataSize))
          << "ReadEntityData returned an error on third entity";
  for (unsigned int i = 0; i < sizeof(DATA3); i++) {
    EXPECT_EQ(DATA3[i], dataBytes[i])
             << "data character " << i << " should be equal";
  }
  delete dataBytes;
  delete writer;
  delete reader;
}

TEST_F(BackupDataTest, DeleteEntity) {
  int fd = ::open(mFilename.string(), O_WRONLY);
  BackupDataWriter* writer = new BackupDataWriter(fd);
  writer->WriteEntityHeader(mKey1, sizeof(DATA1));
  writer->WriteEntityData(DATA1, sizeof(DATA1));
  writer->WriteEntityHeader(mKey2, -1);

  ::close(fd);
  fd = ::open(mFilename.string(), O_RDONLY);
  BackupDataReader* reader = new BackupDataReader(fd);

  bool done;
  int type;
  String8 key;
  size_t dataSize;
  char* dataBytes;
  // read first entity
  reader->ReadNextHeader(&done, &type);
  reader->ReadEntityHeader(&key, &dataSize);
  dataBytes = new char[dataSize];
  reader->ReadEntityData(dataBytes, dataSize);
  delete dataBytes;

  // read and verify deletion
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader on deletion";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on second entity";
  EXPECT_EQ(mKey2, key)
          << "wrong key from ReadEntityHeader on second entity";
  EXPECT_EQ(-1, (int) dataSize)
          << "not recognizing deletion on second entity";

  delete writer;
  delete reader;
}

TEST_F(BackupDataTest, EneityAfterDelete) {
  int fd = ::open(mFilename.string(), O_WRONLY);
  BackupDataWriter* writer = new BackupDataWriter(fd);
  writer->WriteEntityHeader(mKey1, sizeof(DATA1));
  writer->WriteEntityData(DATA1, sizeof(DATA1));
  writer->WriteEntityHeader(mKey2, -1);
  writer->WriteEntityHeader(mKey3, sizeof(DATA3));
  writer->WriteEntityData(DATA3, sizeof(DATA3));

  ::close(fd);
  fd = ::open(mFilename.string(), O_RDONLY);
  BackupDataReader* reader = new BackupDataReader(fd);

  bool done;
  int type;
  String8 key;
  size_t dataSize;
  char* dataBytes;
  // read first entity
  reader->ReadNextHeader(&done, &type);
  reader->ReadEntityHeader(&key, &dataSize);
  dataBytes = new char[dataSize];
  reader->ReadEntityData(dataBytes, dataSize);
  delete dataBytes;

  // read and verify deletion
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader on deletion";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on second entity";
  EXPECT_EQ(mKey2, key)
          << "wrong key from ReadEntityHeader on second entity";
  EXPECT_EQ(-1, (int)dataSize)
          << "not recognizing deletion on second entity";

  // read and verify third entity
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader after deletion";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on third entity";
  EXPECT_EQ(mKey3, key)
          << "wrong key from ReadEntityHeader on third entity";
  EXPECT_EQ(sizeof(DATA3), dataSize)
          << "wrong size from ReadEntityHeader on third entity";

  dataBytes = new char[dataSize];
  EXPECT_EQ((int) dataSize, reader->ReadEntityData(dataBytes, dataSize))
          << "ReadEntityData returned an error on third entity";
  for (unsigned int i = 0; i < sizeof(DATA3); i++) {
    EXPECT_EQ(DATA3[i], dataBytes[i])
             << "data character " << i << " should be equal";
  }
  delete dataBytes;
  delete writer;
  delete reader;
}

TEST_F(BackupDataTest, OnlyDeleteEntities) {
  int fd = ::open(mFilename.string(), O_WRONLY);
  BackupDataWriter* writer = new BackupDataWriter(fd);
  writer->WriteEntityHeader(mKey1, -1);
  writer->WriteEntityHeader(mKey2, -1);
  writer->WriteEntityHeader(mKey3, -1);
  writer->WriteEntityHeader(mKey4, -1);

  ::close(fd);
  fd = ::open(mFilename.string(), O_RDONLY);
  BackupDataReader* reader = new BackupDataReader(fd);

  bool done;
  int type;
  String8 key;
  size_t dataSize;
  // read and verify first deletion
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader first deletion";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on first entity";
  EXPECT_EQ(mKey1, key)
          << "wrong key from ReadEntityHeader on first entity";
  EXPECT_EQ(-1, (int) dataSize)
          << "not recognizing deletion on first entity";

  // read and verify second deletion
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader second deletion";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on second entity";
  EXPECT_EQ(mKey2, key)
          << "wrong key from ReadEntityHeader on second entity";
  EXPECT_EQ(-1, (int) dataSize)
          << "not recognizing deletion on second entity";

  // read and verify third deletion
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader third deletion";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on third entity";
  EXPECT_EQ(mKey3, key)
          << "wrong key from ReadEntityHeader on third entity";
  EXPECT_EQ(-1, (int) dataSize)
          << "not recognizing deletion on third entity";

  // read and verify fourth deletion
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader fourth deletion";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on fourth entity";
  EXPECT_EQ(mKey4, key)
          << "wrong key from ReadEntityHeader on fourth entity";
  EXPECT_EQ(-1, (int) dataSize)
          << "not recognizing deletion on fourth entity";

  delete writer;
  delete reader;
}

TEST_F(BackupDataTest, ReadDeletedEntityData) {
  int fd = ::open(mFilename.string(), O_WRONLY);
  BackupDataWriter* writer = new BackupDataWriter(fd);
  writer->WriteEntityHeader(mKey1, -1);
  writer->WriteEntityHeader(mKey2, -1);

  ::close(fd);
  fd = ::open(mFilename.string(), O_RDONLY);
  BackupDataReader* reader = new BackupDataReader(fd);

  bool done;
  int type;
  String8 key;
  size_t dataSize;
  // read and verify first deletion
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader first deletion";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on first entity";
  EXPECT_EQ(mKey1, key)
          << "wrong key from ReadEntityHeader on first entity";
  EXPECT_EQ(-1, (int) dataSize)
          << "not recognizing deletion on first entity";

  // erroneously try to read first entity data
  char* dataBytes = new char[10];
  dataBytes[0] = 'A';
  EXPECT_EQ(NO_ERROR, reader->ReadEntityData(dataBytes, dataSize));
  // expect dataBytes to be unmodofied
  EXPECT_EQ('A', dataBytes[0]);

  // read and verify second deletion
  reader->ReadNextHeader(&done, &type);
  EXPECT_EQ(BACKUP_HEADER_ENTITY_V1, type)
          << "wrong type from ReadNextHeader second deletion";

  EXPECT_EQ(NO_ERROR, reader->ReadEntityHeader(&key, &dataSize))
          << "ReadEntityHeader returned an error on second entity";
  EXPECT_EQ(mKey2, key)
          << "wrong key from ReadEntityHeader on second entity";
  EXPECT_EQ(-1, (int) dataSize)
          << "not recognizing deletion on second entity";

  delete[] dataBytes;
  delete writer;
  delete reader;
}

}
