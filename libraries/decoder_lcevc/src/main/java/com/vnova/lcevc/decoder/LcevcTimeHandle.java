/*
 * Copyright 2014-2025 V-Nova International Limited <legal@v-nova.com>
 * BSD-3-Clause-Clear WITH V-Nova-No-Relicense-Exception:
 * https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_No_Relicense_Exception.txt
*/
package com.vnova.lcevc.decoder;

/**
 * Handles timehandle creation for the buffers that are fed to the LCEVC Decoder.
 */
public class LcevcTimeHandle {
    private static final String TAG = "LcevcTimeHandle";

    private static final long timeandleTimeUsBitLength = 44;                                     // number of bits to use for the time handle
    private static final long timehandleTimeUsFullBitLength = timeandleTimeUsBitLength + 1;        // need an extra bit for the sign bit
    private static final long timehandleFullRangeBitLength = 64 - timehandleTimeUsFullBitLength;
    private static final long timeStampFullMask = (1L << timehandleTimeUsFullBitLength) - 1L;

    // NOTE: the time handle is in the range -2^(timeHandleTimeBits-1) - 2^(timeHandleTimeBits-1)
    public static long getTimeHandle(int inputCc, long timeUs) {
        return ((long) inputCc << timehandleTimeUsFullBitLength) | (((timeUs << timehandleFullRangeBitLength) >> timehandleFullRangeBitLength) & timeStampFullMask);
    }

    public static int getCc(Long timehandle) {
        return (int) (timehandle >> timehandleTimeUsFullBitLength);
    }

    public static long getTimeUs(Long timehandle) {
        return (timehandle << timehandleFullRangeBitLength) >> timehandleFullRangeBitLength;
    }
}
