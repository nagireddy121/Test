package com.tabdisplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.app.Activity
import java.util.concurrent.Executors
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Receives H.264 from the Mac over a USB adb-reverse tunnel (localhost:7878)
 * and renders it fullscreen. Touches are sent back as normalised coordinates.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {
    private val sender = Executors.newSingleThreadExecutor()
    private lateinit var surfaceView: SurfaceView
    @Volatile private var surface: Surface? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var running = true
    private var worker: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        setContentView(surfaceView)
        surfaceView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        surface = h.surface
        running = true
        worker = thread(name = "stream") { streamLoop() }
    }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) {
        running = false
        surface = null
        worker?.interrupt()
    }

    private fun streamLoop() {
        while (running) {
            try {
                Socket("127.0.0.1", 7878).use { sock ->
                    sock.tcpNoDelay = true
                    val input = DataInputStream(sock.getInputStream().buffered(1 shl 20))
                    val output = DataOutputStream(sock.getOutputStream())
                    out = output
                    val w = surfaceView.width
                    val h = surfaceView.height
                    output.write(byteArrayOf(0x01, (w shr 8).toByte(), w.toByte(), (h shr 8).toByte(), h.toByte()))
                    output.flush()
                    decode(input, w, h)
                }
            } catch (_: Exception) {
                // Mac app not running / cable not forwarded yet: retry.
            }
            out = null
            if (running) try { Thread.sleep(1000) } catch (_: InterruptedException) {}
        }
    }

    private fun decode(input: DataInputStream, w: Int, h: Int) {
        val s = surface ?: return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
        if (Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, s, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var ptsUs = 0L
        try {
            while (running) {
                val len = input.readInt()
                val data = ByteArray(len)
                input.readFully(data)

                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    codec.getInputBuffer(inIdx)!!.apply { clear(); put(data) }
                    codec.queueInputBuffer(inIdx, 0, len, ptsUs, 0)
                    ptsUs += 16_666
                }
                var outIdx = codec.dequeueOutputBuffer(info, 0)
                while (outIdx >= 0) {
                    codec.releaseOutputBuffer(outIdx, true)
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> 2
            else -> return true
        }
        val nx = (e.x / surfaceView.width).coerceIn(0f, 1f)
        val ny = (e.y / surfaceView.height).coerceIn(0f, 1f)
        val o = out ?: return true
        sender.execute {
            try {
                synchronized(o) {
                    o.writeByte(0x02); o.writeByte(action)
                    o.writeFloat(nx); o.writeFloat(ny)
                    o.flush()
                }
            } catch (_: Exception) {}
        }
        return true
    }
}
