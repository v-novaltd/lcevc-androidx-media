
# Changes to the AndroidX/Media project

Most of the changes needed for decoding LCEVC are contained within `libraries/decoder_lcevc` but there are some additional changes implemented to the AndroidX/Media codebase to reflect the nature of LCEVC that the output characteristics of the video are different (enhanced) than the input ones, and so the output video metadata should be taken from the relevant output objects, and not the stream metadata. In fact this is correct in general, regardless of LCEVC. Another minor change is in the main demo app, to allow loading a user xml media list and thus be able to play user defined contents.

The following notes detail the alterations to the core AndroidX/Media and the rationale behind each change.

1. Changes in `DebugTextViewHelper.java`

    This change was required to report the width and height values in the `debugTextViewHelper` from the output video and not from the input metadata.

```
--- a/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/util/DebugTextViewHelper.java	2024-02-06 18:06:11.012448300 +0000
+++ b/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/util/DebugTextViewHelper.java	2024-11-05 09:57:10.868126500 +0000
@@ -22,6 +22,7 @@
 import androidx.media3.common.ColorInfo;
 import androidx.media3.common.Format;
 import androidx.media3.common.Player;
+import androidx.media3.common.VideoSize;
 import androidx.media3.common.util.Assertions;
 import androidx.media3.common.util.UnstableApi;
 import androidx.media3.exoplayer.DecoderCounters;
@@ -127,6 +128,7 @@
   @UnstableApi
   protected String getVideoString() {
     Format format = player.getVideoFormat();
+    VideoSize videoSize = player.getVideoSize();
     DecoderCounters decoderCounters = player.getVideoDecoderCounters();
     if (format == null || decoderCounters == null) {
       return "";
@@ -136,11 +138,11 @@
         + "(id:"
         + format.id
         + " r:"
-        + format.width
+        + videoSize.width
         + "x"
-        + format.height
+        + videoSize.height
         + getColorInfoString(format.colorInfo)
-        + getPixelAspectRatioString(format.pixelWidthHeightRatio)
+        + getPixelAspectRatioString(videoSize.pixelWidthHeightRatio)
         + getDecoderCountersBufferCountString(decoderCounters)
         + " vfpo: "
         + getVideoFrameProcessingOffsetAverageString(
```
Note: this change is not LCEVC specific, but has general validity. It has been merged in main androidx/media3 for release since tag 1.3.1.


2. Changes in `MediaCodecVideoRenderer.java`

    These changes were required for two reasons. Firstly to take into account that the LCEVC decoding may have changed the pixel aspect ratio of the output media format. Secondly to pass the LCEVC encoded data in supplemental data buffer to the MediaCodecAdapter via the codec parameters API:

```
--- a/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java	2024-02-06 18:10:13.948654900 +0000
+++ b/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/video/MediaCodecVideoRenderer.java	2024-11-01 16:04:30.593862800 +0000
@@ -1219,7 +1219,12 @@
? mediaFormat.getInteger(KEY_CROP_BOTTOM) - mediaFormat.getInteger(KEY_CROP_TOP) + 1
: mediaFormat.getInteger(MediaFormat.KEY_HEIGHT);
}
-    pixelWidthHeightRatio = format.pixelWidthHeightRatio;
+    boolean hasPixelAspectRatio = mediaFormat.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH)
+        && mediaFormat.containsKey(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT);
+    pixelWidthHeightRatio = hasPixelAspectRatio ?
+        mediaFormat.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_WIDTH) /
+            mediaFormat.getInteger(MediaFormat.KEY_PIXEL_ASPECT_RATIO_HEIGHT)
+        : format.pixelWidthHeightRatio;
  if (codecAppliesRotation()) {
  // On API level 21 and above the decoder applies the rotation when rendering to the surface.
  // Hence currentUnappliedRotation should always be 0. For 90 and 270 degree rotations, we need
  @@ -1255,18 +1260,36 @@
  @TargetApi(29) // codecHandlesHdr10PlusOutOfBandMetadata is false if Util.SDK_INT < 29
  protected void handleInputBufferSupplementalData(DecoderInputBuffer buffer)
  throws ExoPlaybackException {
-    if (!codecHandlesHdr10PlusOutOfBandMetadata) {
+    ByteBuffer data = buffer.supplementalData;
+
+    if (data == null || data.remaining() < 7) {
     return;
     }
-    ByteBuffer data = checkNotNull(buffer.supplementalData);
-    if (data.remaining() >= 7) {
-      // Check for HDR10+ out-of-band metadata. See User_data_registered_itu_t_t35 in ST 2094-40.
-      byte ituTT35CountryCode = data.get();
-      int ituTT35TerminalProviderCode = data.getShort();
-      int ituTT35TerminalProviderOrientedCode = data.getShort();
-      byte applicationIdentifier = data.get();
-      byte applicationVersion = data.get();
+
+    // Read the first 7 bytes to determine the payload type
+    byte byte0 = data.get();
+    int short1 = data.getShort();
+    int short3 = data.getShort();
+    byte byte5 = data.get();
+    byte byte6 = data.get();
+    data.position(0);
+
+    if (byte0 == (byte)0x00 && short1 == 0x0001 && (short3 == 0x7BFF || short3 == 0x79FF)) {
+      // ISO/IEC 23094-2 payload
+      byte[] lcevcData = new byte[data.remaining()];
+      data.get(lcevcData);
       data.position(0);
+      setLcevcData(checkNotNull(getCodec()), buffer.timeUs, buffer.isKeyFrame(), lcevcData);
+    } else {
+      if (!codecHandlesHdr10PlusOutOfBandMetadata) {
+        return;
+      }
+      // Check for HDR10+ out-of-band metadata. See User_data_registered_itu_t_t35 in ST 2094-40.
+      byte ituTT35CountryCode = byte0;
+      int ituTT35TerminalProviderCode = short1;
+      int ituTT35TerminalProviderOrientedCode = short3;
+      byte applicationIdentifier = byte5;
+      byte applicationVersion = byte6;
       if (ituTT35CountryCode == (byte) 0xB5
           && ituTT35TerminalProviderCode == 0x003C
           && ituTT35TerminalProviderOrientedCode == 0x0001
@@ -1834,6 +1857,15 @@
codec.setParameters(codecParameters);
}

+  @RequiresApi(29)
+  private static void setLcevcData(MediaCodecAdapter codec, long timeUs, boolean isKeyFrame, byte[] lcevcData) {
+    Bundle codecParameters = new Bundle();
+    codecParameters.putLong("lcevc-frame-pts", timeUs);
+    codecParameters.putBoolean("lcevc-frame-iskey", isKeyFrame);
+    codecParameters.putByteArray("lcevc-frame-data", lcevcData);
+    codec.setParameters(codecParameters);
+  }
+
@RequiresApi(23)
protected void setOutputSurfaceV23(MediaCodecAdapter codec, Surface surface) {
codec.setOutputSurface(surface);
```
Note: the change related to pixel aspect ratio is not LCEVC specific, but has general validity. It has been merged in main androidx/media3 for release since tag 1.5.0.

3. Changes in `MatroskaExtractor.java`

   This change was required to allow extracting LCEVC encoded data found in webm/mkv containers and pass them on as supplemental data.

```
--- a/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java	2024-02-06 18:10:14.078037300 +0000
+++ b/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java	2024-10-08 18:14:32.591249300 +0100
@@ -250,10 +250,16 @@
   private static final int ID_LUMNINANCE_MIN = 0x55DA;

   /**
-   * BlockAddID value for ITU T.35 metadata in a VP9 track. See also
-   * https://www.webmproject.org/docs/container/.
+   * BlockAddID value for BlockAdditional data as ITU T.35 metadata. See also
+   * https://www.matroska.org/technical/codec_specs.html.
    */
-  private static final int BLOCK_ADDITIONAL_ID_VP9_ITU_T_35 = 4;
+  private static final int BLOCK_ADDITIONAL_TYPE_ITU_T_35 = 4;
+
+  /**
+   * BlockAddID value for BlockAdditional data as ISO/IEC 23094-2 encoded data. See also
+   * https://www.lcevc.org/.
+   */
+  private static final int BLOCK_ADDITIONAL_TYPE_LCEVC = 5;

   /**
    * BlockAddIdType value for Dolby Vision configuration with profile <= 7. See also
@@ -1370,8 +1376,8 @@
   protected void handleBlockAdditionalData(
       Track track, int blockAdditionalId, ExtractorInput input, int contentSize)
       throws IOException {
-    if (blockAdditionalId == BLOCK_ADDITIONAL_ID_VP9_ITU_T_35
-        && CODEC_ID_VP9.equals(track.codecId)) {
+    if (blockAdditionalId == BLOCK_ADDITIONAL_TYPE_ITU_T_35
+        || blockAdditionalId == BLOCK_ADDITIONAL_TYPE_LCEVC) {
       supplementalData.reset(contentSize);
       input.readFully(supplementalData.getData(), 0, contentSize);
     } else {
@@ -2399,13 +2405,7 @@
      * @param isBlockGroup Whether the samples are from a BlockGroup.
      */
     private boolean samplesHaveSupplementalData(boolean isBlockGroup) {
-      if (CODEC_ID_OPUS.equals(codecId)) {
-        // At the end of a BlockGroup, a positive DiscardPadding value will be written out as
-        // supplemental data for Opus codec. Otherwise (i.e. DiscardPadding <= 0) supplemental data
-        // size will be 0.
-        return isBlockGroup;
-      }
-      return maxBlockAdditionId > 0;
+      return isBlockGroup || maxBlockAdditionId > 0;
     }

     /** Returns the HDR Static Info as defined in CTA-861.3. */
```

4. Changes in `libraries/extractor/.../ts`, as per https://github.com/androidx/media/pull/1189, released in tag 1.4.0.
