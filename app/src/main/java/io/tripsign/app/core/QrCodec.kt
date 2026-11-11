// ver 1.0.0 | 2026-11-04 19:45
package io.tripsign.app.core

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * QR matrix for one export frame (zxing core, Apache-2.0). Error correction L: losing a frame
 * only means waiting for the next loop, so QR-level redundancy would be paid twice.
 */
object QrCodec {
    /** [row][col] = true for a dark module. No quiet zone; the screen draws a 4-module margin. */
    fun matrix(text: String): Array<BooleanArray> {
        val hints = mapOf(EncodeHintType.CHARACTER_SET to "ISO-8859-1")
        val code = Encoder.encode(text, ErrorCorrectionLevel.L, hints)
        val m = code.matrix
        return Array(m.height) { y -> BooleanArray(m.width) { x -> m.get(x, y).toInt() == 1 } }
    }
}
