/*
 * Copyright 2014-2025 V-Nova International Limited <legal@v-nova.com>
 * BSD-3-Clause-Clear WITH V-Nova-No-Relicense-Exception:
 * https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_No_Relicense_Exception.txt
 */
package androidx.media3.extractor;

import androidx.annotation.Nullable;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.extractor.ts.H266Reader;
import androidx.media3.extractor.ts.NalUnitTargetBuffer;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.ParsableByteArray;
import java.util.ArrayList;
import java.util.List;

public final class VvcConfig {
  private static final String TAG = "VvcConfig";

  private static final int OPI_NUT = 12;
  private static final int DCI_NUT = 13;
  private static final int VPS_NUT = 14;
  private static final int SPS_NUT = 15;
  private static final int PPS_NUT = 16;

  @Nullable public final List<byte[]> initializationData;
  public final int nalUnitLengthFieldLength;
  public final int width;
  public final int height;
  public final int bitdepthLuma;
  public final int bitdepthChroma;
  public final float pixelWidthAspectRatio;
  @Nullable public final String codecs;
  protected static NalUnitTargetBuffer vps;
  protected static NalUnitTargetBuffer sps;
  protected static NalUnitTargetBuffer pps;

  private static String hexdump(ParsableByteArray data, int count) {
    int initialPosition = data.getPosition();
    StringBuilder sb = new StringBuilder(3 * count);
    for (int i=0; i<count && i<data.bytesLeft(); i++) {
      sb.append(String.format(" %02X", data.readUnsignedByte()));
    }
    data.setPosition(initialPosition);
    return sb.toString();
  }

  public static VvcConfig parse(ParsableByteArray data, int atomType) throws ParserException {
    VvcConfig vvcConfig = null;
    int lengthSizeMinusOne = 0;
    int bitDepthMinusEight = 0;

    vps = new NalUnitTargetBuffer(VPS_NUT, 128);
    sps = new NalUnitTargetBuffer(SPS_NUT, 128);
    pps = new NalUnitTargetBuffer(PPS_NUT, 128);

    // Skip VvcConfigurationBox header
    data.skipBytes(4);

    //Log.d(TAG, "parse" + hexdump(data, 24));

    try {
      int val = data.readUnsignedByte();
      if ((val >> 3) != 0b11111) {
        throw ParserException.createForMalformedDataOfUnknownType("VvcDecoderConfigurationRecord 1st reserved bits not found", null);
      }
      lengthSizeMinusOne = (val >> 5) & 0b11;
      Log.d(TAG, "lengthSizeMinusOne=" + lengthSizeMinusOne);
      int ptlPresentFlag = val >> 7;
      if (ptlPresentFlag != 0) {
        data.skipBytes(1);  // ols_idx (9)
        val = data.readUnsignedByte();
        int numSublayers = (val >> 4) & 0b111;
        Log.d(TAG, "numSublayers=" + numSublayers);
        val = data.readUnsignedByte();
        bitDepthMinusEight = (val >> 5) & 0b111;
        Log.d(TAG, "bitDepthMinusEight=" + bitDepthMinusEight);
        if ((val & 0b11111) != 0b11111) {
          throw ParserException.createForMalformedDataOfUnknownType("VvcDecoderConfigurationRecord 2nd reserved bits not found", null);
        }
        // begin: VvcPTLRecord(num_sublayers) native_ptl
        val = data.readUnsignedByte();
        int numBytesConstraintInfo = val & 0b111111;
        data.skipBytes(2);    // general_profile_idc (7), general_tier_flag (1), general_level_idc (8)
        data.skipBytes(numBytesConstraintInfo);   // ptl_frame_only_constraint_flag (1), ptl_multi_layer_enabled_flag (1), general_constraint_info (8*num_bytes_constraint_info - 2)
        if (numSublayers >= 2) {
          val = data.readUnsignedByte();
          // bitmask of (numSublayers-1) bits for ptl_sublayer_level_present_flag[i]
          // with i decreasing from (numSublayers-2) followed by zeros, in 1 byte
          int ptlSublayerLevelPresentCount = 0;
          while (val != 0) {
            ptlSublayerLevelPresentCount += val & 0b1;
            val >>= 1;
          }
          data.skipBytes(ptlSublayerLevelPresentCount);
        }
        int numSubProfiles = data.readUnsignedByte();
        Log.d(TAG, "numSubProfiles=" + numSubProfiles);
        long[] generalSubProfileIdc = new long[numSubProfiles];
        for (int j = 0; j < numSubProfiles; j++) {
          generalSubProfileIdc[j] = data.readUnsignedInt();
        }
        // end: VvcPTLRecord(num_sublayers) native_ptl
        int maxPictureWidth = data.readUnsignedShort();
        int maxPictureHeight = data.readUnsignedShort();
        int avgFrameRate = data.readUnsignedShort();
        Log.d(TAG, "maxPictureWidth=" + maxPictureWidth + ", maxPictureHeight=" + maxPictureHeight + ", avgFrameRate=" + avgFrameRate);
      }
      int numOfArrays = data.readUnsignedByte();
      for (int j = 0; j < numOfArrays; j++) {
        // array_completeness (1), reserved (2), NAL_unit_type (5)
        val = data.readUnsignedByte();
        int arrayCompleteness = val >> 7;
        Log.d(TAG, "arrayCompleteness[" + j + "]=" + arrayCompleteness);
        int nalUnitType = val & 0b11111;
        Log.d(TAG, "nalUnitType=" + nalUnitType);
        int numNalus = 1;
        if (nalUnitType != DCI_NUT  &&  nalUnitType != OPI_NUT) {
          numNalus = data.readUnsignedShort();
          //Log.d(TAG, "numNalus=" + numNalus);
        }
        for (int i = 0; i < numNalus; i++) {
          int nalUnitLength = data.readUnsignedShort();
          switch (nalUnitType) {
            case VPS_NUT:
              vps = new NalUnitTargetBuffer(nalUnitType, data.getData(), data.getPosition(), nalUnitLength);
              break;
            case SPS_NUT:
              sps = new NalUnitTargetBuffer(nalUnitType, data.getData(), data.getPosition(), nalUnitLength);
              break;
            case PPS_NUT:
              pps = new NalUnitTargetBuffer(nalUnitType, data.getData(), data.getPosition(), nalUnitLength);
              break;
          }
          data.skipBytes(nalUnitLength);
        }
      }
    } catch (ArrayIndexOutOfBoundsException e) {
      throw ParserException.createForMalformedContainer("Error parsing VVC config", e);
    }

    Format format = H266Reader.parseMediaFormat(atomType, null, vps, sps, pps);

    ColorInfo colorInfo = (format != null && format.colorInfo != null) ? format.colorInfo
        : new ColorInfo.Builder()
            .setLumaBitdepth(bitDepthMinusEight + 8)
            .setChromaBitdepth(bitDepthMinusEight + 8)
            .build();

    if (format != null) {
      vvcConfig = new VvcConfig(format.initializationData,
          lengthSizeMinusOne + 1,
          format.width,
          format.height,
          colorInfo.lumaBitdepth,
          colorInfo.chromaBitdepth,
          format.pixelWidthHeightRatio,
          format.codecs);
    } else {
      vvcConfig = new VvcConfig(new ArrayList<byte[]>(0),
          lengthSizeMinusOne + 1,
          0,
          0,
          colorInfo.lumaBitdepth,
          colorInfo.chromaBitdepth,
          1.0f,
          null);
    }
    return vvcConfig;
  }

  private VvcConfig(
      List<byte[]> initializationData,
      int nalUnitLengthFieldLength,
      int width,
      int height,
      int bitdepthLuma,
      int bitdepthChroma,
      float pixelWidthAspectRatio,
      @Nullable String codecs) {
    this.initializationData = initializationData;
    this.nalUnitLengthFieldLength = nalUnitLengthFieldLength;
    this.width = width;
    this.height = height;
    this.bitdepthLuma = bitdepthLuma;
    this.bitdepthChroma = bitdepthChroma;
    this.pixelWidthAspectRatio = pixelWidthAspectRatio;
    this.codecs = codecs;
  }
}
