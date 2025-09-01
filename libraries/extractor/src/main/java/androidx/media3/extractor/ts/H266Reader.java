/*
 * Copyright 2014-2025 V-Nova International Limited <legal@v-nova.com>
 * BSD-3-Clause-Clear WITH V-Nova-No-Relicense-Exception:
 * https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_No_Relicense_Exception.txt
 */
package androidx.media3.extractor.ts;

import static androidx.media3.common.ColorInfo.isoColorPrimariesToColorSpace;
import static androidx.media3.common.ColorInfo.isoTransferCharacteristicsToColorTransfer;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.container.ParsableNalUnitBitArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.MimeTypes;
import androidx.media3.container.NalUnitUtil;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.Util;
import java.util.Collections;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Parses a continuous H.266 byte stream and extracts individual frames. */
@UnstableApi
public final class H266Reader implements ElementaryStreamReader {

  private static final String TAG = "H266Reader";

  // nal_unit_type values from H.266/VVC (2022) Table 5, page 90.
  private static final int TRAIL_NUT = 0;
  private static final int STSA_NUT = 1;
  private static final int RADL_NUT = 2;
  private static final int RASL_NUT = 3;
  // 4..6 Reserved non-IRAP VCL NAL unit types
  private static final int IDR_W_RADL = 7;
  private static final int IDR_N_LP = 8;
  private static final int CRA_NUT = 9;
  private static final int GDR_NUT = 10;
  // 11 Reserved IRAP VCL NAL unit type
  private static final int OPI_NUT = 12;
  private static final int DCI_NUT = 13;
  private static final int VPS_NUT = 14;
  private static final int SPS_NUT = 15;
  private static final int PPS_NUT = 16;
  private static final int PH_NUT = 19;
  private static final int AUD_NUT = 20;
  private static final int EOS_NUT = 21;
  private static final int EOB_NUT = 22;
  private static final int PREFIX_SEI_NUT = 23;
  private static final int SUFFIX_SEI_NUT = 24;
  private static final int FD_NUT = 25;
  // 26..27 Reserved non-VCL NAL unit types
  // 28..31 Unspecified non-VCL NAL unit types
  private static final int LCEVC_NUT = 31;

  private final SeiReader seiReader;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;
  private /*@MonotonicNonNull*/ H266Reader.SampleReader sampleReader;

  // State that should not be reset on seek.
  private boolean hasOutputFormat;

  // State that should be reset on seek.
  private final boolean[] prefixFlags;
  private final NalUnitTargetBuffer vps;
  private final NalUnitTargetBuffer sps;
  private final NalUnitTargetBuffer pps;
  private final NalUnitTargetBuffer prefixSei;
  private final NalUnitTargetBuffer suffixSei;
  private long totalBytesWritten;

  // Per packet state that gets reset at the start of each packet.
  private long pesTimeUs;

  // Scratch variables to avoid allocations.
  private final ParsableByteArray seiWrapper;

  /** @param seiReader An SEI reader for consuming closed caption channels. */
  public H266Reader(SeiReader seiReader) {
    this.seiReader = seiReader;
    prefixFlags = new boolean[4];
    vps = new NalUnitTargetBuffer(VPS_NUT, 128);
    sps = new NalUnitTargetBuffer(SPS_NUT, 128);
    pps = new NalUnitTargetBuffer(PPS_NUT, 128);
    prefixSei = new NalUnitTargetBuffer(PREFIX_SEI_NUT, 128);
    suffixSei = new NalUnitTargetBuffer(SUFFIX_SEI_NUT, 128);
    pesTimeUs = C.TIME_UNSET;
    seiWrapper = new ParsableByteArray();
  }

  @Override
  public void seek() {
    totalBytesWritten = 0;
    pesTimeUs = C.TIME_UNSET;
    NalUnitUtil.clearPrefixFlags(prefixFlags);
    vps.reset();
    sps.reset();
    pps.reset();
    prefixSei.reset();
    suffixSei.reset();
    if (sampleReader != null) {
      sampleReader.reset();
    }
  }

  @Override
  public void createTracks(
      ExtractorOutput extractorOutput, TsPayloadReader.TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
    sampleReader = new H266Reader.SampleReader(output);
    seiReader.createTracks(extractorOutput, idGenerator);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (pesTimeUs != C.TIME_UNSET) {
      this.pesTimeUs = pesTimeUs;
    }
  }

  @Override
  public void consume(ParsableByteArray data) {
    assertTracksCreated();

    while (data.bytesLeft() > 0) {
      int offset = data.getPosition();
      int limit = data.limit();
      byte[] dataArray = data.getData();

      // Append the data to the buffer.
      totalBytesWritten += data.bytesLeft();
      output.sampleData(data, data.bytesLeft());

      // Scan the appended data, processing NAL units as they are encountered
      while (offset < limit) {
        int nalUnitOffset = NalUnitUtil.findNalUnit(dataArray, offset, limit, prefixFlags);

        if (nalUnitOffset == limit) {
          // We've scanned to the end of the data without finding the start of another NAL unit.
          nalUnitData(dataArray, offset, limit);
          return;
        }

        // We've seen the start of a NAL unit of the following type.
        int nalUnitType = NalUnitUtil.getH266NalUnitType(dataArray, nalUnitOffset);
        //Log.d(TAG, "pesTimeUs=" + pesTimeUs + " nalUnitType=" + nalUnitType);

        // This is the number of bytes from the current offset to the start of the next NAL unit.
        // It may be negative if the NAL unit started in the previously consumed data.
        int lengthToNalUnit = nalUnitOffset - offset;
        if (lengthToNalUnit > 0) {
          nalUnitData(dataArray, offset, nalUnitOffset);
        }

        int bytesWrittenPastPosition = limit - nalUnitOffset;
        // ISO/IEC 23090-3 Annex B allows for 4 byte start codes 0 0 0 1, but they don't seem to be
        // accounted for anywhere in ExoPlayer, without supporting them there can be 1 byte length
        // discrepancy in the sample data. Changing NalUnitUtil to support them is error prone and
        // hinders performance, so will workaround it here
        if (nalUnitOffset > 0 && dataArray[nalUnitOffset - 1] == 0x00) {
          bytesWrittenPastPosition++;
        }
        long absolutePosition = totalBytesWritten - bytesWrittenPastPosition;
        // Indicate the end of the previous NAL unit. If the length to the start of the next unit
        // is negative then we wrote too many bytes to the NAL buffers. Discard the excess bytes
        // when notifying that the unit has ended.
        endNalUnit(
            absolutePosition,
            bytesWrittenPastPosition,
            lengthToNalUnit < 0 ? -lengthToNalUnit : 0,
            pesTimeUs);
        // Indicate the start of the next NAL unit.
        startNalUnit(absolutePosition, bytesWrittenPastPosition, nalUnitType, pesTimeUs);
        // Continue scanning the data.
        offset = nalUnitOffset + 3;
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    //Log.d(TAG, "packetFinished pesTimeUs=" + pesTimeUs + " isEndOfInput=" + isEndOfInput);
    // Flush current sample if end of input
    if (isEndOfInput) {
      sampleReader.end(totalBytesWritten);
    }
  }

  @RequiresNonNull("sampleReader")
  private void startNalUnit(long position, int offset, int nalUnitType, long pesTimeUs) {
    sampleReader.startNalUnit(position, offset, nalUnitType, pesTimeUs, hasOutputFormat);
    if (!hasOutputFormat) {
      vps.startNalUnit(nalUnitType);
      sps.startNalUnit(nalUnitType);
      pps.startNalUnit(nalUnitType);
    }
    prefixSei.startNalUnit(nalUnitType);
    suffixSei.startNalUnit(nalUnitType);
  }

  @RequiresNonNull("sampleReader")
  private void nalUnitData(byte[] dataArray, int offset, int limit) {
    sampleReader.readNalUnitData(dataArray, offset, limit);
    if (!hasOutputFormat) {
      vps.appendToNalUnit(dataArray, offset, limit);
      sps.appendToNalUnit(dataArray, offset, limit);
      pps.appendToNalUnit(dataArray, offset, limit);
    }
    prefixSei.appendToNalUnit(dataArray, offset, limit);
    suffixSei.appendToNalUnit(dataArray, offset, limit);
  }

  @RequiresNonNull({"output", "sampleReader"})
  private void endNalUnit(long position, int offset, int discardPadding, long pesTimeUs) {
    sampleReader.endNalUnit(position, offset, hasOutputFormat);
    if (!hasOutputFormat) {
      vps.endNalUnit(discardPadding);
      sps.endNalUnit(discardPadding);
      pps.endNalUnit(discardPadding);
      if (/*vps.isCompleted() &&*/ sps.isCompleted() && pps.isCompleted()) {
        output.format(parseMediaFormat(formatId, vps, sps, pps));
        hasOutputFormat = true;
      }
    }
    if (prefixSei.endNalUnit(discardPadding)) {
      int unescapedLength = NalUnitUtil.unescapeStream(prefixSei.nalData, prefixSei.nalLength);
      seiWrapper.reset(prefixSei.nalData, unescapedLength);

      // Skip the NAL prefix and type.
      seiWrapper.skipBytes(5);
      seiReader.consume(pesTimeUs, seiWrapper);
    }
    if (suffixSei.endNalUnit(discardPadding)) {
      int unescapedLength = NalUnitUtil.unescapeStream(suffixSei.nalData, suffixSei.nalLength);
      seiWrapper.reset(suffixSei.nalData, unescapedLength);

      // Skip the NAL prefix and type.
      seiWrapper.skipBytes(5);
      seiReader.consume(pesTimeUs, seiWrapper);
    }
  }

  private static void parseGeneralConstraintsInfo(ParsableNalUnitBitArray bitArray) {
    if (bitArray.readBit()) {   // gci_present_flag
      bitArray.skipBits(71);  // from gci_intra_only_constraint_flag to gci_no_virtual_boundaries_constraint_flag inclusive
      int gciNumAdditionalBits = bitArray.readBits(8);
      int numAdditionalBitsUsed = 0;
      if (gciNumAdditionalBits > 5) {
        bitArray.skipBits(6);
        numAdditionalBitsUsed = 6;
      }
      bitArray.skipBits(gciNumAdditionalBits - numAdditionalBitsUsed);
    }
    bitArray.skipToByteAligned();
  }

  private static NalUnitUtil.SpsData parseProfileTierLevel(ParsableNalUnitBitArray bitArray, int maxSubLayersMinus1) {
    // assuming profileTierPresentFlag is 1, otherwise this function would not be called
    int generalProfileIdc = bitArray.readBits(7);
    Log.d(TAG, "generalProfileIdc = " + generalProfileIdc);
    boolean generalTierFlag = bitArray.readBit();
    Log.d(TAG, "generalTierFlag = " + generalTierFlag);
    int generalLevelIdc = bitArray.readBits(8);
    Log.d(TAG, "generalLevelIdc = " + generalLevelIdc);
    bitArray.skipBit();   // ptl_frame_only_constraint_flag
    boolean ptlMultilayerEnabledFlag = bitArray.readBit();
    Log.d(TAG, "ptlMultilayerEnabledFlag = " + ptlMultilayerEnabledFlag);
    // assuming profileTierPresentFlag is 1
    parseGeneralConstraintsInfo(bitArray);
    boolean[] ptlSublayerLevelPresentFlag = new boolean[maxSubLayersMinus1 + 1];
    int[] sublayerLevelIdc = new int[maxSubLayersMinus1 + 1];
    for (int i = maxSubLayersMinus1 - 1; i >= 0; i--) {
      ptlSublayerLevelPresentFlag[i] = bitArray.readBit();
    }
    bitArray.skipToByteAligned();
    for (int i = maxSubLayersMinus1 - 1; i >= 0; i--) {
      if (ptlSublayerLevelPresentFlag[i]) {
        sublayerLevelIdc[i] = bitArray.readBits(8);
        Log.d(TAG, "sublayerLevelIdc[" + i + "] = " + sublayerLevelIdc[i]);
      }
    }
    // assuming profileTierPresentFlag is 1
    int ptlNumSubProfiles = bitArray.readBits(8);
    for (int i = 0; i < ptlNumSubProfiles; i++) {
      int generalSubProfileIdcAtI = bitArray.readBits(32);
      Log.d(TAG, "generalSubProfileIdc[" + i + "] = " + generalSubProfileIdcAtI);
    }
    return new NalUnitUtil.SpsData(
        generalProfileIdc,
        0,
        generalLevelIdc,
        generalTierFlag,
        0,
        0,
        0,
        0,
        1.0f,
        0,
        0,
        false,
        false,
        0,
        0,
        0,
        false,
        Format.NO_VALUE,
        Format.NO_VALUE,
        Format.NO_VALUE
    );
  }

  private static void parseSublayerHrdParameters(ParsableNalUnitBitArray bitArray, int subLayerId, int hrdCpbCntMinus1, boolean generalDuHrdParamsPresentFlag, boolean isNal) {
    for (int j = 0; j<= hrdCpbCntMinus1; j++) {
      bitArray.readUnsignedExpGolombCodedInt();   // bit_rate_value_minus1[subLayerId][j]
      bitArray.readUnsignedExpGolombCodedInt();   // cpb_size_value_minus1[subLayerId][j]
      if (generalDuHrdParamsPresentFlag) {
        bitArray.readUnsignedExpGolombCodedInt();   // cpb_size_du_value_minus1[subLayerId][j]
        bitArray.readUnsignedExpGolombCodedInt();   // bit_rate_du_value_minus1[subLayerId][j]
      }
      bitArray.skipBit();       // cbr_flag[subLayerId][j]
    }
  }

  private static NalUnitUtil.SpsData parseVuiPayload(ParsableNalUnitBitArray bitArray, int payloadsize, NalUnitUtil.SpsData spsData) {
    // Parse VUI according to ITU-T H.274 | ISO/IEC 23002-7
    // begin: vui_parameters(payloadSize)
    float pixelWidthHeightRatio = 1;
    boolean vuiProgressiveSourceFlag = bitArray.readBit();
    boolean vuiInterlacedSourceFlag = bitArray.readBit();
    bitArray.skipBits(2);   // vui_non_packed_constraint_flag, vui_non_projected_constraint_flag
    if (bitArray.readBit()) {   // vui_aspect_ratio_info_present_flag
      bitArray.skipBit();   // vui_aspect_ratio_constant_flag
      int vuiAspectRatioIdc = bitArray.readBits(8);
      Log.d(TAG, "vuiAspectRatioIdc = " + vuiAspectRatioIdc);
      if (vuiAspectRatioIdc == NalUnitUtil.EXTENDED_SAR) {
        int vuiSarWidth = bitArray.readBits(16);
        int vuiSarHeight = bitArray.readBits(16);
        Log.d(TAG, "vuiSarWidth = " + vuiSarWidth + ", vuiSarHeight = " + vuiSarHeight);
        if (vuiSarWidth != 0 && vuiSarHeight != 0) {
          pixelWidthHeightRatio = (float) vuiSarWidth / vuiSarHeight;
        }
      }
      else if (vuiAspectRatioIdc < NalUnitUtil.ASPECT_RATIO_IDC_VALUES.length) {
        pixelWidthHeightRatio = NalUnitUtil.ASPECT_RATIO_IDC_VALUES[vuiAspectRatioIdc];
      } else {
        Log.w(TAG, "Unexpected vui_aspect_ratio_idc value: " + vuiAspectRatioIdc);
      }
    }
    Log.d(TAG, "pixelWidthHeightRatio = " + pixelWidthHeightRatio);
    if (bitArray.readBit()) {     // vui_overscan_info_present_flag
      bitArray.skipBit();     // vui_overscan_appropriate_flag
    }
    @C.ColorSpace int colorSpace = Format.NO_VALUE;
    @C.ColorTransfer int colorTransfer = Format.NO_VALUE;
    @C.ColorRange int colorRange = Format.NO_VALUE;
    if (bitArray.readBit()) {     // vui_colour_description_present_flag
      int vuiColourPrimaries = bitArray.readBits(8);
      Log.d(TAG, "vuiColourPrimaries = " + vuiColourPrimaries);
      colorSpace = isoColorPrimariesToColorSpace(vuiColourPrimaries);
      int vuiTransferCharacteristics = bitArray.readBits(8);
      Log.d(TAG, "vuiTransferCharacteristics = " + vuiTransferCharacteristics);
      colorTransfer = isoTransferCharacteristicsToColorTransfer(vuiTransferCharacteristics);
      int vuiMatrixCoeffs = bitArray.readBits(8);
      Log.d(TAG, "vuiMatrixCoeffs = " + vuiMatrixCoeffs);
      boolean vuiFullRangeFlag = bitArray.readBit();
      Log.d(TAG, "vuiFullRangeFlag = " + vuiFullRangeFlag);
      colorRange = vuiFullRangeFlag ? C.COLOR_RANGE_FULL : C.COLOR_RANGE_LIMITED;
    }
    if (bitArray.readBit()) {   // vui_chroma_loc_info_present_flag
      if (vuiProgressiveSourceFlag && !vuiInterlacedSourceFlag) {
        bitArray.readUnsignedExpGolombCodedInt();   // ui_chroma_sample_loc_type_frame
      }
      else {
        bitArray.readUnsignedExpGolombCodedInt();   // vui_chroma_sample_loc_type_top_field
        bitArray.readUnsignedExpGolombCodedInt();   // vui_chroma_sample_loc_type_bottom_field
      }
    }
    // end: vui_parameters(payloadSize)
    // vui_reserved_payload_extension_data should not be present
    // Ignore the possible additional data of vui_payload
    return new NalUnitUtil.SpsData(
        spsData.profileIdc,
        spsData.constraintsFlagsAndReservedZero2Bits,
        spsData.levelIdc,
        spsData.tierFlag,
        spsData.seqParameterSetId,
        spsData.maxNumRefFrames,
        spsData.width,
        spsData.height,
        pixelWidthHeightRatio,
        spsData.bitDepthLumaMinus8,
        spsData.bitDepthChromaMinus8,
        spsData.separateColorPlaneFlag,
        spsData.frameMbsOnlyFlag,
        spsData.frameNumLength,
        spsData.picOrderCountType,
        spsData.picOrderCntLsbLength,
        spsData.deltaPicOrderAlwaysZeroFlag,
        colorSpace,
        colorRange,
        colorTransfer
    );
  }

  public static Format parseMediaFormat(
      @Nullable String formatId,
      NalUnitTargetBuffer vps,
      NalUnitTargetBuffer sps,
      NalUnitTargetBuffer pps) {
    // Build codec-specific data.
    byte[] csdData = new byte[vps.nalLength + sps.nalLength + pps.nalLength];
    System.arraycopy(vps.nalData, 0, csdData, 0, vps.nalLength);
    System.arraycopy(sps.nalData, 0, csdData, vps.nalLength, sps.nalLength);
    System.arraycopy(pps.nalData, 0, csdData, vps.nalLength + sps.nalLength, pps.nalLength);

    // Parse the SPS NAL unit, as per H.266/VVC (2022) 7.3.2.4
    ParsableNalUnitBitArray bitArray = new ParsableNalUnitBitArray(sps.nalData, 0, sps.nalLength);
    bitArray.skipBits(40);    // NAL header
    int spsSeqParameterSetId = bitArray.readBits(4);
    int spsVideoParameterSetId = bitArray.readBits(4);
    int spsMaxSublayersMinus1 = bitArray.readBits(3);
    Log.d(TAG, "spsMaxSublayersMinus1 = " + spsMaxSublayersMinus1);
    int spsChromaFormatIdc = bitArray.readBits(2);
    Log.d(TAG, "spsChromaFormatIdc = " + spsChromaFormatIdc);
    int subWidthC = spsChromaFormatIdc == 1 || spsChromaFormatIdc == 2 ? 2 : 1;
    int subHeightC = spsChromaFormatIdc == 1 ? 2 : 1;
    int spsLog2CtuSizeMinus5 = bitArray.readBits(2);
    int ctbLog2SizeY = spsLog2CtuSizeMinus5 + 5;
    int ctbSizeY = 1 << ctbLog2SizeY;
    Log.d(TAG, "ctbSizeY = " + ctbSizeY);
    boolean spsPtlDpbHrdParamsPresentFlag = bitArray.readBit();
    NalUnitUtil.SpsData spsData = null;
    if (spsPtlDpbHrdParamsPresentFlag) {
      spsData = parseProfileTierLevel(bitArray, spsMaxSublayersMinus1);
    }
    bitArray.skipBit();   // sps_gdr_enabled_flag
    if (bitArray.readBit()) {   // sps_ref_pic_resampling_enabled_flag
      bitArray.readBit();   // sps_res_change_in_clvs_allowed_flag
    }
    int spsPicWidthMaxInLumaSamples = bitArray.readUnsignedExpGolombCodedInt();
    int spsPicHeightMaxInLumaSamples = bitArray.readUnsignedExpGolombCodedInt();
    Log.d(TAG, "preCW spsPicWidthMaxInLumaSamples = " + spsPicWidthMaxInLumaSamples);
    Log.d(TAG, "preCW spsPicHeightMaxInLumaSamples = " + spsPicHeightMaxInLumaSamples);
    int spsConfWinLeftOffset = 0;
    int spsConfWinRightOffset = 0;
    int spsConfWinTopOffset = 0;
    int spsConfWinBottomOffset = 0;
    boolean spsConformanceWindowFlag = bitArray.readBit();
    Log.d(TAG, "spsConformanceWindowFlag = " + spsConformanceWindowFlag);
    if (spsConformanceWindowFlag) {
      spsConfWinLeftOffset = bitArray.readUnsignedExpGolombCodedInt();
      spsConfWinRightOffset = bitArray.readUnsignedExpGolombCodedInt();
      spsConfWinTopOffset = bitArray.readUnsignedExpGolombCodedInt();
      spsConfWinBottomOffset = bitArray.readUnsignedExpGolombCodedInt();
      Log.d(TAG, "spsConfWinLeftOffset = "+ spsConfWinLeftOffset);
      Log.d(TAG, "spsConfWinRightOffset = "+ spsConfWinRightOffset);
      Log.d(TAG, "spsConfWinTopOffset = "+ spsConfWinTopOffset);
      Log.d(TAG, "spsConfWinBottomOffset = "+ spsConfWinBottomOffset);
    }
    int tmpWidthVal = (spsPicWidthMaxInLumaSamples + ctbSizeY - 1) / ctbSizeY;
    int tmpHeightVal = (spsPicHeightMaxInLumaSamples + ctbSizeY - 1) / ctbSizeY;
    if (bitArray.readBit()) {   // sps_subpic_info_present_flag
      int spsNumSubpicsMinus1 = bitArray.readUnsignedExpGolombCodedInt();
      if (spsNumSubpicsMinus1 > 0) {
        boolean spsIndependentSubpicsFlag = bitArray.readBit();
        boolean spsSubpicSameSizeFlag = bitArray.readBit();
        int bitLenH = (int)Math.ceil(Math.log(tmpWidthVal)/Math.log(2));
        int bitLenV = (int)Math.ceil(Math.log(tmpHeightVal)/Math.log(2));
        for (int i = 0; spsNumSubpicsMinus1 > 0 && i <= spsNumSubpicsMinus1; i++) {
          if (!spsSubpicSameSizeFlag || i == 0) {
            if (i > 0 && spsPicWidthMaxInLumaSamples > ctbSizeY) {
              bitArray.readBits(bitLenH);  // sps_subpic_ctu_top_left_x[i]
            }
            if (i > 0 && spsPicHeightMaxInLumaSamples > ctbSizeY) {
              bitArray.readBits(bitLenV);  // sps_subpic_ctu_top_left_y[i]
            }
            if (i < spsNumSubpicsMinus1 && spsPicWidthMaxInLumaSamples > ctbSizeY) {
              bitArray.readBits(bitLenH);  // sps_subpic_width_minus1[i]
            }
            if (i < spsNumSubpicsMinus1 && spsPicHeightMaxInLumaSamples > ctbSizeY) {
              bitArray.readBits(bitLenV);  // sps_subpic_height_minus1[i]
            }
          }
          if (!spsIndependentSubpicsFlag) {
            bitArray.skipBit();   // sps_subpic_treated_as_pic_flag[i]
            bitArray.skipBit();   // sps_loop_filter_across_subpic_enabled_flag[i]
          }
        }
        int spsSubpicIdLenMinus1 = bitArray.readUnsignedExpGolombCodedInt();
        if (bitArray.readBit()) {   // sps_subpic_id_mapping_explicitly_signalled_flag
          if (bitArray.readBit()) {   // sps_subpic_id_mapping_present_flag
            for (int i = 0; i <= spsNumSubpicsMinus1; i++) {
              bitArray.readBits(spsSubpicIdLenMinus1 + 1);  // sps_subpic_id[i]
            }
          }
        }
      }
    }
    int spsBitdepthMinus8 = bitArray.readUnsignedExpGolombCodedInt();
    Log.d(TAG, "spsBitdepthMinus8 = " + spsBitdepthMinus8);
    bitArray.skipBits(2);   // sps_entropy_coding_sync_enabled_flag, sps_entry_point_offsets_present_flag
    int spsLog2MaxPicOrderCntLsbMinus4 = bitArray.readBits(4);
    if (bitArray.readBit()) {   // sps_poc_msb_cycle_flag
      bitArray.readUnsignedExpGolombCodedInt();   // sps_poc_msb_cycle_len_minus1
    }
    int spsNumExtraPhBytes = bitArray.readBits(2);
    for (int i = 0; i < (spsNumExtraPhBytes * 8); i++) {
      bitArray.skipBit();   // sps_extra_ph_bit_present_flag[i]
    }
    int spsNumExtraShBytes = bitArray.readBits(2);
    for (int i = 0; i < (spsNumExtraShBytes * 8); i++) {
      bitArray.skipBit();   // sps_extra_sh_bit_present_flag[i]
    }
    if (spsPtlDpbHrdParamsPresentFlag) {
      boolean spsSublayerDpbParamsFlag = false;
      if (spsMaxSublayersMinus1 > 0) {
        spsSublayerDpbParamsFlag = bitArray.readBit();
      }
      // begin: dpb_parameters( sps_max_sublayers_minus1, sps_sublayer_dpb_params_flag )
      for (int i = spsSublayerDpbParamsFlag ? 0 : spsMaxSublayersMinus1; i <= spsMaxSublayersMinus1; i++) {
        bitArray.readUnsignedExpGolombCodedInt();   // dpb_max_dec_pic_buffering_minus1[i]
        bitArray.readUnsignedExpGolombCodedInt();   // dpb_max_num_reorder_pics[i]
        bitArray.readUnsignedExpGolombCodedInt();   // dpb_max_latency_increase_plus1[i]
      }
      // end: dpb_parameters( sps_max_sublayers_minus1, sps_sublayer_dpb_params_flag )
    }
    bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_min_luma_coding_block_size_minus2
    bitArray.skipBit();       // sps_partition_constraints_override_enabled_flag
    bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_diff_min_qt_min_cb_intra_slice_luma
    if (bitArray.readUnsignedExpGolombCodedInt() != 0) {  // sps_max_mtt_hierarchy_depth_intra_slice_luma
      bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_diff_max_bt_min_qt_intra_slice_luma
      bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_diff_max_tt_min_qt_intra_slice_luma
    }
    boolean spsQtbttDualTreeIntraFlag = false;
    if (spsChromaFormatIdc != 0) {
      spsQtbttDualTreeIntraFlag = bitArray.readBit();
    }
    if (spsQtbttDualTreeIntraFlag) {
      bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_diff_min_qt_min_cb_intra_slice_chroma
      if (bitArray.readUnsignedExpGolombCodedInt() != 0) {  // sps_max_mtt_hierarchy_depth_intra_slice_chroma
        bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_diff_max_bt_min_qt_intra_slice_chroma
        bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_diff_max_tt_min_qt_intra_slice_chroma
      }
    }
    bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_diff_min_qt_min_cb_inter_slice
    if (bitArray.readUnsignedExpGolombCodedInt() != 0) {  // sps_max_mtt_hierarchy_depth_inter_slice
      bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_diff_max_bt_min_qt_inter_slice
      bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_diff_max_tt_min_qt_inter_slice
    }
    boolean spsMaxLumaTransformSize64Flag = false;
    if (ctbSizeY > 32) {
      spsMaxLumaTransformSize64Flag = bitArray.readBit();
    }
    boolean spsTransformSkipEnabledFlag = bitArray.readBit();
    if (spsTransformSkipEnabledFlag) {
      bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_transform_skip_max_size_minus2
      bitArray.skipBit();       // sps_bdpcm_enabled_flag
    }
    if (bitArray.readBit()) {   // sps_mts_enabled_flag
      bitArray.skipBits(2);   // sps_explicit_mts_intra_enabled_flag, sps_explicit_mts_inter_enabled_flag
    }
    boolean spsLfnstEnabledFlag = bitArray.readBit();
    if (spsChromaFormatIdc != 0) {
      boolean spsJointCbcrEnabledFlag = bitArray.readBit();
      boolean spsSameQpTableForChromaFlag = bitArray.readBit();
      int numQpTables = spsSameQpTableForChromaFlag ? 1 : spsJointCbcrEnabledFlag ? 3 : 2;
      for (int i = 0; i < numQpTables; i++) {
        bitArray.readSignedExpGolombCodedInt();     // sps_qp_table_start_minus26[i]
        int spsNumPointsInQpTableMinus1atI = bitArray.readUnsignedExpGolombCodedInt();
        for (int j = 0; j<=spsNumPointsInQpTableMinus1atI; j++) {
          bitArray.readUnsignedExpGolombCodedInt();   // sps_delta_qp_in_val_minus1[i][j]
          bitArray.readUnsignedExpGolombCodedInt();   // sps_delta_qp_diff_val[i][j]
        }
      }
    }
    boolean spsSaoEnabledFlag = bitArray.readBit();
    boolean spsAlfEnabledFlag = bitArray.readBit();
    boolean spsCcalfEnabledFlag = false;
    if (spsAlfEnabledFlag && (spsChromaFormatIdc != 0)) {
      spsCcalfEnabledFlag = bitArray.readBit();
    }
    bitArray.readBits(3);   // sps_lmcs_enabled_flag, sps_weighted_pred_flag, sps_weighted_bipred_flag
    boolean spsLongTermRefPicsFlag = bitArray.readBit();
    boolean spsInterLayerPredictionEnabledFlag = false;
    if (spsVideoParameterSetId > 0) {
      spsInterLayerPredictionEnabledFlag = bitArray.readBit();
    }
    bitArray.skipBit();     // sps_idr_rpl_present_flag
    boolean spsRpl1SameAsRpl0Flag = bitArray.readBit();
    for (int i = 0; i < (spsRpl1SameAsRpl0Flag ? 1 : 2); i++) {
      int spsNumRefPicListsAtI = bitArray.readUnsignedExpGolombCodedInt();
      for (int j = 0; j < spsNumRefPicListsAtI; j++) {
        // begin: ref_pic_list_struct( i, j )
        int numRefEntriesAtIJ = bitArray.readUnsignedExpGolombCodedInt();
        boolean ltrpInHeaderFlagAtIJ = true;
        if (spsLongTermRefPicsFlag && (j < spsNumRefPicListsAtI) && (numRefEntriesAtIJ > 0)) {
          ltrpInHeaderFlagAtIJ = bitArray.readBit();
        }
        for (int k = 0; k < numRefEntriesAtIJ; k++) {
          boolean interLayerRefPicFlagAtIJK = false;
          if (spsInterLayerPredictionEnabledFlag) {
            interLayerRefPicFlagAtIJK = bitArray.readBit();
          }
          if (!interLayerRefPicFlagAtIJK) {
            boolean stRefPicFlagAtIJK = false;
            if (spsLongTermRefPicsFlag) {
              stRefPicFlagAtIJK = bitArray.readBit();
            }
            if (stRefPicFlagAtIJK) {
              if (bitArray.readUnsignedExpGolombCodedInt() > 0) {   // abs_delta_poc_st[listIdx][rplsIdx][i]
                bitArray.skipBit();   // strp_entry_sign_flag[listIdx][rplsIdx][i]
              }
            }
            else if (!ltrpInHeaderFlagAtIJ) {
              bitArray.readBits(spsLog2MaxPicOrderCntLsbMinus4 + 4);    // rpls_poc_lsb_lt[listIdx][rplsIdx][j++]
            }
          }
          else {
            bitArray.readUnsignedExpGolombCodedInt();   // ilrp_idx[listIdx][rplsIdx][i]
          }
        }
        // end: ref_pic_list_struct( i, j )
      }
    }
    bitArray.skipBit();     // sps_ref_wraparound_enabled_flag
    if (bitArray.readBit()) {   // sps_temporal_mvp_enabled_flag
      bitArray.skipBit();   // sps_sbtmvp_enabled_flag
    }
    boolean spsAmvrEnabledFlag = bitArray.readBit();
    if (bitArray.readBit()) {   // sps_bdof_enabled_flag
      bitArray.skipBit();   // sps_bdof_control_present_in_ph_flag
    }
    bitArray.skipBit();     // sps_smvd_enabled_flag
    if (bitArray.readBit()) {   // sps_dmvr_enabled_flag
      bitArray.skipBit();   // sps_dmvr_control_present_in_ph_flag
    }
    if (bitArray.readBit()) {   // sps_mmvd_enabled_flag
      bitArray.skipBit();   // sps_mmvd_fullpel_only_enabled_flag
    }
    int spsSixMinusMaxNumMergeCand = bitArray.readUnsignedExpGolombCodedInt();
    bitArray.skipBit();     // sps_sbt_enabled_flag
    if (bitArray.readBit()) {   // sps_affine_enabled_flag
      bitArray.readUnsignedExpGolombCodedInt();   // sps_five_minus_max_num_subblock_merge_cand
      bitArray.skipBit();   // sps_6param_affine_enabled_flag
      if (spsAmvrEnabledFlag) {
        bitArray.skipBit(); // sps_affine_amvr_enabled_flag
      }
      if (bitArray.readBit()) {   // sps_affine_prof_enabled_flag
        bitArray.skipBit(); // sps_prof_control_present_in_ph_flag
      }
    }
    bitArray.skipBits(2);   // sps_bcw_enabled_flag, sps_ciip_enabled_flag
    int maxNumMergeCand = 6 - spsSixMinusMaxNumMergeCand;
    if (maxNumMergeCand >= 2) {
      boolean spsGpmEnabledFlag = bitArray.readBit();
      if (spsGpmEnabledFlag && maxNumMergeCand >= 3) {
        bitArray.readUnsignedExpGolombCodedInt();   // sps_max_num_merge_cand_minus_max_num_gpm_cand
      }
    }
    bitArray.readUnsignedExpGolombCodedInt();   // sps_log2_parallel_merge_level_minus2
    bitArray.skipBits(3);     // sps_isp_enabled_flag, sps_mrl_enabled_flag, sps_mip_enabled_flag
    if (spsChromaFormatIdc != 0) {
      bitArray.skipBit();     // sps_cclm_enabled_flag
    }
    if (spsChromaFormatIdc == 1) {
      bitArray.skipBits(2);   // sps_chroma_horizontal_collocated_flag, sps_chroma_vertical_collocated_flag
    }
    boolean spsPaletteEnabledFlag = bitArray.readBit();
    boolean spsActEnabledFlag = false;
    if (spsChromaFormatIdc == 3 && !spsMaxLumaTransformSize64Flag) {
      spsActEnabledFlag = bitArray.readBit();
    }
    if (spsTransformSkipEnabledFlag || spsPaletteEnabledFlag) {
      bitArray.readUnsignedExpGolombCodedInt();     // sps_min_qp_prime_ts
    }
    if (bitArray.readBit()) {       // sps_ibc_enabled_flag
      bitArray.readUnsignedExpGolombCodedInt();     // sps_six_minus_max_num_ibc_merge_cand
    }
    if (bitArray.readBit()) {       // sps_ladf_enabled_flag
      int spsNumLadfIntervalsMinus2 = bitArray.readBits(2);
      bitArray.readSignedExpGolombCodedInt();     // sps_ladf_lowest_interval_qp_offset
      for(int i = 0; i < spsNumLadfIntervalsMinus2 + 1; i++) {
        bitArray.readSignedExpGolombCodedInt();     // sps_ladf_qp_offset[i]
        bitArray.readUnsignedExpGolombCodedInt();   // sps_ladf_delta_threshold_minus1[i]
      }
    }
    boolean spsExplicitScalingListEnabledFlag = bitArray.readBit();
    if (spsLfnstEnabledFlag && spsExplicitScalingListEnabledFlag) {
      bitArray.skipBit();     // sps_scaling_matrix_for_lfnst_disabled_flag
    }
    boolean spsScalingMatrixForAlternativeColourSpaceDisabledFlag = false;
    if (spsActEnabledFlag && spsExplicitScalingListEnabledFlag) {
      spsScalingMatrixForAlternativeColourSpaceDisabledFlag = bitArray.readBit();
    }
    if (spsScalingMatrixForAlternativeColourSpaceDisabledFlag) {
      bitArray.skipBit();   // sps_scaling_matrix_designated_colour_space_flag
    }
    bitArray.skipBits(2);   // sps_dep_quant_enabled_flag, sps_sign_data_hiding_enabled_flag
    if (bitArray.readBit()) {   // sps_virtual_boundaries_enabled_flag
      if (bitArray.readBit()) {   // sps_virtual_boundaries_present_flag
        int spsNumVerVirtualBoundaries = bitArray.readUnsignedExpGolombCodedInt();
        for (int i = 0; i < spsNumVerVirtualBoundaries; i++) {
          bitArray.readUnsignedExpGolombCodedInt();   // sps_virtual_boundary_pos_x_minus1[i]
        }
        int spsNumHorVirtualBoundaries = bitArray.readUnsignedExpGolombCodedInt();
        for (int i = 0; i < spsNumHorVirtualBoundaries; i++) {
          bitArray.readUnsignedExpGolombCodedInt();   // sps_virtual_boundary_pos_y_minus1[i]
        }
      }
    }
    if (spsPtlDpbHrdParamsPresentFlag) {
      if (bitArray.readBit()) {     // sps_timing_hrd_params_present_flag
        // begin: general_timing_hrd_parameters()
        bitArray.skipBits(8);   // num_units_in_tick, time_scale
        boolean generalNalHrdParamsPresentFlag = bitArray.readBit();
        boolean generalVclHrdParamsPresentFlag = bitArray.readBit();
        boolean generalDuHrdParamsPresentFlag = false;
        int hrdCpbCntMinus1 = -1;
        if (generalNalHrdParamsPresentFlag || generalVclHrdParamsPresentFlag) {
          bitArray.skipBit();   // general_same_pic_timing_in_all_ols_flag
          generalDuHrdParamsPresentFlag = bitArray.readBit();
          if (generalDuHrdParamsPresentFlag) {
            bitArray.skipBits(8);   // tick_divisor_minus2
          }
          bitArray.skipBits(8);     // bit_rate_scale, cpb_size_scale
          if (generalDuHrdParamsPresentFlag) {
            bitArray.skipBits(4);   // cpb_size_du_scale
          }
          hrdCpbCntMinus1 = bitArray.readUnsignedExpGolombCodedInt();
        }
        // end: general_timing_hrd_parameters()
        boolean spsSublayerCpbParamsPresentFlag = false;
        if (spsMaxSublayersMinus1 > 0) {
          spsSublayerCpbParamsPresentFlag = bitArray.readBit();
        }
        int firstSubLayer = spsSublayerCpbParamsPresentFlag ? 0 : spsMaxSublayersMinus1;
        // begin: ols_timing_hrd_parameters(firstSubLayer, sps_max_sublayers_minus1)
        for (int i = firstSubLayer; i <= spsMaxSublayersMinus1; i++) {
          boolean fixedPicRateWithinCvsFlagAtI = true;
          if (!bitArray.readBit()) {    // fixed_pic_rate_general_flag[i]
            fixedPicRateWithinCvsFlagAtI = bitArray.readBit();
          }
          if (fixedPicRateWithinCvsFlagAtI) {
            bitArray.readUnsignedExpGolombCodedInt();   // elemental_duration_in_tc_minus1[i]
          }
          else if ((generalNalHrdParamsPresentFlag || generalVclHrdParamsPresentFlag) && hrdCpbCntMinus1 == 0) {
            bitArray.readBit();     // low_delay_hrd_flag[i]
          }
          if (generalNalHrdParamsPresentFlag) {
            parseSublayerHrdParameters(bitArray, i, hrdCpbCntMinus1, generalDuHrdParamsPresentFlag, true);
          }
          if (generalVclHrdParamsPresentFlag) {
            parseSublayerHrdParameters(bitArray, i, hrdCpbCntMinus1, generalDuHrdParamsPresentFlag, false);
          }
        }
        // end: ols_timing_hrd_parameters(firstSubLayer, sps_max_sublayers_minus1)
      }
    }
    bitArray.skipBit();           // sps_field_seq_flag
    if (bitArray.readBit()) {     // sps_vui_parameters_present_flag
      int spsVuiPayloadSizeMinus1 = bitArray.readUnsignedExpGolombCodedInt();
      bitArray.skipToByteAligned();
      spsData = parseVuiPayload(bitArray, spsVuiPayloadSizeMinus1 + 1, spsData);
    }
    // Ignore the possible additional data of seq_parameter_set_rbsp

    // FIXME: assuming op_level_idc is equal to general_level_idc
    String codecs = CodecSpecificDataUtil.buildVvcCodecString(spsData.profileIdc, spsData.tierFlag, spsData.levelIdc);
    Log.d(TAG, "VVC codecs string: " + codecs);

    // Parse the PPS NAL unit, as per H.266/VVC (2022) 7.3.2.5
    bitArray = new ParsableNalUnitBitArray(pps.nalData, 0, pps.nalLength);
    bitArray.skipBits(40);    // NAL header
    bitArray.skipBits(11);    // pps_pic_parameter_set_id, pps_seq_parameter_set_id, pps_mixed_nalu_types_in_pic_flag
    int ppsPicWidthInLumaSamples = bitArray.readUnsignedExpGolombCodedInt();
    int ppsPicHeightInLumaSamples = bitArray.readUnsignedExpGolombCodedInt();
    Log.d(TAG, "preCW ppsPicWidthInLumaSamples = " + ppsPicWidthInLumaSamples);
    Log.d(TAG, "preCW ppsPicHeightInLumaSamples = " + ppsPicHeightInLumaSamples);
    int ppsConfWinLeftOffset = 0;
    int ppsConfWinRightOffset = 0;
    int ppsConfWinTopOffset = 0;
    int ppsConfWinBottomOffset = 0;
    boolean ppsConformanceWindowFlag = bitArray.readBit();
    Log.d(TAG, "ppsConformanceWindowFlag = " + ppsConformanceWindowFlag);
    if (ppsConformanceWindowFlag) {
      ppsConfWinLeftOffset = bitArray.readUnsignedExpGolombCodedInt();
      ppsConfWinRightOffset = bitArray.readUnsignedExpGolombCodedInt();
      ppsConfWinTopOffset = bitArray.readUnsignedExpGolombCodedInt();
      ppsConfWinBottomOffset = bitArray.readUnsignedExpGolombCodedInt();
      Log.d(TAG, "ppsConfWinLeftOffset = "+ ppsConfWinLeftOffset);
      Log.d(TAG, "ppsConfWinRightOffset = "+ ppsConfWinRightOffset);
      Log.d(TAG, "ppsConfWinTopOffset = "+ ppsConfWinTopOffset);
      Log.d(TAG, "ppsConfWinBottomOffset = "+ ppsConfWinBottomOffset);
    }
    else if (ppsPicWidthInLumaSamples==spsPicWidthMaxInLumaSamples && ppsPicHeightInLumaSamples==spsPicHeightMaxInLumaSamples) {
      // As per Rec. ITU-T H.266 (04/2022) top of page 119
      Log.d(TAG, "CW not defined in PPS and ppsPicWidth/Height matching spsPicWidth/HeightMax, must apply the SPS CW");
      ppsConfWinLeftOffset = spsConfWinLeftOffset;
      ppsConfWinRightOffset = spsConfWinRightOffset;
      ppsConfWinTopOffset = spsConfWinTopOffset;
      ppsConfWinBottomOffset = spsConfWinBottomOffset;
    }
    ppsPicWidthInLumaSamples -= subWidthC * (ppsConfWinLeftOffset + ppsConfWinRightOffset);
    ppsPicHeightInLumaSamples -= subHeightC * (ppsConfWinTopOffset + ppsConfWinBottomOffset);
    Log.d(TAG, "postCW ppsPicWidthInLumaSamples = " + ppsPicWidthInLumaSamples);
    Log.d(TAG, "postCW ppsPicHeightInLumaSamples = " + ppsPicHeightInLumaSamples);

    return new Format.Builder()
        .setId(formatId)
        .setSampleMimeType(MimeTypes.VIDEO_H266)
        .setCodecs(codecs)
        .setWidth(ppsPicWidthInLumaSamples)
        .setHeight(ppsPicHeightInLumaSamples)
        .setColorInfo(
            new ColorInfo.Builder()
                .setColorSpace(spsData.colorSpace)
                .setColorRange(spsData.colorRange)
                .setColorTransfer(spsData.colorTransfer)
                .setLumaBitdepth(spsBitdepthMinus8 + 8)
                .setChromaBitdepth(spsBitdepthMinus8 + 8)
                .build())
        .setPixelWidthHeightRatio(spsData.pixelWidthHeightRatio)
        .setInitializationData(Collections.singletonList(csdData))
        .build();
  }

  @EnsuresNonNull({"output", "sampleReader"})
  private void assertTracksCreated() {
    Assertions.checkStateNotNull(output);
    Util.castNonNull(sampleReader);
  }

  private static final class SampleReader {

    /**
     * Offset in bytes of the first_slice_segment_in_pic_flag in a NAL unit containing a
     * slice_segment_layer_rbsp.
     */
    private static final int FIRST_SLICE_FLAG_OFFSET = 2;

    private final TrackOutput output;

    // Per NAL unit state. A sample consists of one or more NAL units.
    private long nalUnitPosition;
    private boolean nalUnitHasKeyframeData;
    private int nalUnitBytesRead;
    private long nalUnitTimeUs;
    private boolean lookingForFirstSliceFlag;
    private boolean isFirstSlice;
    private boolean isFirstPrefixNalUnit;

    // Per sample state that gets reset at the start of each sample.
    private boolean readingSample;
    private boolean readingPrefix;
    private long samplePosition;
    private long sampleTimeUs;
    private boolean sampleIsKeyframe;

    public SampleReader(TrackOutput output) {
      this.output = output;
    }

    public void reset() {
      lookingForFirstSliceFlag = false;
      isFirstSlice = false;
      isFirstPrefixNalUnit = false;
      readingSample = false;
      readingPrefix = false;
    }

    public void startNalUnit(
        long position, int offset, int nalUnitType, long pesTimeUs, boolean hasOutputFormat) {
      isFirstSlice = false;
      isFirstPrefixNalUnit = false;
      nalUnitTimeUs = pesTimeUs;
      nalUnitBytesRead = 0;
      nalUnitPosition = position;

      if (!isVclBodyNalUnit(nalUnitType)) {
        if (readingSample && !readingPrefix) {
          if (hasOutputFormat) {
            outputSample(offset);
          }
          readingSample = false;
        }
        if (isPrefixNalUnit(nalUnitType)) {
          isFirstPrefixNalUnit = !readingPrefix;
          readingPrefix = true;
        }
      }

      // Look for the first slice flag if this NAL unit contains a slice_segment_layer_rbsp.
      nalUnitHasKeyframeData = (nalUnitType >= IDR_W_RADL && nalUnitType <= CRA_NUT);
      lookingForFirstSliceFlag = nalUnitHasKeyframeData || nalUnitType <= RASL_NUT;
    }

    public void readNalUnitData(byte[] data, int offset, int limit) {
      if (lookingForFirstSliceFlag) {
        isFirstSlice = true;
        lookingForFirstSliceFlag = false;
      }
    }

    public void endNalUnit(long position, int offset, boolean hasOutputFormat) {
      if (readingPrefix && isFirstSlice) {
        // This sample has parameter sets. Reset the key-frame flag based on the first slice.
        sampleIsKeyframe = nalUnitHasKeyframeData;
        readingPrefix = false;
      } else if (isFirstPrefixNalUnit || isFirstSlice) {
        // This NAL unit is at the start of a new sample (access unit).
        if (hasOutputFormat && readingSample) {
          // Output the sample ending before this NAL unit.
          int nalUnitLength = (int) (position - nalUnitPosition);
          outputSample(offset + nalUnitLength);
        }
        samplePosition = nalUnitPosition;
        sampleTimeUs = nalUnitTimeUs;
        sampleIsKeyframe = nalUnitHasKeyframeData;
        readingSample = true;
      }
    }

    private void outputSample(int offset) {
      if (sampleTimeUs == C.TIME_UNSET) {
        return;
      }
      @C.BufferFlags int flags = sampleIsKeyframe ? C.BUFFER_FLAG_KEY_FRAME : 0;
      int size = (int) (nalUnitPosition - samplePosition);
      //Log.d(TAG, "outputSample sampleTimeUs=" + sampleTimeUs + " nalUnitStartPosition=" + nalUnitPosition + " samplePosition=" + samplePosition + " => size=" + size);
      output.sampleMetadata(sampleTimeUs, flags, size, offset, null);
    }

    public void end(long position) {
      // Output a final sample with the nal units currently held
      nalUnitPosition = position;
      outputSample(0);
    }

    /** Returns whether a NAL unit type is one that occurs before any VCL NAL units in a sample. */
    private static boolean isPrefixNalUnit(int nalUnitType) {
      return (OPI_NUT <= nalUnitType && nalUnitType <= AUD_NUT) || nalUnitType == PREFIX_SEI_NUT;
    }

    /** Returns whether a NAL unit type is one that occurs in the VLC body of a sample. */
    private static boolean isVclBodyNalUnit(int nalUnitType) {
      return nalUnitType < OPI_NUT || nalUnitType == LCEVC_NUT;
    }
  }
}
