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

#ifndef __DCF_CONTAINER_H__
#define __DCF_CONTAINER_H__

#include <Drm2CommonTypes.h>
#include <uvector.h>
#include <dcf/DrmIStream.h>
#include <dcf/DrmDcfCommon.h>

class DrmInStream;

/////////dcf container
class DcfContainer : public FullBox
{
public:
    friend class DrmInStream;

    /** default constructor of DcfContainer */
    DcfContainer(istream& inRawData):FullBox(NULL),mConStream(inRawData){}

    /**
     * constructor for DcfContainer, used to parse DCF container
     * \param data  DCF container data
     * \param len   DCF container data len
     * \param off   the offset from the start of DCF container
     */
    DcfContainer(const uint8_t* data,istream& inRawData,uint64_t conOff);

    /** Destructor for DcfContainer */
    ~DcfContainer();

    /**
     * get the content type of one content
     * \param none
     * \return
     *   the content type
     */
    string getContentType(void) const;

    /**
     * get the encryption method apply to content
     * \param none
     * \return
     *   the encryption method
     */
    uint8_t getEncryptionMethod(void) const;

    /**
     * get the padding scheme apply to content
     * \param none
     * \return
     *   the padding scheme
     */
    uint8_t getPaddingScheme(void) const;

    /**
     * get the length of plain content
     * \param none
     * \return
     *   the length of plain content
     */
    uint64_t getPlaintextLength(void) const;

    /**
     * get the length of content ID
     * \param none
     * \return
     *   the length of content ID
     */
    uint16_t getContentIDLength(void) const;

    /**
     * get the length of rights issuer URL
     * \param none
     * \return
     *   the length of rights issuer URL
     */
    uint16_t getRightsIssuerURLLength(void) const;

    /**
     * get the length of textal header
     * \param none
     * \return
     *   the length of textal header
     */
    uint16_t getTextualHeadersLength(void) const;

    /**
     * get the content ID of one content
     * \param none
     * \return
     *   the content ID
     */
    string getContentID(void) const;

    /**
     * get the rights issuer URL
     * \param none
     * \return
     *   the rights issuer URL
     */
    string getRightsIssuerURL(void) const;

    /**
     * get the preview method
     * \param none
     * \return
     *   the preview method
     */
    string getPreviewMethod(void) const;

    /**
     * get the location of content
     * \param none
     * \return
     *   the location of content
     */
    string getContentLocation(void) const;

    /**
     * get the URL of content
     * \param none
     * \return
     *   the URL of content
     */
    string getContentURL(void) const;

    /**
     * get the customer head
     * \param none
     * \return
     *   the customer head
     */
    vector<string> getCustomerHead(void) const;

    /**
     * get the preview element data
     * \param none
     * \return
     *   the DRM Instream of preview element data
     */
    DrmInStream getPreviewElementData(void) const;

    /**
     * get the plain content
     * \param none
     * \return
     *   the DRM Instream of plain content
     */
    DrmInStream getDecryptContent(uint8_t* decryptKey) const;

    /**
     * get the istream of DCF
     * \param none
     * \return
     *   the istream of DCF
     */
    istream& getStream(void) const;

PRIVATE:
    static const uint32_t USER_DATA_FLAG = 0x01;

    uint8_t mContentTypeLen;
    string mContentType;
    uint8_t mEncryptionMethod;
    uint8_t mPaddingScheme;
    uint64_t mPlaintextLength;
    uint16_t mContentIDLength;
    uint16_t mRightsIssuerURLLength;
    uint16_t mTextualHeadersLength;
    string mContentID;
    string mRightsIssuerURL;
    vector<TextualHeader*> mTextualHeaders;
    bool mSilentFirst;
    string mSlientMethod;
    string mSilentRightsURL;
    string mPreviewMethod;
    string mPreviewElementURI;
    string mPreviewRightsURL;
    string mContentURL;
    string mContentVersion;
    string mContentLocation;
    vector<string> mCustomHeader;
    bool mHasUserData;
    uint64_t mDataLen;
    istream& mConStream;
    uint64_t mDecOffset;

PRIVATE:
    // parse text header
    bool parseTextualHeaders(const uint8_t* data, uint32_t len);
    void copy(const DcfContainer& container);
    DcfContainer(const DcfContainer& container):FullBox(NULL),mConStream(container.mConStream){}
    DcfContainer& operator=(const DcfContainer& other){return *this;}
};


#endif
