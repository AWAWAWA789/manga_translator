package com.manga.translator.plugin

import android.graphics.Rect
import com.manga.translator.domain.detection.PanelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * PanelDetector 单元测试。
 *
 * 仅覆盖 findPanelForRect（纯 Rect 逻辑，无 OpenCV 依赖）。
 * detectPanels 依赖 OpenCV native 库，不在单测范围。
 *
 * 注：当前环境 Robolectric SDK jar 下载/JVM 崩溃，暂时 @Ignore，
 * 待网络或环境就绪后移除注解启用。
 */
@Ignore("Robolectric SDK 环境不可用，待网络/环境就绪后启用")
@RunWith(RobolectricTestRunner::class)
class PanelDetectorTest {

    private val detector = PanelDetector()

    private fun panel(left: Int, top: Int, right: Int, bottom: Int): PanelInfo {
        val w = right - left
        val h = bottom - top
        return PanelInfo(Rect(left, top, right, bottom), w.toLong() * h)
    }

    @Test
    fun `空分镜列表返回 null`() {
        val result = detector.findPanelForRect(emptyList(), Rect(10, 10, 20, 20))
        assertNull(result)
    }

    @Test
    fun `rect 在分镜内返回该分镜`() {
        val panel1 = panel(100, 100, 300, 400)
        val panel2 = panel(500, 100, 700, 400)
        val rect = Rect(150, 150, 200, 200)
        assertEquals(panel1, detector.findPanelForRect(listOf(panel1, panel2), rect))
    }

    @Test
    fun `rect 跨两个分镜返回重叠面积更大的`() {
        val panel1 = panel(100, 100, 300, 400) // 面积 60000
        val panel2 = panel(250, 100, 450, 400) // 面积 60000
        // rect 与 panel1 重叠 [250,150,300,200]=50*50=2500
        // rect 与 panel2 重叠 [250,150,300,200]=50*50=2500，相同面积取第一个（bestOverlap 不严格大于）
        val rect = Rect(250, 150, 300, 200)
        val result = detector.findPanelForRect(listOf(panel1, panel2), rect)
        assertEquals(panel1, result)
    }

    @Test
    fun `rect 不在任何分镜内但距离最近且在阈值内返回最近分镜`() {
        val panel1 = panel(100, 100, 300, 400) // centerX=200, width=200, height=300
        // rect 在 panel1 右侧外，距离在 2*maxDim=600 阈值内
        val rect = Rect(310, 200, 320, 210) // centerX=315, centerY=205
        val result = detector.findPanelForRect(listOf(panel1), rect)
        assertEquals(panel1, result)
    }

    @Test
    fun `rect 远离所有分镜返回 null`() {
        val panel1 = panel(100, 100, 300, 400) // maxDim=300, maxDist=600, maxDist²=360000
        // rect 中心点距离 panel1 中心 > 600
        val rect = Rect(5000, 5000, 5010, 5010) // centerX=5005, 远超阈值
        val result = detector.findPanelForRect(listOf(panel1), rect)
        assertNull(result)
    }

    @Test
    fun `rect 在两个分镜中选重叠更大的`() {
        val panel1 = panel(100, 100, 300, 400)
        val panel2 = panel(200, 100, 400, 400)
        // rect 与 panel1 重叠 [200,150,250,200]=50*50=2500
        // rect 与 panel2 重叠 [200,150,250,200]=50*50=2500 相同，但 rect 更靠 panel2 右侧
        // 用不对称 rect 让 panel2 重叠更大
        val rect = Rect(250, 150, 350, 200) // 与 panel1 重叠 [250,150,300,200]=2500, 与 panel2 重叠 [250,150,350,200]=5000
        val result = detector.findPanelForRect(listOf(panel1, panel2), rect)
        assertEquals(panel2, result)
    }
}
