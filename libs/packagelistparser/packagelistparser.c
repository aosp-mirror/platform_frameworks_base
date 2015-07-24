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
 */

#include <errno.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <sys/limits.h>

#define LOG_TAG "packagelistparser"
#include <utils/Log.h>

#include "packagelistparser.h"

#define CLOGE(fmt, ...) \
    do {\
        IF_ALOGE() {\
            ALOGE(fmt, ##__VA_ARGS__);\
        }\
    } while(0)

static size_t get_gid_cnt(const char *gids)
{
    size_t cnt;

    if (*gids == '\0') {
        return 0;
    }

    if (!strcmp(gids, "none")) {
        return 0;
    }

    for (cnt = 1; gids[cnt]; gids[cnt] == ',' ? cnt++ : *gids++)
        ;

    return cnt;
}

static bool parse_gids(char *gids, gid_t *gid_list, size_t *cnt)
{
    gid_t gid;
    char* token;
    char *endptr;
    size_t cmp = 0;

    while ((token = strsep(&gids, ",\r\n"))) {

        if (cmp > *cnt) {
            return false;
        }

        gid = strtoul(token, &endptr, 10);
        if (*endptr != '\0') {
            return false;
        }

        /*
         * if unsigned long is greater than size of gid_t,
         * prevent a truncation based roll-over
         */
        if (gid > GID_MAX) {
            CLOGE("A gid in field \"gid list\" greater than GID_MAX");
            return false;
        }

        gid_list[cmp++] = gid;
    }
    return true;
}

extern bool packagelist_parse(pfn_on_package callback, void *userdata)
{

    FILE *fp;
    char *cur;
    char *next;
    char *endptr;
    unsigned long tmp;
    ssize_t bytesread;

    bool rc = false;
    char *buf = NULL;
    size_t buflen = 0;
    unsigned long lineno = 1;
    const char *errmsg = NULL;
    struct pkg_info *pkg_info = NULL;

    fp = fopen(PACKAGES_LIST_FILE, "re");
    if (!fp) {
        CLOGE("Could not open: \"%s\", error: \"%s\"\n", PACKAGES_LIST_FILE,
                strerror(errno));
        return false;
    }

    while ((bytesread = getline(&buf, &buflen, fp)) > 0) {

        pkg_info = calloc(1, sizeof(*pkg_info));
        if (!pkg_info) {
            goto err;
        }

        next = buf;

        cur = strsep(&next, " \t\r\n");
        if (!cur) {
            errmsg = "Could not get next token for \"package name\"";
            goto err;
        }

        pkg_info->name = strdup(cur);
        if (!pkg_info->name) {
            goto err;
        }

        cur = strsep(&next, " \t\r\n");
        if (!cur) {
            errmsg = "Could not get next token for field \"uid\"";
            goto err;
        }

        tmp = strtoul(cur, &endptr, 10);
        if (*endptr != '\0') {
            errmsg = "Could not convert field \"uid\" to integer value";
            goto err;
        }

        /*
         * if unsigned long is greater than size of uid_t,
         * prevent a truncation based roll-over
         */
        if (tmp > UID_MAX) {
            errmsg = "Field \"uid\" greater than UID_MAX";
            goto err;
        }

        pkg_info->uid = (uid_t) tmp;

        cur = strsep(&next, " \t\r\n");
        if (!cur) {
            errmsg = "Could not get next token for field \"debuggable\"";
            goto err;
        }

        tmp = strtoul(cur, &endptr, 10);
        if (*endptr != '\0') {
            errmsg = "Could not convert field \"debuggable\" to integer value";
            goto err;
        }

        /* should be a valid boolean of 1 or 0 */
        if (!(tmp == 0 || tmp == 1)) {
            errmsg = "Field \"debuggable\" is not 0 or 1 boolean value";
            goto err;
        }

        pkg_info->debuggable = (bool) tmp;

        cur = strsep(&next, " \t\r\n");
        if (!cur) {
            errmsg = "Could not get next token for field \"data dir\"";
            goto err;
        }

        pkg_info->data_dir = strdup(cur);
        if (!pkg_info->data_dir) {
            goto err;
        }

        cur = strsep(&next, " \t\r\n");
        if (!cur) {
            errmsg = "Could not get next token for field \"seinfo\"";
            goto err;
        }

        pkg_info->seinfo = strdup(cur);
        if (!pkg_info->seinfo) {
            goto err;
        }

        cur = strsep(&next, " \t\r\n");
        if (!cur) {
            errmsg = "Could not get next token for field \"gid(s)\"";
            goto err;
        }

        /*
         * Parse the gid list, could be in the form of none, single gid or list:
         * none
         * gid
         * gid, gid ...
         */
        pkg_info->gids.cnt = get_gid_cnt(cur);
        if (pkg_info->gids.cnt > 0) {

            pkg_info->gids.gids = calloc(pkg_info->gids.cnt, sizeof(gid_t));
            if (!pkg_info->gids.gids) {
                goto err;
            }

            rc = parse_gids(cur, pkg_info->gids.gids, &pkg_info->gids.cnt);
            if (!rc) {
                errmsg = "Could not parse field \"gid list\"";
                goto err;
            }
        }

        rc = callback(pkg_info, userdata);
        if (rc == false) {
            /*
             * We do not log this as this can be intentional from
             * callback to abort processing. We go to out to not
             * free the pkg_info
             */
            rc = true;
            goto out;
        }
        lineno++;
    }

    rc = true;

out:
    free(buf);
    fclose(fp);
    return rc;

err:
    if (errmsg) {
        CLOGE("Error Parsing \"%s\" on line: %lu for reason: %s",
                PACKAGES_LIST_FILE, lineno, errmsg);
    }
    rc = false;
    packagelist_free(pkg_info);
    goto out;
}

void packagelist_free(pkg_info *info)
{
    if (info) {
        free(info->name);
        free(info->data_dir);
        free(info->seinfo);
        free(info->gids.gids);
        free(info);
    }
}
