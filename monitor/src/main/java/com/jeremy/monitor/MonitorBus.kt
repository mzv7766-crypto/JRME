package com.jeremy.monitor

/** Tiny shared state between the sampling service and the screen. */
object MonitorBus {
    data class Sample(
        val elapsedS: Long,
        val batteryPct: Int,
        val battTempC: Float,
        val charging: Boolean,
        val currentMa: Int,
        val availMemMb: Int,
        val totalMemMb: Int,
        val lowMemory: Boolean,
        val cpuTempC: Float?,
        val drainPctPerHour: Float?, // negative = losing charge, positive = gaining
    )

    @Volatile var running: Boolean = false
    @Volatile var latest: Sample? = null
    @Volatile var csvPath: String? = null
    @Volatile var peakBattTempC: Float = 0f
    @Volatile var peakCpuTempC: Float = 0f
}
