/*
 * Copyright 2015, Intel Corporation
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Written by William Roberts <william.c.roberts@intel.com>
 *
 * This is a parser library for parsing the packages.list file generated
 * by PackageManager service.
 *
 * This simple parser is sensitive to format changes in
 * frameworks/base/services/core/java/com/android/server/pm/Settings.java
 * A dependency note has been added to that file to correct
 * this parser.
 */

#ifndef PACKAGELISTPARSER_H_
#define PACKAGELISTPARSER_H_

#include <stdbool.h>
#include <sys/cdefs.h>
#include <sys/types.h>

__BEGIN_DECLS

/** The file containing the list of installed packages on the system */
#define PACKAGES_LIST_FILE  "/data/system/packages.list"

typedef struct pkg_info pkg_info;
typedef struct gid_list gid_list;

struct gid_list {
    size_t cnt;
    gid_t *gids;
};

struct pkg_info {
    char *name;
    uid_t uid;
    bool debuggable;
    char *data_dir;
    char *seinfo;
    gid_list gids;
    void *private_data;
};

/**
 * Callback function to be used by packagelist_parse() routine.
 * @param info
 *  The parsed package information
 * @param userdata
 *  The supplied userdata pointer to packagelist_parse()
 * @return
 *  true to keep processing, false to stop.
 */
typedef bool (*pfn_on_package)(pkg_info *info, void *userdata);

/**
 * Parses the file specified by PACKAGES_LIST_FILE and invokes the callback on
 * each entry found. Once the callback is invoked, ownership of the pkg_info pointer
 * is passed to the callback routine, thus they are required to perform any cleanup
 * desired.
 * @param callback
 *  The callback function called on each parsed line of the packages list.
 * @param userdata
 *  An optional userdata supplied pointer to pass to the callback function.
 * @return
 *  true on success false on failure.
 */
extern bool packagelist_parse(pfn_on_package callback, void *userdata);

/**
 * Frees a pkg_info structure.
 * @param info
 *  The struct to free
 */
extern void packagelist_free(pkg_info *info);

__END_DECLS

#endif /* PACKAGELISTPARSER_H_ */
