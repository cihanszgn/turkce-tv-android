package dev.turkce.olcum

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KareTest {
    /** A recognisable grey value for every frame pixel. */
    private fun pixel(x: Int, y: Int) = (x * 31 + y * 17) and 0xff

    /** One image plane as a decoder hands it over: padded rows, a pixel step, and no padding after the last row. */
    private fun plane(frameW: Int, frameH: Int, rowStride: Int, pixelStride: Int): ByteBuffer {
        val bytes = ByteArray(rowStride * (frameH - 1) + (frameW - 1) * pixelStride + 1) { 0x55 }
        for (y in 0 until frameH) for (x in 0 until frameW) bytes[y * rowStride + x * pixelStride] = pixel(x, y).toByte()
        return ByteBuffer.wrap(bytes)
    }

    private fun expected(width: Int, height: Int, source: (Int, Int) -> Pair<Int, Int>) =
        ByteArray(width * height) { val (x, y) = source(it % width, it / width); pixel(x, y).toByte() }

    @Test
    fun paddedRowsGiveTheSameSubtitleBand() {
        val picture = PictureRect.of(1920, 1088, 0, 0, 1920, 1088, FRAME_W, FRAME_H)
        val band = copyPlane(plane(1920, 1088, 2048, 1), 2048, 1, picture, CROP_X, CROP_Y, CROP_W, CROP_H)
        assertArrayEquals(expected(CROP_W, CROP_H) { x, y -> CROP_X + x to CROP_Y + y }, band)
    }

    @Test
    fun interleavedPixelsAreReadAtTheirStep() {
        val picture = PictureRect(0, 0, 1920, 1080)
        val band = copyPlane(plane(1920, 1080, 3840, 2), 3840, 2, picture, CROP_X, CROP_Y, CROP_W, CROP_H)
        assertArrayEquals(expected(CROP_W, CROP_H) { x, y -> CROP_X + x to CROP_Y + y }, band)
    }

    @Test
    fun aCropOffsetMovesTheArea() {
        val picture = PictureRect.of(1920, 1096, 0, 8, 1920, 1088, FRAME_W, FRAME_H)
        assertEquals(PictureRect(0, 8, 1920, 1080), picture)
        val corner = copyPlane(plane(1920, 1096, 1920, 1), 1920, 1, picture, 0, 0, 10, 10)
        assertArrayEquals(expected(10, 10) { x, y -> x to y + 8 }, corner)
    }

    @Test
    fun aSmallerPictureIsScaledToTheSameArea() {
        val picture = PictureRect(0, 0, 1280, 720)
        val part = copyPlane(plane(1280, 720, 1280, 1), 1280, 1, picture, 960, 540, 6, 3)
        assertArrayEquals(expected(6, 3) { x, y -> (960 + x) * 2 / 3 to (540 + y) * 2 / 3 }, part)
    }

    @Test
    fun theWholePictureCanBeShrunk() {
        val picture = PictureRect(0, 0, 1920, 1080)
        val preview = copyPlane(plane(1920, 1080, 1920, 1), 1920, 1, picture, 0, 0, 480, 270, 480, 270)
        assertArrayEquals(expected(480, 270) { x, y -> x * 4 to y * 4 }, preview)
    }

    @Test
    fun alignmentPaddingIsLeftOut() {
        assertEquals(PictureRect(0, 0, 1920, 1080), PictureRect.of(1920, 1088, 0, 0, 1920, 1088, 1920, 1080))
        assertEquals(PictureRect(0, 0, 1920, 1080), PictureRect.of(1920, 1080, 0, 0, 1920, 1080, 1920, 1080))
        assertEquals(PictureRect(0, 0, 1920, 1080), PictureRect.of(1920, 1088, 0, 0, 1920, 1080, 1920, 1080))
        assertEquals(PictureRect(0, 0, 1920, 1080), PictureRect.of(1920, 1080, -4, 0, 4000, 1080, 1920, 1080))
    }

    @Test
    fun anotherStreamSizeIsKept() {
        assertEquals(PictureRect(0, 0, 1280, 720), PictureRect.of(1280, 720, 0, 0, 1280, 720, 1920, 1080))
        assertEquals(PictureRect(0, 0, 3840, 2160), PictureRect.of(3840, 2160, 0, 0, 3840, 2160, 1920, 1080))
    }

    @Test
    fun theAreaStaysInsideTheFrame() {
        assertEquals(PictureRect(0, 0, 1920, 1080), PictureRect(0, 0, 1920, 1088).inside(1920, 1080))
        val full = PictureRect(0, 0, 1920, 1080)
        assertEquals(1919, full.column(1919, 1920))
        assertEquals(1079, full.row(1080, 1080))
        assertEquals(0, full.column(-3, 1920))
    }

    private fun decoder(name: String, hardware: Boolean = true, flexible: Boolean = true, fits: Boolean = true) =
        DecoderInfo(name, hardware, flexible, fits)

    @Test
    fun theSonyDecoderGoesFirst() {
        val order = decoderOrder(listOf(decoder("c2.android.avc.decoder", hardware = false), decoder(HARDWARE)))
        assertEquals(listOf(HARDWARE, "c2.android.avc.decoder"), order)
    }

    @Test
    fun hardwareGoesBeforeSoftware() {
        val order = decoderOrder(listOf(decoder("c2.android.avc.decoder", hardware = false), decoder("c2.vendor.avc.decoder")))
        assertEquals(listOf("c2.vendor.avc.decoder", "c2.android.avc.decoder"), order)
    }

    @Test
    fun aDecoderThatCannotTakeTheSizeGoesLast() {
        val order = decoderOrder(listOf(decoder("c2.small.avc.decoder", fits = false), decoder("c2.android.avc.decoder", hardware = false)))
        assertEquals(listOf("c2.android.avc.decoder", "c2.small.avc.decoder"), order)
    }

    @Test
    fun readableFramesGoFirstAndOtherwiseThePlatformOrderStays() {
        val order = decoderOrder(listOf(
            decoder("OMX.a.avc.decoder", flexible = false), decoder("OMX.b.avc.decoder"),
            decoder("OMX.c.avc.decoder"), decoder("OMX.b.avc.decoder"),
        ))
        assertEquals(listOf("OMX.b.avc.decoder", "OMX.c.avc.decoder", "OMX.a.avc.decoder"), order)
    }

    @Test
    fun softwareDecodersAreKnownByName() {
        listOf("OMX.google.h264.decoder", "c2.android.avc.decoder", "OMX.ffmpeg.h264.decoder", "OMX.vendor.avc.decoder.sw")
            .forEach { assertTrue(it, softwareName(it)) }
        listOf(HARDWARE, "c2.mtk.avc.decoder", "OMX.amlogic.avc.decoder.awesome", "OMX.qcom.video.decoder.avc")
            .forEach { assertFalse(it, softwareName(it)) }
    }
}
