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

#ifndef __FWDLOCKFILE_H__
#define __FWDLOCKFILE_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <sys/types.h>

/**
 * Attaches to an open Forward Lock file. The file position is assumed to be at the beginning of the
 * file.
 *
 * @param[in] fileDesc The file descriptor of an open Forward Lock file.
 *
 * @return A status code.
 * @retval 0 Success.
 * @retval -1 Failure.
 */
int FwdLockFile_attach(int fileDesc);

/**
 * Opens a Forward Lock file for reading.
 *
 * @param[in] pFilename A reference to a filename.
 *
 * @return A file descriptor.
 * @retval -1 Failure.
 */
int FwdLockFile_open(const char *pFilename);

/**
 * Reads the specified number of bytes from an open Forward Lock file.
 *
 * @param[in] fileDesc The file descriptor of an open Forward Lock file.
 * @param[out] pBuffer A reference to the buffer that should receive the read data.
 * @param[in] numBytes The number of bytes to read.
 *
 * @return The number of bytes read.
 * @retval -1 Failure.
 */
ssize_t FwdLockFile_read(int fileDesc, void *pBuffer, size_t numBytes);

/**
 * Updates the file position within an open Forward Lock file.
 *
 * @param[in] fileDesc The file descriptor of an open Forward Lock file.
 * @param[in] offset The offset with which to update the file position.
 * @param[in] whence One of SEEK_SET, SEEK_CUR, and SEEK_END.
 *
 * @return The new file position.
 * @retval ((off64_t)-1) Failure.
 */
off64_t FwdLockFile_lseek(int fileDesc, off64_t offset, int whence);

/**
 * Detaches from an open Forward Lock file.
 *
 * @param[in] fileDesc The file descriptor of an open Forward Lock file.
 *
 * @return A status code.
 * @retval 0 Success.
 * @retval -1 Failure.
 */
int FwdLockFile_detach(int fileDesc);

/**
 * Closes an open Forward Lock file.
 *
 * @param[in] fileDesc The file descriptor of an open Forward Lock file.
 *
 * @return A status code.
 * @retval 0 Success.
 * @retval -1 Failure.
 */
int FwdLockFile_close(int fileDesc);

/**
 * Checks the data integrity of an open Forward Lock file.
 *
 * @param[in] fileDesc The file descriptor of an open Forward Lock file.
 *
 * @return A Boolean value indicating whether the integrity check was successful.
 */
int FwdLockFile_CheckDataIntegrity(int fileDesc);

/**
 * Checks the header integrity of an open Forward Lock file.
 *
 * @param[in] fileDesc The file descriptor of an open Forward Lock file.
 *
 * @return A Boolean value indicating whether the integrity check was successful.
 */
int FwdLockFile_CheckHeaderIntegrity(int fileDesc);

/**
 * Checks both the data and header integrity of an open Forward Lock file.
 *
 * @param[in] fileDesc The file descriptor of an open Forward Lock file.
 *
 * @return A Boolean value indicating whether the integrity check was successful.
 */
int FwdLockFile_CheckIntegrity(int fileDesc);

/**
 * Returns the content type of an open Forward Lock file.
 *
 * @param[in] fileDesc The file descriptor of an open Forward Lock file.
 *
 * @return
 *   A reference to the content type. The reference remains valid as long as the file is kept open.
 */
const char *FwdLockFile_GetContentType(int fileDesc);

#ifdef __cplusplus
}
#endif

#endif // __FWDLOCKFILE_H__
