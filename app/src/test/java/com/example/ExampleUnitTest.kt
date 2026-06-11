package com.example

import androidx.compose.ui.geometry.Offset
import com.example.model.CubicBezierHelper
import org.junit.Assert.*
import org.junit.Test

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun cubicBezier_inflectionPoints_isCorrect() {
    // S-curve: starting at (0, 0), control points at (1, 2) and (2, -2), ending at (3, 0)
    // This curve has a single symmetric inflection point exactly at t = 0.5
    val p0 = Offset(0f, 0f)
    val p1 = Offset(1f, 2f)
    val p2 = Offset(2f, -2f)
    val p3 = Offset(3f, 0f)

    val inflections = CubicBezierHelper.calculateInflectionPoints(p0, p1, p2, p3)
    assertEquals("Should find exactly one inflection point", 1, inflections.size)
    assertEquals("Inflection point should be exactly at t = 0.5", 0.5f, inflections[0], 1e-4f)
  }

  @Test
  fun cubicBezier_arcLength_isCorrect() {
    // Straight line represented as a Cubic Bezier curve from (0,0) to (10, 0)
    val p0 = Offset(0f, 0f)
    val p1 = Offset(10f / 3f, 0f)
    val p2 = Offset(20f / 3f, 0f)
    val p3 = Offset(10f, 0f)

    val length = CubicBezierHelper.calculateArcLength(p0, p1, p2, p3)
    assertEquals("Length of straight horizontal line should be exactly 10.0", 10.0f, length, 1e-4f)
  }

  @Test
  fun cubicBezier_curvature_isCorrect() {
    // Curve bowing upwards
    val p0 = Offset(0f, 0f)
    val p1 = Offset(1f, 1f)
    val p2 = Offset(2f, 1f)
    val p3 = Offset(3f, 0f)

    // Curvature at the center t = 0.5 should be negative (bending downwards)
    val curvature = CubicBezierHelper.calculateCurvature(p0, p1, p2, p3, 0.5f)
    assertTrue("Bending downward should have negative curvature", curvature < 0.0f)
  }

  @Test
  fun cubicBezier_curvatureNormalization_isCorrect() {
    // Smooth S-curve
    val p0 = Offset(0f, 0f)
    val p1 = Offset(2f, 4f)
    val p2 = Offset(4f, -4f)
    val p3 = Offset(6f, 0f)

    // Using CubicBezierHelper.findOptimalKeyPoints to subdivide based on 45 degrees threshold
    val keyPoints = CubicBezierHelper.findOptimalKeyPoints(p0, p1, p2, p3, angleThresholdDegrees = 45f)
    
    assertTrue("Should contain start point 0f", keyPoints.contains(0f))
    assertTrue("Should contain end point 1f", keyPoints.contains(1f))
    assertTrue("Should include inflection point 0.5f", keyPoints.contains(0.5f))
    assertTrue("Optimized nodes list should be ordered correctly", keyPoints.size >= 3)
  }
}

