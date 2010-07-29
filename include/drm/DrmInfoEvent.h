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

#ifndef __DRM_INFO_EVENT_H__
#define __DRM_INFO_EVENT_H__

namespace android {

class String8;

/**
 * This is an entity class which would be passed to caller in
 * DrmManagerClient::OnInfoListener::onInfo(const DrmInfoEvent&).
 */
class DrmInfoEvent {
public:
    //! TYPE_ALREADY_REGISTERED_BY_ANOTHER_ACCOUNT, when registration has been
    //! already done by another account ID.
    static const int TYPE_ALREADY_REGISTERED_BY_ANOTHER_ACCOUNT = 0x0000001;
    //! TYPE_REMOVE_RIGHTS, when the rights needs to be removed completely.
    static const int TYPE_REMOVE_RIGHTS = 0x0000002;
    //! TYPE_RIGHTS_INSTALLED, when the rights are downloaded and installed ok.
    static const int TYPE_RIGHTS_INSTALLED = 0x0000003;
    //! TYPE_RIGHTS_NOT_INSTALLED, when something went wrong installing the rights
    static const int TYPE_RIGHTS_NOT_INSTALLED = 0x0000004;
    //! TYPE_RIGHTS_RENEWAL_NOT_ALLOWED, when the server rejects renewal of rights
    static const int TYPE_RIGHTS_RENEWAL_NOT_ALLOWED = 0x0000005;
    //! TYPE_NOT_SUPPORTED, when answer from server can not be handled by the native agent
    static const int TYPE_NOT_SUPPORTED = 0x0000006;
    //! TYPE_WAIT_FOR_RIGHTS, rights object is on it's way to phone,
    //! wait before calling checkRights again
    static const int TYPE_WAIT_FOR_RIGHTS = 0x0000007;
    //! TYPE_OUT_OF_MEMORY, when memory allocation fail during renewal.
    //! Can in the future perhaps be used to trigger garbage collector
    static const int TYPE_OUT_OF_MEMORY = 0x0000008;
    //! TYPE_NO_INTERNET_CONNECTION, when the Internet connection is missing and no attempt
    //! can be made to renew rights
    static const int TYPE_NO_INTERNET_CONNECTION = 0x0000009;

public:
    /**
     * Constructor for DrmInfoEvent
     *
     * @param[in] uniqueId Unique session identifier
     * @param[in] infoType Type of information
     * @param[in] message Message description
     */
    DrmInfoEvent(int uniqueId, int infoType, const String8& message);

    /**
     * Destructor for DrmInfoEvent
     */
    virtual ~DrmInfoEvent() {}

public:
    /**
     * Returns the Unique Id associated with this instance
     *
     * @return Unique Id
     */
    int getUniqueId() const;

    /**
     * Returns the Type of information associated with this object
     *
     * @return Type of information
     */
    int getType() const;

    /**
     * Returns the message description associated with this object
     *
     * @return Message description
     */
    const String8& getMessage() const;

private:
    int mUniqueId;
    int mInfoType;
    const String8& mMessage;
};

};

#endif /* __DRM_INFO_EVENT_H__ */

