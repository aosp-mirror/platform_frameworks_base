
DeviceCreate {
    direct
    nocontext
    ret RsDevice
}

DeviceDestroy {
    direct
    nocontext
    param RsDevice dev
}

DeviceSetConfig {
    direct
    nocontext
    param RsDevice dev
    param RsDeviceParam p
    param int32_t value
}

ContextCreate {
    direct
    nocontext
    param RsDevice dev
    param uint32_t version
    ret RsContext
}

ContextCreateGL {
    direct
    nocontext
    param RsDevice dev
    param uint32_t version
    param RsSurfaceConfig sc
    param uint32_t dpi
    ret RsContext
}

ContextDestroy {
    direct
}

ContextGetMessage {
    direct
    param void *data
    param size_t *receiveLen
    param uint32_t *subID
    param bool wait
    ret RsMessageToClientType
}

ContextPeekMessage {
    direct
    param size_t *receiveLen
    param uint32_t *subID
    param bool wait
    ret RsMessageToClientType
}

ContextInitToClient {
    direct
}

ContextDeinitToClient {
    direct
}

aTypeCreate {
    direct
    param RsElement e
    param uint32_t dimX
    param uint32_t dimY
    param uint32_t dimZ
    param bool mips
    param bool faces
    ret RsType
}

aAllocationCreateTyped {
    direct
    param RsType vtype
    param RsAllocationMipmapControl mips
    param uint32_t usages
    ret RsAllocation
}

aAllocationCreateFromBitmap {
    direct
    param RsType vtype
    param RsAllocationMipmapControl mips
    param const void *data
    param uint32_t usages
    ret RsAllocation
}

aAllocationCubeCreateFromBitmap {
    direct
    param RsType vtype
    param RsAllocationMipmapControl mips
    param const void *data
    param uint32_t usages
    ret RsAllocation
}



ContextFinish {
	handcodeApi
	}

ContextBindRootScript {
	param RsScript sampler
	}

ContextBindProgramStore {
	param RsProgramStore pgm
	}

ContextBindProgramFragment {
	param RsProgramFragment pgm
	}

ContextBindProgramVertex {
	param RsProgramVertex pgm
	}

ContextBindProgramRaster {
	param RsProgramRaster pgm
	}

ContextBindFont {
	param RsFont pgm
	}

ContextPause {
	}

ContextResume {
	}

ContextSetSurface {
	param uint32_t width
	param uint32_t height
	param ANativeWindow *sur
	}

ContextDump {
	param int32_t bits
}

ContextSetPriority {
	param int32_t priority
	}

ContextDestroyWorker {
}

AssignName {
	param RsObjectBase obj
	param const char *name
	}

ObjDestroy {
	param RsAsyncVoidPtr objPtr
	}

ElementCreate {
	param RsDataType mType
	param RsDataKind mKind
	param bool mNormalized
	param uint32_t mVectorSize
	ret RsElement
	}

ElementCreate2 {
	param const RsElement * elements
	param const char ** names
	param const size_t * nameLengths
	param const uint32_t * arraySize
	ret RsElement
	}

AllocationCopyToBitmap {
	param RsAllocation alloc
	param void * data
	}


Allocation1DData {
	handcodeApi
	param RsAllocation va
	param uint32_t xoff
	param uint32_t lod
	param uint32_t count
	param const void *data
	}

Allocation1DElementData {
	handcodeApi
	param RsAllocation va
	param uint32_t x
	param uint32_t lod
	param const void *data
	param uint32_t comp_offset
	}

Allocation2DData {
	param RsAllocation va
	param uint32_t xoff
	param uint32_t yoff
	param uint32_t lod
	param RsAllocationCubemapFace face
	param uint32_t w
	param uint32_t h
	param const void *data
	}

Allocation2DElementData {
	param RsAllocation va
	param uint32_t x
	param uint32_t y
	param uint32_t lod
	param RsAllocationCubemapFace face
	param const void *data
	param uint32_t element_offset
	}

AllocationGenerateMipmaps {
	param RsAllocation va
}

AllocationRead {
	param RsAllocation va
	param void * data
	}

AllocationSyncAll {
	param RsAllocation va
	param RsAllocationUsageType src
}


AllocationResize1D {
	param RsAllocation va
	param uint32_t dimX
	}

AllocationResize2D {
	param RsAllocation va
	param uint32_t dimX
	param uint32_t dimY
	}

SamplerBegin {
	}

SamplerSet {
	param RsSamplerParam p
	param RsSamplerValue value
	}

SamplerSet2 {
	param RsSamplerParam p
	param float value
	}

SamplerCreate {
	ret RsSampler
	}



ScriptBindAllocation {
	param RsScript vtm
	param RsAllocation va
	param uint32_t slot
	}


ScriptSetTimeZone {
	param RsScript s
	param const char * timeZone
	}


ScriptInvoke {
	param RsScript s
	param uint32_t slot
	}

ScriptInvokeV {
	handcodeApi
	param RsScript s
	param uint32_t slot
	param const void * data
	}

ScriptSetVarI {
	param RsScript s
	param uint32_t slot
	param int value
	}

ScriptSetVarObj {
	param RsScript s
	param uint32_t slot
	param RsObjectBase value
	}

ScriptSetVarJ {
	param RsScript s
	param uint32_t slot
	param int64_t value
	}

ScriptSetVarF {
	param RsScript s
	param uint32_t slot
	param float value
	}

ScriptSetVarD {
	param RsScript s
	param uint32_t slot
	param double value
	}

ScriptSetVarV {
	handcodeApi
	param RsScript s
	param uint32_t slot
	param const void * data
	}


ScriptCCreate {
        param const char * resName
        param const char * cacheDir
	param const char * text
	ret RsScript
	}


ProgramStoreCreate {
	param bool colorMaskR
	param bool colorMaskG
	param bool colorMaskB
	param bool colorMaskA
        param bool depthMask
        param bool ditherEnable
	param RsBlendSrcFunc srcFunc
	param RsBlendDstFunc destFunc
        param RsDepthFunc depthFunc
	ret RsProgramStore
	}

ProgramRasterCreate {
	param bool pointSmooth
	param bool lineSmooth
	param bool pointSprite
	param float lineWidth
	param RsCullMode cull
	ret RsProgramRaster
}

ProgramBindConstants {
	param RsProgram vp
	param uint32_t slot
	param RsAllocation constants
	}


ProgramBindTexture {
	param RsProgramFragment pf
	param uint32_t slot
	param RsAllocation a
	}

ProgramBindSampler {
	param RsProgramFragment pf
	param uint32_t slot
	param RsSampler s
	}

ProgramFragmentCreate {
	param const char * shaderText
	param const uint32_t * params
	ret RsProgramFragment
	}

ProgramVertexCreate {
	param const char * shaderText
	param const uint32_t * params
	ret RsProgramVertex
	}

FontCreateFromFile {
	param const char *name
	param float fontSize
	param uint32_t dpi
	ret RsFont
	}

FontCreateFromMemory {
	param const char *name
	param float fontSize
	param uint32_t dpi
	param const void *data
	ret RsFont
	}

MeshCreate {
	ret RsMesh
	param uint32_t vtxCount
	param uint32_t idxCount
	}

MeshBindIndex {
	param RsMesh mesh
	param RsAllocation idx
	param uint32_t primType
	param uint32_t slot
	}

MeshBindVertex {
	param RsMesh mesh
	param RsAllocation vtx
	param uint32_t slot
	}

MeshInitVertexAttribs {
	param RsMesh mesh
	}

