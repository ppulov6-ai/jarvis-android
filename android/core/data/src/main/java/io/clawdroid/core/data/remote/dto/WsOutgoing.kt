package io.clawdroid.core.data.remote.dto

import kotlinx.serialization.Serializable

@Serializable
data class WsOutgoing(
    val generation: Long = 0,
    val content: String,
    val type: String? = null
)
