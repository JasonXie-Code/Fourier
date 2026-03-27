package com.fourier.audioanalyzer.util

import android.graphics.Bitmap
import android.view.View
import java.io.File
import java.io.FileOutputStream

/**
 * 图像工具类
 * 用于保存视图截图
 */
object ImageUtils {
    /**
     * 保存视图为图片
     */
    fun saveViewAsImage(view: View, file: File): Boolean {
        return try {
            view.isDrawingCacheEnabled = true
            view.buildDrawingCache()
            val bitmap = Bitmap.createBitmap(view.drawingCache)
            view.isDrawingCacheEnabled = false
            
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()
            
            bitmap.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 创建视图的Bitmap
     */
    fun createBitmapFromView(view: View): Bitmap? {
        return try {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY)
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            
            val bitmap = Bitmap.createBitmap(
                view.width,
                view.height,
                Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            view.draw(canvas)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
