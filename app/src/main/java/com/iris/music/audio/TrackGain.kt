/*
 * This file is part of IRIS Music.
 * Copyright (C) 2026 WWRJ
 *
 * IRIS Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
/*
 * This file is part of IRIS Music.
 * Copyright (C) 2026 WWRJ
 *
 * IRIS Music is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.iris.music.audio

import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.sin

/**
 * 响度均衡（v3.11 · 曲间拉平）：边听边测响度，把偏响的曲子压下来、偏轻的抬上去。
 *
 * 解决什么：歌单里"现场版巨响、老录音听不清"，每首都要手动调音量。
 * 与 [EqualizerController] 的等响补偿（同一音量下的听感曲线）不同，
 * 本模块解决的是**曲与曲之间**的响度差异。
 *
 * 为什么在播放时测而不是扫描时测：
 * - 扫描期解码全部音频 = 冷启动耗时回归；播放时只有当前曲在解码，零额外 IO。
 * - 每首歌只需完整听一次，测完结果按 path 存进 prefs，之后命中缓存直接用。
 *
 * 测量原理（ITU-R BS.1770 K-weighting 的精简版）：
 * 1. K 加权：高架滤波器(+4dB @1.68kHz) + 高通(@38Hz)，模拟人耳响度感知曲线。
 * 2. 按 10s 分块累计均方能量，连续 2 块差 < 1.5dB 视为稳定 → 定稿（30s 无论如何定稿，
 *    避免安静 intro 一直不收敛；定稿后停止测量，安静 outro 不会拉低结果）。
 * 3. LUFS ≈ -0.691 + 10·log10(帧均方能量)，增益 = 10^((-14 - LUFS)/20)，
 *    钳制在 ±6dB（0.5x~2x）内，防止极端值破坏动态。
 *
 * 应用：链首 [TrackGain] 施加增益，之后 [SafeLimiter] 照常兜底；
 * 测量取原始信号（gain 之前），保证"测的是曲子本身"。
 *
 * 线程模型：queueInput/测量在音频线程；setCurrentSong 在主线程（切歌回调），
 * 用 @Volatile 引用交换，gain 平滑在音频线程内做。
 */
@OptIn(UnstableApi::class)
object TrackGain : BaseAudioProcessor() {

    const val KEY_ENABLED = "loudnorm_enabled"
    /** 拉平力度 0-100：0=不校正，100=完全拉到目标响度，默认 70 */
    const val KEY_STRENGTH = "loudnorm_strength"
    private const val KEY_STORE = "loudnorm_lufs_store"

    /** path → LUFS 存储上限（约 400 首 × ~100B ≈ 40KB） */
    private const val STORE_MAX = 400
    /** 目标响度：Spotify/-14 LUFS 档，听感饱满且给 SafeLimiter 留余量 */
    private const val TARGET_LUFS = -14.0
    /** 少于该时长不定稿（短促片段测不准） */
    private const val MIN_COMMIT_SECS = 10.0
    /** 到该时长无论如何定稿 */
    private const val MAX_COMMIT_SECS = 30.0
    /** 分块稳定性阈值（dB） */
    private const val STABLE_DB = 1.5
    private const val CHUNK_SECS = 10.0

    @Volatile var enabled: Boolean = false
        private set
    @Volatile var strength: Int = 70
        private set

    // ==================== 库（主线程读，音频线程读） ====================

    @Volatile private var prefs: SharedPreferences? = null
    /** path → 该曲的 LUFS（定稿后写入，Key 是完整路径） */
    @Volatile private var store: MutableMap<String, Float> = mutableMapOf()

    fun init(p: SharedPreferences) {
        prefs = p
        enabled = p.getBoolean(KEY_ENABLED, false)
        strength = p.getInt(KEY_STRENGTH, 70).coerceIn(0, 100)
        store = loadStore(p)
    }

    fun setEnabled(v: Boolean) {
        enabled = v
    }

    fun setStrength(v: Int) {
        strength = v.coerceIn(0, 100)
    }

    // ==================== 切歌（主线程调用） ====================

    /** 切歌：传当前曲路径；null = 未知（重置为无增益状态） */
    fun setCurrentSong(path: String?) {
        pendingPath = path
        pendingCommit = true // 下一次音频线程回调先结算上一曲
    }

    // ==================== DSP 状态（仅音频线程） ====================

    // K 加权：high shelf (f0=1681.97, +4dB, Q=0.7072) → high pass (f0=38.14, Q=0.5003)
    private var hsB0 = 0f; private var hsB1 = 0f; private var hsB2 = 0f
    private var hsA1 = 0f; private var hsA2 = 0f
    private var hpB0 = 0f; private var hpB1 = 0f; private var hpB2 = 0f
    private var hpA1 = 0f; private var hpA2 = 0f
    /** 滤波器状态**必须按声道独立**：共享一个状态会让 L 处理完的 y 污染 R 的输入历史，能量测量失真 */
    private class Bq {
        var hsX1 = 0f; var hsX2 = 0f; var hsY1 = 0f; var hsY2 = 0f
        var hpX1 = 0f; var hpX2 = 0f; var hpY1 = 0f; var hpY2 = 0f
    }
    private var bqs: Array<Bq> = emptyArray()
    private fun bqsFor(channels: Int): Array<Bq> {
        if (bqs.size != channels) bqs = Array(channels) { Bq() }
        return bqs
    }
    private var designedRate = 0

    // 测量状态
    private var measPath: String? = null
    private var measSum = 0.0      // K 加权后样本平方和
    private var measFrames = 0L
    private var committed = false
    private var prevChunkLufs = Float.NaN
    private var stableChunks = 0
    // 分块边界累计（用于算块间差）
    private var chunkStartSum = 0.0
    private var chunkStartFrames = 0L

    // 切歌交接（volatile 引用，主线程写/音频线程消费）
    @Volatile private var pendingPath: String? = null
    @Volatile private var pendingCommit: Boolean = false

    // 增益平滑
    private var gain = 1f
    private var gainTarget = 1f
    private var releaseCoef = 0f
    private var lastAppliedStrength = -1
    private var lastAppliedEnabled = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val e = inputAudioFormat.encoding
        if (e != C.ENCODING_PCM_16BIT && e != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        if (inputAudioFormat.channelCount < 1) return AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush() {
        bqs.forEach {
            it.hsX1 = 0f; it.hsX2 = 0f; it.hsY1 = 0f; it.hsY2 = 0f
            it.hpX1 = 0f; it.hpX2 = 0f; it.hpY1 = 0f; it.hpY2 = 0f
        }
        // seek 会 flush：测量跨块继续（重复样本对 10s 块能量影响可忽略），增益保持
    }

    private fun design(sampleRateIn: Int) {
        designedRate = sampleRateIn
        val sr = if (sampleRateIn > 0) sampleRateIn else 44100
        releaseCoef = 1f - 10f.pow(-1f / (0.300f * sr)) // 300ms 平滑，切歌音量渐变不突跳

        // High Shelf：RBJ cookbook shelf，f0=1681.974450955533, A=+4dB(10^(4/40)), Q=0.7071752369554196
        run {
            val f0 = 1681.974450955533f
            val A = 10f.pow(4f / 40f)
            val Q = 0.7071752369554196f
            val w0 = 2f * PI.toFloat() * f0 / sr
            val cw = cos(w0); val sw = sin(w0); val al = sw / (2f * Q)
            val sa = 2f * sqrt(A) * al
            val a0 = (A + 1f) + (A - 1f) * cw + sa
            hsB0 = (A * ((A + 1f) + (A - 1f) * cw + sa)) / a0
            hsB1 = (-2f * A * ((A - 1f) + (A + 1f) * cw)) / a0
            hsB2 = (A * ((A + 1f) + (A - 1f) * cw - sa)) / a0
            hsA1 = (2f * ((A - 1f) - (A + 1f) * cw)) / a0
            hsA2 = ((A + 1f) + (A - 1f) * cw - sa) / a0
        }
        // High Pass：f0=38.13547087602444, Q=0.5003270373238773
        run {
            val f0 = 38.13547087602444f
            val Q = 0.5003270373238773f
            val w0 = 2f * PI.toFloat() * f0 / sr
            val cw = cos(w0); val al = sin(w0) / (2f * Q)
            val a0 = 1f + al
            hpB0 = ((1f + cw) / 2f) / a0
            hpB1 = (-(1f + cw)) / a0
            hpB2 = ((1f + cw) / 2f) / a0
            hpA1 = (-2f * cw) / a0
            hpA2 = (1f - al) / a0
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount
        val is16 = format.encoding == C.ENCODING_PCM_16BIT
        val smp = if (is16) 2 else 4
        val frameBytes = channels * smp
        if (frameBytes <= 0) return

        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()

        if (designedRate != format.sampleRate) design(format.sampleRate)

        // ---- 切歌交接：结算上一曲测量、切换增益目标 ----
        if (pendingCommit) {
            pendingCommit = false
            val p = pendingPath
            pendingPath = null
            if (measPath != null) commitMeasurement()
            resetMeasurement(p)
            gainTarget = if (enabled && p != null) gainFor(p) ?: 1f else 1f
            lastAppliedStrength = strength
            lastAppliedEnabled = enabled
        }

        // 力度/开关在播放中改动：重算目标增益（与切歌同一路径）
        if (lastAppliedStrength != strength || lastAppliedEnabled != enabled) {
            lastAppliedStrength = strength
            lastAppliedEnabled = enabled
            val p = measPath
            gainTarget = if (enabled && p != null) gainFor(p) ?: 1f else 1f
        }

        if (!enabled) return

        val i0 = out.position()
        val bytes = out.remaining()
        val frames = bytes / frameBytes
        val bqs = bqsFor(channels)
        var i = i0
        var n = 0
        while (n < frames) {
            // BS.1770：各声道分别 K 加权后累计能量（每声道 G=1），按帧均方算 LUFS
            var weightedSq = 0.0
            var ch = 0
            while (ch < channels) {
                val st = bqs[ch]
                val raw = if (is16) {
                    (out.getShort(i + ch * smp).toInt()) / 32768f
                } else {
                    out.getFloat(i + ch * smp)
                }
                // biquad cascade（状态按声道独立）
                var x = raw
                val y1 = hsB0 * x + hsB1 * st.hsX1 + hsB2 * st.hsX2 - hsA1 * st.hsY1 - hsA2 * st.hsY2
                st.hsX2 = st.hsX1; st.hsX1 = x; st.hsY2 = st.hsY1; st.hsY1 = y1
                x = y1
                val y2 = hpB0 * x + hpB1 * st.hpX1 + hpB2 * st.hpX2 - hpA1 * st.hpY1 - hpA2 * st.hpY2
                st.hpX2 = st.hpX1; st.hpX1 = x; st.hpY2 = st.hpY1; st.hpY1 = y2
                weightedSq += (y2.toDouble() * y2.toDouble())
                ch++
            }
            // 测量（测的是原始信号，与输出增益无关）
            if (!committed && measPath != null) {
                measSum += weightedSq
                measFrames++
                // 每 CHUNK_SECS 检查块间稳定性
                val chunkFrames = (format.sampleRate * CHUNK_SECS).toLong()
                val done = measFrames - chunkStartFrames >= chunkFrames
                if (done || measFrames >= (format.sampleRate * MAX_COMMIT_SECS).toLong()) {
                    val framesInChunk = (measFrames - chunkStartFrames).coerceAtLeast(1)
                    val chunkLufs = lufsOf(measSum - chunkStartSum, framesInChunk)
                    if (!prevChunkLufs.isNaN()) {
                        if (abs(chunkLufs - prevChunkLufs) <= STABLE_DB) stableChunks++ else stableChunks = 0
                    }
                    prevChunkLufs = chunkLufs
                    chunkStartSum = measSum
                    chunkStartFrames = measFrames
                    val secs = measFrames.toDouble() / format.sampleRate
                    if ((secs >= MIN_COMMIT_SECS && stableChunks >= 1) || secs >= MAX_COMMIT_SECS) {
                        commitMeasurement()
                    }
                }
            }

            // 施加均衡增益（平滑）
            if (gain != gainTarget) {
                val d = gainTarget - gain
                gain += releaseCoef * d
                if (abs(d) < 1e-4f) gain = gainTarget
            }
            if (gain != 1f) {
                var c = 0
                while (c < channels) {
                    val idx = i + c * smp
                    if (is16) {
                        val v = (out.getShort(idx).toInt()) / 32768f * gain
                        out.putShort(idx, (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    } else {
                        out.putFloat(idx, (out.getFloat(idx) * gain).coerceIn(-1f, 1f))
                    }
                    c++
                }
            }
            i += frameBytes
            n++
        }
    }

    // ==================== 测量/存储 ====================

    private fun resetMeasurement(path: String?) {
        measPath = path
        measSum = 0.0
        measFrames = 0
        // 已有测过的 LUFS：直接标记完成，跳过重复测量（省 K 加权 CPU）
        committed = path != null && store.containsKey(path)
        prevChunkLufs = Float.NaN
        stableChunks = 0
        chunkStartSum = 0.0
        chunkStartFrames = 0
    }

    /** 测量充分则写库；不足 10s 视为无效不写（避免 seek 片段污染） */
    private fun commitMeasurement() {
        val path = measPath
        if (path != null && !committed && measFrames > 0) {
            val minFrames = designedRate * MIN_COMMIT_SECS
            if (measFrames >= minFrames) {
                val lufs = lufsOf(measSum, measFrames)
                if (lufs.isFinite() && lufs > -60f && lufs < 0f) {
                    putLufs(path, lufs)
                }
            }
        }
        committed = true
    }

    private fun lufsOf(sumSq: Double, frames: Long): Float {
        if (frames <= 0) return Float.NaN
        val mean = sumSq / frames
        if (mean <= 1e-12) return Float.NaN
        return (-0.691f + 10f * log10(mean)).toFloat()
    }

    /** 查库算增益；未测过返回 null（= 1.0 直通）。力度×偏差决定实际校正量 */
    private fun gainFor(path: String): Float? {
        val lufs = store[path] ?: return null
        val amt = strength / 100f
        val db = ((TARGET_LUFS - lufs).toFloat() * amt).coerceIn(-6f, 6f)
        return 10f.pow(db / 20f)
    }

    // 库存取（切换时才发生，非实时路径；同步方法保护 Map）
    @Synchronized private fun putLufs(path: String, lufs: Float) {
        val m = store.toMutableMap()
        m[path] = lufs
        // 超限删最旧（插入序近似访问序，够用）
        while (m.size > STORE_MAX) {
            val eldest = m.keys.firstOrNull() ?: break
            m.remove(eldest)
        }
        store = m
        prefs?.edit()?.putString(KEY_STORE, encodeStore(m))?.apply()
    }

    private fun loadStore(p: SharedPreferences): MutableMap<String, Float> {
        val raw = p.getString(KEY_STORE, null) ?: return mutableMapOf()
        val out = LinkedHashMap<String, Float>()
        raw.split('').forEach { pair ->
            val idx = pair.lastIndexOf(':')
            if (idx > 0) {
                val k = pair.substring(0, idx)
                val v = pair.substring(idx + 1).toFloatOrNull()
                if (k.isNotEmpty() && v != null) out[k] = v
            }
        }
        return out
    }

    private fun encodeStore(m: Map<String, Float>): String =
        m.entries.joinToString("") { "${it.key}:${it.value}" }
}
