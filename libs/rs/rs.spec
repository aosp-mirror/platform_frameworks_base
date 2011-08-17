
ContextDestroy {
    direct
}

ContextGetMessage {
    direct
    param void *data
    param size_t *receiveLen
    param uint32_t *usrID
    ret RsMessageToClientType
}

ContextPeekMessage {
    direct
    param size_t *receiveLen
    param uint32_t *usrID
    ret RsMessageToClientType
}

ContextInitToClient {
    direct
}

ContextDeinitToClient {
    direct
}

TypeCreate {
    direct
    param RsElement e
    param uint32_t dimX
    param uint32_t dimY
    param uint32_t dimZ
    param bool mips
    param bool faces
    ret RsType
}

AllocationCreateTyped {
    direct
    param RsType vtype
    param RsAllocationMipmapControl mips
    param uint32_t usages
    ret RsAllocation
}

AllocationCreateFromBitmap {
    direct
    param RsType vtype
    param RsAllocationMipmapControl mips
    param const void *data
    param uint32_t usages
    ret RsAllocation
}

AllocationCubeCreateFromBitmap {
    direct
    param RsType vtype
    param RsAllocationMipmapControl mips
    param const void *data
    param uint32_t usages
    ret RsAllocation
}



ContextFinish {
	sync
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
	param RsNativeWindow sur
        sync
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
        direct
	param RsDataType mType
	param RsDataKind mKind
	param bool mNormalized
	param uint32_t mVectorSize
	ret RsElement
	}

ElementCreate2 {
        direct
	param const RsElement * elements
	param const char ** names
	param const uint32_t * arraySize
	ret RsElement
	}

AllocationCopyToBitmap {
	param RsAllocation alloc
	param void * data
	}


Allocation1DData {
	param RsAllocation va
	param uint32_t xoff
	param uint32_t lod
	param uint32_t count
	param const void *data
	}

Allocation1DElementData {
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

AllocationCopy2DRange {
	param RsAllocation dest
	param uint32_t destXoff
	param uint32_t destYoff
	param uint32_t destMip
	param uint32_t destFace
	param uint32_t width
	param uint32_t height
	param RsAllocation src
	param uint32_t srcXoff
	param uint32_t srcYoff
	param uint32_t srcMip
	param uint32_t srcFace
	}

SamplerCreate {
    direct
    param RsSamplerValue magFilter
    param RsSamplerValue minFilter
    param RsSamplerValue wrapS
    param RsSamplerValue wrapT
    param RsSamplerValue wrapR
    param float mAniso
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
	param RsScript s
	param uint32_t slot
	param const void * data
	}

ScriptForEach {
    param RsScript s
    param uint32_t slot
    param RsAllocation ain
    param RsAllocation aout
    param const void * usr
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
	direct
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
	direct
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
	direct
	param const char * shaderText
	param const uint32_t * params
	ret RsProgramFragment
	}

ProgramVertexCreate {
	direct
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
	param RsAllocation *vtx
	param RsAllocation *idx
	param uint32_t *primType
	ret RsMesh
	}
