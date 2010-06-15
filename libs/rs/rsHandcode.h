
#define DATA_SYNC_SIZE 1024

static inline void rsHCAPI_ContextFinish (RsContext rsc)
{
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_ContextFinish);
    RS_CMD_ContextFinish *cmd = static_cast<RS_CMD_ContextFinish *>(io->mToCore.reserve(size));
    io->mToCore.commitSync(RS_CMD_ID_ContextFinish, size);
}

static inline void rsHCAPI_ScriptInvokeV (RsContext rsc, RsScript va, uint32_t slot, const void * data, uint32_t sizeBytes)
{
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_ScriptInvokeV);
    if (sizeBytes < DATA_SYNC_SIZE) {
        size += (sizeBytes + 3) & ~3;
    }
    RS_CMD_ScriptInvokeV *cmd = static_cast<RS_CMD_ScriptInvokeV *>(io->mToCore.reserve(size));
    cmd->s = va;
    cmd->slot = slot;
    cmd->dataLen = sizeBytes;
    cmd->data = data;
    if (sizeBytes < DATA_SYNC_SIZE) {
        cmd->data = (void *)(cmd+1);
        memcpy(cmd+1, data, sizeBytes);
        io->mToCore.commit(RS_CMD_ID_ScriptInvokeV, size);
    } else {
        io->mToCore.commitSync(RS_CMD_ID_ScriptInvokeV, size);
    }
}


static inline void rsHCAPI_ScriptSetVarV (RsContext rsc, RsScript va, uint32_t slot, const void * data, uint32_t sizeBytes)
{
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_ScriptSetVarV);
    if (sizeBytes < DATA_SYNC_SIZE) {
        size += (sizeBytes + 3) & ~3;
    }
    RS_CMD_ScriptSetVarV *cmd = static_cast<RS_CMD_ScriptSetVarV *>(io->mToCore.reserve(size));
    cmd->s = va;
    cmd->slot = slot;
    cmd->dataLen = sizeBytes;
    cmd->data = data;
    if (sizeBytes < DATA_SYNC_SIZE) {
        cmd->data = (void *)(cmd+1);
        memcpy(cmd+1, data, sizeBytes);
        io->mToCore.commit(RS_CMD_ID_ScriptSetVarV, size);
    } else {
        io->mToCore.commitSync(RS_CMD_ID_ScriptSetVarV, size);
    }
}

static inline void rsHCAPI_AllocationData (RsContext rsc, RsAllocation va, const void * data, uint32_t sizeBytes)
{
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_AllocationData);
    if (sizeBytes < DATA_SYNC_SIZE) {
        size += (sizeBytes + 3) & ~3;
    }
    RS_CMD_AllocationData *cmd = static_cast<RS_CMD_AllocationData *>(io->mToCore.reserve(size));
    cmd->va = va;
    cmd->bytes = sizeBytes;
    cmd->data = data;
    if (sizeBytes < DATA_SYNC_SIZE) {
        cmd->data = (void *)(cmd+1);
        memcpy(cmd+1, data, sizeBytes);
        io->mToCore.commit(RS_CMD_ID_AllocationData, size);
    } else {
        io->mToCore.commitSync(RS_CMD_ID_AllocationData, size);
    }
}


static inline void rsHCAPI_Allocation1DSubData (RsContext rsc, RsAllocation va, uint32_t xoff, uint32_t count, const void * data, uint32_t sizeBytes)
{
    ThreadIO *io = &((Context *)rsc)->mIO;
    uint32_t size = sizeof(RS_CMD_Allocation1DSubData);
    if (sizeBytes < DATA_SYNC_SIZE) {
        size += (sizeBytes + 3) & ~3;
    }
    RS_CMD_Allocation1DSubData *cmd = static_cast<RS_CMD_Allocation1DSubData *>(io->mToCore.reserve(size));
    cmd->va = va;
    cmd->xoff = xoff;
    cmd->count = count;
    cmd->data = data;
    cmd->bytes = sizeBytes;
    if (sizeBytes < DATA_SYNC_SIZE) {
        cmd->data = (void *)(cmd+1);
        memcpy(cmd+1, data, sizeBytes);
        io->mToCore.commit(RS_CMD_ID_Allocation1DSubData, size);
    } else {
        io->mToCore.commitSync(RS_CMD_ID_Allocation1DSubData, size);
    }

}

