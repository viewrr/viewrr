@file:OptIn(ExperimentalRpcApi::class, InternalRpcApi::class)
package wtf.jobin

import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.rpc.internal.utils.InternalRpcApi
import kotlinx.rpc.protobuf.internal.GeneratedProtoMessage

@GeneratedProtoMessage
interface ClientGreeting {
    val name: String
}

@GeneratedProtoMessage
interface ServerGreeting {
    val content: String
}
