// Copyright 2008 The Android Open Source Project

#ifndef AUDIOTRACKTEST_H_
#define AUDIOTRACKTEST_H_

namespace android {

class AudioTrackTest{
    public:
        AudioTrackTest(void);
        ~AudioTrackTest() {};

        void Execute(void);
        int Test01();

        void Generate(short *buffer, long bufferSz, long amplitude, unsigned long &phi, long dPhi);
        void InitSine();
        short ComputeSine(long amplitude, long phi);

        #define SIN_SZ    1024
        short sin1024[SIN_SZ];           // sine table 2*pi = 1024
};

};


#endif /*AUDIOTRACKTEST_H_*/
