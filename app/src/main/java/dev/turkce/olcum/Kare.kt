package dev.turkce.olcum

import android.media.Image
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import java.nio.ByteBuffer

/** Padding a decoder may add to a frame's size for alignment (1080 rows often come as 1088). */
private const val ALIGN_SLACK = 16

/** Shown on the phone and the TV when no decoder on this TV opens or gives readable frames. */
internal const val NO_DECODER = "Bu televizyonda kullanılabilir H.264 çözücü bulunamadı."

/** The part of a decoded frame that holds the picture, in the frame's own pixels. */
internal data class PictureRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    /** The frame column that shows column [x] of a picture [pictureW] wide. */
    fun column(x: Int, pictureW: Int): Int = left + (x.toLong() * width / pictureW).toInt().coerceIn(0, width - 1)

    /** The frame row that shows row [y] of a picture [pictureH] high. */
    fun row(y: Int, pictureH: Int): Int = top + (y.toLong() * height / pictureH).toInt().coerceIn(0, height - 1)

    /** The same area cut to a [frameW]x[frameH] frame, for a format that described a bigger one. */
    fun inside(frameW: Int, frameH: Int): PictureRect {
        val l = left.coerceIn(0, frameW - 1)
        val t = top.coerceIn(0, frameH - 1)
        return PictureRect(l, t, width.coerceIn(1, frameW - l), height.coerceIn(1, frameH - t))
    }

    companion object {
        /**
         * Where the picture sits in a [frameW]x[frameH] frame. A crop smaller than the frame is taken as it is
         * (right and bottom exclusive). Without one, padding of up to [ALIGN_SLACK] past the stream's size is left out.
         */
        fun of(
            frameW: Int, frameH: Int, cropLeft: Int, cropTop: Int, cropRight: Int, cropBottom: Int,
            streamW: Int, streamH: Int,
        ): PictureRect {
            val left = cropLeft.coerceIn(0, frameW - 1)
            val top = cropTop.coerceIn(0, frameH - 1)
            val right = cropRight.coerceIn(left + 1, frameW)
            val bottom = cropBottom.coerceIn(top + 1, frameH)
            val cropped = left > 0 || top > 0 || right < frameW || bottom < frameH
            if (cropped) return PictureRect(left, top, right - left, bottom - top)
            fun trim(size: Int, stream: Int) = if (size - stream in 1 until ALIGN_SLACK) stream else size
            return PictureRect(0, 0, trim(frameW, streamW), trim(frameH, streamH))
        }
    }
}

/** The picture area of a decoded image, from the crop rectangle the decoder gave it. */
internal fun Image.picture(streamW: Int = FRAME_W, streamH: Int = FRAME_H): PictureRect {
    val crop = cropRect
    return PictureRect.of(width, height, crop.left, crop.top, crop.right, crop.bottom, streamW, streamH)
}

/** The picture area a decoder's output format describes (crop-right and crop-bottom are inclusive there). */
internal fun MediaFormat.picture(streamW: Int = FRAME_W, streamH: Int = FRAME_H): PictureRect? {
    if (!containsKey(MediaFormat.KEY_WIDTH) || !containsKey(MediaFormat.KEY_HEIGHT)) return null
    val w = getInteger(MediaFormat.KEY_WIDTH)
    val h = getInteger(MediaFormat.KEY_HEIGHT)
    if (w <= 0 || h <= 0) return null
    fun value(key: String, default: Int) = if (containsKey(key)) getInteger(key) else default
    return PictureRect.of(
        w, h, value("crop-left", 0), value("crop-top", 0), value("crop-right", w - 1) + 1, value("crop-bottom", h - 1) + 1,
        streamW, streamH,
    )
}

/** A [width]x[height] area in 1920x1080 picture coordinates, as grey pixels, from any decoded frame. */
internal fun cropLuma(
    image: Image, picture: PictureRect = image.picture(),
    left: Int = CROP_X, top: Int = CROP_Y, width: Int = CROP_W, height: Int = CROP_H,
): ByteArray {
    val plane = image.planes[0]
    return copyPlane(plane.buffer, plane.rowStride, plane.pixelStride, picture, left, top, width, height)
}

/**
 * Copies a [width]x[height] area given in [pictureW]x[pictureH] picture coordinates out of one image plane,
 * whatever the decoder's row padding, pixel step and picture size. A 1920x1080 picture at the frame's corner is
 * copied byte for byte; any other size is scaled to the requested area.
 */
internal fun copyPlane(
    buffer: ByteBuffer, rowStride: Int, pixelStride: Int, picture: PictureRect,
    left: Int, top: Int, width: Int, height: Int, pictureW: Int = FRAME_W, pictureH: Int = FRAME_H,
): ByteArray {
    val source = buffer.duplicate()
    val out = ByteArray(width * height)
    val direct = picture.width == pictureW && picture.height == pictureH && pixelStride == 1 &&
        left >= 0 && left + width <= pictureW
    val columns = if (direct) null else IntArray(width) { picture.column(left + it, pictureW) * pixelStride }
    for (y in 0 until height) {
        val row = picture.row(top + y, pictureH) * rowStride
        if (columns == null) {
            source.position(row + picture.left + left)
            source.get(out, y * width, width)
        } else {
            for (x in 0 until width) out[y * width + x] = source.get(row + columns[x])
        }
    }
    return out
}

/** What the TV offers for H.264, as far as choosing between decoders goes. */
internal class DecoderInfo(val name: String, val hardware: Boolean, val flexible: Boolean, val fits: Boolean)

/**
 * The order to try decoders in: [preferred] (the Sony's MediaTek decoder) when present, then any that can take
 * the size, hardware before software, and decoders that list readable YUV 4:2:0 frames first.
 * Otherwise the platform's own order is kept.
 */
internal fun decoderOrder(found: List<DecoderInfo>, preferred: String = HARDWARE): List<String> =
    found.sortedWith(compareBy<DecoderInfo>({ it.name != preferred }, { !it.fits }, { !it.hardware }, { !it.flexible }))
        .map { it.name }.distinct()

/**
 * The H.264 decoders to try, in the order [decoderOrder] gives; secure-only decoders are left out. The Sony's
 * decoder is tried first even when the list does not name it, since creating it by name is what has worked there.
 */
internal fun avcDecoders(width: Int = FRAME_W, height: Int = FRAME_H): List<String> {
    val mime = MediaFormat.MIMETYPE_VIDEO_AVC
    val found = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info.isAlias)
    }.mapNotNull { info ->
        val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return@mapNotNull null
        if (caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)) return@mapNotNull null
        val hardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else !softwareName(info.name)
        DecoderInfo(
            info.name, hardware,
            flexible = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in caps.colorFormats,
            fits = caps.videoCapabilities?.isSizeSupported(width, height) ?: true,
        )
    }
    return (listOf(HARDWARE) + decoderOrder(found)).distinct()
}

/** Android's own naming of software codecs, for TVs older than Android 10. */
internal fun softwareName(name: String): Boolean {
    val lower = name.lowercase()
    return lower.startsWith("omx.google.") || lower.startsWith("c2.android.") || lower.startsWith("omx.ffmpeg.") ||
        ".sw." in lower || lower.endsWith(".sw")
}
