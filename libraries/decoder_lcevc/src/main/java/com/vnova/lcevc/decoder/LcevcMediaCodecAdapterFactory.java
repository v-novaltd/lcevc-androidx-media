/*
 * SPDX-FileCopyrightText: 2025 V-Nova International Limited <legal@v-nova.com>
 * SPDX-License-Identifier: BSD-3-Clause-Clear WITH V-Nova-No-Relicense-Exception
*/
package com.vnova.lcevc.decoder;

import static androidx.media3.common.util.MediaFormatUtil.isVideoFormat;

import androidx.annotation.RequiresApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.SynchronousMediaCodecAdapter;

import java.io.IOException;

/**
 * The LCEVC Variant of the mediaCodecADapterFactory {@link MediaCodecAdapter.Factory}.
 *
 * <p>Tries to create an LcevcMediaCodecAdapterFactory based on availability of the mediaFormat in
 * the provided configuration. Else, this factory {@link #createAdapter creates}
 * instances on devices with API level &gt;= 31 (Android 12+). For devices with older API versions,
 * the default behavior is to create {@link SynchronousMediaCodecAdapter} instances. The factory
 * offers APIs to force the creation of  (applicable for
 * devices with API &gt;= 23) or {@link SynchronousMediaCodecAdapter} instances.
 */
@UnstableApi
public final class LcevcMediaCodecAdapterFactory implements MediaCodecAdapter.Factory {

    public LcevcMediaCodecAdapterFactory() {
    }

    @Override
    @RequiresApi(21)
    public MediaCodecAdapter createAdapter(MediaCodecAdapter.Configuration configuration) throws IOException {
        return isVideoFormat(configuration.mediaFormat) ? new LcevcSynchronousMediaCodecAdapter.Factory().createAdapter(configuration) : new DefaultMediaCodecAdapterFactory().createAdapter(configuration);
    }
}
