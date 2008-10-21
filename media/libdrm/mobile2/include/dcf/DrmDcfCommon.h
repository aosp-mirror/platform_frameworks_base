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

#ifndef __DCF_COMM_H__
#define __DCF_COMM_H__

#include <Drm2CommonTypes.h>
#include <arpa/inet.h>
#include <mistream.h>
#include <ustring.h>

using namespace ustl;

////DCF box type list
const uint32_t DCF_CONTAINER_BOX = uint32_t('m' << 24 | 'r' << 16 | 'd' << 8 | 'o');
const uint32_t DCF_USER_TYPE = uint32_t('d' << 24 | 'i' << 16 | 'u' << 8 | 'u');
const uint32_t DCF_FILE_TYPE = uint32_t('p' << 24 | 'y' << 16 | 't' << 8 | 'f');
const uint32_t DCF_FILE_BRAND = uint32_t('f' << 24 | 'c' << 16 | 'd' << 8 | 'o');


/**
 * The basic box class.
 */
class Box
{
public:
    /**
     * constructor for Box, used to parse Box
     * \param box  Box data
     */
    Box(const uint8_t* box);

    /**
     * copy constructor for Box
     * \param dcfBox  Box object used to init a new Box object
     */
    Box(const Box& dcfBox);

    /**
     * assignment operator for Box
     * \param other  Box object used to assign to a exist Box object
     */
    Box& operator=(const Box& other);

    /** Destructor for Box */
    virtual ~Box();

    /**
     * get the size of Box
     * \param none
     * \return
     *   the size
     */
    uint64_t getSize(void) const;

    /**
     * get the type of Box
     * \param none
     * \return
     *   the type
     */
    uint32_t getType(void) const;

    /**
     * get the user type of Box
     * \param none
     * \return
     *   the user type
     */
    const uint8_t* getUsertype(void) const;

    /**
     * get the length of Box
     * \param none
     * \return
     *   the length
     */
    virtual uint32_t getLen(void) const;
PRIVATE:
    static const uint32_t USER_TYPE_LEN = 16;

    uint32_t mSize;
    uint32_t mType;
    uint64_t mLargeSize;
    uint8_t* mUserType;
    uint32_t mBoxLength;
};

/**
 * The fullBox class.
 */
class FullBox : public Box
{
public:
    /**
     * constructor for FullBox, used to parse FullBox
     * \param fullBox  FullBox data
     */
    FullBox(const uint8_t* fullBox);

    /** Destructor for FullBox */
    virtual ~FullBox(){}

    /**
     * get the version of FullBox
     * \param none
     * \return
     *   the version
     */
    uint8_t getVersion(void) const;

    /**
     * get the flag of FullBox
     * \param none
     * \return
     *   the flag
     */
    const uint8_t* getFlag(void) const;

    /**
     * get the length of FullBox
     * \param none
     * \return
     *   the length
     */
    virtual uint32_t getLen(void) const;
PRIVATE:
    static const uint32_t FLAG_LEN = 3;

    uint8_t mVersion;
    uint8_t mFlag[FLAG_LEN];
    uint32_t mFullBoxLength;
};

////// textal header class
class TextualHeader
{
public:
    /** default constructor of DrmInStream */
    TextualHeader(){};

    /**
     * constructor for TextualHeader, used to parse textal header
     * \param inData  textal header data
     */
    TextualHeader(const string& inData);

    /**
     * get the name of textal header
     * \param none
     * \return
     *   the name
     */
    string getName() const;

    /**
     * get the value of textal header
     * \param none
     * \return
     *   the value
     */
    string getValue() const;

    /**
     * get the parameter of textal header
     * \param none
     * \return
     *   the parameter
     */
    string getParam() const;
PRIVATE:
    string name;
    string value;
    string param;
};

extern int64_t ntoh_int64(int64_t in);

#endif
