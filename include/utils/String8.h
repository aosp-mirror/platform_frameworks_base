/*
 * Copyright (C) 2005 The Android Open Source Project
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

#ifndef ANDROID_STRING8_H
#define ANDROID_STRING8_H

#include <utils/Errors.h>

// Need this for the char16_t type; String8.h should not
// be depedent on the String16 class.
#include <utils/String16.h>

#include <stdint.h>
#include <string.h>
#include <sys/types.h>

// ---------------------------------------------------------------------------

extern "C" {

typedef uint32_t char32_t;

size_t strlen32(const char32_t *);
size_t strnlen32(const char32_t *, size_t);

/*
 * Returns the length of "src" when "src" is valid UTF-8 string.
 * Returns 0 if src is NULL, 0-length string or non UTF-8 string.
 * This function should be used to determine whether "src" is valid UTF-8
 * characters with valid unicode codepoints. "src" must be null-terminated.
 *
 * If you are going to use other GetUtf... functions defined in this header
 * with string which may not be valid UTF-8 with valid codepoint (form 0 to
 * 0x10FFFF), you should use this function before calling others, since the
 * other functions do not check whether the string is valid UTF-8 or not.
 *
 * If you do not care whether "src" is valid UTF-8 or not, you should use
 * strlen() as usual, which should be much faster.
 */
size_t utf8_length(const char *src);

/*
 * Returns the UTF-32 length of "src".
 */
size_t utf32_length(const char *src, size_t src_len);

/*
 * Returns the UTF-8 length of "src".
 */
size_t utf8_length_from_utf16(const char16_t *src, size_t src_len);

/*
 * Returns the UTF-8 length of "src".
 */
size_t utf8_length_from_utf32(const char32_t *src, size_t src_len);

/*
 * Returns the unicode value at "index".
 * Returns -1 when the index is invalid (equals to or more than "src_len").
 * If returned value is positive, it is able to be converted to char32_t, which
 * is unsigned. Then, if "next_index" is not NULL, the next index to be used is
 * stored in "next_index". "next_index" can be NULL.
 */
int32_t utf32_at(const char *src, size_t src_len,
                 size_t index, size_t *next_index);

/*
 * Stores a UTF-32 string converted from "src" in "dst", if "dst_length" is not
 * large enough to store the string, the part of the "src" string is stored
 * into "dst".
 * Returns the size actually used for storing the string.
 * "dst" is not null-terminated when dst_len is fully used (like strncpy).
 */
size_t utf8_to_utf32(const char* src, size_t src_len,
                     char32_t* dst, size_t dst_len);

/*
 * Stores a UTF-8 string converted from "src" in "dst", if "dst_length" is not
 * large enough to store the string, the part of the "src" string is stored
 * into "dst" as much as possible. See the examples for more detail.
 * Returns the size actually used for storing the string.
 * dst" is not null-terminated when dst_len is fully used (like strncpy).
 *
 * Example 1
 * "src" == \u3042\u3044 (\xE3\x81\x82\xE3\x81\x84)
 * "src_len" == 2
 * "dst_len" >= 7
 * ->
 * Returned value == 6
 * "dst" becomes \xE3\x81\x82\xE3\x81\x84\0
 * (note that "dst" is null-terminated)
 *
 * Example 2
 * "src" == \u3042\u3044 (\xE3\x81\x82\xE3\x81\x84)
 * "src_len" == 2
 * "dst_len" == 5
 * ->
 * Returned value == 3
 * "dst" becomes \xE3\x81\x82\0
 * (note that "dst" is null-terminated, but \u3044 is not stored in "dst"
 * since "dst" does not have enough size to store the character)
 *
 * Example 3
 * "src" == \u3042\u3044 (\xE3\x81\x82\xE3\x81\x84)
 * "src_len" == 2
 * "dst_len" == 6
 * ->
 * Returned value == 6
 * "dst" becomes \xE3\x81\x82\xE3\x81\x84
 * (note that "dst" is NOT null-terminated, like strncpy)
 */
size_t utf32_to_utf8(const char32_t* src, size_t src_len,
                     char* dst, size_t dst_len);

size_t utf16_to_utf8(const char16_t* src, size_t src_len,
                     char* dst, size_t dst_len);

}

// ---------------------------------------------------------------------------

namespace android {

class TextOutput;

//! This is a string holding UTF-8 characters. Does not allow the value more
// than 0x10FFFF, which is not valid unicode codepoint.
class String8
{
public:
                                String8();
                                String8(const String8& o);
    explicit                    String8(const char* o);
    explicit                    String8(const char* o, size_t numChars);
    
    explicit                    String8(const String16& o);
    explicit                    String8(const char16_t* o);
    explicit                    String8(const char16_t* o, size_t numChars);
    explicit                    String8(const char32_t* o);
    explicit                    String8(const char32_t* o, size_t numChars);
                                ~String8();
    
    inline  const char*         string() const;
    inline  size_t              size() const;
    inline  size_t              length() const;
    inline  size_t              bytes() const;
    
    inline  const SharedBuffer* sharedBuffer() const;
    
            void                setTo(const String8& other);
            status_t            setTo(const char* other);
            status_t            setTo(const char* other, size_t numChars);
            status_t            setTo(const char16_t* other, size_t numChars);
            status_t            setTo(const char32_t* other,
                                      size_t length);

            status_t            append(const String8& other);
            status_t            append(const char* other);
            status_t            append(const char* other, size_t numChars);

            status_t            appendFormat(const char* fmt, ...)
                    __attribute__((format (printf, 2, 3)));

            // Note that this function takes O(N) time to calculate the value.
            // No cache value is stored.
            size_t              getUtf32Length() const;
            int32_t             getUtf32At(size_t index,
                                           size_t *next_index) const;
            size_t              getUtf32(char32_t* dst, size_t dst_len) const;

    inline  String8&            operator=(const String8& other);
    inline  String8&            operator=(const char* other);
    
    inline  String8&            operator+=(const String8& other);
    inline  String8             operator+(const String8& other) const;
    
    inline  String8&            operator+=(const char* other);
    inline  String8             operator+(const char* other) const;

    inline  int                 compare(const String8& other) const;

    inline  bool                operator<(const String8& other) const;
    inline  bool                operator<=(const String8& other) const;
    inline  bool                operator==(const String8& other) const;
    inline  bool                operator!=(const String8& other) const;
    inline  bool                operator>=(const String8& other) const;
    inline  bool                operator>(const String8& other) const;
    
    inline  bool                operator<(const char* other) const;
    inline  bool                operator<=(const char* other) const;
    inline  bool                operator==(const char* other) const;
    inline  bool                operator!=(const char* other) const;
    inline  bool                operator>=(const char* other) const;
    inline  bool                operator>(const char* other) const;
    
    inline                      operator const char*() const;
    
            char*               lockBuffer(size_t size);
            void                unlockBuffer();
            status_t            unlockBuffer(size_t size);
            
            // return the index of the first byte of other in this at or after
            // start, or -1 if not found
            ssize_t             find(const char* other, size_t start = 0) const;

            void                toLower();
            void                toLower(size_t start, size_t numChars);
            void                toUpper();
            void                toUpper(size_t start, size_t numChars);

    /*
     * These methods operate on the string as if it were a path name.
     */

    /*
     * Set the filename field to a specific value.
     *
     * Normalizes the filename, removing a trailing '/' if present.
     */
    void setPathName(const char* name);
    void setPathName(const char* name, size_t numChars);

    /*
     * Get just the filename component.
     *
     * "/tmp/foo/bar.c" --> "bar.c"
     */
    String8 getPathLeaf(void) const;

    /*
     * Remove the last (file name) component, leaving just the directory
     * name.
     *
     * "/tmp/foo/bar.c" --> "/tmp/foo"
     * "/tmp" --> "" // ????? shouldn't this be "/" ???? XXX
     * "bar.c" --> ""
     */
    String8 getPathDir(void) const;

    /*
     * Retrieve the front (root dir) component.  Optionally also return the
     * remaining components.
     *
     * "/tmp/foo/bar.c" --> "tmp" (remain = "foo/bar.c")
     * "/tmp" --> "tmp" (remain = "")
     * "bar.c" --> "bar.c" (remain = "")
     */
    String8 walkPath(String8* outRemains = NULL) const;

    /*
     * Return the filename extension.  This is the last '.' and up to
     * four characters that follow it.  The '.' is included in case we
     * decide to expand our definition of what constitutes an extension.
     *
     * "/tmp/foo/bar.c" --> ".c"
     * "/tmp" --> ""
     * "/tmp/foo.bar/baz" --> ""
     * "foo.jpeg" --> ".jpeg"
     * "foo." --> ""
     */
    String8 getPathExtension(void) const;

    /*
     * Return the path without the extension.  Rules for what constitutes
     * an extension are described in the comment for getPathExtension().
     *
     * "/tmp/foo/bar.c" --> "/tmp/foo/bar"
     */
    String8 getBasePath(void) const;

    /*
     * Add a component to the pathname.  We guarantee that there is
     * exactly one path separator between the old path and the new.
     * If there is no existing name, we just copy the new name in.
     *
     * If leaf is a fully qualified path (i.e. starts with '/', it
     * replaces whatever was there before.
     */
    String8& appendPath(const char* leaf);
    String8& appendPath(const String8& leaf)  { return appendPath(leaf.string()); }

    /*
     * Like appendPath(), but does not affect this string.  Returns a new one instead.
     */
    String8 appendPathCopy(const char* leaf) const
                                             { String8 p(*this); p.appendPath(leaf); return p; }
    String8 appendPathCopy(const String8& leaf) const { return appendPathCopy(leaf.string()); }

    /*
     * Converts all separators in this string to /, the default path separator.
     *
     * If the default OS separator is backslash, this converts all
     * backslashes to slashes, in-place. Otherwise it does nothing.
     * Returns self.
     */
    String8& convertToResPath();

private:
            status_t            real_append(const char* other, size_t numChars);
            char*               find_extension(void) const;

            const char* mString;
};

TextOutput& operator<<(TextOutput& to, const String16& val);

// ---------------------------------------------------------------------------
// No user servicable parts below.

inline int compare_type(const String8& lhs, const String8& rhs)
{
    return lhs.compare(rhs);
}

inline int strictly_order_type(const String8& lhs, const String8& rhs)
{
    return compare_type(lhs, rhs) < 0;
}

inline const char* String8::string() const
{
    return mString;
}

inline size_t String8::length() const
{
    return SharedBuffer::sizeFromData(mString)-1;
}

inline size_t String8::size() const
{
    return length();
}

inline size_t String8::bytes() const
{
    return SharedBuffer::sizeFromData(mString)-1;
}

inline const SharedBuffer* String8::sharedBuffer() const
{
    return SharedBuffer::bufferFromData(mString);
}

inline String8& String8::operator=(const String8& other)
{
    setTo(other);
    return *this;
}

inline String8& String8::operator=(const char* other)
{
    setTo(other);
    return *this;
}

inline String8& String8::operator+=(const String8& other)
{
    append(other);
    return *this;
}

inline String8 String8::operator+(const String8& other) const
{
    String8 tmp(*this);
    tmp += other;
    return tmp;
}

inline String8& String8::operator+=(const char* other)
{
    append(other);
    return *this;
}

inline String8 String8::operator+(const char* other) const
{
    String8 tmp(*this);
    tmp += other;
    return tmp;
}

inline int String8::compare(const String8& other) const
{
    return strcmp(mString, other.mString);
}

inline bool String8::operator<(const String8& other) const
{
    return strcmp(mString, other.mString) < 0;
}

inline bool String8::operator<=(const String8& other) const
{
    return strcmp(mString, other.mString) <= 0;
}

inline bool String8::operator==(const String8& other) const
{
    return strcmp(mString, other.mString) == 0;
}

inline bool String8::operator!=(const String8& other) const
{
    return strcmp(mString, other.mString) != 0;
}

inline bool String8::operator>=(const String8& other) const
{
    return strcmp(mString, other.mString) >= 0;
}

inline bool String8::operator>(const String8& other) const
{
    return strcmp(mString, other.mString) > 0;
}

inline bool String8::operator<(const char* other) const
{
    return strcmp(mString, other) < 0;
}

inline bool String8::operator<=(const char* other) const
{
    return strcmp(mString, other) <= 0;
}

inline bool String8::operator==(const char* other) const
{
    return strcmp(mString, other) == 0;
}

inline bool String8::operator!=(const char* other) const
{
    return strcmp(mString, other) != 0;
}

inline bool String8::operator>=(const char* other) const
{
    return strcmp(mString, other) >= 0;
}

inline bool String8::operator>(const char* other) const
{
    return strcmp(mString, other) > 0;
}

inline String8::operator const char*() const
{
    return mString;
}

}  // namespace android

// ---------------------------------------------------------------------------

#endif // ANDROID_STRING8_H
