package com.android.net;

/** @hide */
interface IProxyService
{
    /**
     * Keep up-to-date with
     * frameworks/base/packages/services/PacProcessor/IProxyService.h
     */
    String resolvePacFile(String host, String url);

    int setPacFile(String scriptContents);

    int startPacSystem();
    int stopPacSystem();
}
