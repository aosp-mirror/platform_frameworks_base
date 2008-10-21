/*
 * Copyright (C) 2007 The Android Open Source Project
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


/**
 * File Porting Layer.
 */
#ifndef __DRM_FILE_H__
#define __DRM_FILE_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <drm_common_types.h>

/** Type value of a regular file or file name. */
#define DRM_FILE_ISREG 1
/** Type value of a directory or directory name. */
#define DRM_FILE_ISDIR 2
/** Type value of a filter name */
#define DRM_FILE_ISFILTER 3


/** Return code that indicates successful completion of an operation. */
#define DRM_FILE_SUCCESS 0
/** Indicates that an operation failed. */
#define DRM_FILE_FAILURE -1
/** Indicates that the a DRM_file_read() call reached the end of the file. */
#define DRM_FILE_EOF -2


/** Open for read access. */
#define DRM_FILE_MODE_READ 1
/** Open for write access. */
#define DRM_FILE_MODE_WRITE 2


#ifndef MAX_FILENAME_LEN
/** Maximum number of characters that a filename may have. By default assumes
 *  that the entry results of DRM_file_listNextEntry() are returned in the async state
 *  buffer, after the #DRM_file_result_s, and calculates the maximum name
 *  from that.
 */
#define MAX_FILENAME_LEN 1024
#endif


/**
 * Performs one-time initialization of the File System (FS).
 * This function is called once during the lifetime of an application,
 * and before any call to <code>DRM_file_*</code> functions by this application.
 * When several applications are using the file interface, this function may be called
 * several times, once per application.
 *
 * @return #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE.
 */
int32_t DRM_file_startup(void);

/**
 * Returns the length of a file (by name, opened or unopened).
 *
 * @param name Name of the file, UCS-2 encoded.
 * @param nameChars Number characters encoded in name.
 * asynchronous operation returns #DRM_FILE_WOULDBLOCK.
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_FAILURE or the file length.
 */
int32_t DRM_file_getFileLength(const uint16_t* name,
                               int32_t nameChars);

/**
 * Initializes a list iteration session.
 *
 * @param prefix Prefix that must be matched, UCS-2 encoded. *
 * @param prefixChars Number characters encoded in prefix.
 * @param session List session identifier.
 * @param iteration List iteration identifier.
 *
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE.
 */
int32_t DRM_file_listOpen(const uint16_t* prefix,
                          int32_t prefixChars,
                          int32_t* session,
                          int32_t* iteration);

/**
 * Used to fetch a list of file names that match a given name prefix.
 *
 * @param prefix See DRM_file_listOpen(). This does not change during the
 * iteration session.
 * @param prefixChars See DRM_file_listOpen(). This does not change during
 * the iteration session.
 * @param entry Buffer parameter to return the next file name that matches the
 * #prefix parameter, if any, when the function returns a positive number of
 * characters.
 * @param entryBytes Size of entry in bytes.
 * @param session See DRM_file_listOpen().
 * @param iteration See DRM_file_listOpen().
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_FAILURE or the number of
 * characters encoded in entry. Returns 0 when the end of the list is reached.
 */
int32_t DRM_file_listNextEntry(const uint16_t* prefix,
                               int32_t prefixChars,
                               uint16_t* entry,
                               int32_t entryBytes,
                               int32_t* session,
                               int32_t* iteration);

/**
 * Ends a list iteration session. Notifies the implementation
 * that the list session is over and that any session resources
 * can be released.
 *
 * @param session See DRM_file_listOpen().
 * @param iteration See DRM_file_listOpen().
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE.
 */
int32_t DRM_file_listClose(int32_t session, int32_t iteration);

/**
 * Renames a file, given its old name. The file or directory is renamed
 * immediately on the actual file system upon invocation of this method.
 * Any open handles on the file specified by oldName become invalid after
 * this method has been called.
 *
 * @param oldName Current file name (unopened), UCS-2 encoded.
 * @param oldNameChars Number of characters encoded on oldName.
 * @param newName New name for the file (unopened), UCS-2 encoded.
 * @param newNameChars Number of characters encoded on newName.
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE. In particular,
 * #DRM_FILE_FAILURE if a file or directory already exists with the new name.
 */
int32_t DRM_file_rename(const uint16_t* oldName,
                        int32_t oldNameChars,
                        const uint16_t* newName,
                        int32_t newNameChars);

/**
 * Tests if a file exists given its name.
 *
 * @param name Name of the file, UCS-2 encoded.
 * @param nameChars Number of characters encoded in name.
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_ISREG, #DRM_FILE_ISDIR, #DRM_FILE_FAILURE. If name
 * exists, returns #DRM_FILE_ISREG if it is a regular file and #DRM_FILE_ISDIR if it is a directory.
 * Returns #DRM_FILE_FAILURE in all other cases, including those where name exists but is neither
 * a regular file nor a directory. Platforms that do not support directories MUST NOT return
 * #DRM_FILE_ISDIR.
 */
int32_t DRM_file_exists(const uint16_t* name,
                        int32_t nameChars);

/**
 * Opens a file with the given name and returns its file handle.
 *
 * @param name Name of the file, UCS-2 encoded.
 * @param nameChars Number of characters encoded in name.
 * @param mode Any combination of the #DRM_FILE_MODE_READ and
 * #DRM_FILE_MODE_WRITE flags. If the file does not exist and mode contains the
 * #DRM_FILE_MODE_WRITE flag, then the file is automatically created. If the
 * file exists and the mode contains the #DRM_FILE_MODE_WRITE flag, the file is
 * opened so it can be modified, but the data is not modified by the open call.
 * In all cases the current position is set to the start of the file.
 * The following table shows how to map the mode semantics above to UNIX
 * fopen-style modes.  For brevity in the table, R=#DRM_FILE_MODE_READ,
 * W=#DRM_FILE_MODE_WRITE, E=File exists:
 * <table>
 * <tr><td>RW</td><td>E</td><td>Maps-to</td></tr>
 * <tr><td>00</td><td>0</td><td>Return #DRM_FILE_FAILURE</td></tr>
 * <tr><td>00</td><td>1</td><td>Return #DRM_FILE_FAILURE</td></tr>
 * <tr><td>01</td><td>0</td><td>Use fopen mode "w"</td></tr>
 * <tr><td>01</td><td>1</td><td>Use fopen mode "a" and fseek to the start</td></tr>
 * <tr><td>10</td><td>0</td><td>Return #DRM_FILE_FAILURE</td></tr>
 * <tr><td>10</td><td>1</td><td>Use fopen mode "r"</td></tr>
 * <tr><td>11</td><td>0</td><td>Use fopen mode "w+"</td></tr>
 * <tr><td>11</td><td>1</td><td>Use fopen mode "r+"</td></tr>
 * </table>
 * @param handle Pointer where the result handle value is placed when the function
 * is called synchronously.
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE.
 */
int32_t DRM_file_open(const uint16_t* name,
                      int32_t nameChars,
                      int32_t mode,
                      int32_t* handle);

/**
 * Deletes a file given its name, UCS-2 encoded. The file or directory is
 * deleted immediately on the actual file system upon invocation of this
 * method. Any open handles on the file specified by name become invalid
 * after this method has been called.
 *
 * If the port needs to ensure that a specific application does not exceed a given storage
 * space quota, then the bytes freed by the deletion must be added to the available space for
 * that application.
 *
 * @param name Name of the file, UCS-2 encoded.
 * @param nameChars Number of characters encoded in name.
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE.
 */
int32_t DRM_file_delete(const uint16_t* name,
                        int32_t nameChars);

/**
 * Read bytes from a file at the current position to a buffer. Afterwards the
 * new file position is the byte after the last byte read.
 * DRM_FILE_FAILURE is returned if the handle is invalid (e.g., as a
 * consquence of DRM_file_delete, DRM_file_rename, or DRM_file_close).
 *
 * @param handle File handle as returned by DRM_file_open().
 * @param dst Buffer where the data is to be copied.
 * @param length Number of bytes to be copied.
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE, #DRM_FILE_EOF
 *         or the number of bytes that were read, i.e. in the range 0..length.
 */
int32_t DRM_file_read(int32_t handle,
                      uint8_t* dst,
                      int32_t length);

/**
 * Write bytes from a buffer to the file at the current position.  If the
 * current position + number of bytes written > current size of the file,
 * then the file is grown.  Afterwards the new file position is the byte
 * after the last byte written.
 * DRM_FILE_FAILURE is returned if the handle is invalid (e.g., as a
 * consquence of DRM_file_delete, DRM_file_rename, or DRM_file_close).
 *
 * @param handle File handle as returned by DRM_file_open().
 * @param src Buffer that contains the bytes to be written.
 * @param length Number of bytes to be written.
 * If the port needs to ensure that a specific application does not exceed a given storage
 * space quota, the implementation must make sure the call does not violate that invariant.
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_FAILURE or the number of bytes
 *         that were written. This number must be in the range 0..length.
 *         Returns #DRM_FILE_FAILURE when storage is full or exceeds quota.
 */
int32_t DRM_file_write(int32_t handle,
                       const uint8_t* src,
                       int32_t length);

/**
 * Closes a file.
 * DRM_FILE_SUCCESS is returned if the handle is invalid (e.g., as a
 * consquence of DRM_file_delete or DRM_file_rename).
 *
 * @param handle File handle as returned by DRM_file_open().
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE.
 */
int32_t DRM_file_close(int32_t handle);

/**
 * Sets the current position in an opened file.
 * DRM_FILE_FAILURE is returned if the handle is invalid (e.g., as a
 * consquence of DRM_file_delete, DRM_file_rename, or DRM_file_close).
 *
 * @param handle File handle as returned by DRM_file_open().
 * @param value The new current position of the file. If value is greater
 * than the length of the file then the file should be extended. The contents
 * of the newly extended portion of the file is undefined.
 * If the port needs to ensure that a specific application does not exceed a given storage
 * space quota, the implementation must make sure the call does not violate that invariant.
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE.
 *         Returns #DRM_FILE_FAILURE when storage is full or exceeds quota.
 */
int32_t DRM_file_setPosition(int32_t handle, int32_t value);

/**
 * Creates a directory with the assigned name and full file permissions on
 * the file system. The full path to the new directory must already exist.
 * The directory is created immediately on the actual file system upon
 * invocation of this method.
 *
 * @param name Name of the directory, UCS-2 encoded.
 * @param nameChars Number of characters encoded in name.
 * @return #DRM_FILE_WOULDBLOCK, #DRM_FILE_SUCCESS, #DRM_FILE_FAILURE.
 */
int32_t DRM_file_mkdir(const uint16_t* name,
                       int32_t nameChars);

#ifdef __cplusplus
}
#endif

#endif /* __DRM_FILE_H__ */
