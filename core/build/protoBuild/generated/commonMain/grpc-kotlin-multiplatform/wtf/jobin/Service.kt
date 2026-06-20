@file:OptIn(ExperimentalRpcApi::class, InternalRpcApi::class)
package wtf.jobin

import kotlinx.rpc.grpc.annotations.Grpc
import kotlinx.rpc.internal.utils.ExperimentalRpcApi
import kotlinx.rpc.internal.utils.InternalRpcApi

@Grpc
interface SampleService {
    suspend fun greeting(message: ClientGreeting): ServerGreeting
}
