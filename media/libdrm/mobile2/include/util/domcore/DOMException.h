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
#ifndef __DOM_EXCEPTION__
#define __DOM_EXCEPTION__

#include <uexception.h>
using namespace ustl;

/**
 * DOM operations only raise exceptions in "exceptional" circumstances, i.e.,
 * when an operation is impossible to perform (either for logical reasons, because data is lost,
 * or because the implementation has become unstable). In general, DOM methods return specific error
 * values in ordinary processing situations, such as out-of-bound errors when using <code>NodeList</code>.
 * <p>Implementations should raise other exceptions under other circumstances. For example, implementations
 * should raise an implementation-dependent exception if a null argument is passed.
 * Some languages and object systems do not support the concept of exceptions.
 * For such systems, error conditions may be indicated using native error reporting mechanisms.
 * For some bindings, for example, methods may return error codes similar to those listed in the corresponding
 * method descriptions.
 */
class DOMException : public exception {

 private:
        short code;
 public:
        DOMException(short code)
        {
            this->code = code;
        }
 public:

        enum ExceptionReason {
           /**
            * If index or size is negative, or greater than the allowed value
            */
            INDEX_SIZE_ERR = 1,

            /**
             * If the specified range of text does not fit into a DOMString
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            DOMSTRING_SIZE_ERR = 2,
            /**
             * If any node is inserted somewhere it doesn't belong
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            HIERARCHY_REQUEST_ERR = 3,

            /**
             * If a node is used in a different document than the one that created it
             * (that doesn't support it)
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            WRONG_DOCUMENT_ERR = 4,

            /**
             * If an invalid or illegal character is specified, such as in a name. See
             * production 2 in the XML specification for the definition of a legal
             * character, and production 5 for the definition of a legal name
             * character.
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            INVALID_CHARACTER_ERR = 5,

            /**
             * If data is specified for a node which does not support data
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            NO_DATA_ALLOWED_ERR = 6,

            /**
             * If an attempt is made to modify an object where modifications are not
             * allowed
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            NO_MODIFICATION_ALLOWED_ERR = 7,

            /**
             * If an attempt is made to reference a node in a context where it does
             * not exist
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            NOT_FOUND_ERR = 8,

            /**
             * If the implementation does not support the requested type of object or
             * operation.
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            NOT_SUPPORTED_ERR = 9,

            /**
             * If an attempt is made to add an attribute that is already in use
             * elsewhere
             */
            INUSE_ATTRIBUTE_ERR = 10,

            /**
             * If an attempt is made to use an object that is not, or is no longer,
             * usable.
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            INVALID_STATE_ERR = 11,

            /**
             * If an invalid or illegal string is specified.
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            SYNTAX_ERR = 12,

            /**
             * If an attempt is made to modify the type of the underlying object.
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            INVALID_MODIFICATION_ERR = 13,

            /**
             * If an attempt is made to create or change an object in a way which is
             * incorrect with regard to namespaces.
             * @see http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            NAMESPACE_ERR = 14,

            /**
             * If a parameter or an operation is not supported by the underlying
             * object.
             * @since http://www.w3.org/TR/2000/REC-DOM-Level-2-Core-20001113/core.html
             */
            INVALID_ACCESS_ERR = 15,
        };
public:
        /**
         * Return the exception reason.
         * @return the exception reason.
         */
        short getCode() const
        {
            return code;
        }
};
#endif /*__DOMEXCEPTION__*/

