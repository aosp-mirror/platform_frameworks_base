/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_RESOLVER_H
#define AAPT_RESOLVER_H

#include "Maybe.h"
#include "Resource.h"
#include "ResourceValues.h"

#include <androidfw/ResourceTypes.h>

namespace aapt {

/**
 * Resolves symbolic references (package:type/entry) into resource IDs/objects.
 */
class IResolver {
public:
    virtual ~IResolver() {};

    /**
     * Holds the result of a resource name lookup.
     */
    struct Entry {
        /**
         * The ID of the resource. ResourceId::isValid() may
         * return false if the resource has not been assigned
         * an ID.
         */
        ResourceId id;

        /**
         * If the resource is an attribute, this will point
         * to a valid Attribute object, or else it will be
         * nullptr.
         */
        const Attribute* attr;
    };

    /**
     * Returns a ResourceID if the name is found. The ResourceID
     * may not be valid if the resource was not assigned an ID.
     */
    virtual Maybe<ResourceId> findId(const ResourceName& name) = 0;

    /**
     * Returns an Entry if the name is found. Entry::attr
     * may be nullptr if the resource is not an attribute.
     */
    virtual Maybe<Entry> findAttribute(const ResourceName& name) = 0;

    /**
     * Find a resource by ID. Resolvers may contain resources without
     * resource IDs assigned to them.
     */
    virtual Maybe<ResourceName> findName(ResourceId resId) = 0;
};

} // namespace aapt

#endif // AAPT_RESOLVER_H
