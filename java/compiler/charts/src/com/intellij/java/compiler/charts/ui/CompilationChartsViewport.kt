// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.compiler.charts.ui

import com.intellij.ui.components.JBViewport
import com.intellij.ui.components.ZoomingDelegate
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

class CompilationChartsViewport() : JBViewport() {
  override fun createZooming(): ZoomingDelegate = CompilationChartsZoomingDelegate(view as JComponent, this)

  private class CompilationChartsZoomingDelegate(private val component: JComponent, private val viewport: JBViewport) : ZoomingDelegate(component, viewport) {
    private var magnificationPoint: Point? = null
    private var localX: Int = 0
    private val magnification: MagnificationCounter = MagnificationCounter(0.0)
    override fun paint(g: Graphics) {
      if (g !is Graphics2D) return
      if (component is CompilationChartsDiagramsComponent) component.offset = -(magnificationPoint?.x ?: 0)
      component.paint(g)
    }

    override fun magnificationStarted(at: Point) {
      magnificationPoint = viewport.viewPosition
      if (component is CompilationChartsDiagramsComponent) component.offset = -viewport.viewPosition.x
      localX = at.x
    }

    override fun magnificationFinished(magnification: Double) {
      if (component is CompilationChartsDiagramsComponent) component.offset = 0
      magnificationPoint = null
      localX = 0
      this.magnification.reset()
    }

    override fun magnify(magnification: Double) {
      if (component !is CompilationChartsDiagramsComponent) return
      this.magnification.set(magnification)

      val scale = this.magnification.get()
      if (scale == 0.0) return

      magnificationPoint = viewport.magnificator?.magnify(magnificationToScale(scale), Point((magnificationPoint?.x ?: 0) + localX, 0))
      component.smartDraw(true, false)
    }

    override fun isActive(): Boolean = magnificationPoint != null

    private data class MagnificationCounter(var magnification: Double) {
      private var count = AtomicBoolean(false)
      private var scale: Double = 0.0
      fun get(): Double = if (count.getAndSet(true)) 0.0 else scale
      fun set(data: Double) {
        scale = data - magnification
        magnification = data
        count.set(false)
      }

      fun reset() {
        count.set(false)
        magnification = 0.0
        scale = 0.0
      }
    }
  }
}