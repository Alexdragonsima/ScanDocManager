package com.dragonsima.scandoc

import org.opencv.core.*
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.geometry.Geometry
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import org.opencv.core.MatOfPoint2f
import org.opencv.imgcodecs.Imgcodecs
import android.util.Log
import kotlin.math.*

object DocumentDetector {

    var totalAttempts = 0
    var successCount = 0
    var fallbackCount = 0
    private var maxSharpness = 0.0
    private var previousCorners: Array<Point>? = null
    private var frameCounter = 0
    private var lastValidCorners: Array<Point>? = null
    private var stuckCounter = 0
    private const val TAG = "DocDetector"
    private const val PROCESSING_WIDTH = 640.0
    private const val PROCESSING_HEIGHT = 480.0
    private const val PROCESSING_AREA = PROCESSING_WIDTH * PROCESSING_HEIGHT
    private var logCounter = 0
    // ====== ГЛАВНЫЙ МЕТОД ======
    fun findDocumentCorners(image: Mat): Array<Point> {
        totalAttempts++
        logCounter++
        val shouldLog = logCounter %5 == 0
        if (shouldLog) {
            Log.d(TAG, "=== Попытка #$totalAttempts ===")
        }

        Log.d(TAG, "=== Попытка #$totalAttempts ===")
        Log.d(TAG, "Входное изображение: ${image.cols()}x${image.rows()}, channels=${image.channels()}")

        val result = findCornersInternal(image)
        if (result != null && result.size == 4) {
            successCount++
            if (shouldLog) {
                Log.d(TAG, "✅ УСПЕХ! Углы: ${result.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")
            }
            return result
        }

        fallbackCount++
        Log.d(TAG, "❌ НЕ НАЙДЕНО! Использую фолбэк")
        Log.d(TAG, "Статистика: успех=$successCount, фолбэк=$fallbackCount, всего=$totalAttempts")
        return getFallbackCorners(image)
    }

    // ====== УЛУЧШЕННЫЙ ПОИСК УГЛОВ ======
    private fun findCornersInternal(image: Mat): Array<Point>? {
        val small = Mat()
        val gray = Mat()
        val blurred = Mat()

        return try {
            Imgproc.resize(image, small, Size(640.0, 480.0), 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

            val imageArea=PROCESSING_AREA
            val minArea = imageArea * 0.02
            val maxArea = imageArea * 0.90

            Log.d(TAG, "Размер для обработки: ${small.cols()}x${small.rows()}, area=$imageArea")
            Log.d(TAG, "minArea=$minArea, maxArea=$maxArea")

            // ====== МЕТОД 1: БИНАРИЗАЦИЯ OTSU ======
            Log.d(TAG, "--- Метод 1: Otsu Binary ---")
            var bestCorners = findByOtsuBinary(blurred,gray, minArea, maxArea)
            if (bestCorners != null) {
                Log.d(TAG, "Otsu Binary: НАЙДЕНО")
            } else {
                Log.d(TAG, "Otsu Binary: не найдено")
            }

            // ====== МЕТОД 2: CANNY ======
            if (bestCorners == null) {
                Log.d(TAG, "--- Метод 2: Canny ---")
                bestCorners = findByCanny(blurred, minArea, maxArea)
                if (bestCorners != null) {
                    Log.d(TAG, "Canny: НАЙДЕНО")
                } else {
                    Log.d(TAG, "Canny: не найдено")
                }
            }

            // ====== МЕТОД 3: АДАПТИВНЫЙ THRESHOLD ======
            if (bestCorners == null) {
                Log.d(TAG, "--- Метод 3: Adaptive Threshold ---")
                bestCorners = findByAdaptiveThreshold(blurred, minArea, maxArea)
                if (bestCorners != null) {
                    Log.d(TAG, "Adaptive: НАЙДЕНО")
                } else {
                    Log.d(TAG, "Adaptive: не найдено")
                }
            }

            // ====== МЕТОД 4: ПРОСТОЙ THRESHOLD ======
            if (bestCorners == null) {
                Log.d(TAG, "--- Метод 4: Simple Threshold ---")
                bestCorners = findBySimpleThreshold(blurred, minArea, maxArea)
                if (bestCorners != null) {
                    Log.d(TAG, "Simple Threshold: НАЙДЕНО")
                } else {
                    Log.d(TAG, "Simple Threshold: не найдено")
                }
            }

            // ====== МЕТОД 5: ЛЮБОЙ КОНТУР ======
            if (bestCorners == null) {
                Log.d(TAG, "--- Метод 5: Any Contour ---")
                bestCorners = findByAnyContour(blurred, minArea, maxArea)
                if (bestCorners != null) {
                    Log.d(TAG, "Any Contour: НАЙДЕНО")
                } else {
                    Log.d(TAG, "Any Contour: не найдено")
                }
            }

            // ====== МАСШТАБИРУЕМ ОБРАТНО ======
            if (bestCorners != null && bestCorners.size == 4) {
                val scaleX = image.cols().toDouble() / small.cols().toDouble()
                val scaleY = image.rows().toDouble() / small.rows().toDouble()

                Log.d(TAG, "Масштабирование: scaleX=$scaleX, scaleY=$scaleY")

                val scaledCorners = Array(4) { i ->
                    Point(
                        bestCorners[i].x * scaleX,
                        bestCorners[i].y * scaleY
                    )
                }

                Log.d(TAG, "До сглаживания: ${scaledCorners.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")
                bestCorners = smoothCorners(scaledCorners)
                Log.d(TAG, "После сглаживания: ${bestCorners.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")
            }

            bestCorners

        } catch (e: Exception) {
            Log.e(TAG, "findCornersInternal error: ${e.message}", e)
            null
        } finally {
            small.release()
            gray.release()
            blurred.release()
        }
    }

    // ====== МЕТОД 1: БИНАРИЗАЦИЯ OTSU ======
    private fun findByOtsuBinary(
        blurred: Mat,      // ← Добавлен параметр
        gray: Mat,
        minArea: Double,
        maxArea: Double
    ): Array<Point>? {
        return try {
            val binary = Mat()
            val otsuThreshold = Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            Log.d(TAG, "Otsu threshold: $otsuThreshold")

            val inverted = Mat()
            Core.bitwise_not(binary, inverted)

            val imageArea=640.0*480.0

            // Проверяем обычную
            val contours1 = mutableListOf<MatOfPoint>()
            Imgproc.findContours(binary, contours1, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            Log.d(TAG, "Обычная: контуров = ${contours1.size}")
            val corners1 = findBestQuadFromContours(contours1, minArea, maxArea, "Otsu-обычная", )
            // Проверяем инвертированную
            val contours2 = mutableListOf<MatOfPoint>()
            Imgproc.findContours(inverted, contours2, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            Log.d(TAG, "Инвертированная: контуров = ${contours2.size}")
            val corners2 = findBestQuadFromContours(contours2, minArea, maxArea, "Otsu-инверт", )


            binary.release()
            inverted.release()

            // Если оба null - возвращаем null
            if (corners1 == null && corners2 == null) return null

            // Если один null - возвращаем второй
            if (corners1 == null) return corners2
            if (corners2 == null) return corners1

            // Если оба найдены - выбираем НЕ full screen
            val isFull1 = isFullScreen(corners1)
            val isFull2 = isFullScreen(corners2)

            Log.d(TAG, "Оба варианта: full1=$isFull1, full2=$isFull2")

            if (isFull1 && !isFull2) {
                Log.d(TAG, "Выбираю не-full-screen вариант 2")
                return corners2
            }
            if (isFull2 && !isFull1) {
                Log.d(TAG, "Выбираю не-full-screen вариант 1")
                return corners1
            }

            // Если оба не full screen - выбираем с большей площадью
            val area1 = contourArea(corners1)
            val area2 = contourArea(corners2)
            Log.d(TAG, "Площади: area1=$area1, area2=$area2")

            if (area1 > area2) corners1 else corners2

        } catch (e: Exception) {
            Log.e(TAG, "Otsu Binary error: ${e.message}", e)
            null
        }
    }

    /// ====== МЕТОД 2: CANNY ======
    private fun findByCanny(
        blurred: Mat,
        minArea: Double,
        maxArea: Double
    ): Array<Point>? {
        return try {
            // Пробуем несколько порогов
            val thresholds = listOf(
                30.0 to 100.0,
                50.0 to 150.0,
                20.0 to 80.0
            )

            for ((low, high) in thresholds) {
                Log.d(TAG, "Canny пороги: $low-$high")
                val edges = Mat()
                Imgproc.Canny(blurred, edges, low, high)

                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                Log.d(TAG, "Canny ($low-$high): контуров = ${contours.size}")

                val corners = findBestQuadFromContours(contours, minArea, maxArea, "Canny-$low-$high")

                if (corners != null) return corners
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Canny error: ${e.message}", e)
            null
        }
    }

    // ====== МЕТОД 3: АДАПТИВНЫЙ THRESHOLD ======
    private fun findByAdaptiveThreshold(
        blurred: Mat,
        minArea: Double,
        maxArea: Double
    ): Array<Point>? {
        return try {
            // Пробуем разные параметры
            val blockSizes = listOf(11, 15, 21)
            val constants = listOf(2.0, 5.0, 10.0)

            for (blockSize in blockSizes) {
                for (c in constants) {
                    Log.d(TAG, "Adaptive: blockSize=$blockSize, C=$c")
                    val thresh = Mat()
                    Imgproc.adaptiveThreshold(blurred, thresh, 255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, blockSize, c)

                    val contours = mutableListOf<MatOfPoint>()
                    Imgproc.findContours(thresh, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    Log.d(TAG, "Adaptive: контуров = ${contours.size}")

                    val corners = findBestQuadFromContours(contours, minArea, maxArea, "Adaptive-$blockSize-$c", )

                    if (corners != null) return corners
                }
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Adaptive error: ${e.message}", e)
            null
        }
    }

    // ====== МЕТОД 4: ПРОСТОЙ THRESHOLD ======
    private fun findBySimpleThreshold(
        blurred: Mat,
        minArea: Double,
        maxArea: Double
    ): Array<Point>? {
        return try {
            val thresholds = listOf(80.0, 100.0, 127.0, 150.0, 180.0)

            for (thresh in thresholds) {
                Log.d(TAG, "Simple Threshold: $thresh")
                val binary = Mat()
                Imgproc.threshold(blurred, binary, thresh, 255.0, Imgproc.THRESH_BINARY)

                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(binary, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                Log.d(TAG, "Threshold $thresh: контуров = ${contours.size}")

                val corners = findBestQuadFromContours(contours, minArea, maxArea, "Threshold-$thresh")

                if (corners != null) return corners
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Simple Threshold error: ${e.message}", e)
            null
        }
    }

    // ====== МЕТОД 5: ЛЮБОЙ КОНТУР ======
    private fun findByAnyContour(
        blurred: Mat,
        minArea: Double,
        maxArea: Double
    ): Array<Point>? {
        return try {
            Log.d(TAG, "Ищу ЛЮБОЙ контур...")
            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(blurred, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            Log.d(TAG, "Всего контуров: ${contours.size}")

            var maxAreaFound = 0.0
            var bestContour: MatOfPoint? = null

            for (contour in contours) {
                val area = Geometry.contourArea(contour)
                Log.d(TAG, "Контур: area=$area, точек=${contour.rows()}")

                if (area < minArea || area > maxArea) {
                    Log.d(TAG, "  → отброшен (вне диапазона)")
                    continue
                }

                if (area > maxAreaFound) {
                    maxAreaFound = area
                    bestContour = contour
                }
            }

            if (bestContour == null) {
                Log.d(TAG, "Подходящий контур не найден")
                return null
            }

            Log.d(TAG, "Лучший контур: area=$maxArea, точек=${bestContour.rows()}")

            // Пробуем аппроксимацию
            val peri = Geometry.arcLength(MatOfPoint2f(*bestContour.toArray()), true)
            Log.d(TAG, "Периметр: $peri")

            val epsilons = listOf(0.01, 0.02, 0.03, 0.05, 0.1, 0.15)

            for (eps in epsilons) {
                val approx = MatOfPoint2f()
                Geometry.approxPolyDP(MatOfPoint2f(*bestContour.toArray()), approx, eps * peri, true)
                val points = approx.toArray()
                Log.d(TAG, "epsilon=$eps → точек после аппроксимации: ${points.size}")

                if (points.size == 4) {
                    Log.d(TAG, "Найден четырёхугольник с epsilon=$eps")
                    return orderCorners(points)
                }
            }

            // Fallback: boundingRect
            Log.d(TAG, "Использую boundingRect")
            val rect = Geometry.boundingRect(bestContour)
            Log.d(TAG, "BoundingRect: x=${rect.x}, y=${rect.y}, w=${rect.width}, h=${rect.height}")

            orderCorners(arrayOf(
                Point(rect.x.toDouble(), rect.y.toDouble()),
                Point((rect.x + rect.width).toDouble(), rect.y.toDouble()),
                Point((rect.x + rect.width).toDouble(), (rect.y + rect.height).toDouble()),
                Point(rect.x.toDouble(), (rect.y + rect.height).toDouble())
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Any Contour error: ${e.message}", e)
            null
        }
    }

    // ====== ВСПОМОГАТЕЛЬНЫЙ: ПОИСК ЧЕТЫРЁХУГОЛЬНИКА ======
    // ====== ВСПОМОГАТЕЛЬНЫЙ: ПОИСК ЧЕТЫРЁХУГОЛЬНИКА ======
    private fun findBestQuadFromContours(
        contours: List<MatOfPoint>,
        minArea: Double,
        maxArea: Double,
        source: String
    ): Array<Point>? {
        var bestArea = 0.0
        var bestCorners: Array<Point>? = null

        val absoluteMaxArea = PROCESSING_AREA * 0.95

        for ((index, contour) in contours.withIndex()) {
            val area = Geometry.contourArea(contour)

            if (area > minArea) {
                Log.d(TAG, "$source: контур #$index: area=$area, точек=${contour.rows()}")
            }

            if (area > absoluteMaxArea) {
                Log.d(TAG, "  → отброшен (слишком большой)")
                continue
            }

            if (area < minArea || area > maxArea) {
                continue
            }

            val peri = Geometry.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val epsilons = listOf(0.01, 0.02, 0.03, 0.05, 0.1)

            for (eps in epsilons) {
                val approx = MatOfPoint2f()
                Geometry.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, eps * peri, true)
                val points = approx.toArray()

                if (points.size == 4 && area > bestArea) {
                    val ordered = orderCorners(points)

                    // Добавьте проверку выпуклости
                    if (isConvexQuad(ordered) && isValidQuad(ordered) && !isFullScreen(ordered)) {
                        Log.d(TAG, "  ✅ Валидный: area=$area")
                        bestArea = area
                        bestCorners = ordered
                        break
                    } else {
                        if (!isConvexQuad(ordered)) {
                            Log.d(TAG, "  ❌ Не выпуклый")
                        }
                        if (!isValidQuad(ordered)) {
                            Log.d(TAG, "  ❌ Невалидный")
                        }
                        if (isFullScreen(ordered)) {
                            Log.d(TAG, "  ❌ Весь экран")
                        }
                    }
                }
            }
        }

        return bestCorners
    }

    private fun isClockwise(points: Array<Point>): Boolean {
        if (points.size != 4) return false

        var sum = 0.0
        for (i in 0..3) {
            val p1 = points[i]
            val p2 = points[(i + 1) % 4]
            sum += (p2.x - p1.x) * (p2.y + p1.y)
        }

        // Положительная сумма = по часовой стрелке
        return sum > 0
    }

    // ====== ПРОВЕРКА: НЕ ВЕСЬ ЛИ ЭТО ЭКРАН ======
    private fun isFullScreen(points: Array<Point>): Boolean {
        if (points.size != 4) return false

        val margin = 20.0

        var edgePoints = 0
        for (p in points) {
            val nearLeft = p.x < margin
            val nearRight = p.x > PROCESSING_WIDTH - margin
            val nearTop = p.y < margin
            val nearBottom = p.y > PROCESSING_HEIGHT - margin

            if (nearLeft || nearRight || nearTop || nearBottom) {
                edgePoints++
            }
        }

        // Если 3+ точек на краях - это весь экран
        if (edgePoints >= 3) {
            Log.d(TAG, "  ❌ Весь экран: $edgePoints точек на краях")
            return true
        }

        // Проверяем площадь (более 85% = весь экран)
        val area = contourArea(points)
        val ratio = area / PROCESSING_AREA

        if (ratio > 0.85) {
            Log.d(TAG, "  ❌ Весь экран: площадь ${(ratio * 100).toInt()}%")
            return true
        }

        // Проверяем, что документ не прижат к краям
        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }

        val width = maxX - minX
        val height = maxY - minY

        if (width < PROCESSING_WIDTH * 0.3 || height < PROCESSING_HEIGHT * 0.3) {
            Log.d(TAG, "  ❌ Слишком маленький: ${width}x${height}")
            return true
        }

        return false
    }

    // ====== ВСПОМОГАТЕЛЬНЫЙ: ПЛОЩАДЬ ЧЕТЫРЁХУГОЛЬНИКА ======
    private fun contourArea(points: Array<Point>): Double {
        if (points.size != 4) return 0.0
        val contour = MatOfPoint2f(*points)
        val area = Geometry.contourArea(contour)
        contour.release()
        return area
    }


    // ====== ПРОСТОЕ СГЛАЖИВАНИЕ ======
    private fun smoothCorners(corners: Array<Point>): Array<Point> {
        if (previousCorners == null || previousCorners!!.size != 4) {
            previousCorners = corners
            return corners
        }

        val maxDiff = (0..3).maxOfOrNull { i ->
            val dx = abs(corners[i].x - previousCorners!![i].x)
            val dy = abs(corners[i].y - previousCorners!![i].y)
            max(dx, dy)
        } ?: 0.0

        // Плавное сглаживание ВСЕГДА (без сброса!)
        val smoothFactor = when {
            maxDiff < 5 -> 0.8    // Почти не двигается - сильное сглаживание
            maxDiff < 15 -> 0.6   // Небольшое движение
            maxDiff < 40 -> 0.4   // Среднее движение
            maxDiff < 100 -> 0.2  // Быстрое движение
            else -> 0.1           // Очень быстрое - почти без сглаживания
        }

        val smoothed = Array(4) { i ->
            Point(
                previousCorners!![i].x * smoothFactor + corners[i].x * (1 - smoothFactor),
                previousCorners!![i].y * smoothFactor + corners[i].y * (1 - smoothFactor)
            )
        }

        previousCorners = smoothed
        return smoothed
    }

    private fun isValidQuad(points: Array<Point>): Boolean {
        if (points.size != 4) return false

        // Проверяем, что точки не совпадают
        for (i in 0..2) {
            for (j in i+1..3) {
                val dist = distance(points[i], points[j])
                if (dist < 15) {
                    Log.d(TAG, "  ❌ Точки $i и $j слишком близко: $dist")
                    return false
                }
            }
        }

        // Проверяем стороны
        val w1 = distance(points[0], points[1])  // Top-Left → Top-Right
        val w2 = distance(points[3], points[2])  // Bottom-Left → Bottom-Right
        val h1 = distance(points[0], points[3])  // Top-Left → Bottom-Left
        val h2 = distance(points[1], points[2])  // Top-Right → Bottom-Right

        if (w1 < 20 || w2 < 20 || h1 < 20 || h2 < 20) {
            Log.d(TAG, "  ❌ Стороны слишком короткие")
            return false
        }

        // Проверяем, что противоположные стороны примерно равны
        val wRatio = max(w1, w2) / min(w1, w2)
        val hRatio = max(h1, h2) / min(h1, h2)

        if (wRatio > 2.5 || hRatio > 2.5) {
            Log.d(TAG, "  ❌ Противоположные стороны не равны: wRatio=$wRatio, hRatio=$hRatio")
            return false
        }

        // Проверяем углы (должны быть 60-120 градусов)
        val angle1 = getAngle(points[0], points[1], points[3])  // TL
        val angle2 = getAngle(points[1], points[2], points[0])  // TR
        val angle3 = getAngle(points[2], points[3], points[1])  // BR
        val angle4 = getAngle(points[3], points[0], points[2])  // BL

        for (angle in listOf(angle1, angle2, angle3, angle4)) {
            if (angle < 50 || angle > 130) {
                Log.d(TAG, "  ❌ Угол вне диапазона: $angle")
                return false
            }
        }

        // Соотношение сторон
        val avgW = (w1 + w2) / 2
        val avgH = (h1 + h2) / 2
        val aspect = max(avgW, avgH) / min(avgW, avgH)

        if (aspect > 6) {
            Log.d(TAG, "  ❌ Слишком вытянутый: aspect=$aspect")
            return false
        }

        return true
    }

    private fun getAngle(p1: Point, p2: Point, p3: Point): Double {
        val v1x = p2.x - p1.x
        val v1y = p2.y - p1.y
        val v2x = p3.x - p1.x
        val v2y = p3.y - p1.y

        val dot = v1x * v2x + v1y * v2y
        val mag1 = sqrt(v1x * v1x + v1y * v1y)
        val mag2 = sqrt(v2x * v2x + v2y * v2y)

        if (mag1 == 0.0 || mag2 == 0.0) return 0.0

        val cosAngle = (dot / (mag1 * mag2)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosAngle))
    }

    // ====== СОРТИРОВКА УГЛОВ ======
    private fun orderCorners(points: Array<Point>): Array<Point> {
        if (points.size != 4) return points

        Log.d(TAG, "Сортировка вход: ${points.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")

        // Находим центр масс
        val centerX = points.map { it.x }.average()
        val centerY = points.map { it.y }.average()

        // Сортируем по углу относительно центра (по часовой стрелке)
        val sorted = points.sortedBy { point ->
            atan2(point.y - centerY, point.x - centerX)
        }

        // После сортировки по углу:
        // sorted[0] - самый "верхний" (наименьший угол)
        // sorted[1] - правый
        // sorted[2] - нижний
        // sorted[3] - левый

        // Но нам нужен порядок: TL, TR, BR, BL
        // Находим TL как точку с минимальной суммой x+y
        val tl = sorted.minByOrNull { it.x + it.y } ?: sorted[0]
        val br = sorted.maxByOrNull { it.x + it.y } ?: sorted[2]

        // Оставшиеся две точки
        val remaining = sorted.filter { it != tl && it != br }

        if (remaining.size != 2) {
            // Fallback
            val sortedByY = points.sortedBy { it.y }
            val top = sortedByY.take(2).sortedBy { it.x }
            val bottom = sortedByY.takeLast(2).sortedBy { it.x }
            return arrayOf(top[0], top[1], bottom[1], bottom[0])
        }

        // TR = больший x, BL = меньший x
        val tr = remaining.maxByOrNull { it.x } ?: remaining[0]
        val bl = remaining.minByOrNull { it.x } ?: remaining[1]

        return arrayOf(tl, tr, br, bl)

        val result = arrayOf(tl, tr, br, bl)
        Log.d(TAG, "Сортировка выход: ${result.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")

        return result
    }
    private fun isConvexQuad(points: Array<Point>): Boolean {
        if (points.size != 4) return false

        var sign = 0
        for (i in 0..3) {
            val p1 = points[i]
            val p2 = points[(i + 1) % 4]
            val p3 = points[(i + 2) % 4]

            val cross = (p2.x - p1.x) * (p3.y - p2.y) - (p2.y - p1.y) * (p3.x - p2.x)

            if (cross != 0.0) {
                val currentSign = if (cross > 0) 1 else -1
                if (sign == 0) {
                    sign = currentSign
                } else if (sign != currentSign) {
                    return false  // Не выпуклый
                }
            }
        }

        return true
    }
    // ====== ПРОВЕРКА РЕЗКОСТИ ======
    fun isSharpEnough(image: Mat): Boolean {
        if (image.empty()) return true

        val gray = Mat()
        val laplacian = Mat()
        val squared = Mat()

        return try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Laplacian(gray, laplacian, CvType.CV_32F)
            Core.multiply(laplacian, laplacian, squared)
            val mean = Core.mean(squared).`val`[0]

            val sharpness = mean

            if (sharpness > maxSharpness) {
                maxSharpness = maxSharpness * 0.9 + sharpness * 0.1
            }
            if (maxSharpness == 0.0) maxSharpness = sharpness

            val threshold = maxSharpness * 0.5
            val isSharp = sharpness >= threshold

            val sharpnessNorm = (sharpness * 1000).toInt()
            val maxNorm = (maxSharpness * 1000).toInt()
            val thresholdNorm = (threshold * 1000).toInt()
            Log.d("DocDetector", "Резкость: $sharpnessNorm (макс: $maxNorm, порог: $thresholdNorm) → ${if (isSharp) "✅" else "❌"}")

            isSharp
        } catch (e: Exception) {
            Log.e("DocDetector", "Sharpness error: ${e.message}")
            true
        } finally {
            gray.release()
            laplacian.release()
            squared.release()
        }
    }

    // ====== ЗАПАСНОЙ ======
    private fun getFallbackCorners(image: Mat): Array<Point> {
        val m = image.cols() * 0.1f
        val mY = image.rows() * 0.1f
        val fallback = arrayOf(
            Point(m.toDouble(), mY.toDouble()),
            Point((image.cols() - m).toDouble(), mY.toDouble()),
            Point((image.cols() - m).toDouble(), (image.rows() - mY).toDouble()),
            Point(m.toDouble(), (image.rows() - mY).toDouble())
        )
        Log.d(TAG, "Фолбэк углы: ${fallback.joinToString { "(${it.x.toInt()}, ${it.y.toInt()})" }}")
        return fallback
    }

    // ====== WARP ======
    fun warpDocument(src: Mat, corners: Array<Point>): Mat {
        val ordered = orderCorners(corners)

        val w1 = distance(ordered[0], ordered[1])
        val w2 = distance(ordered[3], ordered[2])
        val h1 = distance(ordered[0], ordered[3])
        val h2 = distance(ordered[1], ordered[2])

        var outW = max(w1, w2).toInt()
        var outH = max(h1, h2).toInt()
        outW = outW.coerceIn(100, 4000)
        outH = outH.coerceIn(100, 5000)

        val srcPts = MatOfPoint2f(
            Point(ordered[0].x, ordered[0].y),
            Point(ordered[1].x, ordered[1].y),
            Point(ordered[2].x, ordered[2].y),
            Point(ordered[3].x, ordered[3].y)
        )
        val dstPts = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0),
            Point(0.0, outH - 1.0)
        )

        val matrix = Geometry.getPerspectiveTransform(srcPts, dstPts)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, matrix, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0))

        return warped
    }

    /**
     * Авто-обрезка полей: убирает белые области по краям.
     */
    fun autoCropMargins(input: Mat): Mat {
        if (input.empty()) return input.clone()

        val gray = Mat()
        val binary = Mat()
        val points = MatOfPoint()
        var result: Mat?

        try {
            if (input.channels() == 3) {
                Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
            } else {
                input.copyTo(gray)
            }

            Imgproc.threshold(gray, binary, 250.0, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_not(binary, binary)

            Core.findNonZero(binary, points)

            if (points.empty()) {
                return input.clone()
            }

            val rect = Geometry.boundingRect(points)

            val margin = 5
            val x = (rect.x - margin).coerceAtLeast(0)
            val y = (rect.y - margin).coerceAtLeast(0)
            val w = (rect.width + margin * 2).coerceAtMost(input.cols() - x)
            val h = (rect.height + margin * 2).coerceAtMost(input.rows() - y)

            Log.d("DocDetector", "AutoCrop: исходный=${input.cols()}x${input.rows()}, " +
                    "после=${w}x${h}, " +
                    "обрезано=${input.cols() - w}px по ширине, ${input.rows() - h}px по высоте")

            result = input.submat(y, y + h, x, x + w).clone()

        } catch (e: Exception) {
            Log.e("DocDetector", "autoCropMargins ошибка: ${e.message}")
            return input.clone()
        } finally {
            gray.release()
            binary.release()
            points.release()
        }

        return result ?: input.clone()
    }

    fun enhanceScan(input: Mat, filter: String = "bw"): Mat {
        return when (filter) {
            "bw" -> enhanceBw(input)
            "color" -> enhanceColor(input)
            "sharp" -> enhanceSharp(input)
            "shadow" -> removeShadows(input)
            else -> enhanceBw(input)
        }
    }

    /**
     * Sauvola Binarization — чистая бинаризация без выгрызания текста.
     */
    private fun sauvolaBinarization(input: Mat, windowSize: Int = 25, k: Double = 0.2): Mat {
        if (input.empty() || input.channels() != 1) {
            Log.e("DocDetector", "sauvolaBinarization: неверные входные данные")
            return input.clone()
        }

        val result = Mat()
        val grayFloat = Mat()
        val mean = Mat()
        val squared = Mat()
        val squaredMean = Mat()
        val variance = Mat()
        val stdDev = Mat()
        val threshold = Mat()
        val temp = Mat()

        try {
            input.convertTo(grayFloat, CvType.CV_32F, 1.0 / 255.0)
            Imgproc.GaussianBlur(grayFloat, mean, Size(windowSize.toDouble(), windowSize.toDouble()), 0.0)

            Core.multiply(grayFloat, grayFloat, squared)
            Imgproc.GaussianBlur(squared, squaredMean, Size(windowSize.toDouble(), windowSize.toDouble()), 0.0)

            Core.subtract(squaredMean, mean, squaredMean)
            Core.absdiff(squaredMean, Scalar(0.0), variance)

            Core.sqrt(variance, stdDev)

            val R = 0.5
            Core.divide(stdDev, Scalar(R), temp)
            Core.add(temp, Scalar(-1.0), temp)
            Core.multiply(temp, Scalar(k), temp)
            Core.add(temp, Scalar(1.0), temp)
            Core.multiply(mean, temp, threshold)

            Core.compare(grayFloat, threshold, result, Core.CMP_GT)
            result.convertTo(result, CvType.CV_8U, 255.0)

            Log.d("DocDetector", "Sauvola: OK, размер=${result.width()}x${result.height()}")

        } catch (e: Exception) {
            Log.e("DocDetector", "Sauvola ошибка: ${e.message}")
            Imgproc.adaptiveThreshold(input, result, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 11, 2.0)
        } finally {
            grayFloat.release()
            mean.release()
            squared.release()
            squaredMean.release()
            variance.release()
            stdDev.release()
            threshold.release()
            temp.release()
        }

        return result
    }

    private fun enhanceBw(input: Mat): Mat {
        return sauvolaBinarization(input)
    }

    private fun enhanceColor(input: Mat): Mat {
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
        return result
    }

    private fun enhanceSharp(input: Mat): Mat {
        return try {
            val input16 = Mat()
            input.convertTo(input16, CvType.CV_16S, 1.0, 0.0)
            val blurred = Mat()
            Imgproc.GaussianBlur(input16, blurred, Size(0.0, 0.0), 1.5)
            val result16 = Mat()
            Core.addWeighted(input16, 1.2, blurred, -0.2, 0.0, result16)
            val result = Mat()
            result16.convertTo(result, CvType.CV_8U)
            result
        } catch (e: Exception) { input.clone() }
    }

    // ====== REMOVE SHADOWS ======
    fun removeShadows(input: Mat): Mat {
        if (input.empty()) {
            Log.e("DocDetector", "removeShadows: пустое изображение")
            return Mat()
        }

        val result = Mat()
        val gray = Mat()
        val grayFloat = Mat()
        val bgFloat = Mat()
        val diffFloat = Mat()

        try {
            if (input.channels() == 3) {
                Imgproc.cvtColor(input, gray, Imgproc.COLOR_BGR2GRAY)
            } else {
                input.copyTo(gray)
            }

            if (gray.empty()) {
                Log.e("DocDetector", "removeShadows: gray пустой")
                return input.clone()
            }

            val maxDimension = max(gray.width(), gray.height())
            var kernelSize = when {
                maxDimension > 3000 -> 91
                maxDimension > 2000 -> 71
                maxDimension > 1000 -> 51
                else -> 31
            }
            kernelSize = min(kernelSize, 151)

            gray.convertTo(grayFloat, CvType.CV_32F, 1.0 / 255.0)
            Imgproc.GaussianBlur(grayFloat, bgFloat, Size(kernelSize.toDouble(), kernelSize.toDouble()), 0.0)
            Core.subtract(grayFloat, bgFloat, diffFloat)

            val minMaxResult = Core.minMaxLoc(diffFloat)
            val minVal = minMaxResult.minVal
            val maxVal = minMaxResult.maxVal

            if (maxVal - minVal < 0.05) {
                Log.d("DocDetector", "removeShadows: контраст слишком низкий, возвращаю оригинал")
                return input.clone()
            }

            Core.normalize(diffFloat, result, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)

            Log.d("DocDetector", "removeShadows: kernel=$kernelSize, размер=${result.width()}x${result.height()}")

        } catch (e: Exception) {
            Log.e("DocDetector", "removeShadows ошибка: ${e.message}")
            return input.clone()
        } finally {
            gray.release()
            grayFloat.release()
            bgFloat.release()
            diffFloat.release()
        }

        return result
    }

    // ====== ВСПОМОГАТЕЛЬНЫЕ ======
    // ====== РАССТОЯНИЕ ======
    private fun distance(p1: Point, p2: Point): Double {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }
}