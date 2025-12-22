/*
 * Copyright 2014-2025 V-Nova International Limited <legal@v-nova.com>
 * BSD-3-Clause-Clear WITH V-Nova-No-Relicense-Exception:
 * https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_No_Relicense_Exception.txt
 */
package androidx.media3.extractor.ts;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import static androidx.media3.common.util.Assertions.checkArgument;
import static androidx.media3.common.util.Assertions.checkStateNotNull;
import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR;

public final class LcevcReader implements ElementaryStreamReader {

    private static final String TAG = "LcevcReader";
    public static final int LCEVC_IDR_NALU_HEADER_1ST_BYTE = 0x7B;
    public static final int LCEVC_NON_IDR_NALU_HEADER_1ST_BYTE = 0x79;
    public static final int LCEVC_BLOCK_TYPE_GLOBAL_CONFIG = 1;
    public static final int[][] LCEVC_RESOLUTION_TYPES = {
        {   0,    0}, { 360,  200}, { 400,  240}, { 480,  320}, { 640,  360}, { 640,  480},
        { 768,  480}, { 800,  600}, { 852,  480}, { 854,  480}, { 856,  480}, { 960,  540},
        { 960,  640}, {1024,  576}, {1024,  600}, {1024,  768}, {1152,  864}, {1280,  720},
        {1280,  800}, {1280, 1024}, {1360,  768}, {1366,  768}, {1400, 1050}, {1440,  900},
        {1600, 1200}, {1680, 1050}, {1920, 1080}, {1920, 1200}, {2048, 1080}, {2048, 1152},
        {2048, 1536}, {2160, 1440}, {2560, 1440}, {2560, 1600}, {2560, 2048}, {3200, 1800},
        {3200, 2048}, {3200, 2400}, {3440, 1440}, {3840, 1600}, {3840, 2160}, {3840, 2400},
        {4096, 2160}, {4096, 3072}, {5120, 2880}, {5120, 3200}, {5120, 4096}, {6400, 4096},
        {6400, 4800}, {7680, 4320}, {7680, 4800},
    };
    private String formatId;
    private int width;
    private int height;
    private int bitdepth;
    private Format format;
    private @MonotonicNonNull TrackOutput output;
    private boolean writingSample;
    private int sampleBytesWritten;
    private long sampleTimeUs;
    private int sampleFlags;

    public LcevcReader(TsPayloadReader.EsInfo esInfo) {
    }

    @Override
    public void seek() {
        writingSample = false;
        sampleTimeUs = C.TIME_UNSET;
    }

    @Override
    public void createTracks(ExtractorOutput extractorOutput, PesReader.TrackIdGenerator idGenerator) {
        throw new IllegalArgumentException("LcevcReader.createTracks() needs the scalableBaseTrackId argument");
    }

    @Override
    public void createTracks(ExtractorOutput extractorOutput, PesReader.TrackIdGenerator idGenerator,
        int scalableBaseTrackId) {
        idGenerator.generateNewId();
        formatId = idGenerator.getFormatId();
        output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO, scalableBaseTrackId);
    }

    @Override
    public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
        if ((flags & FLAG_PAYLOAD_UNIT_START_INDICATOR) == 0) {
            return;
        }
        writingSample = true;
        sampleTimeUs = pesTimeUs;
        sampleBytesWritten = 0;
        sampleFlags = flags;
    }

    @Override
    public void consume(ParsableByteArray data) throws ParserException {
        checkStateNotNull(output);
        if (!writingSample) {
            return;
        }

        int dataPosition = data.getPosition();
        int bytesAvailable = data.bytesLeft();
        if (sampleBytesWritten == 0) {
            Format parsedFormat = maybeParseMediaFormat(data);
            if (parsedFormat != null && !parsedFormat.equals(format)) {
                output.format(parsedFormat);
                format = parsedFormat;
            }
        }
        output.sampleData(data, bytesAvailable);
        data.setPosition(dataPosition + bytesAvailable);
        sampleBytesWritten += bytesAvailable;
    }

    @Override
    public void packetFinished(boolean isEndOfInput) {
        checkStateNotNull(output); // Asserts that createTracks has been called.
        checkArgument(writingSample);
        output.sampleMetadata(sampleTimeUs, sampleFlags, sampleBytesWritten, /* offset= */ 0, /* cryptoData= */ null);
        writingSample = false;
    }

    private @Nullable Format maybeParseMediaFormat(ParsableByteArray data) {
        int initialPosition = data.getPosition();
        // Ensure start code
        int val = data.readUnsignedShort();
        checkArgument(val == 0x0000);
        int byte3 = data.readUnsignedByte();
        if (byte3 != 0x01) {
            int byte4 = data.readUnsignedByte();
            if (byte3 != 0x00 || byte4 != 0x01) {
                throw new IllegalArgumentException(String.format("Unrecognised start code on LCEVC Access Unit: 00 00 %02x %02x",
                    byte3, byte4));
            }
        }
        val = data.readUnsignedByte();      // forbidden_zero_bit (1), forbidden_one_bit (1),  nal_unit_type (5), reserved_flag (1) = 1
        checkArgument(val == LCEVC_IDR_NALU_HEADER_1ST_BYTE || val == LCEVC_NON_IDR_NALU_HEADER_1ST_BYTE,
            String.format("Unrecognised LCEVC NAL unit first byte: %02x", val));
        if (val == LCEVC_NON_IDR_NALU_HEADER_1ST_BYTE) {
            // Non-IDR NAL unit, skip
            data.setPosition(initialPosition);
            return null;
        }
        sampleFlags |= C.BUFFER_FLAG_KEY_FRAME;
        val = data.readUnsignedByte();      // reserved flag (8)
        checkArgument(val == 0xFF);
        do {
            int blockType = peekBlockPayloadType(data);
            int blockSize = readBlockPayloadSize(data);
            Log.d(TAG, " LCEVC: found block of type = " + blockType + ", size = " + blockSize);
            if (blockType == LCEVC_BLOCK_TYPE_GLOBAL_CONFIG) {
                readGlobalConfig(data);
                break;
            } else {
                data.skipBytes(blockSize);
            }
        } while (data.bytesLeft() > 0);
        data.setPosition(initialPosition);
        return new Format.Builder()
            .setId(formatId)
            .setSampleMimeType(MimeTypes.VIDEO_LCEVC)
            .setWidth(width)
            .setHeight(height)
            .setColorInfo(new ColorInfo.Builder()
                .setLumaBitdepth(bitdepth)
                .setChromaBitdepth(bitdepth)
                .build())
            .build();
    }

    private void readGlobalConfig(ParsableByteArray data) {
        int val = data.readUnsignedByte();
        int processedPlanesTypeFlag = (val >> 7) & 0b1;
        int resolutionType = (val >> 1) & 0b111111;
        checkArgument(resolutionType != 0 && (resolutionType <= 50 || resolutionType == 63), "Unrecognized LCEVC resolution_type = " + resolutionType);
        val = data.readUnsignedByte();
        int enhancementDepthType = (val >> 2) & 0b11;
        bitdepth = 8 + (2 * enhancementDepthType);
        int temporalStepWidthModifierSignalledFlag = (val >> 1) & 0b1;
        val = data.readUnsignedByte();
        int upsampleType = (val >> 3) & 0b111;
        int level1FilteringSignalledFlag = (val >> 2) & 0b1;
        val = data.readUnsignedByte();
        int tileDimensionsType = (val >> 4) & 0b11;
        int chromaStepWidthFlag = val & 0b1;
        if (processedPlanesTypeFlag != 0) {
            data.skipBytes(1);
        }
        if (temporalStepWidthModifierSignalledFlag != 0) {
            data.skipBytes(1);
        }
        if (upsampleType == 4) {
            data.skipBytes(8);
        }
        if (level1FilteringSignalledFlag != 0) {
            data.skipBytes(1);
        }
        if (tileDimensionsType > 0) {
            if (tileDimensionsType == 3) {
                data.skipBytes(4);
            }
            data.skipBytes(1);
        }
        if (resolutionType == 63) {
            width = data.readUnsignedShort();
            height = data.readUnsignedShort();
        } else {
            // resolutionType <= 50
            width = LCEVC_RESOLUTION_TYPES[resolutionType][0];
            height = LCEVC_RESOLUTION_TYPES[resolutionType][1];
        }
        if (chromaStepWidthFlag != 0) {
            data.skipBytes(1);
        }
    }

    private static int peekBlockPayloadType(ParsableByteArray data) {
        int val = data.peekUnsignedByte();
        return val & 0b11111;
    }

    private static int readBlockPayloadSize(ParsableByteArray data) {
        int val = data.readUnsignedByte();
        int payloadSizeType = (val >> 5) & 0b111;
        return payloadSizeType < 0b111 ? payloadSizeType : readMultibyte(data);
    }

    private static int readMultibyte(ParsableByteArray data) {
        int result = 0;
        int shift = 0;

        while (true) {
            int val = data.readUnsignedByte();
            result |= (val & 0x7F) << shift; // take 7 bits
            if ((val & 0x80) == 0) { // MSB = 0 → stop
                break;
            }
            shift += 7;
        }
        return result;
    }
}
