package com.example.spy.util

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

/** Получить локальный IPv4-адрес в WiFi-сети (не 127.0.0.1). */
fun getLocalIpAddress(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap {
            it.inetAddresses.toList()
        }?.firstOrNull { addr ->
            !addr.isLoopbackAddress && addr is Inet4Address
        }?.hostAddress
    } catch (_: Exception) {
        null
    }
}

/** Генерирует чёрно-белый QR-код как Bitmap. */
fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
    val writer = QRCodeWriter()
    val matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}
