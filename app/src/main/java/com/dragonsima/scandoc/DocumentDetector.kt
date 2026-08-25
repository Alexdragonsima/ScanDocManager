package com.dragonsima.scandoc

import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import android.util.Log
import org.opencv.geometry.Geometry
import kotlin.math.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write

object DocumentDetector {
    private const val TAG = "DocDetector"

    // ====== КОНСТАНТЫ ======
    private const val PROCESSING_WIDTH = 640.0
    private const val PROCESSING_HEIGHT = 480.0
    private const val PROCESSING_AREA = PROCESSING_WIDTH * PROCESSING_HEIGHT
    private const val SMOOTHING_DISTANCE = 50.0
    private const val MAX_STUCK_ATTEMPTS = 5
    private const val MIN_QUAD_AREA_RATIO = 0.02
    private const val MAX_QUAD_AREA_RATIO = 0.90
    private const val MIN_QUALITY_THRESHOLD = 0.3
    private const val MAX_CANDIDATES = 50
    private const val CONTOUR_PROCESSING_TIMEOUT = 3L

    // ====== ПОТОКОБЕЗОПАСНЫЕ МЕТРИКИ ======
    private val totalAttemptsAtomic = AtomicInteger(0)
    private val successCountAtomic = AtomicInteger(0)
    private val fallbackCountAtomic = AtomicInteger(0)

    val totalAttempts: Int get() = totalAttemptsAtomic.get()
    val successCount: Int get() = successCountAtomic.get()
    val fallbackCount: Int get() = fallbackCountAtomic.get()

    // ====== ПОТОКОБЕЗОПАСНОЕ СОСТОЯНИЕ ======
    private data class DetectorState(
        val previousCorners: Array<Point>? = null,
        val previousArea: Double = 0.0,
        val stuckCounter: Int = 0
    )

    private val stateRef = AtomicReference(DetectorState())
    private val stateLock = ReentrantReadWriteLock()

    // ====== ИСПРАВЛЕННЫЙ MatPool ======
    private class MatPool(private val capacity: Int = 4) {
        private val pool = ConcurrentLinkedQueue<Mat>()
        private val createdCount = AtomicInteger(0)
        private val reusedCount = AtomicInteger(0)
        private val lock = ReentrantReadWriteLock()

        fun acquire(): Mat {
            lock.write {
                while (pool.isNotEmpty()) {
                    val mat = pool.poll()
                    if (mat.nativeObj != 0L && !mat.empty()) {
                        reusedCount.incrementAndGet()
                        return mat
                    } else {
                        try {
                            mat.release()
                        } catch (e: Exception) {
                            // Игнорируем
                        }
                    }
                }

                createdCount.incrementAndGet()
                return Mat()
            }
        }

        fun release(mat: Mat) {
            lock.write {
                if (mat.nativeObj != 0L) {
                    // ✅ Освобождаем нативные ресурсы
                    mat.release()

                    // ✅ Сохраняем пустой Mat в пул
                    if (pool.size < capacity) {
                        pool.offer(Mat())
                    }
                }
            }
        }

        fun getStats(): String {
            return "Created: ${createdCount.get()}, Reused: ${reusedCount.get()}, Pool size: ${pool.size}"
        }

        fun releaseAll() {
            lock.write {
                pool.forEach {
                    try { it.release() } catch (e: Exception) { /* ignore */ }
                }
                pool.clear()
                createdCount.set(0)
                reusedCount.set(0)
            }
        }
    }

    private val smallMatPool = MatPool()
    private val grayMatPool = MatPool()
    private val normalizedMatPool = MatPool()
    private val blurredMatPool = MatPool()

    // ====== ОБЩИЙ ПУЛ ДЛЯ КОНТУРОВ ======
    private val contourPool = ForkJoinPool(
        min(4, Runtime.getRuntime().availableProcessors())
    )

    // ====== БЛОКИРОВКА ДЛЯ ИЗОБРАЖЕНИЙ ======
    private val imageLocks = ConcurrentHashMap<Int, Any>()

    private fun imageLock(image: Mat): Any {
        val key = System.identityHashCode(image.nativeObj)
        return imageLocks.computeIfAbsent(key) { Any() }
    }

    // ====== ГЛАВНЫЙ МЕТОД ======
    fun findDocumentCorners(image: Mat): Array<Point>? {
        totalAttemptsAtomic.incrementAndGet()

        if (image.empty() || image.cols() == 0 || image.rows() == 0) {
            Log.e(TAG, "Empty or invalid input image")
            fallbackCountAtomic.incrementAndGet()
            return getFallbackCorners(image)
        }

        return synchronized(imageLock(image)) {
            try {
                val result = findCornersInternal(image)

                if (result != null && result.size == 4 && isValidQuad(result)) {
                    successCountAtomic.incrementAndGet()
                    updateSmoothingThreadSafe(result)
                    return result
                }

                val currentState = stateRef.get()
                if (currentState.previousCorners != null &&
                    currentState.stuckCounter < MAX_STUCK_ATTEMPTS) {

                    Log.d(TAG, "Using previous corners (stuck: ${currentState.stuckCounter})")
                    stateRef.updateAndGet { it.copy(stuckCounter = it.stuckCounter + 1) }
                    return currentState.previousCorners
                }

                fallbackCountAtomic.incrementAndGet()
                stateRef.updateAndGet { it.copy(stuckCounter = 0) }
                getFallbackCorners(image)
            } catch (e: Exception) {
                Log.e(TAG, "findDocumentCorners error: ${e.message}", e)
                fallbackCountAtomic.incrementAndGet()
                getFallbackCorners(image)
            }
        }
    }

    // ====== ВНУТРЕННИЙ ПОИСК ======
    private fun findCornersInternal(image: Mat): Array<Point>? {
        val small = smallMatPool.acquire()
        val gray = grayMatPool.acquire()
        val normalized = normalizedMatPool.acquire()
        val blurred = blurredMatPool.acquire()

        return try {
            Imgproc.resize(image, small, Size(PROCESSING_WIDTH, PROCESSING_HEIGHT),
                0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)

            Core.normalize(gray, normalized, 0.0, 255.0, Core.NORM_MINMAX)

            val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
            clahe.apply(normalized, normalized)

            Imgproc.GaussianBlur(normalized, blurred, Size(5.0, 5.0), 0.0)

            val minArea = PROCESSING_AREA * MIN_QUAD_AREA_RATIO
            val maxArea = PROCESSING_AREA * MAX_QUAD_AREA_RATIO

            val candidates = ConcurrentHashMap.newKeySet<CandidateQuad>()

            // Используем общий пул
            val futures = listOf(
                contourPool.submit {
                    findCandidatesByOtsu(gray, minArea, maxArea)?.let {
                        candidates.addAll(it)
                    }
                },
                contourPool.submit {
                    findCandidatesByCanny(blurred, minArea, maxArea)?.let {
                        candidates.addAll(it)
                    }
                },
                contourPool.submit {
                    findCandidatesByAdaptive(blurred, minArea, maxArea)?.let {
                        candidates.addAll(it)
                    }
                }
            )

            futures.forEach { future ->
                try {
                    future.get(CONTOUR_PROCESSING_TIMEOUT, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    Log.w(TAG, "Method timeout, cancelling")
                } catch (e: ExecutionException) {
                    Log.e(TAG, "Execution error: ${e.cause?.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Method error: ${e.message}")
                }
            }

            val bestCandidate = selectBestCandidate(candidates.toList())

            if (bestCandidate != null) {
                val scaleX = image.cols().toDouble() / small.cols().toDouble()
                val scaleY = image.rows().toDouble() / small.rows().toDouble()

                val scaledCorners = Array(4) { i ->
                    Point(
                        bestCandidate.corners[i].x * scaleX,
                        bestCandidate.corners[i].y * scaleY
                    )
                }

                smoothCornersThreadSafe(scaledCorners)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "findCornersInternal error: ${e.message}", e)
            null
        } finally {
            smallMatPool.release(small)
            grayMatPool.release(gray)
            normalizedMatPool.release(normalized)
            blurredMatPool.release(blurred)
        }
    }

    // ====== КЛАСС КАНДИДАТА ======
    private data class CandidateQuad(
        val corners: Array<Point>,
        val quality: Double,
        val area: Double,
        val method: String
    )

    // ====== ИСПРАВЛЕННЫЙ findCandidatesByOtsu ======
    private fun findCandidatesByOtsu(
        gray: Mat,
        minArea: Double,
        maxArea: Double
    ): List<CandidateQuad>? {
        val candidates = mutableListOf<CandidateQuad>()
        val binary = Mat()
        val binaryInv = Mat()

        return try {
            // Otsu Binary
            val contours = mutableListOf<MatOfPoint>()
            try {
                Imgproc.threshold(gray, binary, 0.0, 255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                Imgproc.findContours(binary, contours, Mat(),
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                candidates.addAll(processContours(contours, minArea, maxArea, "Otsu-Binary"))
            } finally {
                contours.forEach { it.release() }
                contours.clear()
            }

            // Otsu Binary Inv
            val contoursInv = mutableListOf<MatOfPoint>()
            try {
                Imgproc.threshold(gray, binaryInv, 0.0, 255.0,
                    Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
                Imgproc.findContours(binaryInv, contoursInv, Mat(),
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                candidates.addAll(processContours(contoursInv, minArea, maxArea, "Otsu-Inv"))
            } finally {
                contoursInv.forEach { it.release() }
                contoursInv.clear()
            }

            if (candidates.isEmpty()) null else candidates
        } catch (e: Exception) {
            Log.e(TAG, "Otsu method error: ${e.message}")
            null
        } finally {
            binary.release()
            binaryInv.release()
        }
    }

    // ====== ИСПРАВЛЕННЫЙ findCandidatesByCanny ======
    private fun findCandidatesByCanny(
        blurred: Mat,
        minArea: Double,
        maxArea: Double
    ): List<CandidateQuad>? {
        val candidates = mutableListOf<CandidateQuad>()

        return try {
            val thresholds = listOf(
                Pair(30.0, 90.0),
                Pair(50.0, 150.0),
                Pair(70.0, 200.0)
            )

            for ((low, high) in thresholds) {
                val edges = Mat()
                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))

                try {
                    Imgproc.Canny(blurred, edges, low, high)
                    Imgproc.dilate(edges, edges, kernel)

                    val contours = mutableListOf<MatOfPoint>()
                    try {
                        Imgproc.findContours(edges, contours, Mat(),
                            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        candidates.addAll(processContours(contours, minArea, maxArea, "Canny-$low-$high"))
                    } finally {
                        contours.forEach { it.release() }
                        contours.clear()
                    }
                } finally {
                    edges.release()
                    kernel.release()
                }
            }

            if (candidates.isEmpty()) null else candidates
        } catch (e: Exception) {
            Log.e(TAG, "Canny method error: ${e.message}")
            null
        }
    }

    // ====== ИСПРАВЛЕННЫЙ findCandidatesByAdaptive ======
    private fun findCandidatesByAdaptive(
        blurred: Mat,
        minArea: Double,
        maxArea: Double
    ): List<CandidateQuad>? {
        val candidates = mutableListOf<CandidateQuad>()

        return try {
            val blockSizes = listOf(11, 15, 21, 31)

            for (blockSize in blockSizes) {
                val binary = Mat()

                try {
                    Imgproc.adaptiveThreshold(
                        blurred, binary, 255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY, blockSize, 10.0
                    )

                    val contours = mutableListOf<MatOfPoint>()
                    try {
                        Imgproc.findContours(binary, contours, Mat(),
                            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        candidates.addAll(processContours(contours, minArea, maxArea, "Adaptive-$blockSize"))
                    } finally {
                        contours.forEach { it.release() }
                        contours.clear()
                    }
                } finally {
                    binary.release()
                }
            }

            if (candidates.isEmpty()) null else candidates
        } catch (e: Exception) {
            Log.e(TAG, "Adaptive method error: ${e.message}")
            null
        }
    }

    // ====== ИСПРАВЛЕННЫЙ processContours ======
    private fun processContours(
        contours: List<MatOfPoint>,
        minArea: Double,
        maxArea: Double,
        method: String
    ): List<CandidateQuad> {
        val candidates = ConcurrentLinkedQueue<CandidateQuad>()

        val futures = contours.mapNotNull { contour ->
            // ✅ Дополнительная проверка перед клонированием
            if (contour.empty() || contour.total() < 3) {
                null
            } else {
                val contourCopy = try {
                    MatOfPoint(*contour.toArray())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy contour for method $method", e)
                    null
                }

                if (contourCopy == null) {
                    null
                } else {
                    contourPool.submit<CandidateQuad?> {
                        try {
                            processSingleContour(contourCopy, minArea, maxArea, method)
                        } catch (e: Exception) {
                            Log.e(TAG, "Contour processing error: ${e.message}")
                            null
                        } finally {
                            contourCopy.release()
                        }
                    }
                }
            }
        }

        futures.forEach { future ->
            try {
                val result = future.get(CONTOUR_PROCESSING_TIMEOUT, TimeUnit.SECONDS)
                if (result != null && candidates.size < MAX_CANDIDATES) {
                    candidates.add(result)
                }
            } catch (e: TimeoutException) {
                future.cancel(true)
                Log.w(TAG, "Contour processing timeout")
            } catch (e: ExecutionException) {
                Log.e(TAG, "Execution error: ${e.cause?.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Future error: ${e.message}")
            }
        }

        return candidates.toList()
    }

    private fun processSingleContour(
        contour: MatOfPoint,
        minArea: Double,
        maxArea: Double,
        method: String
    ): CandidateQuad? {
        // ✅ Добавлена проверка на валидность контура
        if (contour.empty() || contour.rows() < 3) {
            return null
        }

        val area = try {
            Geometry.contourArea(contour)
        } catch (e: Exception) {
            Log.e(TAG, "contourArea failed for $method: ${e.message}")
            return null
        }

        if (area < minArea || area > maxArea) return null

        var contour2f: MatOfPoint2f? = null

        return try {
            contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Geometry.arcLength(contour2f, true)

            val epsilons = listOf(0.02, 0.03, 0.05)

            for (eps in epsilons) {
                var approx: MatOfPoint2f? = null

                try {
                    approx = MatOfPoint2f()
                    Geometry.approxPolyDP(contour2f, approx, eps * peri, true)

                    if (approx.total() == 4L) {
                        var approxMatOfPoint: MatOfPoint? = null

                        try {
                            approxMatOfPoint = MatOfPoint(*approx.toArray())

                            if (Geometry.isContourConvex(approxMatOfPoint)) {
                                val points = approx.toArray()
                                val corners = orderCorners(arrayOf(
                                    Point(points[0].x, points[0].y),
                                    Point(points[1].x, points[1].y),
                                    Point(points[2].x, points[2].y),
                                    Point(points[3].x, points[3].y)
                                ))

                                if (isValidQuad(corners)) {
                                    val quality = calculateQuality(corners, area)
                                    if (quality > MIN_QUALITY_THRESHOLD) {
                                        return CandidateQuad(corners, quality, area, method)
                                    }
                                }
                            }
                        } finally {
                            approxMatOfPoint?.release()
                        }
                    }
                } finally {
                    approx?.release()
                }
            }

            null
        } finally {
            contour2f?.release()
        }
    }

    // ====== ВЫБОР ЛУЧШЕГО КАНДИДАТА ======
    private fun selectBestCandidate(candidates: List<CandidateQuad>): CandidateQuad? {
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { it.quality }
    }

    // ====== ГЕОМЕТРИЧЕСКИЕ МЕТРИКИ ======
    private fun calculateQuality(corners: Array<Point>, area: Double): Double {
        if (!isConvex(corners)) return 0.0
        if (hasSelfIntersection(corners)) return 0.0

        var quality = 0.0

        val angles = calculateAngles(corners)
        val angleScore = angles.map { angle ->
            when {
                angle in 70.0..110.0 -> 1.0
                angle in 50.0..130.0 -> 0.7
                angle in 30.0..150.0 -> 0.4
                else -> 0.1
            }
        }.average()
        quality += angleScore * 0.5

        val parallelismScore = calculateParallelism(corners)
        quality += parallelismScore * 0.3

        val sizeScore = when {
            area > PROCESSING_AREA * 0.20 -> 1.0
            area > PROCESSING_AREA * 0.10 -> 0.8
            area > PROCESSING_AREA * 0.05 -> 0.5
            else -> 0.2
        }
        quality += sizeScore * 0.2

        return quality
    }

    private fun calculateParallelism(corners: Array<Point>): Double {
        val v1 = doubleArrayOf(corners[1].x - corners[0].x, corners[1].y - corners[0].y)
        val v2 = doubleArrayOf(corners[2].x - corners[1].x, corners[2].y - corners[1].y)
        val v3 = doubleArrayOf(corners[3].x - corners[2].x, corners[3].y - corners[2].y)
        val v4 = doubleArrayOf(corners[0].x - corners[3].x, corners[0].y - corners[3].y)

        val len1 = sqrt(v1[0] * v1[0] + v1[1] * v1[1])
        val len2 = sqrt(v2[0] * v2[0] + v2[1] * v2[1])
        val len3 = sqrt(v3[0] * v3[0] + v3[1] * v3[1])
        val len4 = sqrt(v4[0] * v4[0] + v4[1] * v4[1])

        if (len1 == 0.0 || len2 == 0.0 || len3 == 0.0 || len4 == 0.0) return 0.0

        val n1 = doubleArrayOf(v1[0] / len1, v1[1] / len1)
        val n2 = doubleArrayOf(v2[0] / len2, v2[1] / len2)
        val n3 = doubleArrayOf(v3[0] / len3, v3[1] / len3)
        val n4 = doubleArrayOf(v4[0] / len4, v4[1] / len4)

        val dot1 = n1[0] * n3[0] + n1[1] * n3[1]
        val dot2 = n2[0] * n4[0] + n2[1] * n4[1]

        val parallel1 = (dot1 + 1.0) / 2.0
        val parallel2 = (dot2 + 1.0) / 2.0

        return (parallel1 + parallel2) / 2.0
    }

    private fun isConvex(points: Array<Point>): Boolean {
        if (points.size != 4) return false

        var sign = 0
        for (i in 0 until 4) {
            val dx1 = points[(i + 2) % 4].x - points[(i + 1) % 4].x
            val dy1 = points[(i + 2) % 4].y - points[(i + 1) % 4].y
            val dx2 = points[i].x - points[(i + 1) % 4].x
            val dy2 = points[i].y - points[(i + 1) % 4].y
            val cross = dx1 * dy2 - dy1 * dx2

            if (cross != 0.0) {
                val currentSign = if (cross > 0) 1 else -1
                if (sign == 0) sign = currentSign
                else if (sign != currentSign) return false
            }
        }
        return true
    }

    private fun hasSelfIntersection(points: Array<Point>): Boolean {
        fun segmentsIntersect(p1: Point, p2: Point, p3: Point, p4: Point): Boolean {
            val d1 = direction(p3, p4, p1)
            val d2 = direction(p3, p4, p2)
            val d3 = direction(p1, p2, p3)
            val d4 = direction(p1, p2, p4)

            return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) &&
                    ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        }

        return segmentsIntersect(points[0], points[1], points[2], points[3]) ||
                segmentsIntersect(points[1], points[2], points[3], points[0])
    }

    private fun direction(p1: Point, p2: Point, p3: Point): Double {
        return (p3.x - p1.x) * (p2.y - p1.y) - (p2.x - p1.x) * (p3.y - p1.y)
    }

    private fun calculateAngles(corners: Array<Point>): DoubleArray {
        val angles = DoubleArray(4)

        for (i in 0 until 4) {
            val prev = corners[(i + 3) % 4]
            val curr = corners[i]
            val next = corners[(i + 1) % 4]

            val v1 = doubleArrayOf(prev.x - curr.x, prev.y - curr.y)
            val v2 = doubleArrayOf(next.x - curr.x, next.y - curr.y)

            val dot = v1[0] * v2[0] + v1[1] * v2[1]
            val mag1 = sqrt(v1[0] * v1[0] + v1[1] * v1[1])
            val mag2 = sqrt(v2[0] * v2[0] + v2[1] * v2[1])

            if (mag1 == 0.0 || mag2 == 0.0) {
                angles[i] = 0.0
            } else {
                val cosAngle = dot / (mag1 * mag2)
                angles[i] = Math.toDegrees(acos(cosAngle.coerceIn(-1.0, 1.0)))
            }
        }

        return angles
    }

    private fun isValidQuad(corners: Array<Point>): Boolean {
        if (corners.size != 4) return false

        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                if (distance(corners[i], corners[j]) < 10.0) return false
            }
        }

        if (!isConvex(corners)) return false

        val angles = calculateAngles(corners)
        return angles.all { it in 20.0..160.0 }
    }

    // ====== ИСПРАВЛЕННЫЙ orderCorners (стабильная сортировка) ======
    private fun orderCorners(points: Array<Point>): Array<Point> {
        if (points.size != 4) return points

        // Сортировка по Y (верхние первыми)
        val sortedByY = points.sortedBy { it.y }

        // Верхние две точки сортируем по X (левый -> правый)
        val topPoints = sortedByY.take(2).sortedBy { it.x }

        // Нижние две точки сортируем по X (правый -> левый)
        val bottomPoints = sortedByY.takeLast(2).sortedByDescending { it.x }

        return arrayOf(
            topPoints[0],     // Верхний-левый
            topPoints[1],     // Верхний-правый
            bottomPoints[0],  // Нижний-правый
            bottomPoints[1]   // Нижний-левый
        )
    }

    // ====== ПОТОКОБЕЗОПАСНОЕ СГЛАЖИВАНИЕ ======
    private fun smoothCornersThreadSafe(corners: Array<Point>): Array<Point> {
        return stateLock.write {
            val currentState = stateRef.get()
            val prev = currentState.previousCorners

            if (prev == null) {
                stateRef.set(currentState.copy(
                    previousCorners = corners,
                    previousArea = calculateArea(corners)
                ))
                return@write corners
            }

            var maxDistance = 0.0
            for (i in 0 until 4) {
                maxDistance = max(maxDistance, distance(prev[i], corners[i]))
            }

            val currentArea = calculateArea(corners)
            val areaRatio = if (currentState.previousArea > 0 && currentArea > 0) {
                max(currentArea / currentState.previousArea,
                    currentState.previousArea / currentArea)
            } else {
                1.0
            }

            if (maxDistance > SMOOTHING_DISTANCE || areaRatio > 1.5) {
                stateRef.set(currentState.copy(
                    previousCorners = corners,
                    previousArea = currentArea,
                    stuckCounter = 0
                ))
                return@write corners
            }

            val smoothed = Array(4) { i ->
                Point(
                    prev[i].x * 0.7 + corners[i].x * 0.3,
                    prev[i].y * 0.7 + corners[i].y * 0.3
                )
            }

            stateRef.set(currentState.copy(
                previousCorners = smoothed,
                previousArea = calculateArea(smoothed),
                stuckCounter = 0
            ))

            smoothed
        }
    }

    private fun updateSmoothingThreadSafe(corners: Array<Point>) {
        stateLock.write {
            stateRef.updateAndGet { state ->
                state.copy(
                    previousCorners = corners,
                    previousArea = calculateArea(corners),
                    stuckCounter = 0
                )
            }
        }
    }

    private fun calculateArea(corners: Array<Point>): Double {
        if (corners.size != 4) return 0.0

        var area = 0.0
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            area += corners[i].x * corners[j].y
            area -= corners[j].x * corners[i].y
        }
        return abs(area) / 2.0
    }

    private fun distance(p1: Point, p2: Point): Double {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun getFallbackCorners(image: Mat): Array<Point>? {
        if (image.empty() || image.cols() == 0 || image.rows() == 0) return null

        return arrayOf(
            Point(0.0, 0.0),
            Point(image.cols().toDouble(), 0.0),
            Point(image.cols().toDouble(), image.rows().toDouble()),
            Point(0.0, image.rows().toDouble())
        )
    }

    // ====== ПУБЛИЧНЫЕ УТИЛИТЫ ======

    fun isSharpEnough(image: Mat, threshold: Double = 30.0): Boolean {
        if (image.empty()) return false

        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val stddev = MatOfDouble()

        return try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, stddev)

            val variance = stddev.get(0, 0)[0] * stddev.get(0, 0)[0]
            variance > threshold
        } catch (e: Exception) {
            Log.e(TAG, "isSharpEnough error: ${e.message}")
            false
        } finally {
            gray.release()
            laplacian.release()
            mean.release()
            stddev.release()
        }
    }

    // ====== ИСПРАВЛЕННЫЙ warpDocument ======
    fun warpDocument(image: Mat, corners: Array<Point>): Mat? {
        if (image.empty() || corners.size != 4) return null

        for (corner in corners) {
            if (corner.x < 0 || corner.x > image.cols() ||
                corner.y < 0 || corner.y > image.rows()) {
                Log.w(TAG, "Corners outside image boundaries")
                return null
            }
        }

        if (!isValidQuad(corners)) return null

        return try {
            val ordered = orderCorners(corners)

            // ✅ Используем среднее для сохранения пропорций
            val width = ((distance(ordered[0], ordered[1]) +
                    distance(ordered[2], ordered[3])) / 2.0).roundToInt()

            val height = ((distance(ordered[0], ordered[3]) +
                    distance(ordered[1], ordered[2])) / 2.0).roundToInt()

            if (width < 100 || height < 100 || width > 5000 || height > 5000) {
                return null
            }

            // ✅ Правильный порядок для перспективы
            val srcPts = MatOfPoint2f(
                ordered[0], ordered[1], ordered[2], ordered[3]  // Внимание!
            )
            val dstPts = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(width.toDouble(), 0.0),
                Point(width.toDouble(), height.toDouble()), // Правый-нижний
                Point(0.0, height.toDouble())   // Левый-нижний
            )

            val matrix = Geometry.getPerspectiveTransform(srcPts, dstPts)
            val warped = Mat()
            Imgproc.warpPerspective(image, warped, matrix, Size(width.toDouble(), height.toDouble()))

            srcPts.release()
            dstPts.release()
            matrix.release()

            warped
        } catch (e: Exception) {
            Log.e(TAG, "warpDocument error: ${e.message}")
            null
        }
    }

    // ====== ИСПРАВЛЕННЫЙ autoCropMargins ======
    fun autoCropMargins(image: Mat): Mat? {
        if (image.empty()) return null

        val gray = Mat()
        val binary = Mat()
        val points = MatOfPoint()
        var kernel: Mat? = null

        return try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)

            val mean = Core.mean(gray).`val`[0]

            if (mean > 200) {
                Imgproc.threshold(gray, binary, 0.0, 255.0,
                    Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            } else {
                Imgproc.adaptiveThreshold(gray, binary, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY, 15, 2.0)
            }

            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)

            Core.findNonZero(binary, points)

            if (points.empty()) {
                return image.clone()
            }

            val rect = Geometry.boundingRect(points)
            val padding = 5

            val x = (rect.x - padding).coerceAtLeast(0)
            val y = (rect.y - padding).coerceAtLeast(0)
            val width = (rect.width + padding * 2).coerceAtMost(image.cols() - x)
            val height = (rect.height + padding * 2).coerceAtMost(image.rows() - y)

            if (width <= 0 || height <= 0) return image.clone()

            Mat(image, Rect(x, y, width, height)).clone()
        } catch (e: Exception) {
            Log.e(TAG, "autoCropMargins error: ${e.message}")
            image.clone()
        } finally {
            gray.release()
            binary.release()
            points.release()
            kernel?.release()
        }
    }

    // ====== ИСПРАВЛЕННЫЙ removeShadows ======
    fun removeShadows(image: Mat): Mat {
        if (image.empty()) return image.clone()

        val lab = Mat()
        val channels = mutableListOf<Mat>()

        return try {
            Imgproc.cvtColor(image, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, channels)

            val lChannel = channels[0]

            val maxDimension = max(image.cols(), image.rows())
            val kernelSize = when {
                maxDimension > 3000 -> 101
                maxDimension > 2000 -> 71
                maxDimension > 1000 -> 51
                maxDimension > 500 -> 31
                else -> 15
            }

            val background = Mat()
            Imgproc.GaussianBlur(lChannel, background,
                Size(kernelSize.toDouble(), kernelSize.toDouble()), 0.0)

            val diff = Mat()
            Core.subtract(lChannel, background, diff)
            Core.normalize(diff, diff, 0.0, 255.0, Core.NORM_MINMAX)

            // Заменяем L канал
            lChannel.release()
            channels[0] = diff

            val result = Mat()
            Core.merge(channels, result)
            Imgproc.cvtColor(result, result, Imgproc.COLOR_Lab2BGR)

            background.release()
            channels[1].release()
            channels[2].release()

            result
        } catch (e: Exception) {
            Log.e(TAG, "removeShadows error: ${e.message}")
            image.clone()
        } finally {
            lab.release()
        }
    }

    /**
     * Улучшение скана в зависимости от выбранного фильтра.
     */
    fun enhanceScan(image: Mat, filter: String = "bw"): Mat {
        if (image.empty()) return image.clone()

        return when (filter) {
            "bw" -> {
                // Чёрно-белый режим: бинаризация Отсу
                val gray = Mat()
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
                val binary = Mat()
                Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                gray.release()
                binary
            }
            "color" -> {
                // Лёгкое улучшение контраста (CLAHE)
                val lab = Mat()
                Imgproc.cvtColor(image, lab, Imgproc.COLOR_BGR2Lab)
                val channels = mutableListOf<Mat>()
                Core.split(lab, channels)
                val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                clahe.apply(channels[0], channels[0])
                val merged = Mat()
                Core.merge(channels, merged)
                val result = Mat()
                Imgproc.cvtColor(merged, result, Imgproc.COLOR_Lab2BGR)
                lab.release()
                channels.forEach { it.release() }
                merged.release()
                result
            }
            "sharp" -> {
                // Повышение резкости
                val blurred = Mat()
                Imgproc.GaussianBlur(image, blurred, Size(0.0, 0.0), 3.0)
                val result = Mat()
                Core.addWeighted(image, 1.5, blurred, -0.5, 0.0, result)
                blurred.release()
                result
            }
            "shadow" -> removeShadows(image)  // уже есть
            else -> {
                // По умолчанию – ч/б
                val gray = Mat()
                Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
                val binary = Mat()
                Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                gray.release()
                binary
            }
        }
    }

    // ====== ОСВОБОЖДЕНИЕ РЕСУРСОВ ======
    fun release() {
        smallMatPool.releaseAll()
        grayMatPool.releaseAll()
        normalizedMatPool.releaseAll()
        blurredMatPool.releaseAll()

        stateLock.write {
            stateRef.set(DetectorState())
        }

        imageLocks.clear()

        Log.d(TAG, "Resources released. Pool stats: ${smallMatPool.getStats()}")
    }
}