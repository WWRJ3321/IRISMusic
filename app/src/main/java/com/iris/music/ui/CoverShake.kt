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
package com.iris.music.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * 摇晃检测 + 弹簧物理，产出封面应施加的 rotationZ（度）与平移（px）。
 * 关闭时不注册传感器、状态恒为 0（封面静止）。
 */
@Composable
fun rememberShakeState(enabled: Boolean): CoverShake {
    val ctx = LocalContext.current
    val shake = remember { CoverShake() }

    // 线性加速度（已去重力）。摇动时幅值突增。
    val rawAx = remember { FloatArray(1) }
    val rawAy = remember { FloatArray(1) }
    val rawAz = remember { FloatArray(1) }
    val grav = remember { floatArrayOf(0f, 0f, 9.81f) }  // 重力低通估计

    DisposableEffect(enabled) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        if (!enabled || sm == null) {
            shake.reset()
            return@DisposableEffect onDispose { }
        }
        // 用加速度计（含重力）：低通重力分量给出"朝哪倾"=静态吊点；
        // 原始值减低通重力=线性加速度，用于检测摇动冲量。
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                rawAx[0] = e.values[0]; rawAy[0] = e.values[1]; rawAz[0] = e.values[2]
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }

    LaunchedEffect(enabled) {
        if (!enabled) { shake.reset(); return@LaunchedEffect }
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f else ((now - last) / 16_666_667f).coerceIn(0.2f, 3f)
                last = now
                val ax = rawAx[0]; val ay = rawAy[0]; val az = rawAz[0]
                // 扣掉重力低通分量：兼容退回普通加速度计的情况（其读数常年含 ~9.8 重力）。
                val gx = grav[0]; val gy = grav[1]; val gz = grav[2]
                // 中速低通：平滑估计重力方向（朝哪倾），又快又稳 → 静态倾斜跟手连贯。
                grav[0] = gx + (ax - gx) * 0.08f
                grav[1] = gy + (ay - gy) * 0.08f
                grav[2] = gz + (az - gz) * 0.08f
                val lx = ax - gx; val ly = ay - gy; val lz = az - gz
                val mag = kotlin.math.sqrt(lx * lx + ly * ly + lz * lz)
                // 摇动冲量：线性加速度幅值高过静止噪声阈值的部分，乘方向注入弹簧速度。
                val impulse = (mag - SHAKE_NOISE).coerceAtLeast(0f)
                // 把重力分量(-ax,-ay 方向=垂坠方向)作为静态吊点目标传入。
                shake.step(dt, lx, ly, -gx, -gy, impulse)
            }
        }
    }
    return shake
}

private const val SHAKE_NOISE = 5.0f  // m/s²，触发阈值（调低=更易触发摇动）

/**
 * 封面的弹簧物理。rotationZ 与 translationX/Y 用欠阻尼弹簧：
 * 摇动给冲量 → 甩起 → 回弹数次（duangduang）→ 收敛原位。
 */
class CoverShake internal constructor() {
    var rotationZ by mutableFloatStateOf(0f)
        internal set
    var translationX by mutableFloatStateOf(0f)
        internal set
    var translationY by mutableFloatStateOf(0f)
        internal set

    // 以"度 / px"为单位的物理量与速度
    private var rot = 0f;  private var rotV = 0f
    private var tx = 0f;   private var txV = 0f
    private var ty = 0f;   private var tyV = 0f

    // 欠阻尼参数：k 决定回弹快慢，c 决定摆几下。偏小 c => 明显 duangduang。
    // 降 k + 提 c：起步与回摆更柔顺连贯，不再一顿一顿。
    private val kR = 0.030f; private val cR = 0.22f
    private val kT = 0.032f; private val cT = 0.24f
    // 越出"软限位"后的回复力刚度与阻尼：像橡皮筋越拉越紧，而不是撞墙。
    // kOver 足够大→冲过边界后果断拽回，消除"贴边傻冲、延迟弹回"；平方起步保证过界瞬间不阶跃。
    private val kOver = 0.55f; private val cOver = 0.10f
    // 软限位：旋转 ±ROT_LIM 度、平移 ±(TX_LIM, TY_LIM) px，封面基本不出卡。
    private val ROT_LIM = 5f
    private val TX_LIM = 50f
    private val TY_LIM = 50f
    // 硬夹（兜底，给足余量避免顿挫；软限位+阻尼已让封面几乎碰不到，正常摇不可见）。
    // 上下放宽到与横向一致，让"上下摇"也能明显甩开。
    private val ROT_HARD = 6.5f
    private val TX_HARD = 64f
    private val TY_HARD = 64f

    /** 软限位弹簧的回复力：范围内线性 -k·x；越界后每超一分再拽回 kOver 倍。 */
    private fun restore(x: Float, lim: Float, k: Float): Float {
        val base = -k * x
        val over = kotlin.math.abs(x) - lim
        return if (over > 0f) base - kOver * over * kotlin.math.sign(x) else base
    }

    /** dx, dy：静态"吊点"目标位移（px），来自重力方向；静止倾斜时封面往这方向垂坠。
     *  内部按固定小步长 SUB_DT 子步积分：太猛/掉帧时也不会"一步穿到边界再被拉回"的瞬移。 */
    fun step(dt: Float, ax: Float, ay: Float, dx: Float, dy: Float, impulse: Float) {
        // 冲量方向：左摇(ax<0)让封面顺时针倾向左；这里取 -ax 方向注入角速度。一次性注入。
        if (impulse > 0f) {
            val dir = if (ax != 0f) -kotlin.math.sign(ax) else 0f
            rotV += dir * impulse * SHAKE_TO_ROT
            txV  -= ax * SHAKE_TO_TX * impulse
            tyV  += ay * SHAKE_TO_TY * impulse
            // 速度上限：极端猛冲也封顶，避免单帧位移过大（瞬移的主因）。
            rotV = rotV.coerceIn(-VMAX_ROT, VMAX_ROT)
            txV  = txV.coerceIn(-VMAX_PX,  VMAX_PX)
            tyV  = tyV.coerceIn(-VMAX_PX,  VMAX_PX)
        }
        // 重力→吊点：限制在较紧的范围（吊着顶多歪这么多），让弹簧追这个目标而非原点。
        val tTx = dx.coerceIn(-TX_LIM * 0.5f, TX_LIM * 0.5f) * (SUSPEND_PX / 9.81f)
        val tTy = -dy.coerceIn(-TY_LIM * 0.5f, TY_LIM * 0.5f) * (SUSPEND_PX / 9.81f)
        val tRot = dx.coerceIn(-9.81f, 9.81f) * (SUSPEND_ROT / 9.81f)

        // 固定步长子步积分：把本帧 dt 拆成若干 <=SUB_DT 的小步，逐步积分。
        var remain = dt
        while (remain > 0f) {
            val h = if (remain > SUB_DT) SUB_DT else remain
            remain -= h
            // 带平滑越界回复力的弹簧：软限位内是纯弹簧（丝滑），冲过软限位后回复力随深度
            // 平方起步地拽回——冲越远拽越狠，杜绝冲过边界后内部"贴边傻冲、延迟弹回"。
            // 阻尼也随越界深度渐增（过界瞬间几乎不额外刹→不黏边）。
            rotV += (restoreTo(rot, tRot, ROT_LIM, kR) - (cR + extraDamp(rot, ROT_LIM)) * rotV) * h; rot += rotV * h
            txV  += (restoreTo(tx,  tTx,  TX_LIM,  kT) - (cT + extraDamp(tx,  TX_LIM))  * txV ) * h; tx  += txV  * h
            tyV  += (restoreTo(ty,  tTy,  TY_LIM,  kT) - (cT + extraDamp(ty,  TY_LIM))  * tyV ) * h; ty  += tyV  * h
        }
        // 输出用 tanh 软饱和：把内部自由摆动的值平滑压进 ±SOFT 范围。
        // 越远越逼近极限但永远到不了（斜率渐趋0），没有硬夹的"撞墙急停"。
        rotationZ = ROT_HARD * kotlin.math.tanh(rot / ROT_HARD)
        translationX = TX_HARD * kotlin.math.tanh(tx / TX_HARD)
        translationY = TY_HARD * kotlin.math.tanh(ty / TY_HARD)
    }

    /** 朝目标 target 的软限位回复力。范围内线性 -k·(x-target)；
     *  越界后的附加力随深度"平方"起步——过界瞬间力≈0，越深越紧，
     *  这样不会一过界就被猛拽（黏边）再弹回，而是柔性墙。 */
    private fun restoreTo(x: Float, target: Float, lim: Float, k: Float): Float {
        val base = -k * (x - target)
        val over = kotlin.math.abs(x) - lim
        if (over <= 0f) return base
        val t = over / lim
        return base - kOver * t * t * kotlin.math.sign(x)
    }

    private fun extraDamp(x: Float, lim: Float): Float {
        // 越界阻尼随"越界深度"平滑渐增（用越界量/软限位归一），
        // 过界一点点时几乎无额外阻尼→不会一瞬间被粘住，越深才越紧。
        val over = kotlin.math.abs(x) - lim
        if (over <= 0f) return 0f
        val t = (over / lim).coerceAtMost(1f)
        return cOver * t
    }

    fun reset() {
        rot = 0f; rotV = 0f; tx = 0f; txV = 0f; ty = 0f; tyV = 0f
        rotationZ = 0f; translationX = 0f; translationY = 0f
    }

    private companion object {
        const val SHAKE_TO_ROT = 0.42f   // 加速度 -> 角速度增益（调低=更迟钝）
        const val SHAKE_TO_TX = 1.1f     // 横向惯性平移增益
        const val SHAKE_TO_TY = 1.1f     // 上下与横向一致，上下摇也甩得开
        // 重力吊点映射：dx=9.81（满倾）时封面垂坠的最大位移/角度，按比例缩放。
        const val SUSPEND_PX = 46f
        const val SUSPEND_ROT = 4.5f
        // 子步长（帧单位，1=60fps 一帧）：拆小每步跨度，杜绝大步穿模瞬移。
        const val SUB_DT = 0.25f
        // 速度上限（每帧位移量级）：猛冲也封顶，配合子步让运动始终连续。
        const val VMAX_PX = 60f
        const val VMAX_ROT = 9f
    }
}

/** 把摇动状态应用到封面：旋转 + 小幅平移 + 轻微放大。
 *  放大让封面超出裁剪框一点，于是晃动/旋转都在框内发生、边缘不露底色。 */
fun Modifier.coverShake(shake: CoverShake, scale: Float = 1f): Modifier = graphicsLayer {
    this.scaleX = scale
    this.scaleY = scale
    rotationZ = shake.rotationZ
    translationX = shake.translationX
    translationY = shake.translationY
}