package com.example.model

import java.util.UUID

data class VectorLayer(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Layer 1",
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var opacity: Float = 1f,
    // Note: Untuk reaktivitas state Compose yang lebih efisien dan performa ringan,
    // daftar objek ("MutableList<VectorObject>") diikat menggunakan properti 'layerId'
    // yang terdapat di dalam VectorShape. Objects dapat di-filter secara on-the-fly.
) 
