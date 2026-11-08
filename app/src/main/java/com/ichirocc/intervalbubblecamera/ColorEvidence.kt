package com.ichirocc.intervalbubblecamera

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** 縮めた色つきの画像（センサーの向き。画素は 0xAARRGGBB）。 */
class ColorThumb(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixels must be width * height" }
    }
}

/**
 * 遠くの小さな人が動いたかを、縮めた色つきの画像の差で確かめる。80×60 の明るさの格子では、遠くの小さな人は
 * 1 つのマスの中で背景と平均されて薄まり、服と芝生のように明るさが近いと変化として出てこない。色つきで、格子より
 * 細かい画像なら、その人の所の変化が残る。
 *
 * 手ぶれや照明の変化では色の差が画面じゅうに出るので、人の枠の中に [MIN_CONCENTRATION] 倍以上こく集まって
 * いるときだけ「本当に動いた」とする（実機の写真 33 枚と作った屋外の画像 192 場面で選んだ値）。
 */
object ColorEvidence {
    /** 縮めた画像の幅（高さは縦横の比で決める）。 */
    const val WIDTH = 320

    /** 赤・緑・青のどれかがこれより変われば、その画素は変わったとする。 */
    const val CHANNEL_DIFF = 30

    /** 人の枠の中で、変わった画素がこの割合以上。 */
    const val MIN_INSIDE = 0.05

    /** 枠の中の変わった画素の割合が、枠の外のこの倍以上。 */
    const val MIN_CONCENTRATION = 3.0

    /**
     * 前回 [previous] を、格子（[gridWidth]×[gridHeight]）でのずれ [shift] を縮めた画像の画素に直した分だけずらして
     * 重ね、今回 [current] と比べて変わった画素。前回に写っていなかった縁は変わっていないとする。
     * 大きさが違えば null。
     */
    fun changedMask(previous: ColorThumb, current: ColorThumb, shift: FrameShift, gridWidth: Int, gridHeight: Int): BooleanArray? {
        if (previous.width != current.width || previous.height != current.height) return null
        val width = current.width
        val height = current.height
        val sx = (shift.dx.toDouble() * width / gridWidth).roundToInt()
        val sy = (shift.dy.toDouble() * height / gridHeight).roundToInt()
        return BooleanArray(width * height) { i ->
            val x = i % width
            val y = i / width
            val px = x - sx
            val py = y - sy
            px in 0 until width && py in 0 until height &&
                differs(previous.pixels[py * width + px], current.pixels[i])
        }
    }

    /** 枠 [box] の中に、変わった画素が [MIN_INSIDE] 以上、かつ枠の外の [MIN_CONCENTRATION] 倍以上こく集まっているか。 */
    fun movedInside(mask: BooleanArray, width: Int, height: Int, box: NormalizedBox): Boolean {
        val x0 = (box.left * width).toInt().coerceIn(0, width)
        val x1 = ceil(box.right * width).toInt().coerceIn(0, width)
        val y0 = (box.top * height).toInt().coerceIn(0, height)
        val y1 = ceil(box.bottom * height).toInt().coerceIn(0, height)
        val insideCells = (x1 - x0) * (y1 - y0)
        if (insideCells <= 0) return false
        var inside = 0
        for (y in y0 until y1) for (x in x0 until x1) if (mask[y * width + x]) inside++
        val total = mask.count { it }
        val outsideCells = width * height - insideCells
        val insideRatio = inside.toDouble() / insideCells
        val outsideRatio = if (outsideCells > 0) (total - inside).toDouble() / outsideCells else 0.0
        return insideRatio >= MIN_INSIDE && insideRatio >= MIN_CONCENTRATION * outsideRatio
    }

    private fun differs(a: Int, b: Int): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) > CHANNEL_DIFF ||
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) > CHANNEL_DIFF ||
            abs((a and 0xFF) - (b and 0xFF)) > CHANNEL_DIFF
}

/**
 * 画面を重なり合う 2×2 の区画（それぞれ画面の 6 割四方）に分け、撮った写真から切り出して人を探す。画面全体を
 * 縮めて探すと、遠くの小さな人（画面の高さの 1 割以下）は半分ほどしか見つからないが、区画ごとなら 2 倍ほど
 * 大きく写るので見つかる（作った屋外の画像で、遠くの人を見つけた数が 41/144 から 95/144 に増えた）。
 * 検出が 4 回増えるので、「外・遠く」の撮り方で、本体が熱くないときだけ使う。
 */
object TiledDetection {
    const val TILE_SIZE = 0.6

    val regions: List<NormalizedBox> = listOf(0.0 to 0.0, 1 - TILE_SIZE to 0.0, 0.0 to 1 - TILE_SIZE, 1 - TILE_SIZE to 1 - TILE_SIZE)
        .map { (left, top) -> NormalizedBox(left, top, left + TILE_SIZE, top + TILE_SIZE) }

    /** 撮り方 [preset] と本体の温度 [heat] で、区画に分けて探すか。 */
    fun enabled(preset: CapturePreset, heat: HeatLevel): Boolean = preset == CapturePreset.DISTANT && heat == HeatLevel.NORMAL
}
