package com.example.model

import androidx.compose.ui.geometry.Offset
import kotlin.math.*

object CubicBezierHelper {

    // Converts Cubic Bezier points to Power Basis coefficients: B(t) = a*t^3 + b*t^2 + c*t + d
    class PowerBasis(
        val ax: Float, val bx: Float, val cx: Float, val dx: Float,
        val ay: Float, val by: Float, val cy: Float, val dy: Float
    ) {
        // First derivative coefficients: B'(t) = 3*a*t^2 + 2*b*t + c
        // Second derivative coefficients: B''(t) = 6*a*t + 2*b
        
        fun getVelocity(t: Float): Offset {
            val vx = 3f * ax * t * t + 2f * bx * t + cx
            val vy = 3f * ay * t * t + 2f * by * t + cy
            return Offset(vx, vy)
        }
        
        fun getAcceleration(t: Float): Offset {
            val axVal = 6f * ax * t + 2f * bx
            val ayVal = 6f * ay * t + 2f * by
            return Offset(axVal, ayVal)
        }
    }

    fun getPowerBasis(p0: Offset, p1: Offset, p2: Offset, p3: Offset): PowerBasis {
        val ax = p3.x - 3f * p2.x + 3f * p1.x - p0.x
        val bx = 3f * (p2.x - 2f * p1.x + p0.x)
        val cx = 3f * (p1.x - p0.x)
        val dx = p0.x

        val ay = p3.y - 3f * p2.y + 3f * p1.y - p0.y
        val by = 3f * (p2.y - 2f * p1.y + p0.y)
        val cy = 3f * (p1.y - p0.y)
        val dy = p0.y

        return PowerBasis(ax, bx, cx, dx, ay, by, cy, dy)
    }

    /**
     * Calculates all inflection points of the cubic Bezier spline in interval t in (0, 1).
     * At inflection points, curvature changes sign, i.e., I(t) = x'(t)*y''(t) - y'(t)*x''(t) = 0.
     */
    fun calculateInflectionPoints(p0: Offset, p1: Offset, p2: Offset, p3: Offset): List<Float> {
        val basis = getPowerBasis(p0, p1, p2, p3)
        val ax = basis.ax; val bx = basis.bx; val cx = basis.cx
        val ay = basis.ay; val by = basis.by; val cy = basis.cy

        val aInf = 3f * (bx * ay - ax * by)
        val bInf = 3f * (cx * ay - ax * cy)
        val cInf = cx * by - bx * cy

        val results = mutableListOf<Float>()

        if (abs(aInf) < 1e-5f) {
            if (abs(bInf) > 1e-5f) {
                val t = -cInf / bInf
                if (t in 0.001f..0.999f) {
                    results.add(t)
                }
            }
        } else {
            val discriminant = bInf * bInf - 4f * aInf * cInf
            if (discriminant >= 0f) {
                val rD = sqrt(discriminant)
                val t1 = (-bInf - rD) / (2f * aInf)
                val t2 = (-bInf + rD) / (2f * aInf)
                if (t1 in 0.001f..0.999f) results.add(t1)
                if (t2 in 0.001f..0.999f) results.add(t2)
            }
        }
        return results.sorted()
    }

    /**
     * Calculates the highly precise arc length of the cubic Bezier curve in interval [t0, t1]
     * using 5-point Gauss-Legendre Quadrature.
     */
    fun calculateArcLength(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t0: Float = 0f, t1: Float = 1f): Float {
        val basis = getPowerBasis(p0, p1, p2, p3)
        val xs = doubleArrayOf(
            0.0,
            -0.5384693101056831, 0.5384693101056831,
            -0.9061798459386640, 0.9061798459386640
        )
        val ws = doubleArrayOf(
            0.5688888888888889,
            0.4786286704993665, 0.4786286704993665,
            0.2369268850561891, 0.2369268850561891
        )

        var length = 0.0
        val tDiff = (t1 - t0) / 2.0
        val tSum = (t1 + t0) / 2.0

        for (i in xs.indices) {
            val t = (tDiff * xs[i] + tSum).toFloat()
            val velocity = basis.getVelocity(t)
            val speed = hypot(velocity.x, velocity.y)
            length += ws[i] * speed
        }

        return (length * tDiff).toFloat()
    }

    /**
     * Calculates curvature kappa(t) = (x'*y'' - y'*x'') / (x'^2 + y'^2)^(1.5)
     */
    fun calculateCurvature(p0: Offset, p1: Offset, p2: Offset, p3: Offset, t: Float): Float {
        val basis = getPowerBasis(p0, p1, p2, p3)
        val vel = basis.getVelocity(t)
        val acc = basis.getAcceleration(t)

        val speedSq = vel.x * vel.x + vel.y * vel.y
        if (speedSq < 1e-6f) return 0f

        val numerator = vel.x * acc.y - vel.y * acc.x
        val denominator = speedSq * sqrt(speedSq)
        return numerator / denominator
    }

    /**
     * Section-based Curvature Normalization to find the minimum number of nodes (subsections)
     * necessary to represent a curve perfectly without exceeding threshold changes in direction.
     * Prevents node clustering.
     */
    fun findOptimalKeyPoints(
        p0: Offset,
        p1: Offset,
        p2: Offset,
        p3: Offset,
        angleThresholdDegrees: Float = 60f
    ): List<Float> {
        val keyPoints = mutableListOf<Float>()
        keyPoints.add(0f)

        // Always add inflection points as critical structural boundaries
        val inflections = calculateInflectionPoints(p0, p1, p2, p3)
        keyPoints.addAll(inflections)
        keyPoints.add(1f)
        keyPoints.sort()

        val normalizedKeyPoints = mutableListOf<Float>()
        normalizedKeyPoints.add(0f)

        // For each segment between inflection points, check angular deviation
        for (i in 0 until keyPoints.size - 1) {
            val ta = keyPoints[i]
            val tb = keyPoints[i + 1]
            if (tb - ta < 0.01f) continue

            val basis = getPowerBasis(p0, p1, p2, p3)
            val velStart = basis.getVelocity(ta)
            val velEnd = basis.getVelocity(tb)

            val lenStart = hypot(velStart.x, velStart.y)
            val lenEnd = hypot(velEnd.x, velEnd.y)

            if (lenStart > 0.01f && lenEnd > 0.01f) {
                val dot = (velStart.x * velEnd.x + velStart.y * velEnd.y) / (lenStart * lenEnd)
                val angle = acos(dot.coerceIn(-1f, 1f)) * (180f / PI)
                
                // If angle change is too steep, subdivide recursively or add sub-nodes
                if (angle > angleThresholdDegrees) {
                    val numSubdivisions = ceil(angle / angleThresholdDegrees).toInt().coerceIn(2, 4)
                    for (step in 1 until numSubdivisions) {
                        val subT = ta + (tb - ta) * (step.toFloat() / numSubdivisions)
                        normalizedKeyPoints.add(subT)
                    }
                }
            }
            normalizedKeyPoints.add(tb)
        }

        return normalizedKeyPoints.distinct().sorted()
    }
}
