package com.jeremy.dashcam.core.detect

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure (Android-free, unit-tested) danger logic for the on-device AI detector.
 *
 * The neural network says WHAT is in the picture (car, truck, bus, motorcycle, bicycle, person) and WHERE.
 * This class decides whether one of those objects is a real danger:
 *  - objects are tracked frame to frame (box overlap);
 *  - "looming": the time-to-contact is estimated from how fast the object's apparent size grows
 *    (TTC = size / growth-rate) – a car braking hard in front of you, or a pedestrian stepping out;
 *  - only objects in our driving path (centre of the image, lower half = on the road) count –
 *    parked cars on the side and traffic far ahead are ignored;
 *  - the danger must be seen on 2 consecutive updates, then a 15 s cool-down.
 * All coordinates are normalised 0..1 in the upright image.
 */
class ApproachAlgorithm {
    enum class Level { LOW, MEDIUM, HIGH }
    var sensitivity = Level.MEDIUM

    data class Box(val label: String, val score: Float, val l: Float, val t: Float, val r: Float, val b: Float) {
        val w get() = (r - l).coerceAtLeast(0f)
        val h get() = (b - t).coerceAtLeast(0f)
        val cx get() = (l + r) / 2
        val area get() = w * h
        /** apparent size (≈ angular size) */
        val size get() = sqrt(area)
    }

    data class Danger(val label: String, val ttcSec: Float, val size: Float)

    private class Track(var box: Box, var lastSeen: Long) {
        val hist = ArrayDeque<Pair<Long, Float>>()
        var streak = 0
    }

    private val tracks = mutableListOf<Track>()
    private var lastTrigger = Long.MIN_VALUE / 2

    /** smallest TTC seen on the last update (for diagnostics / UI), null if nothing approaching */
    @Volatile var lastTtc: Float? = null; private set

    private val ttcThreshold get() = when (sensitivity) { Level.HIGH -> 1.6f; Level.MEDIUM -> 1.2f; Level.LOW -> 0.9f }

    fun reset() { tracks.clear(); lastTtc = null }

    fun process(boxes: List<Box>, tMs: Long): Danger? {
        // 1) associate detections with existing tracks (greedy, biggest first)
        val free = tracks.toMutableList()
        for (b in boxes.sortedByDescending { it.area }) {
            val best = free.filter { sameKind(it.box.label, b.label) }.maxByOrNull { iou(it.box, b) }
            if (best != null && iou(best.box, b) >= 0.25f) {
                best.box = b; best.lastSeen = tMs; free.remove(best)
                best.hist.addLast(tMs to b.size)
            } else {
                tracks += Track(b, tMs).also { it.hist.addLast(tMs to b.size) }
            }
        }
        tracks.removeAll { tMs - it.lastSeen > 700 }

        // 2) looming check per track
        var danger: Danger? = null
        var minTtc: Float? = null
        for (tr in tracks) {
            while (tr.hist.isNotEmpty() && tMs - tr.hist.first().first > 1300) tr.hist.removeFirst()
            if (tr.lastSeen != tMs || tr.hist.size < 3) { tr.streak = 0; continue }
            val ttc = ttc(tr.hist)
            if (ttc != null) minTtc = min(minTtc ?: ttc, ttc)
            val b = tr.box
            val person = b.label == "person" || b.label == "bicycle"
            val inPath = b.cx in 0.28f..0.72f || (b.l < 0.40f && b.r > 0.60f)
            val minSize = if (person) 0.09f else 0.15f
            val onRoad = b.b >= 0.45f
            val close = b.size >= minSize && onRoad
            if (ttc != null && inPath && close && ttc < ttcThreshold) tr.streak++ else tr.streak = 0
            if (tr.streak >= 2 && (danger == null || ttc!! < danger.ttcSec)) danger = Danger(b.label, ttc!!, b.size)
        }
        lastTtc = minTtc
        if (danger != null && tMs - lastTrigger > 15_000) {
            lastTrigger = tMs
            tracks.forEach { it.streak = 0 }
            return danger
        }
        return null
    }

    /** TTC = size / d(size)/dt, with the growth rate from a least-squares fit over the recent history. */
    private fun ttc(h: ArrayDeque<Pair<Long, Float>>): Float? {
        val n = h.size
        val t0 = h.first().first
        var st = 0.0; var ss = 0.0; var stt = 0.0; var sts = 0.0
        for ((t, s) in h) { val x = (t - t0) / 1000.0; st += x; ss += s; stt += x * x; sts += x * s }
        val den = n * stt - st * st
        if (den <= 1e-9) return null
        val slope = (n * sts - st * ss) / den              // size units per second
        if (slope <= 0) return null
        val now = h.last().second
        return (now / slope).toFloat()
    }

    private fun sameKind(a: String, b: String): Boolean {
        val veh = setOf("car", "truck", "bus", "motorcycle")
        return a == b || (a in veh && b in veh)
    }

    private fun iou(a: Box, b: Box): Float {
        val iw = max(0f, min(a.r, b.r) - max(a.l, b.l))
        val ih = max(0f, min(a.b, b.b) - max(a.t, b.t))
        val inter = iw * ih
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        val LABELS = listOf("car", "truck", "bus", "motorcycle", "bicycle", "person")
        fun hebrew(label: String) = when (label) {
            "car" -> "רכב"; "truck" -> "משאית"; "bus" -> "אוטובוס"
            "motorcycle" -> "אופנוע"; "bicycle" -> "אופניים"; "person" -> "הולך רגל"
            else -> label
        }
    }
}
