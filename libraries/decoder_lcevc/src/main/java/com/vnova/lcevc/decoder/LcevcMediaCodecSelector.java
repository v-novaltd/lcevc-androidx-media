/*
 * Copyright 2014-2025 V-Nova International Limited <legal@v-nova.com>
 * BSD-3-Clause-Clear WITH V-Nova-No-Relicense-Exception:
 * https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_No_Relicense_Exception.txt
 */
package com.vnova.lcevc.decoder;

import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import java.util.ArrayList;
import java.util.List;

public class LcevcMediaCodecSelector implements MediaCodecSelector {
  @Override
  public synchronized List<MediaCodecInfo> getDecoderInfos(
      String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder)
      throws MediaCodecUtil.DecoderQueryException {
    String baseMimeType = mimeType.replace("lcevc-", "");
    List<MediaCodecInfo> lcevcMediaCodecInfos = new ArrayList<>();
    List<MediaCodecInfo> baseMediaCodecInfos = MediaCodecUtil.getDecoderInfos(
        baseMimeType,
        requiresSecureDecoder,
        requiresTunnelingDecoder);
    if (!baseMimeType.equals(mimeType)) {
      for (MediaCodecInfo mediaCodecInfo : baseMediaCodecInfos) {
        MediaCodecInfo lcevcMediaCodecInfo = MediaCodecInfo.newInstance(
            mediaCodecInfo.name,
            mimeType,
            mediaCodecInfo.codecMimeType,
            mediaCodecInfo.capabilities,
            mediaCodecInfo.hardwareAccelerated,
            mediaCodecInfo.softwareOnly,
            mediaCodecInfo.vendor,
            false,
            false);
        lcevcMediaCodecInfos.add(lcevcMediaCodecInfo);
      }
    }
    lcevcMediaCodecInfos.addAll(baseMediaCodecInfos);
    return lcevcMediaCodecInfos;
  }
}
