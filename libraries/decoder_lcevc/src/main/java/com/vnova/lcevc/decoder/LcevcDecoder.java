/*
 * Copyright 2014-2025 V-Nova International Limited <legal@v-nova.com>
 * BSD-3-Clause-Clear WITH V-Nova-No-Relicense-Exception:
 * https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_No_Relicense_Exception.txt
*/
package com.vnova.lcevc.decoder;

import com.vnova.lcevc.decoder.dil.LcevcNativeAdapter;
import com.vnova.lcevc.decoder.dil.LcevcConfig;

import static android.media.MediaFormat.KEY_CROP_BOTTOM;
import static android.media.MediaFormat.KEY_CROP_LEFT;
import static android.media.MediaFormat.KEY_CROP_RIGHT;
import static android.media.MediaFormat.KEY_CROP_TOP;

import android.graphics.ImageFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.view.Surface;
import androidx.annotation.RequiresApi;
import androidx.media3.common.Format;
import androidx.media3.common.util.Log;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import org.json.JSONException;

/**
 * Connects the mediacodecAdapter calls to the LCEVC Decoder library.
 */
public class LcevcDecoder extends LcevcNativeAdapter {
    private final String TAG = "LcevcDecoder";
    private static final int MAX_CONCURRENT_DECODES = 3;
    private static final int MAX_QUEUED_DECODES = 6;
    private final int INPUT_CC = 0;
    private MediaCodec mMediaCodec;
    private int mLcevcSyntaxType;
    private LcevcConfig mLcevcConfig;
    private int mImageCount;
    private int mFrameDetailsCount;
    private int mFrameIndex;
    protected ConcurrentSkipListSet<LcevcImage> mIdleBaseImages;
    protected ConcurrentSkipListSet<LcevcImage> mIdleDecodedImages;
    protected ConcurrentSkipListSet<FrameDetails> mFrameDetailsIdle;
    protected ConcurrentSkipListMap<Long, FrameDetails> mFrameDetailsBusy;

    // Buffer details of frames that have been LCEVC decoded
    protected ConcurrentLinkedDeque<BufferDetails> mDecodedFrames;

    // Buffer details of frames that have been submitted to the LCEVC decoder
    protected ConcurrentLinkedQueue<BufferDetails> mDecodingFrames;

    public LcevcDecoder(MediaCodec mediaCodec) {
        mMediaCodec = mediaCodec;
        mLcevcSyntaxType = LCEVC_UnknownNALSyntax;
    }

    public class BufferLayout {

        private int[] planeOffsets;  // offset into buffer for plane data
        private int[] planeSizes;    // width (0) and height (1) of plane data
        private int[] planeLengths;  // length of data in buffer to use

        public int[] offsets() {
            return planeOffsets;
        }

        public int[] sizes() {
            return planeSizes;
        }

        public int[] lengths() {
            return planeLengths;
        }

        public int getNumPlanes() {
            return (planeOffsets == null) ? 0 : planeOffsets.length;
        }

        public void resize(int numPlanes) {
            if ((planeOffsets == null) || (numPlanes != planeOffsets.length)) {
                planeOffsets = new int[numPlanes];
                planeLengths = new int[numPlanes];
                planeSizes = new int[numPlanes * 2];
            }
        }
    }

    public class BufferDetails {
        public int index;
        MediaCodec.BufferInfo info;
        int externalIndex;

        public BufferDetails() {
            this.index = Integer.MIN_VALUE;
            this.info = new MediaCodec.BufferInfo();
            this.externalIndex = -1;
        }

        public void set(int index, MediaCodec.BufferInfo info, int externalIndex) {
            this.index = index;
            this.info.set(info.offset, info.size, info.presentationTimeUs, info.flags);
            this.externalIndex = externalIndex;
        }

        public void reset() {
            this.index = Integer.MIN_VALUE;
            this.info.set(0, 0, 0, 0);
            this.externalIndex = -1;
        }
    }

    /**
     * FrameDetails holds all the details for a frame to be decoded, enhanced and rendered.
     */
    public class FrameDetails implements Comparable<FrameDetails> {
        public static final int STATE_READY = 1;
        public static final int STATE_MISSED_RENDER = 3;
        public static final int STATE_DECODED = 5;

        // For reference the arrays below are divided into 2 blocks
        // if named ??Size -> a 2 element array containing a width and height
        // if named ??Sizes -> a multi element array (multiples of 2) containing n width and height pairs
        // NOTE: base_buffer/texture mode assumes an RGB input until the first frame which then allows it to
        //       setup the correct information
        public int ID;
        public int state;
        public LcevcImage baseImage;          // the DIL_Image object for the base (input) image to be decoded
        public LcevcImage decodeImage;        //` the DIL_Image object for the decoded (output) image
        public int[] baseSize;           // base size of the image
        public int[] baseCropSize;       // crops size, which may or may not be the same as baseSize
        public int[] baseCropOrigin;     // the origin of the crop
        public int sliceHeight;
        ColorParams colorParams;
        DisplayParams displayParams;
        BufferDetails bufferDetails;
        public Format inputFormat;
        public long timehandle;
        public BufferLayout bufferLayout;
        public DecodeInformation decodeInformation;

        @RequiresApi(21)
        public FrameDetails(int id) {
            ID = id;
            baseSize = new int[]{0, 0};
            baseCropSize = new int[]{0, 0};
            baseCropOrigin = new int[]{0, 0};
            colorParams = new ColorParams();
            displayParams = new DisplayParams();
            bufferDetails = new BufferDetails();
            bufferLayout = new BufferLayout();
            decodeInformation = new DecodeInformation();
            reset();
        }

        public int compareTo(FrameDetails other) {
            return (ID - other.ID);
        }

        public void reset() {
            state = STATE_READY;
            bufferDetails.reset();
            inputFormat = null; // this is set every frame
            timehandle = -1;
            Arrays.fill(baseSize, 0);
            Arrays.fill(baseCropSize, 0);
            Arrays.fill(baseCropOrigin, 0);
            decodeInformation.reset();
            releaseBaseImage();
            releaseDecodeImage();
        }

        public void releaseBaseImage() {
            if (baseImage != null) {
                mIdleBaseImages.add(baseImage);
                baseImage = null;
            }
        }

        public void releaseDecodeImage() {
            if (decodeImage != null) {
                mIdleDecodedImages.add(decodeImage);
                decodeImage = null;
            }
        }

        public void handleBaseFormatChanged(Format format, MediaFormat mediaFormat) {
            getSizes(mediaFormat, baseSize, baseCropOrigin, baseCropSize);
            setDisplayAndColorParams(format, mediaFormat);
            this.inputFormat = format;
        }

        private void setDisplayAndColorParams(Format format, MediaFormat outputFormat) {
            sliceHeight = outputFormat.containsKey(MediaFormat.KEY_SLICE_HEIGHT) ? outputFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT) : baseCropSize[1];
            displayParams.rotation = format.rotationDegrees;
            colorParams.colorRange = outputFormat.containsKey(MediaFormat.KEY_COLOR_RANGE) ? outputFormat.getInteger(MediaFormat.KEY_COLOR_RANGE) : 0;
            colorParams.colorStandard = outputFormat.containsKey(MediaFormat.KEY_COLOR_STANDARD) ? outputFormat.getInteger(MediaFormat.KEY_COLOR_STANDARD) : 0;
            displayParams.colorTransfer = outputFormat.containsKey(MediaFormat.KEY_COLOR_TRANSFER) ? outputFormat.getInteger(MediaFormat.KEY_COLOR_TRANSFER) : 0;
            if (outputFormat.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                colorParams.hdrStaticInfo = new byte[24];
                ByteBuffer hdrStaticInfo = outputFormat.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO);
                hdrStaticInfo.get();     // skip descriptor id
                hdrStaticInfo.get(colorParams.hdrStaticInfo, 0, colorParams.hdrStaticInfo.length);
            }
        }

        private void getSizes(MediaFormat outputFormat, int[] size, int[] cropOrigin, int[] cropSize) {
            boolean hasCrop = outputFormat.containsKey(KEY_CROP_RIGHT) && outputFormat.containsKey(KEY_CROP_LEFT) && outputFormat.containsKey(KEY_CROP_BOTTOM) && outputFormat.containsKey(KEY_CROP_TOP);
            size[0] = outputFormat.getInteger(MediaFormat.KEY_WIDTH);
            size[1] = outputFormat.getInteger(MediaFormat.KEY_HEIGHT);
            if (hasCrop) {
                cropOrigin[0] = outputFormat.getInteger(KEY_CROP_LEFT);
                cropOrigin[1] = outputFormat.getInteger(KEY_CROP_TOP);
                cropSize[0] = outputFormat.getInteger(KEY_CROP_RIGHT) - cropOrigin[0] + 1;
                cropSize[1] = outputFormat.getInteger(KEY_CROP_BOTTOM) - cropOrigin[1] + 1;
            } else {
                cropOrigin[0] = 0;
                cropOrigin[1] = 0;
                cropSize[0] = size[0];
                cropSize[1] = size[1];
            }
        }
    }

    /**
     * Load LCEVC Configuration.
     */
    private void loadConfig() throws JSONException, IOException {
        mLcevcConfig = new LcevcConfig();
    }

    @RequiresApi(21)
    /**
     * Create LCEVC Decoder.
     */
    public boolean createDecoder() {
        try {
            loadConfig();
        } catch (JSONException e) {
            return false;
        } catch (IOException e) {
            return false;
        }

        if ((mLcevcDecoderInstance =
                lcevcCreate(
                true,
                0,
                null,
                "{"
                // Uncomment the following two lines to enable stats
                //+ "  \"stats\": true,"
                //+ "  \"stats_file\": \"/sdcard/exo_stats.json\","
                + "  \"simple_render_mode\": true,"
                + "  \"render_late_time_ms\": 86400000"
                + "}"))
                == 0) {
            Log.e(TAG, "Failed to create lcevc decoder");
            return false;
        }

        lcevcRegisterDecodeCallback(mLcevcDecoderInstance, "onDecodeCompleted", "(JIIJ" + DecodeInformation.getNativeClassName() + ")V");
        lcevcRegisterRenderCallback(mLcevcDecoderInstance, "onRenderCompleted", "(JIIJJJ)V");
        lcevcRegisterDrainCallback(mLcevcDecoderInstance, "onDrainCompleted", "(JI)V");
        return true;
    }

    public void setInputFormat(Format format) {
        Log.i(TAG, "Creating MediaCodec adapter for mimeType:container  <" + format.containerMimeType + "> sample <" + format.sampleMimeType + ">");
        String mimeType = "";
        if ((format != null)) {
            if (mimeType.isEmpty() && format.containerMimeType != null) {
                mimeType = format.containerMimeType;
            }
            if (mimeType.isEmpty() && format.sampleMimeType != null) {
                mimeType = format.sampleMimeType;
            }
        }
        if (mLcevcSyntaxType == LCEVC_UnknownNALSyntax) {
            mLcevcSyntaxType = mimeType.contains("vvc") ? LCEVC_NALSyntaxH266 : mimeType.contains("hevc") ? LCEVC_NALSyntaxH265 : LCEVC_NALSyntaxH264;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public BufferDetails peekNextDecodedDetails() {
        BufferDetails first = mDecodedFrames.peekFirst();
        if (first == null) {
            return null;
        }
        BufferDetails last = mDecodedFrames.peekLast();
        // Following logic is to implement pre-roll, i.e. fill up the decoded queue
        // before starting pass decoded frames to the client, unless the back of the queue
        // already contains an EOS, in which case we cannot hold on any longer
        return ((last.externalIndex >= (MAX_QUEUED_DECODES - 1))
            || (last.info.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM)) ?
            first : null;
    }

    public boolean removeDecodedDetails(BufferDetails bufferDetails) {
        return mDecodedFrames.remove(bufferDetails);
    }

    public DecodeInformation getDecodeInformation(long presentationTimeUs) {
        FrameDetails frameDetails = getFrameDetailsFromMap(presentationTimeUs);
        if (frameDetails == null) {
            Log.e(TAG, "getDecodeInformation: could not find a decoded frame with presentationTimeUs=" + presentationTimeUs);
            return null;
        }
        return frameDetails.decodeInformation;
    }

    public boolean canDecode() {
        return mDecodingFrames.size() < MAX_CONCURRENT_DECODES && mDecodedFrames.size() < MAX_QUEUED_DECODES;
    }

    /**
     * Initialise LCEVC Decoder.
     */
    @RequiresApi(21)
    public void initialise() throws JSONException, IOException {
        Log.d(TAG, "Initialising LcevcDecoder");

        mLcevcConfig = new LcevcConfig();

        mFrameDetailsIdle = new ConcurrentSkipListSet<>();
        mFrameDetailsBusy = new ConcurrentSkipListMap<>();

        mDecodedFrames = new ConcurrentLinkedDeque<>();
        mDecodingFrames = new ConcurrentLinkedQueue<>();

        // Now the idle queues for input and output images, currently 1 of each for each FrameDetails object
        mIdleBaseImages = new ConcurrentSkipListSet<>();
        mIdleDecodedImages = new ConcurrentSkipListSet<>();
    }

    /**
     * Provide the supplied surface from mediaCodecAdapter to the LCEVC Decoder.
     */
    public void setOutputSurface(Surface surface) {
        if (surface == null) {
            Log.d(TAG, "SetOutputSurface being called with a Null surface");
        } else {
            Log.d(TAG, "SetOutputSurface being called with non-null, " + surface.toString());
        }
        lcevcSetSurface(mLcevcDecoderInstance, surface, false);
    }

    /**
     * Pass a sequence of NAL units to the LCEVC Decoder for it to extract and process LCEVC Data.
     */
    public int addNALData(long presentationTimeUs, ByteBuffer inputBuffer, boolean isKeyFrame) {
        // Strip SEI data regardless of the device
        return lcevcAddNALData(mLcevcDecoderInstance, INPUT_CC, presentationTimeUs, isKeyFrame, inputBuffer, mLcevcSyntaxType, true);
    }

    /**
     * Pass a raw LCEVC NAL unit to the LCEVC Decoder for it to process.
     */
    public int addRawData(long presentationTimeUs, byte[] data, boolean isKeyFrame) {
        return lcevcAddRawData(mLcevcDecoderInstance, INPUT_CC, presentationTimeUs, isKeyFrame, data);
    }

    /**
     * Decode LCEVC Frame.
     * <p>
     * Setup the FrameDetails based on the format provided and begin the LCEVC decode process.
     */
    @RequiresApi(21)
    public synchronized int maybeDecode(int bufferIndex, MediaCodec.BufferInfo bufferInfo, Format inFormat, MediaFormat mediaFormat, Image image) {
        int ret = LCEVC_Error;
        FrameDetails frameDetails = getIdleFrameDetails();
        // MediaCodec on some Huawei devices report a presentation timeUs in bufferInfo at EOS that is prior to the last frame
        // so it must be corrected to 0, which is the common value from most devices and the one lcevcDrain will expect to use
        long timeUs = bufferInfo.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM ? bufferInfo.presentationTimeUs : 0;
        frameDetails.timehandle = LcevcTimeHandle.getTimeHandle(INPUT_CC, timeUs);
        frameDetails.bufferDetails.set(bufferIndex, bufferInfo, mFrameIndex++);
        mFrameDetailsBusy.put(frameDetails.timehandle, frameDetails);
        mDecodingFrames.add(frameDetails.bufferDetails);
        if (bufferInfo.flags == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
            ret = lcevcDrain(mLcevcDecoderInstance);
        } else {
            frameDetails.baseImage = getIdleBaseImage();
            frameDetails.decodeImage = getIdleDecodedImage();
            frameDetails.handleBaseFormatChanged(inFormat, mediaFormat);
            boolean success = fillBufferLayout(image, frameDetails.sliceHeight, frameDetails.bufferDetails.index, frameDetails.bufferLayout);
            if (!success) {
                Log.e(TAG, "Prepare YUV data failed for timeUs = " + timeUs);
                return -1;
            }
            setupBaseImage(frameDetails, frameDetails.bufferLayout, image);
            ret = lcevcDecode(mLcevcDecoderInstance,
                INPUT_CC,
                timeUs,
                0,
                frameDetails.baseImage.getHandle(),
                frameDetails.decodeImage.getHandle());
            if (ret < 0) {
                Log.e(TAG, "LCEVC Decode failed ret = " + ret + ", timeUs = " + timeUs);
                releaseFrame(frameDetails);
            }
        }
        return ret;
    }

    /**
     * Setup the buffer provided by mediaCodecAdapter to the image format that LCEVC Decoder expects.
     */
    @RequiresApi(21)
    private void setupBaseImage(FrameDetails frameDetails, BufferLayout bufferLayout, Image baseImageIn) {
        LcevcBufferImage baseImageOut = (LcevcBufferImage) frameDetails.baseImage;
        Image.Plane[] baseImageInPlanes = baseImageIn.getPlanes();
        ByteBuffer buffer = baseImageInPlanes[0].getBuffer();
        int numPlanes = bufferLayout != null ? bufferLayout.getNumPlanes() : 1;
        float pixelAspectRatio = frameDetails.inputFormat.pixelWidthHeightRatio;
        int lumaBitDepth = getBitsPerSample(baseImageIn.getFormat()); // assume lumaBD = chromaBD for now

        if (baseImageOut.shouldChange(numPlanes, lumaBitDepth, frameDetails.baseCropSize[0], frameDetails.baseCropSize[1], pixelAspectRatio)) {
            baseImageOut.reset("base changed");
            if (!baseImageOut.create(false, mLcevcConfig.MODIFY_INPUT_IMAGE, false, numPlanes, frameDetails.baseCropSize[0], frameDetails.baseCropSize[1], lumaBitDepth, pixelAspectRatio, frameDetails.colorParams)) {
                return;
            }
        }

        int[] rowByteStrides = new int[numPlanes];
        for (int i = 0; i < numPlanes; i++) {
            int lumaByteDepth = (lumaBitDepth + 7) / 8;
            rowByteStrides[i] = bufferLayout.sizes()[2 * i] * lumaByteDepth;
        }
        int ret = baseImageOut.setPlanes(buffer, bufferLayout.offsets(), rowByteStrides);
        if (ret != LCEVC_Success) {
            Log.e(TAG, "setupBaseImage: ID " + baseImageOut.getID() + " Failed setting plane for " + baseImageOut.getHandle() + " err " + ret);
            baseImageOut.reset("Failed to set buffer planes");
        }
    }

    /**
     * Prepare YUV data from the image provided by mediaCodec to be provided to the LCEVC Decoder.
     */
    @RequiresApi(21)
    public boolean fillBufferLayout(Image baseImage, int sliceHeight, int bufferIndex, BufferLayout bufferLayout) {
        if (baseImage == null) {
            Log.e(TAG, "fillBufferLayout: baseImage is null for index " + bufferIndex);
            return false;
        }
        Rect cropRect = baseImage.getCropRect();
        Image.Plane[] planeObjects = baseImage.getPlanes();
        int numPlanes = (planeObjects[1].getPixelStride() == 1) ? 3 : 2;
        bufferLayout.resize(numPlanes);
        int[] planeSizes = bufferLayout.sizes();
        int[] planeOffsets = bufferLayout.offsets();
        int[] planeLengths = bufferLayout.lengths();
        int totalOffset = 0;
        for (int i = 0; i < numPlanes; ++i) {
            int stride = planeObjects[i].getRowStride();
            int idx = i * 2; // 2 per plane (width and height)
            Point planeCropOrigin = new Point(cropRect.left * planeObjects[i].getPixelStride(), cropRect.top);
            int planeCropHeight = cropRect.bottom - cropRect.top;
            int planeSliceHeight = sliceHeight;
            if (i != 0) {
                planeCropOrigin.set(planeCropOrigin.x / 2, planeCropOrigin.y / 2);
                planeCropHeight /= 2;
                planeSliceHeight /= 2;
            }
            int offset = stride * planeCropOrigin.y + planeCropOrigin.x;
            int length = stride * planeCropHeight;
            planeSizes[idx + 0] = stride;     // width for buffers, might need an adjustment for textures
            planeSizes[idx + 1] = planeCropHeight; // height is always the same (with is different for textures and buffers)
            planeOffsets[i] = totalOffset + offset;
            planeLengths[i] = length;
            totalOffset += stride * planeSliceHeight;
        }
        return true;
    }

    /**
     * Create a new Output image.
     */
    private LcevcImage createOutputImage(int ID) {
        LcevcTextureImage image = new LcevcTextureImage(ID);
        // Use colorFormat = 0 to allow DIL to auto select the best one to use.
        image.create(true, true, true, LCEVC_UnknownColorFormat, 0, 0, 1.0f, new ColorParams(), mLcevcConfig.DECODED_IMAGE_TYPE);
        Log.d(TAG, "Created decode Image " + image.toString() + " desc " + image.getImageDesc().toString());
        return image;
    }

    /**
     * Render and Release Frames.
     * <p>
     * Render Decoded LCEVC Frames available in the queue on the surface provided.
     */
    public synchronized int renderDecoded(int index, long delayUs, boolean shouldRender) {
        FrameDetails frameDetails = getFrameDetailsFromMap(index);
        if (frameDetails == null) {
            Log.e(TAG, "Did not call render: map has no entry with index = " + index);
            return -1;
        }
        int inputCc = LcevcTimeHandle.getCc(frameDetails.timehandle);
        long timeUs = LcevcTimeHandle.getTimeUs(frameDetails.timehandle);
        if (frameDetails.state != FrameDetails.STATE_DECODED) {
            // Request to render has arrived before frame was decoded
            // decode completed callback will arrive, so frame release will happen there
            Log.w(TAG, "Can not render before decoded: index = " + index + ", timeUs = " + timeUs);
            frameDetails.state = FrameDetails.STATE_MISSED_RENDER;
            return -2;
        }
        if (!shouldRender) {
            Log.d(TAG, "Should not render: index = " + index + ", timeUs = " + timeUs + ", state = " + frameDetails.state);
            releaseFrame(frameDetails);
            return -3;
        }

        int ret = lcevcRender(mLcevcDecoderInstance, inputCc, timeUs, frameDetails.decodeImage.getHandle(), frameDetails.displayParams, delayUs);
        if (ret != 0) {
            Log.e(TAG, "Render call failed ret = " + ret + ", index = " + index + ", timeUs = " + timeUs);
        }
        return ret;
    }

    /**
     * Release all the frames in the pipeline.
     */
    public synchronized void releaseAllFrames() {
        for (FrameDetails frameDetails : mFrameDetailsBusy.values()) {
            frameDetails.reset();
            mFrameDetailsIdle.add(frameDetails);
        }
        mFrameDetailsBusy.clear();
        mDecodedFrames.clear();
    }

    /**
     * Release and recycle the FrameDetails when the frame is rendered.
     */
    private void releaseFrame(int inputCc, long timeUs) {
        long timehandle = LcevcTimeHandle.getTimeHandle(inputCc, timeUs);
        FrameDetails frameDetails = mFrameDetailsBusy.get(timehandle);
        if (frameDetails != null) {
            releaseFrame(frameDetails);
        } else {
            Log.e(TAG, "map REMOVE failed for timeUs = " + timeUs);
        }
    }

    private synchronized void releaseFrame(FrameDetails frameDetails) {
        mFrameDetailsBusy.remove(frameDetails.timehandle);
        frameDetails.reset();
        mFrameDetailsIdle.add(frameDetails);
    }

    /**
     * Flush LCEVC Decoder.
     */
    public void flush() {
        Log.d(TAG, "flush()");
        lcevcFlush(mLcevcDecoderInstance);
        mDecodedFrames.clear();
        mDecodingFrames.clear();
        releaseAllFrames();
    }

    /**
     * Destroy LCEVC Decoder.
     */
    public void destroy() {
        lcevcDestroy(mLcevcDecoderInstance);
    }

    /**
     * Callbacks
     * <p>
     * Callback from LCEVC Decoder on completion of decode to tag the frame as decoded and ready to be rendered.
     */
    public void onDecodeCompleted(long instance, int result, int inputCc, long timeUs, DecodeInformation decodeInformation) {
        long timehandle = LcevcTimeHandle.getTimeHandle(inputCc, timeUs);
        FrameDetails frameDetails = mFrameDetailsBusy.get(timehandle);
        if (frameDetails == null) {
            // This can happen only after a flush
            if (result != LCEVC_Flushed) {
                Log.e(TAG, "onDecodeCompleted: could not find frameDetails for timeUs " + timeUs);
            }
            return;
        }
        mDecodingFrames.remove(frameDetails.bufferDetails);
        if (result == LCEVC_Success || result == LCEVC_Flushed) {
            frameDetails.decodeInformation.set(decodeInformation);
        } else {
            Log.e(TAG, "onDecodeCompleted timeUs = " + timeUs + ", result = " + result);
            // do not reset decodeInformation, flush could be just a skip to the next gop
        }
        try {
            mMediaCodec.releaseOutputBuffer(frameDetails.bufferDetails.index, false);
        } catch (IllegalStateException e) {
            Log.e(TAG, "releaseOutputBuffer failed " + e);
        }
        if (frameDetails.state == FrameDetails.STATE_MISSED_RENDER || (result != 0 && result != LCEVC_Flushed)) {
            // There will not be a render, so release
            releaseFrame(frameDetails);
            return;
        }
        frameDetails.state = FrameDetails.STATE_DECODED;
        mDecodedFrames.add(frameDetails.bufferDetails);
    }

    /**
     * Callback from LCEVC Decoder on completion of render to reset and recycle the resources associated to that frame.
     */
    public void onRenderCompleted(long instance, int result, int inputCc, long timeUs, long imageHdl, long completionTimeUs) {
        if (result != 0) {
            Log.e(TAG, "onRenderCompleted failed " + result + ", timeUs " + timeUs);
        }
        releaseFrame(inputCc, timeUs);
    }

    public void onDrainCompleted(long instance, int result) {
        // Make sure the client gets the last pending index at the next dequeue output index call,
        // which will trigger its internal end of stream processing
        onDecodeCompleted(instance, result, INPUT_CC, 0, null);
    }

    /**
     * Internal functions.
     * <p>
     * What we mean by a "sample" is a Y, or a U, or a V (if it's YUV), or else an entire RGB. This
     * is different from the concept of a "pixel" as used in ImageFormat.java's "getBitsPerPixel".
     * Specifically:
     * totalBits = bitsPerPixel * resolution[0]
     * = bitsPerSample * (resolution[0] + resolution[1] + resolution[2])
     * Note that RGB images are treated as having one plane, while images in the YUV family will
     * either have 1, 2, or 3 planes depending on their interleaving.
     */
    private int getBitsPerSample(int format) {
        switch (format) {
            case ImageFormat.NV16:
            case ImageFormat.NV21:
            case ImageFormat.YUY2:
            case ImageFormat.YUV_420_888:
            case ImageFormat.YUV_422_888:
            case ImageFormat.YUV_444_888:
            case ImageFormat.Y8:
            case ImageFormat.YV12:
                return 8;

            case ImageFormat.RAW10:
            case 0X36: // ImageFormat.YCBCR_P010: (not available in current compileSdkVersion)
                return 10;

            case ImageFormat.RAW12:
                return 12;

            case ImageFormat.RGB_565:
            case ImageFormat.RAW_SENSOR:
            case 0x1002: // ImageFormat.RAW_DEPTH: (not available in current compileSdkVersion)
            case 0x20363159: // ImageFormat.Y16: (not available in current compileSdkVersion)
            case ImageFormat.DEPTH16:
                return 16;

            case ImageFormat.FLEX_RGB_888:
                return 24;

            case ImageFormat.FLEX_RGBA_8888:
                return 32;
        }

        Log.e(TAG, "Unrecognised format: " + format);
        return -1;
    }

    @RequiresApi(21)
    protected FrameDetails getIdleFrameDetails() {
        if (mFrameDetailsIdle.isEmpty()) {
            return new FrameDetails(mFrameDetailsCount++);
        }
        FrameDetails ret = mFrameDetailsIdle.pollFirst();
        return ret;
    }

    protected LcevcImage getIdleBaseImage() {
        if (mIdleBaseImages.isEmpty()) {
            return new LcevcBufferImage(mImageCount++);
        }
        LcevcImage ret = mIdleBaseImages.pollFirst();
        return ret;
    }

    protected LcevcImage getIdleDecodedImage() {
        if (mIdleDecodedImages.isEmpty()) {
            return createOutputImage(mImageCount++);
        }
        LcevcImage ret = mIdleDecodedImages.pollFirst();
        return ret;
    }

    /**
     * From the frameDetails map return the first, as in with lowest timehandle, value entry with the desired index,
     * and return non EOS frameDetails first
     */
    protected FrameDetails getFrameDetailsFromMap(int externalIndex) {
        FrameDetails ret = null;
        for (FrameDetails frameDetails : mFrameDetailsBusy.values()) {
            if (frameDetails.bufferDetails.externalIndex == externalIndex) {
                ret = frameDetails;
                if (frameDetails.bufferDetails.info.flags != MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                    break;
                }
            }
        }
        return ret;
    }

    /**
     * From the frameDetails map return the value entry with the desired timehandle
     */
    protected FrameDetails getFrameDetailsFromMap(long presentationTimeUs) {
        long timehandle = LcevcTimeHandle.getTimeHandle(INPUT_CC, presentationTimeUs);
        return mFrameDetailsBusy.get(timehandle);
    }
}
