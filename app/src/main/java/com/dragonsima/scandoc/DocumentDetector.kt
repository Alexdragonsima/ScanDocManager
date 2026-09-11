package com.dragonsima.scandoc

import org.opencv.core.*
import org.opencv.core.TermCriteria
import org.opencv.imgproc.Imgproc
import org.opencv.imgproc.CLAHE
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
    private const val MAX_STUCK_ATTEMPTS = 3
    private const val MIN_QUAD_AREA_RATIO = 0.02
    private const val MAX_QUAD_AREA_RATIO = 0.90
    private const val MIN_QUALITY_THRESHOLD = 0.15
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
        val previousQuality: Double = 0.0,
        val stuckCounter: Int = 0
    )

    private val stateRef = AtomicReference(DetectorState())
    private val stateLock = ReentrantReadWriteLock()

    // ====== MatPool ======
    private class MatPool(private val capacity: Int = 4) {
        private val pool = ConcurrentLinkedQueue<Mat>()
        private val createdCount = AtomicInteger(0)
        private val reusedCount = AtomicInteger(0)
        private val lock = ReentrantReadWriteLock()

        fun acquire(): Mat {
            lock.write {
                while (pool.isNotEmpty()) {
                    val mat = pool.poll()
                    if (mat.nativeObj != 0L) {
                        reusedCount.incrementAndGet()
                        return mat
                    } else {
                        try { mat.release() } catch (_: Exception) {}
                    }
                }
                createdCount.incrementAndGet()
                return Mat()
            }
        }

        fun release(mat: Mat) {
            lock.write {
                if (mat.nativeObj != 0L) {
                    mat.setTo(Scalar(0.0))
                    if (pool.size < capacity) pool.offer(mat)
                    else mat.release()
                }
            }
        }

        fun getStats(): String = "Created: ${createdCount.get()}, Reused: ${reusedCount.get()}, Pool size: ${pool.size}"
        fun releaseAll() {
            lock.write {
                pool.forEach { it.release() }
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

    private val contourPool = ForkJoinPool(
        min(4, Runtime.getRuntime().availableProcessors())
    )

    private val imageLocks = ConcurrentHashMap<Int, Any>()
    private fun imageLock(image: Mat): Any {
        val key = System.identityHashCode(image.nativeObj)
        return imageLocks.computeIfAbsent(key) { Any() }
    }

    // ====== КЛАСС КАНДИДАТА ======
    private data class CandidateQuad(
        val corners: Array<Point>,
        val quality: Double,
        val area: Double,
        val method: String
    )

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
                val candidate = findCornersInternal(image)
                if (candidate != null && candidate.corners.size == 4 && isValidQuad(candidate.corners)) {
                    successCountAtomic.incrementAndGet()
                    val smoothed = smoothCornersThreadSafe(candidate.corners, candidate.quality)
                    return smoothed
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
    private fun findCornersInternal(image: Mat): CandidateQuad? {
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

            val futures = listOf(
                contourPool.submit {
                    findCandidatesByOtsu(gray, minArea, maxArea)?.let { candidates.addAll(it) }
                },
                contourPool.submit {
                    findCandidatesByCanny(blurred, minArea, maxArea)?.let { candidates.addAll(it) }
                },
                contourPool.submit {
                    findCandidatesByAdaptive(blurred, minArea, maxArea)?.let { candidates.addAll(it) }
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

            val best = selectBestCandidate(candidates.toList())
            if (best != null) {
                // --- Уточняем углы на уменьшенном изображении (gray) ---
                val refinedCorners = refineCornersSubpix(gray, best.corners)
                // теперь масштабируем уже уточнённые углы
                val scaleX = image.cols().toDouble() / small.cols().toDouble()
                val scaleY = image.rows().toDouble() / small.rows().toDouble()
                val scaled = refinedCorners.map { Point(it.x * scaleX, it.y * scaleY) }.toTypedArray()
                return best.copy(corners = scaled)
            }
            null
        } finally {
            smallMatPool.release(small)
            grayMatPool.release(gray)
            normalizedMatPool.release(normalized)
            blurredMatPool.release(blurred)
        }
    }
    private fun refineCornersSubpix(image: Mat, corners: Array<Point>): Array<Point> {
        if (corners.size != 4) return corners
        // Проверяем, что все углы не слишком близко к краю (отступ > winSize.width)
        val margin = 6.0 // чуть больше половины окна
        for (p in corners) {
            if (p.x < margin || p.x > image.cols() - margin ||
                p.y < margin || p.y > image.rows() - margin) {
                Log.w(TAG, "Corner too close to edge, skipping subpix")
                return corners
            }
        }
        val cornersMat = MatOfPoint2f(*corners)
        // Размер окна – половина от 11, можно подобрать
        val winSize = Size(5.0, 5.0)
        val zeroZone = Size(-1.0, -1.0) // означает «использовать всё окно»
        val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, 30, 0.001)
        return try {
            Imgproc.cornerSubPix(image, cornersMat, winSize, zeroZone, criteria)
            cornersMat.toArray().map { Point(it.x, it.y) }.toTypedArray()
        } catch (e: Exception) {
            Log.e(TAG, "cornerSubPix error: ${e.message}", e)
            corners // возвращаем исходные при ошибке
        } finally {
            cornersMat.release()
        }
    }

    // ====== ПОИСК КАНДИДАТОВ (без изменений, но с проверкой isNearEdge) ======
    private fun findCandidatesByOtsu(gray: Mat, minArea: Double, maxArea: Double): List<CandidateQuad>? {
        val candidates = mutableListOf<CandidateQuad>()
        val binary = Mat()
        val binaryInv = Mat()
        return try {
            val contours = mutableListOf<MatOfPoint>()
            try {
                Imgproc.threshold(gray, binary, 0.0, 255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                Imgproc.findContours(binary, contours, Mat(),
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                candidates.addAll(processContours(contours, minArea, maxArea, "Otsu-Binary"))
            } finally { contours.forEach { it.release() }; contours.clear() }

            val contoursInv = mutableListOf<MatOfPoint>()
            try {
                Imgproc.threshold(gray, binaryInv, 0.0, 255.0,
                    Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
                Imgproc.findContours(binaryInv, contoursInv, Mat(),
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                candidates.addAll(processContours(contoursInv, minArea, maxArea, "Otsu-Inv"))
            } finally { contoursInv.forEach { it.release() }; contoursInv.clear() }

            if (candidates.isEmpty()) null else candidates
        } finally {
            binary.release()
            binaryInv.release()
        }
    }

    private fun findCandidatesByCanny(blurred: Mat, minArea: Double, maxArea: Double): List<CandidateQuad>? {
        val candidates = mutableListOf<CandidateQuad>()
        return try {
            val thresholds = listOf(Pair(30.0, 90.0), Pair(50.0, 150.0), Pair(70.0, 200.0))
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
                    } finally { contours.forEach { it.release() }; contours.clear() }
                } finally { edges.release(); kernel.release() }
            }
            if (candidates.isEmpty()) null else candidates
        } catch (e: Exception) { Log.e(TAG, "Canny error", e); null }
    }

    private fun findCandidatesByAdaptive(blurred: Mat, minArea: Double, maxArea: Double): List<CandidateQuad>? {
        val candidates = mutableListOf<CandidateQuad>()
        return try {
            val blockSizes = listOf(11, 15, 21, 31)
            for (blockSize in blockSizes) {
                val binary = Mat()
                try {
                    Imgproc.adaptiveThreshold(blurred, binary, 255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY, blockSize, 10.0)
                    val contours = mutableListOf<MatOfPoint>()
                    try {
                        Imgproc.findContours(binary, contours, Mat(),
                            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                        candidates.addAll(processContours(contours, minArea, maxArea, "Adaptive-$blockSize"))
                    } finally { contours.forEach { it.release() }; contours.clear() }
                } finally { binary.release() }
            }
            if (candidates.isEmpty()) null else candidates
        } catch (e: Exception) { Log.e(TAG, "Adaptive error", e); null }
    }

    // ====== ОБРАБОТКА КОНТУРОВ ======
    private fun processContours(contours: List<MatOfPoint>, minArea: Double, maxArea: Double, method: String): List<CandidateQuad> {
        val candidates = ConcurrentLinkedQueue<CandidateQuad>()
        val futures = contours.mapNotNull { contour ->
            if (contour.empty() || contour.total() < 3) return@mapNotNull null
            val contourCopy = try { MatOfPoint(*contour.toArray()) } catch (_: Exception) { null } ?: return@mapNotNull null
            contourPool.submit<CandidateQuad?> {
                try {
                    processSingleContour(contourCopy, minArea, maxArea, method)
                } catch (e: Exception) { Log.e(TAG, "Contour error", e); null }
                finally { contourCopy.release() }
            }
        }
        futures.forEach { future ->
            try {
                future.get(CONTOUR_PROCESSING_TIMEOUT, TimeUnit.SECONDS)?.let {
                    if (candidates.size < MAX_CANDIDATES) candidates.add(it)
                }
            } catch (e: TimeoutException) { future.cancel(true); Log.w(TAG, "Timeout") }
            catch (e: Exception) { Log.e(TAG, "Future error", e) }
        }
        return candidates.toList()
    }

    private fun processSingleContour(contour: MatOfPoint, minArea: Double, maxArea: Double, method: String): CandidateQuad? {
        if (contour.empty() || contour.rows() < 3) return null
        val area = try { Geometry.contourArea(contour) } catch (_: Exception) { return null }
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
                                val pts = approx.toArray()
                                val corners = orderCorners(arrayOf(
                                    Point(pts[0].x, pts[0].y),
                                    Point(pts[1].x, pts[1].y),
                                    Point(pts[2].x, pts[2].y),
                                    Point(pts[3].x, pts[3].y)
                                ))
                                if (isValidQuad(corners) && !isFullScreen(corners) && !isNearEdge(corners)) {
                                    val quality = calculateQuality(corners, area)
                                    if (quality > MIN_QUALITY_THRESHOLD) {
                                        return CandidateQuad(corners, quality, area, method)
                                    }
                                }
                            }
                        } finally { approxMatOfPoint?.release() }
                    }
                } finally { approx?.release() }
            }
            null
        } finally { contour2f?.release() }
    }
    // ====== ВСПОМОГАТЕЛЬНЫЕ ГЕОМЕТРИЧЕСКИЕ ФУНКЦИИ ======
    private fun isNearEdge(points: Array<Point>): Boolean {
        val margin = PROCESSING_WIDTH * 0.05
        for (p in points) {
            if (p.x < margin || p.x > PROCESSING_WIDTH - margin ||
                p.y < margin || p.y > PROCESSING_HEIGHT - margin) return true
        }
        return false
    }

    private fun isFullScreen(points: Array<Point>): Boolean {
        if (points.size != 4) return false
        val margin = 15.0
        var edgePoints = 0
        for (p in points) {
            if (p.x < margin || p.x > PROCESSING_WIDTH - margin ||
                p.y < margin || p.y > PROCESSING_HEIGHT - margin) edgePoints++
        }
        if (edgePoints >= 3) return true
        val area = calculateArea(points)
        return area / PROCESSING_AREA > 0.93
    }

    private fun selectBestCandidate(candidates: List<CandidateQuad>): CandidateQuad? = candidates.maxByOrNull { it.quality }

    private fun calculateQuality(corners: Array<Point>, area: Double): Double {
        if (!isConvex(corners) || hasSelfIntersection(corners)) return 0.0
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
        quality += calculateParallelism(corners) * 0.3
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
        val len1 = sqrt(v1[0]*v1[0] + v1[1]*v1[1])
        val len2 = sqrt(v2[0]*v2[0] + v2[1]*v2[1])
        val len3 = sqrt(v3[0]*v3[0] + v3[1]*v3[1])
        val len4 = sqrt(v4[0]*v4[0] + v4[1]*v4[1])
        if (len1==0.0 || len2==0.0 || len3==0.0 || len4==0.0) return 0.0
        val n1 = doubleArrayOf(v1[0]/len1, v1[1]/len1)
        val n2 = doubleArrayOf(v2[0]/len2, v2[1]/len2)
        val n3 = doubleArrayOf(v3[0]/len3, v3[1]/len3)
        val n4 = doubleArrayOf(v4[0]/len4, v4[1]/len4)
        val dot1 = n1[0]*n3[0] + n1[1]*n3[1]
        val dot2 = n2[0]*n4[0] + n2[1]*n4[1]
        return ((dot1 + 1.0) / 2.0 + (dot2 + 1.0) / 2.0) / 2.0
    }

    private fun isConvex(points: Array<Point>): Boolean {
        if (points.size != 4) return false
        var sign = 0
        for (i in 0 until 4) {
            val dx1 = points[(i+2)%4].x - points[(i+1)%4].x
            val dy1 = points[(i+2)%4].y - points[(i+1)%4].y
            val dx2 = points[i].x - points[(i+1)%4].x
            val dy2 = points[i].y - points[(i+1)%4].y
            val cross = dx1*dy2 - dy1*dx2
            if (cross != 0.0) {
                val cs = if (cross > 0) 1 else -1
                if (sign == 0) sign = cs
                else if (sign != cs) return false
            }
        }
        return true
    }

    private fun hasSelfIntersection(points: Array<Point>): Boolean {
        fun direction(p1: Point, p2: Point, p3: Point) = (p3.x - p1.x)*(p2.y - p1.y) - (p2.x - p1.x)*(p3.y - p1.y)
        fun segmentsIntersect(p1: Point, p2: Point, p3: Point, p4: Point): Boolean {
            val d1 = direction(p3, p4, p1); val d2 = direction(p3, p4, p2)
            val d3 = direction(p1, p2, p3); val d4 = direction(p1, p2, p4)
            return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
        }
        return segmentsIntersect(points[0], points[1], points[2], points[3]) ||
                segmentsIntersect(points[1], points[2], points[3], points[0])
    }

    private fun calculateAngles(corners: Array<Point>): DoubleArray {
        val angles = DoubleArray(4)
        for (i in 0 until 4) {
            val prev = corners[(i+3)%4]; val curr = corners[i]; val next = corners[(i+1)%4]
            val v1 = doubleArrayOf(prev.x - curr.x, prev.y - curr.y)
            val v2 = doubleArrayOf(next.x - curr.x, next.y - curr.y)
            val dot = v1[0]*v2[0] + v1[1]*v2[1]
            val mag1 = sqrt(v1[0]*v1[0] + v1[1]*v1[1])
            val mag2 = sqrt(v2[0]*v2[0] + v2[1]*v2[1])
            angles[i] = if (mag1 == 0.0 || mag2 == 0.0) 0.0 else Math.toDegrees(acos((dot / (mag1 * mag2)).coerceIn(-1.0,1.0)))
        }
        return angles
    }

    private fun isValidQuad(corners: Array<Point>): Boolean {
        if (corners.size != 4) return false
        for (i in 0 until 4) for (j in i+1 until 4) if (distance(corners[i], corners[j]) < 10.0) return false
        if (!isConvex(corners)) return false
        val angles = calculateAngles(corners)
        return angles.all { it in 20.0..160.0 }
    }

    private fun orderCorners(points: Array<Point>): Array<Point> {
        if (points.size != 4) return points
        val sortedByY = points.sortedBy { it.y }
        val top = sortedByY.take(2).sortedBy { it.x }
        val bottom = sortedByY.takeLast(2).sortedByDescending { it.x }
        return arrayOf(top[0], top[1], bottom[0], bottom[1])
    }

    private fun smoothCornersThreadSafe(newCorners: Array<Point>, newQuality: Double): Array<Point>? {
        return stateRef.updateAndGet { currentState ->
            val prev = currentState.previousCorners
            if (prev == null) {
                return@updateAndGet currentState.copy(
                    previousCorners = newCorners,
                    previousArea = calculateArea(newCorners),
                    previousQuality = newQuality,
                    stuckCounter = 0
                )
            }

            var maxDist = 0.0
            for (i in 0 until 4) maxDist = max(maxDist, distance(prev[i], newCorners[i]))
            val newArea = calculateArea(newCorners)
            val areaRatio = if (currentState.previousArea > 0 && newArea > 0)
                max(newArea / currentState.previousArea, currentState.previousArea / newArea)
            else 1.0

            if (maxDist <= SMOOTHING_DISTANCE && areaRatio <= 1.5) {
                val smoothed = Array(4) { i ->
                    Point(prev[i].x * 0.7 + newCorners[i].x * 0.3,
                        prev[i].y * 0.7 + newCorners[i].y * 0.3)
                }
                return@updateAndGet currentState.copy(
                    previousCorners = smoothed,
                    previousArea = calculateArea(smoothed),
                    previousQuality = (currentState.previousQuality + newQuality) / 2,
                    stuckCounter = 0
                )
            }

            // Если качество нового объекта превышает порог — переключаемся
            if (newQuality > MIN_QUALITY_THRESHOLD) {
                Log.d(TAG, "Переключение на новый объект с качеством $newQuality")
                return@updateAndGet currentState.copy(
                    previousCorners = newCorners,
                    previousArea = newArea,
                    previousQuality = newQuality,
                    stuckCounter = 0
                )
            } else {
                return@updateAndGet currentState.copy(
                    stuckCounter = currentState.stuckCounter + 1
                )
            }
        }.previousCorners
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

    private fun distance(p1: Point, p2: Point): Double = sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))

    private fun getFallbackCorners(image: Mat): Array<Point>? {
        if (image.empty() || image.cols() == 0 || image.rows() == 0) return null
        return arrayOf(Point(0.0,0.0), Point(image.cols().toDouble(),0.0),
            Point(image.cols().toDouble(), image.rows().toDouble()),
            Point(0.0, image.rows().toDouble()))
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
        Log.d(TAG, "warpDocument получил углы: ${corners.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")
        if (!isValidQuad(corners)) {
            Log.e(TAG, "Углы не прошли isValidQuad: ${corners.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")
            return null
        }

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
        var background: Mat? = null
        var diff: Mat? = null
        var blackhat: Mat? = null
        var kernel: Mat? = null
        var clahe: CLAHE? = null

        return try {
            Imgproc.cvtColor(image, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, channels)

            val lChannel = channels[0]

            // Адаптивный размер ядра ~1/20 от короткой стороны
            val ksize = (min(image.cols(), image.rows()) / 20).coerceIn(15, 61)
            val kernelSize = if (ksize % 2 == 0) ksize + 1 else ksize

            // Оценка фона (тени + неравномерное освещение)
            background = Mat()
            Imgproc.GaussianBlur(lChannel, background,
                Size(kernelSize.toDouble(), kernelSize.toDouble()), 0.0)

            // Вычитание фона → выравнивание освещения
            diff = Mat()
            Core.subtract(lChannel, background, diff)
            Core.add(diff, Scalar(128.0), diff)

            // Убираем заломы: black-hat находит тонкие тёмные линии
            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            blackhat = Mat()
            Imgproc.morphologyEx(diff, blackhat, Imgproc.MORPH_BLACKHAT, kernel)

            // Ослабляем заломы
            Core.subtract(diff, blackhat, diff)
            Core.add(diff, Scalar(64.0), diff)

            // Локальный контраст
            clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
            clahe.apply(diff, diff)

            // Нормализация — избегаем "серого"
            Core.normalize(diff, diff, 0.0, 255.0, Core.NORM_MINMAX)

            // Копируем результат в L-канал
            diff.copyTo(channels[0])

            val result = Mat()
            Core.merge(channels, result)
            Imgproc.cvtColor(result, result, Imgproc.COLOR_Lab2BGR)
            result
        } catch (e: Exception) {
            Log.e(TAG, "removeShadows error: ${e.message}", e)
            image.clone()
        } finally {
            background?.release()
            blackhat?.release()
            kernel?.release()
            clahe?.clear()
            diff?.release()
            if (channels.size >= 3) {
                channels[0].release()
                channels[1].release()
                channels[2].release()
            }
            lab.release()
        }
    }

    /**
     * Улучшение скана в зависимости от выбранного фильтра.
     */
    // ====== УЛУЧШЕНИЕ ИЗОБРАЖЕНИЯ ======

    fun enhanceScan(input: Mat, filter: String = "bw"): Mat {
        val result = when (filter) {
            "bw" -> enhanceBwAuto(input)
            "color" -> enhanceColor(input)
            "sharp" -> enhanceSharp(input)
            "shadow" -> removeShadows(input)
            "gray" -> enhanceGray(input)       // новый
            "photo" -> enhancePhoto(input)     // новый
            else -> input.clone()
        }

        if (result.channels() == 1) {
            val bgr = Mat()
            Imgproc.cvtColor(result, bgr, Imgproc.COLOR_GRAY2BGR)
            result.release()
            return bgr
        }
        return result
    }

    // Оттенки серого — просто grayscale + лёгкий CLAHE
    private fun enhanceGray(input: Mat): Mat {
        if (input.empty()) return input.clone()

        val gray = Mat()
        if (input.channels() == 3) {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            input.copyTo(gray)
        }

        val clahe = Imgproc.createCLAHE(1.5, Size(8.0, 8.0))
        val result = Mat()
        clahe.apply(gray, result)

        clahe.clear()
        gray.release()
        return result  // вернёт grayscale, конвертнём в BGR в enhanceScan
    }

    // Фото — авто-баланс белого + мягкое усиление контраста
    private fun enhancePhoto(input: Mat): Mat {
        if (input.empty()) return input.clone()

        // 1. Авто-баланс белого по методу "gray world"
        val mean = Core.mean(input)
        val grayValue = (mean.`val`[0] + mean.`val`[1] + mean.`val`[2]) / 3.0

        val balanced = Mat()
        val channels = mutableListOf<Mat>()
        Core.split(input, channels)

        // Ограничиваем коэффициенты, чтобы тёмный канал не вытянул шум
        val scales = doubleArrayOf(
            if (mean.`val`[0] > 1.0) (grayValue / mean.`val`[0]).coerceIn(0.5, 2.0) else 1.0,
            if (mean.`val`[1] > 1.0) (grayValue / mean.`val`[1]).coerceIn(0.5, 2.0) else 1.0,
            if (mean.`val`[2] > 1.0) (grayValue / mean.`val`[2]).coerceIn(0.5, 2.0) else 1.0
        )
        for (i in 0..2) {
            channels[i].convertTo(channels[i], -1, scales[i], 0.0)
        }
        Core.merge(channels, balanced)
        channels.forEach { it.release() }

        // 2. Мягкое усиление контраста через CLAHE на L-канале
        val lab = Mat()
        Imgproc.cvtColor(balanced, lab, Imgproc.COLOR_BGR2Lab)
        balanced.release()

        val labChannels = mutableListOf<Mat>()
        Core.split(lab, labChannels)
        val clahe = Imgproc.createCLAHE(1.8, Size(8.0, 8.0))
        clahe.apply(labChannels[0], labChannels[0])
        clahe.clear()

        val merged = Mat()
        Core.merge(labChannels, merged)
        labChannels.forEach { it.release() }
        lab.release()

        val result = Mat()
        Imgproc.cvtColor(merged, result, Imgproc.COLOR_Lab2BGR)
        merged.release()

        return result
    }

    private fun enhanceBw(input: Mat): Mat {
        if (input.empty()) return input.clone()

        val gray = Mat()
        if (input.channels() == 3) {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            input.copyTo(gray)
        }

        // ← ШАГ 1: Median blur ДО бинаризации — убираем шум бумаги
        val denoised = Mat()
        Imgproc.medianBlur(gray, denoised, 3)
        gray.release()

        // ← ШАГ 2: адаптивный blockSize в зависимости от размера изображения
        val minSide = min(denoised.cols(), denoised.rows())
        var blockSize = (minSide / 40).coerceIn(15, 41)
        if (blockSize % 2 == 0) blockSize += 1  // должен быть нечётным

        // Адаптивная бинаризация
        val binary = Mat()
        Imgproc.adaptiveThreshold(
            denoised,
            binary,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            blockSize,        // ← было 15
            5.0
        )
        denoised.release()

        // ← ШАГ 1: Median blur ПОСЛЕ бинаризации — убираем одиночные чёрные точки
        val result = Mat()
        Imgproc.medianBlur(binary, result, 3)
        binary.release()

        // Проверяем, не слишком ли белый результат
        val whiteRatio = Core.countNonZero(result).toDouble() / (result.rows() * result.cols())
        if (whiteRatio > 0.98) {
            // Почти всё белое — возвращаем grayscale с усилением контраста
            val fallbackGray = Mat()
            if (input.channels() == 3) {
                Imgproc.cvtColor(input, fallbackGray, Imgproc.COLOR_BGR2GRAY)
            } else {
                input.copyTo(fallbackGray)
            }
            val enhanced = Mat()
            Core.normalize(fallbackGray, enhanced, 0.0, 255.0, Core.NORM_MINMAX)
            fallbackGray.release()
            result.release()
            return enhanced
        }

        return result
    }

    /**
     * Sauvola binarization.
     * T = mean * (1 + k * (std / R - 1))
     * k = 0.2..0.5, R = 128
     */
    private fun enhanceBwSauvola(input: Mat, k: Double = 0.34, R: Double = 128.0): Mat {
        if (input.empty()) return input.clone()

        val gray = Mat()
        if (input.channels() == 3) {
            Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
        } else {
            input.copyTo(gray)
        }

        // Median blur до бинаризации (шаг 1)
        val denoised = Mat()
        Imgproc.medianBlur(gray, denoised, 3)
        gray.release()

        // Адаптивный размер окна
        val minSide = min(denoised.cols(), denoised.rows())
        var window = (minSide / 40).coerceIn(15, 41)
        if (window % 2 == 0) window += 1

        // Приводим к float — для точности вычислений
        val grayF = Mat()
        denoised.convertTo(grayF, CvType.CV_32F)
        denoised.release()

        // mean = boxFilter(grayF)
        val mean = Mat()
        Imgproc.boxFilter(grayF, mean, CvType.CV_32F, Size(window.toDouble(), window.toDouble()))

        // sqMean = boxFilter(grayF * grayF)
        val graySq = Mat()
        Core.multiply(grayF, grayF, graySq)
        val sqMean = Mat()
        Imgproc.boxFilter(graySq, sqMean, CvType.CV_32F, Size(window.toDouble(), window.toDouble()))
        graySq.release()

        // variance = sqMean - mean^2
        val meanSq = Mat()
        Core.multiply(mean, mean, meanSq)
        val variance = Mat()
        Core.subtract(sqMean, meanSq, variance)
        meanSq.release()
        sqMean.release()

        // std = sqrt(max(variance, 0))
        val zero = Mat(variance.size(), variance.type(), Scalar(0.0))
        val varClamped = Mat()
        Core.max(variance, zero, varClamped)
        variance.release()
        zero.release()

        val std = Mat()
        Core.sqrt(varClamped, std)
        varClamped.release()

        // threshold = mean * (1 + k * (std / R - 1))
        val stdOverR = Mat()
        Core.divide(std, Scalar(R), stdOverR)
        std.release()

        val oneMinus = Mat()  // (std/R - 1)
        Core.subtract(stdOverR, Scalar(1.0), oneMinus)
        stdOverR.release()

        val kTimes = Mat()
        Core.multiply(oneMinus, Scalar(k), kTimes)
        oneMinus.release()

        val onePlus = Mat()
        Core.add(kTimes, Scalar(1.0), onePlus)
        kTimes.release()

        val threshold = Mat()
        Core.multiply(mean, onePlus, threshold)
        mean.release()
        onePlus.release()

        // Бинаризация: grayF > threshold ? 255 : 0
        val mask = Mat()
        Core.compare(grayF, threshold, mask, Core.CMP_GT)
        grayF.release()
        threshold.release()

        // mask — 8U с 0/255
        val result = Mat()
        mask.convertTo(result, CvType.CV_8U, 255.0)
        mask.release()

        // Median blur после бинаризации (шаг 1)
        val cleaned = Mat()
        Imgproc.medianBlur(result, cleaned, 3)
        result.release()

        // Fallback: если почти всё белое — возвращаем нормализованный grayscale
        val whiteRatio = Core.countNonZero(cleaned).toDouble() / (cleaned.rows() * cleaned.cols())
        if (whiteRatio > 0.98) {
            val fallbackGray = Mat()
            if (input.channels() == 3) {
                Imgproc.cvtColor(input, fallbackGray, Imgproc.COLOR_BGR2GRAY)
            } else {
                input.copyTo(fallbackGray)
            }
            val enhanced = Mat()
            Core.normalize(fallbackGray, enhanced, 0.0, 255.0, Core.NORM_MINMAX)
            fallbackGray.release()
            cleaned.release()
            return enhanced
        }

        return cleaned
    }

    /**
     * Автовыбор между Sauvola и Adaptive Gaussian.
     * Sauvola даёт лучший результат на тонком тексте,
     * но на жирном — инвертирует (белые буквы на чёрном).
     * Здесь мы пробуем Sauvola, проверяем результат по whiteRatio
     * и откатываемся на Adaptive Gaussian, если Sauvola "сломалась".
     */
    private fun enhanceBwAuto(input: Mat): Mat {
        if (input.empty()) return input.clone()

        val grayForMean = Mat()
        if (input.channels() == 3) {
            Imgproc.cvtColor(input, grayForMean, Imgproc.COLOR_BGR2GRAY)
        } else {
            input.copyTo(grayForMean)
        }
        val inputMean = Core.mean(grayForMean).`val`[0]

        // ← Считаем долю тёмных пикселей (ниже 80)
        val darkMask = Mat()
        Imgproc.threshold(grayForMean, darkMask, 80.0, 255.0, Imgproc.THRESH_BINARY_INV)
        val darkRatio = Core.countNonZero(darkMask).toDouble() /
                (darkMask.rows() * darkMask.cols())
        darkMask.release()
        grayForMean.release()

        Log.d(TAG, "Input mean = $inputMean, darkRatio = $darkRatio")

        val workingInput: Mat
        var preInverted = false

        // ← Тёмный источник: либо очень низкое среднее, либо большинство пикселей тёмные
        if (inputMean < 140.0 || darkRatio > 0.55) {
            workingInput = Mat()
            Core.bitwise_not(input, workingInput)
            preInverted = true
            Log.d(TAG, "Dark input detected, pre-inverting")
        } else {
            workingInput = input
        }

        try {
            val sauvolaResult = try {
                enhanceBwSauvola(workingInput)
            } catch (e: Exception) {
                Log.e(TAG, "Sauvola failed: ${e.message}")
                null
            }

            if (sauvolaResult != null) {
                var whiteRatio = Core.countNonZero(sauvolaResult).toDouble() /
                        (sauvolaResult.rows() * sauvolaResult.cols())
                Log.d(TAG, "Sauvola whiteRatio = $whiteRatio")

                if (whiteRatio < 0.5) {
                    Core.bitwise_not(sauvolaResult, sauvolaResult)
                    whiteRatio = Core.countNonZero(sauvolaResult).toDouble() /
                            (sauvolaResult.rows() * sauvolaResult.cols())
                    Log.d(TAG, "After flip whiteRatio = $whiteRatio")
                }

                if (whiteRatio in 0.60..0.97) {
                    return sauvolaResult
                }

                Log.d(TAG, "Sauvola ratio out of range, falling back to Adaptive")
                sauvolaResult.release()
            }

            return enhanceBw(workingInput)
        } finally {
            if (preInverted) {
                workingInput.release()
            }
        }
    }
    private fun enhanceColor(input: Mat): Mat {
        if (input.empty()) return input.clone()

        val lab = Mat()
        Imgproc.cvtColor(input, lab, Imgproc.COLOR_BGR2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)

        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        val claheL = Mat()
        clahe.apply(channels[0], claheL)
        claheL.copyTo(channels[0])

        val merged = Mat()
        Core.merge(channels, merged)
        val result = Mat()
        Imgproc.cvtColor(merged, result, Imgproc.COLOR_Lab2BGR)

        // Освобождение
        lab.release()
        channels[0].release()
        channels[1].release()
        channels[2].release()
        claheL.release()
        merged.release()
        clahe.clear()

        return result
    }

    private fun enhanceSharp(input: Mat): Mat {
        if (input.empty()) return input.clone()

        // 1. Сначала лёгкий CLAHE для контраста текста
        val lab = Mat()
        Imgproc.cvtColor(input, lab, Imgproc.COLOR_BGR2Lab)
        val channels = mutableListOf<Mat>()
        Core.split(lab, channels)
        val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        clahe.apply(channels[0], channels[0])
        val merged = Mat()
        Core.merge(channels, merged)
        channels.forEach { it.release() }
        lab.release()
        clahe.clear()

        val contrastEnhanced = Mat()
        Imgproc.cvtColor(merged, contrastEnhanced, Imgproc.COLOR_Lab2BGR)
        merged.release()

        // 2. Unsharp mask с меньшим радиусом — не даёт "ореолов" вокруг текста
        val blurred = Mat()
        Imgproc.GaussianBlur(contrastEnhanced, blurred, Size(0.0, 0.0), 1.5)
        val result = Mat()
        Core.addWeighted(contrastEnhanced, 1.6, blurred, -0.6, 0.0, result)

        blurred.release()
        contrastEnhanced.release()
        return result
    }

    // ====== ОСВОБОЖДЕНИЕ ======
    fun release() {
        smallMatPool.releaseAll()
        grayMatPool.releaseAll()
        normalizedMatPool.releaseAll()
        blurredMatPool.releaseAll()
        stateLock.write { stateRef.set(DetectorState()) }
        imageLocks.clear()
        Log.d(TAG, "Resources released.")
    }
}