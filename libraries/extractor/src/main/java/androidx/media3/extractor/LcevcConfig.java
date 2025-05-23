/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;

/** LCEVC configuration data. */
@UnstableApi
public final class LcevcConfig {
  private static final String TAG = "LcevcConfig";

  /**
   * Parses LCEVC configuration data.
   *
   * @param data A {@link ParsableByteArray}, whose position is set to the start of the LCEVC
   *     configuration data to parse.
   * @return A parsed representation of the LCEVC configuration data.
   * @throws ParserException If an error occurred parsing the data.
   */
  public static LcevcConfig parse(ParsableByteArray data) throws ParserException {
    // From ISO/IEC 14496-15:2022/Amd 1:2023, incorporated in ISO/IEC 14496-15:2024:
    //  aligned(8) class LCEVCDecoderConfigurationRecord {
    //    unsigned int(8) configurationVersion = 1;
    //    unsigned int(8) LCEVCProfileIndication;
    //    unsigned int(8) LCEVCLevelIndication;
    //    unsigned int(2) chroma_format_idc;
    //    unsigned int(3) bit_depth_luma_minus8;
    //    unsigned int(3) bit_depth_chroma_minus8;
    //    unsigned int(2) lengthSizeMinusOne;
    //    bit(6) reserved = '111111'b;
    //    unsigned int(32) pic_width_in_luma_samples;
    //    unsigned int(32) pic_height_in_luma_samples;
    //    unsigned int(1) sc_in_stream;
    //    unsigned int(1) gc_in_stream;
    //    unsigned int(1) ai_in_stream;
    //    bit(5) reserved = '11111'b;
    //    unsigned int(8) numOfArrays;
    //  for (j=0; j < numOfArrays; j++) {
    //      bit(2) reserved = '00'b;
    //      unsigned int(6) NAL_unit_type;
    //      unsigned int(16) numOfNalus;
    //      for (i=0; i< numOfNalus; i++) {
    //        unsigned int(16) nalUnitLength;
    //        bit(8*nalUnitLength) nalUnit;
    //      }
    //    }
    //  }

    try {
      int configurationVersion = data.readUnsignedByte();
      if (configurationVersion != 1) {
        throw new IllegalStateException(TAG + ": parsed unexpected configurationVersion = " + configurationVersion);
      }
      Log.i(TAG, "configurationVersion = " + configurationVersion);
      int lcevcProfileIndication = data.readUnsignedByte();
      Log.i(TAG, "lcevcProfileIndication = " + lcevcProfileIndication);
      int lcevcLevelIndication = data.readUnsignedByte();
      Log.i(TAG, "lcevcLevelIndication = " + lcevcLevelIndication);
      int readByte = data.readUnsignedByte();
      int bitdepthLuma = ((readByte >> 3) & 0b111) + 8;
      Log.i(TAG, "bitdepthLuma = " + bitdepthLuma);
      int bitdepthChroma = (readByte & 0b111) + 8;
      Log.i(TAG, "bitdepthChroma = " + bitdepthChroma);
      readByte = data.readUnsignedByte();
      int nalUnitLengthFieldLength = ((readByte >> 6) & 0b11) + 1;
      if (nalUnitLengthFieldLength < 4) {
        throw new IllegalStateException(TAG + ": parsed unexpected nalUnitLengthFieldLength = " + nalUnitLengthFieldLength);
      }
      Log.i(TAG, "nalUnitLengthFieldLength = " + nalUnitLengthFieldLength);
      int reserved = readByte & 0b111111;
      if (reserved != 0x3F) {
        throw new IllegalStateException(TAG + ": parsed unexpected reserved field = " + reserved);
      }
      int width = data.readUnsignedIntToInt();
      Log.i(TAG, "width = " + width);
      int height = data.readUnsignedIntToInt();
      Log.i(TAG, "height = " + height);
      @Nullable String codecs = "lvc1";

      return new LcevcConfig(
          nalUnitLengthFieldLength,
          width,
          height,
          bitdepthLuma,
          bitdepthChroma,
          codecs);
    } catch (ArrayIndexOutOfBoundsException e) {
      throw ParserException.createForMalformedContainer("Error parsing LCEVC config", e);
    }
  }

  /** The length of the NAL unit length field in the bitstream's container, in bytes. */
  public final int nalUnitLengthFieldLength;

  /** The width of each decoded frame, or {@link Format#NO_VALUE} if unknown. */
  public final int width;

  /** The height of each decoded frame, or {@link Format#NO_VALUE} if unknown. */
  public final int height;

  /** The bit depth of the luma samples, or {@link Format#NO_VALUE} if unknown. */
  public final int bitdepthLuma;

  /** The bit depth of the chroma samples, or {@link Format#NO_VALUE} if unknown. */
  public final int bitdepthChroma;

  /**
   * An RFC 6381 codecs string representing the video format, or {@code null} if not known.
   *
   * <p>See {@link Format#codecs}.
   */
  @Nullable public final String codecs;

  private LcevcConfig(
      int nalUnitLengthFieldLength,
      int width,
      int height,
      int bitdepthLuma,
      int bitdepthChroma,
      @Nullable String codecs) {
    this.nalUnitLengthFieldLength = nalUnitLengthFieldLength;
    this.width = width;
    this.height = height;
    this.bitdepthLuma = bitdepthLuma;
    this.bitdepthChroma = bitdepthChroma;
    this.codecs = codecs;
  }
}
