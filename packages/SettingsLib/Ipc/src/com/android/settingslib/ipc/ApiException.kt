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

package com.android.settingslib.ipc

/** Exception raised when handle request. */
sealed class ApiException : Exception {
    constructor() : super()

    constructor(cause: Throwable?) : super(cause)

    constructor(message: String, cause: Throwable?) : super(message, cause)
}

/** Exception occurred on client side. */
open class ApiClientException : ApiException {
    constructor() : super()

    constructor(cause: Throwable?) : super(cause)

    constructor(message: String, cause: Throwable?) : super(message, cause)
}

/** Client has already been closed. */
class ClientClosedException : ApiClientException()

/** Api to request is invalid, e.g. negative identity number. */
class ClientInvalidApiException(message: String) : ApiClientException(message, null)

/**
 * Exception when bind service failed.
 *
 * This exception may be raised for following reasons:
 * - Context used to bind service has finished its lifecycle (e.g. activity stopped).
 * - Service not found.
 * - Permission denied.
 */
class ClientBindServiceException(cause: Throwable?) : ApiClientException(cause)

/** Exception when encode request. */
class ClientEncodeException(cause: Throwable) : ApiClientException(cause)

/** Exception when decode response. */
class ClientDecodeException(cause: Throwable) : ApiClientException(cause)

/** Exception when send message. */
class ClientSendException(message: String, cause: Throwable) : ApiClientException(message, cause)

/** Service returns unknown error code. */
class ClientUnknownResponseCodeException(code: Int) :
    ApiClientException("Unknown code: $code", null)

/** Exception returned from service. */
open class ApiServiceException : ApiException() {
    companion object {
        internal const val CODE_OK = 0
        internal const val CODE_PERMISSION_DENIED = 1
        internal const val CODE_UNKNOWN_API = 2
        internal const val CODE_INTERNAL_ERROR = 3

        internal fun of(code: Int): ApiServiceException? =
            when (code) {
                CODE_PERMISSION_DENIED -> ServicePermissionDeniedException()
                CODE_UNKNOWN_API -> ServiceUnknownApiException()
                CODE_INTERNAL_ERROR -> ServiceInternalException()
                else -> null
            }
    }
}

/** Exception indicates the request is rejected due to permission deny. */
class ServicePermissionDeniedException : ApiServiceException()

/** Exception indicates API request is unknown. */
class ServiceUnknownApiException : ApiServiceException()

/** Exception indicates internal issue occurred when service handles the request. */
class ServiceInternalException : ApiServiceException()
