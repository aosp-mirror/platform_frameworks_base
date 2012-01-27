/* Copyright 2008 The Android Open Source Project
 */

#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <fcntl.h>

#include <private/android_filesystem_config.h>

#include "binder.h"

#if 0
#define ALOGI(x...) fprintf(stderr, "svcmgr: " x)
#define ALOGE(x...) fprintf(stderr, "svcmgr: " x)
#else
#define LOG_TAG "ServiceManager"
#include <cutils/log.h>
#endif

/* TODO:
 * These should come from a config file or perhaps be
 * based on some namespace rules of some sort (media
 * uid can register media.*, etc)
 */
static struct {
    unsigned uid;
    const char *name;
} allowed[] = {
    { AID_MEDIA, "media.audio_flinger" },
    { AID_MEDIA, "media.player" },
    { AID_MEDIA, "media.camera" },
    { AID_MEDIA, "media.audio_policy" },
    { AID_DRM,   "drm.drmManager" },
    { AID_NFC,   "nfc" },
    { AID_RADIO, "radio.phone" },
    { AID_RADIO, "radio.sms" },
    { AID_RADIO, "radio.phonesubinfo" },
    { AID_RADIO, "radio.simphonebook" },
/* TODO: remove after phone services are updated: */
    { AID_RADIO, "phone" },
    { AID_RADIO, "sip" },
    { AID_RADIO, "isms" },
    { AID_RADIO, "iphonesubinfo" },
    { AID_RADIO, "simphonebook" },
};

void *svcmgr_handle;

const char *str8(uint16_t *x)
{
    static char buf[128];
    unsigned max = 127;
    char *p = buf;

    if (x) {
        while (*x && max--) {
            *p++ = *x++;
        }
    }
    *p++ = 0;
    return buf;
}

int str16eq(uint16_t *a, const char *b)
{
    while (*a && *b)
        if (*a++ != *b++) return 0;
    if (*a || *b)
        return 0;
    return 1;
}

int svc_can_register(unsigned uid, uint16_t *name)
{
    unsigned n;
    
    if ((uid == 0) || (uid == AID_SYSTEM))
        return 1;

    for (n = 0; n < sizeof(allowed) / sizeof(allowed[0]); n++)
        if ((uid == allowed[n].uid) && str16eq(name, allowed[n].name))
            return 1;

    return 0;
}

struct svcinfo 
{
    struct svcinfo *next;
    void *ptr;
    struct binder_death death;
    unsigned len;
    uint16_t name[0];
};

struct svcinfo *svclist = 0;

struct svcinfo *find_svc(uint16_t *s16, unsigned len)
{
    struct svcinfo *si;

    for (si = svclist; si; si = si->next) {
        if ((len == si->len) &&
            !memcmp(s16, si->name, len * sizeof(uint16_t))) {
            return si;
        }
    }
    return 0;
}

void svcinfo_death(struct binder_state *bs, void *ptr)
{
    struct svcinfo *si = ptr;
    ALOGI("service '%s' died\n", str8(si->name));
    if (si->ptr) {
        binder_release(bs, si->ptr);
        si->ptr = 0;
    }   
}

uint16_t svcmgr_id[] = { 
    'a','n','d','r','o','i','d','.','o','s','.',
    'I','S','e','r','v','i','c','e','M','a','n','a','g','e','r' 
};
  

void *do_find_service(struct binder_state *bs, uint16_t *s, unsigned len)
{
    struct svcinfo *si;
    si = find_svc(s, len);

//    ALOGI("check_service('%s') ptr = %p\n", str8(s), si ? si->ptr : 0);
    if (si && si->ptr) {
        return si->ptr;
    } else {
        return 0;
    }
}

int do_add_service(struct binder_state *bs,
                   uint16_t *s, unsigned len,
                   void *ptr, unsigned uid)
{
    struct svcinfo *si;
//    ALOGI("add_service('%s',%p) uid=%d\n", str8(s), ptr, uid);

    if (!ptr || (len == 0) || (len > 127))
        return -1;

    if (!svc_can_register(uid, s)) {
        ALOGE("add_service('%s',%p) uid=%d - PERMISSION DENIED\n",
             str8(s), ptr, uid);
        return -1;
    }

    si = find_svc(s, len);
    if (si) {
        if (si->ptr) {
            ALOGE("add_service('%s',%p) uid=%d - ALREADY REGISTERED, OVERRIDE\n",
                 str8(s), ptr, uid);
            svcinfo_death(bs, si);
        }
        si->ptr = ptr;
    } else {
        si = malloc(sizeof(*si) + (len + 1) * sizeof(uint16_t));
        if (!si) {
            ALOGE("add_service('%s',%p) uid=%d - OUT OF MEMORY\n",
                 str8(s), ptr, uid);
            return -1;
        }
        si->ptr = ptr;
        si->len = len;
        memcpy(si->name, s, (len + 1) * sizeof(uint16_t));
        si->name[len] = '\0';
        si->death.func = svcinfo_death;
        si->death.ptr = si;
        si->next = svclist;
        svclist = si;
    }

    binder_acquire(bs, ptr);
    binder_link_to_death(bs, ptr, &si->death);
    return 0;
}

int svcmgr_handler(struct binder_state *bs,
                   struct binder_txn *txn,
                   struct binder_io *msg,
                   struct binder_io *reply)
{
    struct svcinfo *si;
    uint16_t *s;
    unsigned len;
    void *ptr;
    uint32_t strict_policy;

//    ALOGI("target=%p code=%d pid=%d uid=%d\n",
//         txn->target, txn->code, txn->sender_pid, txn->sender_euid);

    if (txn->target != svcmgr_handle)
        return -1;

    // Equivalent to Parcel::enforceInterface(), reading the RPC
    // header with the strict mode policy mask and the interface name.
    // Note that we ignore the strict_policy and don't propagate it
    // further (since we do no outbound RPCs anyway).
    strict_policy = bio_get_uint32(msg);
    s = bio_get_string16(msg, &len);
    if ((len != (sizeof(svcmgr_id) / 2)) ||
        memcmp(svcmgr_id, s, sizeof(svcmgr_id))) {
        fprintf(stderr,"invalid id %s\n", str8(s));
        return -1;
    }

    switch(txn->code) {
    case SVC_MGR_GET_SERVICE:
    case SVC_MGR_CHECK_SERVICE:
        s = bio_get_string16(msg, &len);
        ptr = do_find_service(bs, s, len);
        if (!ptr)
            break;
        bio_put_ref(reply, ptr);
        return 0;

    case SVC_MGR_ADD_SERVICE:
        s = bio_get_string16(msg, &len);
        ptr = bio_get_ref(msg);
        if (do_add_service(bs, s, len, ptr, txn->sender_euid))
            return -1;
        break;

    case SVC_MGR_LIST_SERVICES: {
        unsigned n = bio_get_uint32(msg);

        si = svclist;
        while ((n-- > 0) && si)
            si = si->next;
        if (si) {
            bio_put_string16(reply, si->name);
            return 0;
        }
        return -1;
    }
    default:
        ALOGE("unknown code %d\n", txn->code);
        return -1;
    }

    bio_put_uint32(reply, 0);
    return 0;
}

int main(int argc, char **argv)
{
    struct binder_state *bs;
    void *svcmgr = BINDER_SERVICE_MANAGER;

    bs = binder_open(128*1024);

    if (binder_become_context_manager(bs)) {
        ALOGE("cannot become context manager (%s)\n", strerror(errno));
        return -1;
    }

    svcmgr_handle = svcmgr;
    binder_loop(bs, svcmgr_handler);
    return 0;
}
