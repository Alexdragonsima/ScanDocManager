package com.dragonsima.scandoc

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.video.KalmanFilter
import android.util.Log

class CornerTracker {

    private val kalmanFilters = Array(4) { createKalmanFilter() }
    private var isInitialized = false
    private val TAG = "CornerTracker"

    private fun createKalmanFilter(): KalmanFilter {
        // Используем правильный конструктор: KalmanFilter(dynamParams, measureParams, controlParams)
        val kf = KalmanFilter(4, 2, 0)

        try {
            // Устанавливаем матрицы через поля (public в OpenCV 5.0.0)
            val transition = Mat.eye(4, 4, CvType.CV_32F)
            transition.put(0, 2, 1.0)
            transition.put(1, 3, 1.0)
            kf._transitionMatrix = transition

            val measurement = Mat.zeros(2, 4, CvType.CV_32F)
            measurement.put(0, 0, 1.0)
            measurement.put(1, 1, 1.0)
            kf._measurementMatrix = measurement

            val processNoise = Mat.eye(4, 4, CvType.CV_32F)
            processNoise.setTo(Scalar(0.01)) // Увеличил для лучшей адаптации
            kf._processNoiseCov = processNoise

            val measurementNoise = Mat.eye(2, 2, CvType.CV_32F)
            measurementNoise.setTo(Scalar(0.1))
            kf._measurementNoiseCov = measurementNoise

            val errorCov = Mat.eye(4, 4, CvType.CV_32F)
            kf._errorCovPost = errorCov

        } catch (e: Exception) {
            Log.e(TAG, "KalmanFilter init error: ${e.message}")
        }

        return kf
    }

    fun update(corners: Array<Point>): Array<Point> {
        if (corners.size != 4) {
            return corners
        }

        // Инициализация
        if (!isInitialized) {
            isInitialized = true
            for (i in 0..3) {
                try {
                    val state = Mat.zeros(4, 1, CvType.CV_32F)
                    state.put(0, 0, corners[i].x)
                    state.put(1, 0, corners[i].y)
                    state.put(2, 0, 0.0)
                    state.put(3, 0, 0.0)
                    kalmanFilters[i]._statePost = state
                } catch (e: Exception) {
                    Log.e(TAG, "Init error: ${e.message}")
                }
            }
            return corners
        }

        // Обновление
        return Array(4) { i ->
            try {
                // 1. Predict
                val prediction = kalmanFilters[i].predict()

                // 2. Измерение
                val measurement = Mat.zeros(2, 1, CvType.CV_32F)
                measurement.put(0, 0, corners[i].x)
                measurement.put(1, 0, corners[i].y)

                // 3. Correct
                val corrected = kalmanFilters[i].correct(measurement)

                // 4. Извлекаем результат
                Point(
                    corrected.get(0, 0)?.get(0)?.toDouble() ?: corners[i].x,
                    corrected.get(1, 0)?.get(0)?.toDouble() ?: corners[i].y
                )
            } catch (e: Exception) {
                Log.w(TAG, "Kalman error: ${e.message}")
                corners[i]
            }
        }
    }

    fun reset() {
        isInitialized = false
        for (i in 0..3) {
            try {
                kalmanFilters[i] = createKalmanFilter()
            } catch (e: Exception) {
                // Игнорируем
            }
        }
    }
}