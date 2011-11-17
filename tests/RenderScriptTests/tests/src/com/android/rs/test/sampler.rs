#include "shared.rsh"
#include "rs_graphics.rsh"
rs_sampler minification;
rs_sampler magnification;
rs_sampler wrapS;
rs_sampler wrapT;
rs_sampler anisotropy;

static bool test_sampler_getters() {
    bool failed = false;

    _RS_ASSERT(rsgSamplerGetMagnification(minification) == RS_SAMPLER_NEAREST);
    _RS_ASSERT(rsgSamplerGetMinification(minification) == RS_SAMPLER_LINEAR_MIP_LINEAR);
    _RS_ASSERT(rsgSamplerGetWrapS(minification) == RS_SAMPLER_CLAMP);
    _RS_ASSERT(rsgSamplerGetWrapT(minification) == RS_SAMPLER_CLAMP);
    _RS_ASSERT(rsgSamplerGetAnisotropy(minification) == 1.0f);

    _RS_ASSERT(rsgSamplerGetMagnification(magnification) == RS_SAMPLER_LINEAR);
    _RS_ASSERT(rsgSamplerGetMinification(magnification) == RS_SAMPLER_NEAREST);
    _RS_ASSERT(rsgSamplerGetWrapS(magnification) == RS_SAMPLER_CLAMP);
    _RS_ASSERT(rsgSamplerGetWrapT(magnification) == RS_SAMPLER_CLAMP);
    _RS_ASSERT(rsgSamplerGetAnisotropy(magnification) == 1.0f);

    _RS_ASSERT(rsgSamplerGetMagnification(wrapS) == RS_SAMPLER_NEAREST);
    _RS_ASSERT(rsgSamplerGetMinification(wrapS) == RS_SAMPLER_NEAREST);
    _RS_ASSERT(rsgSamplerGetWrapS(wrapS) == RS_SAMPLER_WRAP);
    _RS_ASSERT(rsgSamplerGetWrapT(wrapS) == RS_SAMPLER_CLAMP);
    _RS_ASSERT(rsgSamplerGetAnisotropy(wrapS) == 1.0f);

    _RS_ASSERT(rsgSamplerGetMagnification(wrapT) == RS_SAMPLER_NEAREST);
    _RS_ASSERT(rsgSamplerGetMinification(wrapT) == RS_SAMPLER_NEAREST);
    _RS_ASSERT(rsgSamplerGetWrapS(wrapT) == RS_SAMPLER_CLAMP);
    _RS_ASSERT(rsgSamplerGetWrapT(wrapT) == RS_SAMPLER_WRAP);
    _RS_ASSERT(rsgSamplerGetAnisotropy(wrapT) == 1.0f);

    _RS_ASSERT(rsgSamplerGetMagnification(anisotropy) == RS_SAMPLER_NEAREST);
    _RS_ASSERT(rsgSamplerGetMinification(anisotropy) == RS_SAMPLER_NEAREST);
    _RS_ASSERT(rsgSamplerGetWrapS(anisotropy) == RS_SAMPLER_CLAMP);
    _RS_ASSERT(rsgSamplerGetWrapT(anisotropy) == RS_SAMPLER_CLAMP);
    _RS_ASSERT(rsgSamplerGetAnisotropy(anisotropy) == 8.0f);

    if (failed) {
        rsDebug("test_sampler_getters FAILED", 0);
    }
    else {
        rsDebug("test_sampler_getters PASSED", 0);
    }

    return failed;
}

void sampler_test() {
    bool failed = false;
    failed |= test_sampler_getters();

    if (failed) {
        rsSendToClientBlocking(RS_MSG_TEST_FAILED);
    }
    else {
        rsSendToClientBlocking(RS_MSG_TEST_PASSED);
    }
}

