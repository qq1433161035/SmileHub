package brut.androlib.res.decoder

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import brut.androlib.exceptions.AndrolibException
import brut.androlib.exceptions.CantFind9PatchChunkException
import brut.androlib.res.data.ninepatch.NinePatchData
import brut.androlib.res.data.ninepatch.OpticalInset
import brut.util.ExtDataInput
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Replacement for apktool's nine-patch decoder.
 *
 * The upstream implementation reads and writes PNG through `javax.imageio` and
 * `java.awt`, neither of which exists on Android. This version keeps the same
 * algorithm and chunk parsing but draws with Bitmap/Canvas. It lives in the
 * engine dex so apktool never needs those desktop APIs inside the APK.
 */
class Res9patchStreamDecoder : ResStreamDecoder {

    override fun decode(input: InputStream, output: OutputStream) {
        try {
            val data = input.readBytes()
            if (data.isEmpty()) return

            val source = BitmapFactory.decodeByteArray(data, 0, data.size)
                ?: throw AndrolibException("nine-patch is not a decodable image")
            val width = source.width
            val height = source.height
            val result = Bitmap.createBitmap(width + 2, height + 2, Bitmap.Config.ARGB_8888)
            Canvas(result).drawBitmap(source, 1f, 1f, null)

            val patch = ninePatch(data)
            drawHLine(result, height + 1, patch.padLeft + 1, width - patch.padRight)
            drawVLine(result, width + 1, patch.padTop + 1, height - patch.padBottom)

            val xDivs = patch.xDivs
            if (xDivs.isEmpty()) {
                drawHLine(result, 0, 1, width)
            } else {
                var i = 0
                while (i < xDivs.size) {
                    drawHLine(result, 0, xDivs[i] + 1, xDivs[i + 1])
                    i += 2
                }
            }

            val yDivs = patch.yDivs
            if (yDivs.isEmpty()) {
                drawVLine(result, 0, 1, height)
            } else {
                var i = 0
                while (i < yDivs.size) {
                    drawVLine(result, 0, yDivs[i] + 1, yDivs[i + 1])
                    i += 2
                }
            }

            try {
                val inset = opticalInset(data)
                for (i in 0 until inset.layoutBoundsLeft) result.setPixel(1 + i, height + 1, OI_COLOR)
                for (i in 0 until inset.layoutBoundsRight) result.setPixel(width - i, height + 1, OI_COLOR)
                for (i in 0 until inset.layoutBoundsTop) result.setPixel(width + 1, 1 + i, OI_COLOR)
                for (i in 0 until inset.layoutBoundsBottom) result.setPixel(width + 1, height - i, OI_COLOR)
            } catch (_: CantFind9PatchChunkException) {
                // No optical bounds in this image.
            }

            val encoded = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.PNG, 100, encoded)
            output.write(encoded.toByteArray())
            output.flush()
        } catch (error: Exception) {
            throw AndrolibException(error)
        }
    }

    private fun ninePatch(data: ByteArray): NinePatchData {
        val input = ExtDataInput(ByteArrayInputStream(data))
        findChunk(input, NP_CHUNK_TYPE)
        return NinePatchData.decode(input)
    }

    private fun opticalInset(data: ByteArray): OpticalInset {
        val input = ExtDataInput(ByteArrayInputStream(data))
        findChunk(input, OI_CHUNK_TYPE)
        return OpticalInset.decode(input)
    }

    private fun findChunk(input: ExtDataInput, magic: Int) {
        input.skipBytes(8)
        while (true) {
            val size = try {
                input.readInt()
            } catch (error: IOException) {
                throw CantFind9PatchChunkException("Cant find nine patch chunk", error)
            }
            if (input.readInt() == magic) return
            input.skipBytes(size + 4)
        }
    }

    private fun drawHLine(bitmap: Bitmap, y: Int, x1: Int, x2: Int) {
        for (x in x1..x2) bitmap.setPixel(x, y, NP_COLOR)
    }

    private fun drawVLine(bitmap: Bitmap, x: Int, y1: Int, y2: Int) {
        for (y in y1..y2) bitmap.setPixel(x, y, NP_COLOR)
    }

    private companion object {
        const val NP_CHUNK_TYPE = 0x6e705463 // npTc
        const val OI_CHUNK_TYPE = 0x6e704c62 // npLb
        const val NP_COLOR = 0xff000000.toInt()
        const val OI_COLOR = 0xffff0000.toInt()
    }
}
