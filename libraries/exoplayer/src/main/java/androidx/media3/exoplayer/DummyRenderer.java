package androidx.media3.exoplayer;

import androidx.annotation.CallSuper;
import androidx.media3.common.C;
import androidx.media3.common.Format;

public class DummyRenderer extends BaseRenderer {

  private static final String TAG = "DummyRenderer";

  private final String mimeType;
  /**
   * @param trackType The track type that the renderer handles. One of the {@link C} {@code
   *                  TRACK_TYPE_*} constants.
   */
  public DummyRenderer(@C.TrackType int trackType, String mimeType) {
    super(trackType);
    this.mimeType = mimeType;
  }

  public String getName() {
    return TAG;
  }

  @Override
  public void setPlaybackSpeed(float currentPlaybackSpeed, float targetPlaybackSpeed)
      throws ExoPlaybackException {
    super.setPlaybackSpeed(currentPlaybackSpeed, targetPlaybackSpeed);
  }

  @Override
  public void enableMayRenderStartOfStream() {
    super.enableMayRenderStartOfStream();
  }

  @Override
  public @Capabilities int supportsFormat(Format format) throws ExoPlaybackException {
    if (!mimeType.equalsIgnoreCase(format.sampleMimeType)) {
      return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
    }
    return RendererCapabilities.create(
        C.FORMAT_HANDLED,
        ADAPTIVE_NOT_SEAMLESS,
        TUNNELING_NOT_SUPPORTED,
        HARDWARE_ACCELERATION_SUPPORTED,
        DECODER_SUPPORT_PRIMARY);
  }

  @CallSuper
  @Override
  public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
    // Do nothing.
  }

  @Override
  public boolean isReady() {
    return true;
  }

  @Override
  public boolean isEnded() {
    return true;
  }

  @Override
  public boolean hasReadStreamToEnd() {
    return true;
  }
}
