/*
 * Copyright 2014-2025 V-Nova International Limited <legal@v-nova.com>
 * BSD-3-Clause-Clear WITH V-Nova-No-Relicense-Exception:
 * https://raw.githubusercontent.com/v-novaltd/licenses/refs/heads/main/V-Nova_No_Relicense_Exception.txt
*/
package com.vnova.lcevc.decoder;

import android.content.Context;
import android.os.Handler;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import java.lang.reflect.Constructor;
import java.util.ArrayList;


/** Extending {@link DefaultRenderersFactory}. */
@UnstableApi
public class LcevcRenderersFactory extends DefaultRenderersFactory {
    private static final String TAG = "LcevcRenderersFactory";
    /**
     * @param context A {@link Context}.
     */
    public LcevcRenderersFactory(Context context) {
        super(context);
    }

    /**
     * Builds video renderers for use by the player.
     *
     * @param context The {@link Context} associated with the player.
     * @param extensionRendererMode The extension renderer mode.
     * @param mediaCodecSelector A decoder selector.
     * @param enableDecoderFallback Whether to enable fallback to lower-priority decoders if decoder
     *     initialization fails. This may result in using a decoder that is slower/less efficient than
     *     the primary decoder.
     * @param eventHandler A handler associated with the main thread's looper.
     * @param eventListener An event listener.
     * @param allowedVideoJoiningTimeMs The maximum duration for which video renderers can attempt to
     *     seamlessly join an ongoing playback, in milliseconds.
     * @param out An array to which the built renderers should be appended.
     */
    @Override
    protected void buildVideoRenderers(
            Context context,
            @LcevcRenderersFactory.ExtensionRendererMode int extensionRendererMode,
            MediaCodecSelector mediaCodecSelector,
            boolean enableDecoderFallback,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            long allowedVideoJoiningTimeMs,
            ArrayList<Renderer> out) {
        MediaCodecAdapter.Factory videoMediaCodecAdapterFactory = new LcevcMediaCodecAdapterFactory();

        MediaCodecVideoRenderer videoRenderer =
                new MediaCodecVideoRenderer(
                        context,
                        videoMediaCodecAdapterFactory,
                        mediaCodecSelector,
                        allowedVideoJoiningTimeMs,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
        out.add(videoRenderer);

        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF) {
            return;
        }
        int extensionRendererIndex = out.size();
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) {
            extensionRendererIndex--;
        }

        try {
            // Full class names used for constructor args so the LINT rule triggers if any of them move.
            Class<?> clazz = Class.forName("androidx.media3.decoder.vp9.LibvpxVideoRenderer");
            Constructor<?> constructor =
                    clazz.getConstructor(
                            long.class,
                            android.os.Handler.class,
                            androidx.media3.exoplayer.video.VideoRendererEventListener.class,
                            int.class);
            Renderer renderer =
                    (Renderer)
                            constructor.newInstance(
                                    allowedVideoJoiningTimeMs,
                                    eventHandler,
                                    eventListener,
                                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
            out.add(extensionRendererIndex++, renderer);
            Log.i(TAG, "Loaded LibvpxVideoRenderer.");
        } catch (ClassNotFoundException e) {
            // Expected if the app was built without the extension.
        } catch (Exception e) {
            // The extension is present, but instantiation failed.
            throw new RuntimeException("Error instantiating VP9 extension", e);
        }

        try {
            // Full class names used for constructor args so the LINT rule triggers if any of them move.
            Class<?> clazz = Class.forName("androidx.media3.decoder.av1.Libgav1VideoRenderer");
            Constructor<?> constructor =
                    clazz.getConstructor(
                            long.class,
                            android.os.Handler.class,
                            androidx.media3.exoplayer.video.VideoRendererEventListener.class,
                            int.class);
            Renderer renderer =
                    (Renderer)
                            constructor.newInstance(
                                    allowedVideoJoiningTimeMs,
                                    eventHandler,
                                    eventListener,
                                    MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY);
            out.add(extensionRendererIndex++, renderer);
            Log.i(TAG, "Loaded Libgav1VideoRenderer.");
        } catch (ClassNotFoundException e) {
            // Expected if the app was built without the extension.
        } catch (Exception e) {
            // The extension is present, but instantiation failed.
            throw new RuntimeException("Error instantiating AV1 extension", e);
        }
    }
}
