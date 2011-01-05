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

#ifndef __FWDLOCKCONV_H__
#define __FWDLOCKCONV_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <sys/types.h>

/**
 * The size of the data and header signatures combined. The signatures are adjacent to each other in
 * the produced output file.
 */
#define FWD_LOCK_SIGNATURES_SIZE (2 * 20)

/**
 * Data type for the output from FwdLockConv_ConvertData.
 */
typedef struct FwdLockConv_ConvertData_Output {
    /// The converted data.
    void *pBuffer;

    /// The size of the converted data.
    size_t numBytes;

    /// The file position where the error occurred, in the case of a syntax error.
    off64_t errorPos;
} FwdLockConv_ConvertData_Output_t;

/**
 * Data type for the output from FwdLockConv_CloseSession.
 */
typedef struct FwdLockConv_CloseSession_Output {
    /// The final set of signatures.
    unsigned char signatures[FWD_LOCK_SIGNATURES_SIZE];

    /// The offset in the produced output file where the signatures are located.
    off64_t fileOffset;

    /// The file position where the error occurred, in the case of a syntax error.
    off64_t errorPos;
} FwdLockConv_CloseSession_Output_t;

/**
 * Data type for the output from the conversion process.
 */
typedef union FwdLockConv_Output {
    FwdLockConv_ConvertData_Output_t fromConvertData;
    FwdLockConv_CloseSession_Output_t fromCloseSession;
} FwdLockConv_Output_t;

/**
 * Data type for the Posix-style read function used by the converter in pull mode.
 *
 * @param[in] fileDesc The file descriptor of a file opened for reading.
 * @param[out] pBuffer A reference to the buffer that should receive the read data.
 * @param[in] numBytes The number of bytes to read.
 *
 * @return The number of bytes read.
 * @retval -1 Failure.
 */
typedef ssize_t FwdLockConv_ReadFunc_t(int fileDesc, void *pBuffer, size_t numBytes);

/**
 * Data type for the Posix-style write function used by the converter in pull mode.
 *
 * @param[in] fileDesc The file descriptor of a file opened for writing.
 * @param[in] pBuffer A reference to the buffer containing the data to be written.
 * @param[in] numBytes The number of bytes to write.
 *
 * @return The number of bytes written.
 * @retval -1 Failure.
 */
typedef ssize_t FwdLockConv_WriteFunc_t(int fileDesc, const void *pBuffer, size_t numBytes);

/**
 * Data type for the Posix-style lseek function used by the converter in pull mode.
 *
 * @param[in] fileDesc The file descriptor of a file opened for writing.
 * @param[in] offset The offset with which to update the file position.
 * @param[in] whence One of SEEK_SET, SEEK_CUR, and SEEK_END.
 *
 * @return The new file position.
 * @retval ((off64_t)-1) Failure.
 */
typedef off64_t FwdLockConv_LSeekFunc_t(int fileDesc, off64_t offset, int whence);

/**
 * The status codes returned by the converter functions.
 */
typedef enum FwdLockConv_Status {
    /// The operation was successful.
    FwdLockConv_Status_OK = 0,

    /// An actual argument to the function is invalid (a program error on the caller's part).
    FwdLockConv_Status_InvalidArgument = 1,

    /// There is not enough free dynamic memory to complete the operation.
    FwdLockConv_Status_OutOfMemory = 2,

    /// An error occurred while opening the input file.
    FwdLockConv_Status_FileNotFound = 3,

    /// An error occurred while creating the output file.
    FwdLockConv_Status_FileCreationFailed = 4,

    /// An error occurred while reading from the input file.
    FwdLockConv_Status_FileReadError = 5,

    /// An error occurred while writing to the output file.
    FwdLockConv_Status_FileWriteError = 6,

    /// An error occurred while seeking to a new file position within the output file.
    FwdLockConv_Status_FileSeekError = 7,

    /// The input file is not a syntactically correct OMA DRM v1 Forward Lock file.
    FwdLockConv_Status_SyntaxError = 8,

    /// Support for this DRM file format has been disabled in the current product configuration.
    FwdLockConv_Status_UnsupportedFileFormat = 9,

    /// The content transfer encoding is not one of "binary", "base64", "7bit", or "8bit"
    /// (case-insensitive).
    FwdLockConv_Status_UnsupportedContentTransferEncoding = 10,

    /// The generation of a random number failed.
    FwdLockConv_Status_RandomNumberGenerationFailed = 11,

    /// Key encryption failed.
    FwdLockConv_Status_KeyEncryptionFailed = 12,

    /// The calculation of a keyed hash for integrity protection failed.
    FwdLockConv_Status_IntegrityProtectionFailed = 13,

    /// There are too many ongoing sessions for another one to be opened.
    FwdLockConv_Status_TooManySessions = 14,

    /// An unexpected error occurred.
    FwdLockConv_Status_ProgramError = 15
} FwdLockConv_Status_t;

/**
 * Opens a session for converting an OMA DRM v1 Forward Lock file to the internal Forward Lock file
 * format.
 *
 * @param[out] pSessionId The session ID.
 * @param[out] pOutput The output from the conversion process (initialized).
 *
 * @return A status code.
 * @retval FwdLockConv_Status_OK
 * @retval FwdLockConv_Status_InvalidArgument
 * @retval FwdLockConv_Status_TooManySessions
 */
FwdLockConv_Status_t FwdLockConv_OpenSession(int *pSessionId, FwdLockConv_Output_t *pOutput);

/**
 * Supplies the converter with data to convert. The caller is expected to write the converted data
 * to file. Can be called an arbitrary number of times.
 *
 * @param[in] sessionId The session ID.
 * @param[in] pBuffer A reference to a buffer containing the data to convert.
 * @param[in] numBytes The number of bytes to convert.
 * @param[in,out] pOutput The output from the conversion process (allocated/reallocated).
 *
 * @return A status code.
 * @retval FwdLockConv_Status_OK
 * @retval FwdLockConv_Status_InvalidArgument
 * @retval FwdLockConv_Status_OutOfMemory
 * @retval FwdLockConv_Status_SyntaxError
 * @retval FwdLockConv_Status_UnsupportedFileFormat
 * @retval FwdLockConv_Status_UnsupportedContentTransferEncoding
 * @retval FwdLockConv_Status_RandomNumberGenerationFailed
 * @retval FwdLockConv_Status_KeyEncryptionFailed
 * @retval FwdLockConv_Status_DataEncryptionFailed
 */
FwdLockConv_Status_t FwdLockConv_ConvertData(int sessionId,
                                             const void *pBuffer,
                                             size_t numBytes,
                                             FwdLockConv_Output_t *pOutput);

/**
 * Closes a session for converting an OMA DRM v1 Forward Lock file to the internal Forward Lock
 * file format. The caller must update the produced output file at the indicated file offset with
 * the final set of signatures.
 *
 * @param[in] sessionId The session ID.
 * @param[in,out] pOutput The output from the conversion process (deallocated and overwritten).
 *
 * @return A status code.
 * @retval FwdLockConv_Status_OK
 * @retval FwdLockConv_Status_InvalidArgument
 * @retval FwdLockConv_Status_OutOfMemory
 * @retval FwdLockConv_Status_IntegrityProtectionFailed
 */
FwdLockConv_Status_t FwdLockConv_CloseSession(int sessionId, FwdLockConv_Output_t *pOutput);

/**
 * Converts an open OMA DRM v1 Forward Lock file to the internal Forward Lock file format in pull
 * mode.
 *
 * @param[in] inputFileDesc The file descriptor of the open input file.
 * @param[in] fpReadFunc A reference to a read function that can operate on the open input file.
 * @param[in] outputFileDesc The file descriptor of the open output file.
 * @param[in] fpWriteFunc A reference to a write function that can operate on the open output file.
 * @param[in] fpLSeekFunc A reference to an lseek function that can operate on the open output file.
 * @param[out] pErrorPos
 *   The file position where the error occurred, in the case of a syntax error. May be NULL.
 *
 * @return A status code.
 * @retval FwdLockConv_Status_OK
 * @retval FwdLockConv_Status_InvalidArgument
 * @retval FwdLockConv_Status_OutOfMemory
 * @retval FwdLockConv_Status_FileReadError
 * @retval FwdLockConv_Status_FileWriteError
 * @retval FwdLockConv_Status_FileSeekError
 * @retval FwdLockConv_Status_SyntaxError
 * @retval FwdLockConv_Status_UnsupportedFileFormat
 * @retval FwdLockConv_Status_UnsupportedContentTransferEncoding
 * @retval FwdLockConv_Status_RandomNumberGenerationFailed
 * @retval FwdLockConv_Status_KeyEncryptionFailed
 * @retval FwdLockConv_Status_DataEncryptionFailed
 * @retval FwdLockConv_Status_IntegrityProtectionFailed
 * @retval FwdLockConv_Status_TooManySessions
 */
FwdLockConv_Status_t FwdLockConv_ConvertOpenFile(int inputFileDesc,
                                                 FwdLockConv_ReadFunc_t *fpReadFunc,
                                                 int outputFileDesc,
                                                 FwdLockConv_WriteFunc_t *fpWriteFunc,
                                                 FwdLockConv_LSeekFunc_t *fpLSeekFunc,
                                                 off64_t *pErrorPos);

/**
 * Converts an OMA DRM v1 Forward Lock file to the internal Forward Lock file format in pull mode.
 *
 * @param[in] pInputFilename A reference to the input filename.
 * @param[in] pOutputFilename A reference to the output filename.
 * @param[out] pErrorPos
 *   The file position where the error occurred, in the case of a syntax error. May be NULL.
 *
 * @return A status code.
 * @retval FwdLockConv_Status_OK
 * @retval FwdLockConv_Status_InvalidArgument
 * @retval FwdLockConv_Status_OutOfMemory
 * @retval FwdLockConv_Status_FileNotFound
 * @retval FwdLockConv_Status_FileCreationFailed
 * @retval FwdLockConv_Status_FileReadError
 * @retval FwdLockConv_Status_FileWriteError
 * @retval FwdLockConv_Status_FileSeekError
 * @retval FwdLockConv_Status_SyntaxError
 * @retval FwdLockConv_Status_UnsupportedFileFormat
 * @retval FwdLockConv_Status_UnsupportedContentTransferEncoding
 * @retval FwdLockConv_Status_RandomNumberGenerationFailed
 * @retval FwdLockConv_Status_KeyEncryptionFailed
 * @retval FwdLockConv_Status_DataEncryptionFailed
 * @retval FwdLockConv_Status_IntegrityProtectionFailed
 * @retval FwdLockConv_Status_TooManySessions
 */
FwdLockConv_Status_t FwdLockConv_ConvertFile(const char *pInputFilename,
                                             const char *pOutputFilename,
                                             off64_t *pErrorPos);

#ifdef __cplusplus
}
#endif

#endif // __FWDLOCKCONV_H__
