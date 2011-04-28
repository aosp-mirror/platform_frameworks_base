
#define DATA_SYNC_SIZE 1024

static inline void rsHCAPI_ContextFinish (RsContext rsc) {
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_ContextFinish);
    io->mToCore.commitSync(RS_CMD_ID_ContextFinish, size);
}

