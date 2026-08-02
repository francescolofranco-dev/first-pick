package com.firstpick.overlay

import com.firstpick.core.AppPaths
import com.firstpick.core.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.roundToInt


@Serializable
data class PackGridCalibration(
    val colX0: Float,
    val colPitch: Float,
    val colW: Float,
    val row0Y: Float,
    val rowPitch: Float,
    val cardH: Float,
)

object PackGeometry {
    const val COLS = CardDetector.MAX_COLS


    val DEFAULT = PackGridCalibration(
        colX0 = 0.1469f,
        colPitch = 0.1018f,
        colW = 0.0899f,
        row0Y = 0.2010f,
        rowPitch = 0.2371f,
        cardH = 0.2208f,
    )

    fun rects(cal: PackGridCalibration, winW: Int, winH: Int, count: Int): List<CardDetector.CardRect> =
        (0 until count.coerceAtLeast(0)).map { i ->
            val col = i % COLS
            val row = i / COLS
            CardDetector.CardRect(
                index = i,
                x = ((cal.colX0 + col * cal.colPitch) * winW).roundToInt(),
                y = ((cal.row0Y + row * cal.rowPitch) * winH).roundToInt(),
                w = (cal.colW * winW).roundToInt(),
                h = (cal.cardH * winH).roundToInt(),
            )
        }


    fun fromGrid(grid: CardDetector.Grid): PackGridCalibration? {
        if (grid.cols.size < 2 || grid.rows.isEmpty()) return null
        val w = grid.imageW.toFloat()
        val h = grid.imageH.toFloat()
        val cardH = (grid.rows.first().last - grid.rows.first().first + 1) / h
        return PackGridCalibration(
            colX0 = grid.cols.first().first / w,
            colPitch = (grid.cols.last().first - grid.cols.first().first) / ((grid.cols.size - 1) * w),
            colW = (grid.cols.first().last - grid.cols.first().first + 1) / w,
            row0Y = grid.rows.first().first / h,
            rowPitch = if (grid.overlayRowPitch != null) grid.overlayRowPitch / h
            else if (grid.rows.size >= 2) (grid.rows[1].first - grid.rows[0].first) / h
            else DEFAULT.rowPitch * (cardH / DEFAULT.cardH),
            cardH = cardH,
        )
    }

    internal fun isPlausible(calibration: PackGridCalibration): Boolean {
        val values = listOf(
            calibration.colX0,
            calibration.colPitch,
            calibration.colW,
            calibration.row0Y,
            calibration.rowPitch,
            calibration.cardH,
        )
        if (values.any { !it.isFinite() || it <= 0f }) return false
        val lastColX = calibration.colX0 + (COLS - 1) * calibration.colPitch
        val defaultLastColX = DEFAULT.colX0 + (COLS - 1) * DEFAULT.colPitch
        return abs(calibration.colPitch - DEFAULT.colPitch) <= 0.0045f &&
            abs(lastColX - defaultLastColX) <= 0.02f &&
            abs(calibration.colW - DEFAULT.colW) <= 0.015f &&
            abs(calibration.row0Y - DEFAULT.row0Y) <= 0.025f &&
            abs(calibration.rowPitch - DEFAULT.rowPitch) <= 0.02f &&
            abs(calibration.cardH - DEFAULT.cardH) <= 0.025f
    }
}


class PackGridCalibrationStore(
    private val file: Path = AppPaths.appSupport.resolve("overlay-grid.json"),
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var cache: MutableMap<String, PackGridCalibration>? = null

    @Synchronized
    fun get(w: Int, h: Int): PackGridCalibration? =
        loadAll()["${w}x$h"]?.takeIf(PackGeometry::isPlausible)

    @Synchronized
    fun put(w: Int, h: Int, cal: PackGridCalibration) {
        val all = loadAll()
        all["${w}x$h"] = cal
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, json.encodeToString(all.toMap()))
        }.onFailure { Log.warn(TAG, "persist failed: $it") }
    }

    private fun loadAll(): MutableMap<String, PackGridCalibration> {
        cache?.let { return it }
        val loaded = runCatching {
            if (Files.exists(file)) json.decodeFromString<Map<String, PackGridCalibration>>(Files.readString(file))
            else emptyMap()
        }.getOrElse { Log.warn(TAG, "load failed: $it"); emptyMap() }
        return loaded.toMutableMap().also { cache = it }
    }

    companion object {
        private const val TAG = "PackGridCalibration"
    }
}
