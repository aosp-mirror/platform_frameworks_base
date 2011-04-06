
#define DATA_SYNC_SIZE 1024

static inline void rsHCAPI_ContextFinish (RsContext rsc) {
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_ContextFinish);
    io->mToCore.commitSync(RS_CMD_ID_ContextFinish, size);
}

static inline void rsHCAPI_ScriptInvokeV (RsContext rsc, RsScript va, uint32_t slot, const void * data, size_t sizeBytes) {
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_ScriptInvokeV);
    if (sizeBytes < DATA_SYNC_SIZE) {
        size += (sizeBytes + 3) & ~3;
    }
    RS_CMD_ScriptInvokeV *cmd = static_cast<RS_CMD_ScriptInvokeV *>(io->mToCore.reserve(size));
    cmd->s = va;
    cmd->slot = slot;
    cmd->data_length = sizeBytes;
    cmd->data = data;
    if (sizeBytes < DATA_SYNC_SIZE) {
        cmd->data = (void *)(cmd+1);
        memcpy(cmd+1, data, sizeBytes);
        io->mToCore.commit(RS_CMD_ID_ScriptInvokeV, size);
    } else {
        io->mToCore.commitSync(RS_CMD_ID_ScriptInvokeV, size);
    }
}


static inline void rsHCAPI_ScriptSetVarV (RsContext rsc, RsScript va, uint32_t slot, const void * data, size_t sizeBytes) {
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_ScriptSetVarV);
    if (sizeBytes < DATA_SYNC_SIZE) {
        size += (sizeBytes + 3) & ~3;
    }
    RS_CMD_ScriptSetVarV *cmd = static_cast<RS_CMD_ScriptSetVarV *>(io->mToCore.reserve(size));
    cmd->s = va;
    cmd->slot = slot;
    cmd->data_length = sizeBytes;
    cmd->data = data;
    if (sizeBytes < DATA_SYNC_SIZE) {
        cmd->data = (void *)(cmd+1);
        memcpy(cmd+1, data, sizeBytes);
        io->mToCore.commit(RS_CMD_ID_ScriptSetVarV, size);
    } else {
        io->mToCore.commitSync(RS_CMD_ID_ScriptSetVarV, size);
    }
}

static inline void rsHCAPI_Allocation1DData (RsContext rsc, RsAllocation va, uint32_t xoff, uint32_t lod,
                                             uint32_t count, const void * data, size_t sizeBytes) {
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_Allocation1DData);
    if (sizeBytes < DATA_SYNC_SIZE) {
        size += (sizeBytes + 3) & ~3;
    }
    RS_CMD_Allocation1DData *cmd = static_cast<RS_CMD_Allocation1DData *>(io->mToCore.reserve(size));
    cmd->va = va;
    cmd->xoff = xoff;
    cmd->lod = lod;
    cmd->count = count;
    cmd->data = data;
    cmd->data_length = sizeBytes;
    if (sizeBytes < DATA_SYNC_SIZE) {
        cmd->data = (void *)(cmd+1);
        memcpy(cmd+1, data, sizeBytes);
        io->mToCore.commit(RS_CMD_ID_Allocation1DData, size);
    } else {
        io->mToCore.commitSync(RS_CMD_ID_Allocation1DData, size);
    }
}

static inline void rsHCAPI_Allocation1DElementData (RsContext rsc, RsAllocation va, uint32_t x, uint32_t lod,
                                                    const void * data, size_t sizeBytes, uint32_t comp_offset) {
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_Allocation1DElementData);
    if (sizeBytes < DATA_SYNC_SIZE) {
        size += (sizeBytes + 3) & ~3;
    }
    RS_CMD_Allocation1DElementData *cmd = static_cast<RS_CMD_Allocation1DElementData *>(io->mToCore.reserve(size));
    cmd->va = va;
    cmd->x = x;
    cmd->lod = lod;
    cmd->data = data;
    cmd->comp_offset = comp_offset;
    cmd->data_length = sizeBytes;
    if (sizeBytes < DATA_SYNC_SIZE) {
        cmd->data = (void *)(cmd+1);
        memcpy(cmd+1, data, sizeBytes);
        io->mToCore.commit(RS_CMD_ID_Allocation1DElementData, size);
    } else {
        io->mToCore.commitSync(RS_CMD_ID_Allocation1DElementData, size);
    }
}

