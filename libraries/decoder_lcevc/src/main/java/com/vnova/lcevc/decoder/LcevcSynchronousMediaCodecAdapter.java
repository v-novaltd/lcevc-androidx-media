/*
 * Copyright 2014-2025 V-Nova International Limited <legal@v-nova.com>
 * BSD-3-Clause-Clear WITH V-Nova-No-Relicense-Exception:
 * https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_No_Relicense_Exception.txt
*/
package com.vnova.lcevc.decoder;

import com.vnova.lcevc.decoder.dil.LcevcNativeAdapter;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.TraceUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import java.io.IOException;
import java.nio.ByteBuffer;
import org.json.JSONException;

/**
 * A {@link MediaCodecAdapter} that operates in LCEVC mode.
 */
@UnstableApi
public final class LcevcSynchronousMediaCodecAdapter implements MediaCodecAdapter {

    private static final String TAG = "LCEVCMediaCodecAdapter";
    private final MediaCodec mCodec;
    private Format mFormat;
    private LcevcDecoder mLcevcDecoder;
    private LcevcNativeAdapter.DecodeInformation mDecodeInformation;

    private boolean mInBandLcevcData;

    public static class FloatToFraction {
        private FloatToFraction() {
            throw new IllegalStateException("Class shall not be instantiated");
        }

        private static int gcd(int a, int b) {
            return b == 0 ? a : gcd(b, a % b);
        }

        public static int[] getFraction(float value) {
            final int precision = 1000000;
            int sign = value < 0 ? -1 : 1;
            value = Math.abs(value);

            int integerPart = (int) value;
            float fractionalPart = value - integerPart;

            int numerator = Math.round(fractionalPart * precision);
            int denominator = precision;

            int divisor = gcd(numerator, denominator);
            numerator /= divisor;
            denominator /= divisor;

            numerator = sign * (integerPart * denominator + numerator);

            return new int[] { numerator, denominator };
        }
    }

    /**
     * A factory for {@link LcevcSynchronousMediaCodecAdapter} instances.
     */
    public static class Factory implements MediaCodecAdapter.Factory {

        @Override
        @RequiresApi(21)
        public MediaCodecAdapter createAdapter(Configuration configuration) throws IOException {
            @Nullable MediaCodec codec = null;
            try {
                codec = createCodec(configuration);
                TraceUtil.beginSection("configureCodec");
                codec.configure(configuration.mediaFormat, null, // Remove the surface to get the image
                        configuration.crypto, configuration.flags);
                TraceUtil.endSection();
                TraceUtil.beginSection("startCodec");
                codec.start();
                TraceUtil.endSection();
                return new LcevcSynchronousMediaCodecAdapter(codec, configuration);
            } catch (IOException | RuntimeException e) {
                if (codec != null) {
                    codec.release();
                }
                throw e;
            }
        }

        /**
         * Creates a new {@link MediaCodec} instance.
         */
        protected MediaCodec createCodec(Configuration configuration) throws IOException {
            checkNotNull(configuration.codecInfo);
            String codecName = configuration.codecInfo.name;
            TraceUtil.beginSection("createCodec:" + codecName);
            MediaCodec mediaCodec = MediaCodec.createByCodecName(codecName);
            TraceUtil.endSection();
            return mediaCodec;
        }

    }

    @RequiresApi(21)
    private LcevcSynchronousMediaCodecAdapter(MediaCodec mediaCodec, Configuration configuration) {
        mCodec = mediaCodec;
        mFormat = configuration.format;
        int[] sar = FloatToFraction.getFraction(mFormat.pixelWidthHeightRatio);
        int bitdepth = mFormat.colorInfo != null ? mFormat.colorInfo.lumaBitdepth : 8;
        mDecodeInformation = new LcevcNativeAdapter.DecodeInformation(
            mFormat.width, mFormat.height, sar[0], sar[1], bitdepth);

        mLcevcDecoder = new LcevcDecoder(mediaCodec);
        if (!mLcevcDecoder.createDecoder()) {
            throw new RuntimeException("Failed to create LCEVC decoder");
        }
        try {
            mLcevcDecoder.initialise();
        } catch (JSONException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        mLcevcDecoder.setInputFormat(configuration.format);
        mLcevcDecoder.setOutputSurface(configuration.surface);
        mInBandLcevcData = true;
    }

    @Override
    public boolean needsReconfiguration() {
        return false;
    }

    @Override
    public int dequeueInputBufferIndex() {
        return mCodec.dequeueInputBuffer(0);
    }

    @RequiresApi(21)
    @Override
    public int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
        // First check if output resolution has changed
        LcevcDecoder.BufferDetails nextDecodedDetails = mLcevcDecoder.peekNextDecodedDetails();
        if (nextDecodedDetails != null && nextDecodedDetails.info.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
            LcevcNativeAdapter.DecodeInformation decodeInformation = mLcevcDecoder.getDecodeInformation(nextDecodedDetails.info.presentationTimeUs);
            if (decodeInformation != null && decodeInformation.isValid() && !decodeInformation.equals(mDecodeInformation)) {
                mDecodeInformation.set(decodeInformation);
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
            }
        }
        int index = Integer.MAX_VALUE;
        while (mLcevcDecoder.canDecode() && index >= 0) {
            // Dequeue from mediacodec and send to the DIL
            do {
                index = mCodec.dequeueOutputBuffer(bufferInfo, 0);
            } while (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED);

            if (index >= 0) {
                MediaFormat baseMediaFormat = mCodec.getOutputFormat(index);
                Image baseImage = mCodec.getOutputImage(index);
                mLcevcDecoder.maybeDecode(index, bufferInfo, mFormat, baseMediaFormat, baseImage);
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
            }
        }

        if (nextDecodedDetails == null) {
            return MediaCodec.INFO_TRY_AGAIN_LATER;
        }

        mLcevcDecoder.removeDecodedDetails(nextDecodedDetails);
        bufferInfo.set(
            nextDecodedDetails.info.offset,
            nextDecodedDetails.info.size,
            nextDecodedDetails.info.presentationTimeUs,
            nextDecodedDetails.info.flags);
        return nextDecodedDetails.externalIndex;
    }

    @Override
    public MediaFormat getOutputFormat() {
        MediaFormat mediaFormat = mCodec.getOutputFormat();
        if (mDecodeInformation.isValid()) {
            mediaFormat = MediaFormat.createVideoFormat(mediaFormat.getString(MediaFormat.KEY_MIME), mDecodeInformation.width, mDecodeInformation.height);
            mediaFormat.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH, (int)mDecodeInformation.sar_num);
            mediaFormat.setInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT, (int)mDecodeInformation.sar_den);
        }
        return mediaFormat;
    }

    @Override
    @Nullable
    @RequiresApi(21)
    public ByteBuffer getInputBuffer(int index) {
        return mCodec.getInputBuffer(index);
    }

    @Override
    @Nullable
    @RequiresApi(21)
    public ByteBuffer getOutputBuffer(int index) {
        return null;
    }

    @RequiresApi(21)
    public ByteBuffer getMediaCodecOutputBuffer(int index) {
        return mCodec.getOutputBuffer(index);
    }

    @Override
    @RequiresApi(21)
    public void queueInputBuffer(int index, int offset, int size, long presentationTimeUs, int flags) {
        ByteBuffer buffer = mCodec.getInputBuffer(index);
        buffer.position(offset);
        buffer.limit(offset + size);
        if (mInBandLcevcData && flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
            boolean isKeyFrame = (flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            int ret = mLcevcDecoder.addNALData(presentationTimeUs, buffer, isKeyFrame);
            if (ret < 0) {
                Log.e(TAG, "Add NAL Data failed for timeUs = " + presentationTimeUs + ", ret = " + ret);
            }
        }
        // NOTE: Some mediacodec implementations seem to ignore the offset (!), i.e.
        //       they start reading from position 0 regardless of the offset passed here
        mCodec.queueInputBuffer(index, buffer.position(), buffer.remaining(), presentationTimeUs, flags);
    }

    @Override
    public void queueSecureInputBuffer(int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {
        mCodec.queueSecureInputBuffer(index, offset, info.getFrameworkCryptoInfo(), presentationTimeUs, flags);
    }

    @Override
    public void releaseOutputBuffer(int index, boolean render) {
        mLcevcDecoder.renderDecoded(index, 1, render);
    }

    @Override
    @RequiresApi(21)
    public void releaseOutputBuffer(int index, long renderTimeStampNs) {
        long deltaUs = (renderTimeStampNs - System.nanoTime()) / 1000;
        mLcevcDecoder.renderDecoded(index, deltaUs, true);
    }

    @Override
    public void flush() {
        mLcevcDecoder.flush();
        mCodec.flush();
    }

    @Override
    public void release() {
        mCodec.release();
        mLcevcDecoder.destroy();
    }

    @Override
    @RequiresApi(23)
    public void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {
        mCodec.setOnFrameRenderedListener((codec, presentationTimeUs, nanoTime) -> listener.onFrameRendered(LcevcSynchronousMediaCodecAdapter.this, presentationTimeUs, nanoTime), handler);
    }

    @Override
    @RequiresApi(23)
    public void setOutputSurface(Surface surface) {
        mLcevcDecoder.setOutputSurface(surface);
    }

    @Override
    @RequiresApi(19)
    public void setParameters(Bundle params) {
        byte[] lcevcData = params.getByteArray("lcevc-frame-data");
        if (lcevcData != null) {
            mInBandLcevcData = false;
            long presentationTimeUs = params.getLong("lcevc-frame-pts");
            boolean isKeyFrame = params.getBoolean("lcevc-frame-iskey");
            int ret = mLcevcDecoder.addRawData(presentationTimeUs, lcevcData, isKeyFrame);
            if (ret < 0) {
                Log.e(TAG, "Add Raw Data failed for timeUs = " + presentationTimeUs + ", ret = " + ret);
            }
        } else {
            mCodec.setParameters(params);
        }
    }

    @Override
    public void setVideoScalingMode(@C.VideoScalingMode int scalingMode) {
        mCodec.setVideoScalingMode(scalingMode);
    }

    @Override
    @RequiresApi(26)
    public PersistableBundle getMetrics() {
        return mCodec.getMetrics();
    }

    /*
     * @see MediaCodec#getOutputImage(int)
     */
    @RequiresApi(21)
    public Image getOutputImage(int index) {
        return mCodec.getOutputImage(index);
    }
}
